package uk.co.bigirl.sender

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import android.view.SurfaceView
import androidx.core.app.NotificationCompat
import com.pedro.common.ConnectChecker
import com.pedro.encoder.input.sources.video.Camera2Source
import com.pedro.encoder.utils.gl.AspectRatioMode
import com.pedro.library.generic.GenericStream
import java.util.concurrent.CopyOnWriteArraySet

class StreamingService : Service(), ConnectChecker {

    interface Listener {
        fun onStreamState(state: StreamState)
    }

    data class StreamState(
        val message: String = "Ready",
        val isPrepared: Boolean = false,
        val isStreaming: Boolean = false,
        val wantsStreaming: Boolean = false,
        val isReconnecting: Boolean = false,
        val bitrateKbps: Long = 0,
        val endpoint: String = "",
        val profile: StreamProfile = StreamProfile.MAXIMUM_1080P60,
        val torchEnabled: Boolean = false,
    )

    inner class LocalBinder : Binder() {
        fun service(): StreamingService = this@StreamingService
    }

    companion object {
        private const val TAG = "BIG_IRL_V2"
        private const val CHANNEL_ID = "big_irl_streaming"
        private const val NOTIFICATION_ID = 1001
        const val ACTION_KILL_STREAM = "uk.co.bigirl.sender.KILL_STREAM"

        private val RETRY_DELAYS_MS =
            longArrayOf(0, 500, 1_000, 2_000, 5_000)
    }

    private val binder = LocalBinder()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val listeners = CopyOnWriteArraySet<Listener>()

    private lateinit var stream: GenericStream

    private var state = StreamState()
    private var wakeLock: PowerManager.WakeLock? = null
    private var activeSurface: SurfaceView? = null
    private var retryIndex = 0
    private var retryRunnable: Runnable? = null
    private var userStopping = false
    private var previewAttachInProgress = false

    override fun onCreate() {
        super.onCreate()

        createStream()
        createNotificationChannel()
    }

    private fun createStream() {
        stream = GenericStream(
            applicationContext,
            this,
        ).apply {
            getGlInterface().autoHandleOrientation = true
            getGlInterface().setAspectRatioMode(
                AspectRatioMode.Adjust,
            )
        }
    }

    override fun onBind(intent: Intent?): IBinder {
        return binder
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int {
        if (intent?.action == ACTION_KILL_STREAM) {
            stopStream()
            stopSelf()
            return START_NOT_STICKY
        }
        startForeground(NOTIFICATION_ID, notification(state.message))
        acquireWakeLock()
        return START_NOT_STICKY
    }

    fun addListener(listener: Listener) {
        listeners += listener
        listener.onStreamState(state)
    }

    fun removeListener(listener: Listener) {
        listeners -= listener
    }

    fun currentState(): StreamState {
        return state
    }

    fun prepareAndAttachPreview(
        surfaceView: SurfaceView,
        profile: StreamProfile = state.profile,
    ): Boolean {
        if (previewAttachInProgress) {
            return true
        }

        if (
            activeSurface === surfaceView &&
            stream.isOnPreview
        ) {
            updatePreviewSize(
                surfaceView.width,
                surfaceView.height,
            )

            return true
        }

        previewAttachInProgress = true
        activeSurface = surfaceView

        return try {
            if (
                state.isPrepared &&
                profile != state.profile &&
                !state.wantsStreaming
            ) {
                rebuildForProfile(profile)
            }

            if (!state.isPrepared) {
                updateMessage(
                    "Preparing ${profile.label}",
                )

                val videoReady = stream.prepareVideo(
                    profile.width,
                    profile.height,
                    profile.videoBitrate,
                    profile.fps,
                )

                val audioReady = stream.prepareAudio(
                    profile.sampleRate,
                    true,
                    profile.audioBitrate,
                )

                if (!videoReady || !audioReady) {
                    updateMessage(
                        "Encoder preparation failed",
                    )

                    return false
                }

                state = state.copy(
                    isPrepared = true,
                    profile = profile,
                )
            }

            if (stream.isOnPreview) {
                stream.stopPreview()
            }

            stream.startPreview(surfaceView)

            if (
                surfaceView.width > 0 &&
                surfaceView.height > 0
            ) {
                stream.getGlInterface()
                    .setPreviewResolution(
                        surfaceView.width,
                        surfaceView.height,
                    )
            }

            restoreTorchIfRequested()

            if (!state.wantsStreaming) {
                updateMessage(
                    "Camera ready | ${state.profile.label}",
                )
            } else {
                publishState()
            }

            true
        } catch (error: Throwable) {
            Log.e(
                TAG,
                "Preview preparation failed",
                error,
            )

            updateMessage(
                "Camera error: " +
                    (
                        error.message
                            ?: error.javaClass.simpleName
                    ),
            )

            false
        } finally {
            previewAttachInProgress = false
        }
    }

    fun selectProfile(
        profile: StreamProfile,
    ): Boolean {
        if (state.wantsStreaming) {
            updateMessage(
                "Stop streaming before changing profile",
            )

            return false
        }

        if (
            profile == state.profile &&
            state.isPrepared
        ) {
            return true
        }

        return try {
            rebuildForProfile(profile)

            activeSurface?.let { surface ->
                prepareAndAttachPreview(
                    surface,
                    profile,
                )
            } ?: run {
                state = state.copy(
                    profile = profile,
                )

                publishState()

                true
            }
        } catch (error: Throwable) {
            Log.e(
                TAG,
                "Profile change failed",
                error,
            )

            updateMessage(
                "Profile error: " +
                    (
                        error.message
                            ?: error.javaClass.simpleName
                    ),
            )

            false
        }
    }

    private fun rebuildForProfile(
        profile: StreamProfile,
    ) {
        val restoreTorch = state.torchEnabled

        cancelRetry()

        runCatching {
            if (stream.isStreaming) {
                stream.stopStream()
            }
        }

        runCatching {
            if (stream.isOnPreview) {
                stream.stopPreview()
            }
        }

        runCatching {
            stream.release()
        }

        createStream()

        state = state.copy(
            isPrepared = false,
            isStreaming = false,
            wantsStreaming = false,
            isReconnecting = false,
            bitrateKbps = 0,
            profile = profile,
            torchEnabled = restoreTorch,
        )
    }

    fun detachPreview() {
        runCatching {
            if (stream.isOnPreview) {
                stream.stopPreview()
            }
        }.onFailure { error ->
            Log.w(
                TAG,
                "Could not detach preview",
                error,
            )
        }

        activeSurface = null
    }

    fun toggleTorch(): Boolean {
        val cameraSource =
            stream.videoSource as? Camera2Source

        if (cameraSource == null) {
            updateMessage(
                "Torch unavailable for current camera source",
            )

            return false
        }

        return try {
            val enabled = !state.torchEnabled

            if (enabled) {
                cameraSource.enableLantern()
            } else {
                cameraSource.disableLantern()
            }

            state = state.copy(
                torchEnabled = enabled,
            )

            publishState()

            true
        } catch (error: Throwable) {
            Log.e(
                TAG,
                "Torch error",
                error,
            )

            updateMessage(
                "Torch error: " +
                    (
                        error.message
                            ?: error.javaClass.simpleName
                    ),
            )

            false
        }
    }

    private fun restoreTorchIfRequested() {
        if (!state.torchEnabled) {
            return
        }

        runCatching {
            (
                stream.videoSource as? Camera2Source
            )?.enableLantern()
        }.onFailure { error ->
            Log.w(
                TAG,
                "Could not restore torch",
                error,
            )

            state = state.copy(
                torchEnabled = false,
            )

            updateMessage(
                "Torch restore error: " +
                    (
                        error.message
                            ?: error.javaClass.simpleName
                    ),
            )
        }
    }

    fun updatePreviewSize(
        width: Int,
        height: Int,
    ) {
        if (
            !state.isPrepared ||
            width <= 0 ||
            height <= 0
        ) {
            return
        }

        runCatching {
            stream.getGlInterface()
                .setPreviewResolution(
                    width,
                    height,
                )
        }.onFailure { error ->
            Log.w(
                TAG,
                "Could not resize preview",
                error,
            )
        }
    }

    fun startSrt(
        endpoint: String,
    ): Boolean {
        val clean = endpoint.trim()

        /*
         * Use java.net.URI instead of the previous
         * IPv4-only regular expression.
         *
         * This accepts both:
         *
         * srt://192.168.1.27:10000/live
         *
         * and:
         *
         * srt://[2a0e:cb01:e9:cf00:4ab0:2dff:fe2f:610e]:10000/live
         */
        val parsedEndpoint =
            runCatching {
                java.net.URI(clean)
            }.getOrNull()

        val validEndpoint =
            parsedEndpoint?.scheme.equals(
                "srt",
                ignoreCase = true,
            ) &&
                !parsedEndpoint?.host
                    .isNullOrBlank() &&
                (
                    parsedEndpoint?.port
                        ?: -1
                    ) in 1..65535 &&
                !parsedEndpoint?.path
                    .isNullOrBlank() &&
                parsedEndpoint?.path != "/"

        if (!validEndpoint) {
            updateMessage(
                "Use srt://host:port/streamid " +
                    "(IPv6 must use [brackets])",
            )

            return false
        }

        if (!state.isPrepared) {
            updateMessage(
                "Start camera preview first",
            )

            return false
        }

        userStopping = false
        retryIndex = 0

        state = state.copy(
            endpoint = clean,
            wantsStreaming = true,
            isReconnecting = false,
            bitrateKbps = 0,
        )

        return connectNow()
    }

    private fun connectNow(): Boolean {
        if (
            !state.wantsStreaming ||
            state.endpoint.isBlank()
        ) {
            return false
        }

        cancelRetry()

        return try {
            if (stream.isStreaming) {
                stream.stopStream()
            }

            val message =
                if (retryIndex == 0) {
                    "Connecting to StreamBox"
                } else {
                    "Reconnecting | attempt " +
                        "${retryIndex + 1}"
                }

            state = state.copy(
                isStreaming = false,
                isReconnecting = retryIndex > 0,
                bitrateKbps = 0,
            )

            updateMessage(message)

            Log.i(
                TAG,
                "Starting SRT: ${state.endpoint}",
            )

            stream.startStream(
                state.endpoint,
            )

            true
        } catch (error: Throwable) {
            Log.e(
                TAG,
                "SRT start failed",
                error,
            )

            scheduleReconnect(
                error.message
                    ?: error.javaClass.simpleName,
            )

            false
        }
    }

    private fun scheduleReconnect(
        reason: String,
    ) {
        if (
            !state.wantsStreaming ||
            userStopping
        ) {
            return
        }

        cancelRetry()

        val delay =
            RETRY_DELAYS_MS[
                retryIndex.coerceAtMost(
                    RETRY_DELAYS_MS.lastIndex,
                )
            ]

        state = state.copy(
            isStreaming = false,
            isReconnecting = true,
            bitrateKbps = 0,
        )

        updateMessage(
            "Reconnecting in ${delay}ms | " +
                reason.ifBlank {
                    "connection lost"
                },
        )

        retryRunnable = Runnable {
            retryIndex =
                (retryIndex + 1).coerceAtMost(
                    RETRY_DELAYS_MS.lastIndex,
                )

            connectNow()
        }.also { runnable ->
            mainHandler.postDelayed(
                runnable,
                delay,
            )
        }
    }

    private fun cancelRetry() {
        retryRunnable?.let { runnable ->
            mainHandler.removeCallbacks(runnable)
        }

        retryRunnable = null
    }

    fun stopStream() {
        userStopping = true

        cancelRetry()

        state = state.copy(
            wantsStreaming = false,
            isReconnecting = false,
            bitrateKbps = 0,
        )

        runCatching {
            if (stream.isStreaming) {
                stream.stopStream()
            }
        }.onFailure { error ->
            Log.w(
                TAG,
                "Stop stream failed",
                error,
            )
        }

        state = state.copy(
            isStreaming = false,
        )

        updateMessage(
            "Stream stopped",
        )
    }

    private fun updateMessage(
        message: String,
    ) {
        state = state.copy(
            message = message,
        )

        publishState()

        getSystemService(
            NotificationManager::class.java,
        ).notify(
            NOTIFICATION_ID,
            notification(message),
        )
    }

    override fun onConnectionStarted(
        url: String,
    ) {
        Log.i(
            TAG,
            "Connection started: $url",
        )

        if (state.wantsStreaming) {
            updateMessage(
                "SRT handshake started",
            )
        }
    }

    override fun onConnectionSuccess() {
        Log.i(TAG, "SRT connected")
        retryIndex = 0
        state = state.copy(isStreaming = true, isReconnecting = false)
        updateMessage("Live over SRT")

        // Give the receiver a fresh decoder entry point as soon as SRT is established.
        // RootEncoder prepends the current codec configuration when requesting a keyframe.
        runCatching { stream.requestKeyframe() }
            .onFailure { Log.w(TAG, "Immediate keyframe request failed", it) }

        mainHandler.postDelayed({
            if (state.wantsStreaming && stream.isStreaming) {
                runCatching { stream.requestKeyframe() }
                    .onFailure { Log.w(TAG, "Follow-up keyframe request failed", it) }
            }
        }, 750L)
    }

    override fun onConnectionFailed(
        reason: String,
    ) {
        val detail =
            reason.ifBlank {
                "connection failed"
            }

        Log.e(
            TAG,
            "SRT connection failed: $detail",
        )

        state = state.copy(
            isStreaming = false,
            bitrateKbps = 0,
        )

        if (
            state.wantsStreaming &&
            !userStopping
        ) {
            scheduleReconnect(detail)
        } else {
            updateMessage(
                "SRT failed: $detail",
            )
        }
    }

    override fun onNewBitrate(
        bitrate: Long,
    ) {
        if (!state.wantsStreaming) {
            return
        }

        state = state.copy(
            message =
                "Sending ${bitrate / 1000} Kbps",
            isStreaming = true,
            isReconnecting = false,
            bitrateKbps = bitrate / 1000,
        )

        publishState()
    }

    private fun publishState() {
        val snapshot = state

        mainHandler.post {
            listeners.forEach { listener ->
                listener.onStreamState(snapshot)
            }
        }
    }

    override fun onDisconnect() {
        Log.w(
            TAG,
            "SRT disconnected",
        )

        state = state.copy(
            isStreaming = false,
            bitrateKbps = 0,
        )

        if (
            state.wantsStreaming &&
            !userStopping
        ) {
            scheduleReconnect(
                "disconnected",
            )
        } else {
            updateMessage(
                "Disconnected",
            )
        }
    }

    override fun onAuthError() {
        updateMessage(
            "Authentication failed",
        )
    }

    override fun onAuthSuccess() {
        updateMessage(
            "Authenticated",
        )
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // Swiping the app away is an explicit emergency stop.
        stopStream()
        stopSelf()
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        userStopping = true

        cancelRetry()

        runCatching {
            (
                stream.videoSource as? Camera2Source
            )?.disableLantern()
        }

        state = state.copy(
            torchEnabled = false,
        )

        runCatching {
            if (stream.isStreaming) {
                stream.stopStream()
            }
        }

        runCatching {
            if (stream.isOnPreview) {
                stream.stopPreview()
            }
        }

        runCatching {
            stream.release()
        }

        wakeLock
            ?.takeIf { it.isHeld }
            ?.release()

        wakeLock = null

        super.onDestroy()
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) {
            return
        }

        val power =
            getSystemService(
                Context.POWER_SERVICE,
            ) as PowerManager

        wakeLock = power.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "BIGIRL:StreamingWakeLock",
        ).apply {
            acquire()
        }
    }

    private fun createNotificationChannel() {
        if (
            Build.VERSION.SDK_INT >=
            Build.VERSION_CODES.O
        ) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "BIG IRL streaming",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description =
                    "Keeps camera, microphone " +
                        "and SRT streaming active"

                setShowBadge(false)
            }

            getSystemService(
                NotificationManager::class.java,
            ).createNotificationChannel(
                channel,
            )
        }
    }

    private fun notification(
        message: String,
    ): android.app.Notification {
        val launchIntent =
            Intent(
                this,
                MainActivity::class.java,
            )

        val pendingIntent =
            PendingIntent.getActivity(
                this,
                0,
                launchIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or
                    PendingIntent.FLAG_IMMUTABLE,
            )

        val killIntent = Intent(this, StreamingService::class.java).apply {
            action = ACTION_KILL_STREAM
        }
        val killPendingIntent = PendingIntent.getService(
            this, 1002, killIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(
            this,
            CHANNEL_ID,
        )
            .setSmallIcon(
                android.R.drawable
                    .presence_video_online,
            )
            .setContentTitle(
                "BIG IRL APP V2",
            )
            .setContentText(message)
            .setOngoing(
                state.wantsStreaming,
            )
            .setContentIntent(
                pendingIntent,
            )
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "KILL STREAM", killPendingIntent)
            .setOnlyAlertOnce(true)
            .build()
    }
}
