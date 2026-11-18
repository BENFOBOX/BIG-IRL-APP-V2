package uk.co.bigirl.sender

data class StreamProfile(
    val width: Int = 1280,
    val height: Int = 720,
    val fps: Int = 30,
    val videoBitrate: Int = 3_000_000,
    val audioBitrate: Int = 128_000,
    val sampleRate: Int = 48_000,
)
