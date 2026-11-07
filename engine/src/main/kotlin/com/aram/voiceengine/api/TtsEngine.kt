package com.aram.voiceengine.api

interface TtsEngine {
    val info: EngineInfo

    suspend fun initialize(): EngineResult<Unit>

    suspend fun synthesize(
        request: TtsRequest,
        onAudioChunk: suspend (AudioChunk) -> Unit
    ): EngineResult<SynthesisMetrics>

    suspend fun release()
}
