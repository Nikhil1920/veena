#ifndef RUST_BACKEND_BRIDGE_H
#define RUST_BACKEND_BRIDGE_H

#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>
#include <zlib.h>

/*
 * Veena speech engine (IndicConformer ONNX) C ABI.
 * Symbols live in the vendored RustBackend.xcframework.
 */

typedef struct Transcriber Transcriber;

Transcriber *transcriber_create(const char *model_path,
                                const char *decoder_config_path,
                                const char *vocab_path);

Transcriber *transcriber_create_multilingual(const char *model_path,
                                             const char *decoder_config_path,
                                             const char *vocab_path,
                                             const char *masks_path);

/* The engine returns malloc'd UTF-8 strings owned by the caller. */
char *transcriber_transcribe_file(Transcriber *handle,
                                  const char *audio_path);

char *transcriber_transcribe_file_lang(Transcriber *handle,
                                       const char *audio_path,
                                       const char *language);

char *transcriber_transcribe_audio(Transcriber *handle,
                                   const float *audio,
                                   size_t len,
                                   uint32_t sample_rate);

char *transcriber_transcribe_audio_lang(Transcriber *handle,
                                        const float *audio,
                                        size_t len,
                                        uint32_t sample_rate,
                                        const char *language);

/*
 * Long audio is transcribed in windows of up to 25 s. The _progress variants
 * call `progress` synchronously on the calling thread with (completed, total)
 * windows: once with none completed, then after each window. Returning false
 * cancels: the call stops before the next window and returns NULL.
 */
typedef bool (*TranscriberProgressFn)(void *user_data,
                                      size_t completed,
                                      size_t total);

char *transcriber_transcribe_file_progress(Transcriber *handle,
                                           const char *audio_path,
                                           const char *language,
                                           TranscriberProgressFn progress,
                                           void *user_data);

char *transcriber_transcribe_audio_progress(Transcriber *handle,
                                            const float *audio,
                                            size_t len,
                                            uint32_t sample_rate,
                                            const char *language,
                                            TranscriberProgressFn progress,
                                            void *user_data);

/*
 * Live transcription of a recording in progress: push 16 kHz mono audio as
 * it arrives, preview for text to show meanwhile (its last words can still
 * change), finish for the transcript. One recording and one caller at a
 * time; destroy the session before its transcriber.
 */
typedef struct LiveSession LiveSession;

LiveSession *live_session_create(Transcriber *handle, const char *language);

void live_session_push(LiveSession *session, const float *audio, size_t len);

char *live_session_preview(LiveSession *session);

char *live_session_finish(LiveSession *session);

void live_session_destroy(LiveSession *session);

/*
 * Conversation captions: one line of text per utterance, for as long as
 * audio keeps coming. Push 16 kHz mono audio as it arrives (cheap); step
 * decodes what is due and returns what changed, as JSON:
 *   {"finished": ["final text of each utterance that ended", ...],
 *    "partial": "the utterance still being spoken; can still change",
 *    "speaking": true}
 * A 700 ms pause ends an utterance. Flush ends the one in progress, for
 * when listening pauses or stops. Step and flush return NULL on failure.
 * One caller at a time; destroy the conversation before its transcriber.
 * Create returns NULL for a language the model does not support.
 */
typedef struct Conversation Conversation;

Conversation *conversation_create(Transcriber *handle, const char *language);

void conversation_push(Conversation *conversation, const float *audio, size_t len);

char *conversation_step(Conversation *conversation);

char *conversation_flush(Conversation *conversation);

void conversation_destroy(Conversation *conversation);

void transcriber_destroy(Transcriber *handle);

void transcriber_free_string(char *s);

#endif /* RUST_BACKEND_BRIDGE_H */
