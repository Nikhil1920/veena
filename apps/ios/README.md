# Veena iOS

Offline speech-to-text for the 22 scheduled languages of India. Record or
import audio, transcribe on-device with IndicConformer models through the
shared engine in [`crates/rust-backend`](../../crates/rust-backend), and keep
a local history.

## Project layout

- `project.yml` — the single source of truth for the Xcode project
  ([XcodeGen](https://github.com/yonaskolb/XcodeGen) definition).
- `Veena/` — application sources. `Veena/Bridging/rust_backend.h` is the
  app-side copy of the engine's C ABI header.
- `APP_STORE_LISTING.md` — App Store metadata.

## Building

1. Build the engine XCFramework (see
   [`crates/rust-backend/README.md`](../../crates/rust-backend/README.md)) and
   place it at `frameworks/RustBackend.xcframework`. The framework is a build
   artifact and is never committed.
2. Generate the Xcode project and open it:

   ```bash
   brew install xcodegen   # once
   cd apps/ios
   xcodegen generate
   open Veena.xcodeproj
   ```

`Veena.xcodeproj` is generated from `project.yml` and not tracked — change
`project.yml`, not the project file.

Model archives are downloaded on first launch from Veena's model CDN; nothing
model-related is bundled.
