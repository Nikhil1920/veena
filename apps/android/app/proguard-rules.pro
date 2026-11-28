# Keep JNI entry points referenced from RustBackend.kt
-keepclasseswithmembernames class com.byanr.veena.android.** {
    native <methods>;
}

# Invoked by name from rust_backend_jni.cpp to report transcription progress
-keep interface com.byanr.veena.android.engine.TranscriptionProgress { *; }

# Play In-App Review (review-ktx 2.0.2) references this compile-only annotation;
# it is never needed at runtime.
-dontwarn com.google.android.gms.common.annotation.NoNullnessRewrite
