package com.aram.voiceengine.api

data class TtsRequest(
    val text: String,
    val voiceId: String? = null,
    val speed: Float = 1.0f
) {
    init {
        require(text.isNotBlank()) { "Text must not be empty." }
        require(speed in 0.5f..2.0f) { "Speed must be between 0.5 and 2.0." }
    }
}
