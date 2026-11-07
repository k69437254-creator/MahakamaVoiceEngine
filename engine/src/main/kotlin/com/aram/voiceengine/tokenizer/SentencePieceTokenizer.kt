package com.aram.voiceengine.tokenizer

class SentencePieceTokenizer(modelPath: String) : AutoCloseable {

    private var handle: Long = 0L

    init {
        System.loadLibrary("ishtar_tokenizer")
        handle = nativeCreate(modelPath)
        check(handle != 0L) { "Failed to initialize SentencePiece." }
    }

    @Synchronized
    fun encode(text: String): IntArray {
        require(text.isNotEmpty()) { "Text must not be empty." }
        check(handle != 0L) { "Tokenizer is closed." }
        return nativeEncode(handle, text)
    }

    @Synchronized
    override fun close() {
        val current = handle
        handle = 0L
        if (current != 0L) nativeDestroy(current)
    }

    private external fun nativeCreate(modelPath: String): Long
    private external fun nativeEncode(handle: Long, text: String): IntArray
    private external fun nativeDestroy(handle: Long)
}
