// file: WhisperLib.c
// ============================================================
// ✅ whisper.cpp JNI Bridge — Safe Loader + Transcriber (Technical Commented Final C Edition)
// ------------------------------------------------------------
// • Three unified model loading paths: File / Asset / InputStream
// • Thread-safe JNIEnv attach/detach via cached JavaVM pointer
// • Exception-safe JNI operations (GlobalRef lifecycle guarded)
// • Defensive handling of AAsset_read() (error vs EOF) and NULL pointers
// • Segment index bounds checking + Bench API guards
// • Technical doc comments (KDoc-like) per function
// ============================================================

#include <jni.h>
#include <android/asset_manager.h>
#include <android/asset_manager_jni.h>
#include <android/log.h>
#include <stdlib.h>
#include <string.h>
#include <stdbool.h>
#include "whisper.h"

#define TAG "JNI-Whisper"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN,  TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

/**
 * Retrieves a valid JNIEnv* for the current thread.
 *
 * - If the current thread is not attached to the JVM, it is automatically attached.
 * - The `attached_by_us` flag is set when this function attaches a thread.
 * - The caller should call DetachCurrentThread() later if it attached.
 *
 * @param jvm Cached JavaVM pointer from GetJavaVM()
 * @param attached_by_us Pointer to int flag updated when attached (may be NULL)
 * @return Valid JNIEnv pointer or NULL if failed
 */
static inline JNIEnv* get_env_from_jvm(JavaVM* jvm, int* attached_by_us) {
    if (!jvm) return NULL;
    JNIEnv* env = NULL;
    if ((*jvm)->GetEnv(jvm, (void**)&env, JNI_VERSION_1_6) != JNI_OK) {
        if ((*jvm)->AttachCurrentThread(jvm, (JNIEnv **) (void **) &env, NULL) != 0) {
            LOGE("AttachCurrentThread() failed — cannot obtain JNIEnv");
            return NULL;
        }
        if (attached_by_us) *attached_by_us = 1;
    }
    return env;
}

/**
 * Data structure for streaming whisper models from Java InputStream.
 *
 * Fields:
 * - jvm: Cached JavaVM pointer
 * - input_stream: GlobalRef to Java InputStream
 * - mid_read: Cached method ID for InputStream.read(byte[], int, int)
 * - buffer_gl: GlobalRef to reusable byte[] buffer (64 KB)
 * - buf_len: buffer length
 * - eof: end-of-stream flag
 * - attached_by_us: set if current thread attached by native side
 */
struct input_stream_context {
    JavaVM   *jvm;
    jobject   input_stream;
    jmethodID mid_read;
    jobject   buffer_gl;
    jint      buf_len;
    int       eof;
    int       attached_by_us;
};

/**
 * Reads a block from the Java InputStream into native memory.
 *
 * Handles Java exceptions, EOF detection, and buffer copying.
 *
 * @param ctx Pointer to input_stream_context
 * @param output Native destination buffer
 * @param read_size Maximum bytes to read
 * @return Number of bytes read or 0 if EOF or error
 */
static size_t is_read(void *ctx, void *output, size_t read_size) {
    struct input_stream_context* is = (struct input_stream_context*)ctx;
    if (!is || !is->jvm) return 0;
    JNIEnv* env = get_env_from_jvm(is->jvm, &is->attached_by_us);
    if (!env) return 0;

    const jint chunk = (jint)((read_size > (size_t)is->buf_len) ? is->buf_len : read_size);
    const jint n = (*env)->CallIntMethod(env, is->input_stream, is->mid_read, is->buffer_gl, 0, chunk);

    if ((*env)->ExceptionCheck(env)) {
        LOGE("Exception during InputStream.read(%d): marking EOF", (int)chunk);
        (*env)->ExceptionDescribe(env);
        (*env)->ExceptionClear(env);
        is->eof = 1;
        return 0;
    }
    if (n <= 0) { is->eof = 1; return 0; }

    jbyte* ptr = (*env)->GetByteArrayElements(env, (jbyteArray)is->buffer_gl, NULL);
    if (!ptr) {
        LOGE("GetByteArrayElements() returned NULL");
        is->eof = 1;
        return 0;
    }
    memcpy(output, ptr, (size_t)n);
    (*env)->ReleaseByteArrayElements(env, (jbyteArray)is->buffer_gl, ptr, JNI_ABORT);
    return (size_t)n;
}

/** Returns EOF flag for InputStream loader. */
static bool is_eof(void *ctx) {
    struct input_stream_context* is = (struct input_stream_context*)ctx;
    return is ? (is->eof != 0) : true;
}

/**
 * Closes the InputStream loader context, deletes global references,
 * detaches thread if self-attached, and frees the context.
 *
 * @param ctx Pointer to input_stream_context
 */
static void is_close(void *ctx) {
    struct input_stream_context* is = (struct input_stream_context*)ctx;
    if (!is) return;

    JNIEnv* env = get_env_from_jvm(is->jvm, NULL);
    if (env) {
        if (is->input_stream) {
            (*env)->DeleteGlobalRef(env, is->input_stream);
            is->input_stream = NULL;
        }
        if (is->buffer_gl) {
            (*env)->DeleteGlobalRef(env, is->buffer_gl);
            is->buffer_gl = NULL;
        }
    }
    if (is->jvm && is->attached_by_us) {
        (*is->jvm)->DetachCurrentThread(is->jvm);
    }
    free(is);
}

/**
 * Initializes whisper_context by streaming model bytes from a Java InputStream.
 *
 * Thread-safe: yes, initialization runs synchronously.
 *
 * @param env JNI environment
 * @param clazz Java class (unused)
 * @param input_stream Java InputStream for model
 * @return pointer to whisper_context or 0 if failed
 */
JNIEXPORT jlong JNICALL
Java_com_whispercpp_whisper_WhisperLib_initContextFromInputStream(
        JNIEnv *env, jclass clazz, jobject input_stream) {
    (void)clazz;
    if (!input_stream) { LOGW("initContextFromInputStream: InputStream NULL"); return 0; }

    struct input_stream_context* inp = calloc(1, sizeof(*inp));
    if (!inp) { LOGE("calloc() failed"); return 0; }

    if ((*env)->GetJavaVM(env, &inp->jvm) != 0) {
        LOGE("GetJavaVM() failed");
        free(inp);
        return 0;
    }

    inp->input_stream = (*env)->NewGlobalRef(env, input_stream);
    if (!inp->input_stream) { LOGE("NewGlobalRef(InputStream) failed"); free(inp); return 0; }

    jclass cls = (*env)->GetObjectClass(env, input_stream);
    if (!cls) { LOGE("GetObjectClass() failed"); is_close(inp); return 0; }

    inp->mid_read = (*env)->GetMethodID(env, cls, "read", "([BII)I");
    (*env)->DeleteLocalRef(env, cls);
    if (!inp->mid_read) { LOGE("GetMethodID(read) failed"); is_close(inp); return 0; }

    inp->buf_len = 64 * 1024;
    jbyteArray buf_local = (*env)->NewByteArray(env, inp->buf_len);
    if (!buf_local) { LOGE("NewByteArray() failed"); is_close(inp); return 0; }

    inp->buffer_gl = (*env)->NewGlobalRef(env, buf_local);
    (*env)->DeleteLocalRef(env, buf_local);
    if (!inp->buffer_gl) { LOGE("NewGlobalRef(buffer) failed"); is_close(inp); return 0; }

    inp->attached_by_us = 0;

    struct whisper_model_loader loader = { inp, is_read, is_eof, is_close };
    struct whisper_context_params cparams = whisper_context_default_params();
    struct whisper_context *ctx = whisper_init_with_params(&loader, cparams);

    if (!ctx) {
        LOGE("whisper_init_with_params() failed (InputStream)");
        is_close(inp);
        return 0;
    }

    LOGI("✅ Whisper model successfully loaded from InputStream");
    return (jlong)ctx;
}

/* ============================================================
 * Asset-based loading helpers (pure C, no lambdas)
 * ============================================================ */

/** Reads from AAsset; returns 0 on EOF or error; logs on error (r<0). */
static size_t asset_read(void *ctx, void *output, size_t read_size) {
    const int r = AAsset_read((AAsset *)ctx, output, read_size);
    if (r < 0) {
        LOGE("AAsset_read() returned %d (error)", r);
        return 0;
    }
    return (r > 0) ? (size_t)r : 0;
}

/** EOF check using remaining length. */
static bool asset_eof(void *ctx) {
    return AAsset_getRemainingLength64((AAsset *)ctx) <= 0;
}

/** Closes the asset if non-null. */
static void asset_close(void *ctx) {
    if (ctx) AAsset_close((AAsset *)ctx);
}

/**
 * Initializes whisper context from asset manager stream.
 *
 * @param env JNI environment
 * @param mgrObj AssetManager Java object
 * @param asset_path asset filename within /assets
 * @return whisper_context pointer or NULL
 */
static struct whisper_context* whisper_init_from_asset(
        JNIEnv *env, jobject mgrObj, const char *asset_path) {
    if (!mgrObj || !asset_path) { LOGW("Invalid asset arguments"); return NULL; }

    LOGI("Loading model from asset: %s", asset_path);
    AAssetManager *mgr = AAssetManager_fromJava(env, mgrObj);
    if (!mgr) { LOGE("AAssetManager_fromJava() failed"); return NULL; }

    AAsset *asset = AAssetManager_open(mgr, asset_path, AASSET_MODE_STREAMING);
    if (!asset) { LOGE("AAssetManager_open() failed for: %s", asset_path); return NULL; }

    struct whisper_model_loader loader = { asset, asset_read, asset_eof, asset_close };
    struct whisper_context_params cparams = whisper_context_default_params();
    struct whisper_context *ctx = whisper_init_with_params(&loader, cparams);
    if (!ctx) LOGE("whisper_init_with_params() failed (Asset)");
    return ctx;
}

/**
 * JNI wrapper for initContextFromAsset().
 *
 * @param env JNI environment
 * @param clazz class ref
 * @param mgr AssetManager
 * @param pathStr model asset filename
 * @return whisper_context pointer
 */
JNIEXPORT jlong JNICALL
Java_com_whispercpp_whisper_WhisperLib_initContextFromAsset(
        JNIEnv *env, jclass clazz, jobject mgr, jstring pathStr) {
    (void)clazz;
    if (!pathStr) return 0;

    const char *path = (*env)->GetStringUTFChars(env, pathStr, NULL);
    if (!path) return 0;
    struct whisper_context *ctx = whisper_init_from_asset(env, mgr, path);
    (*env)->ReleaseStringUTFChars(env, pathStr, path);
    return (jlong)ctx;
}

/**
 * Initializes whisper_context from a direct file path on local storage.
 *
 * Fastest method (uses mmap internally via GGML).
 *
 * @param env JNI environment
 * @param clazz class reference
 * @param pathStr Java string path
 * @return whisper_context pointer or 0
 */
JNIEXPORT jlong JNICALL
Java_com_whispercpp_whisper_WhisperLib_initContext(
        JNIEnv *env, jclass clazz, jstring pathStr) {
    (void)clazz;
    if (!pathStr) { LOGW("pathStr is NULL"); return 0; }

    const char *path = (*env)->GetStringUTFChars(env, pathStr, NULL);
    if (!path) { LOGE("GetStringUTFChars() failed"); return 0; }

    struct whisper_context_params cparams = whisper_context_default_params();
    struct whisper_context *ctx = whisper_init_from_file_with_params(path, cparams);
    (*env)->ReleaseStringUTFChars(env, pathStr, path);

    if (!ctx) LOGE("whisper_init_from_file_with_params() failed");
    else LOGI("✅ Whisper model loaded from file: %s", path);
    return (jlong)ctx;
}

/**
 * Frees a whisper_context and releases all native resources.
 *
 * Safe to call multiple times (idempotent).
 *
 * @param env JNI environment
 * @param clazz class ref
 * @param ptr native pointer to whisper_context
 */
JNIEXPORT void JNICALL
Java_com_whispercpp_whisper_WhisperLib_freeContext(
        JNIEnv *env, jclass clazz, jlong ptr) {
    (void)env; (void)clazz;
    if (ptr) {
        whisper_free((struct whisper_context*)ptr);
        LOGI("Whisper context freed successfully");
    }
}

/**
 * Performs full blocking transcription on provided PCM audio buffer.
 *
 * @param env JNI environment
 * @param clazz Java class
 * @param ctxPtr native whisper_context pointer
 * @param langStr language code or "auto"
 * @param nthreads number of CPU threads
 * @param translate translation mode (true/false)
 * @param audio float[] PCM data (-1.0f~1.0f)
 */
JNIEXPORT void JNICALL
Java_com_whispercpp_whisper_WhisperLib_fullTranscribe(
        JNIEnv *env, jclass clazz, jlong ctxPtr, jstring langStr,
        jint nthreads, jboolean translate, jfloatArray audio) {
    (void)clazz;
    struct whisper_context *ctx = (struct whisper_context*)ctxPtr;
    if (!ctx || !audio) { LOGW("fullTranscribe: context or audio NULL"); return; }

    jfloat *pcm = (*env)->GetFloatArrayElements(env, audio, NULL);
    if (!pcm) { LOGE("GetFloatArrayElements() failed"); return; }
    jsize n = (*env)->GetArrayLength(env, audio);

    const char *lang = NULL;
    if (langStr) lang = (*env)->GetStringUTFChars(env, langStr, NULL);

    struct whisper_full_params p = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    p.n_threads = (nthreads > 0) ? nthreads : 1;
    p.translate = (translate == JNI_TRUE);
    p.no_context = true;          // independent runs (no KV reuse)
    p.single_segment = false;
    p.print_realtime = false;
    p.print_progress = false;
    p.print_timestamps = false;
    p.print_special = false;

    if (lang && strcmp(lang, "auto") != 0) {
        p.language = lang;
        p.detect_language = false;
    } else {
        p.detect_language = true;
    }

    LOGI("Starting whisper_full(): samples=%d threads=%d translate=%d", (int)n, p.n_threads, p.translate);
    whisper_reset_timings(ctx);

    if (whisper_full(ctx, p, pcm, (int)n) != 0)
        LOGW("whisper_full() failed");
    else
        whisper_print_timings(ctx);

    if (langStr && lang) (*env)->ReleaseStringUTFChars(env, langStr, lang);
    (*env)->ReleaseFloatArrayElements(env, audio, pcm, JNI_ABORT);
}

/**
 * Returns number of decoded text segments.
 */
JNIEXPORT jint JNICALL
Java_com_whispercpp_whisper_WhisperLib_getTextSegmentCount(
        JNIEnv *env, jclass clazz, jlong ptr) {
    (void)env; (void)clazz;
    return ptr ? whisper_full_n_segments((struct whisper_context*)ptr) : 0;
}

/**
 * Returns decoded text for segment index.
 *
 * Range-checked; returns "" when out of bounds.
 */
JNIEXPORT jstring JNICALL
Java_com_whispercpp_whisper_WhisperLib_getTextSegment(
        JNIEnv *env, jclass clazz, jlong ptr, jint i) {
    (void)clazz;
    if (!ptr) return (*env)->NewStringUTF(env, "");
    int n = whisper_full_n_segments((struct whisper_context*)ptr);
    if (i < 0 || i >= n) {
        LOGW("getTextSegment: index %d out of range [0,%d)", i, n);
        return (*env)->NewStringUTF(env, "");
    }
    const char *s = whisper_full_get_segment_text((struct whisper_context*)ptr, i);
    return (*env)->NewStringUTF(env, s ? s : "");
}

/**
 * Returns start timestamp (t0) of segment i.
 * Note: units follow whisper API (typically 10ms ticks).
 */
JNIEXPORT jlong JNICALL
Java_com_whispercpp_whisper_WhisperLib_getTextSegmentT0(
        JNIEnv *env, jclass clazz, jlong ptr, jint i) {
    (void)env; (void)clazz;
    if (!ptr) return 0;
    int n = whisper_full_n_segments((struct whisper_context*)ptr);
    if (i < 0 || i >= n) {
        LOGW("getTextSegmentT0: index %d out of range [0,%d)", i, n);
        return 0;
    }
    return whisper_full_get_segment_t0((struct whisper_context*)ptr, i);
}

/**
 * Returns end timestamp (t1) of segment i.
 * Note: units follow whisper API (typically 10ms ticks).
 */
JNIEXPORT jlong JNICALL
Java_com_whispercpp_whisper_WhisperLib_getTextSegmentT1(
        JNIEnv *env, jclass clazz, jlong ptr, jint i) {
    (void)env; (void)clazz;
    if (!ptr) return 0;
    int n = whisper_full_n_segments((struct whisper_context*)ptr);
    if (i < 0 || i >= n) {
        LOGW("getTextSegmentT1: index %d out of range [0,%d)", i, n);
        return 0;
    }
    return whisper_full_get_segment_t1((struct whisper_context*)ptr, i);
}

/**
 * Returns GGML/Whisper system build info string.
 */
JNIEXPORT jstring JNICALL
Java_com_whispercpp_whisper_WhisperLib_getSystemInfo(JNIEnv *env, jclass clazz) {
    (void)clazz;
    const char *s = whisper_print_system_info();
    return (*env)->NewStringUTF(env, s ? s : "");
}

/**
 * Optional benchmark: memcpy performance (requires WHISPER_BENCH build).
 */
JNIEXPORT jstring JNICALL
Java_com_whispercpp_whisper_WhisperLib_benchMemcpy(JNIEnv *env, jclass clazz, jint nt) {
    (void)clazz;
#ifdef WHISPER_BENCH
    const char *s = whisper_bench_memcpy_str(nt);
    return (*env)->NewStringUTF(env, s ? s : "");
#else
    return (*env)->NewStringUTF(env, "bench memcpy not enabled");
#endif
}

/**
 * Optional benchmark: matrix multiplication throughput (requires WHISPER_BENCH build).
 */
JNIEXPORT jstring JNICALL
Java_com_whispercpp_whisper_WhisperLib_benchGgmlMulMat(JNIEnv *env, jclass clazz, jint nt) {
    (void)clazz;
#ifdef WHISPER_BENCH
    const char *s = whisper_bench_ggml_mul_mat_str(nt);
    return (*env)->NewStringUTF(env, s ? s : "");
#else
    return (*env)->NewStringUTF(env, "bench ggml_mul_mat not enabled");
#endif
}

/**
 * Called when the native library (libwhisper.so) is loaded.
 *
 * Performs minimal initialization and returns the JNI version supported.
 * This ensures that the runtime matches the compiled JNI headers.
 *
 * @param vm Pointer to the JavaVM instance
 * @param reserved Reserved by JNI spec (unused)
 * @return JNI version (JNI_VERSION_1_6) if successful
 */
JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void* reserved) {
    (void)vm; (void)reserved;
    LOGI("JNI_OnLoad(): Whisper JNI initialized (JNI v1.6)");
    return JNI_VERSION_1_6;
}
