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
        progress: TranscriptionProgress?,
    ): String?

    external fun transcribeAudio(
        handlePtr: Long,
        audioData: FloatArray,
        sampleRate: Int,
        language: String?,
        progress: TranscriptionProgress?,
    ): String?

    external fun transcriberDestroy(handlePtr: Long)

    // Live transcription of a recording in progress: see [LiveTranscriber].
    // A session takes one call at a time and is destroyed before its
    // transcriber.

    /** Returns 0 when the model does not support [language]. */
    external fun liveCreate(handlePtr: Long, language: String?): Long

    /** Adds 16 kHz mono samples to the end of the recording. */
    external fun livePush(sessionPtr: Long, audioData: FloatArray)

    external fun livePreview(sessionPtr: Long): String?

    external fun liveFinish(sessionPtr: Long): String?

    external fun liveDestroy(sessionPtr: Long)
}

/**
 * Long audio is transcribed in windows of up to 25 s. Called on the
 * transcribing thread with (completed, total) windows: once with none
 * completed, then after each window. Returning false cancels: the engine
 * stops before the next window and the native call returns null.
 */
fun interface TranscriptionProgress {
    fun onProgress(completed: Int, total: Int): Boolean
}
