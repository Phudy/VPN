package com.phudy.redline.speech

import com.google.mlkit.nl.translate.TranslateLanguage
import com.phudy.redline.translate.SubTranslator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

data class TimedCue(val startMs: Long, val endMs: Long, val original: String, val thai: String, val lang: String)

/**
 * Speech → Thai: whisper.cpp recognizes what is said (auto language detection, 99 languages),
 * then ML Kit translates it to Thai on the device. Languages ML Kit lacks go through
 * whisper's built-in speech→English translation first.
 */
object SpeechTranslator {
    private var ctx = 0L
    private var ctxModel: String? = null
    private val lock = Mutex()
    private val threads = Runtime.getRuntime().availableProcessors().coerceIn(2, 6)

    val available get() = WhisperNative.loaded

    private fun ensure(): Boolean {
        val path = SpeechModels.usablePath() ?: return false
        if (ctx != 0L && ctxModel == path) return true
        if (ctx != 0L) WhisperNative.free(ctx)
        ctx = WhisperNative.init(path); ctxModel = path
        return ctx != 0L
    }

    /** [pcm] is 16 kHz mono; [offsetMs] is where it starts in the video. */
    suspend fun process(pcm: FloatArray, offsetMs: Long, langHint: String = "auto"): List<TimedCue> = withContext(Dispatchers.Default) {
        val raw = lock.withLock {
            if (!available) error("อุปกรณ์นี้ไม่รองรับ (ต้องเป็น ARM 64-bit)")
            if (!ensure()) error("ยังไม่ได้ติดตั้งโมเดลแปลเสียง")
            WhisperNative.transcribe(ctx, pcm, threads, false, langHint)
        }
        val lines = raw.lines()
        val lang = lines.firstOrNull().orEmpty().ifBlank { "en" }
        val segs = lines.drop(1).mapNotNull { l ->
            val p = l.split('\t', limit = 3)
            if (p.size < 3 || p[2].isBlank()) null else Triple(p[0].toLong(), p[1].toLong(), p[2].trim())
        }.filterNot { isNoise(it.third) }
        if (segs.isEmpty()) return@withContext emptyList()

        val mlkitOk = lang == "th" || TranslateLanguage.fromLanguageTag(lang) != null
        val english: List<String>? = if (!mlkitOk) {
            lock.withLock {
                val r = WhisperNative.transcribe(ctx, pcm, threads, true, lang)
                r.lines().drop(1).mapNotNull { it.split('\t', limit = 3).getOrNull(2)?.trim() }
            }
        } else null

        segs.mapIndexed { i, (t0, t1, text) ->
            val thai = when {
                lang == "th" -> text
                english != null -> SubTranslator.toThai(english.getOrNull(i) ?: text, "en")
                else -> runCatching { SubTranslator.toThai(text, lang) }.getOrDefault(text)
            }
            TimedCue(offsetMs + t0, offsetMs + maxOf(t1, t0 + 1200), text, thai, lang)
        }
    }

    private fun isNoise(t: String): Boolean {
        val s = t.trim()
        return s.isEmpty() || (s.startsWith("[") && s.endsWith("]")) || (s.startsWith("(") && s.endsWith(")")) ||
            s.all { !it.isLetterOrDigit() }
    }
}
