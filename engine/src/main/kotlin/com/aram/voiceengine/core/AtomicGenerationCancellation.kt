package com.aram.voiceengine.core

import java.util.concurrent.atomic.AtomicBoolean

class AtomicGenerationCancellation : GenerationCancellation {
    private val cancelled = AtomicBoolean(false)
    override fun isCancelled(): Boolean = cancelled.get()
    fun cancel() { cancelled.set(true) }
}
