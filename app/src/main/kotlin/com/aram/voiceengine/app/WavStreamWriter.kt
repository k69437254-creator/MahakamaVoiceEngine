package com.aram.voiceengine.app

import java.io.Closeable
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Bounded-memory PCM16 WAV writer; the RIFF sizes are patched on finish(). */
internal class WavStreamWriter(
    private val file: File,
    private val sampleRate: Int,
    private val channels: Int,
) : Closeable {
    private val output: RandomAccessFile
    private var dataBytes = 0L
    private var finished = false

    init {
        require(sampleRate > 0 && channels > 0)
        file.parentFile?.let { require(it.exists() || it.mkdirs()) { "تعذر إنشاء مجلد التسجيلات" } }
        output = RandomAccessFile(file, "rw")
        output.setLength(0)
        output.write(ByteArray(44))
    }

    @Synchronized fun write(samples: ShortArray) {
        check(!finished) { "WAV writer is already finished" }
        require(samples.size % channels == 0) { "PCM samples do not match channel count" }
        val buffer = ByteBuffer.allocate(samples.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        samples.forEach { buffer.putShort(it) }
        output.seek(44L + dataBytes)
        output.write(buffer.array())
        dataBytes += buffer.capacity().toLong()
        require(dataBytes <= 0xFFFF_FFFFL - 36L) { "WAV file exceeded RIFF size limit" }
    }

    @Synchronized fun finish(): File {
        if (!finished) {
            val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
            header.put("RIFF".toByteArray(Charsets.US_ASCII))
            header.putInt((36L + dataBytes).toInt())
            header.put("WAVE".toByteArray(Charsets.US_ASCII))
            header.put("fmt ".toByteArray(Charsets.US_ASCII))
            header.putInt(16)
            header.putShort(1.toShort())
            header.putShort(channels.toShort())
            header.putInt(sampleRate)
            header.putInt(sampleRate * channels * 2)
            header.putShort((channels * 2).toShort())
            header.putShort(16.toShort())
            header.put("data".toByteArray(Charsets.US_ASCII))
            header.putInt(dataBytes.toInt())
            output.seek(0L)
            output.write(header.array())
            output.fd.sync()
            finished = true
        }
        return file
    }

    override fun close() {
        try { if (!finished && dataBytes > 0) finish() } finally { output.close() }
    }
}
