package com.aram.voiceengine.app

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.os.SystemClock
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

internal data class DecodedReferenceAudio(
    val samples: FloatArray,
    val sampleRate: Int,
    val channels: Int
)

/**
 * Decodes a user-selected content:// audio URI to interleaved float PCM.
 * The MOSS codec encoder performs channel conversion and resampling to its
 * metadata-defined input format.
 */
internal object AndroidReferenceAudioDecoder {
    private const val MAX_REFERENCE_DURATION_US = 30_000_000L
    private const val DECODE_TIMEOUT_MS = 120_000L

    fun decode(context: Context, uri: Uri): DecodedReferenceAudio {
        val extractor = MediaExtractor()
        var decoder: MediaCodec? = null
        try {
            extractor.setDataSource(context, uri, null)
            var audioTrack = -1
            var inputFormat: MediaFormat? = null
            for (index in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(index)
                val mime = format.getString(MediaFormat.KEY_MIME).orEmpty()
                if (mime.startsWith("audio/")) {
                    audioTrack = index
                    inputFormat = format
                    break
                }
            }
            require(audioTrack >= 0 && inputFormat != null) {
                "الملف المختار لا يحتوي على مسار صوتي قابل للفك."
            }

            val selectedFormat = inputFormat ?: error("الملف المختار لا يحتوي على صيغة صوتية.")
            val mime = selectedFormat.getString(MediaFormat.KEY_MIME)
                ?: error("تعذر تحديد ترميز الملف الصوتي.")
            extractor.selectTrack(audioTrack)
            decoder = MediaCodec.createDecoderByType(mime)
            decoder.configure(selectedFormat, null, null, 0)
            decoder.start()

            val bytes = ByteArrayOutputStream()
            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false
            var outputFormat: MediaFormat? = null
            val startedAt = SystemClock.elapsedRealtime()

            while (!outputDone) {
                check(SystemClock.elapsedRealtime() - startedAt < DECODE_TIMEOUT_MS) {
                    "انتهت مهلة فك الملف الصوتي المرجعي."
                }

                if (!inputDone) {
                    val inputIndex = decoder.dequeueInputBuffer(10_000L)
                    if (inputIndex >= 0) {
                        val inputBuffer = decoder.getInputBuffer(inputIndex)
                            ?: error("تعذر الوصول إلى مخزن إدخال مفكك الصوت.")
                        inputBuffer.clear()
                        val sampleTime = extractor.sampleTime
                        val sampleSize = if (sampleTime < 0L || sampleTime >= MAX_REFERENCE_DURATION_US) {
                            -1
                        } else {
                            extractor.readSampleData(inputBuffer, 0)
                        }
                        if (sampleSize < 0) {
                            decoder.queueInputBuffer(
                                inputIndex, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM
                            )
                            inputDone = true
                        } else {
                            decoder.queueInputBuffer(
                                inputIndex, 0, sampleSize, sampleTime, extractor.sampleFlags
                            )
                            extractor.advance()
                        }
                    }
                }

                val outputIndex = decoder.dequeueOutputBuffer(info, 10_000L)
                when {
                    outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        outputFormat = decoder.outputFormat
                    }
                    outputIndex >= 0 -> {
                        if (info.size > 0 &&
                            (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0
                        ) {
                            val outputBuffer = decoder.getOutputBuffer(outputIndex)
                                ?: error("تعذر الوصول إلى خرج مفكك الصوت.")
                            outputBuffer.position(info.offset)
                            outputBuffer.limit(info.offset + info.size)
                            val chunk = ByteArray(info.size)
                            outputBuffer.get(chunk)
                            bytes.write(chunk)
                        }
                        outputDone = (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0
                        decoder.releaseOutputBuffer(outputIndex, false)
                    }
                }
            }

            val format = outputFormat ?: decoder.outputFormat ?: selectedFormat
            val sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            val channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            require(sampleRate > 0 && channels in 1..2) {
                "يدعم مرجع الصوت قناة واحدة أو قناتين فقط؛ الصيغة المستلمة: $sampleRate Hz / $channels ch."
            }
            val pcmEncoding = if (format.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
                format.getInteger(MediaFormat.KEY_PCM_ENCODING)
            } else {
                AudioFormat.ENCODING_PCM_16BIT
            }

            val raw = bytes.toByteArray()
            require(raw.isNotEmpty()) { "الملف المرجعي فارغ أو لم ينتج صوتًا قابلًا للفك." }
            val samples = when (pcmEncoding) {
                AudioFormat.ENCODING_PCM_FLOAT -> {
                    require(raw.size % 4 == 0) { "حجم PCM float غير مكتمل." }
                    val buffer = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN)
                    FloatArray(raw.size / 4) { buffer.float.coerceIn(-1f, 1f) }
                }
                AudioFormat.ENCODING_PCM_16BIT -> {
                    require(raw.size % 2 == 0) { "حجم PCM 16-bit غير مكتمل." }
                    val buffer = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN)
                    FloatArray(raw.size / 2) { buffer.short.toFloat() / 32768f }
                }
                AudioFormat.ENCODING_PCM_8BIT -> FloatArray(raw.size) {
                    (((raw[it].toInt() and 0xff) - 128).toFloat() / 128f)
                }
                else -> error("صيغة PCM الناتجة غير مدعومة من مفكك الصوت: $pcmEncoding")
            }
            val aligned = samples.size - (samples.size % channels)
            require(aligned > 0) { "لم ينتج الملف المرجعي عينات صوت كافية." }
            return DecodedReferenceAudio(samples.copyOf(aligned), sampleRate, channels)
        } finally {
            try { decoder?.stop() } catch (_: Exception) { }
            try { decoder?.release() } catch (_: Exception) { }
            try { extractor.release() } catch (_: Exception) { }
        }
    }
}
