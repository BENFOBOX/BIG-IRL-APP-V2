package uk.co.bigirl.sender

enum class TransportMode(
    val label: String,
    val scheme: String,
    val defaultEndpoint: String,
    val destinationLabel: String,
    val destinationHelp: String,
) {
    SINGLE_SRT(
        label = "Single SRT",
        scheme = "srt",
        defaultEndpoint = "srt://[2a0e:cb01:e9:cf00:4ab0:2dff:fe2f:610e]:10020/live",
        destinationLabel = "SRT destination",
        destinationHelp = "Use the public receiver address; the private 192.168.1.27 address only works on the home network.",
    ),
    BONDED_SRT(
        label = "SRT Redundancy | Wi-Fi + SIM",
        scheme = "srt-bond",
        defaultEndpoint = BondingConfig().endpoint,
        destinationLabel = "Bonded SRT destinations",
        destinationHelp = "Broadcasts duplicate packets to the Wi-Fi and SIM SRT group members; this adds redundancy, not bandwidth.",
    ),
    SRTLA(
        label = "SRTLA | Wi-Fi + SIM",
        scheme = "srtla",
        defaultEndpoint = "srtla://145.40.153.191:10012?streamid=live",
        destinationLabel = "SRTLA receiver",
        destinationHelp = "SRTLA2 bonds SRT traffic over the available Wi-Fi and SIM paths; requires the SRTLA receiver on the Nano.",
    ),
    RIST(
        label = "RIST",
        scheme = "rist",
        defaultEndpoint = "rist://145.40.153.191:11000",
        destinationLabel = "RIST destination",
        destinationHelp = "StreamBox RIST receiver on UDP 11000.",
    ),
    RIST_MULTIPATH(
        label = "RIST Main | Wi-Fi + SIM",
        scheme = "rist",
        defaultEndpoint = "rist://145.40.153.191:11000",
        destinationLabel = "RIST multipath destination",
        destinationHelp = "RIST Main load-balances across Wi-Fi and SIM; receiver must merge both paths. StreamBox support is unverified.",
    ),
}
