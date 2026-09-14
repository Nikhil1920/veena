package com.byanr.veena.android.engine

/**
 * JNI bridge into librust_backend.so (see app/src/main/cpp/rust_backend_jni.cpp
 * and rust_backend/include/rust_backend.h).
 */
object RustBackend {
    init {
        System.loadLibrary("rust_backend")
        System.loadLibrary("rust_backend_jni")
    }

    external fun transcriberCreate(
        modelPath: String,
        decoderConfigPath: String,
        vocabPath: String,
    ): Long

    external fun transcriberCreateMultilingual(
        modelPath: String,
        decoderConfigPath: String,
        vocabPath: String,
        masksPath: String,
    ): Long

    external fun transcribeFile(
        handlePtr: Long,
        audioPath: String,
        language: String?,
    ): String?

    external fun transcribeAudio(
        handlePtr: Long,
        audioData: FloatArray,
        sampleRate: Int,
        language: String?,
    ): String?

    external fun transcriberDestroy(handlePtr: Long)
}
