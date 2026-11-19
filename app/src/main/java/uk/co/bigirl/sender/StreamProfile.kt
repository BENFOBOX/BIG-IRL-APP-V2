package uk.co.bigirl.sender

enum class StreamProfile(
    val label: String,
    val width: Int,
    val height: Int,
    val fps: Int,
    val videoBitrate: Int,
    val audioBitrate: Int = 128_000,
    val sampleRate: Int = 48_000,
) {
    RELIABLE_720P30("Reliable | 720p30 | 3.5 Mbps", 1280, 720, 30, 3_500_000),
    SMOOTH_720P60("Smooth | 720p60 | 5 Mbps", 1280, 720, 60, 5_000_000),
    QUALITY_1080P30("Quality | 1080p30 | 6 Mbps", 1920, 1080, 30, 6_000_000),
    MAXIMUM_1080P60("Maximum | 1080p60 | 8 Mbps", 1920, 1080, 60, 8_000_000),
}
