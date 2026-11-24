package com.byanr.veena.android.engine

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The engine's live session for one recording (see `live.rs` in the engine
 * crate). Takes one call at a time; [EngineManager.openLive] creates it.
 */
class LiveTranscriber internal constructor(
    private val session: Long,
    private val release: suspend () -> Unit,
) {
    private var closed = false

    /**
     * Adds [audio] (16 kHz mono) to the recording and returns the recording
     * so far as text. Its last words can still change.
     */
    suspend fun preview(audio: FloatArray): String = call(audio, RustBackend::livePreview)

    /**
     * Adds [audio] and returns the transcript: the text
     * [EngineManager.transcribe] gives for the whole recording, with all but
     * its last 25 s window already done.
     */
    suspend fun finish(audio: FloatArray): String = call(audio, RustBackend::liveFinish)

    private suspend fun call(audio: FloatArray, text: (Long) -> String?): String =
        withContext(Dispatchers.Default) {
            check(!closed) { "Live transcription already closed" }
            if (audio.isNotEmpty()) RustBackend.livePush(session, audio)
            text(session) ?: throw IllegalStateException("Transcription failed (see logcat)")
        }

    suspend fun close() {
        if (closed) return
        closed = true
        RustBackend.liveDestroy(session)
        release()
    }
}

/**
 * Text for a dictation while it is being recorded. From creation it keeps
 * handing newly recorded audio to the engine and reports each preview
 * through [onText]; [finish] ends it with the transcript.
 *
 * Previews are a convenience on top of the dictation, never a way for it to
 * fail: if the live session cannot start or breaks, [onText] goes quiet and
 * [finish] returns null, and the caller transcribes the recording as it
 * would without live text.
 *
 * @param audioFrom the samples recorded so far from index `from` on, as
 *   16 kHz mono floats.
 */
class LiveDictation(
    scope: CoroutineScope,
    engine: EngineManager,
    languageCode: String,
    private val audioFrom: (from: Int) -> FloatArray,
    private val onText: (String) -> Unit,
) {
    /** Completed by [finish] with the whole recording. */
    private val recording = CompletableDeferred<FloatArray>()
    private val transcript = CompletableDeferred<String?>()

    private val job = scope.launch {
        var session: LiveTranscriber? = null
        try {
            session = engine.openLive(languageCode)
            var previewed = 0
            var pauseMs = POLL_MS
            var all: FloatArray? = null
            while (true) {
                all = withTimeoutOrNull(pauseMs) { recording.await() }
                if (all != null) break
                val fresh = audioFrom(previewed)
                if (fresh.size < MIN_FRESH_SAMPLES) {
                    pauseMs = POLL_MS
                    continue
                }
                previewed += fresh.size
                val startedAt = System.nanoTime()
                onText(session.preview(fresh))
                // A slow phone spends at most two thirds of its time on
                // previews, which also bounds how long a preview in flight
                // can hold up the transcript.
                val tookMs = (System.nanoTime() - startedAt) / 1_000_000
                pauseMs = maxOf(MIN_PAUSE_MS, tookMs / 2)
            }
            val rest = all!!.copyOfRange(minOf(previewed, all.size), all.size)
            transcript.complete(session.finish(rest))
        } catch (e: CancellationException) {
            throw e
        } catch (_: Throwable) {
            // The caller falls back to transcribing the recording itself.
        } finally {
            transcript.complete(null)
            withContext(NonCancellable) { session?.close() }
        }
    }

    /**
     * Ends the previews and returns the transcript of [audio], the whole
     * recording; null when live transcription failed along the way.
     */
    suspend fun finish(audio: FloatArray): String? {
        recording.complete(audio)
        try {
            return transcript.await()
        } catch (e: CancellationException) {
            job.cancel()
            throw e
        }
    }

    /** Ends the previews and drops the dictation. */
    suspend fun cancel() {
        job.cancelAndJoin()
    }

    private companion object {
        const val POLL_MS = 100L
        /** A preview is worth running for 200 ms of new audio. */
        const val MIN_FRESH_SAMPLES = 3_200
        const val MIN_PAUSE_MS = 200L
    }
}
