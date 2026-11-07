package com.aram.voiceengine.api

data class EngineInfo(
    val id: String,
    val name: String,
    val version: String,
    val supportedLanguages: Set<String>,
    val supportsStreaming: Boolean,
    val supportsVoiceCloning: Boolean,
    val supportedSampleRates: Set<Int>
)
