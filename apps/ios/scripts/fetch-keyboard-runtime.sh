#!/bin/bash
# Fetches the ONNX Runtime Web build that the keyboard's in-place dictation
# runs Veena's model on, into VeenaKeyboard/Web/ort. Like the engine
# XCFramework it is a build artifact and is never committed. Run it before
# `xcodegen generate`; a keyboard built without it still works, through the
# hand-off to the app.
#
# The version matches apps/web (package.json), whose pipeline the keyboard's
# engine was ported from.
set -euo pipefail

VERSION="1.29.0"
SHA256="7a934b7811c3b050ecfb7619722e2b4de771ce6da20520e17a2018a440316ef3"
FILES=(ort.wasm.min.mjs ort-wasm-simd-threaded.mjs ort-wasm-simd-threaded.wasm)

DEST="$(cd "$(dirname "$0")/.." && pwd)/VeenaKeyboard/Web/ort"
STAMP="$DEST/.version"

if [ "$(cat "$STAMP" 2>/dev/null)" = "$VERSION" ]; then
  echo "ONNX Runtime Web $VERSION is already in place."
  exit 0
fi

WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

curl -fsSL -o "$WORK/ort.tgz" \
  "https://registry.npmjs.org/onnxruntime-web/-/onnxruntime-web-$VERSION.tgz"
ACTUAL="$( (shasum -a 256 "$WORK/ort.tgz" 2>/dev/null || sha256sum "$WORK/ort.tgz") | cut -d' ' -f1)"
if [ "$ACTUAL" != "$SHA256" ]; then
  echo "onnxruntime-web $VERSION has an unexpected checksum: $ACTUAL" >&2
  exit 1
fi

mkdir -p "$WORK/out"
for file in "${FILES[@]}"; do
  tar -xzf "$WORK/ort.tgz" -C "$WORK/out" --strip-components 2 "package/dist/$file"
done

rm -rf "$DEST"
mkdir -p "$DEST"
mv "$WORK/out"/* "$DEST/"
echo "$VERSION" > "$STAMP"
echo "Fetched ONNX Runtime Web $VERSION into $DEST."
