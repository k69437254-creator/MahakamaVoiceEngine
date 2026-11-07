package com.aram.voiceengine.api

sealed class EngineResult<out T> {
    data class Success<T>(val value: T) : EngineResult<T>()
    data class Failure(val error: EngineError) : EngineResult<Nothing>()
}

data class EngineError(
    val code: String,
    val message: String,
    val cause: Throwable? = null,
    val recoverable: Boolean = false
)
