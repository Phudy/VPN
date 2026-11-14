package com.phudy.redline.speech

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/** Whisper models published by the whisper.cpp project on Hugging Face (quantized, multilingual). */
data class SpeechModel(val id: String, val label: String, val sizeMb: Int, val note: String) {
    val url get() = "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-$id.bin"
}

val SPEECH_MODELS = listOf(
    SpeechModel("tiny-q5_1", "Tiny", 31, "เร็วที่สุด เหมาะแปลสด ความแม่นปานกลาง"),
    SpeechModel("base-q5_1", "Base", 57, "สมดุล แนะนำ"),
    SpeechModel("small-q5_1", "Small", 181, "แม่นสุด ช้ากว่า เหมาะแปลทั้งคลิปล่วงหน้า"),
)

object SpeechModels {
    val downloading = MutableStateFlow<String?>(null)
    val progress = MutableStateFlow(0f)
    val installed = MutableStateFlow<Set<String>>(emptySet())
    val selected = MutableStateFlow("base-q5_1")
    private lateinit var dir: File
    private lateinit var ctxApp: Context

    fun init(ctx: Context) {
        ctxApp = ctx.applicationContext
        dir = File(ctx.filesDir, "whisper").apply { mkdirs() }
        selected.value = ctx.getSharedPreferences("redline", Context.MODE_PRIVATE).getString("speech_model", "base-q5_1")!!
        refresh()
    }

    fun select(id: String) {
        selected.value = id
        ctxApp.getSharedPreferences("redline", Context.MODE_PRIVATE).edit().putString("speech_model", id).apply()
    }

    private fun refresh() { installed.value = SPEECH_MODELS.filter { file(it.id).exists() }.map { it.id }.toSet() }

    fun file(id: String) = File(dir, "ggml-$id.bin")

    /** Path of the selected model if installed, otherwise of any installed model. */
    fun usablePath(): String? {
        val sel = file(selected.value)
        if (sel.exists()) return sel.absolutePath
        return SPEECH_MODELS.map { file(it.id) }.firstOrNull { it.exists() }?.absolutePath
    }

    suspend fun download(id: String): Result<Unit> = withContext(Dispatchers.IO) {
        val m = SPEECH_MODELS.first { it.id == id }
        downloading.value = id; progress.value = 0f
        runCatching {
            val tmp = File(dir, "$id.part")
            var url = m.url
            var conn: HttpURLConnection
            var hops = 0
            while (true) {
                conn = URL(url).openConnection() as HttpURLConnection
                conn.instanceFollowRedirects = false
                conn.connectTimeout = 15000; conn.readTimeout = 30000
                val have = tmp.length()
                if (have > 0) conn.setRequestProperty("Range", "bytes=$have-")
                val code = conn.responseCode
                if (code in 300..399 && hops++ < 6) { url = URL(URL(url), conn.getHeaderField("Location")).toString(); conn.disconnect(); continue }
                if (code !in 200..299) error("HTTP $code")
                break
            }
            val append = conn.responseCode == 206
            val total = conn.contentLengthLong + (if (append) tmp.length() else 0)
            var done = if (append) tmp.length() else 0L
            conn.inputStream.use { ins ->
                java.io.FileOutputStream(tmp, append).use { out ->
                    val buf = ByteArray(256 * 1024)
                    while (true) {
                        val n = ins.read(buf); if (n < 0) break
                        out.write(buf, 0, n); done += n
                        if (total > 0) progress.value = done.toFloat() / total
                    }
                }
            }
            if (!tmp.renameTo(file(id))) error("บันทึกโมเดลไม่ได้")
            Unit
        }.also { downloading.value = null; refresh() }
    }

    fun delete(id: String) { file(id).delete(); refresh() }
}
