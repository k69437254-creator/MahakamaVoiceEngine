package com.aram.voiceengine.api

data class AudioChunk(
    val samples: FloatArray,
    val sampleRate: Int,
    val channels: Int,
    val sequence: Int,
    val isFinal: Boolean = false
) {
    init {
        require(samples.isNotEmpty()) { "Audio chunk is empty." }
        require(sampleRate > 0) { "Invalid sample rate." }
        require(channels > 0) { "Invalid channel count." }
        require(sequence >= 0) { "Invalid sequence number." }
        require(samples.size % channels == 0) {
            "Audio data does not align with channel count."
        }
    }

    val samplesPerChannel: Int
        get() = samples.size / channels

    val durationMs: Long
        get() = samplesPerChannel * 1000L / sampleRate
}
