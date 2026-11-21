package uk.co.bigirl.sender

import android.content.Context
import android.media.MediaCodec
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import com.pedro.common.AudioCodec
import com.pedro.common.ConnectChecker
import com.pedro.common.VideoCodec
import com.pedro.common.onMainThreadHandler
import com.pedro.common.toMediaFrameInfo
import com.pedro.encoder.input.sources.audio.MicrophoneSource
import com.pedro.encoder.input.sources.video.Camera2Source
import com.pedro.library.base.StreamBase
import com.pedro.library.util.streamclient.StreamBaseClient
import com.pedro.common.socket.base.SocketType
import java.nio.ByteBuffer

internal class RistStream(
    context: Context,
    private val connectChecker: ConnectChecker,
    private val networkHandle: () -> Long?,
) : StreamBase(context, Camera2Source(context), MicrophoneSource()) {

    private val client = UnsupportedRistStreamClient()
    private val muxer = RistMpegTsMuxer(::sendTransportPacket)
    private val bitrateHandler = Handler(Looper.getMainLooper())
    @Volatile
    private var sender: RistSender? = null
    @Volatile
    private var connectionReported = false
    @Volatile
    private var failureReported = false
    private var bytesSinceBitrateUpdate = 0L
    private var bitrateWindowStartMs = SystemClock.elapsedRealtime()

    override fun getStreamClient(): StreamBaseClient = client

    override fun setVideoCodecImp(codec: VideoCodec) {
        require(codec == VideoCodec.H264) { "RIST test sender supports H.264 only" }
    }

    override fun setAudioCodecImp(codec: AudioCodec) {
        require(codec == AudioCodec.AAC) { "RIST test sender supports AAC only" }
    }

    override fun onAudioInfoImp(sampleRate: Int, isStereo: Boolean) {
        muxer.setAudioInfo(sampleRate, isStereo)
    }

    override fun startStreamImp(endPoint: String) {
        val handle = networkHandle()
            ?: throw IllegalStateException("Cellular network is unavailable")
        connectionReported = false
        failureReported = false
        bytesSinceBitrateUpdate = 0
        bitrateWindowStartMs = SystemClock.elapsedRealtime()
        connectChecker.onConnectionStarted(endPoint)
        sender = RistSender(endPoint, handle, ::onConnectionStatus)
        muxer.start()
    }

    override fun stopStreamImp() {
        sender?.close()
        sender = null
        connectionReported = false
        failureReported = false
    }

    override fun onVideoInfoImp(sps: ByteBuffer, pps: ByteBuffer?, vps: ByteBuffer?) {
        require(pps != null && vps == null) { "RIST test sender requires H.264 codec configuration" }
        muxer.setVideoInfo(sps, pps)
    }

    override fun getVideoDataImp(videoBuffer: ByteBuffer, info: MediaCodec.BufferInfo) {
        if (sender == null || failureReported) return
        muxer.sendVideo(videoBuffer, info.toMediaFrameInfo())
    }

    override fun getAudioDataImp(audioBuffer: ByteBuffer, info: MediaCodec.BufferInfo) {
        if (sender == null || failureReported) return
        muxer.sendAudio(audioBuffer, info.toMediaFrameInfo())
    }

    private fun sendTransportPacket(packet: ByteArray) {
        val activeSender = sender ?: return
        try {
            activeSender.send(packet)
        } catch (error: RuntimeException) {
            if (!failureReported) {
                failureReported = true
                Log.e("BIG_IRL_RIST", "RIST send failed", error)
                onMainThreadHandler {
                    connectChecker.onConnectionFailed(
                        error.message ?: error.javaClass.simpleName,
                    )
                }
            }
            return
        }

        bytesSinceBitrateUpdate += packet.size
        reportBitrateIfDue()
    }

    private fun onConnectionStatus(connected: Boolean) {
        if (connected) {
            if (!connectionReported && !failureReported) {
                connectionReported = true
                onMainThreadHandler { connectChecker.onConnectionSuccess() }
            }
        } else if (!failureReported) {
            failureReported = true
            onMainThreadHandler {
                connectChecker.onConnectionFailed("RIST receiver connection timed out")
            }
        }
    }

    private fun reportBitrateIfDue() {
        val now = SystemClock.elapsedRealtime()
        val elapsed = now - bitrateWindowStartMs
        if (elapsed < BITRATE_UPDATE_INTERVAL_MS) return
        val bitrate = bytesSinceBitrateUpdate * BITS_PER_BYTE * 1_000L / elapsed
        bytesSinceBitrateUpdate = 0
        bitrateWindowStartMs = now
        onMainThreadHandler { connectChecker.onNewBitrate(bitrate) }
    }

    private class UnsupportedRistStreamClient : StreamBaseClient() {
        override fun setAuthorization(user: String?, password: String?) = unsupported()
        override fun reTry(delay: Long, reason: String, backupUrl: String?) = unsupported()
        override fun setReTries(reTries: Int) = unsupported()
        override fun hasCongestion(percentUsed: Float) = unsupported()
        override fun setLogs(enabled: Boolean) = unsupported()
        override fun setCheckServerAlive(enabled: Boolean) = unsupported()
        override fun resizeCache(newSize: Int) = unsupported()
        override fun clearCache() = unsupported()
        override fun getCacheSize() = unsupported()
        override fun getItemsInCache() = unsupported()
        override fun getQueueBytesOut() = unsupported()
        override fun getSentAudioFrames() = unsupported()
        override fun getSentVideoFrames() = unsupported()
        override fun getBytesSend() = unsupported()
        override fun getDroppedAudioFrames() = unsupported()
        override fun getDroppedVideoFrames() = unsupported()
        override fun resetSentAudioFrames() = unsupported()
        override fun resetSentVideoFrames() = unsupported()
        override fun resetDroppedAudioFrames() = unsupported()
        override fun resetDroppedVideoFrames() = unsupported()
        override fun resetBytesSend() = unsupported()
        override fun setOnlyAudio(onlyAudio: Boolean) = unsupported()
        override fun setOnlyVideo(onlyVideo: Boolean) = unsupported()
        override fun setBitrateExponentialFactor(factor: Float) = unsupported()
        override fun getBitrateExponentialFactor() = unsupported()
        override fun setSocketType(type: SocketType) = unsupported()
        override fun setSocketTimeout(timeout: Long) = unsupported()
        override fun setDelay(millis: Long) = unsupported()

        private fun unsupported(): Nothing =
            throw UnsupportedOperationException("SRT client controls do not apply to RIST")
    }

    private companion object {
        const val BITRATE_UPDATE_INTERVAL_MS = 1_000L
        const val BITS_PER_BYTE = 8L
    }
}
