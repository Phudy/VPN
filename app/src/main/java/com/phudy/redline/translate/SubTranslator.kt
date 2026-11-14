package com.phudy.redline.translate

import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.languageid.LanguageIdentification
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import kotlinx.coroutines.tasks.await
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * On-device subtitle translation to Thai (Google ML Kit, ~30 MB model per language,
 * downloaded once, then works offline). Source language is auto-detected.
 */
object SubTranslator {
    private val identifier by lazy { LanguageIdentification.getClient() }
    private val translators = ConcurrentHashMap<String, Translator>()
    private val memo = object : LinkedHashMap<String, String>(256, 0.75f, true) {
        override fun removeEldestEntry(e: MutableMap.MutableEntry<String, String>?) = size > 800
    }
    /** Language whose Thai translation model is downloading right now (first use, ~30 MB). */
    val downloadingModel = MutableStateFlow<String?>(null)
    @Volatile var lastSource: String? = null
        private set

    suspend fun toThai(text: String, hintLang: String? = null): String {
        val clean = text.trim()
        if (clean.isEmpty()) return ""
        synchronized(memo) { memo[clean] }?.let { return it }
        val detected = hintLang?.take(2)?.lowercase()?.takeIf { it != "un" }
            ?: runCatching { identifier.identifyLanguage(clean).await() }.getOrNull()?.takeIf { it != "und" }
            ?: lastSource ?: TranslateLanguage.ENGLISH
        val src = TranslateLanguage.fromLanguageTag(detected) ?: return clean
        lastSource = src
        if (src == TranslateLanguage.THAI) return clean
        val tr = translators.getOrPut(src) {
            Translation.getClient(
                TranslatorOptions.Builder().setSourceLanguage(src).setTargetLanguage(TranslateLanguage.THAI).build()
            )
        }
        downloadingModel.value = src
        try { tr.downloadModelIfNeeded(DownloadConditions.Builder().build()).await() } finally { downloadingModel.value = null }
        val out = tr.translate(clean).await()
        synchronized(memo) { memo[clean] = out }
        return out
    }

    fun languageName(code: String?): String = when (code) {
        null -> "อัตโนมัติ"
        "en" -> "อังกฤษ"; "ja" -> "ญี่ปุ่น"; "zh" -> "จีน"; "ko" -> "เกาหลี"; "es" -> "สเปน"
        "fr" -> "ฝรั่งเศส"; "de" -> "เยอรมัน"; "ru" -> "รัสเซีย"; "pt" -> "โปรตุเกส"; "it" -> "อิตาลี"
        "vi" -> "เวียดนาม"; "id" -> "อินโดนีเซีย"; "th" -> "ไทย"
        else -> code
    }
}
