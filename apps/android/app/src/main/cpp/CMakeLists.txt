cmake_minimum_required(VERSION 3.22.1)
project("rust-backend-jni")

# jniLibs carries the prebuilt librust_backend.so from the Rust engine.
link_directories(${CMAKE_SOURCE_DIR}/../jniLibs/${ANDROID_ABI})

add_library(rust_backend_jni SHARED rust_backend_jni.cpp)

target_link_libraries(rust_backend_jni log)
set_target_properties(rust_backend_jni PROPERTIES LINK_FLAGS "-L${CMAKE_SOURCE_DIR}/../jniLibs/${ANDROID_ABI} -lrust_backend")
