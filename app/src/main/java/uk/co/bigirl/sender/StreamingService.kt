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
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.SystemClock
import android.view.SurfaceView
import androidx.core.app.NotificationCompat
import com.pedro.common.ConnectChecker
import com.pedro.encoder.input.sources.video.Camera2Source
import com.pedro.encoder.utils.gl.AspectRatioMode
import com.pedro.library.base.StreamBase
import com.pedro.library.generic.GenericStream
import java.net.Inet4Address
import java.util.concurrent.CopyOnWriteArraySet

class StreamingService : Service(), ConnectChecker {

    interface Listener {
        fun onStreamState(state: StreamState)
    }

    private fun updateBondedLinkCount(count: Int) {
        mainHandler.post {
            if (state.transportMode != TransportMode.BONDED_SRT) return@post
            state = state.copy(bondedLinksConnected = count.coerceIn(0, 2))
            if (state.isStreaming && count > 0) {
                updateMessage("Live over Bonded SRT | ${state.bondedLinksConnected}/2 links")
            } else {
                publishState()
            }
        }
    }

    private fun updateRistLinkCount(count: Int) {
        mainHandler.post {
            if (state.transportMode !in setOf(TransportMode.RIST, TransportMode.RIST_MULTIPATH)) {
                return@post
            }
            if (!state.wantsStreaming) return@post
            val maxLinks = if (state.transportMode == TransportMode.RIST_MULTIPATH) 2 else 1
            state = state.copy(ristLinksConnected = count.coerceIn(0, maxLinks))
            if (state.isStreaming && count > 0) {
                updateMessage("Live over RIST | ${state.ristLinksConnected}/$maxLinks links")
            } else {
                publishState()
            }
        }
    }

    private fun updateSrtlaLinkCount(count: Int, total: Int) {
        mainHandler.post {
            if (state.transportMode != TransportMode.SRTLA || !state.wantsStreaming) return@post
            val previousCount = state.srtlaLinksConnected
            val previousTotal = state.srtlaLinksTotal
            state = state.copy(
                srtlaLinksConnected = count.coerceIn(0, total),
                srtlaLinksTotal = total,
            )
            if (
                state.isStreaming &&
                (previousCount != state.srtlaLinksConnected || previousTotal != total)
            ) {
                updateMessage(
                    "SRTLA | ${state.srtlaLinksConnected}/${state.srtlaLinksTotal} receiver links",
                )
            } else {
                publishState()
            }
        }
    }

    data class StreamState(
        val message: String = "Ready",
        val isPrepared: Boolean = false,
        val isStreaming: Boolean = false,
        val wantsStreaming: Boolean = false,
        val isReconnecting: Boolean = false,
        val bitrateKbps: Long = 0,
        val requestedBitrateKbps: Long = 8_000,
        val endpoint: String = "",
        val profile: StreamProfile = StreamProfile.MAXIMUM_1080P60,
        val transportMode: TransportMode = TransportMode.SINGLE_SRT,
        val bondedLinksConnected: Int = 0,
        val ristLinksConnected: Int = 0,
        val srtlaLinksConnected: Int = 0,
        val srtlaLinksTotal: Int = 0,
        val torchEnabled: Boolean = false,
    )

    inner class LocalBinder : Binder() {
        fun service(): StreamingService = this@StreamingService
    }

    companion object {
        private const val TAG = "BIG_IRL_V2"
        private const val CHANNEL_ID = "big_irl_streaming"
        private const val NOTIFICATION_ID = 1001
        private const val SRTLA_POLL_INTERVAL_MS = 500L
        private const val SRTLA_LINK_TIMEOUT_MS = 15_000L
        const val ACTION_KILL_STREAM = "uk.co.bigirl.sender.KILL_STREAM"

        private val RETRY_DELAYS_MS =
            longArrayOf(0, 500, 1_000, 2_000, 5_000)
    }

    private val binder = LocalBinder()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val listeners = CopyOnWriteArraySet<Listener>()

    private lateinit var stream: StreamBase

    private var state = StreamState()
    private var wakeLock: PowerManager.WakeLock? = null
    private var activeSurface: SurfaceView? = null
    private var retryIndex = 0
    private var retryRunnable: Runnable? = null
    private var userStopping = false
    private var previewAttachInProgress = false
    private var appForeground = true
    private val connectivityManager by lazy {
        getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
    }
    private var cellularNetwork: Network? = null
    private var cellularRequestActive = false
    private var wifiNetwork: Network? = null
    private var wifiRequestActive = false
    private var srtlaProxy: SrtlaProxy? = null
    private var srtlaPoll: Runnable? = null
    private val cellularCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            mainHandler.post {
                cellularNetwork = network
                if (!state.wantsStreaming) return@post
                connectNow()
            }
        }

        override fun onUnavailable() {
            mainHandler.post {
                if (cellularNetwork == null && state.wantsStreaming) {
                    updateMessage("SIM network unavailable")
                }
            }
        }

        override fun onLost(network: Network) {
            mainHandler.post {
                if (cellularNetwork != network) return@post
                cellularNetwork = null
                if (state.wantsStreaming) {
                    reconnectForNetworkChange("SIM lost")
                }
            }
        }
    }

    private val wifiCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            mainHandler.post {
                wifiNetwork = network
                if (
                    state.wantsStreaming &&
                    state.transportMode in setOf(
                        TransportMode.BONDED_SRT,
                        TransportMode.SRTLA,
                        TransportMode.RIST_MULTIPATH,
                    )
                ) {
                    connectNow()
                }
            }
        }

        override fun onLost(network: Network) {
            mainHandler.post {
                if (wifiNetwork != network) return@post
                wifiNetwork = null
                if (
                    state.wantsStreaming &&
                    state.transportMode in setOf(
                        TransportMode.BONDED_SRT,
                        TransportMode.SRTLA,
                        TransportMode.RIST_MULTIPATH,
                    )
                ) {
                    reconnectForNetworkChange("Wi-Fi lost")
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        RistDiagnostics.append(this, "service created")

        createStream(state.transportMode)
        createNotificationChannel()
    }

    private fun createStream(mode: TransportMode) {
        stream = when (mode) {
            TransportMode.SINGLE_SRT -> GenericStream(applicationContext, this)
            TransportMode.RIST -> RistStream(
                applicationContext,
                this,
                { Pair(null, cellularNetwork?.networkHandle) },
                multipath = false,
                onLinkCountChanged = ::updateRistLinkCount,
            )
            TransportMode.RIST_MULTIPATH -> RistStream(
                applicationContext,
                this,
                { Pair(wifiNetwork?.networkHandle, cellularNetwork?.networkHandle) },
                multipath = true,
                onLinkCountChanged = ::updateRistLinkCount,
            )
            TransportMode.SRTLA -> GenericStream(applicationContext, this)
            TransportMode.BONDED_SRT -> SrtBondedStream(
                applicationContext,
                this,
                { Pair(wifiNetwork?.networkHandle, cellularNetwork?.networkHandle) },
                ::updateBondedLinkCount,
            )
        }.apply {
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

    fun setAppForeground(foreground: Boolean) {
        appForeground = foreground
        if (foreground) refreshPreview()
    }

    fun refreshPreview() {
        activeSurface?.let { surface ->
            if (surface.holder.surface.isValid) {
                mainHandler.post { prepareAndAttachPreview(surface, state.profile) }
            }
        }
    }

    private fun holdCellularWhileStreaming(): Boolean {
        if (cellularRequestActive) return true
        val request = android.net.NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
            .build()
        return runCatching {
            connectivityManager.requestNetwork(request, cellularCallback)
            cellularRequestActive = true
            true
        }.getOrElse { error ->
            Log.e(TAG, "Could not request cellular network", error)
            updateMessage("Could not request SIM network: ${error.message ?: "unknown error"}")
            false
        }
    }

    private fun releaseCellularHold() {
        if (!cellularRequestActive) return
        runCatching { connectivityManager.unregisterNetworkCallback(cellularCallback) }
        cellularRequestActive = false
        cellularNetwork = null
    }

    private fun holdNetworksWhileStreaming(mode: TransportMode): Boolean {
        if (!holdCellularWhileStreaming()) return false
        if (
            mode !in setOf(
                TransportMode.BONDED_SRT,
                TransportMode.SRTLA,
                TransportMode.RIST_MULTIPATH,
            ) ||
            wifiRequestActive
        ) return true

        val request = android.net.NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .build()
        return runCatching {
            connectivityManager.registerNetworkCallback(request, wifiCallback)
            wifiRequestActive = true
            true
        }.getOrElse { error ->
            Log.e(TAG, "Could not observe Wi-Fi network", error)
            releaseCellularHold()
            updateMessage("Could not observe Wi-Fi network: ${error.message ?: "unknown error"}")
            false
        }
    }

    private fun releaseWifiHold() {
        if (!wifiRequestActive) return
        runCatching { connectivityManager.unregisterNetworkCallback(wifiCallback) }
        wifiRequestActive = false
        wifiNetwork = null
    }

    private fun reconnectForNetworkChange(reason: String) {
        runCatching {
            if (stream.isStreaming) stream.stopStream()
        }.onFailure { error ->
            Log.w(TAG, "Could not stop transport after network change", error)
        }
        stopSrtlaProxy()
        state = state.copy(
            isStreaming = false,
            isReconnecting = true,
            bitrateKbps = 0,
            bondedLinksConnected = 0,
            ristLinksConnected = 0,
            srtlaLinksConnected = 0,
            srtlaLinksTotal = 0,
        )
        if (hasRequiredNetworks(state.transportMode)) {
            connectNow()
        } else {
            updateMessage("$reason; waiting for a usable network path")
        }
    }

    private fun hasRequiredNetworks(mode: TransportMode): Boolean =
        when (mode) {
            TransportMode.SINGLE_SRT, TransportMode.RIST -> cellularNetwork != null
            TransportMode.BONDED_SRT -> cellularNetwork != null && wifiNetwork != null
            TransportMode.SRTLA, TransportMode.RIST_MULTIPATH ->
                cellularNetwork != null || wifiNetwork != null
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

    fun ristDiagnosticReport(): String = RistDiagnostics.report(this, state)

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

        createStream(state.transportMode)

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

    fun selectTransport(mode: TransportMode): Boolean {
        if (state.wantsStreaming) {
            updateMessage("Stop streaming before changing transport")
            return false
        }
        if (mode == state.transportMode) return true

        val restoreTorch = state.torchEnabled
        cancelRetry()
        runCatching {
            if (stream.isStreaming) stream.stopStream()
        }.onFailure { error ->
            Log.w(TAG, "Could not stop previous transport", error)
        }
        stopSrtlaProxy()
        runCatching {
            if (stream.isOnPreview) stream.stopPreview()
        }.onFailure { error ->
            Log.w(TAG, "Could not stop previous preview", error)
        }
        runCatching {
            stream.release()
        }.onFailure { error ->
            Log.w(TAG, "Could not release previous transport", error)
        }

        state = state.copy(
            transportMode = mode,
            isPrepared = false,
            isStreaming = false,
            wantsStreaming = false,
            isReconnecting = false,
            bitrateKbps = 0,
            bondedLinksConnected = 0,
            ristLinksConnected = 0,
            srtlaLinksConnected = 0,
            srtlaLinksTotal = 0,
            torchEnabled = restoreTorch,
        )
        createStream(mode)

        val preview = activeSurface
        if (preview != null && preview.holder.surface.isValid) {
            return prepareAndAttachPreview(preview, state.profile)
        }
        updateMessage("${mode.label} selected")
        return true
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
    ): Boolean = startTransport(TransportMode.SINGLE_SRT, endpoint)

    fun startTransport(
        mode: TransportMode,
        endpoint: String,
    ): Boolean {
        val clean = endpoint.trim()
        if (mode == TransportMode.RIST || mode == TransportMode.RIST_MULTIPATH) {
            RistDiagnostics.append(this, "start requested | mode=$mode endpoint=$clean")
        }

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

        val validEndpoint = when (mode) {
            TransportMode.SINGLE_SRT ->
                parsedEndpoint?.scheme.equals(mode.scheme, ignoreCase = true) &&
                    !parsedEndpoint?.host.isNullOrBlank() &&
                    (parsedEndpoint?.port ?: -1) in 1..65535 &&
                    !parsedEndpoint?.path.isNullOrBlank() &&
                    parsedEndpoint?.path != "/"
            TransportMode.RIST, TransportMode.RIST_MULTIPATH ->
                parsedEndpoint?.scheme.equals(mode.scheme, ignoreCase = true) &&
                    !parsedEndpoint?.host.isNullOrBlank() &&
                    (parsedEndpoint?.port ?: -1) in 1..65535
            TransportMode.BONDED_SRT ->
                BondingConfig.parseEndpoint(clean) != null
            TransportMode.SRTLA ->
                SrtlaConfig.parseEndpoint(clean) != null
        }

        if (!validEndpoint) {
            val example = when (mode) {
                TransportMode.SINGLE_SRT -> "srt://host:port/streamid"
                TransportMode.RIST, TransportMode.RIST_MULTIPATH -> "rist://host:port"
                TransportMode.BONDED_SRT -> BondingConfig().endpoint
                TransportMode.SRTLA -> TransportMode.SRTLA.defaultEndpoint
            }
            updateMessage("Use $example (IPv6 must use [brackets])")
            return false
        }

        if (mode != state.transportMode && !selectTransport(mode)) return false

        if (!state.isPrepared) {
            updateMessage(
                "Start camera preview first",
            )

            return false
        }

        userStopping = false
        retryIndex = 0

        if (!holdNetworksWhileStreaming(mode)) return false
        state = state.copy(
            transportMode = mode,
            endpoint = clean,
            wantsStreaming = true,
            isReconnecting = false,
            bitrateKbps = 0,
            bondedLinksConnected = 0,
            ristLinksConnected = 0,
            srtlaLinksConnected = 0,
            srtlaLinksTotal = 0,
            requestedBitrateKbps = state.profile.videoBitrate / 1000L,
        )

        val started = connectNow()
        return started
    }

    private fun connectNow(): Boolean {
        if (
            !state.wantsStreaming ||
            state.endpoint.isBlank()
        ) {
            return false
        }

        if (!hasRequiredNetworks(state.transportMode)) {
            state = state.copy(
                isStreaming = false,
                isReconnecting = true,
                bitrateKbps = 0,
            )
            val reason = when (state.transportMode) {
                TransportMode.SINGLE_SRT, TransportMode.RIST -> "Waiting for SIM network"
                TransportMode.BONDED_SRT -> "Waiting for Wi-Fi and SIM networks"
                TransportMode.SRTLA, TransportMode.RIST_MULTIPATH ->
                    "Waiting for Wi-Fi or SIM network"
            }
            updateMessage(reason)
            return true
        }
        cancelRetry()

        return try {
            if (stream.isStreaming) {
                stream.stopStream()
            }
            stopSrtlaProxy()

            val message = if (retryIndex == 0) {
                "Connecting over ${state.transportMode.label}"
            } else {
                "Reconnecting | attempt ${retryIndex + 1}"
            }

            state = state.copy(
                isStreaming = false,
                isReconnecting = retryIndex > 0,
                bitrateKbps = 0,
            )

            updateMessage(message)

            Log.i(TAG, "Starting ${state.transportMode.label}: ${state.endpoint}")

            val outputEndpoint = if (state.transportMode == TransportMode.SRTLA) {
                startSrtlaProxy(state.endpoint)
            } else {
                state.endpoint
            }
            stream.startStream(
                outputEndpoint,
            )

            true
        } catch (error: Throwable) {
            Log.e(TAG, "${state.transportMode.label} start failed", error)
            if (state.transportMode == TransportMode.RIST || state.transportMode == TransportMode.RIST_MULTIPATH) {
                RistDiagnostics.append(this, "start failed | ${error.javaClass.simpleName}: ${error.message}")
            }

            scheduleReconnect(
                error.message
                    ?: error.javaClass.simpleName,
            )

            false
        }
    }

    private fun startSrtlaProxy(endpoint: String): String {
        val config = SrtlaConfig.parseEndpoint(endpoint)
            ?: throw IllegalArgumentException("Invalid SRTLA destination")
        val wifiIp = wifiNetwork?.let(::networkAddress)
        val cellularIp = cellularNetwork?.let(::networkAddress)
        val proxy = SrtlaProxy(
            applicationContext,
            config,
            wifiIp,
            wifiIp?.let { wifiNetwork?.networkHandle },
            cellularIp,
            cellularIp?.let { cellularNetwork?.networkHandle },
        )
        state = state.copy(
            srtlaLinksConnected = 0,
            srtlaLinksTotal = proxy.uplinkCount,
        )
        srtlaProxy = proxy
        monitorSrtlaProxy(proxy)
        return config.localSrtEndpoint(proxy.localSrtPort)
    }

    private fun networkAddress(network: Network): String? =
        connectivityManager.getLinkProperties(network)
            ?.linkAddresses
            ?.map { it.address }
            ?.filterNot {
                it.isAnyLocalAddress ||
                    it.isLoopbackAddress ||
                    it.isLinkLocalAddress ||
                    it.isMulticastAddress
            }
            ?.sortedByDescending { it is Inet4Address }
            ?.firstOrNull()
            ?.hostAddress
            ?.substringBefore('%')

    private fun monitorSrtlaProxy(proxy: SrtlaProxy) {
        val startedAt = SystemClock.elapsedRealtime()
        val poll = object : Runnable {
            override fun run() {
                if (srtlaProxy !== proxy || !state.wantsStreaming) return
                val activeLinks = proxy.activeLinks()
                updateSrtlaLinkCount(activeLinks, proxy.uplinkCount)
                if (activeLinks < 0) {
                    val reason = proxy.error() ?: "SRTLA sender stopped"
                    stopSrtlaProxy()
                    onConnectionFailed(reason)
                    return
                }
                if (activeLinks > 0) retryIndex = 0
                if (
                    activeLinks == 0 &&
                    SystemClock.elapsedRealtime() - startedAt >= SRTLA_LINK_TIMEOUT_MS
                ) {
                    stopSrtlaProxy()
                    onConnectionFailed("No SRTLA receiver links became active")
                    return
                }
                mainHandler.postDelayed(this, SRTLA_POLL_INTERVAL_MS)
            }
        }
        srtlaPoll = poll
        mainHandler.postDelayed(poll, SRTLA_POLL_INTERVAL_MS)
    }

    private fun stopSrtlaProxy() {
        srtlaPoll?.let(mainHandler::removeCallbacks)
        srtlaPoll = null
        srtlaProxy?.close()
        srtlaProxy = null
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

    fun adjustVideoBitrateKbps(deltaKbps: Int): Boolean {
        if (!state.wantsStreaming) return false
        val next = (state.requestedBitrateKbps + deltaKbps).coerceIn(1_000L, 20_000L)
        return runCatching {
            stream.setVideoBitrateOnFly((next * 1000L).toInt())
            state = state.copy(requestedBitrateKbps = next)
            publishState()
            if (kotlin.math.abs(deltaKbps) >= 1_000) stream.requestKeyframe()
            true
        }.getOrElse {
            Log.e(TAG, "Live bitrate adjustment failed", it)
            updateMessage("Bitrate change failed")
            false
        }
    }

    fun stopStream() {
        userStopping = true

        cancelRetry()
        releaseCellularHold()
        releaseWifiHold()

        state = state.copy(
            wantsStreaming = false,
            isReconnecting = false,
            bitrateKbps = 0,
            bondedLinksConnected = 0,
            ristLinksConnected = 0,
            srtlaLinksConnected = 0,
            srtlaLinksTotal = 0,
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
        stopSrtlaProxy()

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
            updateMessage("Connecting over ${state.transportMode.label}")
        }
    }

    override fun onConnectionSuccess() {
        Log.i(TAG, "${state.transportMode.label} connected")
        if (state.transportMode == TransportMode.RIST || state.transportMode == TransportMode.RIST_MULTIPATH) {
            RistDiagnostics.append(this, "connected | links=${state.ristLinksConnected}")
        }
        if (state.transportMode != TransportMode.SRTLA) retryIndex = 0
        state = state.copy(isStreaming = true, isReconnecting = false)
        val connectedMessage = if (state.transportMode == TransportMode.BONDED_SRT) {
            "Live over Bonded SRT | ${state.bondedLinksConnected}/2 links"
        } else if (state.transportMode in setOf(TransportMode.RIST, TransportMode.RIST_MULTIPATH)) {
            val maxLinks = if (state.transportMode == TransportMode.RIST_MULTIPATH) 2 else 1
            "Live over RIST | ${state.ristLinksConnected}/$maxLinks links"
        } else if (state.transportMode == TransportMode.SRTLA) {
            "Local SRT connected | SRTLA receiver links ${state.srtlaLinksConnected}/${state.srtlaLinksTotal}"
        } else {
            "Live over ${state.transportMode.label}"
        }
        updateMessage(connectedMessage)

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

        Log.e(TAG, "${state.transportMode.label} connection failed: $detail")
        if (state.transportMode == TransportMode.RIST || state.transportMode == TransportMode.RIST_MULTIPATH) {
            RistDiagnostics.append(this, "connection failed | $detail")
        }

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
                "${state.transportMode.label} failed: $detail",
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
            message = if (state.isStreaming && state.transportMode == TransportMode.BONDED_SRT) {
                "Bonded SRT | ${state.bondedLinksConnected}/2 links | Sending ${bitrate / 1000} Kbps"
            } else if (state.isStreaming && state.transportMode == TransportMode.SRTLA) {
                "SRTLA | ${state.srtlaLinksConnected}/${state.srtlaLinksTotal} receiver links | Sending ${bitrate / 1000} Kbps"
            } else if (state.isStreaming && state.transportMode in setOf(
                    TransportMode.RIST,
                    TransportMode.RIST_MULTIPATH,
                )
            ) {
                val maxLinks = if (state.transportMode == TransportMode.RIST_MULTIPATH) 2 else 1
                "RIST | ${state.ristLinksConnected}/$maxLinks links | Sending ${bitrate / 1000} Kbps"
            } else if (state.isStreaming) {
                "Sending ${bitrate / 1000} Kbps"
            } else {
                state.message
            },
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
            "${state.transportMode.label} disconnected",
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
        stopSrtlaProxy()

        runCatching {
            if (stream.isOnPreview) {
                stream.stopPreview()
            }
        }

        runCatching {
            stream.release()
        }
        releaseCellularHold()
        releaseWifiHold()

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
