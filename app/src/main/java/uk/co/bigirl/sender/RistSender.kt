package uk.co.bigirl.sender

internal class RistSender(
    endpoint: String,
    networkHandle: Long,
    private val onConnectionStatus: (Boolean) -> Unit,
) : AutoCloseable {

    @Volatile
    private var closed = false
    private var nativeHandle = 0L

    init {
        nativeHandle = RistNative.createNative(endpoint, networkHandle, this)
        check(nativeHandle != 0L) { "Could not start the native RIST sender" }
    }

    fun onConnectionStatusNative(connected: Boolean) {
        if (!closed) onConnectionStatus(connected)
    }

    @Synchronized
    fun send(transportStreamPacket: ByteArray) {
        check(nativeHandle != 0L) { "RIST sender is closed" }
        val result = RistNative.sendNative(nativeHandle, transportStreamPacket)
        check(result >= 0) { "RIST rejected a transport-stream packet" }
    }

    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        RistNative.destroyNative(nativeHandle)
        nativeHandle = 0L
    }
}

private object RistNative {

    init {
        System.loadLibrary("bigirl_transport")
    }

    external fun createNative(
        endpoint: String,
        networkHandle: Long,
        callback: RistSender,
    ): Long

    external fun sendNative(handle: Long, payload: ByteArray): Int

    external fun destroyNative(handle: Long)
}
