// Bruecke zwischen Java (Whisper.java) und whisper.cpp.
#include <jni.h>
#include <atomic>
#include <string>
#include <cstdio>
#include "whisper.h"

static std::atomic<bool> g_cancel(false);

struct Cb { JNIEnv *env; jobject obj; jmethodID mid; };

static void on_progress(struct whisper_context *, struct whisper_state *, int progress, void *ud) {
    Cb *c = (Cb *) ud;
    if (c && c->obj && c->mid) c->env->CallVoidMethod(c->obj, c->mid, (jint) progress);
}

static bool on_abort(void *) { return g_cancel.load(); }

static void json_str(std::string &o, const char *s) {
    o += '"';
    for (const unsigned char *p = (const unsigned char *) s; *p; p++) {
        unsigned char ch = *p;
        if (ch == '"') o += "\\\"";
        else if (ch == '\\') o += "\\\\";
        else if (ch == '\n') o += "\\n";
        else if (ch < 0x20) { char b[8]; snprintf(b, sizeof b, "\\u%04x", ch); o += b; }
        else o += (char) ch;
    }
    o += '"';
}

extern "C" JNIEXPORT jlong JNICALL
Java_de_foxdub_app_Whisper_init(JNIEnv *env, jclass, jstring jpath) {
    const char *path = env->GetStringUTFChars(jpath, nullptr);
    struct whisper_context_params cp = whisper_context_default_params();
    cp.use_gpu = false;
    struct whisper_context *ctx = whisper_init_from_file_with_params(path, cp);
    env->ReleaseStringUTFChars(jpath, path);
    return (jlong) ctx;
}

extern "C" JNIEXPORT void JNICALL
Java_de_foxdub_app_Whisper_free(JNIEnv *, jclass, jlong h) {
    if (h) whisper_free((struct whisper_context *) h);
}

extern "C" JNIEXPORT void JNICALL
Java_de_foxdub_app_Whisper_cancel(JNIEnv *, jclass, jboolean on) { g_cancel.store(on); }

// Liefert JSON: [{"s":Sekunden,"e":Sekunden,"t":"Text"}, ...] oder null bei Fehler/Abbruch.
extern "C" JNIEXPORT jstring JNICALL
Java_de_foxdub_app_Whisper_full(JNIEnv *env, jclass, jlong h, jfloatArray jpcm, jint n, jint threads, jobject cb) {
    struct whisper_context *ctx = (struct whisper_context *) h;
    if (!ctx) return nullptr;
    Cb c{env, cb, nullptr};
    if (cb) {
        jclass cls = env->GetObjectClass(cb);
        c.mid = env->GetMethodID(cls, "onProgress", "(I)V");
    }
    struct whisper_full_params p = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    p.n_threads = threads;
    p.language = "en";
    p.translate = false;
    p.print_progress = false;
    p.print_realtime = false;
    p.print_timestamps = false;
    p.print_special = false;
    p.suppress_blank = true;
    p.suppress_nst = true;
    p.progress_callback = on_progress;
    p.progress_callback_user_data = &c;
    p.abort_callback = on_abort;
    p.abort_callback_user_data = nullptr;

    jfloat *pcm = env->GetFloatArrayElements(jpcm, nullptr);
    int r = whisper_full(ctx, p, pcm, n);
    env->ReleaseFloatArrayElements(jpcm, pcm, JNI_ABORT);
    if (r != 0 || g_cancel.load()) return nullptr;

    std::string o = "[";
    int ns = whisper_full_n_segments(ctx);
    for (int i = 0; i < ns; i++) {
        char b[64];
        snprintf(b, sizeof b, "%s{\"s\":%.2f,\"e\":%.2f,\"t\":", i ? "," : "",
                 whisper_full_get_segment_t0(ctx, i) / 100.0, whisper_full_get_segment_t1(ctx, i) / 100.0);
        o += b;
        json_str(o, whisper_full_get_segment_text(ctx, i));
        o += "}";
    }
    o += "]";
    return env->NewStringUTF(o.c_str());
}
