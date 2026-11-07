package com.aram.voiceengine.runtime

import android.content.Context
import org.json.JSONObject
import java.io.File

/** Resolves the already-installed Hugging Face assets without moving or downloading them. */
internal data class ResolvedModelAssets(
    val modelsRoot: File,
    val manifestFile: File,
    val ttsMetaFile: File,
    val codecMetaFile: File,
    val tokenizerFile: File,
    val manifest: JSONObject,
    val ttsMeta: JSONObject,
    val codecMeta: JSONObject,
) {
    companion object {
        fun from(context: Context): ResolvedModelAssets = resolve(File(context.filesDir, "models"))

        fun resolve(modelsDirectory: File): ResolvedModelAssets {
            require(modelsDirectory.isDirectory) {
                "مجلد النماذج غير موجود: ${modelsDirectory.absolutePath}"
            }
            val root = modelsDirectory.canonicalFile
            val manifestFile = listOf(
                File(root, "moss/tts/browser_poc_manifest.json"),
                File(root, "browser_poc_manifest.json"),
            ).firstOrNull { it.isFile && it.canonicalFile.isInside(root) }
                ?: root.walkTopDown().firstOrNull {
                    it.isFile && it.name == "browser_poc_manifest.json" && it.canonicalFile.isInside(root)
                }
                ?: error("لم أعثر على browser_poc_manifest.json داخل ${root.absolutePath}")

            val manifest = readJson(manifestFile)
            val modelFiles = manifest.getJSONObject("model_files")
            val ttsMetaFile = resolveAsset(root, manifestFile.parentFile!!,
                modelFiles.getString("tts_meta"), File(root, "moss/tts"))
            val codecMetaFile = resolveAsset(root, manifestFile.parentFile!!,
                modelFiles.getString("codec_meta"), File(root, "moss/codec"))
            val tokenizerName = modelFiles.optString("tokenizer_model", "tokenizer.model")
            val tokenizerFile = resolveAsset(root, manifestFile.parentFile!!,
                tokenizerName, File(root, "moss/tts"))

            val ttsMeta = readJson(ttsMetaFile)
            val codecMeta = readJson(codecMetaFile)
            validateExternalData(root, ttsMetaFile.parentFile!!,
                ttsMeta.optJSONObject("external_data_files"), "TTS")
            validateExternalData(root, codecMetaFile.parentFile!!,
                codecMeta.optJSONObject("external_data_files"), "Codec")

            val ttsFiles = ttsMeta.getJSONObject("files")
            listOf("prefill", "decode_step", "local_fixed_sampled_frame").forEach { key ->
                val model = File(ttsMetaFile.parentFile, ttsFiles.getString(key)).canonicalFile
                require(model.isFile && model.isInside(ttsMetaFile.parentFile)) { "ملف نموذج TTS مفقود أو خارج مجلد TTS: $model" }
            }
            val codecDecode = File(codecMetaFile.parentFile,
                codecMeta.getJSONObject("files").getString("decode_step")).canonicalFile
            require(codecDecode.isFile && codecDecode.isInside(codecMetaFile.parentFile)) { "ملف فك Codec المتدفق مفقود أو خارج مجلد Codec: $codecDecode" }
            require(tokenizerFile.isFile && tokenizerFile.isInside(root)) { "ملف tokenizer.model مفقود" }

            return ResolvedModelAssets(root, manifestFile, ttsMetaFile, codecMetaFile,
                tokenizerFile, manifest, ttsMeta, codecMeta)
        }

        private fun resolveAsset(root: File, base: File, relativeName: String, preferred: File): File {
            val direct = File(base, relativeName).canonicalFile
            if (direct.isFile && direct.isInside(root)) return direct
            val preferredFile = File(preferred, File(relativeName).name).canonicalFile
            if (preferredFile.isFile && preferredFile.isInside(root)) return preferredFile
            val name = File(relativeName).name
            val matches = root.walkTopDown().filter {
                it.isFile && it.name == name && it.canonicalFile.isInside(root)
            }.toList()
            require(matches.isNotEmpty()) { "ملف النموذج المشار إليه غير موجود: $relativeName" }
            if (matches.size == 1) return matches.single().canonicalFile
            val preferredMatches = matches.filter { it.parentFile.canonicalFile == preferred.canonicalFile }
            require(preferredMatches.size == 1) {
                "وجدت أكثر من نسخة للملف $name ولا يمكن اختيار مسارها بأمان: ${matches.joinToString()}"
            }
            return preferredMatches.single().canonicalFile
        }

        private fun validateExternalData(root: File, modelDirectory: File, mapping: JSONObject?, label: String) {
            if (mapping == null) return
            val keys = mapping.keys()
            while (keys.hasNext()) {
                val modelName = keys.next()
                val dataFiles = mapping.optJSONArray(modelName) ?: continue
                for (index in 0 until dataFiles.length()) {
                    val name = dataFiles.getString(index)
                    val file = File(modelDirectory, name).canonicalFile
                    require(file.isFile && file.isInside(root) && file.isInside(modelDirectory)) {
                        "ملف الأوزان الخارجية $label مفقود أو خارج مجلد النموذج نفسه: $file"
                    }
                }
            }
        }

        private fun readJson(file: File): JSONObject = JSONObject(file.readText(Charsets.UTF_8))
        private fun File.isInside(root: File): Boolean =
            canonicalPath == root.canonicalPath || canonicalPath.startsWith(root.canonicalPath + File.separator)
    }
}
