// JNI bridge: com.phudy.redline.speech.WhisperNative
#include <jni.h>
#include <string>
#include <android/log.h>
#include "whisper.h"

#define LOG(...) __android_log_print(ANDROID_LOG_INFO, "RedlineWhisper", __VA_ARGS__)

extern "C" {

JNIEXPORT jlong JNICALL
Java_com_phudy_redline_speech_WhisperNative_init(JNIEnv *env, jobject, jstring path) {
    const char *p = env->GetStringUTFChars(path, nullptr);
    whisper_context_params cp = whisper_context_default_params();
    cp.use_gpu = false;
    whisper_context *ctx = whisper_init_from_file_with_params(p, cp);
    env->ReleaseStringUTFChars(path, p);
    return reinterpret_cast<jlong>(ctx);
}

JNIEXPORT void JNICALL
Java_com_phudy_redline_speech_WhisperNative_free(JNIEnv *, jobject, jlong ctx) {
    if (ctx) whisper_free(reinterpret_cast<whisper_context *>(ctx));
}

/**
 * Runs speech recognition on 16 kHz mono float PCM.
 * Returns "lang\n" followed by one line per segment: "t0ms\tt1ms\ttext".
 */
JNIEXPORT jstring JNICALL
Java_com_phudy_redline_speech_WhisperNative_transcribe(JNIEnv *env, jobject, jlong ctxPtr, jfloatArray audio,
                                                       jint threads, jboolean translateToEnglish, jstring lang) {
    auto *ctx = reinterpret_cast<whisper_context *>(ctxPtr);
    if (!ctx) return env->NewStringUTF("");
    const char *l = env->GetStringUTFChars(lang, nullptr);
    std::string language(l);
    env->ReleaseStringUTFChars(lang, l);

    whisper_full_params p = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    p.n_threads = threads;
    p.translate = translateToEnglish;
    p.language = language.c_str();
    p.detect_language = false;
    p.no_context = true;
    p.single_segment = false;
    p.print_progress = false;
    p.print_realtime = false;
    p.print_special = false;
    p.print_timestamps = false;
    p.suppress_blank = true;
    p.suppress_nst = true;

    jsize n = env->GetArrayLength(audio);
    jfloat *pcm = env->GetFloatArrayElements(audio, nullptr);
    int rc = whisper_full(ctx, p, pcm, n);
    env->ReleaseFloatArrayElements(audio, pcm, JNI_ABORT);
    if (rc != 0) { LOG("whisper_full failed: %d", rc); return env->NewStringUTF(""); }

    std::string out = whisper_lang_str(whisper_full_lang_id(ctx));
    out += "\n";
    int segs = whisper_full_n_segments(ctx);
    for (int i = 0; i < segs; ++i) {
        out += std::to_string(whisper_full_get_segment_t0(ctx, i) * 10) + "\t" +
               std::to_string(whisper_full_get_segment_t1(ctx, i) * 10) + "\t" +
               whisper_full_get_segment_text(ctx, i) + "\n";
    }
    return env->NewStringUTF(out.c_str());
}

JNIEXPORT jstring JNICALL
Java_com_phudy_redline_speech_WhisperNative_systemInfo(JNIEnv *env, jobject) {
    return env->NewStringUTF(whisper_print_system_info());
}

}
