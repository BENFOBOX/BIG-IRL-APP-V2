package uk.co.bigirl.sender

internal class RistSender(
    endpoint: String,
    wifiNetworkHandle: Long?,
    cellularNetworkHandle: Long,
    private val onLinkCountChanged: (Int) -> Unit,
) : TransportPacketSender {

    @Volatile
    private var closed = false
    private var nativeHandle = 0L

    init {
        nativeHandle = RistNative.createNative(
            endpoint,
            wifiNetworkHandle ?: 0L,
            cellularNetworkHandle,
            this,
        )
        check(nativeHandle != 0L) {
            RistNative.lastCreateErrorNative() ?: "Could not start the native RIST sender"
        }
    }

    fun onLinkCountNative(connectedLinks: Int) {
        if (!closed) onLinkCountChanged(connectedLinks)
    }

    @Synchronized
    override fun send(transportStreamPacket: ByteArray) {
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

internal object RistNative {

    init {
        System.loadLibrary("bigirl_transport")
    }

    external fun createNative(
        endpoint: String,
        wifiNetworkHandle: Long,
        cellularNetworkHandle: Long,
        callback: RistSender,
    ): Long

    external fun lastCreateErrorNative(): String?
    external fun diagnosticNative(): String?

    external fun sendNative(handle: Long, payload: ByteArray): Int

    external fun destroyNative(handle: Long)
}
