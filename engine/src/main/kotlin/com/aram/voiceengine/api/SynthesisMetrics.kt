package com.aram.voiceengine.api

data class SynthesisMetrics(
    val firstAudioLatencyMs: Long,
    val totalGenerationTimeMs: Long,
    val generatedAudioDurationMs: Long,
    val emittedChunks: Int
) {
    val realTimeFactor: Double
        get() = if (generatedAudioDurationMs > 0) {
            totalGenerationTimeMs.toDouble() / generatedAudioDurationMs
        } else {
            0.0
        }
}
