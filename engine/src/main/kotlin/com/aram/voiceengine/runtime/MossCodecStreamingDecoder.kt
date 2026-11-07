package com.aram.voiceengine.runtime

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import org.json.JSONArray
import org.json.JSONObject
import java.nio.FloatBuffer
import java.nio.IntBuffer

/**
 * Stateful MOSS codec streaming decoder.
 *
 * The caller owns environment and session. This class owns its cached input
 * state and temporary input tensors. codecMeta must be the codec metadata
 * root object containing codec_config and streaming_decode.
 */
class MossCodecStreamingDecoder(
    private val environment: OrtEnvironment,
    private val session: OrtSession,
    codecMeta: JSONObject
) {
    data class DecodedAudio(
        /** Interleaved PCM samples: L, R, L, R, ... for stereo output. */
        val samples: FloatArray,
        val sampleRate: Int,
        val channels: Int
    ) {
        val samplesPerChannel: Int
            get() = if (channels > 0) samples.size / channels else 0
    }

    private data class IntStateSpec(
        val inputName: String,
        val outputName: String,
        val shape: LongArray,
        val initialValue: Int
    )

    private data class FloatStateSpec(
        val inputName: String,
        val outputName: String,
        val shape: LongArray
    )

    private val sampleRate: Int
    private val channels: Int
    private val numQuantizers: Int

    private val intStateSpecs = ArrayList<IntStateSpec>()
    private val floatStateSpecs = ArrayList<FloatStateSpec>()

    private val intStateValues = LinkedHashMap<String, IntArray>()
    private val floatStateValues = LinkedHashMap<String, FloatArray>()

    init {
        val config = codecMeta.getJSONObject("codec_config")
        sampleRate = config.getInt("sample_rate")
        channels = config.getInt("channels")
        numQuantizers = config.getInt("num_quantizers")

        require(sampleRate > 0) {
            "Invalid codec sample_rate: $sampleRate"
        }
        require(channels > 0) {
            "Invalid codec channels: $channels"
        }
        require(numQuantizers > 0) {
            "Invalid codec num_quantizers: $numQuantizers"
        }

        val streaming = codecMeta.getJSONObject("streaming_decode")
        val transformerOffsets =
            streaming.getJSONArray("transformer_offsets")

        for (index in 0 until transformerOffsets.length()) {
            val spec = transformerOffsets.getJSONObject(index)

            require(spec.getString("dtype") == "int32") {
                "Unsupported transformer offset dtype at index $index"
            }

            intStateSpecs += IntStateSpec(
                inputName = spec.getString("input_name"),
                outputName = spec.getString("output_name"),
                shape = readShape(spec.getJSONArray("shape")),
                initialValue = 0
            )
        }

        val attentionCaches =
            streaming.getJSONArray("attention_caches")

        for (index in 0 until attentionCaches.length()) {
            val spec = attentionCaches.getJSONObject(index)

            require(spec.getString("cache_dtype") == "float32") {
                "Unsupported attention cache dtype at index $index"
            }
            require(spec.getString("positions_dtype") == "int32") {
                "Unsupported attention position dtype at index $index"
            }

            intStateSpecs += IntStateSpec(
                inputName = spec.getString("offset_input_name"),
                outputName = spec.getString("offset_output_name"),
                shape = readShape(spec.getJSONArray("offset_shape")),
                initialValue = 0
            )

            floatStateSpecs += FloatStateSpec(
                inputName = spec.getString("cached_keys_input_name"),
                outputName = spec.getString("cached_keys_output_name"),
                shape = readShape(spec.getJSONArray("cache_shape"))
            )

            floatStateSpecs += FloatStateSpec(
                inputName = spec.getString("cached_values_input_name"),
                outputName = spec.getString("cached_values_output_name"),
                shape = readShape(spec.getJSONArray("cache_shape"))
            )

            intStateSpecs += IntStateSpec(
                inputName = spec.getString("cached_positions_input_name"),
                outputName = spec.getString("cached_positions_output_name"),
                shape = readShape(spec.getJSONArray("positions_shape")),
                initialValue = -1
            )
        }

        require(intStateSpecs.isNotEmpty()) {
            "No integer streaming state was defined in codec metadata."
        }
        require(floatStateSpecs.isNotEmpty()) {
            "No floating-point streaming state was defined in codec metadata."
        }

        require(
            intStateSpecs.map { it.inputName }.distinct().size ==
                intStateSpecs.size
        ) {
            "Duplicate integer state input names in codec metadata."
        }

        require(
            floatStateSpecs.map { it.inputName }.distinct().size ==
                floatStateSpecs.size
        ) {
            "Duplicate float state input names in codec metadata."
        }

        reset()
    }

    /** Reset all streaming offsets and attention caches before a new utterance. */
    @Synchronized
    fun reset() {
        intStateValues.clear()
        floatStateValues.clear()

        for (spec in intStateSpecs) {
            val values = IntArray(elementCount(spec.shape))

            if (spec.initialValue != 0) {
                values.fill(spec.initialValue)
            }

            intStateValues[spec.inputName] = values
        }

        for (spec in floatStateSpecs) {
            floatStateValues[spec.inputName] =
                FloatArray(elementCount(spec.shape))
        }
    }

    /**
     * Decode a batch of generated codec frames.
     * Each frame must contain exactly num_quantizers integer codes.
     * Returns null only when the supplied frame list is empty.
     */
    @Synchronized
    fun decodeFrames(frameRows: List<IntArray>): DecodedAudio? {
        if (frameRows.isEmpty()) return null

        for ((index, row) in frameRows.withIndex()) {
            require(row.size == numQuantizers) {
                "Frame $index has ${row.size} codes; expected $numQuantizers."
            }
        }

        val frameCount = frameRows.size
        val flatCodes = IntArray(frameCount * numQuantizers)

        var cursor = 0
        for (row in frameRows) {
            row.copyInto(flatCodes, destinationOffset = cursor)
            cursor += numQuantizers
        }

        val feeds = LinkedHashMap<String, OnnxTensor>()
        val ownedInputs = ArrayList<OnnxTensor>()

        fun addInput(name: String, tensor: OnnxTensor) {
            check(!feeds.containsKey(name)) {
                "Duplicate ONNX input name: $name"
            }

            feeds[name] = tensor
            ownedInputs += tensor
        }

        try {
            addInput(
                "audio_codes",
                OnnxTensor.createTensor(
                    environment,
                    IntBuffer.wrap(flatCodes),
                    longArrayOf(
                        1L,
                        frameCount.toLong(),
                        numQuantizers.toLong()
                    )
                )
            )

            addInput(
                "audio_code_lengths",
                OnnxTensor.createTensor(
                    environment,
                    IntBuffer.wrap(intArrayOf(frameCount)),
                    longArrayOf(1L)
                )
            )

            for (spec in intStateSpecs) {
                addInput(
                    spec.inputName,
                    OnnxTensor.createTensor(
                        environment,
                        IntBuffer.wrap(
                            intStateValues.getValue(spec.inputName)
                        ),
                        spec.shape
                    )
                )
            }

            for (spec in floatStateSpecs) {
                addInput(
                    spec.inputName,
                    OnnxTensor.createTensor(
                        environment,
                        FloatBuffer.wrap(
                            floatStateValues.getValue(spec.inputName)
                        ),
                        spec.shape
                    )
                )
            }

            session.run(feeds).use { result ->
                val audioTensor = requireTensor(result, "audio")
                val lengthsTensor = requireTensor(result, "audio_lengths")

                val audioShape = audioTensor.info.shape

                require(
                    audioShape.size == 3 &&
                    audioShape[0] == 1L &&
                    audioShape[1] == channels.toLong() &&
                    audioShape[2] >= 0L &&
                    audioShape[2] <= Int.MAX_VALUE.toLong()
                ) {
                    "Unexpected codec audio output shape: " +
                        audioShape.contentToString()
                }

                val channelCapacity = audioShape[2].toInt()
                val expectedAudioElements =
                    Math.multiplyExact(channels.toLong(), audioShape[2])

                val audioBuffer = audioTensor.floatBuffer
                    ?: error(
                        "Codec output 'audio' is not a float-compatible tensor."
                    )

                require(
                    audioBuffer.remaining().toLong() == expectedAudioElements
                ) {
                    "Codec audio buffer contains ${audioBuffer.remaining()} values; " +
                        "expected $expectedAudioElements from tensor shape."
                }

                val channelMajor =
                    FloatArray(audioBuffer.remaining())

                audioBuffer.get(channelMajor)

                val lengthsInfo = lengthsTensor.info
                var lengthsElementCount = 1L

                for (dimension in lengthsInfo.shape) {
                    require(dimension >= 0L) {
                        "Invalid audio_lengths tensor dimension: $dimension"
                    }
                    lengthsElementCount =
                        Math.multiplyExact(lengthsElementCount, dimension)
                }

                require(lengthsElementCount == 1L) {
                    "Expected exactly one audio_lengths value, got shape " +
                        lengthsInfo.shape.contentToString()
                }

                val samplesPerChannelLong = when (lengthsInfo.type.name) {
                    "INT32" -> {
                        val buffer = lengthsTensor.intBuffer
                            ?: error(
                                "audio_lengths is INT32 but has no IntBuffer."
                            )
                        require(buffer.remaining() == 1) {
                            "Expected one INT32 audio_lengths value."
                        }
                        buffer.get().toLong()
                    }

                    "INT64" -> {
                        val buffer = lengthsTensor.longBuffer
                            ?: error(
                                "audio_lengths is INT64 but has no LongBuffer."
                            )
                        require(buffer.remaining() == 1) {
                            "Expected one INT64 audio_lengths value."
                        }
                        buffer.get()
                    }

                    else -> error(
                        "Unsupported audio_lengths type: ${lengthsInfo.type}"
                    )
                }

                require(
                    samplesPerChannelLong in 0L..Int.MAX_VALUE.toLong()
                ) {
                    "audio_lengths is outside the supported Int range: " +
                        samplesPerChannelLong
                }

                val samplesPerChannel = samplesPerChannelLong.toInt()

                require(samplesPerChannel <= channelCapacity) {
                    "Invalid audio_lengths value $samplesPerChannel " +
                        "for channel capacity $channelCapacity."
                }

                // Read all next-state outputs before committing the new state.
                val nextIntState = LinkedHashMap<String, IntArray>()
                val nextFloatState = LinkedHashMap<String, FloatArray>()

                for (spec in intStateSpecs) {
                    nextIntState[spec.inputName] = readIntOutput(
                        result,
                        spec.outputName,
                        elementCount(spec.shape)
                    )
                }

                for (spec in floatStateSpecs) {
                    nextFloatState[spec.inputName] = readFloatOutput(
                        result,
                        spec.outputName,
                        elementCount(spec.shape)
                    )
                }

                intStateValues.putAll(nextIntState)
                floatStateValues.putAll(nextFloatState)

                // ONNX audio layout is [batch, channels, samples].
                // Convert channel-major planes to interleaved PCM.
                val interleaved =
                    FloatArray(samplesPerChannel * channels)

                for (sampleIndex in 0 until samplesPerChannel) {
                    for (channelIndex in 0 until channels) {
                        interleaved[
                            sampleIndex * channels + channelIndex
                        ] = channelMajor[
                            channelIndex * channelCapacity + sampleIndex
                        ]
                    }
                }

                return DecodedAudio(
                    samples = interleaved,
                    sampleRate = sampleRate,
                    channels = channels
                )
            }
        } finally {
            for (tensor in ownedInputs.asReversed()) {
                try {
                    tensor.close()
                } catch (_: Exception) {
                    // Preserve the original inference/extraction exception.
                }
            }
        }
    }

    private fun requireTensor(
        result: OrtSession.Result,
        name: String
    ): OnnxTensor {
        val value = result.get(name).orElse(null)
            ?: error("Required ONNX output '$name' was not returned.")

        return value as? OnnxTensor
            ?: error("ONNX output '$name' is not a tensor.")
    }

    private fun readIntOutput(
        result: OrtSession.Result,
        outputName: String,
        expectedSize: Int
    ): IntArray {
        val buffer = requireTensor(result, outputName).intBuffer
            ?: error("ONNX state output '$outputName' is not int32.")

        require(buffer.remaining() == expectedSize) {
            "State output '$outputName' has ${buffer.remaining()} values; " +
                "expected $expectedSize."
        }

        return IntArray(expectedSize).also {
            buffer.get(it)
        }
    }

    private fun readFloatOutput(
        result: OrtSession.Result,
        outputName: String,
        expectedSize: Int
    ): FloatArray {
        val buffer = requireTensor(result, outputName).floatBuffer
            ?: error(
                "ONNX state output '$outputName' is not float32-compatible."
            )

        require(buffer.remaining() == expectedSize) {
            "State output '$outputName' has ${buffer.remaining()} values; " +
                "expected $expectedSize."
        }

        return FloatArray(expectedSize).also {
            buffer.get(it)
        }
    }

    private fun readShape(array: JSONArray): LongArray {
        require(array.length() > 0) {
            "A state tensor shape cannot be empty."
        }

        return LongArray(array.length()) { index ->
            array.getLong(index)
        }
    }

    private fun elementCount(shape: LongArray): Int {
        var count = 1L

        for (dimension in shape) {
            require(dimension > 0L) {
                "Invalid state tensor dimension: $dimension"
            }

            count = Math.multiplyExact(count, dimension)
        }

        require(count <= Int.MAX_VALUE.toLong()) {
            "State tensor is too large for a JVM array: $count"
        }

        return count.toInt()
    }
}
