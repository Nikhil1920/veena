#include <jni.h>
#include <stddef.h>
#include <stdint.h>
#include <string>

// Rust C-FFI signatures (see rust_backend/include/rust_backend.h)
extern "C" {
typedef struct Transcriber Transcriber;

Transcriber *transcriber_create(const char *model_path,
                                const char *decoder_config_path,
                                const char *vocab_path);

Transcriber *transcriber_create_multilingual(const char *model_path,
                                             const char *decoder_config_path,
                                             const char *vocab_path,
                                             const char *masks_path);

char *transcriber_transcribe_file(Transcriber *handle, const char *audio_path);

char *transcriber_transcribe_file_lang(Transcriber *handle,
                                       const char *audio_path,
                                       const char *language);

char *transcriber_transcribe_audio(Transcriber *handle, const float *audio,
                                   size_t len, uint32_t sample_rate);

char *transcriber_transcribe_audio_lang(Transcriber *handle, const float *audio,
                                        size_t len, uint32_t sample_rate,
                                        const char *language);

typedef bool (*TranscriberProgressFn)(void *user_data, size_t completed,
                                      size_t total);

char *transcriber_transcribe_file_progress(Transcriber *handle,
                                           const char *audio_path,
                                           const char *language,
                                           TranscriberProgressFn progress,
                                           void *user_data);

char *transcriber_transcribe_audio_progress(Transcriber *handle,
                                            const float *audio, size_t len,
                                            uint32_t sample_rate,
                                            const char *language,
                                            TranscriberProgressFn progress,
                                            void *user_data);

void transcriber_destroy(Transcriber *handle);

void transcriber_free_string(char *s);
}

namespace {

jstring toJString(JNIEnv *env, char *result) {
    if (result == nullptr) {
        return nullptr;
    }
    jstring j_result = env->NewStringUTF(result);
    transcriber_free_string(result);
    return j_result;
}

// Forwards engine progress to a Kotlin TranscriptionProgress, whose return
// value decides whether the engine continues. The engine calls back
// synchronously on the JNI thread, so the env stays valid.
struct ProgressListener {
    JNIEnv *env;
    jobject listener;
    jmethodID onProgress;
};

bool forwardProgress(void *user_data, size_t completed, size_t total) {
    auto *target = static_cast<ProgressListener *>(user_data);
    jboolean keepGoing = target->env->CallBooleanMethod(
        target->listener, target->onProgress, static_cast<jint>(completed),
        static_cast<jint>(total));
    if (target->env->ExceptionCheck()) {
        // A throwing UI callback is a bug, not a cancel request.
        target->env->ExceptionDescribe();
        target->env->ExceptionClear();
        return true;
    }
    return keepGoing == JNI_TRUE;
}

ProgressListener progressListener(JNIEnv *env, jobject listener) {
    if (listener == nullptr) return {env, nullptr, nullptr};
    jclass type = env->GetObjectClass(listener);
    jmethodID method = env->GetMethodID(type, "onProgress", "(II)Z");
    env->DeleteLocalRef(type);
    if (method == nullptr) env->ExceptionClear();
    return {env, listener, method};
}

} // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_com_byanr_veena_android_engine_RustBackend_transcriberCreate(
    JNIEnv *env, jobject, jstring model_path, jstring config_path,
    jstring vocab_path) {
    const char *c_model = env->GetStringUTFChars(model_path, nullptr);
    const char *c_config = env->GetStringUTFChars(config_path, nullptr);
    const char *c_vocab = env->GetStringUTFChars(vocab_path, nullptr);

    auto *handle =
        transcriber_create(c_model, c_config, c_vocab);

    env->ReleaseStringUTFChars(model_path, c_model);
    env->ReleaseStringUTFChars(config_path, c_config);
    env->ReleaseStringUTFChars(vocab_path, c_vocab);
    return reinterpret_cast<jlong>(handle);
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_byanr_veena_android_engine_RustBackend_transcriberCreateMultilingual(
    JNIEnv *env, jobject, jstring model_path, jstring config_path,
    jstring vocab_path, jstring masks_path) {
    const char *c_model = env->GetStringUTFChars(model_path, nullptr);
    const char *c_config = env->GetStringUTFChars(config_path, nullptr);
    const char *c_vocab = env->GetStringUTFChars(vocab_path, nullptr);
    const char *c_masks = env->GetStringUTFChars(masks_path, nullptr);

    auto *handle =
        transcriber_create_multilingual(c_model, c_config, c_vocab, c_masks);

    env->ReleaseStringUTFChars(model_path, c_model);
    env->ReleaseStringUTFChars(config_path, c_config);
    env->ReleaseStringUTFChars(vocab_path, c_vocab);
    env->ReleaseStringUTFChars(masks_path, c_masks);
    return reinterpret_cast<jlong>(handle);
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_byanr_veena_android_engine_RustBackend_transcribeFile(
    JNIEnv *env, jobject, jlong handle_ptr, jstring audio_path,
    jstring language, jobject progress) {
    if (handle_ptr == 0) return nullptr;
    auto *handle = reinterpret_cast<Transcriber *>(handle_ptr);

    const char *c_path = env->GetStringUTFChars(audio_path, nullptr);
    const char *c_lang =
        language != nullptr ? env->GetStringUTFChars(language, nullptr) : nullptr;

    ProgressListener listener = progressListener(env, progress);
    char *result = transcriber_transcribe_file_progress(
        handle, c_path, c_lang,
        listener.onProgress != nullptr ? forwardProgress : nullptr, &listener);

    env->ReleaseStringUTFChars(audio_path, c_path);
    if (c_lang != nullptr) {
        env->ReleaseStringUTFChars(language, c_lang);
    }
    return toJString(env, result);
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_byanr_veena_android_engine_RustBackend_transcribeAudio(
    JNIEnv *env, jobject, jlong handle_ptr, jfloatArray audio_data,
    jint sample_rate, jstring language, jobject progress) {
    if (handle_ptr == 0 || audio_data == nullptr) return nullptr;
    auto *handle = reinterpret_cast<Transcriber *>(handle_ptr);

    jsize len = env->GetArrayLength(audio_data);
    jfloat *c_audio = env->GetFloatArrayElements(audio_data, nullptr);
    const char *c_lang =
        language != nullptr ? env->GetStringUTFChars(language, nullptr) : nullptr;

    ProgressListener listener = progressListener(env, progress);
    char *result = transcriber_transcribe_audio_progress(
        handle, c_audio, static_cast<size_t>(len),
        static_cast<uint32_t>(sample_rate), c_lang,
        listener.onProgress != nullptr ? forwardProgress : nullptr, &listener);

    env->ReleaseFloatArrayElements(audio_data, c_audio, JNI_ABORT);
    if (c_lang != nullptr) {
        env->ReleaseStringUTFChars(language, c_lang);
    }
    return toJString(env, result);
}

extern "C" JNIEXPORT void JNICALL
Java_com_byanr_veena_android_engine_RustBackend_transcriberDestroy(JNIEnv *,
                                                                   jobject,
                                                                   jlong handle_ptr) {
    if (handle_ptr != 0) {
        transcriber_destroy(reinterpret_cast<Transcriber *>(handle_ptr));
    }
}
