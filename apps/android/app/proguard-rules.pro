# Keep JNI entry points referenced from RustBackend.kt
-keepclasseswithmembernames class com.byanr.veena.android.** {
    native <methods>;
}
