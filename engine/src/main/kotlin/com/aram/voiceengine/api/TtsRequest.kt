package com.aram.voiceengine.api

import com.aram.voiceengine.core.GenerationCancellation

data class TtsRequest(
    val text: String,
    /** Exact manifest voice identifier. No voice is selected implicitly. */
    val voiceId: String? = null,
    val speed: Float = 1.0f,
    /** Optional caller-supplied MOSS RVQ prompt. Each row must contain n_vq codes. */
    val promptAudioCodes: List<IntArray>? = null,
    val maxNewFrames: Int? = null,
    val cancellation: GenerationCancellation? = null,
) {
    init {
        require(text.isNotBlank()) { "Text must not be empty." }
        require(speed == 1.0f) { "The current MOSS runtime supports speed 1.0 only." }
        require(maxNewFrames == null || maxNewFrames > 0) { "maxNewFrames must be positive." }
    }
}
