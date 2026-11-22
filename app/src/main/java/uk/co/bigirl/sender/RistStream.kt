package uk.co.bigirl.sender

import android.content.Context
import android.net.ConnectivityManager
import com.pedro.common.ConnectChecker

internal class RistStream(
    context: Context,
    connectChecker: ConnectChecker,
    private val networkHandles: () -> Pair<Long?, Long?>,
    private val multipath: Boolean,
    private val onLinkCountChanged: (Int) -> Unit,
) : MpegTsStream(context, connectChecker) {

    override fun createSender(endpoint: String): TransportPacketSender {
        val (wifiHandle, cellularHandle) = networkHandles()
        if (!multipath) {
            val cell = cellularHandle
                ?: throw IllegalStateException("Cellular network is unavailable")
            val activeNetworkHandle = (
                context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            ).activeNetwork?.networkHandle
            if (activeNetworkHandle != cell) {
                throw IllegalStateException(
                    "Turn off Wi-Fi to stream single-path RIST over the SIM",
                )
            }
            return createRistSender(endpoint, null, cell)
        }

        val firstAvailableHandle = wifiHandle ?: cellularHandle
            ?: throw IllegalStateException("No network is available for RIST")
        val useBothPaths = wifiHandle != null && cellularHandle != null
        return createRistSender(
            endpoint,
            if (useBothPaths) wifiHandle else null,
            cellularHandle ?: firstAvailableHandle,
        )
    }

    private fun createRistSender(
        endpoint: String,
        wifiHandle: Long?,
        cellularHandle: Long,
    ): RistSender =
        RistSender(endpoint, wifiHandle, cellularHandle) { connectedLinks ->
            onLinkCountChanged(connectedLinks)
            onNativeConnectionStatus(
                connectedLinks > 0,
                "RIST receiver connection timed out",
            )
        }
}
