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

void transcriber_destroy(TranscriberHandle handle);

void transcriber_free_string(const char *s);

#ifdef __cplusplus
}
#endif

#endif /* RUST_BACKEND_H */
