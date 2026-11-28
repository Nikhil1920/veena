#ifndef RUST_BACKEND_H
#define RUST_BACKEND_H

#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

/*
 * Veena speech engine (IndicConformer ONNX) C ABI.
 *
 * Single-language models (e.g. Telugu) are created with
 * transcriber_create; the multilingual model (22 languages) with
 * transcriber_create_multilingual. Language codes are ISO 639-1/3
 * ("te", "hi", ...) and may be NULL for the model default.
 */

typedef void *TranscriberHandle;

TranscriberHandle transcriber_create(const char *model_path,
                                     const char *decoder_config_path,
                                     const char *vocab_path);

TranscriberHandle transcriber_create_multilingual(const char *model_path,
                                                  const char *decoder_config_path,
                                                  const char *vocab_path,
                                                  const char *masks_path);

const char *transcriber_transcribe_file(TranscriberHandle handle,
                                        const char *audio_path);

const char *transcriber_transcribe_file_lang(TranscriberHandle handle,
                                             const char *audio_path,
                                             const char *language);

const char *transcriber_transcribe_audio(TranscriberHandle handle,
                                         const float *audio,
                                         size_t len,
                                         uint32_t sample_rate);

const char *transcriber_transcribe_audio_lang(TranscriberHandle handle,
                                              const float *audio,
                                              size_t len,
                                              uint32_t sample_rate,
                                              const char *language);

/*
 * Long audio is transcribed in windows of up to 25 s. The _progress variants
 * call `progress` synchronously on the calling thread with (completed, total)
 * windows: once with none completed, then after each window. Returning false
 * cancels: the call stops before the next window and returns NULL. `progress`
 * may be NULL; `user_data` is passed through untouched.
 */
typedef bool (*TranscriberProgressFn)(void *user_data,
                                      size_t completed,
                                      size_t total);

const char *transcriber_transcribe_file_progress(TranscriberHandle handle,
                                                 const char *audio_path,
                                                 const char *language,
                                                 TranscriberProgressFn progress,
                                                 void *user_data);

const char *transcriber_transcribe_audio_progress(TranscriberHandle handle,
                                                  const float *audio,
                                                  size_t len,
                                                  uint32_t sample_rate,
                                                  const char *language,
                                                  TranscriberProgressFn progress,
                                                  void *user_data);

/*
 * Live transcription: text for a recording while it is still being made.
 * Push 16 kHz mono audio as it arrives. live_session_preview returns the
 * recording so far as text, whose last words can still change;
 * live_session_finish returns the transcript, the same text
 * transcriber_transcribe_audio gives for that audio, with all but its last
 * window already done. Both return NULL on failure, and their strings are
 * freed with transcriber_free_string.
 *
 * A session serves one recording and one caller at a time, and must be
 * destroyed before its transcriber. live_session_create returns NULL for a
 * language the model does not support.
 */
typedef void *LiveSessionHandle;

LiveSessionHandle live_session_create(TranscriberHandle handle,
                                      const char *language);

void live_session_push(LiveSessionHandle session,
                       const float *audio,
                       size_t len);

const char *live_session_preview(LiveSessionHandle session);

const char *live_session_finish(LiveSessionHandle session);

void live_session_destroy(LiveSessionHandle session);

/*
 * Conversation captions: one line of text per utterance, for as long as
 * audio keeps coming. Push 16 kHz mono audio as it arrives (cheap) and call
 * conversation_step for what changed; it decodes only what is due. A pause
 * of 700 ms ends an utterance, and one that runs past 15 s is cut at a quiet
 * moment. Ended utterances are transcribed whole and their audio dropped.
 *
 * conversation_step and conversation_flush return JSON, freed with
 * transcriber_free_string, or NULL on failure:
 *   {"finished": ["final text of each utterance that ended", ...],
 *    "partial": "the utterance still being spoken; can still change",
 *    "speaking": true}
 * An utterance whose final text fails to decode is dropped. conversation_flush
 * ends the utterance in progress, for when listening pauses or stops.
 *
 * A conversation serves one caller at a time and must be destroyed before
 * its transcriber. conversation_create returns NULL for a language the model
 * does not support.
 */
typedef void *ConversationHandle;

ConversationHandle conversation_create(TranscriberHandle handle,
                                       const char *language);

void conversation_push(ConversationHandle conversation,
                       const float *audio,
                       size_t len);

const char *conversation_step(ConversationHandle conversation);

const char *conversation_flush(ConversationHandle conversation);

void conversation_destroy(ConversationHandle conversation);

void transcriber_destroy(TranscriberHandle handle);

void transcriber_free_string(const char *s);

#ifdef __cplusplus
}
#endif

#endif /* RUST_BACKEND_H */
