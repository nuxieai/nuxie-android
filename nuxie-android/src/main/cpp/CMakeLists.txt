# JNI shim over the prebuilt Nuxie engine (nux_capi). The engine .so and its
# generated header are fetched from the pin (or explicitly staged for local
# runtime work) under runtime/prebuilt/; this build only compiles the thin
# adapter.
cmake_minimum_required(VERSION 3.22.1)
project(nuxie_runtime_android C)

set(NUXIE_PREBUILT_DIR "${CMAKE_CURRENT_SOURCE_DIR}/../../../../runtime/prebuilt")

add_library(nux_capi SHARED IMPORTED)
set_target_properties(nux_capi PROPERTIES
    IMPORTED_LOCATION "${NUXIE_PREBUILT_DIR}/jniLibs/${ANDROID_ABI}/libnux_capi.so"
    # The Rust cdylib carries no SONAME; without this the shim records the
    # absolute host path as DT_NEEDED and dlopen fails on device.
    IMPORTED_NO_SONAME TRUE)

add_library(nuxie_runtime_android SHARED nuxie_runtime_android.c)
target_include_directories(nuxie_runtime_android PRIVATE "${NUXIE_PREBUILT_DIR}/include")
target_compile_definitions(nuxie_runtime_android PRIVATE
    NUX_CAPI_ANDROID_VULKAN
    NUX_CAPI_ANDROID_AUTHORED_WGSL)
target_link_libraries(nuxie_runtime_android nux_capi android log)
