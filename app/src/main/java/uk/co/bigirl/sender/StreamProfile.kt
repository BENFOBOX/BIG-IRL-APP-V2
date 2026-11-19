package uk.co.bigirl.sender

data class StreamProfile(
    val width: Int = 1920,
    val height: Int = 1080,
    val fps: Int = 60,
    val videoBitrate: Int = 8_000_000,
    val audioBitrate: Int = 128_000,
    val sampleRate: Int = 48_000,
)
