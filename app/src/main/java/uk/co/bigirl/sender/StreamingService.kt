package uk.co.bigirl.sender

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import android.view.SurfaceView
import androidx.core.app.NotificationCompat
import com.pedro.common.ConnectChecker
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
        val bitrateKbps: Long = 0,
        val endpoint: String = "",
    )

    inner class LocalBinder : Binder() {
        fun service(): StreamingService = this@StreamingService
    }

    companion object {
        private const val TAG = "BIG_IRL_V2"
        private const val CHANNEL_ID = "big_irl_streaming"
        private const val NOTIFICATION_ID = 1001
    }

    private val binder = LocalBinder()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val listeners = CopyOnWriteArraySet<Listener>()

    private lateinit var stream: GenericStream
    private var state = StreamState()
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        stream = GenericStream(applicationContext, this).apply {
            getGlInterface().autoHandleOrientation = true
        }
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, notification(state.message))
        acquireWakeLock()
        return START_STICKY
    }

    fun addListener(listener: Listener) {
        listeners += listener
        listener.onStreamState(state)
    }

    fun removeListener(listener: Listener) {
        listeners -= listener
    }

    fun currentState(): StreamState = state

    fun prepareAndAttachPreview(
        surfaceView: SurfaceView,
        profile: StreamProfile = StreamProfile(),
    ): Boolean {
        return try {
            if (!state.isPrepared) {
                update("Preparing camera and microphone")
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
                    update("Encoder preparation failed")
                    return false
                }
                state = state.copy(isPrepared = true)
            }

            if (stream.isOnPreview) stream.stopPreview()
            stream.startPreview(surfaceView)
            update("Camera ready")
            true
        } catch (error: Throwable) {
            Log.e(TAG, "Preview preparation failed", error)
            update("Camera error: ${error.message ?: error.javaClass.simpleName}")
            false
        }
    }

    fun detachPreview() {
        runCatching {
            if (stream.isOnPreview) stream.stopPreview()
        }.onFailure { Log.w(TAG, "Could not detach preview", it) }
    }

    fun updatePreviewSize(width: Int, height: Int) {
        if (!state.isPrepared || width <= 0 || height <= 0) return
        runCatching {
            stream.getGlInterface().setPreviewResolution(width, height)
        }.onFailure { Log.w(TAG, "Could not resize preview", it) }
    }

    fun startSrt(endpoint: String): Boolean {
        val clean = endpoint.trim()
        if (!clean.startsWith("srt://", ignoreCase = true)) {
            update("SRT endpoint must begin srt://")
            return false
        }
        if (!state.isPrepared) {
            update("Start camera preview first")
            return false
        }
        if (stream.isStreaming) return true

        return try {
            state = state.copy(endpoint = clean)
            update("Connecting to $clean")
            Log.i(TAG, "Starting SRT: $clean")
            stream.startStream(clean)
            true
        } catch (error: Throwable) {
            Log.e(TAG, "SRT start failed", error)
            update("SRT start error: ${error.message ?: error.javaClass.simpleName}")
            false
        }
    }

    fun stopStream() {
        runCatching {
            if (stream.isStreaming) stream.stopStream()
        }.onFailure { Log.w(TAG, "Stop stream failed", it) }
        state = state.copy(isStreaming = false, bitrateKbps = 0)
        update("Stream stopped")
    }

    private fun update(message: String) {
        state = state.copy(
            message = message,
            isStreaming = runCatching { stream.isStreaming }.getOrDefault(state.isStreaming),
        )
        publishState()
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, notification(message))
    }

    override fun onConnectionStarted(url: String) {
        Log.i(TAG, "Connection started: $url")
        update("SRT handshake started")
    }

    override fun onConnectionSuccess() {
        Log.i(TAG, "SRT connected")
        state = state.copy(isStreaming = true)
        update("Live over SRT")
    }

    override fun onConnectionFailed(reason: String) {
        val detail = reason.ifBlank { "RootEncoder returned no reason" }
        Log.e(TAG, "SRT connection failed: $detail")
        state = state.copy(isStreaming = false, bitrateKbps = 0)
        update("SRT failed: $detail")
    }

    override fun onNewBitrate(bitrate: Long) {
        state = state.copy(
            message = "Sending ${bitrate / 1000} Kbps",
            isStreaming = true,
            bitrateKbps = bitrate / 1000,
        )
        publishState()
    }

    private fun publishState() {
        val snapshot = state
        mainHandler.post { listeners.forEach { it.onStreamState(snapshot) } }
    }

    override fun onDisconnect() {
        Log.w(TAG, "SRT disconnected")
        state = state.copy(isStreaming = false, bitrateKbps = 0)
        update("Disconnected")
    }

    override fun onAuthError() = update("Authentication failed")
    override fun onAuthSuccess() = update("Authenticated")

    override fun onDestroy() {
        runCatching { if (stream.isStreaming) stream.stopStream() }
        runCatching { if (stream.isOnPreview) stream.stopPreview() }
        runCatching { stream.release() }
        wakeLock?.takeIf { it.isHeld }?.release()
        wakeLock = null
        super.onDestroy()
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val power = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = power.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "BIGIRL:StreamingWakeLock",
        ).apply { acquire() }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "BIG IRL streaming",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "Keeps camera, microphone and SRT streaming active"
                setShowBadge(false)
            }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun notification(message: String): android.app.Notification {
        val launchIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.presence_video_online)
            .setContentTitle("BIG IRL APP V2")
            .setContentText(message)
            .setOngoing(state.isStreaming)
            .setContentIntent(pendingIntent)
            .setOnlyAlertOnce(true)
            .build()
    }
}
