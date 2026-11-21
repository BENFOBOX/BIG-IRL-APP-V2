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
    RIST(
        label = "RIST",
        scheme = "rist",
        defaultEndpoint = "rist://145.40.153.191:11000",
        destinationLabel = "RIST destination",
        destinationHelp = "StreamBox RIST receiver on UDP 11000.",
    ),
}
