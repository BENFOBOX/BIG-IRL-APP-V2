package uk.co.bigirl.sender

internal class SrtBondedSender(
    config: BondingConfig,
    wifiNetworkHandle: Long,
    cellularNetworkHandle: Long,
    private val onLinkCountChanged: (Int) -> Unit,
) : TransportPacketSender {

    @Volatile
    private var closed = false
    private var nativeHandle = 0L

    init {
        nativeHandle = SrtBondedNative.createNative(
            config.host.removeSurrounding("[", "]"),
            config.wifiPort,
            config.cellularPort,
            config.streamId,
            wifiNetworkHandle,
            cellularNetworkHandle,
            this,
        )
        check(nativeHandle != 0L) { "Could not start the bonded SRT sender" }
    }

    fun onLinkCountNative(connectedLinks: Int) {
        if (!closed) onLinkCountChanged(connectedLinks)
    }

    @Synchronized
    override fun send(packet: ByteArray) {
        check(!closed) { "Bonded SRT sender is closed" }
        val result = SrtBondedNative.sendNative(nativeHandle, packet)
        check(result >= 0) { "Bonded SRT rejected a transport-stream packet" }
    }

    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        SrtBondedNative.destroyNative(nativeHandle)
        nativeHandle = 0L
    }
}

private object SrtBondedNative {

    init {
        System.loadLibrary("bigirl_srt_transport")
    }

    external fun createNative(
        host: String,
        wifiPort: Int,
        cellularPort: Int,
        streamId: String,
        wifiNetworkHandle: Long,
        cellularNetworkHandle: Long,
        callback: SrtBondedSender,
    ): Long

    external fun sendNative(handle: Long, payload: ByteArray): Int

    external fun destroyNative(handle: Long)
}
