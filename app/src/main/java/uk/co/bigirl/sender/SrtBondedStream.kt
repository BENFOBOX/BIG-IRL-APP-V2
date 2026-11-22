package uk.co.bigirl.sender

import android.content.Context
import com.pedro.common.ConnectChecker

internal class SrtBondedStream(
    context: Context,
    connectChecker: ConnectChecker,
    private val networkHandles: () -> Pair<Long?, Long?>,
    private val onLinksChanged: (Int) -> Unit,
) : MpegTsStream(context, connectChecker) {

    override fun createSender(endpoint: String): TransportPacketSender {
        val config = BondingConfig.parseEndpoint(endpoint)
            ?: throw IllegalArgumentException("Invalid bonded SRT destinations")
        val (wifiHandle, cellularHandle) = networkHandles()
        return SrtBondedSender(
            config,
            wifiHandle ?: throw IllegalStateException("Wi-Fi network is unavailable"),
            cellularHandle ?: throw IllegalStateException("Cellular network is unavailable"),
        ) { connectedLinks ->
            onLinksChanged(connectedLinks)
            onNativeConnectionStatus(
                connectedLinks > 0,
                "Bonded SRT has no connected network links",
            )
        }
    }
}
