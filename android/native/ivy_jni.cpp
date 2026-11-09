// JNI bridge for com.example.engine.IvyNative. Results come back as one JSON string (text + timings).
#include <jni.h>

#include <string>
#include <vector>

#include "ggml-backend.h"
#include "ivy_engine.h"

static std::string g_err;

static std::string esc(const std::string & s) {
    std::string o;
    for (char c : s) {
        switch (c) {
            case '"': o += "\\\""; break;
            case '\\': o += "\\\\"; break;
            case '\n': o += "\\n"; break;
            case '\r': break;
            case '\t': o += "\\t"; break;
            default: o += c;
        }
    }
    return o;
}

static std::string to_json(const std::string & text, const IvyReport & r) {
    char buf[512];
    snprintf(buf, sizeof(buf),
             "\"total_ms\":%.0f,\"read_ms\":%.0f,\"write_ms\":%.0f,\"reused\":%d,\"at_end\":%d,\"tokens\":%d,"
             "\"drafts\":%d,\"draft_tokens\":%d,\"proposed\":%d,\"accepted\":%d,\"passes\":%d,",
             r.total_ms, r.read_ms, r.write_ms, r.windows_reused, r.windows_at_end, r.out_tokens,
             r.drafts, r.draft_tokens, r.proposed, r.accepted, r.passes);
    return "{" + std::string(buf) + "\"note\":\"" + esc(r.note) + "\",\"text\":\"" + esc(text) + "\"}";
}

static std::string jstr(JNIEnv * env, jstring s) {
    const char * c = env->GetStringUTFChars(s, nullptr);
    std::string out(c);
    env->ReleaseStringUTFChars(s, c);
    return out;
}

// NewStringUTF needs modified UTF-8; the model's text is plain UTF-8, so build the jstring from bytes.
static jstring utf8(JNIEnv * env, const std::string & s) {
    jbyteArray bytes = env->NewByteArray((jsize) s.size());
    env->SetByteArrayRegion(bytes, 0, (jsize) s.size(), reinterpret_cast<const jbyte *>(s.data()));
    jclass str = env->FindClass("java/lang/String");
    jmethodID ctor = env->GetMethodID(str, "<init>", "([BLjava/lang/String;)V");
    jstring cs = env->NewStringUTF("UTF-8");
    auto out = (jstring) env->NewObject(str, ctor, bytes, cs);
    env->DeleteLocalRef(bytes);
    env->DeleteLocalRef(cs);
    return out;
}

static std::vector<std::string> dict_of(JNIEnv * env, jobjectArray arr) {
    std::vector<std::string> d;
    if (!arr) return d;
    jsize n = env->GetArrayLength(arr);
    for (jsize i = 0; i < n; i++) {
        auto s = (jstring) env->GetObjectArrayElement(arr, i);
        d.push_back(jstr(env, s));
        env->DeleteLocalRef(s);
    }
    return d;
}

extern "C" {

JNIEXPORT void JNICALL Java_com_example_engine_IvyNative_initBackends(JNIEnv * env, jobject, jstring lib_dir, jstring cache_dir) {
    ivy_backend_init(jstr(env, lib_dir).c_str(), jstr(env, cache_dir).c_str());
}

// Which CPU code the phone picked (e.g. "android_armv8.2_2 (kept); CPU [...]"), for the test log.
JNIEXPORT jstring JNICALL Java_com_example_engine_IvyNative_cpuInfo(JNIEnv * env, jobject) {
    std::string out = ivy_cpu_choice() + ";";
    for (size_t i = 0; i < ggml_backend_reg_count(); i++) {
        ggml_backend_reg_t r = ggml_backend_reg_get(i);
        out += std::string(out.empty() ? "" : ", ") + ggml_backend_reg_name(r);
        for (size_t j = 0; j < ggml_backend_reg_dev_count(r); j++)
            out += std::string(" [") + ggml_backend_dev_description(ggml_backend_reg_dev_get(r, j)) + "]";
    }
    return env->NewStringUTF(out.c_str());
}

JNIEXPORT jlong JNICALL Java_com_example_engine_IvyNative_load(JNIEnv * env, jobject, jstring model, jstring mmproj,
                                                                 jint threads) {
    IvyEngine * e = ivy_load(jstr(env, model).c_str(), jstr(env, mmproj).c_str(), threads, 0, g_err);
    return reinterpret_cast<jlong>(e);
}

JNIEXPORT jstring JNICALL Java_com_example_engine_IvyNative_lastError(JNIEnv * env, jobject) {
    return env->NewStringUTF(g_err.c_str());
}

JNIEXPORT void JNICALL Java_com_example_engine_IvyNative_free(JNIEnv *, jobject, jlong h) {
    ivy_free(reinterpret_cast<IvyEngine *>(h));
}

JNIEXPORT void JNICALL Java_com_example_engine_IvyNative_liveReset(JNIEnv * env, jobject, jlong h, jobjectArray dict) {
    ivy_live_reset(reinterpret_cast<IvyEngine *>(h), dict_of(env, dict));
}

JNIEXPORT jboolean JNICALL Java_com_example_engine_IvyNative_liveStep(JNIEnv * env, jobject, jlong h, jfloatArray pcm,
                                                                        jint n) {
    jfloat * p = env->GetFloatArrayElements(pcm, nullptr);
    bool did = ivy_live_step(reinterpret_cast<IvyEngine *>(h), p, (size_t) n);
    env->ReleaseFloatArrayElements(pcm, p, JNI_ABORT);
    return did;
}

JNIEXPORT void JNICALL Java_com_example_engine_IvyNative_liveInterrupt(JNIEnv *, jobject, jlong h) {
    ivy_live_interrupt(reinterpret_cast<IvyEngine *>(h));
}

JNIEXPORT jint JNICALL Java_com_example_engine_IvyNative_threads(JNIEnv *, jobject, jlong h) {
    return ivy_threads(reinterpret_cast<IvyEngine *>(h));
}

JNIEXPORT jstring JNICALL Java_com_example_engine_IvyNative_liveFinish(JNIEnv * env, jobject, jlong h, jfloatArray pcm,
                                                                         jint n) {
    jfloat * p = env->GetFloatArrayElements(pcm, nullptr);
    IvyReport r;
    std::string text = ivy_live_finish(reinterpret_cast<IvyEngine *>(h), p, (size_t) n, r);
    env->ReleaseFloatArrayElements(pcm, p, JNI_ABORT);
    return utf8(env, to_json(text, r));
}

JNIEXPORT jstring JNICALL Java_com_example_engine_IvyNative_transcribe(JNIEnv * env, jobject, jlong h, jfloatArray pcm,
                                                                         jint n, jobjectArray dict) {
    jfloat * p = env->GetFloatArrayElements(pcm, nullptr);
    IvyReport r;
    std::string text = ivy_transcribe(reinterpret_cast<IvyEngine *>(h), p, (size_t) n, dict_of(env, dict), r);
    env->ReleaseFloatArrayElements(pcm, p, JNI_ABORT);
    return utf8(env, to_json(text, r));
}

static IvyRewriter * g_rw = nullptr;

// "Make it professional": loads the small rewrite model once (first use).
JNIEXPORT jboolean JNICALL Java_com_example_engine_IvyNative_rewriteLoad(JNIEnv * env, jobject, jstring path) {
    if (g_rw) return true;
    g_rw = ivy_rewrite_load(jstr(env, path).c_str(), 0, g_err);
    return g_rw != nullptr;
}

// Frees the rewrite model (about 0.5 GB) so it does not push the speech model out of RAM between uses.
JNIEXPORT void JNICALL Java_com_example_engine_IvyNative_rewriteFree(JNIEnv *, jobject) {
    ivy_rewrite_free(g_rw);
    g_rw = nullptr;
}

JNIEXPORT jstring JNICALL Java_com_example_engine_IvyNative_rewrite(JNIEnv * env, jobject, jbyteArray utf8text) {
    jsize n = env->GetArrayLength(utf8text);
    std::string text(n, ' ');
    env->GetByteArrayRegion(utf8text, 0, n, reinterpret_cast<jbyte *>(text.data()));
    double ms = 0;
    std::string out = g_rw ? ivy_rewrite(g_rw, text, ms) : text;
    return utf8(env, out);
}

// "Fix the grammar" (mode 1) or "Make it professional" (mode 0) with the same small model.
JNIEXPORT jstring JNICALL Java_com_example_engine_IvyNative_rewriteMode(JNIEnv * env, jobject, jbyteArray utf8text, jint mode) {
    jsize n = env->GetArrayLength(utf8text);
    std::string text(n, ' ');
    env->GetByteArrayRegion(utf8text, 0, n, reinterpret_cast<jbyte *>(text.data()));
    double ms = 0;
    std::string out = g_rw ? ivy_rewrite(g_rw, text, ms, mode == 1 ? "Fix the grammar:\n" : "Make this professional:\n") : text;
    return utf8(env, out);
}

}  // extern "C"
