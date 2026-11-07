package com.aram.voiceengine.runtime

import android.content.Context
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OnnxTensorLike
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import com.aram.voiceengine.api.AudioChunk
import com.aram.voiceengine.api.EngineError
import com.aram.voiceengine.api.EngineInfo
import com.aram.voiceengine.api.EngineResult
import com.aram.voiceengine.api.SynthesisMetrics
import com.aram.voiceengine.api.TtsEngine
import com.aram.voiceengine.api.TtsRequest
import com.aram.voiceengine.core.GenerationCancellation
import com.aram.voiceengine.tokenizer.SentencePieceTokenizer
import java.io.Closeable
import java.io.File
import java.nio.FloatBuffer
import java.nio.IntBuffer
import java.security.SecureRandom
import java.util.LinkedHashMap
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/** Native Kotlin MOSS TTS pipeline with incremental codec decoding. */
class MossTtsEngine(
    context: Context,
    private val cpuThreads: Int = 2,
) : TtsEngine {
    private val appContext = context.applicationContext
    private val gate = Mutex()
    private val activeRunOptions = AtomicReference<OrtSession.RunOptions?>(null)

    private data class LifecycleState(
        val generation: Long = 0L,
        val pendingReleases: Int = 0,
    )

    private val lifecycleState = AtomicReference(LifecycleState())
    @Volatile private var runtime: RuntimeBundle? = null

    override val info = EngineInfo(
        id = "ishtar-moss-tts-nano",
        name = "ISHTAR · MOSS TTS Nano",
        version = "1.0",
        supportedLanguages = setOf("multilingual"),
        supportsStreaming = true,
        supportsVoiceCloning = true,
        supportedSampleRates = setOf(48000),
    )

    override suspend fun initialize(): EngineResult<Unit> {
        val lifecycleAtRequest = lifecycleState.get()
        gate.lock()
        return try {
            if (isLifecycleRequestInvalidated(lifecycleAtRequest)) {
                EngineResult.Failure(
                    EngineError("LIFECYCLE_INVALIDATED", "تجاوز طلب التهيئة طلب تحرير أحدث للمحرك.")
                )
            } else {
                withContext(Dispatchers.IO) {
                    if (runtime == null) runtime = createRuntime()
                    if (isLifecycleRequestInvalidated(lifecycleAtRequest)) {
                        EngineResult.Failure(
                            EngineError("LIFECYCLE_INVALIDATED", "أُلغي طلب التهيئة بسبب تحرير المحرك.")
                        )
                    } else {
                        EngineResult.Success(Unit)
                    }
                }
            }
        } catch (error: Throwable) {
            EngineResult.Failure(EngineError("INITIALIZATION_FAILED", error.message ?: "تعذر تهيئة محرك MOSS", error, true))
        } finally {
            gate.unlock()
        }
    }

    override suspend fun synthesize(
        request: TtsRequest,
        onAudioChunk: suspend (AudioChunk) -> Unit,
    ): EngineResult<SynthesisMetrics> {
        val lifecycleAtRequest = lifecycleState.get()
        gate.lock()
        return try {
            if (isLifecycleRequestInvalidated(lifecycleAtRequest)) {
                EngineResult.Failure(
                    EngineError("CANCELLED", "أُلغي طلب التوليد بسبب تحرير المحرك.")
                )
            } else {
                withContext(Dispatchers.IO) {
                    val result = synthesizeLocked(request, onAudioChunk)
                    if (isLifecycleRequestInvalidated(lifecycleAtRequest)) {
                        EngineResult.Failure(
                            EngineError("CANCELLED", "أُلغي التوليد بسبب تحرير المحرك.")
                        )
                    } else {
                        result
                    }
                }
            }
        } catch (error: Throwable) {
            EngineResult.Failure(EngineError("SYNTHESIS_FAILED", error.message ?: "فشل توليد الصوت", error, true))
        } finally {
            gate.unlock()
        }
    }

    /** May be called from a service/UI cancellation action while ORT inference is running. */
    fun cancelCurrentSynthesis() {
        try { activeRunOptions.get()?.setTerminate(true) } catch (_: Exception) { }
    }

    override suspend fun release() {
        // Atomically invalidate older requests before waiting for the engine mutex.
        lifecycleState.updateAndGet { state ->
            state.copy(
                generation = state.generation + 1L,
                pendingReleases = state.pendingReleases + 1,
            )
        }
        cancelCurrentSynthesis()

        // Resource release must finish even if the caller's coroutine is cancelled.
        withContext(NonCancellable) {
            gate.lock()
            try {
                withContext(Dispatchers.IO) {
                    runtime?.close()
                    runtime = null
                }
            } finally {
                lifecycleState.updateAndGet { state ->
                    state.copy(pendingReleases = state.pendingReleases - 1)
                }
                gate.unlock()
            }
        }
    }

    private suspend fun synthesizeLocked(
        request: TtsRequest,
        onAudioChunk: suspend (AudioChunk) -> Unit,
    ): EngineResult<SynthesisMetrics> {
        if (isCancellationRequested(request.cancellation)) {
            return EngineResult.Failure(EngineError("CANCELLED", "أُلغي توليد الصوت قبل البدء."))
        }
        val segments = splitTextIntoSegments(request.text)
        var nextSequence = 0
        var firstAudioLatency = 0L
        var totalGenerationTime = 0L
        var totalAudioDuration = 0L
        var totalChunks = 0
        for (segment in segments) {
            if (isCancellationRequested(request.cancellation)) {
                return EngineResult.Failure(EngineError("CANCELLED", "أُلغي توليد الصوت."))
            }
            when (val result = synthesizeSegmentLocked(request.copy(text = segment), nextSequence, onAudioChunk)) {
                is EngineResult.Failure -> return result
                is EngineResult.Success -> {
                    if (totalChunks == 0) firstAudioLatency = result.value.firstAudioLatencyMs
                    totalGenerationTime += result.value.totalGenerationTimeMs
                    totalAudioDuration += result.value.generatedAudioDurationMs
                    totalChunks += result.value.emittedChunks
                    nextSequence += result.value.emittedChunks
                }
            }
        }
        return EngineResult.Success(SynthesisMetrics(
            firstAudioLatencyMs = firstAudioLatency,
            totalGenerationTimeMs = totalGenerationTime,
            generatedAudioDurationMs = totalAudioDuration,
            emittedChunks = totalChunks,
        ))
    }

    private suspend fun synthesizeSegmentLocked(
        request: TtsRequest,
        sequenceStart: Int,
        onAudioChunk: suspend (AudioChunk) -> Unit,
    ): EngineResult<SynthesisMetrics> {
        val bundle = runtime ?: createRuntime().also { runtime = it }
        if (request.speed != 1.0f) {
            return EngineResult.Failure(EngineError(
                "UNSUPPORTED_SPEED", "تغيير السرعة غير موصول بعد؛ لم أغيّر معدل العينة بطريقة تؤثر في طبقة الصوت."
            ))
        }
        val prompt = resolvePromptCodes(request, bundle)
            ?: return EngineResult.Failure(EngineError(
                "VOICE_PROMPT_REQUIRED",
                "يتطلب MOSS معرف صوت صريحًا أو بيانات صوت مرجعي. لم يتم اختيار صوت افتراضي."
            ))
        require(prompt.isNotEmpty()) { "بيانات الصوت المرجعي فارغة." }
        val nVq = bundle.ttsConfig.nVq
        prompt.forEachIndexed { index, row ->
            require(row.size == nVq) { "صف الصوت المرجعي $index يحتوي ${row.size} رمزًا، والمتوقع $nVq." }
            row.forEachIndexed { channel, code ->
                require(code in 0 until bundle.ttsConfig.codebookSizes[channel]) {
                    "رمز صوت مرجعي خارج نطاق codebook في الصف $index والقناة $channel."
                }
            }
        }

        val started = System.nanoTime()
        val tokenizer = bundle.tokenizer
        val textTokenIds = tokenizer.encode(request.text)
        require(textTokenIds.isNotEmpty()) { "لم ينتج SentencePiece أي رموز للنص." }
        val rows = buildRequestRows(textTokenIds, prompt, bundle)
        val runOptions = bundle.runOptions
        runOptions.setTerminate(false)
        activeRunOptions.set(runOptions)
        bundle.codecDecoder.reset()

        var pastResult: OrtSession.Result? = null
        var globalHidden: OnnxTensor? = null
        var firstAudioLatency = 0L
        var generatedAudioDuration = 0L
        var emittedChunks = 0
        var sequence = sequenceStart
        var generatedFrames = 0
        val random = SecureRandom()
        val priorTokens = Array(nVq) { HashSet<Int>() }

        return try {
            ensureNotCancelled(request.cancellation)
            val inputFlat = flattenRows(rows)
            val inputIds = tensorInt(bundle.environment, inputFlat,
                longArrayOf(1L, rows.size.toLong(), (nVq + 1).toLong()))
            val prefillResult = try {
                tensorInt(bundle.environment, IntArray(rows.size) { 1 }, longArrayOf(1L, rows.size.toLong())).use { mask ->
                    bundle.prefillSession.run(mapOf("input_ids" to inputIds, "attention_mask" to mask), runOptions)
                }
            } finally {
                inputIds.close()
            }
            pastResult = prefillResult
            globalHidden = extractLastHidden(bundle.environment, requiredTensor(prefillResult, "global_hidden"))
            var pastValidLength = rows.size
            val maxFrames = (request.maxNewFrames ?: bundle.maxNewFrames).coerceAtMost(bundle.maxNewFrames)

            for (step in 0 until maxFrames) {
                ensureNotCancelled(request.cancellation)
                val frame = sampleFrame(globalHidden!!, priorTokens, random, bundle)
                    ?: break
                require(frame.size == nVq) { "نموذج MOSS أعاد إطارًا بعدد رموز غير متوقع: ${frame.size}" }
                for (channel in frame.indices) {
                    require(frame[channel] in 0 until bundle.ttsConfig.codebookSizes[channel]) {
                        "رمز صوت مولّد خارج نطاق codebook: القناة $channel، القيمة ${frame[channel]}"
                    }
                    priorTokens[channel].add(frame[channel])
                }

                val nextRow = IntArray(nVq + 1) { index ->
                    if (index == 0) bundle.ttsConfig.audioAssistantSlotTokenId
                    else bundle.ttsConfig.audioPadTokenId
                }
                for (index in frame.indices) nextRow[index + 1] = frame[index]
                val decodeResult = runDecodeStep(nextRow, pastValidLength, pastResult!!, bundle)
                val nextHidden = try {
                    extractLastHidden(bundle.environment, requiredTensor(decodeResult, "global_hidden"))
                } catch (error: Throwable) {
                    try { decodeResult.close() } catch (_: Exception) { }
                    throw error
                }
                pastResult!!.close()
                pastResult = decodeResult
                globalHidden!!.close()
                globalHidden = nextHidden
                pastValidLength += 1
                generatedFrames += 1

                val decoded = bundle.codecDecoder.decodeFrames(listOf(frame))
                if (decoded != null && decoded.samples.isNotEmpty()) {
                    ensureNotCancelled(request.cancellation)
                    val chunk = AudioChunk(
                        samples = decoded.samples,
                        sampleRate = decoded.sampleRate,
                        channels = decoded.channels,
                        sequence = sequence++,
                    )
                    if (emittedChunks == 0) firstAudioLatency = (System.nanoTime() - started) / 1_000_000L
                    onAudioChunk(chunk)
                    emittedChunks += 1
                    generatedAudioDuration += chunk.durationMs
                }
            }
            ensureNotCancelled(request.cancellation)
            val metrics = SynthesisMetrics(
                firstAudioLatencyMs = firstAudioLatency,
                totalGenerationTimeMs = (System.nanoTime() - started) / 1_000_000L,
                generatedAudioDurationMs = generatedAudioDuration,
                emittedChunks = emittedChunks,
            )
            if (generatedFrames == 0 || emittedChunks == 0) {
                EngineResult.Failure(EngineError("NO_AUDIO", "انتهى النموذج دون إنتاج إطار صوتي قابل للتشغيل."))
            } else {
                EngineResult.Success(metrics)
            }
        } catch (error: Throwable) {
            val cancelled = isCancellationRequested(request.cancellation) || error.message?.contains("terminated", true) == true
            EngineResult.Failure(EngineError(
                if (cancelled) "CANCELLED" else "INFERENCE_FAILED",
                if (cancelled) "أُلغي توليد الصوت." else (error.message ?: "تعذر إكمال استدلال MOSS"),
                error,
                !cancelled,
            ))
        } finally {
            try { globalHidden?.close() } catch (_: Exception) { }
            try { pastResult?.close() } catch (_: Exception) { }
            activeRunOptions.compareAndSet(runOptions, null)
            try { runOptions.setTerminate(false) } catch (_: Exception) { }
            bundle.codecDecoder.reset()
        }
    }

    /** Sentence-aware bounded chunks keep prompt+text prefill within practical KV-cache limits. */
    private fun splitTextIntoSegments(raw: String, maxChars: Int = 280): List<String> {
        val text = raw.replace('\r', ' ').replace('\n', ' ').trim().replace(Regex("\\s+"), " ")
        if (text.length <= maxChars) return listOf(text)
        val sentences = ArrayList<String>()
        val current = StringBuilder()
        for (character in text) {
            current.append(character)
            if (character in setOf('.', '!', '?', '؟', '。', '！', '؛', ';')) {
                val sentence = current.toString().trim()
                if (sentence.isNotEmpty()) sentences += sentence
                current.setLength(0)
            }
        }
        if (current.isNotEmpty()) current.toString().trim().takeIf { it.isNotEmpty() }?.let(sentences::add)

        val bounded = ArrayList<String>()
        for (sentence in sentences) {
            var rest = sentence
            while (rest.length > maxChars) {
                var cut = rest.lastIndexOf(' ', maxChars)
                if (cut < maxChars / 2) cut = maxChars
                bounded += rest.substring(0, cut).trim()
                rest = rest.substring(cut).trimStart()
            }
            if (rest.isNotEmpty()) bounded += rest
        }
        val packed = ArrayList<String>()
        var pending = StringBuilder()
        for (part in bounded) {
            if (pending.isNotEmpty() && pending.length + 1 + part.length > maxChars) {
                packed += pending.toString()
                pending = StringBuilder()
            }
            if (pending.isNotEmpty()) pending.append(' ')
            pending.append(part)
        }
        if (pending.isNotEmpty()) packed += pending.toString()
        return packed.ifEmpty { listOf(text) }
    }

    private fun resolvePromptCodes(request: TtsRequest, bundle: RuntimeBundle): List<IntArray>? {
        request.promptAudioCodes?.takeIf { it.isNotEmpty() }?.let { return it }
        val requestedVoice = request.voiceId?.takeIf { it.isNotBlank() } ?: return null
        val voices = bundle.manifest.optJSONArray("builtin_voices") ?: return null
        for (index in 0 until voices.length()) {
            val voice = voices.getJSONObject(index)
            if (voice.optString("voice") == requestedVoice) {
                val codes = voice.optJSONArray("prompt_audio_codes") ?: return null
                return List(codes.length()) { rowIndex ->
                    val row = codes.getJSONArray(rowIndex)
                    IntArray(row.length()) { codeIndex -> row.getInt(codeIndex) }
                }
            }
        }
        error("معرف الصوت غير موجود في manifest: $requestedVoice")
    }

    private fun buildRequestRows(textIds: IntArray, prompt: List<IntArray>, bundle: RuntimeBundle): List<IntArray> {
        val cfg = bundle.ttsConfig
        val n = cfg.nVq + 1
        val rows = ArrayList<IntArray>()
        fun addText(tokens: IntArray) {
            tokens.forEach { token ->
                rows += IntArray(n) { index -> if (index == 0) token else cfg.audioPadTokenId }
            }
        }
        addText(bundle.promptTemplates.userPrefix + intArrayOf(cfg.audioStartTokenId))
        prompt.forEach { codeRow ->
            rows += IntArray(n) { index ->
                when {
                    index == 0 -> cfg.audioUserSlotTokenId
                    else -> codeRow[index - 1]
                }
            }
        }
        addText(intArrayOf(cfg.audioEndTokenId) + bundle.promptTemplates.userAfterReference +
            textIds + bundle.promptTemplates.assistantPrefix + intArrayOf(cfg.audioStartTokenId))
        require(rows.isNotEmpty()) { "طلب MOSS لا يحتوي صفوفًا." }
        return rows
    }

    private fun sampleFrame(
        globalHidden: OnnxTensor,
        priorTokens: Array<HashSet<Int>>,
        random: SecureRandom,
        bundle: RuntimeBundle,
    ): IntArray? {
        val cfg = bundle.ttsConfig
        val codebookSize = cfg.codebookSizes.first()
        val seen = IntArray(cfg.nVq * codebookSize)
        for (channel in priorTokens.indices) {
            val base = channel * codebookSize
            priorTokens[channel].forEach { token -> if (token in 0 until codebookSize) seen[base + token] = 1 }
        }
        val assistantRandom = floatArrayOf(random.nextDouble().coerceIn(0.000001, 0.999999).toFloat())
        val audioRandom = FloatArray(cfg.nVq) { random.nextDouble().coerceIn(0.000001, 0.999999).toFloat() }
        val feeds = linkedMapOf<String, OnnxTensor>()
        try {
            feeds["global_hidden"] = globalHidden
            feeds["repetition_seen_mask"] = tensorInt(bundle.environment, seen,
                longArrayOf(1L, cfg.nVq.toLong(), codebookSize.toLong()))
            feeds["assistant_random_u"] = tensorFloat(bundle.environment, assistantRandom, longArrayOf(1L))
            feeds["audio_random_u"] = tensorFloat(bundle.environment, audioRandom, longArrayOf(1L, cfg.nVq.toLong()))
            bundle.localFixedFrameSession.run(feeds, bundle.runOptions).use { result ->
                val shouldContinue = readIntValues(requiredTensor(result, "should_continue")).firstOrNull() ?: 0
                if (shouldContinue <= 0) return null
                return readIntValues(requiredTensor(result, "frame_token_ids"))
            }
        } finally {
            feeds.filterKeys { it != "global_hidden" }.values.forEach { try { it.close() } catch (_: Exception) { } }
        }
    }

    private fun runDecodeStep(
        nextRow: IntArray,
        pastValidLength: Int,
        previous: OrtSession.Result,
        bundle: RuntimeBundle,
    ): OrtSession.Result {
        val feeds = LinkedHashMap<String, OnnxTensorLike>()
        val owned = ArrayList<OnnxTensor>()
        try {
            val ids = tensorInt(bundle.environment, nextRow, longArrayOf(1L, 1L, nextRow.size.toLong()))
            val length = tensorInt(bundle.environment, intArrayOf(pastValidLength), longArrayOf(1L))
            owned += ids
            owned += length
            feeds["input_ids"] = ids
            feeds["past_valid_lengths"] = length
            val inputNames = bundle.decodeInputNames
            val outputNames = bundle.decodeOutputNames
            require(inputNames.size >= 2 && inputNames.size - 2 == outputNames.size - 1) {
                "تعارض أسماء KV cache في metadata: inputs=${inputNames.size}, outputs=${outputNames.size}"
            }
            for (index in 2 until inputNames.size) {
                feeds[inputNames[index]] = requiredTensor(previous, outputNames[index - 1])
            }
            return bundle.decodeSession.run(feeds, bundle.runOptions)
        } finally {
            owned.forEach { try { it.close() } catch (_: Exception) { } }
        }
    }

    private fun createRuntime(): RuntimeBundle {
        val assets = ResolvedModelAssets.from(appContext)
        val environment = OrtEnvironment.getEnvironment()
        val options = OrtSession.SessionOptions().apply {
            setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            setIntraOpNumThreads(cpuThreads.coerceIn(1, 4))
            setInterOpNumThreads(1)
        }
        val runOptions = OrtSession.RunOptions()
        var tokenizer: SentencePieceTokenizer? = null
        val sessions = ArrayList<OrtSession>()
        try {
            tokenizer = SentencePieceTokenizer(assets.tokenizerFile.absolutePath)
            val ttsDir = assets.ttsMetaFile.parentFile!!
            val codecDir = assets.codecMetaFile.parentFile!!
            fun session(file: File): OrtSession {
                require(file.isFile) { "ملف ONNX مفقود: ${file.absolutePath}" }
                return environment.createSession(file.absolutePath, options).also { sessions += it }
            }
            val ttsFiles = assets.ttsMeta.getJSONObject("files")
            val codecFiles = assets.codecMeta.getJSONObject("files")
            val prefill = session(File(ttsDir, ttsFiles.getString("prefill")))
            val decode = session(File(ttsDir, ttsFiles.getString("decode_step")))
            val localFrame = session(File(ttsDir, ttsFiles.getString("local_fixed_sampled_frame")))
            val codecDecodeStep = session(File(codecDir, codecFiles.getString("decode_step")))
            val bundle = RuntimeBundle(
                environment = environment,
                options = options,
                runOptions = runOptions,
                tokenizer = tokenizer,
                manifest = assets.manifest,
                ttsConfig = TtsConfig.from(assets.manifest.getJSONObject("tts_config")),
                promptTemplates = PromptTemplates.from(assets.manifest.getJSONObject("prompt_templates")),
                maxNewFrames = assets.manifest.optJSONObject("generation_defaults")?.optInt("max_new_frames", 375)?.coerceIn(1, 2000) ?: 375,
                prefillSession = prefill,
                decodeSession = decode,
                localFixedFrameSession = localFrame,
                codecSession = codecDecodeStep,
                codecDecoder = MossCodecStreamingDecoder(environment, codecDecodeStep, assets.codecMeta, runOptions),
                decodeInputNames = assets.ttsMeta.getJSONObject("onnx").getJSONArray("decode_input_names").toStringList(),
                decodeOutputNames = assets.ttsMeta.getJSONObject("onnx").getJSONArray("decode_output_names").toStringList(),
            )
            return bundle
        } catch (error: Throwable) {
            sessions.asReversed().forEach { try { it.close() } catch (_: Exception) { } }
            try { runOptions.close() } catch (_: Exception) { }
            try { options.close() } catch (_: Exception) { }
            try { tokenizer?.close() } catch (_: Exception) { }
            throw error
        }
    }

    private fun isLifecycleRequestInvalidated(requestState: LifecycleState): Boolean {
        val current = lifecycleState.get()
        return requestState.pendingReleases > 0 ||
            current.pendingReleases > 0 ||
            current.generation != requestState.generation
    }

    private fun isCancellationRequested(cancellation: GenerationCancellation?): Boolean {
        return lifecycleState.get().pendingReleases > 0 ||
            cancellation?.isCancelled() == true
    }

    private fun ensureNotCancelled(cancellation: GenerationCancellation?) {
        if (isCancellationRequested(cancellation)) {
            throw IllegalStateException("generation cancelled")
        }
    }

    private fun tensorInt(env: OrtEnvironment, values: IntArray, shape: LongArray) =
        OnnxTensor.createTensor(env, IntBuffer.wrap(values), shape)

    private fun tensorFloat(env: OrtEnvironment, values: FloatArray, shape: LongArray) =
        OnnxTensor.createTensor(env, FloatBuffer.wrap(values), shape)

    private fun flattenRows(rows: List<IntArray>): IntArray {
        val width = rows.first().size
        require(rows.all { it.size == width }) { "صفوف الإدخال لا تملك عرضًا موحدًا." }
        return IntArray(rows.size * width).also { target ->
            var offset = 0
            rows.forEach { row -> row.copyInto(target, offset); offset += width }
        }
    }

    private fun requiredTensor(result: OrtSession.Result, name: String): OnnxTensor =
        result.get(name).orElse(null) as? OnnxTensor ?: error("مخرج ONNX المطلوب غير موجود أو ليس Tensor: $name")

    private fun extractLastHidden(env: OrtEnvironment, tensor: OnnxTensor): OnnxTensor {
        val shape = tensor.info.shape
        require(shape.size == 2 || shape.size == 3) { "global_hidden shape غير مدعوم: ${shape.contentToString()}" }
        require(shape[0] == 1L && shape.last() > 0L) { "global_hidden batch/hidden dimensions غير صالحة." }
        val hiddenSize = shape.last().toInt()
        val sequenceLength = if (shape.size == 2) 1 else shape[1].toInt()
        require(sequenceLength > 0) { "global_hidden sequence فارغ." }
        val buffer = tensor.floatBuffer ?: error("global_hidden ليس Float32.")
        val values = FloatArray(buffer.remaining())
        buffer.get(values)
        val start = (sequenceLength - 1) * hiddenSize
        require(start >= 0 && start + hiddenSize <= values.size) { "global_hidden buffer لا يطابق أبعاده." }
        return tensorFloat(env, values.copyOfRange(start, start + hiddenSize), longArrayOf(1L, hiddenSize.toLong()))
    }

    private fun readIntValues(tensor: OnnxTensor): IntArray = when (tensor.info.type.name) {
        "INT32" -> {
            val b = tensor.intBuffer ?: error("Tensor INT32 بلا IntBuffer")
            IntArray(b.remaining()).also { b.get(it) }
        }
        "INT64" -> {
            val b = tensor.longBuffer ?: error("Tensor INT64 بلا LongBuffer")
            IntArray(b.remaining()) { b.get().toInt() }
        }
        else -> error("نوع Tensor الصحيح غير مدعوم: ${tensor.info.type}")
    }

    private data class TtsConfig(
        val nVq: Int,
        val audioPadTokenId: Int,
        val audioStartTokenId: Int,
        val audioEndTokenId: Int,
        val audioUserSlotTokenId: Int,
        val audioAssistantSlotTokenId: Int,
        val codebookSizes: IntArray,
    ) {
        companion object {
            fun from(json: JSONObject): TtsConfig {
                val array = json.getJSONArray("audio_codebook_sizes")
                val sizes = IntArray(array.length()) { array.getInt(it) }
                val n = json.getInt("n_vq")
                require(n > 0 && sizes.size == n && sizes.all { it > 0 }) { "MOSS tts_config codebook metadata غير متسق." }
                return TtsConfig(n, json.getInt("audio_pad_token_id"), json.getInt("audio_start_token_id"),
                    json.getInt("audio_end_token_id"), json.optInt("audio_user_slot_token_id", 8),
                    json.getInt("audio_assistant_slot_token_id"), sizes)
            }
        }
    }

    private data class PromptTemplates(
        val userPrefix: IntArray,
        val userAfterReference: IntArray,
        val assistantPrefix: IntArray,
    ) {
        companion object {
            fun from(json: JSONObject): PromptTemplates = PromptTemplates(
                json.getJSONArray("user_prompt_prefix_token_ids").toIntArray(),
                json.getJSONArray("user_prompt_after_reference_token_ids").toIntArray(),
                json.getJSONArray("assistant_prompt_prefix_token_ids").toIntArray(),
            )
        }
    }

    private class RuntimeBundle(
        val environment: OrtEnvironment,
        private val options: OrtSession.SessionOptions,
        val runOptions: OrtSession.RunOptions,
        val tokenizer: SentencePieceTokenizer,
        val manifest: JSONObject,
        val ttsConfig: TtsConfig,
        val promptTemplates: PromptTemplates,
        val maxNewFrames: Int,
        val prefillSession: OrtSession,
        val decodeSession: OrtSession,
        val localFixedFrameSession: OrtSession,
        val codecSession: OrtSession,
        val codecDecoder: MossCodecStreamingDecoder,
        val decodeInputNames: List<String>,
        val decodeOutputNames: List<String>,
    ) : Closeable {
        override fun close() {
            try { codecSession.close() } catch (_: Exception) { }
            try { localFixedFrameSession.close() } catch (_: Exception) { }
            try { decodeSession.close() } catch (_: Exception) { }
            try { prefillSession.close() } catch (_: Exception) { }
            try { runOptions.close() } catch (_: Exception) { }
            try { tokenizer.close() } catch (_: Exception) { }
            try { options.close() } catch (_: Exception) { }
        }
    }
}

private fun JSONArray.toIntArray(): IntArray = IntArray(length()) { getInt(it) }
private fun JSONArray.toStringList(): List<String> = List(length()) { getString(it) }
