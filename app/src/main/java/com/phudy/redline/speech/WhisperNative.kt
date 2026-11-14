package com.phudy.redline.speech

import java.io.File

/** Thin JNI wrapper around whisper.cpp (third_party/whisper.cpp). */
object WhisperNative {
    val loaded: Boolean
    val variant: String

    init {
        val fp16 = runCatching {
            val info = File("/proc/cpuinfo").readText()
            info.contains("fphp") && info.contains("asimdhp")
        }.getOrDefault(false)
        var name = "none"
        val ok = runCatching { if (fp16) { System.loadLibrary("whisper_fp16"); name = "arm64 fp16" } else error("no fp16") }.isSuccess ||
            runCatching { System.loadLibrary("whisper"); name = "arm64" }.isSuccess
        loaded = ok
        variant = name
    }

    external fun init(modelPath: String): Long
    external fun free(ctx: Long)
    external fun transcribe(ctx: Long, pcm16k: FloatArray, threads: Int, translateToEnglish: Boolean, lang: String): String
    external fun systemInfo(): String
}
