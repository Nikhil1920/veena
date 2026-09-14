#ifndef RUST_BACKEND_BRIDGE_H
#define RUST_BACKEND_BRIDGE_H

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

void transcriber_destroy(Transcriber *handle);

void transcriber_free_string(char *s);

#endif /* RUST_BACKEND_BRIDGE_H */
