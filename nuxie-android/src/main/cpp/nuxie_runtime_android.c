// JNI shim: adapts the engine's portable C ABI (nux_capi) plus the Android
// Vulkan presentation extension to ai.nuxie.sdk.runtime.NuxieRuntimeBridge.
//
// Rules (iOS adapter parity):
// - Handles cross as jlong; 0 means failure. The Kotlin lane owns lifetime.
// - Panics never cross: the Rust side already firewalls them into statuses.
// - This shim performs no allocation-free trickery: owned results are freed
//   before returning.

#if defined(__ANDROID__)
#include <android/log.h>
#include <android/native_window_jni.h>
#endif
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

#if defined(__ANDROID__)
#define NUXIE_LOG_WARN(...) \
  __android_log_print(ANDROID_LOG_WARN, "Nuxie", __VA_ARGS__)
#else
#define NUXIE_LOG_WARN(...)          \
  do {                               \
    fprintf(stderr, "Nuxie: ");     \
    fprintf(stderr, __VA_ARGS__);    \
    fputc('\n', stderr);             \
  } while (0)
#endif

// Android discards stderr, but Rust panic messages print there. Pump the
// process's stderr into logcat so contained panics are diagnosable.
#if defined(__ANDROID__)
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
          NUXIE_LOG_WARN("stderr: %s", line);
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
#endif

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
      NUXIE_LOG_WARN("%s failed: status=%d code=%.*s message=%.*s",
                     operation, (int)status, (int)view.code.len,
                     view.code.data ? view.code.data : "",
                     (int)view.message.len,
                     view.message.data ? view.message.data : "");
    } else {
      NUXIE_LOG_WARN("%s failed: status=%d", operation, (int)status);
    }
  }
  free_result(result);
}

static void log_cleanup_failure(const char *operation, NuxStatus status) {
  if (status != NUX_STATUS_OK) {
    NUXIE_LOG_WARN("%s cleanup failed: status=%d", operation, (int)status);
  }
}

static void configure_host_commands(struct NuxHostCommandImportConfig *host) {
  static const char module_name[] = "nuxie";
  memset(host, 0, sizeof(*host));
  host->struct_size = (uint32_t)sizeof(*host);
  host->module_name.data = module_name;
  host->module_name.len = sizeof(module_name) - 1;
  host->max_script_memory_bytes = 64u * 1024u * 1024u;
  host->max_script_interrupts_per_callback = 50000u;
  host->max_commands_per_step = 256u;
  host->max_value_depth = 32u;
  host->max_value_nodes = 4096u;
  host->max_identifier_bytes = 4096u;
  host->max_string_bytes = 1024u * 1024u;
  host->max_value_bytes = 4u * 1024u * 1024u;
  host->max_command_bytes_per_step = 4u * 1024u * 1024u;
}

#if !defined(__ANDROID__)
// A valid, minimal Rive file. Importing it through the trusted host-command
// surface distinguishes a scripting-enabled nux_capi from one that exports
// the compatibility symbol but was compiled without the scripting feature.
static const uint8_t host_scripting_probe_file[] = {
    0x52, 0x49, 0x56, 0x45, 0x07, 0x02, 0x98, 0xcf, 0x01,
    0x00, 0x17, 0x00, 0x69, 0xcc, 0x01, 0x07, 0x00, 0x01,
    0x07, 0x00, 0x00, 0x80, 0x42, 0x08, 0x00, 0x00, 0x80,
    0x42, 0x00, 0x64, 0x05, 0x00, 0xce, 0x01, 0x00, 0x00,
};

JNIEXPORT jint JNICALL
Java_ai_nuxie_sdk_runtime_NuxieRuntimeBridge_nativeHostScriptingProbe(
    JNIEnv *env, jobject self) {
  (void)env;
  (void)self;
  struct NuxHostCommandImportConfig host;
  configure_host_commands(&host);
  struct NuxFile *file = NULL;
  struct NuxCapiResult *result = NULL;
  struct NuxRenderCallbacks callbacks;
  memset(&callbacks, 0, sizeof(callbacks));
  callbacks.struct_size = (uint32_t)sizeof(callbacks);
  NuxStatus status = nux_file_import_trusted_with_host_commands(
      host_scripting_probe_file, sizeof(host_scripting_probe_file), &callbacks,
      &host, &file, &result);
  log_and_free_result("host_scripting_probe", status, result);
  if (file != NULL) {
    log_cleanup_failure("host_scripting_probe_file_free", nux_file_free(file));
  }
  return (jint)status;
}
#endif

struct asset_payload {
  uint8_t *data;
  size_t len;
  int present;
};

struct asset_import_context {
  JNIEnv *env;
  jobject decoder;
  struct asset_payload *payloads;
  size_t payload_count;
};

static void retain_malloc_bytes(void *owner) { (void)owner; }

static void release_malloc_bytes(void *owner) { free(owner); }

static int clear_jni_exception(JNIEnv *env) {
  if (!(*env)->ExceptionCheck(env)) return 0;
  (*env)->ExceptionClear(env);
  return 1;
}

static int set_status_out(JNIEnv *env, jintArray out_status, NuxStatus status) {
  if (out_status == NULL) return 0;
  jsize count = (*env)->GetArrayLength(env, out_status);
  if (clear_jni_exception(env) || count != 1) return 0;
  jint value = (jint)status;
  (*env)->SetIntArrayRegion(env, out_status, 0, 1, &value);
  return !clear_jni_exception(env);
}

static NuxAssetCallbackStatus lookup_external_asset(
    void *raw_context, const struct NuxExternalAssetRequest *request,
    struct NuxRetainedBytes *out_bytes) {
  if (raw_context == NULL || request == NULL || out_bytes == NULL) {
    return NUX_ASSET_CALLBACK_STATUS_FAILED;
  }
  struct asset_import_context *context = raw_context;
  if (request->asset_index >= context->payload_count ||
      !context->payloads[request->asset_index].present) {
    return NUX_ASSET_CALLBACK_STATUS_NOT_FOUND;
  }
  const struct asset_payload *payload = &context->payloads[request->asset_index];
  uint8_t *owned = malloc(payload->len == 0 ? 1 : payload->len);
  if (owned == NULL) return NUX_ASSET_CALLBACK_STATUS_FAILED;
  if (payload->len != 0) memcpy(owned, payload->data, payload->len);
  memset(out_bytes, 0, sizeof(*out_bytes));
  out_bytes->struct_size = (uint32_t)sizeof(*out_bytes);
  out_bytes->data = owned;
  out_bytes->len = payload->len;
  out_bytes->owner = owned;
  out_bytes->retain = retain_malloc_bytes;
  out_bytes->release = release_malloc_bytes;
  return NUX_ASSET_CALLBACK_STATUS_OK;
}

static NuxAssetCallbackStatus decode_image(
    void *raw_context, const struct NuxImageDecodeRequest *request,
    struct NuxDecodedImage *out_image) {
  if (raw_context == NULL || request == NULL || out_image == NULL ||
      (request->encoded.data == NULL && request->encoded.len != 0) ||
      request->encoded.len > INT32_MAX) {
    return NUX_ASSET_CALLBACK_STATUS_FAILED;
  }
  struct asset_import_context *context = raw_context;
  JNIEnv *env = context->env;
  jclass decoder_class = NULL;
  jobject decoded = NULL;
  jclass decoded_class = NULL;
  jbyteArray pixels = NULL;
  uint8_t *owned = NULL;
  jbyteArray encoded = (*env)->NewByteArray(env, (jsize)request->encoded.len);
  if (clear_jni_exception(env) || encoded == NULL) {
    goto decode_image_failed;
  }
  if (request->encoded.len != 0) {
    (*env)->SetByteArrayRegion(env, encoded, 0, (jsize)request->encoded.len,
                              (const jbyte *)request->encoded.data);
    if (clear_jni_exception(env)) goto decode_image_failed;
  }
  decoder_class = (*env)->GetObjectClass(env, context->decoder);
  if (clear_jni_exception(env) || decoder_class == NULL) {
    goto decode_image_failed;
  }
  jmethodID decode = (*env)->GetMethodID(
      env, decoder_class, "decode",
      "([BIJ)Lai/nuxie/sdk/runtime/DecodedImage;");
  if (clear_jni_exception(env) || decode == NULL) goto decode_image_failed;
  decoded = (*env)->CallObjectMethod(
      env, context->decoder, decode, encoded,
      (jint)request->maximum_dimension,
      (jlong)request->maximum_decoded_bytes);
  if (clear_jni_exception(env) || decoded == NULL) goto decode_image_failed;
  (*env)->DeleteLocalRef(env, encoded);
  encoded = NULL;
  (*env)->DeleteLocalRef(env, decoder_class);
  decoder_class = NULL;

  decoded_class = (*env)->GetObjectClass(env, decoded);
  if (clear_jni_exception(env) || decoded_class == NULL) {
    goto decode_image_failed;
  }
  jmethodID get_width =
      (*env)->GetMethodID(env, decoded_class, "getWidth", "()I");
  if (clear_jni_exception(env) || get_width == NULL) goto decode_image_failed;
  jmethodID get_height =
      (*env)->GetMethodID(env, decoded_class, "getHeight", "()I");
  if (clear_jni_exception(env) || get_height == NULL) goto decode_image_failed;
  jmethodID get_row_bytes =
      (*env)->GetMethodID(env, decoded_class, "getRowBytes", "()I");
  if (clear_jni_exception(env) || get_row_bytes == NULL) {
    goto decode_image_failed;
  }
  jmethodID get_pixels =
      (*env)->GetMethodID(env, decoded_class, "getPixels", "()[B");
  if (clear_jni_exception(env) || get_pixels == NULL) goto decode_image_failed;
  jint width = (*env)->CallIntMethod(env, decoded, get_width);
  if (clear_jni_exception(env)) goto decode_image_failed;
  jint height = (*env)->CallIntMethod(env, decoded, get_height);
  if (clear_jni_exception(env)) goto decode_image_failed;
  jint row_bytes = (*env)->CallIntMethod(env, decoded, get_row_bytes);
  if (clear_jni_exception(env)) goto decode_image_failed;
  pixels =
      (jbyteArray)(*env)->CallObjectMethod(env, decoded, get_pixels);
  if (clear_jni_exception(env) || pixels == NULL || width <= 0 || height <= 0 ||
      row_bytes <= 0) {
    goto decode_image_failed;
  }
  jsize pixel_len = (*env)->GetArrayLength(env, pixels);
  if (clear_jni_exception(env)) goto decode_image_failed;
  uint64_t tight_row_bytes = (uint64_t)(uint32_t)width * 4u;
  uint64_t tight_byte_count = tight_row_bytes * (uint64_t)(uint32_t)height;
  if ((uint32_t)width > request->maximum_dimension ||
      (uint32_t)height > request->maximum_dimension ||
      tight_row_bytes > UINT32_MAX || (uint64_t)(uint32_t)row_bytes != tight_row_bytes ||
      tight_byte_count != (uint64_t)(uint32_t)pixel_len ||
      tight_byte_count > request->maximum_decoded_bytes) {
    goto decode_image_failed;
  }
  owned = malloc(pixel_len == 0 ? 1 : (size_t)pixel_len);
  if (owned != NULL && pixel_len != 0) {
    (*env)->GetByteArrayRegion(env, pixels, 0, pixel_len, (jbyte *)owned);
    if (clear_jni_exception(env)) goto decode_image_failed;
  }
  if (owned == NULL) goto decode_image_failed;
  (*env)->DeleteLocalRef(env, pixels);
  pixels = NULL;
  (*env)->DeleteLocalRef(env, decoded_class);
  decoded_class = NULL;
  (*env)->DeleteLocalRef(env, decoded);
  decoded = NULL;

  memset(out_image, 0, sizeof(*out_image));
  out_image->struct_size = (uint32_t)sizeof(*out_image);
  out_image->width = (uint32_t)width;
  out_image->height = (uint32_t)height;
  out_image->row_bytes = (uint32_t)row_bytes;
  out_image->pixel_format = NUX_PIXEL_FORMAT_RGBA8_PREMULTIPLIED_SRGB;
  out_image->pixels.struct_size = (uint32_t)sizeof(out_image->pixels);
  out_image->pixels.data = owned;
  out_image->pixels.len = (size_t)pixel_len;
  out_image->pixels.owner = owned;
  out_image->pixels.retain = retain_malloc_bytes;
  out_image->pixels.release = release_malloc_bytes;
  return NUX_ASSET_CALLBACK_STATUS_OK;

decode_image_failed:
  free(owned);
  if (pixels != NULL) (*env)->DeleteLocalRef(env, pixels);
  if (decoded_class != NULL) (*env)->DeleteLocalRef(env, decoded_class);
  if (decoded != NULL) (*env)->DeleteLocalRef(env, decoded);
  if (decoder_class != NULL) (*env)->DeleteLocalRef(env, decoder_class);
  if (encoded != NULL) (*env)->DeleteLocalRef(env, encoded);
  return NUX_ASSET_CALLBACK_STATUS_FAILED;
}

static int is_valid_utf8(const char *data, size_t len) {
  if (data == NULL) return len == 0;
  const uint8_t *bytes = (const uint8_t *)data;
  size_t index = 0;
  while (index < len) {
    uint8_t first = bytes[index];
    if (first <= 0x7f) {
      index++;
      continue;
    }
    if (first >= 0xc2 && first <= 0xdf) {
      if (len - index < 2 || bytes[index + 1] < 0x80 ||
          bytes[index + 1] > 0xbf) {
        return 0;
      }
      index += 2;
      continue;
    }
    if (first == 0xe0) {
      if (len - index < 3 || bytes[index + 1] < 0xa0 ||
          bytes[index + 1] > 0xbf || bytes[index + 2] < 0x80 ||
          bytes[index + 2] > 0xbf) {
        return 0;
      }
      index += 3;
      continue;
    }
    if ((first >= 0xe1 && first <= 0xec) ||
        (first >= 0xee && first <= 0xef)) {
      if (len - index < 3 || bytes[index + 1] < 0x80 ||
          bytes[index + 1] > 0xbf || bytes[index + 2] < 0x80 ||
          bytes[index + 2] > 0xbf) {
        return 0;
      }
      index += 3;
      continue;
    }
    if (first == 0xed) {
      if (len - index < 3 || bytes[index + 1] < 0x80 ||
          bytes[index + 1] > 0x9f || bytes[index + 2] < 0x80 ||
          bytes[index + 2] > 0xbf) {
        return 0;
      }
      index += 3;
      continue;
    }
    if (first == 0xf0) {
      if (len - index < 4 || bytes[index + 1] < 0x90 ||
          bytes[index + 1] > 0xbf || bytes[index + 2] < 0x80 ||
          bytes[index + 2] > 0xbf || bytes[index + 3] < 0x80 ||
          bytes[index + 3] > 0xbf) {
        return 0;
      }
      index += 4;
      continue;
    }
    if (first >= 0xf1 && first <= 0xf3) {
      if (len - index < 4 || bytes[index + 1] < 0x80 ||
          bytes[index + 1] > 0xbf || bytes[index + 2] < 0x80 ||
          bytes[index + 2] > 0xbf || bytes[index + 3] < 0x80 ||
          bytes[index + 3] > 0xbf) {
        return 0;
      }
      index += 4;
      continue;
    }
    if (first == 0xf4) {
      if (len - index < 4 || bytes[index + 1] < 0x80 ||
          bytes[index + 1] > 0x8f || bytes[index + 2] < 0x80 ||
          bytes[index + 2] > 0xbf || bytes[index + 3] < 0x80 ||
          bytes[index + 3] > 0xbf) {
        return 0;
      }
      index += 4;
      continue;
    }
    return 0;
  }
  return 1;
}

static jstring new_string_view(JNIEnv *env, struct NuxStringView view) {
  if (view.len > INT32_MAX || !is_valid_utf8(view.data, view.len)) return NULL;
  jclass string_class = NULL;
  jstring charset = NULL;
  jstring value = NULL;
  jbyteArray bytes = (*env)->NewByteArray(env, (jsize)view.len);
  if (clear_jni_exception(env) || bytes == NULL) goto new_string_view_failed;
  if (view.len != 0) {
    (*env)->SetByteArrayRegion(env, bytes, 0, (jsize)view.len,
                              (const jbyte *)view.data);
    if (clear_jni_exception(env)) goto new_string_view_failed;
  }
  string_class = (*env)->FindClass(env, "java/lang/String");
  if (clear_jni_exception(env) || string_class == NULL) {
    goto new_string_view_failed;
  }
  jmethodID constructor = (*env)->GetMethodID(
      env, string_class, "<init>", "([BLjava/lang/String;)V");
  if (clear_jni_exception(env) || constructor == NULL) {
    goto new_string_view_failed;
  }
  charset = (*env)->NewStringUTF(env, "UTF-8");
  if (clear_jni_exception(env) || charset == NULL) goto new_string_view_failed;
  value = (jstring)(*env)->NewObject(
      env, string_class, constructor, bytes, charset);
  if (clear_jni_exception(env) || value == NULL) goto new_string_view_failed;
  (*env)->DeleteLocalRef(env, charset);
  (*env)->DeleteLocalRef(env, string_class);
  (*env)->DeleteLocalRef(env, bytes);
  return value;

new_string_view_failed:
  if (value != NULL) (*env)->DeleteLocalRef(env, value);
  if (charset != NULL) (*env)->DeleteLocalRef(env, charset);
  if (string_class != NULL) (*env)->DeleteLocalRef(env, string_class);
  if (bytes != NULL) (*env)->DeleteLocalRef(env, bytes);
  return NULL;
}

static jbyteArray new_byte_view(JNIEnv *env, struct NuxByteView view) {
  if ((view.data == NULL && view.len != 0) || view.len > INT32_MAX) return NULL;
  jbyteArray value = (*env)->NewByteArray(env, (jsize)view.len);
  if (clear_jni_exception(env) || value == NULL) return NULL;
  if (view.len != 0) {
    (*env)->SetByteArrayRegion(env, value, 0, (jsize)view.len,
                              (const jbyte *)view.data);
    if (clear_jni_exception(env)) {
      (*env)->DeleteLocalRef(env, value);
      return NULL;
    }
  }
  return value;
}

struct host_value_jni_context {
  jclass value_class;
  jclass field_class;
  jmethodID value_constructor;
  jmethodID field_constructor;
};

static jobject copy_host_value(
    JNIEnv *env, const struct NuxPlayerStepResult *result, size_t value_index,
    size_t depth, const struct host_value_jni_context *context,
    NuxStatus *reported_status) {
  if (depth > 64u) return NULL;

  struct NuxHostValueView view;
  memset(&view, 0, sizeof(view));
  view.struct_size = (uint32_t)sizeof(view);
  NuxStatus status =
      nux_player_step_result_host_value(result, value_index, &view);
  if (status != NUX_STATUS_OK) {
    *reported_status = status;
    return NULL;
  }
  if (view.kind > NUX_HOST_VALUE_KIND_OBJECT ||
      view.child_count > INT32_MAX ||
      (view.kind != NUX_HOST_VALUE_KIND_LIST &&
       view.kind != NUX_HOST_VALUE_KIND_OBJECT && view.child_count != 0)) {
    return NULL;
  }

  struct NuxStringView empty_string;
  memset(&empty_string, 0, sizeof(empty_string));
  jstring string_value = new_string_view(
      env, view.kind == NUX_HOST_VALUE_KIND_STRING ? view.string_value
                                                   : empty_string);
  if (string_value == NULL) return NULL;

  jsize list_count = view.kind == NUX_HOST_VALUE_KIND_LIST
                         ? (jsize)view.child_count
                         : 0;
  jsize object_count = view.kind == NUX_HOST_VALUE_KIND_OBJECT
                           ? (jsize)view.child_count
                           : 0;
  jobjectArray list_values =
      (*env)->NewObjectArray(env, list_count, context->value_class, NULL);
  jobjectArray object_values =
      (*env)->NewObjectArray(env, object_count, context->field_class, NULL);
  if (clear_jni_exception(env) || list_values == NULL || object_values == NULL) {
    if (object_values != NULL) (*env)->DeleteLocalRef(env, object_values);
    if (list_values != NULL) (*env)->DeleteLocalRef(env, list_values);
    (*env)->DeleteLocalRef(env, string_value);
    return NULL;
  }

  int failed = 0;
  for (size_t child_index = 0; child_index < view.child_count; child_index++) {
    struct NuxHostValueChildView child;
    memset(&child, 0, sizeof(child));
    child.struct_size = (uint32_t)sizeof(child);
    status = nux_player_step_result_host_value_child(
        result, value_index, child_index, &child);
    if (status != NUX_STATUS_OK) {
      *reported_status = status;
      failed = 1;
      break;
    }
    if (view.kind == NUX_HOST_VALUE_KIND_LIST &&
        (child.key.data != NULL || child.key.len != 0)) {
      failed = 1;
      break;
    }
    jobject child_value = copy_host_value(
        env, result, child.value_index, depth + 1u, context, reported_status);
    if (child_value == NULL) {
      failed = 1;
      break;
    }
    if (view.kind == NUX_HOST_VALUE_KIND_LIST) {
      (*env)->SetObjectArrayElement(env, list_values, (jsize)child_index,
                                    child_value);
      if (clear_jni_exception(env)) failed = 1;
    } else {
      jstring key = new_string_view(env, child.key);
      jobject field = NULL;
      if (key == NULL) {
        failed = 1;
      } else {
        field = (*env)->NewObject(env, context->field_class,
                                  context->field_constructor, key, child_value);
        if (clear_jni_exception(env) || field == NULL) {
          failed = 1;
        } else {
          (*env)->SetObjectArrayElement(env, object_values,
                                        (jsize)child_index, field);
          if (clear_jni_exception(env)) failed = 1;
        }
      }
      if (field != NULL) (*env)->DeleteLocalRef(env, field);
      if (key != NULL) (*env)->DeleteLocalRef(env, key);
    }
    (*env)->DeleteLocalRef(env, child_value);
    if (failed) break;
  }

  jobject value = NULL;
  if (!failed) {
    value = (*env)->NewObject(
        env, context->value_class, context->value_constructor, (jint)view.kind,
        (jboolean)(view.bool_value ? JNI_TRUE : JNI_FALSE),
        (jdouble)view.number_value, string_value, list_values, object_values);
    if (clear_jni_exception(env) || value == NULL) failed = 1;
  }
  if (failed && value != NULL) {
    (*env)->DeleteLocalRef(env, value);
    value = NULL;
  }
  (*env)->DeleteLocalRef(env, object_values);
  (*env)->DeleteLocalRef(env, list_values);
  (*env)->DeleteLocalRef(env, string_value);
  return value;
}

JNIEXPORT jobjectArray JNICALL
Java_ai_nuxie_sdk_runtime_NuxieRuntimeBridge_nativeFileInspectAssets(
    JNIEnv *env, jobject self, jbyteArray bytes) {
  (void)self;
  if (bytes == NULL) return NULL;
  jsize length = (*env)->GetArrayLength(env, bytes);
  if (clear_jni_exception(env)) return NULL;
  jbyte *data = (*env)->GetByteArrayElements(env, bytes, NULL);
  if (clear_jni_exception(env)) {
    if (data != NULL) {
      (*env)->ReleaseByteArrayElements(env, bytes, data, JNI_ABORT);
    }
    return NULL;
  }
  if (data == NULL) return NULL;
  struct NuxFile *file = NULL;
  struct NuxRenderCallbacks callbacks;
  memset(&callbacks, 0, sizeof(callbacks));
  callbacks.struct_size = (uint32_t)sizeof(callbacks);
  NuxStatus status =
      nux_file_import((const uint8_t *)data, (size_t)length, &callbacks, &file);
  (*env)->ReleaseByteArrayElements(env, bytes, data, JNI_ABORT);
  if (status != NUX_STATUS_OK || file == NULL) return NULL;

  size_t count = 0;
  if (nux_file_asset_count(file, &count) != NUX_STATUS_OK || count > INT32_MAX) {
    nux_file_free(file);
    return NULL;
  }
  jclass item_class = (*env)->FindClass(
      env, "ai/nuxie/sdk/runtime/NativeExpectedFileAsset");
  if (clear_jni_exception(env)) {
    if (item_class != NULL) (*env)->DeleteLocalRef(env, item_class);
    nux_file_free(file);
    return NULL;
  }
  if (item_class == NULL) {
    nux_file_free(file);
    return NULL;
  }
  jmethodID constructor = (*env)->GetMethodID(
      env, item_class, "<init>",
      "(IIZJLjava/lang/String;Ljava/lang/String;ZZI)V");
  if (clear_jni_exception(env) || constructor == NULL) {
    (*env)->DeleteLocalRef(env, item_class);
    nux_file_free(file);
    return NULL;
  }
  jobjectArray result =
      (*env)->NewObjectArray(env, (jsize)count, item_class, NULL);
  if (clear_jni_exception(env)) {
    if (result != NULL) (*env)->DeleteLocalRef(env, result);
    (*env)->DeleteLocalRef(env, item_class);
    nux_file_free(file);
    return NULL;
  }
  if (result == NULL) {
    (*env)->DeleteLocalRef(env, item_class);
    nux_file_free(file);
    return NULL;
  }
  int failed = 0;
  for (size_t index = 0; index < count; index++) {
    struct NuxFileAssetDescriptorView view;
    memset(&view, 0, sizeof(view));
    view.struct_size = (uint32_t)sizeof(view);
    if (nux_file_asset_descriptor(file, index, &view) != NUX_STATUS_OK) {
      failed = 1;
      break;
    }
    jstring name = new_string_view(env, view.name);
    if (name == NULL) {
      failed = 1;
      break;
    }
    jstring extension = new_string_view(env, view.file_extension);
    if (extension == NULL) {
      (*env)->DeleteLocalRef(env, name);
      failed = 1;
      break;
    }
    jobject item = (*env)->NewObject(
        env, item_class, constructor, (jint)view.ordinal, (jint)view.kind,
        (jboolean)(view.has_authored_id != 0), (jlong)view.authored_id,
        name, extension, (jboolean)(view.is_embedded != 0),
        (jboolean)(view.has_contents_record != 0),
        (jint)view.required_provider_flags);
    if (clear_jni_exception(env) || item == NULL) {
      failed = 1;
    } else {
      (*env)->SetObjectArrayElement(env, result, (jsize)index, item);
      if (clear_jni_exception(env)) failed = 1;
    }
    if (item != NULL) (*env)->DeleteLocalRef(env, item);
    (*env)->DeleteLocalRef(env, name);
    (*env)->DeleteLocalRef(env, extension);
    if (failed) break;
  }
  nux_file_free(file);
  (*env)->DeleteLocalRef(env, item_class);
  if (failed) {
    (*env)->DeleteLocalRef(env, result);
    return NULL;
  }
  return result;
}

JNIEXPORT jlong JNICALL
Java_ai_nuxie_sdk_runtime_NuxieRuntimeBridge_nativeFileNewConfigured(
    JNIEnv *env, jobject self, jlong renderer, jbyteArray bytes, jintArray ordinal_array,
    jintArray kind_array, jbooleanArray has_id_array, jlongArray id_array,
    jobjectArray name_array, jobjectArray extension_array,
    jbooleanArray embedded_array, jbooleanArray contents_array,
    jintArray flags_array, jintArray external_ordinal_array,
    jobjectArray external_payload_array, jobject decoder) {
  (void)self;
  if (renderer == 0 || bytes == NULL || ordinal_array == NULL || kind_array == NULL ||
      has_id_array == NULL || id_array == NULL || name_array == NULL ||
      extension_array == NULL || embedded_array == NULL ||
      contents_array == NULL || flags_array == NULL ||
      external_ordinal_array == NULL || external_payload_array == NULL ||
      decoder == NULL) {
    return 0;
  }
  jsize count = (*env)->GetArrayLength(env, ordinal_array);
  if (clear_jni_exception(env)) return 0;
  jsize external_count = (*env)->GetArrayLength(env, external_ordinal_array);
  if (clear_jni_exception(env)) return 0;
  jsize kind_count = (*env)->GetArrayLength(env, kind_array);
  if (clear_jni_exception(env)) return 0;
  jsize has_id_count = (*env)->GetArrayLength(env, has_id_array);
  if (clear_jni_exception(env)) return 0;
  jsize id_count = (*env)->GetArrayLength(env, id_array);
  if (clear_jni_exception(env)) return 0;
  jsize name_count = (*env)->GetArrayLength(env, name_array);
  if (clear_jni_exception(env)) return 0;
  jsize extension_count = (*env)->GetArrayLength(env, extension_array);
  if (clear_jni_exception(env)) return 0;
  jsize embedded_count = (*env)->GetArrayLength(env, embedded_array);
  if (clear_jni_exception(env)) return 0;
  jsize contents_count = (*env)->GetArrayLength(env, contents_array);
  if (clear_jni_exception(env)) return 0;
  jsize flags_count = (*env)->GetArrayLength(env, flags_array);
  if (clear_jni_exception(env)) return 0;
  jsize external_payload_count =
      (*env)->GetArrayLength(env, external_payload_array);
  if (clear_jni_exception(env)) return 0;
  if (count <= 0 || count > NUX_FILE_ASSET_CATALOG_HARD_MAX ||
      kind_count != count || has_id_count != count || id_count != count ||
      name_count != count || extension_count != count ||
      embedded_count != count || contents_count != count ||
      flags_count != count || external_payload_count != external_count) {
    return 0;
  }

  jint *ordinals = calloc((size_t)count, sizeof(*ordinals));
  jint *kinds = calloc((size_t)count, sizeof(*kinds));
  jboolean *has_ids = calloc((size_t)count, sizeof(*has_ids));
  jlong *ids = calloc((size_t)count, sizeof(*ids));
  jboolean *embedded = calloc((size_t)count, sizeof(*embedded));
  jboolean *contents = calloc((size_t)count, sizeof(*contents));
  jint *flags = calloc((size_t)count, sizeof(*flags));
  jint *external_ordinals = calloc((size_t)(external_count == 0 ? 1 : external_count),
                                   sizeof(*external_ordinals));
  struct NuxExpectedFileAssetDescriptor *expected =
      calloc((size_t)count, sizeof(*expected));
  char **names = calloc((size_t)count, sizeof(*names));
  char **extensions = calloc((size_t)count, sizeof(*extensions));
  struct asset_payload *payloads = calloc((size_t)count, sizeof(*payloads));
  jbyte *file_bytes = NULL;
  struct NuxFile *file = NULL;
  struct NuxCapiResult *result = NULL;
  NuxStatus status = NUX_STATUS_INVALID_ARGUMENT;
  if (ordinals == NULL || kinds == NULL || has_ids == NULL || ids == NULL ||
      embedded == NULL || contents == NULL || flags == NULL ||
      external_ordinals == NULL || expected == NULL || names == NULL ||
      extensions == NULL || payloads == NULL) {
    goto cleanup_configured_import;
  }
  (*env)->GetIntArrayRegion(env, ordinal_array, 0, count, ordinals);
  if (clear_jni_exception(env)) goto cleanup_configured_import;
  (*env)->GetIntArrayRegion(env, kind_array, 0, count, kinds);
  if (clear_jni_exception(env)) goto cleanup_configured_import;
  (*env)->GetBooleanArrayRegion(env, has_id_array, 0, count, has_ids);
  if (clear_jni_exception(env)) goto cleanup_configured_import;
  (*env)->GetLongArrayRegion(env, id_array, 0, count, ids);
  if (clear_jni_exception(env)) goto cleanup_configured_import;
  (*env)->GetBooleanArrayRegion(env, embedded_array, 0, count, embedded);
  if (clear_jni_exception(env)) goto cleanup_configured_import;
  (*env)->GetBooleanArrayRegion(env, contents_array, 0, count, contents);
  if (clear_jni_exception(env)) goto cleanup_configured_import;
  (*env)->GetIntArrayRegion(env, flags_array, 0, count, flags);
  if (clear_jni_exception(env)) goto cleanup_configured_import;
  if (external_count != 0) {
    (*env)->GetIntArrayRegion(env, external_ordinal_array, 0, external_count,
                             external_ordinals);
    if (clear_jni_exception(env)) goto cleanup_configured_import;
  }

  for (jsize index = 0; index < count; index++) {
    if (ordinals[index] != index || ids[index] < 0 || ids[index] > UINT32_MAX) {
      goto cleanup_configured_import;
    }
    jbyteArray name_bytes = (jbyteArray)(*env)->GetObjectArrayElement(
        env, name_array, index);
    if (clear_jni_exception(env)) {
      if (name_bytes != NULL) (*env)->DeleteLocalRef(env, name_bytes);
      goto cleanup_configured_import;
    }
    jbyteArray extension_bytes = (jbyteArray)(*env)->GetObjectArrayElement(
        env, extension_array, index);
    if (clear_jni_exception(env)) {
      if (name_bytes != NULL) (*env)->DeleteLocalRef(env, name_bytes);
      if (extension_bytes != NULL) (*env)->DeleteLocalRef(env, extension_bytes);
      goto cleanup_configured_import;
    }
    if (name_bytes == NULL || extension_bytes == NULL) {
      if (name_bytes != NULL) (*env)->DeleteLocalRef(env, name_bytes);
      if (extension_bytes != NULL) (*env)->DeleteLocalRef(env, extension_bytes);
      goto cleanup_configured_import;
    }
    jsize name_len = (*env)->GetArrayLength(env, name_bytes);
    if (clear_jni_exception(env)) {
      (*env)->DeleteLocalRef(env, name_bytes);
      (*env)->DeleteLocalRef(env, extension_bytes);
      goto cleanup_configured_import;
    }
    jsize extension_len = (*env)->GetArrayLength(env, extension_bytes);
    if (clear_jni_exception(env)) {
      (*env)->DeleteLocalRef(env, name_bytes);
      (*env)->DeleteLocalRef(env, extension_bytes);
      goto cleanup_configured_import;
    }
    names[index] = malloc(name_len == 0 ? 1 : (size_t)name_len);
    extensions[index] = malloc(extension_len == 0 ? 1 : (size_t)extension_len);
    if (names[index] == NULL || extensions[index] == NULL) {
      (*env)->DeleteLocalRef(env, name_bytes);
      (*env)->DeleteLocalRef(env, extension_bytes);
      goto cleanup_configured_import;
    }
    if (name_len != 0) {
      (*env)->GetByteArrayRegion(env, name_bytes, 0, name_len,
                                 (jbyte *)names[index]);
      if (clear_jni_exception(env)) {
        (*env)->DeleteLocalRef(env, name_bytes);
        (*env)->DeleteLocalRef(env, extension_bytes);
        goto cleanup_configured_import;
      }
    }
    if (extension_len != 0) {
      (*env)->GetByteArrayRegion(env, extension_bytes, 0, extension_len,
                                 (jbyte *)extensions[index]);
      if (clear_jni_exception(env)) {
        (*env)->DeleteLocalRef(env, name_bytes);
        (*env)->DeleteLocalRef(env, extension_bytes);
        goto cleanup_configured_import;
      }
    }
    (*env)->DeleteLocalRef(env, name_bytes);
    (*env)->DeleteLocalRef(env, extension_bytes);
    expected[index].struct_size = (uint32_t)sizeof(expected[index]);
    expected[index].ordinal = (size_t)ordinals[index];
    expected[index].kind = (uint32_t)kinds[index];
    expected[index].has_authored_id = has_ids[index] ? 1u : 0u;
    expected[index].authored_id = (uint32_t)ids[index];
    expected[index].name.data = names[index];
    expected[index].name.len = (size_t)name_len;
    expected[index].file_extension.data = extensions[index];
    expected[index].file_extension.len = (size_t)extension_len;
    expected[index].is_embedded = embedded[index] ? 1u : 0u;
    expected[index].has_contents_record = contents[index] ? 1u : 0u;
    expected[index].required_provider_flags = (uint32_t)flags[index];
  }
  for (jsize index = 0; index < external_count; index++) {
    jint ordinal = external_ordinals[index];
    if (ordinal < 0 || ordinal >= count || payloads[ordinal].present) {
      goto cleanup_configured_import;
    }
    jbyteArray bytes_value = (jbyteArray)(*env)->GetObjectArrayElement(
        env, external_payload_array, index);
    if (clear_jni_exception(env)) {
      if (bytes_value != NULL) (*env)->DeleteLocalRef(env, bytes_value);
      goto cleanup_configured_import;
    }
    if (bytes_value == NULL) goto cleanup_configured_import;
    jsize payload_len = (*env)->GetArrayLength(env, bytes_value);
    if (clear_jni_exception(env)) {
      (*env)->DeleteLocalRef(env, bytes_value);
      goto cleanup_configured_import;
    }
    payloads[ordinal].data = malloc(payload_len == 0 ? 1 : (size_t)payload_len);
    if (payloads[ordinal].data == NULL) {
      (*env)->DeleteLocalRef(env, bytes_value);
      goto cleanup_configured_import;
    }
    if (payload_len != 0) {
      (*env)->GetByteArrayRegion(env, bytes_value, 0, payload_len,
                                 (jbyte *)payloads[ordinal].data);
      if (clear_jni_exception(env)) {
        (*env)->DeleteLocalRef(env, bytes_value);
        goto cleanup_configured_import;
      }
    }
    (*env)->DeleteLocalRef(env, bytes_value);
    payloads[ordinal].len = (size_t)payload_len;
    payloads[ordinal].present = 1;
  }

  struct asset_import_context context = {
      .env = env,
      .decoder = decoder,
      .payloads = payloads,
      .payload_count = (size_t)count,
  };
  struct NuxAssetHooks hooks;
  memset(&hooks, 0, sizeof(hooks));
  hooks.struct_size = (uint32_t)sizeof(hooks);
  hooks.context = &context;
  hooks.lookup_external_asset = lookup_external_asset;
  hooks.decode_image = decode_image;
  hooks.maximum_external_asset_bytes = 32u * 1024u * 1024u;
  hooks.maximum_total_external_asset_bytes = 128u * 1024u * 1024u;
  hooks.maximum_image_dimension = 8192u;
  hooks.maximum_decoded_image_bytes = 256u * 1024u * 1024u;
  hooks.maximum_total_decoded_image_bytes = 512u * 1024u * 1024u;

  struct NuxHostCommandImportConfig host;
  configure_host_commands(&host);

  struct NuxFileImportConfig config;
  memset(&config, 0, sizeof(config));
  config.struct_size = (uint32_t)sizeof(config);
  config.host_commands = &host;
  config.asset_hooks = &hooks;
  config.expected_assets = expected;
  config.expected_asset_count = (size_t)count;

  jsize file_len = (*env)->GetArrayLength(env, bytes);
  if (clear_jni_exception(env)) goto cleanup_configured_import;
  file_bytes = (*env)->GetByteArrayElements(env, bytes, NULL);
  if (clear_jni_exception(env) || file_bytes == NULL) {
    goto cleanup_configured_import;
  }
  status = nux_file_import_android_vulkan_with_trusted_wgsl(
      (struct NuxAndroidVulkanRenderer *)from_handle(renderer),
      (const uint8_t *)file_bytes, (size_t)file_len, &config, &file, &result);
  log_and_free_result("file_import_android_vulkan_with_trusted_wgsl", status,
                      result);
  result = NULL;

cleanup_configured_import:
  if (file_bytes != NULL) {
    (*env)->ReleaseByteArrayElements(env, bytes, file_bytes, JNI_ABORT);
  }
  if (result != NULL) {
    log_and_free_result("file_import_android_vulkan_with_trusted_wgsl", status,
                        result);
  }
  if (status != NUX_STATUS_OK && file != NULL) {
    nux_file_free(file);
    file = NULL;
  }
  clear_jni_exception(env);
  if (payloads != NULL) {
    for (jsize index = 0; index < count; index++) free(payloads[index].data);
  }
  if (names != NULL) {
    for (jsize index = 0; index < count; index++) {
      free(names[index]);
    }
  }
  if (extensions != NULL) {
    for (jsize index = 0; index < count; index++) {
      free(extensions[index]);
    }
  }
  free(ordinals);
  free(kinds);
  free(has_ids);
  free(ids);
  free(embedded);
  free(contents);
  free(flags);
  free(external_ordinals);
  free(expected);
  free(names);
  free(extensions);
  free(payloads);
  return status == NUX_STATUS_OK ? as_handle(file) : 0;
}

JNIEXPORT jlong JNICALL
Java_ai_nuxie_sdk_runtime_NuxieRuntimeBridge_nativeFileNew(
    JNIEnv *env, jobject self, jlong renderer, jbyteArray bytes) {
  (void)self;
  if (renderer == 0 || bytes == NULL) return 0;
  jsize length = (*env)->GetArrayLength(env, bytes);
  jbyte *data = (*env)->GetByteArrayElements(env, bytes, NULL);
  if (data == NULL) return 0;
  struct NuxFile *file = NULL;
  struct NuxCapiResult *result = NULL;
  struct NuxFileImportConfig config;
  memset(&config, 0, sizeof(config));
  config.struct_size = (uint32_t)sizeof(config);
  NuxStatus status = nux_file_import_android_vulkan(
      (struct NuxAndroidVulkanRenderer *)from_handle(renderer),
      (const uint8_t *)data, (size_t)length, &config, &file, &result);
  (*env)->ReleaseByteArrayElements(env, bytes, data, JNI_ABORT);
  log_and_free_result("file_import_android_vulkan", status, result);
  return status == NUX_STATUS_OK ? as_handle(file) : 0;
}

JNIEXPORT void JNICALL
Java_ai_nuxie_sdk_runtime_NuxieRuntimeBridge_nativeFileFree(
    JNIEnv *env, jobject self, jlong file) {
  (void)env;
  (void)self;
  if (file != 0) nux_file_free((struct NuxFile *)from_handle(file));
}

JNIEXPORT jobject JNICALL
Java_ai_nuxie_sdk_runtime_NuxieRuntimeBridge_nativeFileViewModelCatalog(
    JNIEnv *env, jobject self, jlong file, jintArray status_out) {
  (void)self;
  if (status_out == NULL) return NULL;
  if (file == 0) {
    set_status_out(env, status_out, NUX_STATUS_NULL_ARGUMENT);
    return NULL;
  }
  struct NuxViewModelCatalog *catalog = NULL;
  jclass schema_class = NULL;
  jclass property_class = NULL;
  jclass authored_class = NULL;
  jclass catalog_class = NULL;
  jclass string_class = NULL;
  jobjectArray schemas = NULL;
  jobjectArray properties = NULL;
  jobjectArray authored = NULL;
  jobject result = NULL;
  int failed = 0;

  NuxStatus status = nux_file_view_model_catalog(
      (const struct NuxFile *)from_handle(file), &catalog);
  if (status != NUX_STATUS_OK || catalog == NULL) {
    if (status == NUX_STATUS_OK) status = NUX_STATUS_RUNTIME_ERROR;
    set_status_out(env, status_out, status);
    return NULL;
  }

  struct NuxViewModelCatalogInfo info;
  memset(&info, 0, sizeof(info));
  info.struct_size = (uint32_t)sizeof(info);
  status = nux_view_model_catalog_info(catalog, &info);
  if (status != NUX_STATUS_OK || info.schema_count > INT32_MAX ||
      info.property_count > INT32_MAX || info.authored_instance_count > INT32_MAX ||
      info.enum_label_count > INT32_MAX) {
    failed = 1;
    goto view_model_catalog_cleanup;
  }

  schema_class = (*env)->FindClass(
      env, "ai/nuxie/sdk/runtime/NativeViewModelSchema");
  if (clear_jni_exception(env) || schema_class == NULL) {
    failed = 1;
    goto view_model_catalog_cleanup;
  }
  property_class = (*env)->FindClass(
      env, "ai/nuxie/sdk/runtime/NativeViewModelProperty");
  if (clear_jni_exception(env) || property_class == NULL) {
    failed = 1;
    goto view_model_catalog_cleanup;
  }
  authored_class = (*env)->FindClass(
      env, "ai/nuxie/sdk/runtime/NativeViewModelAuthoredInstance");
  if (clear_jni_exception(env) || authored_class == NULL) {
    failed = 1;
    goto view_model_catalog_cleanup;
  }
  catalog_class = (*env)->FindClass(
      env, "ai/nuxie/sdk/runtime/NativeViewModelCatalog");
  if (clear_jni_exception(env) || catalog_class == NULL) {
    failed = 1;
    goto view_model_catalog_cleanup;
  }
  string_class = (*env)->FindClass(env, "java/lang/String");
  if (clear_jni_exception(env) || string_class == NULL) {
    failed = 1;
    goto view_model_catalog_cleanup;
  }

  jmethodID schema_constructor = (*env)->GetMethodID(
      env, schema_class, "<init>", "(JLjava/lang/String;JJJJJZ)V");
  if (clear_jni_exception(env) || schema_constructor == NULL) {
    failed = 1;
    goto view_model_catalog_cleanup;
  }
  jmethodID property_constructor = (*env)->GetMethodID(
      env, property_class, "<init>",
      "(JJLjava/lang/String;IJ[Ljava/lang/String;)V");
  if (clear_jni_exception(env) || property_constructor == NULL) {
    failed = 1;
    goto view_model_catalog_cleanup;
  }
  jmethodID authored_constructor = (*env)->GetMethodID(
      env, authored_class, "<init>", "(JJLjava/lang/String;)V");
  if (clear_jni_exception(env) || authored_constructor == NULL) {
    failed = 1;
    goto view_model_catalog_cleanup;
  }
  jmethodID catalog_constructor = (*env)->GetMethodID(
      env, catalog_class, "<init>",
      "([Lai/nuxie/sdk/runtime/NativeViewModelSchema;"
      "[Lai/nuxie/sdk/runtime/NativeViewModelProperty;"
      "[Lai/nuxie/sdk/runtime/NativeViewModelAuthoredInstance;)V");
  if (clear_jni_exception(env) || catalog_constructor == NULL) {
    failed = 1;
    goto view_model_catalog_cleanup;
  }

  schemas = (*env)->NewObjectArray(env, (jsize)info.schema_count,
                                   schema_class, NULL);
  if (clear_jni_exception(env) || schemas == NULL) {
    failed = 1;
    goto view_model_catalog_cleanup;
  }
  properties = (*env)->NewObjectArray(env, (jsize)info.property_count,
                                      property_class, NULL);
  if (clear_jni_exception(env) || properties == NULL) {
    failed = 1;
    goto view_model_catalog_cleanup;
  }
  authored = (*env)->NewObjectArray(env, (jsize)info.authored_instance_count,
                                    authored_class, NULL);
  if (clear_jni_exception(env) || authored == NULL) {
    failed = 1;
    goto view_model_catalog_cleanup;
  }

  for (size_t index = 0; index < info.schema_count; index++) {
    struct NuxViewModelSchemaView view;
    memset(&view, 0, sizeof(view));
    view.struct_size = (uint32_t)sizeof(view);
    NuxStatus accessor_status =
        nux_view_model_catalog_schema(catalog, index, &view);
    if (accessor_status != NUX_STATUS_OK) {
      status = accessor_status;
      failed = 1;
      break;
    }
    if (view.schema_index > INT64_MAX || view.first_property > INT64_MAX ||
        view.property_count > INT64_MAX ||
        view.first_authored_instance > INT64_MAX ||
        view.authored_instance_count > INT64_MAX ||
        (view.default_authored_instance != SIZE_MAX &&
         view.default_authored_instance > INT64_MAX)) {
      failed = 1;
      break;
    }
    jstring name = new_string_view(env, view.name);
    if (name == NULL) {
      failed = 1;
      break;
    }
    jlong default_instance = view.default_authored_instance == SIZE_MAX
                                 ? (jlong)-1
                                 : (jlong)view.default_authored_instance;
    jobject item = (*env)->NewObject(
        env, schema_class, schema_constructor, (jlong)view.schema_index, name,
        (jlong)view.first_property, (jlong)view.property_count,
        (jlong)view.first_authored_instance, (jlong)view.authored_instance_count,
        default_instance, (jboolean)(view.is_global != 0));
    if (clear_jni_exception(env) || item == NULL) {
      failed = 1;
    } else {
      (*env)->SetObjectArrayElement(env, schemas, (jsize)index, item);
      if (clear_jni_exception(env)) failed = 1;
    }
    if (item != NULL) (*env)->DeleteLocalRef(env, item);
    (*env)->DeleteLocalRef(env, name);
    if (failed) break;
  }

  for (size_t index = 0; !failed && index < info.property_count; index++) {
    struct NuxViewModelPropertyView view;
    memset(&view, 0, sizeof(view));
    view.struct_size = (uint32_t)sizeof(view);
    NuxStatus accessor_status =
        nux_view_model_catalog_property(catalog, index, &view);
    if (accessor_status != NUX_STATUS_OK) {
      status = accessor_status;
      failed = 1;
      break;
    }
    if (view.schema_index > INT64_MAX || view.property_index > INT64_MAX ||
        (view.referenced_schema_index != SIZE_MAX &&
         view.referenced_schema_index > INT64_MAX) ||
        view.enum_label_count > INT32_MAX ||
        view.first_enum_label > SIZE_MAX - view.enum_label_count) {
      failed = 1;
      break;
    }
    jstring name = new_string_view(env, view.name);
    if (name == NULL) {
      failed = 1;
      break;
    }
    jobjectArray labels = (*env)->NewObjectArray(
        env, (jsize)view.enum_label_count, string_class, NULL);
    if (clear_jni_exception(env) || labels == NULL) {
      if (labels != NULL) (*env)->DeleteLocalRef(env, labels);
      (*env)->DeleteLocalRef(env, name);
      failed = 1;
      break;
    }
    for (size_t offset = 0; offset < view.enum_label_count; offset++) {
      struct NuxStringView label_view;
      memset(&label_view, 0, sizeof(label_view));
      accessor_status = nux_view_model_catalog_enum_label(
          catalog, view.first_enum_label + offset, &label_view);
      if (accessor_status != NUX_STATUS_OK) {
        status = accessor_status;
        failed = 1;
        break;
      }
      jstring label = new_string_view(env, label_view);
      if (label == NULL) {
        failed = 1;
        break;
      }
      (*env)->SetObjectArrayElement(env, labels, (jsize)offset, label);
      if (clear_jni_exception(env)) failed = 1;
      (*env)->DeleteLocalRef(env, label);
      if (failed) break;
    }
    jobject item = NULL;
    if (!failed) {
      jlong referenced = view.referenced_schema_index == SIZE_MAX
                             ? (jlong)-1
                             : (jlong)view.referenced_schema_index;
      item = (*env)->NewObject(
          env, property_class, property_constructor, (jlong)view.schema_index,
          (jlong)view.property_index, name, (jint)view.kind, referenced, labels);
      if (clear_jni_exception(env) || item == NULL) {
        failed = 1;
      } else {
        (*env)->SetObjectArrayElement(env, properties, (jsize)index, item);
        if (clear_jni_exception(env)) failed = 1;
      }
    }
    if (item != NULL) (*env)->DeleteLocalRef(env, item);
    (*env)->DeleteLocalRef(env, labels);
    (*env)->DeleteLocalRef(env, name);
  }

  for (size_t index = 0; !failed && index < info.authored_instance_count;
       index++) {
    struct NuxViewModelAuthoredInstanceView view;
    memset(&view, 0, sizeof(view));
    view.struct_size = (uint32_t)sizeof(view);
    NuxStatus accessor_status =
        nux_view_model_catalog_authored_instance(catalog, index, &view);
    if (accessor_status != NUX_STATUS_OK) {
      status = accessor_status;
      failed = 1;
      break;
    }
    if (view.schema_index > INT64_MAX || view.instance_index > INT64_MAX ||
        (view.name.data == NULL && view.name.len != 0)) {
      failed = 1;
      break;
    }
    jstring name = NULL;
    if (view.name.data != NULL || view.name.len != 0) {
      name = new_string_view(env, view.name);
      if (name == NULL) {
        failed = 1;
        break;
      }
    }
    jobject item = (*env)->NewObject(
        env, authored_class, authored_constructor, (jlong)view.schema_index,
        (jlong)view.instance_index, name);
    if (clear_jni_exception(env) || item == NULL) {
      failed = 1;
    } else {
      (*env)->SetObjectArrayElement(env, authored, (jsize)index, item);
      if (clear_jni_exception(env)) failed = 1;
    }
    if (item != NULL) (*env)->DeleteLocalRef(env, item);
    if (name != NULL) (*env)->DeleteLocalRef(env, name);
  }

  if (!failed) {
    result = (*env)->NewObject(env, catalog_class, catalog_constructor,
                               schemas, properties, authored);
    if (clear_jni_exception(env) || result == NULL) failed = 1;
  }

view_model_catalog_cleanup:
  if (catalog != NULL) {
    NuxStatus free_status = nux_view_model_catalog_free(catalog);
    log_cleanup_failure("view_model_catalog_free", free_status);
  }
  if (authored != NULL) (*env)->DeleteLocalRef(env, authored);
  if (properties != NULL) (*env)->DeleteLocalRef(env, properties);
  if (schemas != NULL) (*env)->DeleteLocalRef(env, schemas);
  if (string_class != NULL) (*env)->DeleteLocalRef(env, string_class);
  if (catalog_class != NULL) (*env)->DeleteLocalRef(env, catalog_class);
  if (authored_class != NULL) (*env)->DeleteLocalRef(env, authored_class);
  if (property_class != NULL) (*env)->DeleteLocalRef(env, property_class);
  if (schema_class != NULL) (*env)->DeleteLocalRef(env, schema_class);
  if (failed && result != NULL) {
    (*env)->DeleteLocalRef(env, result);
    result = NULL;
  }
  clear_jni_exception(env);
  if (failed && status == NUX_STATUS_OK) status = NUX_STATUS_RUNTIME_ERROR;
  if (!set_status_out(env, status_out, status)) {
    if (result != NULL) (*env)->DeleteLocalRef(env, result);
    return NULL;
  }
  return result;
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
Java_ai_nuxie_sdk_runtime_NuxieRuntimeBridge_nativeViewModelInstanceNew(
    JNIEnv *env, jobject self, jlong file, jint schema_index,
    jint authored_instance_index, jintArray status_out) {
  (void)self;
  if (status_out == NULL) return 0;
  if (file == 0) {
    set_status_out(env, status_out, NUX_STATUS_NULL_ARGUMENT);
    return 0;
  }
  if (schema_index < 0 || authored_instance_index < -1) {
    set_status_out(env, status_out, NUX_STATUS_INVALID_ARGUMENT);
    return 0;
  }
  struct NuxViewModelInstance *instance = NULL;
  NuxStatus status = authored_instance_index < 0
      ? nux_view_model_instance_new_schema_default(
            (const struct NuxFile *)from_handle(file), (size_t)schema_index,
            &instance)
      : nux_view_model_instance_new_authored(
            (const struct NuxFile *)from_handle(file), (size_t)schema_index,
            (size_t)authored_instance_index, &instance);
  if (status == NUX_STATUS_OK && instance == NULL) status = NUX_STATUS_RUNTIME_ERROR;
  if (!set_status_out(env, status_out, status)) {
    if (instance != NULL) nux_view_model_instance_free(instance);
    return 0;
  }
  return status == NUX_STATUS_OK ? as_handle(instance) : 0;
}

JNIEXPORT jlong JNICALL
Java_ai_nuxie_sdk_runtime_NuxieRuntimeBridge_nativeViewModelInstanceNewDefault(
    JNIEnv *env, jobject self, jlong artboard, jintArray status_out) {
  (void)self;
  if (status_out == NULL) return 0;
  if (artboard == 0) {
    set_status_out(env, status_out, NUX_STATUS_NULL_ARGUMENT);
    return 0;
  }
  struct NuxViewModelInstance *instance = NULL;
  NuxStatus status = nux_view_model_instance_new_default(
      (const struct NuxArtboardInstance *)from_handle(artboard), &instance);
  if (status == NUX_STATUS_OK && instance == NULL) status = NUX_STATUS_RUNTIME_ERROR;
  if (!set_status_out(env, status_out, status)) {
    if (instance != NULL) nux_view_model_instance_free(instance);
    return 0;
  }
  return status == NUX_STATUS_OK ? as_handle(instance) : 0;
}

JNIEXPORT jlong JNICALL
Java_ai_nuxie_sdk_runtime_NuxieRuntimeBridge_nativeViewModelRootSchemaIndex(
    JNIEnv *env, jobject self, jlong view_model, jintArray status_out) {
  (void)self;
  if (status_out == NULL) return 0;
  if (view_model == 0) {
    set_status_out(env, status_out, NUX_STATUS_NULL_ARGUMENT);
    return 0;
  }
  struct NuxViewModelSnapshot *snapshot = NULL;
  NuxStatus status = nux_view_model_instance_snapshot(
      (const struct NuxViewModelInstance *)from_handle(view_model), &snapshot);
  jlong schema_index = 0;
  if (status == NUX_STATUS_OK && snapshot == NULL) status = NUX_STATUS_RUNTIME_ERROR;
  if (status == NUX_STATUS_OK) {
    struct NuxViewModelSnapshotInfo info;
    memset(&info, 0, sizeof(info));
    info.struct_size = (uint32_t)sizeof(info);
    status = nux_view_model_snapshot_info(snapshot, &info);
    int found_root = 0;
    for (size_t index = 0; status == NUX_STATUS_OK && index < info.instance_count; ++index) {
      struct NuxViewModelSnapshotInstanceView instance;
      memset(&instance, 0, sizeof(instance));
      instance.struct_size = (uint32_t)sizeof(instance);
      status = nux_view_model_snapshot_instance(snapshot, index, &instance);
      if (status == NUX_STATUS_OK && instance.instance_id == info.root_instance_id) {
        if (instance.schema_index > INT64_MAX || found_root) {
          status = NUX_STATUS_RUNTIME_ERROR;
        } else {
          schema_index = (jlong)instance.schema_index;
          found_root = 1;
        }
      }
    }
    if (status == NUX_STATUS_OK && !found_root) status = NUX_STATUS_RUNTIME_ERROR;
  }
  if (snapshot != NULL) {
    NuxStatus free_status = nux_view_model_snapshot_free(snapshot);
    if (status == NUX_STATUS_OK) status = free_status;
  }
  if (!set_status_out(env, status_out, status)) return 0;
  return status == NUX_STATUS_OK ? schema_index : 0;
}

JNIEXPORT jobject JNICALL
Java_ai_nuxie_sdk_runtime_NuxieRuntimeBridge_nativeViewModelInstanceSnapshot(
    JNIEnv *env, jobject self, jlong view_model, jintArray status_out) {
  (void)self;
  if (status_out == NULL) return NULL;
  if (view_model == 0) {
    set_status_out(env, status_out, NUX_STATUS_NULL_ARGUMENT);
    return NULL;
  }

  struct NuxViewModelSnapshot *snapshot = NULL;
  jclass instance_class = NULL;
  jclass value_class = NULL;
  jclass snapshot_class = NULL;
  jobjectArray instances = NULL;
  jobjectArray values = NULL;
  jobject result = NULL;
  NuxStatus status = nux_view_model_instance_snapshot(
      (const struct NuxViewModelInstance *)from_handle(view_model), &snapshot);
  if (status == NUX_STATUS_OK && snapshot == NULL) status = NUX_STATUS_RUNTIME_ERROR;
  if (status != NUX_STATUS_OK) goto view_model_snapshot_cleanup;

  struct NuxViewModelSnapshotInfo info;
  memset(&info, 0, sizeof(info));
  info.struct_size = (uint32_t)sizeof(info);
  status = nux_view_model_snapshot_info(snapshot, &info);
  if (status != NUX_STATUS_OK || info.instance_count > INT32_MAX ||
      info.value_count > INT32_MAX || info.list_item_count > INT32_MAX) {
    if (status == NUX_STATUS_OK) status = NUX_STATUS_RUNTIME_ERROR;
    goto view_model_snapshot_cleanup;
  }

  instance_class = (*env)->FindClass(
      env, "ai/nuxie/sdk/runtime/NativeViewModelSnapshotInstance");
  if (clear_jni_exception(env) || instance_class == NULL) {
    status = NUX_STATUS_RUNTIME_ERROR;
    goto view_model_snapshot_cleanup;
  }
  value_class = (*env)->FindClass(
      env, "ai/nuxie/sdk/runtime/NativeViewModelSnapshotValue");
  if (clear_jni_exception(env) || value_class == NULL) {
    status = NUX_STATUS_RUNTIME_ERROR;
    goto view_model_snapshot_cleanup;
  }
  snapshot_class = (*env)->FindClass(
      env, "ai/nuxie/sdk/runtime/NativeViewModelSnapshot");
  if (clear_jni_exception(env) || snapshot_class == NULL) {
    status = NUX_STATUS_RUNTIME_ERROR;
    goto view_model_snapshot_cleanup;
  }

  jmethodID instance_constructor = (*env)->GetMethodID(
      env, instance_class, "<init>", "(JJ)V");
  if (clear_jni_exception(env) || instance_constructor == NULL) {
    status = NUX_STATUS_RUNTIME_ERROR;
    goto view_model_snapshot_cleanup;
  }
  jmethodID value_constructor = (*env)->GetMethodID(
      env, value_class, "<init>", "(JJLjava/lang/String;I[BJ)V");
  if (clear_jni_exception(env) || value_constructor == NULL) {
    status = NUX_STATUS_RUNTIME_ERROR;
    goto view_model_snapshot_cleanup;
  }
  jmethodID snapshot_constructor = (*env)->GetMethodID(
      env, snapshot_class, "<init>",
      "(J[Lai/nuxie/sdk/runtime/NativeViewModelSnapshotInstance;"
      "[Lai/nuxie/sdk/runtime/NativeViewModelSnapshotValue;)V");
  if (clear_jni_exception(env) || snapshot_constructor == NULL) {
    status = NUX_STATUS_RUNTIME_ERROR;
    goto view_model_snapshot_cleanup;
  }

  instances = (*env)->NewObjectArray(
      env, (jsize)info.instance_count, instance_class, NULL);
  if (clear_jni_exception(env) || instances == NULL) {
    status = NUX_STATUS_RUNTIME_ERROR;
    goto view_model_snapshot_cleanup;
  }
  values = (*env)->NewObjectArray(
      env, (jsize)info.value_count, value_class, NULL);
  if (clear_jni_exception(env) || values == NULL) {
    status = NUX_STATUS_RUNTIME_ERROR;
    goto view_model_snapshot_cleanup;
  }

  for (size_t index = 0; index < info.instance_count; ++index) {
    struct NuxViewModelSnapshotInstanceView view;
    memset(&view, 0, sizeof(view));
    view.struct_size = (uint32_t)sizeof(view);
    status = nux_view_model_snapshot_instance(snapshot, index, &view);
    if (status != NUX_STATUS_OK || view.schema_index > INT64_MAX) {
      if (status == NUX_STATUS_OK) status = NUX_STATUS_RUNTIME_ERROR;
      goto view_model_snapshot_cleanup;
    }
    jobject instance = (*env)->NewObject(
        env, instance_class, instance_constructor, (jlong)view.instance_id,
        (jlong)view.schema_index);
    if (clear_jni_exception(env) || instance == NULL) {
      if (instance != NULL) (*env)->DeleteLocalRef(env, instance);
      status = NUX_STATUS_RUNTIME_ERROR;
      goto view_model_snapshot_cleanup;
    }
    (*env)->SetObjectArrayElement(env, instances, (jsize)index, instance);
    (*env)->DeleteLocalRef(env, instance);
    if (clear_jni_exception(env)) {
      status = NUX_STATUS_RUNTIME_ERROR;
      goto view_model_snapshot_cleanup;
    }
  }

  for (size_t index = 0; index < info.value_count; ++index) {
    struct NuxViewModelSnapshotValueView view;
    memset(&view, 0, sizeof(view));
    view.struct_size = (uint32_t)sizeof(view);
    status = nux_view_model_snapshot_value(snapshot, index, &view);
    if (status != NUX_STATUS_OK || view.property_index > INT64_MAX) {
      if (status == NUX_STATUS_OK) status = NUX_STATUS_RUNTIME_ERROR;
      goto view_model_snapshot_cleanup;
    }
    jstring name = new_string_view(env, view.name);
    if (name == NULL) {
      status = NUX_STATUS_RUNTIME_ERROR;
      goto view_model_snapshot_cleanup;
    }
    jbyteArray bytes = new_byte_view(env, view.bytes_value);
    if (bytes == NULL) {
      (*env)->DeleteLocalRef(env, name);
      status = NUX_STATUS_RUNTIME_ERROR;
      goto view_model_snapshot_cleanup;
    }
    jobject value = (*env)->NewObject(
        env, value_class, value_constructor, (jlong)view.owner_instance_id,
        (jlong)view.property_index, name, (jint)view.kind, bytes,
        (jlong)view.referenced_instance_id);
    if (clear_jni_exception(env) || value == NULL) {
      if (value != NULL) (*env)->DeleteLocalRef(env, value);
      (*env)->DeleteLocalRef(env, bytes);
      (*env)->DeleteLocalRef(env, name);
      status = NUX_STATUS_RUNTIME_ERROR;
      goto view_model_snapshot_cleanup;
    }
    (*env)->SetObjectArrayElement(env, values, (jsize)index, value);
    (*env)->DeleteLocalRef(env, value);
    (*env)->DeleteLocalRef(env, bytes);
    (*env)->DeleteLocalRef(env, name);
    if (clear_jni_exception(env)) {
      status = NUX_STATUS_RUNTIME_ERROR;
      goto view_model_snapshot_cleanup;
    }
  }

  result = (*env)->NewObject(
      env, snapshot_class, snapshot_constructor, (jlong)info.root_instance_id,
      instances, values);
  if (clear_jni_exception(env) || result == NULL) {
    if (result != NULL) (*env)->DeleteLocalRef(env, result);
    result = NULL;
    status = NUX_STATUS_RUNTIME_ERROR;
  }

view_model_snapshot_cleanup:
  if (values != NULL) (*env)->DeleteLocalRef(env, values);
  if (instances != NULL) (*env)->DeleteLocalRef(env, instances);
  if (snapshot_class != NULL) (*env)->DeleteLocalRef(env, snapshot_class);
  if (value_class != NULL) (*env)->DeleteLocalRef(env, value_class);
  if (instance_class != NULL) (*env)->DeleteLocalRef(env, instance_class);
  if (snapshot != NULL) {
    NuxStatus free_status = nux_view_model_snapshot_free(snapshot);
    if (status == NUX_STATUS_OK) status = free_status;
  }
  if (status != NUX_STATUS_OK && result != NULL) {
    (*env)->DeleteLocalRef(env, result);
    result = NULL;
  }
  if (!set_status_out(env, status_out, status)) {
    if (result != NULL) (*env)->DeleteLocalRef(env, result);
    return NULL;
  }
  return status == NUX_STATUS_OK ? result : NULL;
}

JNIEXPORT jint JNICALL
Java_ai_nuxie_sdk_runtime_NuxieRuntimeBridge_nativeArtboardInstanceBindViewModel(
    JNIEnv *env, jobject self, jlong artboard, jlong view_model) {
  (void)env;
  (void)self;
  if (artboard == 0 || view_model == 0) return (jint)NUX_STATUS_NULL_ARGUMENT;
  return (jint)nux_artboard_instance_bind_view_model(
      (struct NuxArtboardInstance *)from_handle(artboard),
      (const struct NuxViewModelInstance *)from_handle(view_model));
}

JNIEXPORT jint JNICALL
Java_ai_nuxie_sdk_runtime_NuxieRuntimeBridge_nativeViewModelMutate(
    JNIEnv *env, jobject self, jlong view_model, jint kind, jbyteArray path,
    jbyteArray bytes_value, jfloat number_value, jlong integer_value,
    jboolean bool_value, jlong related_view_model, jlong index) {
  (void)self;
  if (view_model == 0 || path == NULL || bytes_value == NULL) {
    return (jint)NUX_STATUS_NULL_ARGUMENT;
  }
  if (!((kind >= NUX_VIEW_MODEL_MUTATION_KIND_SET_STRING &&
         kind <= NUX_VIEW_MODEL_MUTATION_KIND_FIRE_TRIGGER) ||
        kind == NUX_VIEW_MODEL_MUTATION_KIND_SET_VIEW_MODEL ||
        kind == NUX_VIEW_MODEL_MUTATION_KIND_LIST_SET)) {
    return (jint)NUX_STATUS_INVALID_ARGUMENT;
  }
  if ((kind == NUX_VIEW_MODEL_MUTATION_KIND_SET_VIEW_MODEL ||
       kind == NUX_VIEW_MODEL_MUTATION_KIND_LIST_SET) &&
      related_view_model == 0) {
    return (jint)NUX_STATUS_NULL_ARGUMENT;
  }
  if (index < 0) return (jint)NUX_STATUS_INVALID_ARGUMENT;
  jsize path_len = (*env)->GetArrayLength(env, path);
  if (clear_jni_exception(env)) return (jint)NUX_STATUS_RUNTIME_ERROR;
  jsize bytes_len = (*env)->GetArrayLength(env, bytes_value);
  if (clear_jni_exception(env)) return (jint)NUX_STATUS_RUNTIME_ERROR;
  jbyte *path_data = (*env)->GetByteArrayElements(env, path, NULL);
  if (clear_jni_exception(env) || path_data == NULL) {
    if (path_data != NULL) {
      (*env)->ReleaseByteArrayElements(env, path, path_data, JNI_ABORT);
    }
    return (jint)NUX_STATUS_RUNTIME_ERROR;
  }
  jbyte *bytes_data = (*env)->GetByteArrayElements(env, bytes_value, NULL);
  if (clear_jni_exception(env) || bytes_data == NULL) {
    if (bytes_data != NULL) {
      (*env)->ReleaseByteArrayElements(env, bytes_value, bytes_data, JNI_ABORT);
    }
    (*env)->ReleaseByteArrayElements(env, path, path_data, JNI_ABORT);
    return (jint)NUX_STATUS_RUNTIME_ERROR;
  }

  struct NuxViewModelMutation mutation;
  memset(&mutation, 0, sizeof(mutation));
  mutation.kind = (uint32_t)kind;
  mutation.instance = (struct NuxViewModelInstance *)from_handle(view_model);
  mutation.path.data = (const char *)path_data;
  mutation.path.len = (size_t)path_len;
  mutation.bytes_value.data = (const uint8_t *)bytes_data;
  mutation.bytes_value.len = (size_t)bytes_len;
  mutation.number_value = number_value;
  mutation.integer_value = (uint64_t)integer_value;
  mutation.bool_value = bool_value == JNI_TRUE ? 1u : 0u;
  mutation.related_instance =
      (struct NuxViewModelInstance *)from_handle(related_view_model);
  mutation.index = (size_t)index;
  struct NuxViewModelMutationBatch batch;
  memset(&batch, 0, sizeof(batch));
  batch.struct_size = (uint32_t)sizeof(batch);
  batch.mutations = &mutation;
  batch.mutation_count = 1;
  struct NuxViewModelMutationResult *result = NULL;
  NuxStatus status = nux_view_model_mutate(&batch, &result);
  (*env)->ReleaseByteArrayElements(env, bytes_value, bytes_data, JNI_ABORT);
  (*env)->ReleaseByteArrayElements(env, path, path_data, JNI_ABORT);

  if (result != NULL) {
    struct NuxViewModelMutationResultInfo info;
    memset(&info, 0, sizeof(info));
    info.struct_size = (uint32_t)sizeof(info);
    NuxStatus info_status = nux_view_model_mutation_result_info(result, &info);
    if (info_status != NUX_STATUS_OK || info.status != status) {
      status = info_status == NUX_STATUS_OK ? NUX_STATUS_RUNTIME_ERROR : info_status;
    } else if (status != NUX_STATUS_OK) {
      NUXIE_LOG_WARN(
          "view_model_mutate failed: status=%d code=%.*s message=%.*s",
          (int)status, (int)info.code.len,
          info.code.data != NULL ? info.code.data : "", (int)info.message.len,
          info.message.data != NULL ? info.message.data : "");
    }
    NuxStatus free_status = nux_view_model_mutation_result_free(result);
    log_cleanup_failure("view_model_mutation_result_free", free_status);
  } else if (status == NUX_STATUS_OK) {
    status = NUX_STATUS_RUNTIME_ERROR;
  }
  return (jint)status;
}

JNIEXPORT jint JNICALL
Java_ai_nuxie_sdk_runtime_NuxieRuntimeBridge_nativeViewModelInstanceFree(
    JNIEnv *env, jobject self, jlong view_model) {
  (void)env;
  (void)self;
  if (view_model == 0) return (jint)NUX_STATUS_NULL_ARGUMENT;
  return (jint)nux_view_model_instance_free(
      (struct NuxViewModelInstance *)from_handle(view_model));
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

JNIEXPORT jobject JNICALL
Java_ai_nuxie_sdk_runtime_NuxieRuntimeBridge_nativePlayerStepTyped(
    JNIEnv *env, jobject self, jlong player, jintArray input_kind_array,
    jobjectArray input_name_array, jbooleanArray input_bool_array,
    jfloatArray input_number_array, jintArray pointer_kind_array,
    jfloatArray pointer_x_array, jfloatArray pointer_y_array,
    jintArray pointer_id_array, jfloatArray pointer_timestamp_array,
    jfloat elapsed_seconds, jlong correlation_id, jintArray status_out) {
  (void)self;
  NuxStatus reported_status = NUX_STATUS_RUNTIME_ERROR;
  if (status_out == NULL) return NULL;
  if (player == 0 || input_kind_array == NULL || input_name_array == NULL ||
      input_bool_array == NULL || input_number_array == NULL ||
      pointer_kind_array == NULL || pointer_x_array == NULL ||
      pointer_y_array == NULL || pointer_id_array == NULL ||
      pointer_timestamp_array == NULL) {
    set_status_out(env, status_out, NUX_STATUS_NULL_ARGUMENT);
    return NULL;
  }

  jint *kinds = NULL;
  jboolean *bool_values = NULL;
  jfloat *number_values = NULL;
  jint *pointer_kinds = NULL;
  jfloat *pointer_xs = NULL;
  jfloat *pointer_ys = NULL;
  jint *pointer_ids = NULL;
  jfloat *pointer_timestamps = NULL;
  char **names = NULL;
  struct NuxPlayerInputChange *inputs = NULL;
  struct NuxPlayerPointerEvent *pointers = NULL;
  struct NuxPlayerStepResult *step_result = NULL;
  jclass property_class = NULL;
  jclass event_class = NULL;
  jclass host_command_class = NULL;
  jclass host_value_class = NULL;
  jclass host_field_class = NULL;
  jclass change_class = NULL;
  jclass outcome_class = NULL;
  jintArray pointer_hits = NULL;
  jobjectArray events = NULL;
  jobjectArray host_commands = NULL;
  jobjectArray changes = NULL;
  jobject outcome = NULL;
  int failed = 0;

  jsize count = (*env)->GetArrayLength(env, input_kind_array);
  if (clear_jni_exception(env)) {
    set_status_out(env, status_out, NUX_STATUS_RUNTIME_ERROR);
    return NULL;
  }
  jsize name_count = (*env)->GetArrayLength(env, input_name_array);
  if (clear_jni_exception(env)) {
    set_status_out(env, status_out, NUX_STATUS_RUNTIME_ERROR);
    return NULL;
  }
  jsize bool_count = (*env)->GetArrayLength(env, input_bool_array);
  if (clear_jni_exception(env)) {
    set_status_out(env, status_out, NUX_STATUS_RUNTIME_ERROR);
    return NULL;
  }
  jsize number_count = (*env)->GetArrayLength(env, input_number_array);
  if (clear_jni_exception(env)) {
    set_status_out(env, status_out, NUX_STATUS_RUNTIME_ERROR);
    return NULL;
  }
  if (name_count != count || bool_count != count || number_count != count) {
    set_status_out(env, status_out, NUX_STATUS_INVALID_ARGUMENT);
    return NULL;
  }
  jsize pointer_count = (*env)->GetArrayLength(env, pointer_kind_array);
  if (clear_jni_exception(env)) {
    set_status_out(env, status_out, NUX_STATUS_RUNTIME_ERROR);
    return NULL;
  }
  jsize pointer_x_count = (*env)->GetArrayLength(env, pointer_x_array);
  jsize pointer_y_count = (*env)->GetArrayLength(env, pointer_y_array);
  jsize pointer_id_count = (*env)->GetArrayLength(env, pointer_id_array);
  jsize pointer_timestamp_count =
      (*env)->GetArrayLength(env, pointer_timestamp_array);
  if (clear_jni_exception(env)) {
    set_status_out(env, status_out, NUX_STATUS_RUNTIME_ERROR);
    return NULL;
  }
  if (pointer_x_count != pointer_count || pointer_y_count != pointer_count ||
      pointer_id_count != pointer_count ||
      pointer_timestamp_count != pointer_count) {
    set_status_out(env, status_out, NUX_STATUS_INVALID_ARGUMENT);
    return NULL;
  }

  size_t allocation_count = count == 0 ? 1u : (size_t)count;
  kinds = calloc(allocation_count, sizeof(*kinds));
  bool_values = calloc(allocation_count, sizeof(*bool_values));
  number_values = calloc(allocation_count, sizeof(*number_values));
  names = calloc(allocation_count, sizeof(*names));
  inputs = calloc(allocation_count, sizeof(*inputs));
  size_t pointer_allocation_count =
      pointer_count == 0 ? 1u : (size_t)pointer_count;
  pointer_kinds = calloc(pointer_allocation_count, sizeof(*pointer_kinds));
  pointer_xs = calloc(pointer_allocation_count, sizeof(*pointer_xs));
  pointer_ys = calloc(pointer_allocation_count, sizeof(*pointer_ys));
  pointer_ids = calloc(pointer_allocation_count, sizeof(*pointer_ids));
  pointer_timestamps =
      calloc(pointer_allocation_count, sizeof(*pointer_timestamps));
  pointers = calloc(pointer_allocation_count, sizeof(*pointers));
  if (kinds == NULL || bool_values == NULL || number_values == NULL ||
      names == NULL || inputs == NULL || pointer_kinds == NULL ||
      pointer_xs == NULL || pointer_ys == NULL || pointer_ids == NULL ||
      pointer_timestamps == NULL || pointers == NULL) {
    failed = 1;
    goto typed_step_cleanup;
  }

  if (count != 0) {
    (*env)->GetIntArrayRegion(env, input_kind_array, 0, count, kinds);
    if (clear_jni_exception(env)) {
      failed = 1;
      goto typed_step_cleanup;
    }
    (*env)->GetBooleanArrayRegion(env, input_bool_array, 0, count, bool_values);
    if (clear_jni_exception(env)) {
      failed = 1;
      goto typed_step_cleanup;
    }
    (*env)->GetFloatArrayRegion(env, input_number_array, 0, count, number_values);
    if (clear_jni_exception(env)) {
      failed = 1;
      goto typed_step_cleanup;
    }
  }
  if (pointer_count != 0) {
    (*env)->GetIntArrayRegion(env, pointer_kind_array, 0, pointer_count,
                              pointer_kinds);
    (*env)->GetFloatArrayRegion(env, pointer_x_array, 0, pointer_count,
                                pointer_xs);
    (*env)->GetFloatArrayRegion(env, pointer_y_array, 0, pointer_count,
                                pointer_ys);
    (*env)->GetIntArrayRegion(env, pointer_id_array, 0, pointer_count,
                              pointer_ids);
    (*env)->GetFloatArrayRegion(env, pointer_timestamp_array, 0,
                                pointer_count, pointer_timestamps);
    if (clear_jni_exception(env)) {
      failed = 1;
      goto typed_step_cleanup;
    }
  }
  for (jsize index = 0; index < pointer_count; index++) {
    if (pointer_kinds[index] < NUX_PLAYER_POINTER_KIND_DOWN ||
        pointer_kinds[index] > NUX_PLAYER_POINTER_KIND_EXIT) {
      reported_status = NUX_STATUS_INVALID_ARGUMENT;
      failed = 1;
      goto typed_step_cleanup;
    }
    pointers[index].kind = (uint32_t)pointer_kinds[index];
    pointers[index].x = pointer_xs[index];
    pointers[index].y = pointer_ys[index];
    pointers[index].pointer_id = pointer_ids[index];
    pointers[index].timestamp_seconds = pointer_timestamps[index];
  }
  for (jsize index = 0; index < count; index++) {
    if (kinds[index] < NUX_PLAYER_INPUT_KIND_BOOL ||
        kinds[index] > NUX_PLAYER_INPUT_KIND_TRIGGER) {
      failed = 1;
      goto typed_step_cleanup;
    }
    jbyteArray name_bytes = (jbyteArray)(*env)->GetObjectArrayElement(
        env, input_name_array, index);
    if (clear_jni_exception(env) || name_bytes == NULL) {
      if (name_bytes != NULL) (*env)->DeleteLocalRef(env, name_bytes);
      failed = 1;
      goto typed_step_cleanup;
    }
    jsize name_len = (*env)->GetArrayLength(env, name_bytes);
    if (clear_jni_exception(env)) {
      (*env)->DeleteLocalRef(env, name_bytes);
      failed = 1;
      goto typed_step_cleanup;
    }
    names[index] = malloc(name_len == 0 ? 1u : (size_t)name_len);
    if (names[index] == NULL) {
      (*env)->DeleteLocalRef(env, name_bytes);
      failed = 1;
      goto typed_step_cleanup;
    }
    if (name_len != 0) {
      (*env)->GetByteArrayRegion(env, name_bytes, 0, name_len,
                                 (jbyte *)names[index]);
      if (clear_jni_exception(env)) {
        (*env)->DeleteLocalRef(env, name_bytes);
        failed = 1;
        goto typed_step_cleanup;
      }
    }
    (*env)->DeleteLocalRef(env, name_bytes);
    inputs[index].kind = (uint32_t)kinds[index];
    inputs[index].name.data = names[index];
    inputs[index].name.len = (size_t)name_len;
    inputs[index].bool_value = bool_values[index] == JNI_TRUE ? 1u : 0u;
    inputs[index].number_value = number_values[index];
  }

  struct NuxPlayerStep step;
  memset(&step, 0, sizeof(step));
  step.struct_size = (uint32_t)sizeof(step);
  step.inputs = count == 0 ? NULL : inputs;
  step.input_count = (size_t)count;
  step.pointers = pointer_count == 0 ? NULL : pointers;
  step.pointer_count = (size_t)pointer_count;
  step.elapsed_seconds = elapsed_seconds;
  step.correlation_id = (uint64_t)correlation_id;
  NuxStatus call_status = nux_player_step(
      (struct NuxPlayer *)from_handle(player), &step, &step_result);
  reported_status = call_status;
  if (step_result == NULL) {
    failed = 1;
    goto typed_step_cleanup;
  }
  NuxStatus result_status = NUX_STATUS_RUNTIME_ERROR;
  NuxStatus accessor_status =
      nux_player_step_result_status(step_result, &result_status);
  if (accessor_status != NUX_STATUS_OK) {
    reported_status = accessor_status;
    failed = 1;
    goto typed_step_cleanup;
  }
  if (result_status != call_status) {
    reported_status = NUX_STATUS_RUNTIME_ERROR;
    failed = 1;
    goto typed_step_cleanup;
  }
  if (result_status != NUX_STATUS_OK) {
    struct NuxCapiDiagnosticView diagnostic;
    memset(&diagnostic, 0, sizeof(diagnostic));
    diagnostic.struct_size = (uint32_t)sizeof(diagnostic);
    if (nux_player_step_result_diagnostic(step_result, &diagnostic) ==
        NUX_STATUS_OK) {
      NUXIE_LOG_WARN(
          "player_step failed: status=%d code=%.*s message=%.*s",
          (int)result_status, (int)diagnostic.code.len,
          diagnostic.code.data != NULL ? diagnostic.code.data : "",
          (int)diagnostic.message.len,
          diagnostic.message.data != NULL ? diagnostic.message.data : "");
    }
    failed = 1;
    goto typed_step_cleanup;
  }

  struct NuxPlayerStepInfo info;
  memset(&info, 0, sizeof(info));
  info.struct_size = (uint32_t)sizeof(info);
  accessor_status = nux_player_step_result_info(step_result, &info);
  if (accessor_status != NUX_STATUS_OK) {
    reported_status = accessor_status;
    failed = 1;
    goto typed_step_cleanup;
  }
  if (info.pointer_result_count > INT32_MAX ||
      info.event_count > INT32_MAX || info.host_command_count > INT32_MAX ||
      info.view_model_change_count > INT32_MAX) {
    failed = 1;
    goto typed_step_cleanup;
  }

  property_class = (*env)->FindClass(
      env, "ai/nuxie/sdk/runtime/NativeRuntimeEventProperty");
  if (clear_jni_exception(env) || property_class == NULL) {
    failed = 1;
    goto typed_step_cleanup;
  }
  event_class = (*env)->FindClass(
      env, "ai/nuxie/sdk/runtime/NativeRuntimeEvent");
  if (clear_jni_exception(env) || event_class == NULL) {
    failed = 1;
    goto typed_step_cleanup;
  }
  host_command_class = (*env)->FindClass(
      env, "ai/nuxie/sdk/runtime/NativeHostCommand");
  if (clear_jni_exception(env) || host_command_class == NULL) {
    failed = 1;
    goto typed_step_cleanup;
  }
  host_value_class = (*env)->FindClass(
      env, "ai/nuxie/sdk/runtime/NativeHostValue");
  if (clear_jni_exception(env) || host_value_class == NULL) {
    failed = 1;
    goto typed_step_cleanup;
  }
  host_field_class = (*env)->FindClass(
      env, "ai/nuxie/sdk/runtime/NativeHostField");
  if (clear_jni_exception(env) || host_field_class == NULL) {
    failed = 1;
    goto typed_step_cleanup;
  }
  change_class = (*env)->FindClass(
      env, "ai/nuxie/sdk/runtime/NativeViewModelChange");
  if (clear_jni_exception(env) || change_class == NULL) {
    failed = 1;
    goto typed_step_cleanup;
  }
  outcome_class = (*env)->FindClass(
      env, "ai/nuxie/sdk/runtime/NativePlayerStepOutcome");
  if (clear_jni_exception(env) || outcome_class == NULL) {
    failed = 1;
    goto typed_step_cleanup;
  }
  jmethodID property_constructor = (*env)->GetMethodID(
      env, property_class, "<init>", "(Ljava/lang/String;IFZ[BIJ)V");
  if (clear_jni_exception(env) || property_constructor == NULL) {
    failed = 1;
    goto typed_step_cleanup;
  }
  jmethodID event_constructor = (*env)->GetMethodID(
      env, event_class, "<init>",
      "(JILjava/lang/String;Ljava/lang/String;Ljava/lang/String;F"
      "[Lai/nuxie/sdk/runtime/NativeRuntimeEventProperty;)V");
  if (clear_jni_exception(env) || event_constructor == NULL) {
    failed = 1;
    goto typed_step_cleanup;
  }
  jmethodID host_value_constructor = (*env)->GetMethodID(
      env, host_value_class, "<init>",
      "(IZDLjava/lang/String;[Lai/nuxie/sdk/runtime/NativeHostValue;"
      "[Lai/nuxie/sdk/runtime/NativeHostField;)V");
  if (clear_jni_exception(env) || host_value_constructor == NULL) {
    failed = 1;
    goto typed_step_cleanup;
  }
  jmethodID host_field_constructor = (*env)->GetMethodID(
      env, host_field_class, "<init>",
      "(Ljava/lang/String;Lai/nuxie/sdk/runtime/NativeHostValue;)V");
  if (clear_jni_exception(env) || host_field_constructor == NULL) {
    failed = 1;
    goto typed_step_cleanup;
  }
  jmethodID host_command_constructor = (*env)->GetMethodID(
      env, host_command_class, "<init>",
      "(Ljava/lang/String;Lai/nuxie/sdk/runtime/NativeHostValue;)V");
  if (clear_jni_exception(env) || host_command_constructor == NULL) {
    failed = 1;
    goto typed_step_cleanup;
  }
  jmethodID change_constructor = (*env)->GetMethodID(
      env, change_class, "<init>", "(IJJJI[BFJZJ[J)V");
  if (clear_jni_exception(env) || change_constructor == NULL) {
    failed = 1;
    goto typed_step_cleanup;
  }
  jmethodID outcome_constructor = (*env)->GetMethodID(
      env, outcome_class, "<init>",
      "(Z[I[Lai/nuxie/sdk/runtime/NativeRuntimeEvent;"
      "[Lai/nuxie/sdk/runtime/NativeHostCommand;"
      "[Lai/nuxie/sdk/runtime/NativeViewModelChange;)V");
  if (clear_jni_exception(env) || outcome_constructor == NULL) {
    failed = 1;
    goto typed_step_cleanup;
  }

  pointer_hits = (*env)->NewIntArray(env, (jsize)info.pointer_result_count);
  if (clear_jni_exception(env) || pointer_hits == NULL) {
    failed = 1;
    goto typed_step_cleanup;
  }
  for (size_t pointer_index = 0;
       pointer_index < info.pointer_result_count; pointer_index++) {
    uint32_t raw_hit = UINT32_MAX;
    accessor_status =
        nux_player_step_result_pointer(step_result, pointer_index, &raw_hit);
    if (accessor_status != NUX_STATUS_OK) {
      reported_status = accessor_status;
      failed = 1;
      break;
    }
    if (raw_hit > NUX_PLAYER_POINTER_HIT_HIT_OPAQUE) {
      failed = 1;
      break;
    }
    jint hit = (jint)raw_hit;
    (*env)->SetIntArrayRegion(env, pointer_hits, (jsize)pointer_index, 1,
                              &hit);
    if (clear_jni_exception(env)) {
      failed = 1;
      break;
    }
  }
  if (failed) goto typed_step_cleanup;

  events = (*env)->NewObjectArray(env, (jsize)info.event_count,
                                  event_class, NULL);
  if (clear_jni_exception(env) || events == NULL) {
    failed = 1;
    goto typed_step_cleanup;
  }
  for (size_t event_index = 0; event_index < info.event_count; event_index++) {
    struct NuxPlayerEventView view;
    memset(&view, 0, sizeof(view));
    view.struct_size = (uint32_t)sizeof(view);
    accessor_status =
        nux_player_step_result_event(step_result, event_index, &view);
    if (accessor_status != NUX_STATUS_OK) {
      reported_status = accessor_status;
      failed = 1;
      break;
    }
    if (view.event_local_index > INT64_MAX ||
        view.property_count > INT32_MAX) {
      failed = 1;
      break;
    }
    jstring name = new_string_view(env, view.name);
    jstring url = new_string_view(env, view.url);
    jstring target = new_string_view(env, view.target);
    if (name == NULL || url == NULL || target == NULL) {
      if (target != NULL) (*env)->DeleteLocalRef(env, target);
      if (url != NULL) (*env)->DeleteLocalRef(env, url);
      if (name != NULL) (*env)->DeleteLocalRef(env, name);
      failed = 1;
      break;
    }
    jobjectArray event_properties = (*env)->NewObjectArray(
        env, (jsize)view.property_count, property_class, NULL);
    if (clear_jni_exception(env) || event_properties == NULL) {
      if (event_properties != NULL) (*env)->DeleteLocalRef(env, event_properties);
      (*env)->DeleteLocalRef(env, target);
      (*env)->DeleteLocalRef(env, url);
      (*env)->DeleteLocalRef(env, name);
      failed = 1;
      break;
    }
    for (size_t property_index = 0; property_index < view.property_count;
         property_index++) {
      struct NuxPlayerEventPropertyView property;
      memset(&property, 0, sizeof(property));
      property.struct_size = (uint32_t)sizeof(property);
      accessor_status = nux_player_step_result_event_property(
          step_result, event_index, property_index, &property);
      if (accessor_status != NUX_STATUS_OK) {
        reported_status = accessor_status;
        failed = 1;
        break;
      }
      if (property.kind > NUX_PLAYER_EVENT_PROPERTY_KIND_TRIGGER) {
        failed = 1;
        break;
      }
      jstring property_name = new_string_view(env, property.name);
      struct NuxByteView string_value;
      memset(&string_value, 0, sizeof(string_value));
      jfloat number_value = 0.0f;
      jboolean bool_value = JNI_FALSE;
      jint color_value = 0;
      jlong integer_value = 0;
      switch (property.kind) {
        case NUX_PLAYER_EVENT_PROPERTY_KIND_NUMBER:
          number_value = (jfloat)property.number_value;
          break;
        case NUX_PLAYER_EVENT_PROPERTY_KIND_BOOL:
          bool_value = property.bool_value ? JNI_TRUE : JNI_FALSE;
          break;
        case NUX_PLAYER_EVENT_PROPERTY_KIND_STRING:
          string_value = property.string_value;
          break;
        case NUX_PLAYER_EVENT_PROPERTY_KIND_COLOR:
          color_value = (jint)property.color_value;
          break;
        case NUX_PLAYER_EVENT_PROPERTY_KIND_ENUM:
          integer_value = (jlong)property.integer_value;
          break;
        case NUX_PLAYER_EVENT_PROPERTY_KIND_TRIGGER:
          break;
      }
      jbyteArray bytes = new_byte_view(env, string_value);
      if (property_name == NULL || bytes == NULL) {
        if (bytes != NULL) (*env)->DeleteLocalRef(env, bytes);
        if (property_name != NULL) (*env)->DeleteLocalRef(env, property_name);
        failed = 1;
        break;
      }
      jobject property_item = (*env)->NewObject(
          env, property_class, property_constructor, property_name,
          (jint)property.kind, number_value, bool_value, bytes, color_value,
          integer_value);
      if (clear_jni_exception(env) || property_item == NULL) {
        failed = 1;
      } else {
        (*env)->SetObjectArrayElement(env, event_properties,
                                     (jsize)property_index, property_item);
        if (clear_jni_exception(env)) failed = 1;
      }
      if (property_item != NULL) (*env)->DeleteLocalRef(env, property_item);
      (*env)->DeleteLocalRef(env, bytes);
      (*env)->DeleteLocalRef(env, property_name);
      if (failed) break;
    }
    jobject event_item = NULL;
    if (!failed) {
      event_item = (*env)->NewObject(
          env, event_class, event_constructor, (jlong)view.event_local_index,
          (jint)view.event_core_type, name, url, target,
          (jfloat)view.seconds_delay, event_properties);
      if (clear_jni_exception(env) || event_item == NULL) {
        failed = 1;
      } else {
        (*env)->SetObjectArrayElement(env, events, (jsize)event_index,
                                     event_item);
        if (clear_jni_exception(env)) failed = 1;
      }
    }
    if (event_item != NULL) (*env)->DeleteLocalRef(env, event_item);
    (*env)->DeleteLocalRef(env, event_properties);
    (*env)->DeleteLocalRef(env, target);
    (*env)->DeleteLocalRef(env, url);
    (*env)->DeleteLocalRef(env, name);
    if (failed) break;
  }

  if (!failed) {
    host_commands = (*env)->NewObjectArray(
        env, (jsize)info.host_command_count, host_command_class, NULL);
    if (clear_jni_exception(env) || host_commands == NULL) failed = 1;
  }
  struct host_value_jni_context host_context = {
      .value_class = host_value_class,
      .field_class = host_field_class,
      .value_constructor = host_value_constructor,
      .field_constructor = host_field_constructor,
  };
  for (size_t command_index = 0;
       !failed && command_index < info.host_command_count; command_index++) {
    struct NuxHostCommandView view;
    memset(&view, 0, sizeof(view));
    view.struct_size = (uint32_t)sizeof(view);
    accessor_status = nux_player_step_result_host_command(
        step_result, command_index, &view);
    if (accessor_status != NUX_STATUS_OK) {
      reported_status = accessor_status;
      failed = 1;
      break;
    }
    jstring name = new_string_view(env, view.name);
    jobject value = copy_host_value(
        env, step_result, view.root_value_index, 0u, &host_context,
        &reported_status);
    jobject command = NULL;
    if (name == NULL || value == NULL) {
      failed = 1;
    } else {
      command = (*env)->NewObject(env, host_command_class,
                                  host_command_constructor, name, value);
      if (clear_jni_exception(env) || command == NULL) {
        failed = 1;
      } else {
        (*env)->SetObjectArrayElement(env, host_commands,
                                      (jsize)command_index, command);
        if (clear_jni_exception(env)) failed = 1;
      }
    }
    if (command != NULL) (*env)->DeleteLocalRef(env, command);
    if (value != NULL) (*env)->DeleteLocalRef(env, value);
    if (name != NULL) (*env)->DeleteLocalRef(env, name);
  }

  if (!failed) {
    changes = (*env)->NewObjectArray(env, (jsize)info.view_model_change_count,
                                     change_class, NULL);
    if (clear_jni_exception(env) || changes == NULL) failed = 1;
  }
  for (size_t change_index = 0;
       !failed && change_index < info.view_model_change_count; change_index++) {
    struct NuxViewModelChangeView view;
    memset(&view, 0, sizeof(view));
    view.struct_size = (uint32_t)sizeof(view);
    accessor_status = nux_player_step_result_view_model_change(
        step_result, change_index, &view);
    if (accessor_status != NUX_STATUS_OK) {
      reported_status = accessor_status;
      failed = 1;
      break;
    }
    if (view.origin > NUX_VIEW_MODEL_CHANGE_ORIGIN_RUNTIME ||
        view.property_index > INT64_MAX || view.list_item_count > INT32_MAX ||
        (view.kind == NUX_VIEW_MODEL_VALUE_KIND_BOOL && view.bool_value > 1u)) {
      failed = 1;
      break;
    }
    jbyteArray bytes = new_byte_view(env, view.bytes_value);
    jlongArray list_items = (*env)->NewLongArray(
        env, (jsize)view.list_item_count);
    if (clear_jni_exception(env) || bytes == NULL || list_items == NULL) {
      if (list_items != NULL) (*env)->DeleteLocalRef(env, list_items);
      if (bytes != NULL) (*env)->DeleteLocalRef(env, bytes);
      failed = 1;
      break;
    }
    jlong *list_storage = calloc(
        view.list_item_count == 0 ? 1u : view.list_item_count,
        sizeof(*list_storage));
    if (list_storage == NULL) {
      (*env)->DeleteLocalRef(env, list_items);
      (*env)->DeleteLocalRef(env, bytes);
      failed = 1;
      break;
    }
    for (size_t item_index = 0; item_index < view.list_item_count;
         item_index++) {
      uint64_t identity = 0;
      accessor_status = nux_player_step_result_view_model_change_list_item(
          step_result, change_index, item_index, &identity);
      if (accessor_status != NUX_STATUS_OK) {
        reported_status = accessor_status;
        failed = 1;
        break;
      }
      list_storage[item_index] = (jlong)identity;
    }
    if (!failed && view.list_item_count != 0) {
      (*env)->SetLongArrayRegion(env, list_items, 0,
                                 (jsize)view.list_item_count, list_storage);
      if (clear_jni_exception(env)) failed = 1;
    }
    free(list_storage);
    jobject change_item = NULL;
    if (!failed) {
      change_item = (*env)->NewObject(
          env, change_class, change_constructor, (jint)view.origin,
          (jlong)view.correlation_id, (jlong)view.owner_instance_id,
          (jlong)view.property_index, (jint)view.kind, bytes,
          (jfloat)view.number_value, (jlong)view.integer_value,
          (jboolean)(view.bool_value == 1u ? JNI_TRUE : JNI_FALSE),
          (jlong)view.referenced_instance_id, list_items);
      if (clear_jni_exception(env) || change_item == NULL) {
        failed = 1;
      } else {
        (*env)->SetObjectArrayElement(env, changes, (jsize)change_index,
                                     change_item);
        if (clear_jni_exception(env)) failed = 1;
      }
    }
    if (change_item != NULL) (*env)->DeleteLocalRef(env, change_item);
    (*env)->DeleteLocalRef(env, list_items);
    (*env)->DeleteLocalRef(env, bytes);
  }

  if (!failed) {
    outcome = (*env)->NewObject(
        env, outcome_class, outcome_constructor,
        (jboolean)(info.keep_going ? JNI_TRUE : JNI_FALSE), pointer_hits,
        events, host_commands, changes);
    if (clear_jni_exception(env) || outcome == NULL) failed = 1;
  }

typed_step_cleanup:
  if (step_result != NULL) {
    NuxStatus free_status = nux_player_step_result_free(step_result);
    log_cleanup_failure("player_step_result_free", free_status);
  }
  if (changes != NULL) (*env)->DeleteLocalRef(env, changes);
  if (host_commands != NULL) (*env)->DeleteLocalRef(env, host_commands);
  if (events != NULL) (*env)->DeleteLocalRef(env, events);
  if (pointer_hits != NULL) (*env)->DeleteLocalRef(env, pointer_hits);
  if (outcome_class != NULL) (*env)->DeleteLocalRef(env, outcome_class);
  if (change_class != NULL) (*env)->DeleteLocalRef(env, change_class);
  if (host_field_class != NULL) (*env)->DeleteLocalRef(env, host_field_class);
  if (host_value_class != NULL) (*env)->DeleteLocalRef(env, host_value_class);
  if (host_command_class != NULL) (*env)->DeleteLocalRef(env, host_command_class);
  if (event_class != NULL) (*env)->DeleteLocalRef(env, event_class);
  if (property_class != NULL) (*env)->DeleteLocalRef(env, property_class);
  if (names != NULL) {
    for (jsize index = 0; index < count; index++) free(names[index]);
  }
  free(inputs);
  free(pointers);
  free(pointer_timestamps);
  free(pointer_ids);
  free(pointer_ys);
  free(pointer_xs);
  free(pointer_kinds);
  free(names);
  free(number_values);
  free(bool_values);
  free(kinds);
  if (failed && outcome != NULL) {
    (*env)->DeleteLocalRef(env, outcome);
    outcome = NULL;
  }
  clear_jni_exception(env);
  if (failed && reported_status == NUX_STATUS_OK) {
    reported_status = NUX_STATUS_RUNTIME_ERROR;
  }
  if (!set_status_out(env, status_out, reported_status)) {
    if (outcome != NULL) (*env)->DeleteLocalRef(env, outcome);
    return NULL;
  }
  return outcome;
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

#if defined(__ANDROID__)
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
#endif

JNIEXPORT jobject JNICALL
Java_ai_nuxie_sdk_runtime_NuxieRuntimeBridge_nativeRendererRenderPlayerToCpuFrame(
    JNIEnv *env, jobject self, jlong renderer, jlong player, jint clear_color,
    jboolean fit_contain_center) {
  (void)self;
  if (renderer == 0 || player == 0) return NULL;

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
    return NULL;
  }

  jobject output = NULL;
  const uint32_t width = nux_android_vulkan_frame_width(frame);
  const uint32_t height = nux_android_vulkan_frame_height(frame);
  const uint32_t stride = nux_android_vulkan_frame_row_stride_bytes(frame);
  const size_t source_len = nux_android_vulkan_frame_len(frame);
  const uint8_t *source = nux_android_vulkan_frame_data(frame);
  const size_t tight_stride = (size_t)width * 4u;
  if (width == 0 || height == 0 || width > INT32_MAX || height > INT32_MAX ||
      height > SIZE_MAX / tight_stride ||
      tight_stride * height > INT32_MAX || source == NULL ||
      stride < tight_stride || height > SIZE_MAX / stride ||
      source_len < (size_t)height * stride ||
      nux_android_vulkan_frame_pixel_format(frame) !=
          NUX_ANDROID_VULKAN_PIXEL_FORMAT_RGBA8_PREMULTIPLIED) {
    NUXIE_LOG_WARN("renderer returned an invalid CPU frame");
    goto cpu_frame_cleanup;
  }

  const jsize byte_count = (jsize)(tight_stride * height);
  jbyteArray pixels = (*env)->NewByteArray(env, byte_count);
  if (pixels == NULL) goto cpu_frame_cleanup;
  if (stride == tight_stride) {
    (*env)->SetByteArrayRegion(env, pixels, 0, byte_count,
                               (const jbyte *)source);
  } else {
    for (uint32_t row = 0; row < height; row++) {
      (*env)->SetByteArrayRegion(
          env, pixels, (jsize)((size_t)row * tight_stride),
          (jsize)tight_stride, (const jbyte *)(source + (size_t)row * stride));
      if ((*env)->ExceptionCheck(env)) break;
    }
  }
  if ((*env)->ExceptionCheck(env)) {
    (*env)->DeleteLocalRef(env, pixels);
    goto cpu_frame_cleanup;
  }

  jclass frame_class =
      (*env)->FindClass(env, "ai/nuxie/sdk/runtime/NuxieCpuFrame");
  if (frame_class == NULL) {
    (*env)->DeleteLocalRef(env, pixels);
    goto cpu_frame_cleanup;
  }
  jmethodID constructor =
      (*env)->GetMethodID(env, frame_class, "<init>", "(II[B)V");
  if (constructor != NULL) {
    output = (*env)->NewObject(env, frame_class, constructor, (jint)width,
                               (jint)height, pixels);
  }
  (*env)->DeleteLocalRef(env, frame_class);
  (*env)->DeleteLocalRef(env, pixels);

cpu_frame_cleanup:
  nux_android_vulkan_frame_free(frame);
  return output;
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
