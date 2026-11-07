package com.aram.voiceengine.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.concurrent.Executors

/** First-run, resumable model installer. Model files stay in app-private storage. */
object ModelDownloadManager {
    private const val API = "https://huggingface.co/api/models/"
    private const val BASE = "https://huggingface.co/"
    private const val MARKER = ".ishtar-model-install.json"
    private val executor = Executors.newSingleThreadExecutor()

    data class Progress(val fileName: String, val done: Long, val total: Long)
    interface Listener {
        fun onProgress(value: Progress)
        fun onComplete()
        fun onError(message: String)
    }
    private data class Item(
        val repo: String, val revision: String, val name: String, val path: String,
        val size: Long, val sha256: String?
    )

    private val required = linkedMapOf(
        "OpenMOSS-Team/MOSS-TTS-Nano-100M-ONNX" to listOf(
            "moss_tts_prefill.onnx", "moss_tts_decode_step.onnx",
            "moss_tts_local_decoder.onnx", "moss_tts_local_cached_step.onnx",
            "moss_tts_local_fixed_sampled_frame.onnx", "moss_tts_global_shared.data",
            "moss_tts_local_shared.data", "tokenizer.model",
            "tts_browser_onnx_meta.json", "browser_poc_manifest.json"
        ),
        "OpenMOSS-Team/MOSS-Audio-Tokenizer-Nano-ONNX" to listOf(
            "moss_audio_tokenizer_encode.onnx", "moss_audio_tokenizer_encode.data",
            "moss_audio_tokenizer_decode_full.onnx", "moss_audio_tokenizer_decode_step.onnx",
            "moss_audio_tokenizer_decode_shared.data", "codec_browser_onnx_meta.json"
        )
    )

    fun isInstalled(context: Context): Boolean {
        val root = File(context.filesDir, "models")
        val marker = File(root, MARKER)
        if (!marker.isFile) return false
        return try {
            val files = JSONObject(marker.readText()).getJSONArray("files")
            if (files.length() != required.values.sumOf { it.size }) return false
            for (i in 0 until files.length()) {
                val item = files.getJSONObject(i)
                val f = File(root, item.getString("path"))
                if (!f.isFile || f.length() != item.getLong("size")) return false
            }
            true
        } catch (_: Exception) { false }
    }

    fun ensureInstalled(context: Context, listener: Listener) {
        val app = context.applicationContext
        executor.execute {
            try {
                if (isInstalled(app)) {
                    listener.onComplete()
                    return@execute
                }
                install(app, listener)
            } catch (e: Exception) {
                listener.onError(e.message ?: "تعذر تنزيل نماذج ISHTAR")
            }
        }
    }

    private fun install(context: Context, listener: Listener) {
        val root = File(context.filesDir, "models")
        if (!root.exists() && !root.mkdirs()) error("تعذر إنشاء مساحة تخزين النماذج")

        val items = ArrayList<Item>()
        val revisions = JSONObject()
        for ((repo, names) in required) {
            val meta = repositoryMetadata(repo)
            revisions.put(repo, meta.first)
            for (name in names) {
                val entry = meta.second[name] ?: error("الملف غير موجود في Hugging Face: $name")
                val size = entry.optLong("size", -1L)
                if (size <= 0L) error("حجم غير صالح للملف: $name")
                val lfs = entry.optJSONObject("lfs")
                var hash = lfs?.optString("sha256", null)
                if (hash.isNullOrBlank()) {
                    val oid = lfs?.optString("oid", null)
                    if (!oid.isNullOrBlank() && oid.startsWith("sha256:")) hash = oid.removePrefix("sha256:")
                }
                val folder = if (repo.contains("TTS-Nano-100M")) "moss/tts" else "moss/codec"
                items.add(Item(repo, meta.first, name, "$folder/$name", size, hash))
            }
        }

        val total = items.sumOf { it.size }
        var done = 0L
        val installed = JSONArray()
        for (item in items) {
            val dest = File(root, item.path)
            dest.parentFile?.let { if (!it.exists() && !it.mkdirs()) error("تعذر إنشاء مجلد النماذج") }
            val digest = download(item, dest, done, total, listener)
            installed.put(JSONObject().apply {
                put("path", item.path)
                put("size", item.size)
                put("sha256", digest)
                put("verifiedAgainstHub", !item.sha256.isNullOrBlank())
            })
            done += item.size
            listener.onProgress(Progress(item.name, done, total))
        }

        val record = JSONObject().apply {
            put("schema", 1)
            put("source", "Hugging Face")
            put("revisions", revisions)
            put("installedAt", System.currentTimeMillis())
            put("files", installed)
        }
        val temp = File(root, "$MARKER.tmp")
        temp.writeText(record.toString(2))
        val final = File(root, MARKER)
        if (final.exists() && !final.delete()) error("تعذر تحديث سجل التثبيت")
        if (!temp.renameTo(final)) error("تعذر اعتماد اكتمال تنزيل النماذج")
        listener.onComplete()
    }

    private fun repositoryMetadata(repo: String): Pair<String, Map<String, JSONObject>> {
        val c = (URL(API + repo + "?blobs=true").openConnection() as HttpURLConnection).apply {
            connectTimeout = 25000
            readTimeout = 30000
            setRequestProperty("User-Agent", "ISHTAR-Android/0.1")
            setRequestProperty("Accept", "application/json")
        }
        try {
            val code = c.responseCode
            if (code !in 200..299) error("تعذر قراءة بيانات Hugging Face (HTTP $code)")
            val json = JSONObject(c.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() })
            val files = HashMap<String, JSONObject>()
            val siblings = json.getJSONArray("siblings")
            for (i in 0 until siblings.length()) {
                val row = siblings.getJSONObject(i)
                val name = row.optString("rfilename")
                if (name.isNotBlank()) files[name] = row
            }
            return json.optString("sha", "main") to files
        } finally { c.disconnect() }
    }

    private fun download(
        item: Item, dest: File, completedBefore: Long, total: Long, listener: Listener
    ): String {
        if (dest.isFile && dest.length() == item.size) {
            val digest = sha256(dest)
            if (item.sha256.isNullOrBlank() || digest.equals(item.sha256, true)) return digest
            if (!dest.delete()) error("تعذر حذف ملف غير سليم: ${item.name}")
        }

        val part = File(dest.parentFile, dest.name + ".part")
        val state = File(dest.parentFile, dest.name + ".resume")
        var attempt = 0
        while (attempt < 3) {
            attempt++
            var offset = if (part.isFile) part.length() else 0L
            if (offset > item.size) {
                part.delete(); state.delete(); offset = 0L
            }
            val etag = if (state.isFile) state.readText().trim() else ""
            val filename = URLEncoder.encode(item.name, "UTF-8").replace("+", "%20")
            val url = URL("$BASE${item.repo}/resolve/${item.revision}/$filename?download=true")
            val c = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = 30000
                readTimeout = 60000
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", "ISHTAR-Android/0.1")
                setRequestProperty("Accept-Encoding", "identity")
                if (offset > 0L && etag.isNotBlank()) {
                    setRequestProperty("Range", "bytes=$offset-")
                    setRequestProperty("If-Range", etag)
                } else if (offset > 0L) {
                    part.delete()
                    offset = 0L
                }
            }
            try {
                val code = c.responseCode
                if (code != 200 && code != 206) error("فشل تنزيل ${item.name} (HTTP $code)")
                val responseTag = c.getHeaderField("ETag") ?: c.getHeaderField("X-Linked-Etag") ?: ""
                val append = code == 206 && offset > 0L
                if (append) {
                    val range = c.getHeaderField("Content-Range") ?: ""
                    if (!range.startsWith("bytes $offset-")) {
                        part.delete(); state.delete()
                        continue
                    }
                } else {
                    offset = 0L
                    if (part.exists()) part.delete()
                }
                if (responseTag.isNotBlank()) state.writeText(responseTag)
                BufferedInputStream(c.inputStream, 131072).use { input ->
                    BufferedOutputStream(FileOutputStream(part, append), 131072).use { output ->
                        val buffer = ByteArray(131072)
                        var count: Int
                        var current = offset
                        while (input.read(buffer).also { count = it } != -1) {
                            output.write(buffer, 0, count)
                            current += count
                            listener.onProgress(Progress(item.name, completedBefore + current, total))
                        }
                        output.flush()
                    }
                }
                if (!part.isFile || part.length() != item.size) error("التنزيل غير مكتمل: ${item.name}")
                val digest = sha256(part)
                if (!item.sha256.isNullOrBlank() && !digest.equals(item.sha256, true)) {
                    part.delete(); state.delete()
                    error("فشل فحص SHA-256 للملف: ${item.name}")
                }
                if (dest.exists() && !dest.delete()) error("تعذر استبدال الملف: ${item.name}")
                if (!part.renameTo(dest)) error("تعذر اعتماد الملف: ${item.name}")
                state.delete()
                return digest
            } catch (e: Exception) {
                if (attempt >= 3) throw e
            } finally { c.disconnect() }
        }
        error("تعذر إكمال تنزيل ${item.name}")
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buffer = ByteArray(131072)
            var count: Int
            while (input.read(buffer).also { count = it } != -1) digest.update(buffer, 0, count)
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
