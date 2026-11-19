package uk.co.bigirl.sender

/**
 * Network-side configuration for the StreamBox bonded receiver.
 *
 * This deliberately does not claim that RootEncoder's single SRT client is bonded.
 * The native sender must create an SRT broadcast group with one member per Android Network.
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
}
