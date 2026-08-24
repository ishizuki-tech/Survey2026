// file: WhisperLib.c
// ============================================================
// whisper.cpp JNI Bridge — Reviewed production-safe version
// ------------------------------------------------------------
// - Model loading from File / Asset / InputStream
// - Read-fully semantics for custom whisper_model_loader callbacks
// - Callback-local JVM thread attach/detach
// - Defensive JNI exception and reference handling
// - No long-lived pinning of Java float[] during inference
// - Empty-audio guard before whisper_full()
// - UTF-8 -> UTF-16 conversion for transcription text
// - Segment index bounds checks
// - Current whisper.cpp benchmark API support
//
// IMPORTANT:
// A whisper_context must not be used concurrently from multiple threads.
// The Kotlin/Java owner must serialize inference/result access and must set
// its native handle to 0 immediately after freeContext().
// ============================================================

#include <jni.h>
#include <android/asset_manager.h>
#include <android/asset_manager_jni.h>
#include <android/log.h>

#include <stdbool.h>
#include <stdint.h>
#include <limits.h>
#include <stdlib.h>
#include <string.h>

#include "whisper.h"

#define TAG "JNI-Whisper"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN,  TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

#define INPUT_STREAM_BUFFER_SIZE (256 * 1024)

// ============================================================
// JNI helpers
// ============================================================

/**
 * Obtains JNIEnv for the current thread.
 *
 * If the current thread is detached, this function attaches it and sets
 * attached_here to 1. The caller must detach in the SAME callback/frame.
 */
static JNIEnv *get_env_from_jvm(JavaVM *jvm, int *attached_here) {
    if (attached_here) {
        *attached_here = 0;
    }

    if (!jvm) {
        LOGE("get_env_from_jvm: JavaVM is NULL");
        return NULL;
    }

    JNIEnv *env = NULL;
    const jint status = (*jvm)->GetEnv(jvm, (void **) &env, JNI_VERSION_1_6);

    if (status == JNI_OK) {
        return env;
    }

    if (status != JNI_EDETACHED) {
        LOGE("GetEnv() failed with status=%d", (int) status);
        return NULL;
    }

    if ((*jvm)->AttachCurrentThread(jvm, (JNIEnv **) (void **) &env, NULL) != JNI_OK) {
        LOGE("AttachCurrentThread() failed");
        return NULL;
    }

    if (attached_here) {
        *attached_here = 1;
    }

    return env;
}

/** Detaches the current thread only when this callback attached it. */
static void detach_if_needed(JavaVM *jvm, int attached_here) {
    if (jvm && attached_here) {
        const jint status = (*jvm)->DetachCurrentThread(jvm);
        if (status != JNI_OK) {
            LOGW("DetachCurrentThread() failed with status=%d", (int) status);
        }
    }
}

/**
 * Logs and clears a pending Java exception.
 *
 * Loader callbacks cannot safely propagate a Java exception through
 * whisper.cpp, so InputStream exceptions are converted into loader errors.
 */
static bool log_and_clear_jni_exception(JNIEnv *env, const char *where) {
    if (!env || !(*env)->ExceptionCheck(env)) {
        return false;
    }

    LOGE("JNI exception in %s", where ? where : "unknown location");
    (*env)->ExceptionDescribe(env);
    (*env)->ExceptionClear(env);
    return true;
}

/** Returns an empty Java String. */
static jstring new_empty_string(JNIEnv *env) {
    static const jchar empty[1] = { 0 };
    return (*env)->NewString(env, empty, 0);
}

/**
 * Converts standard UTF-8 into a Java UTF-16 String.
 *
 * NewStringUTF() expects Modified UTF-8, while whisper.cpp returns standard
 * UTF-8. This helper handles 1-4 byte UTF-8 sequences and replaces malformed
 * input with U+FFFD.
 */
static jstring new_string_from_utf8(JNIEnv *env, const char *utf8) {
    if (!env) {
        return NULL;
    }

    if (!utf8 || utf8[0] == '\0') {
        return new_empty_string(env);
    }

    const size_t input_len = strlen(utf8);
    if (input_len > (size_t) INT32_MAX) {
        LOGE("UTF-8 string is too large for a Java String");
        return new_empty_string(env);
    }

    // UTF-16 code-unit count never exceeds UTF-8 byte count for valid UTF-8.
    jchar *utf16 = (jchar *) malloc(input_len * sizeof(jchar));
    if (!utf16) {
        LOGE("malloc() failed while converting UTF-8 string");
        return new_empty_string(env);
    }

    const unsigned char *s = (const unsigned char *) utf8;
    size_t i = 0;
    size_t out = 0;

    while (i < input_len) {
        uint32_t cp = 0xFFFDu;
        size_t consumed = 1;
        const unsigned char c0 = s[i];

        if (c0 < 0x80u) {
            cp = c0;
            consumed = 1;
        } else if (c0 >= 0xC2u && c0 <= 0xDFu && i + 1 < input_len) {
            const unsigned char c1 = s[i + 1];
            if ((c1 & 0xC0u) == 0x80u) {
                cp = ((uint32_t) (c0 & 0x1Fu) << 6) |
                     ((uint32_t) (c1 & 0x3Fu));
                consumed = 2;
            }
        } else if (c0 >= 0xE0u && c0 <= 0xEFu && i + 2 < input_len) {
            const unsigned char c1 = s[i + 1];
            const unsigned char c2 = s[i + 2];

            const bool c1_ok =
                    ((c1 & 0xC0u) == 0x80u) &&
                    !(c0 == 0xE0u && c1 < 0xA0u) &&
                    !(c0 == 0xEDu && c1 >= 0xA0u);

            if (c1_ok && (c2 & 0xC0u) == 0x80u) {
                cp = ((uint32_t) (c0 & 0x0Fu) << 12) |
                     ((uint32_t) (c1 & 0x3Fu) << 6) |
                     ((uint32_t) (c2 & 0x3Fu));
                consumed = 3;
            }
        } else if (c0 >= 0xF0u && c0 <= 0xF4u && i + 3 < input_len) {
            const unsigned char c1 = s[i + 1];
            const unsigned char c2 = s[i + 2];
            const unsigned char c3 = s[i + 3];

            const bool c1_ok =
                    ((c1 & 0xC0u) == 0x80u) &&
                    !(c0 == 0xF0u && c1 < 0x90u) &&
                    !(c0 == 0xF4u && c1 > 0x8Fu);

            if (c1_ok &&
                (c2 & 0xC0u) == 0x80u &&
                (c3 & 0xC0u) == 0x80u) {
                cp = ((uint32_t) (c0 & 0x07u) << 18) |
                     ((uint32_t) (c1 & 0x3Fu) << 12) |
                     ((uint32_t) (c2 & 0x3Fu) << 6) |
                     ((uint32_t) (c3 & 0x3Fu));
                consumed = 4;
            }
        }

        i += consumed;

        if (cp <= 0xFFFFu) {
            utf16[out++] = (jchar) cp;
        } else {
            cp -= 0x10000u;
            utf16[out++] = (jchar) (0xD800u + (cp >> 10));
            utf16[out++] = (jchar) (0xDC00u + (cp & 0x3FFu));
        }
    }

    jstring result = (*env)->NewString(env, utf16, (jsize) out);
    free(utf16);

    if (!result) {
        LOGE("NewString() failed");
    }

    return result;
}

// ============================================================
// Java InputStream model loader
// ============================================================

struct input_stream_context {
    JavaVM   *jvm;
    jobject   input_stream;  // GlobalRef
    jmethodID mid_read;
    jbyteArray buffer_gl;    // GlobalRef
    jint      buf_len;
    int       eof;
    int       error;
};

/**
 * Reads exactly read_size bytes when possible.
 *
 * whisper.cpp frequently assumes that loader.read() fills the full requested
 * buffer, so this callback loops over the reusable Java byte[] until the
 * requested amount has been copied or a real EOF/error occurs.
 */
static size_t is_read(void *ctx, void *output, size_t read_size) {
    struct input_stream_context *is = (struct input_stream_context *) ctx;

    if (!is || !output || read_size == 0) {
        return 0;
    }

    // Always initialize the destination because upstream may ignore the
    // callback's returned byte count.
    memset(output, 0, read_size);

    if (!is->jvm || !is->input_stream || !is->buffer_gl || !is->mid_read) {
        is->error = 1;
        return 0;
    }

    if (is->eof || is->error) {
        return 0;
    }

    int attached_here = 0;
    JNIEnv *env = get_env_from_jvm(is->jvm, &attached_here);
    if (!env) {
        is->error = 1;
        return 0;
    }

    size_t total = 0;
    unsigned char *dst = (unsigned char *) output;

    while (total < read_size) {
        const size_t remaining = read_size - total;
        const jint chunk = (jint) (remaining > (size_t) is->buf_len
                                   ? is->buf_len
                                   : remaining);

        const jint n = (*env)->CallIntMethod(
                env,
                is->input_stream,
                is->mid_read,
                is->buffer_gl,
                0,
                chunk);

        if (log_and_clear_jni_exception(env, "InputStream.read")) {
            is->error = 1;
            break;
        }

        if (n < 0) {
            if (total == 0) {
                // EOF before reading anything for this request is a normal EOF.
                is->eof = 1;
            } else {
                // EOF in the middle of a requested block means a truncated model.
                LOGE("Unexpected EOF after %zu/%zu bytes", total, read_size);
                is->error = 1;
            }
            break;
        }

        if (n == 0) {
            // InputStream.read(byte[], off, len) should not return 0 for len > 0.
            LOGE("InputStream.read() returned 0 for a non-zero request");
            is->error = 1;
            break;
        }

        if (n > chunk) {
            LOGE("InputStream.read() returned invalid byte count: %d > %d",
                 (int) n, (int) chunk);
            is->error = 1;
            break;
        }

        (*env)->GetByteArrayRegion(
                env,
                is->buffer_gl,
                0,
                n,
                (jbyte *) (dst + total));

        if (log_and_clear_jni_exception(env, "GetByteArrayRegion")) {
            is->error = 1;
            break;
        }

        total += (size_t) n;
    }

    detach_if_needed(is->jvm, attached_here);
    return total;
}

/** Returns true only for a clean end-of-stream, not for loader errors. */
static bool is_eof(void *ctx) {
    struct input_stream_context *is = (struct input_stream_context *) ctx;
    if (!is) {
        return true;
    }
    return is->error ? false : (is->eof != 0);
}

/** Releases InputStream loader resources. */
static void is_close(void *ctx) {
    struct input_stream_context *is = (struct input_stream_context *) ctx;
    if (!is) {
        return;
    }

    int attached_here = 0;
    JNIEnv *env = get_env_from_jvm(is->jvm, &attached_here);

    if (env) {
        if (is->input_stream) {
            (*env)->DeleteGlobalRef(env, is->input_stream);
            is->input_stream = NULL;
        }

        if (is->buffer_gl) {
            (*env)->DeleteGlobalRef(env, is->buffer_gl);
            is->buffer_gl = NULL;
        }
    } else {
        LOGE("is_close: could not obtain JNIEnv; GlobalRefs may leak");
    }

    detach_if_needed(is->jvm, attached_here);
    free(is);
}

/** Loads a whisper model from a Java InputStream. */
JNIEXPORT jlong JNICALL
Java_com_whispercpp_whisper_WhisperLib_initContextFromInputStream(
        JNIEnv *env,
        jclass clazz,
        jobject input_stream) {
    (void) clazz;

    if (!env || !input_stream) {
        LOGW("initContextFromInputStream: InputStream is NULL");
        return 0;
    }

    struct input_stream_context *inp =
            (struct input_stream_context *) calloc(1, sizeof(*inp));
    if (!inp) {
        LOGE("calloc() failed for input_stream_context");
        return 0;
    }

    if ((*env)->GetJavaVM(env, &inp->jvm) != JNI_OK || !inp->jvm) {
        LOGE("GetJavaVM() failed");
        free(inp);
        return 0;
    }

    inp->input_stream = (*env)->NewGlobalRef(env, input_stream);
    if (!inp->input_stream) {
        LOGE("NewGlobalRef(InputStream) failed");
        log_and_clear_jni_exception(env, "NewGlobalRef(InputStream)");
        free(inp);
        return 0;
    }

    jclass cls = (*env)->GetObjectClass(env, input_stream);
    if (!cls) {
        LOGE("GetObjectClass(InputStream) failed");
        log_and_clear_jni_exception(env, "GetObjectClass(InputStream)");
        is_close(inp);
        return 0;
    }

    inp->mid_read = (*env)->GetMethodID(env, cls, "read", "([BII)I");
    (*env)->DeleteLocalRef(env, cls);

    if (!inp->mid_read) {
        LOGE("GetMethodID(InputStream.read) failed");
        log_and_clear_jni_exception(env, "GetMethodID(InputStream.read)");
        is_close(inp);
        return 0;
    }

    inp->buf_len = INPUT_STREAM_BUFFER_SIZE;

    jbyteArray buffer_local = (*env)->NewByteArray(env, inp->buf_len);
    if (!buffer_local) {
        LOGE("NewByteArray(%d) failed", (int) inp->buf_len);
        log_and_clear_jni_exception(env, "NewByteArray");
        is_close(inp);
        return 0;
    }

    inp->buffer_gl = (jbyteArray) (*env)->NewGlobalRef(env, buffer_local);
    (*env)->DeleteLocalRef(env, buffer_local);

    if (!inp->buffer_gl) {
        LOGE("NewGlobalRef(byte[]) failed");
        log_and_clear_jni_exception(env, "NewGlobalRef(byte[])");
        is_close(inp);
        return 0;
    }

    inp->eof = 0;
    inp->error = 0;

    struct whisper_model_loader loader = {
            inp,
            is_read,
            is_eof,
            is_close
    };

    struct whisper_context_params cparams = whisper_context_default_params();
    struct whisper_context *ctx = whisper_init_with_params(&loader, cparams);

    // IMPORTANT:
    // Current whisper.cpp owns the loader.close() call while init is running.
    // It calls close() on both success and model-load failure. Do not call
    // is_close(inp) here after whisper_init_with_params().

    if (!ctx) {
        LOGE("whisper_init_with_params() failed for InputStream");
        return 0;
    }

    LOGI("Whisper model loaded from InputStream");
    return (jlong) (intptr_t) ctx;
}

// ============================================================
// Android Asset model loader
// ============================================================

struct asset_context {
    AAsset *asset;
    int eof;
    int error;
};

/** Reads the full requested Asset block whenever possible. */
static size_t asset_read(void *ctx, void *output, size_t read_size) {
    struct asset_context *ac = (struct asset_context *) ctx;

    if (!ac || !output || read_size == 0) {
        return 0;
    }

    memset(output, 0, read_size);

    if (!ac->asset) {
        ac->error = 1;
        return 0;
    }

    if (ac->eof || ac->error) {
        return 0;
    }

    unsigned char *dst = (unsigned char *) output;
    size_t total = 0;

    while (total < read_size) {
        const size_t remaining = read_size - total;
        const size_t chunk = remaining > (size_t) INT_MAX
                             ? (size_t) INT_MAX
                             : remaining;
        const int r = AAsset_read(ac->asset, dst + total, chunk);

        if (r < 0) {
            LOGE("AAsset_read() failed with %d", r);
            ac->error = 1;
            break;
        }

        if (r == 0) {
            if (total == 0) {
                ac->eof = 1;
            } else {
                LOGE("Unexpected Asset EOF after %zu/%zu bytes", total, read_size);
                ac->error = 1;
            }
            break;
        }

        total += (size_t) r;
    }

    return total;
}

/** Returns true only for a clean Asset EOF. */
static bool asset_eof(void *ctx) {
    struct asset_context *ac = (struct asset_context *) ctx;
    if (!ac || !ac->asset) {
        return true;
    }

    if (ac->error) {
        return false;
    }

    if (ac->eof) {
        return true;
    }

    return AAsset_getRemainingLength64(ac->asset) <= 0;
}

/** Closes and frees the Asset loader context. */
static void asset_close(void *ctx) {
    struct asset_context *ac = (struct asset_context *) ctx;
    if (!ac) {
        return;
    }

    if (ac->asset) {
        AAsset_close(ac->asset);
        ac->asset = NULL;
    }

    free(ac);
}

/** Loads a whisper model from Android assets. */
static struct whisper_context *whisper_init_from_asset(
        JNIEnv *env,
        jobject mgr_obj,
        const char *asset_path) {
    if (!env || !mgr_obj || !asset_path || asset_path[0] == '\0') {
        LOGW("whisper_init_from_asset: invalid arguments");
        return NULL;
    }

    AAssetManager *mgr = AAssetManager_fromJava(env, mgr_obj);
    if (!mgr) {
        LOGE("AAssetManager_fromJava() failed");
        return NULL;
    }

    AAsset *asset = AAssetManager_open(mgr, asset_path, AASSET_MODE_STREAMING);
    if (!asset) {
        LOGE("AAssetManager_open() failed for '%s'", asset_path);
        return NULL;
    }

    struct asset_context *ac =
            (struct asset_context *) calloc(1, sizeof(*ac));
    if (!ac) {
        LOGE("calloc() failed for asset_context");
        AAsset_close(asset);
        return NULL;
    }

    ac->asset = asset;

    struct whisper_model_loader loader = {
            ac,
            asset_read,
            asset_eof,
            asset_close
    };

    struct whisper_context_params cparams = whisper_context_default_params();

    LOGI("Loading Whisper model from Asset: %s", asset_path);
    struct whisper_context *ctx = whisper_init_with_params(&loader, cparams);

    // whisper.cpp calls asset_close() through loader.close() on success/failure.
    if (!ctx) {
        LOGE("whisper_init_with_params() failed for Asset '%s'", asset_path);
    }

    return ctx;
}

/** JNI wrapper for Asset model loading. */
JNIEXPORT jlong JNICALL
Java_com_whispercpp_whisper_WhisperLib_initContextFromAsset(
        JNIEnv *env,
        jclass clazz,
        jobject mgr,
        jstring path_str) {
    (void) clazz;

    if (!env || !mgr || !path_str) {
        LOGW("initContextFromAsset: invalid arguments");
        return 0;
    }

    const char *path = (*env)->GetStringUTFChars(env, path_str, NULL);
    if (!path) {
        LOGE("GetStringUTFChars(asset path) failed");
        return 0;
    }

    struct whisper_context *ctx = whisper_init_from_asset(env, mgr, path);

    (*env)->ReleaseStringUTFChars(env, path_str, path);
    return (jlong) (intptr_t) ctx;
}

// ============================================================
// Direct file model loader
// ============================================================

/** Loads a whisper model from a direct filesystem path. */
JNIEXPORT jlong JNICALL
Java_com_whispercpp_whisper_WhisperLib_initContext(
        JNIEnv *env,
        jclass clazz,
        jstring path_str) {
    (void) clazz;

    if (!env || !path_str) {
        LOGW("initContext: path is NULL");
        return 0;
    }

    const char *path = (*env)->GetStringUTFChars(env, path_str, NULL);
    if (!path) {
        LOGE("GetStringUTFChars(model path) failed");
        return 0;
    }

    struct whisper_context_params cparams = whisper_context_default_params();
    struct whisper_context *ctx =
            whisper_init_from_file_with_params(path, cparams);

    if (ctx) {
        LOGI("Whisper model loaded from file: %s", path);
    } else {
        LOGE("whisper_init_from_file_with_params() failed for '%s'", path);
    }

    (*env)->ReleaseStringUTFChars(env, path_str, path);
    return (jlong) (intptr_t) ctx;
}

// ============================================================
// Context lifecycle
// ============================================================

/**
 * Frees a whisper_context.
 *
 * ptr == 0 is a no-op. A repeated call with the same non-zero pointer is NOT
 * safe; the Java/Kotlin owner must clear its stored handle immediately after
 * the first call.
 */
JNIEXPORT void JNICALL
Java_com_whispercpp_whisper_WhisperLib_freeContext(
        JNIEnv *env,
        jclass clazz,
        jlong ptr) {
    (void) env;
    (void) clazz;

    if (!ptr) {
        return;
    }

    struct whisper_context *ctx =
            (struct whisper_context *) (intptr_t) ptr;
    whisper_free(ctx);
    LOGI("Whisper context freed");
}

// ============================================================
// Transcription
// ============================================================

/**
 * Performs blocking transcription of mono 16 kHz float PCM samples.
 *
 * The Java float[] is copied into native memory before inference so ART does
 * not keep a Java array pinned for the potentially long whisper_full() call.
 */
JNIEXPORT void JNICALL
Java_com_whispercpp_whisper_WhisperLib_fullTranscribe(
        JNIEnv *env,
        jclass clazz,
        jlong ctx_ptr,
        jstring lang_str,
        jint nthreads,
        jboolean translate,
        jfloatArray audio) {
    (void) clazz;

    struct whisper_context *ctx =
            (struct whisper_context *) (intptr_t) ctx_ptr;

    if (!env || !ctx || !audio) {
        LOGW("fullTranscribe: context or audio is NULL");
        return;
    }

    const jsize n = (*env)->GetArrayLength(env, audio);
    if (n <= 0) {
        // Guard zero-length input before whisper_full().
        LOGW("fullTranscribe: empty audio buffer; skipping inference");
        return;
    }

    if ((size_t) n > SIZE_MAX / sizeof(float)) {
        LOGE("fullTranscribe: audio buffer size overflow");
        return;
    }

    const size_t pcm_bytes = (size_t) n * sizeof(float);
    float *pcm = (float *) malloc(pcm_bytes);
    if (!pcm) {
        LOGE("fullTranscribe: malloc(%zu) failed", pcm_bytes);
        return;
    }

    (*env)->GetFloatArrayRegion(env, audio, 0, n, pcm);
    if ((*env)->ExceptionCheck(env)) {
        LOGE("GetFloatArrayRegion() failed");
        free(pcm);
        return;
    }

    const char *lang = NULL;
    if (lang_str) {
        lang = (*env)->GetStringUTFChars(env, lang_str, NULL);
        if (!lang) {
            LOGE("GetStringUTFChars(language) failed");
            free(pcm);
            return;
        }
    }

    struct whisper_full_params p =
            whisper_full_default_params(WHISPER_SAMPLING_GREEDY);

    p.n_threads = nthreads > 0 ? nthreads : 1;
    p.translate = (translate == JNI_TRUE);
    p.no_context = true;
    p.single_segment = false;
    p.print_realtime = false;
    p.print_progress = false;
    p.print_timestamps = false;
    p.print_special = false;

    if (lang && lang[0] != '\0' && strcmp(lang, "auto") != 0) {
        if (whisper_lang_id(lang) < 0) {
            LOGE("fullTranscribe: unsupported language '%s'", lang);
            (*env)->ReleaseStringUTFChars(env, lang_str, lang);
            free(pcm);
            return;
        }

        p.language = lang;
        p.detect_language = false;
    } else {
        p.detect_language = true;
    }

    // English-only models cannot perform multilingual language detection or
    // translation. Match upstream example behavior by forcing English.
    if (!whisper_is_multilingual(ctx)) {
        p.language = "en";
        p.detect_language = false;
        p.translate = false;
    }

    LOGI("Starting whisper_full(): samples=%d threads=%d translate=%d lang=%s",
         (int) n,
         p.n_threads,
         p.translate ? 1 : 0,
         p.detect_language ? "auto" : (p.language ? p.language : "en"));

    whisper_reset_timings(ctx);

    const int rc = whisper_full(ctx, p, pcm, (int) n);
    if (rc != 0) {
        LOGW("whisper_full() failed with code=%d", rc);
    }

    if (lang) {
        (*env)->ReleaseStringUTFChars(env, lang_str, lang);
    }

    free(pcm);
}

// ============================================================
// Result accessors
// ============================================================

/** Returns the number of decoded text segments. */
JNIEXPORT jint JNICALL
Java_com_whispercpp_whisper_WhisperLib_getTextSegmentCount(
        JNIEnv *env,
        jclass clazz,
        jlong ptr) {
    (void) env;
    (void) clazz;

    if (!ptr) {
        return 0;
    }

    struct whisper_context *ctx =
            (struct whisper_context *) (intptr_t) ptr;
    return (jint) whisper_full_n_segments(ctx);
}

/** Returns UTF-8 decoded segment text, or an empty string if out of range. */
JNIEXPORT jstring JNICALL
Java_com_whispercpp_whisper_WhisperLib_getTextSegment(
        JNIEnv *env,
        jclass clazz,
        jlong ptr,
        jint i) {
    (void) clazz;

    if (!env || !ptr) {
        return env ? new_empty_string(env) : NULL;
    }

    struct whisper_context *ctx =
            (struct whisper_context *) (intptr_t) ptr;

    const int n = whisper_full_n_segments(ctx);
    if (i < 0 || i >= n) {
        LOGW("getTextSegment: index %d out of range [0,%d)", (int) i, n);
        return new_empty_string(env);
    }

    const char *text = whisper_full_get_segment_text(ctx, (int) i);
    return new_string_from_utf8(env, text);
}

/** Returns segment start time in whisper.cpp centiseconds (10 ms units). */
JNIEXPORT jlong JNICALL
Java_com_whispercpp_whisper_WhisperLib_getTextSegmentT0(
        JNIEnv *env,
        jclass clazz,
        jlong ptr,
        jint i) {
    (void) env;
    (void) clazz;

    if (!ptr) {
        return 0;
    }

    struct whisper_context *ctx =
            (struct whisper_context *) (intptr_t) ptr;

    const int n = whisper_full_n_segments(ctx);
    if (i < 0 || i >= n) {
        LOGW("getTextSegmentT0: index %d out of range [0,%d)", (int) i, n);
        return 0;
    }

    return (jlong) whisper_full_get_segment_t0(ctx, (int) i);
}

/** Returns segment end time in whisper.cpp centiseconds (10 ms units). */
JNIEXPORT jlong JNICALL
Java_com_whispercpp_whisper_WhisperLib_getTextSegmentT1(
        JNIEnv *env,
        jclass clazz,
        jlong ptr,
        jint i) {
    (void) env;
    (void) clazz;

    if (!ptr) {
        return 0;
    }

    struct whisper_context *ctx =
            (struct whisper_context *) (intptr_t) ptr;

    const int n = whisper_full_n_segments(ctx);
    if (i < 0 || i >= n) {
        LOGW("getTextSegmentT1: index %d out of range [0,%d)", (int) i, n);
        return 0;
    }

    return (jlong) whisper_full_get_segment_t1(ctx, (int) i);
}

// ============================================================
// Diagnostics / benchmarks
// ============================================================

/** Returns GGML/Whisper build and backend information. */
JNIEXPORT jstring JNICALL
Java_com_whispercpp_whisper_WhisperLib_getSystemInfo(
        JNIEnv *env,
        jclass clazz) {
    (void) clazz;

    const char *info = whisper_print_system_info();
    return new_string_from_utf8(env, info);
}

/** Returns whisper.cpp memcpy benchmark results. */
JNIEXPORT jstring JNICALL
Java_com_whispercpp_whisper_WhisperLib_benchMemcpy(
        JNIEnv *env,
        jclass clazz,
        jint nt) {
    (void) clazz;

    const int threads = nt > 0 ? (int) nt : 1;
    const char *result = whisper_bench_memcpy_str(threads);
    return new_string_from_utf8(env, result);
}

/** Returns whisper.cpp matrix multiplication benchmark results. */
JNIEXPORT jstring JNICALL
Java_com_whispercpp_whisper_WhisperLib_benchGgmlMulMat(
        JNIEnv *env,
        jclass clazz,
        jint nt) {
    (void) clazz;

    const int threads = nt > 0 ? (int) nt : 1;
    const char *result = whisper_bench_ggml_mul_mat_str(threads);
    return new_string_from_utf8(env, result);
}

// ============================================================
// JNI lifecycle
// ============================================================

/** Called when the native library is loaded. */
JNIEXPORT jint JNICALL
JNI_OnLoad(JavaVM *vm, void *reserved) {
    (void) vm;
    (void) reserved;

    LOGI("JNI_OnLoad(): Whisper JNI initialized (JNI v1.6)");
    return JNI_VERSION_1_6;
}
