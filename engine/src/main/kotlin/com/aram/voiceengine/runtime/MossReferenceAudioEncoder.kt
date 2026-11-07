package com.aram.voiceengine.runtime

import android.content.Context
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.io.Closeable
import java.io.File
import java.nio.FloatBuffer
import java.nio.IntBuffer
import kotlin.math.floor
import kotlin.math.roundToInt

data class EncodedReferenceAudio(
    val promptAudioCodes: List<IntArray>,
    val sampleRate: Int,
    val channels: Int
)

/**
 * Converts decoded Android PCM into the discrete prompt codes consumed by
 * MossTtsEngine. It uses the downloaded Nano codec encoder ONNX graph and
 * never downloads, installs, or selects a voice.
 */
class MossReferenceAudioEncoder(context: Context) : Closeable {
    private val assets = ResolvedModelAssets.from(context.applicationContext)
    private val environment = OrtEnvironment.getEnvironment()
    private val options = OrtSession.SessionOptions().apply {
        setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
        setIntraOpNumThreads(2)
        setInterOpNumThreads(1)
    }
    private val codecConfig = assets.codecMeta.getJSONObject("codec_config")
    private val sampleRate = codecConfig.getInt("sample_rate")
    private val channels = codecConfig.getInt("channels")
    private val numQuantizers = codecConfig.getInt("num_quantizers")
    private val session: OrtSession

    init {
        require(sampleRate > 0 && channels in 1..2 && numQuantizers > 0) {
            "بيانات ترميز الصوت في codec metadata غير صالحة."
        }
        val encodeName = assets.codecMeta.getJSONObject("files").getString("encode")
        val encodeFile = File(assets.codecMetaFile.parentFile, encodeName).canonicalFile
        require(encodeFile.isFile &&
            encodeFile.canonicalPath.startsWith(assets.modelsRoot.canonicalPath + File.separator)
        ) { "ملف MOSS Audio Tokenizer encoder غير موجود: $encodeFile" }
        session = environment.createSession(encodeFile.absolutePath, options)
    }

    fun encode(
        interleavedSamples: FloatArray,
        sourceSampleRate: Int,
        sourceChannels: Int
    ): EncodedReferenceAudio {
        require(sourceSampleRate > 0) { "معدل عينة الصوت المرجعي غير صالح." }
        require(sourceChannels in 1..2) { "يجب أن يحتوي المرجع على قناة واحدة أو قناتين." }
        require(interleavedSamples.isNotEmpty() &&
            interleavedSamples.size % sourceChannels == 0
        ) { "بيانات PCM المرجعية فارغة أو غير متوافقة مع عدد القنوات." }

        val sourceFrames = interleavedSamples.size / sourceChannels
        require(sourceFrames >= sourceSampleRate / 2) {
            "التسجيل المرجعي قصير جدًا؛ اختر تسجيلًا لا يقل عن نصف ثانية."
        }
        require(sourceFrames.toLong() <= sourceSampleRate.toLong() * 30L) {
            "مدة المرجع تتجاوز 30 ثانية. اختر مقطعًا أقصر."
        }

        val targetFrames = (sourceFrames.toDouble() * sampleRate / sourceSampleRate)
            .roundToInt().coerceAtLeast(1)
        val channelMajor = FloatArray(Math.multiplyExact(targetFrames, channels))

        fun sourceValue(frame: Int, targetChannel: Int): Float {
            val safeFrame = frame.coerceIn(0, sourceFrames - 1)
            return when {
                channels == 1 && sourceChannels == 2 -> {
                    val base = safeFrame * 2
                    (interleavedSamples[base] + interleavedSamples[base + 1]) * 0.5f
                }
                sourceChannels == 1 -> interleavedSamples[safeFrame]
                else -> interleavedSamples[safeFrame * sourceChannels + targetChannel]
            }
        }

        for (channel in 0 until channels) {
            val base = channel * targetFrames
            for (index in 0 until targetFrames) {
                val sourcePosition = index.toDouble() * sourceSampleRate / sampleRate
                val left = floor(sourcePosition).toInt()
                val fraction = (sourcePosition - left).toFloat()
                val a = sourceValue(left, channel)
                val b = sourceValue(left + 1, channel)
                channelMajor[base + index] = (a + (b - a) * fraction).coerceIn(-1f, 1f)
            }
        }

        val waveform = OnnxTensor.createTensor(
            environment,
            FloatBuffer.wrap(channelMajor),
            longArrayOf(1L, channels.toLong(), targetFrames.toLong())
        )
        val lengths = OnnxTensor.createTensor(
            environment,
            IntBuffer.wrap(intArrayOf(targetFrames)),
            longArrayOf(1L)
        )
        try {
            session.run(
                mapOf("waveform" to waveform, "input_lengths" to lengths)
            ).use { result ->
                val codes = result.get("audio_codes").orElse(null) as? OnnxTensor
                    ?: error("MOSS encoder لم يُرجع audio_codes.")
                val codeLengths = result.get("audio_code_lengths").orElse(null) as? OnnxTensor
                    ?: error("MOSS encoder لم يُرجع audio_code_lengths.")
                val shape = codes.info.shape
                require(shape.size == 3 && shape[0] == 1L &&
                    shape[1] in 1L..Int.MAX_VALUE.toLong() &&
                    shape[2] == numQuantizers.toLong()
                ) { "أبعاد audio_codes غير متوافقة: ${shape.contentToString()}" }

                val codeLength = when (codeLengths.info.type.name) {
                    "INT32" -> {
                        val buffer = codeLengths.intBuffer ?: error("audio_code_lengths بلا IntBuffer.")
                        require(buffer.remaining() == 1)
                        buffer.get()
                    }
                    "INT64" -> {
                        val buffer = codeLengths.longBuffer ?: error("audio_code_lengths بلا LongBuffer.")
                        require(buffer.remaining() == 1)
                        buffer.get().toInt()
                    }
                    else -> error("نوع audio_code_lengths غير مدعوم: ${codeLengths.info.type}")
                }
                require(codeLength in 1..shape[1].toInt()) {
                    "طول رموز الصوت المرجعي غير صالح: $codeLength."
                }

                val flatCodes = when (codes.info.type.name) {
                    "INT32" -> {
                        val buffer = codes.intBuffer ?: error("audio_codes INT32 بلا IntBuffer.")
                        IntArray(buffer.remaining()).also { buffer.get(it) }
                    }
                    "INT64" -> {
                        val buffer = codes.longBuffer ?: error("audio_codes INT64 بلا LongBuffer.")
                        IntArray(buffer.remaining()) { buffer.get().toInt() }
                    }
                    else -> error("نوع audio_codes غير مدعوم: ${codes.info.type}")
                }
                require(flatCodes.size >= codeLength * numQuantizers) {
                    "مخزن audio_codes أقصر من الطول المعلن."
                }
                val rows = List(codeLength) { frame ->
                    IntArray(numQuantizers) { quantizer ->
                        flatCodes[frame * numQuantizers + quantizer]
                    }
                }
                return EncodedReferenceAudio(rows, sampleRate, channels)
            }
        } finally {
            try { lengths.close() } catch (_: Exception) { }
            try { waveform.close() } catch (_: Exception) { }
        }
    }

    override fun close() {
        try { session.close() } finally { options.close() }
    }
}
