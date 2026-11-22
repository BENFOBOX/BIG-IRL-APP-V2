package uk.co.bigirl.sender

/**
 * Network-side configuration for the StreamBox bonded receiver.
 *
 * The native sender opens a broadcast-group member for each Android Network.
 */
data class BondingConfig(
    val host: String = "145.40.153.191",
    val wifiPort: Int = 10010,
    val cellularPort: Int = 10011,
    val streamId: String = "live",
) {
    init {
        require(host.isNotBlank()) { "Bonding host must not be blank" }
        require(wifiPort in 1..65535) { "Invalid Wi-Fi port: $wifiPort" }
        require(cellularPort in 1..65535) { "Invalid cellular port: $cellularPort" }
        require(wifiPort != cellularPort) { "Bonded member ports must be different" }
        require(streamId.isNotBlank()) { "Stream ID must not be blank" }
    }

    val wifiEndpoint: String get() = "srt://$host:$wifiPort/$streamId"
    val cellularEndpoint: String get() = "srt://$host:$cellularPort/$streamId"
    val endpoint: String get() = "srt-bond://$host:$wifiPort,$cellularPort/$streamId"

    companion object {
        private val ENDPOINT_PATTERN =
            Regex("""^srt-bond://(\[[0-9A-Fa-f:.]+\]|[A-Za-z0-9.-]+):(\d+),(\d+)/([^/?#]+)$""")

        fun parseEndpoint(endpoint: String): BondingConfig? {
            val match = ENDPOINT_PATTERN.matchEntire(endpoint.trim()) ?: return null
            return runCatching {
                BondingConfig(
                    host = match.groupValues[1],
                    wifiPort = match.groupValues[2].toInt(),
                    cellularPort = match.groupValues[3].toInt(),
                    streamId = match.groupValues[4],
                )
            }.getOrNull()
        }
    }
}
