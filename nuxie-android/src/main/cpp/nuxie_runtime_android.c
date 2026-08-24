// JNI shim: adapts the engine's portable C ABI (nux_capi) plus the Android
// Vulkan presentation extension to ai.nuxie.sdk.runtime.NuxieRuntimeBridge.
//
// Rules (iOS adapter parity):
// - Handles cross as jlong; 0 means failure. The Kotlin lane owns lifetime.
// - Panics never cross: the Rust side already firewalls them into statuses.
// - This shim performs no allocation-free trickery: owned results are freed
//   before returning.

#include <android/log.h>
#include <android/native_window_jni.h>
#include <errno.h>
#include <fcntl.h>
#include <jni.h>
#include <limits.h>
#include <pthread.h>
#include <stddef.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>

#include "nux_capi.generated.h"

// Android discards stderr, but Rust panic messages print there. Pump the
// process's stderr into logcat so contained panics are diagnosable.
static void *stderr_pump(void *arg) {
  int fd = (int)(intptr_t)arg;
  char line[512];
  size_t used = 0;
  int read_failed = 0;
  for (;;) {
    char chunk[128];
    ssize_t n = read(fd, chunk, sizeof(chunk));
    if (n < 0 && errno == EINTR) continue;
    if (n < 0) {
      read_failed = 1;
      break;
    }
    if (n == 0) break;
    for (ssize_t i = 0; i < n; i++) {
      if (chunk[i] == '\n' || used == sizeof(line) - 1) {
        line[used] = '\0';
        if (used > 0) {
          __android_log_print(ANDROID_LOG_WARN, "Nuxie", "stderr: %s", line);
        }
        used = 0;
        if (chunk[i] == '\n') continue;
      }
      line[used++] = chunk[i];
    }
  }
  // EOF means every write end is gone: the host replaced stderr with its
  // own target, so leave it alone. A read error means stderr may still be
  // our pipe with no reader, where writers would eventually block on a
  // full pipe; point stderr at /dev/null before abandoning it. If open
  // hands back fd 2 itself, /dev/null is already installed as stderr and
  // must stay open.
  if (read_failed) {
    int devnull = open("/dev/null", O_WRONLY);
    if (devnull >= 0 && devnull != STDERR_FILENO) {
      dup2(devnull, STDERR_FILENO);
      close(devnull);
    }
  }
  close(fd);
  return NULL;
}

__attribute__((constructor)) static void redirect_stderr_to_logcat(void) {
  int fds[2];
  if (pipe(fds) != 0) return;
  if (fds[0] <= STDERR_FILENO || fds[1] <= STDERR_FILENO) {
    // A stdio fd was closed in this process; redirecting would clobber
    // our own pipe end. Leave stderr alone.
    close(fds[0]);
    close(fds[1]);
    return;
  }
  pthread_t thread;
  if (pthread_create(&thread, NULL, stderr_pump, (void *)(intptr_t)fds[0]) != 0) {
    close(fds[0]);
    close(fds[1]);
    return;
  }
  pthread_detach(thread);
  // The pump is guaranteed to be draining before any write can land.
  dup2(fds[1], STDERR_FILENO);
  close(fds[1]);
}

static jlong as_handle(void *pointer) { return (jlong)(intptr_t)pointer; }
static void *from_handle(jlong handle) { return (void *)(intptr_t)handle; }

static void free_result(struct NuxCapiResult *result) {
  if (result != NULL) {
    nux_capi_result_free(result);
  }
}

// Log the engine's diagnostic (code + message) for a failed call, then free
// the owned result. Statuses alone are not actionable from logcat.
static void log_and_free_result(const char *operation, NuxStatus status,
                                struct NuxCapiResult *result) {
  if (status != NUX_STATUS_OK) {
    struct NuxCapiDiagnosticView view;
    memset(&view, 0, sizeof(view));
    view.struct_size = (uint32_t)sizeof(view);
    if (result != NULL &&
        nux_capi_result_diagnostic(result, &view) == NUX_STATUS_OK) {
      __android_log_print(ANDROID_LOG_WARN, "Nuxie",
                          "%s failed: status=%d code=%.*s message=%.*s",
                          operation, (int)status, (int)view.code.len,
                          view.code.data ? view.code.data : "",
                          (int)view.message.len,
                          view.message.data ? view.message.data : "");
    } else {
      __android_log_print(ANDROID_LOG_WARN, "Nuxie", "%s failed: status=%d",
                          operation, (int)status);
    }
  }
  free_result(result);
}

JNIEXPORT jlong JNICALL
Java_ai_nuxie_sdk_runtime_NuxieRuntimeBridge_nativeFileNew(
    JNIEnv *env, jobject self, jbyteArray bytes) {
  (void)self;
  if (bytes == NULL) return 0;
  jsize length = (*env)->GetArrayLength(env, bytes);
  jbyte *data = (*env)->GetByteArrayElements(env, bytes, NULL);
  if (data == NULL) return 0;
  struct NuxFile *file = NULL;
  NuxStatus status = nux_file_import((const uint8_t *)data, (size_t)length, &file);
  (*env)->ReleaseByteArrayElements(env, bytes, data, JNI_ABORT);
  return status == NUX_STATUS_OK ? as_handle(file) : 0;
}

JNIEXPORT void JNICALL
Java_ai_nuxie_sdk_runtime_NuxieRuntimeBridge_nativeFileFree(
    JNIEnv *env, jobject self, jlong file) {
  (void)env;
  (void)self;
  if (file != 0) nux_file_free((struct NuxFile *)from_handle(file));
}

JNIEXPORT jlong JNICALL
Java_ai_nuxie_sdk_runtime_NuxieRuntimeBridge_nativeArtboardInstanceNewNamed(
    JNIEnv *env, jobject self, jlong file, jstring artboard_name) {
  (void)self;
  if (file == 0 || artboard_name == NULL) return 0;
  const char *name = (*env)->GetStringUTFChars(env, artboard_name, NULL);
  if (name == NULL) return 0;
  struct NuxStringView view = {name, strlen(name)};
  struct NuxArtboardInstance *instance = NULL;
  NuxStatus status = nux_artboard_instance_new_named(
      (const struct NuxFile *)from_handle(file), view, &instance);
  (*env)->ReleaseStringUTFChars(env, artboard_name, name);
  return status == NUX_STATUS_OK ? as_handle(instance) : 0;
}

JNIEXPORT jlong JNICALL
Java_ai_nuxie_sdk_runtime_NuxieRuntimeBridge_nativeArtboardInstanceNewDefault(
    JNIEnv *env, jobject self, jlong file) {
  (void)env;
  (void)self;
  if (file == 0) return 0;
  struct NuxArtboardInstance *instance = NULL;
  NuxStatus status = nux_artboard_instance_new(
      (const struct NuxFile *)from_handle(file), 0, &instance);
  return status == NUX_STATUS_OK ? as_handle(instance) : 0;
}

JNIEXPORT void JNICALL
Java_ai_nuxie_sdk_runtime_NuxieRuntimeBridge_nativeArtboardInstanceFree(
    JNIEnv *env, jobject self, jlong artboard) {
  (void)env;
  (void)self;
  if (artboard != 0) {
    nux_artboard_instance_free((struct NuxArtboardInstance *)from_handle(artboard));
  }
}

JNIEXPORT jlong JNICALL
Java_ai_nuxie_sdk_runtime_NuxieRuntimeBridge_nativePlayerNewDefault(
    JNIEnv *env, jobject self, jlong artboard) {
  (void)env;
  (void)self;
  if (artboard == 0) return 0;
  struct NuxPlayer *player = NULL;
  NuxStatus status = nux_player_new_default(
      (struct NuxArtboardInstance *)from_handle(artboard), &player);
  return status == NUX_STATUS_OK ? as_handle(player) : 0;
}

JNIEXPORT jlong JNICALL
Java_ai_nuxie_sdk_runtime_NuxieRuntimeBridge_nativePlayerNewStateMachineNamed(
    JNIEnv *env, jobject self, jlong artboard, jstring machine_name) {
  (void)self;
  if (artboard == 0 || machine_name == NULL) return 0;
  const char *name = (*env)->GetStringUTFChars(env, machine_name, NULL);
  if (name == NULL) return 0;
  struct NuxStringView view = {name, strlen(name)};
  struct NuxPlayer *player = NULL;
  NuxStatus status = nux_player_new_state_machine_named(
      (struct NuxArtboardInstance *)from_handle(artboard), view, &player);
  (*env)->ReleaseStringUTFChars(env, machine_name, name);
  return status == NUX_STATUS_OK ? as_handle(player) : 0;
}

JNIEXPORT void JNICALL
Java_ai_nuxie_sdk_runtime_NuxieRuntimeBridge_nativePlayerFree(
    JNIEnv *env, jobject self, jlong player) {
  (void)env;
  (void)self;
  if (player != 0) nux_player_free((struct NuxPlayer *)from_handle(player));
}

JNIEXPORT jint JNICALL
Java_ai_nuxie_sdk_runtime_NuxieRuntimeBridge_nativePlayerStep(
    JNIEnv *env, jobject self, jlong player, jdouble elapsed_seconds) {
  (void)env;
  (void)self;
  if (player == 0) return (jint)NUX_STATUS_NULL_ARGUMENT;
  struct NuxPlayerStep step;
  memset(&step, 0, sizeof(step));
  step.struct_size = (uint32_t)sizeof(step);
  step.elapsed_seconds = (float)elapsed_seconds;
  struct NuxPlayerStepResult *result = NULL;
  NuxStatus status = nux_player_step(
      (struct NuxPlayer *)from_handle(player), &step, &result);
  if (result != NULL) {
    nux_player_step_result_free(result);
  }
  return (jint)status;
}

JNIEXPORT jlong JNICALL
Java_ai_nuxie_sdk_runtime_NuxieRuntimeBridge_nativeRendererNewAndroidVulkan(
    JNIEnv *env, jobject self, jint pixel_width, jint pixel_height) {
  (void)env;
  (void)self;
  if (pixel_width <= 0 || pixel_height <= 0) return 0;
  struct NuxAndroidVulkanRenderer *renderer = NULL;
  struct NuxCapiResult *result = NULL;
  NuxStatus status = nux_renderer_new_android_vulkan(
      (uint32_t)pixel_width, (uint32_t)pixel_height, &renderer, &result);
  log_and_free_result("renderer_new_android_vulkan", status, result);
  return status == NUX_STATUS_OK ? as_handle(renderer) : 0;
}

JNIEXPORT jlong JNICALL
Java_ai_nuxie_sdk_runtime_NuxieRuntimeBridge_nativeWindowAcquire(
    JNIEnv *env, jobject self, jobject surface) {
  (void)self;
  if (surface == NULL) return 0;
  return as_handle(ANativeWindow_fromSurface(env, surface));
}

JNIEXPORT void JNICALL
Java_ai_nuxie_sdk_runtime_NuxieRuntimeBridge_nativeWindowRelease(
    JNIEnv *env, jobject self, jlong window) {
  (void)env;
  (void)self;
  if (window != 0) ANativeWindow_release((ANativeWindow *)from_handle(window));
}

JNIEXPORT jint JNICALL
Java_ai_nuxie_sdk_runtime_NuxieRuntimeBridge_nativeRendererResize(
    JNIEnv *env, jobject self, jlong renderer, jint pixel_width,
    jint pixel_height) {
  (void)env;
  (void)self;
  if (renderer == 0) return (jint)NUX_STATUS_NULL_ARGUMENT;
  if (pixel_width <= 0 || pixel_height <= 0) {
    return (jint)NUX_STATUS_INVALID_ARGUMENT;
  }
  struct NuxCapiResult *result = NULL;
  NuxStatus status = nux_renderer_android_vulkan_resize(
      (struct NuxAndroidVulkanRenderer *)from_handle(renderer),
      (uint32_t)pixel_width, (uint32_t)pixel_height, &result);
  log_and_free_result("renderer_android_vulkan_resize", status, result);
  return (jint)status;
}

static jint blit_android_vulkan_frame(
    ANativeWindow *window, const struct NuxAndroidVulkanFrame *frame) {
  const uint32_t width = nux_android_vulkan_frame_width(frame);
  const uint32_t height = nux_android_vulkan_frame_height(frame);
  const uint32_t source_stride =
      nux_android_vulkan_frame_row_stride_bytes(frame);
  const size_t source_len = nux_android_vulkan_frame_len(frame);
  const uint8_t *source = nux_android_vulkan_frame_data(frame);

  if (width == 0 || height == 0 || width > INT32_MAX || height > INT32_MAX) {
    return -((jint)NUX_STATUS_INVALID_ARGUMENT);
  }
  const size_t row_bytes = (size_t)width * 4u;
  if (source == NULL || source_stride < row_bytes ||
      height > SIZE_MAX / source_stride ||
      source_len < (size_t)height * source_stride ||
      nux_android_vulkan_frame_pixel_format(frame) !=
          NUX_ANDROID_VULKAN_PIXEL_FORMAT_RGBA8_PREMULTIPLIED) {
    return -((jint)NUX_STATUS_RUNTIME_ERROR);
  }

  // Setting geometry on every frame is acceptable for this tracer. The
  // window cache can be added when presentation performance is refined.
  if (ANativeWindow_setBuffersGeometry(window, (int32_t)width,
                                       (int32_t)height,
                                       WINDOW_FORMAT_RGBA_8888) != 0) {
    return -((jint)NUX_STATUS_RUNTIME_ERROR);
  }

  ANativeWindow_Buffer buffer;
  if (ANativeWindow_lock(window, &buffer, NULL) != 0) {
    return -((jint)NUX_STATUS_RUNTIME_ERROR);
  }

  jint outcome = 1;
  if (buffer.bits == NULL || buffer.width < (int32_t)width ||
      buffer.height < (int32_t)height || buffer.stride < (int32_t)width) {
    outcome = -((jint)NUX_STATUS_RUNTIME_ERROR);
  } else {
    const size_t destination_stride = (size_t)buffer.stride * 4u;
    uint8_t *destination = (uint8_t *)buffer.bits;
    for (uint32_t row = 0; row < height; row++) {
      memcpy(destination + (size_t)row * destination_stride,
             source + (size_t)row * source_stride, row_bytes);
    }
  }

  if (ANativeWindow_unlockAndPost(window) != 0) {
    outcome = -((jint)NUX_STATUS_RUNTIME_ERROR);
  }
  return outcome;
}

JNIEXPORT jint JNICALL
Java_ai_nuxie_sdk_runtime_NuxieRuntimeBridge_nativeRendererRenderPlayer(
    JNIEnv *env, jobject self, jlong renderer, jlong player, jlong window,
    jint clear_color, jboolean fit_contain_center) {
  (void)env;
  (void)self;
  if (renderer == 0 || player == 0 || window == 0) {
    return -((jint)NUX_STATUS_NULL_ARGUMENT);
  }
  struct NuxAndroidVulkanFrame *frame = NULL;
  struct NuxCapiResult *result = NULL;
  NuxStatus status = nux_renderer_android_vulkan_render_player(
      (struct NuxAndroidVulkanRenderer *)from_handle(renderer),
      (struct NuxPlayer *)from_handle(player), (uint32_t)clear_color,
      fit_contain_center == JNI_TRUE
          ? NUX_ANDROID_VULKAN_RENDERER_FIT_CONTAIN_CENTER
          : NUX_ANDROID_VULKAN_RENDERER_FIT_NONE,
      &frame, &result);
  log_and_free_result("renderer_android_vulkan_render_player", status, result);
  if (status != NUX_STATUS_OK || frame == NULL) {
    if (frame != NULL) nux_android_vulkan_frame_free(frame);
    return -((jint)(status == NUX_STATUS_OK ? NUX_STATUS_RUNTIME_ERROR : status));
  }

  jint outcome = blit_android_vulkan_frame(
      (ANativeWindow *)from_handle(window), frame);
  nux_android_vulkan_frame_free(frame);
  return outcome;
}

JNIEXPORT jint JNICALL
Java_ai_nuxie_sdk_runtime_NuxieRuntimeBridge_nativeRendererResetPlayerDomain(
    JNIEnv *env, jobject self, jlong renderer, jlong player) {
  (void)env;
  (void)self;
  if (renderer == 0 || player == 0) return (jint)NUX_STATUS_NULL_ARGUMENT;
  struct NuxCapiResult *result = NULL;
  NuxStatus status = nux_renderer_android_vulkan_reset_player_domain(
      (const struct NuxAndroidVulkanRenderer *)from_handle(renderer),
      (struct NuxPlayer *)from_handle(player), &result);
  log_and_free_result("renderer_android_vulkan_reset_player_domain", status,
                      result);
  return (jint)status;
}

JNIEXPORT void JNICALL
Java_ai_nuxie_sdk_runtime_NuxieRuntimeBridge_nativeRendererFree(
    JNIEnv *env, jobject self, jlong renderer) {
  (void)env;
  (void)self;
  if (renderer != 0) {
    nux_renderer_android_vulkan_free(
        (struct NuxAndroidVulkanRenderer *)from_handle(renderer));
  }
}

JNIEXPORT jstring JNICALL
Java_ai_nuxie_sdk_runtime_NuxieRuntimeBridge_nativeRuntimeInfo(
    JNIEnv *env, jobject self) {
  (void)self;
  struct NuxRuntimeInfo info;
  memset(&info, 0, sizeof(info));
  info.struct_size = (uint32_t)sizeof(info);
  if (nux_capi_runtime_info(&info) != NUX_STATUS_OK) {
    return (*env)->NewStringUTF(env, "{}");
  }
  char buffer[512];
  size_t version_len = info.runtime_version.len < 128 ? info.runtime_version.len : 128;
  size_t revision_len = info.source_revision.len < 128 ? info.source_revision.len : 128;
  snprintf(buffer, sizeof(buffer),
           "{\"abiVersion\":%u,\"runtimeVersion\":\"%.*s\",\"sourceRevision\":\"%.*s\"}",
           (unsigned)info.abi_version, (int)version_len,
           info.runtime_version.data != NULL ? info.runtime_version.data : "",
           (int)revision_len,
           info.source_revision.data != NULL ? info.source_revision.data : "");
  return (*env)->NewStringUTF(env, buffer);
}
