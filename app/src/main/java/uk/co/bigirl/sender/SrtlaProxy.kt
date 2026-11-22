package uk.co.bigirl.sender

import android.content.Context
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress

internal class SrtlaProxy(
    context: Context,
    config: SrtlaConfig,
    wifiIp: String?,
    wifiNetworkHandle: Long?,
    cellularIp: String?,
    cellularNetworkHandle: Long?,
) : AutoCloseable {

    val localSrtPort: Int
    val uplinkCount: Int
    private var nativeHandle = 0L

    init {
        val paths = listOfNotNull(
            wifiIp?.let { it to wifiNetworkHandle },
            cellularIp?.let { it to cellularNetworkHandle },
        )
        require(paths.isNotEmpty()) { "SRTLA needs at least one available network path" }
        require(paths.all { (_, handle) -> handle != null && handle != 0L }) {
            "SRTLA network path is missing its Android network binding"
        }
        require(paths.map { it.first }.distinct().size == paths.size) {
            "Wi-Fi and SIM reported the same uplink address"
        }
        uplinkCount = paths.size
        localSrtPort = DatagramSocket(null).use { socket ->
            socket.bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0))
            socket.localPort
        }
        nativeHandle = SrtlaNative.createNative(
            config.host,
            config.port,
            localSrtPort,
            wifiIp.orEmpty(),
            wifiNetworkHandle ?: 0L,
            cellularIp.orEmpty(),
            cellularNetworkHandle ?: 0L,
            context.cacheDir.absolutePath,
        )
        check(nativeHandle != 0L) { "Could not start the SRTLA sender" }
    }

    fun activeLinks(): Int =
        if (nativeHandle == 0L) -1 else SrtlaNative.activeLinksNative(nativeHandle)

    fun error(): String? =
        if (nativeHandle == 0L) null else SrtlaNative.errorNative(nativeHandle)

    @Synchronized
    override fun close() {
        if (nativeHandle == 0L) return
        SrtlaNative.destroyNative(nativeHandle)
        nativeHandle = 0L
    }
}

private object SrtlaNative {

    init {
        System.loadLibrary("bigirl_srt_transport")
    }

    external fun createNative(
        receiverHost: String,
        receiverPort: Int,
        localSrtPort: Int,
        wifiIp: String,
        wifiNetworkHandle: Long,
        cellularIp: String,
        cellularNetworkHandle: Long,
        cacheDirectory: String,
    ): Long

    external fun activeLinksNative(handle: Long): Int

    external fun errorNative(handle: Long): String?

    external fun destroyNative(handle: Long)
}
