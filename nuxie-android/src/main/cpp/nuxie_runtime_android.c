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
  jbyteArray encoded = (*env)->NewByteArray(env, (jsize)request->encoded.len);
  if (encoded == NULL) return NUX_ASSET_CALLBACK_STATUS_FAILED;
  if (request->encoded.len != 0) {
    (*env)->SetByteArrayRegion(env, encoded, 0, (jsize)request->encoded.len,
                              (const jbyte *)request->encoded.data);
  }
  jclass decoder_class = (*env)->GetObjectClass(env, context->decoder);
  jmethodID decode = decoder_class == NULL
      ? NULL
      : (*env)->GetMethodID(
            env, decoder_class, "decode",
            "([BIJ)Lai/nuxie/sdk/runtime/DecodedImage;");
  jobject decoded = decode == NULL
      ? NULL
      : (*env)->CallObjectMethod(env, context->decoder, decode, encoded,
                                 (jint)request->maximum_dimension,
                                 (jlong)request->maximum_decoded_bytes);
  (*env)->DeleteLocalRef(env, encoded);
  if (decoder_class != NULL) (*env)->DeleteLocalRef(env, decoder_class);
  if ((*env)->ExceptionCheck(env)) {
    (*env)->ExceptionClear(env);
    if (decoded != NULL) (*env)->DeleteLocalRef(env, decoded);
    return NUX_ASSET_CALLBACK_STATUS_FAILED;
  }
  if (decoded == NULL) return NUX_ASSET_CALLBACK_STATUS_FAILED;

  jclass decoded_class = (*env)->GetObjectClass(env, decoded);
  jmethodID get_width = decoded_class == NULL
      ? NULL : (*env)->GetMethodID(env, decoded_class, "getWidth", "()I");
  jmethodID get_height = decoded_class == NULL
      ? NULL : (*env)->GetMethodID(env, decoded_class, "getHeight", "()I");
  jmethodID get_row_bytes = decoded_class == NULL
      ? NULL : (*env)->GetMethodID(env, decoded_class, "getRowBytes", "()I");
  jmethodID get_pixels = decoded_class == NULL
      ? NULL : (*env)->GetMethodID(env, decoded_class, "getPixels", "()[B");
  if (get_width == NULL || get_height == NULL || get_row_bytes == NULL ||
      get_pixels == NULL) {
    if ((*env)->ExceptionCheck(env)) (*env)->ExceptionClear(env);
    if (decoded_class != NULL) (*env)->DeleteLocalRef(env, decoded_class);
    (*env)->DeleteLocalRef(env, decoded);
    return NUX_ASSET_CALLBACK_STATUS_FAILED;
  }
  jint width = (*env)->CallIntMethod(env, decoded, get_width);
  jint height = (*env)->CallIntMethod(env, decoded, get_height);
  jint row_bytes = (*env)->CallIntMethod(env, decoded, get_row_bytes);
  jbyteArray pixels = (jbyteArray)(*env)->CallObjectMethod(env, decoded, get_pixels);
  if ((*env)->ExceptionCheck(env) || pixels == NULL || width <= 0 || height <= 0 ||
      row_bytes <= 0) {
    if ((*env)->ExceptionCheck(env)) (*env)->ExceptionClear(env);
    if (pixels != NULL) (*env)->DeleteLocalRef(env, pixels);
    (*env)->DeleteLocalRef(env, decoded_class);
    (*env)->DeleteLocalRef(env, decoded);
    return NUX_ASSET_CALLBACK_STATUS_FAILED;
  }
  jsize pixel_len = (*env)->GetArrayLength(env, pixels);
  uint64_t tight_row_bytes = (uint64_t)(uint32_t)width * 4u;
  uint64_t tight_byte_count = tight_row_bytes * (uint64_t)(uint32_t)height;
  if ((uint32_t)width > request->maximum_dimension ||
      (uint32_t)height > request->maximum_dimension ||
      tight_row_bytes > UINT32_MAX || (uint64_t)(uint32_t)row_bytes != tight_row_bytes ||
      tight_byte_count != (uint64_t)(uint32_t)pixel_len ||
      tight_byte_count > request->maximum_decoded_bytes) {
    (*env)->DeleteLocalRef(env, pixels);
    (*env)->DeleteLocalRef(env, decoded_class);
    (*env)->DeleteLocalRef(env, decoded);
    return NUX_ASSET_CALLBACK_STATUS_FAILED;
  }
  uint8_t *owned = malloc(pixel_len == 0 ? 1 : (size_t)pixel_len);
  if (owned != NULL && pixel_len != 0) {
    (*env)->GetByteArrayRegion(env, pixels, 0, pixel_len, (jbyte *)owned);
  }
  (*env)->DeleteLocalRef(env, pixels);
  (*env)->DeleteLocalRef(env, decoded_class);
  (*env)->DeleteLocalRef(env, decoded);
  if (owned == NULL || (*env)->ExceptionCheck(env)) {
    free(owned);
    if ((*env)->ExceptionCheck(env)) (*env)->ExceptionClear(env);
    return NUX_ASSET_CALLBACK_STATUS_FAILED;
  }

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
}

static jstring new_string_view(JNIEnv *env, struct NuxStringView view) {
  if ((view.data == NULL && view.len != 0) || view.len > INT32_MAX) return NULL;
  jbyteArray bytes = (*env)->NewByteArray(env, (jsize)view.len);
  if (bytes == NULL) return NULL;
  if (view.len != 0) {
    (*env)->SetByteArrayRegion(env, bytes, 0, (jsize)view.len,
                              (const jbyte *)view.data);
  }
  jclass string_class = (*env)->FindClass(env, "java/lang/String");
  jmethodID constructor = string_class == NULL ? NULL : (*env)->GetMethodID(
      env, string_class, "<init>", "([BLjava/lang/String;)V");
  jstring charset = constructor == NULL ? NULL : (*env)->NewStringUTF(env, "UTF-8");
  jstring value = charset == NULL ? NULL : (jstring)(*env)->NewObject(
      env, string_class, constructor, bytes, charset);
  if (charset != NULL) (*env)->DeleteLocalRef(env, charset);
  if (string_class != NULL) (*env)->DeleteLocalRef(env, string_class);
  (*env)->DeleteLocalRef(env, bytes);
  return value;
}

JNIEXPORT jobjectArray JNICALL
Java_ai_nuxie_sdk_runtime_NuxieRuntimeBridge_nativeFileInspectAssets(
    JNIEnv *env, jobject self, jbyteArray bytes) {
  (void)self;
  if (bytes == NULL) return NULL;
  jsize length = (*env)->GetArrayLength(env, bytes);
  jbyte *data = (*env)->GetByteArrayElements(env, bytes, NULL);
  if (data == NULL) return NULL;
  struct NuxFile *file = NULL;
  NuxStatus status = nux_file_import((const uint8_t *)data, (size_t)length, &file);
  (*env)->ReleaseByteArrayElements(env, bytes, data, JNI_ABORT);
  if (status != NUX_STATUS_OK || file == NULL) return NULL;

  size_t count = 0;
  if (nux_file_asset_count(file, &count) != NUX_STATUS_OK || count > INT32_MAX) {
    nux_file_free(file);
    return NULL;
  }
  jclass item_class = (*env)->FindClass(
      env, "ai/nuxie/sdk/runtime/NativeExpectedFileAsset");
  jmethodID constructor = item_class == NULL ? NULL : (*env)->GetMethodID(
      env, item_class, "<init>",
      "(IIZJLjava/lang/String;Ljava/lang/String;ZZI)V");
  jobjectArray result = constructor == NULL
      ? NULL : (*env)->NewObjectArray(env, (jsize)count, item_class, NULL);
  for (size_t index = 0; result != NULL && index < count; index++) {
    struct NuxFileAssetDescriptorView view;
    memset(&view, 0, sizeof(view));
    view.struct_size = (uint32_t)sizeof(view);
    if (nux_file_asset_descriptor(file, index, &view) != NUX_STATUS_OK) {
      result = NULL;
      break;
    }
    jstring name = new_string_view(env, view.name);
    jstring extension = new_string_view(env, view.file_extension);
    jobject item = name == NULL || extension == NULL ? NULL : (*env)->NewObject(
        env, item_class, constructor, (jint)view.ordinal, (jint)view.kind,
        (jboolean)(view.has_authored_id != 0), (jlong)view.authored_id,
        name, extension, (jboolean)(view.is_embedded != 0),
        (jboolean)(view.has_contents_record != 0),
        (jint)view.required_provider_flags);
    if (item == NULL || (*env)->ExceptionCheck(env)) {
      result = NULL;
    } else {
      (*env)->SetObjectArrayElement(env, result, (jsize)index, item);
    }
    if (item != NULL) (*env)->DeleteLocalRef(env, item);
    if (name != NULL) (*env)->DeleteLocalRef(env, name);
    if (extension != NULL) (*env)->DeleteLocalRef(env, extension);
  }
  nux_file_free(file);
  if (item_class != NULL) (*env)->DeleteLocalRef(env, item_class);
  if ((*env)->ExceptionCheck(env)) {
    (*env)->ExceptionClear(env);
    return NULL;
  }
  return result;
}

JNIEXPORT jlong JNICALL
Java_ai_nuxie_sdk_runtime_NuxieRuntimeBridge_nativeFileNewConfigured(
    JNIEnv *env, jobject self, jbyteArray bytes, jintArray ordinal_array,
    jintArray kind_array, jbooleanArray has_id_array, jlongArray id_array,
    jobjectArray name_array, jobjectArray extension_array,
    jbooleanArray embedded_array, jbooleanArray contents_array,
    jintArray flags_array, jintArray external_ordinal_array,
    jobjectArray external_payload_array, jobject decoder) {
  (void)self;
  if (bytes == NULL || ordinal_array == NULL || kind_array == NULL ||
      has_id_array == NULL || id_array == NULL || name_array == NULL ||
      extension_array == NULL || embedded_array == NULL ||
      contents_array == NULL || flags_array == NULL ||
      external_ordinal_array == NULL || external_payload_array == NULL ||
      decoder == NULL) {
    return 0;
  }
  jsize count = (*env)->GetArrayLength(env, ordinal_array);
  jsize external_count = (*env)->GetArrayLength(env, external_ordinal_array);
  if (count <= 0 || count > NUX_FILE_ASSET_CATALOG_HARD_MAX ||
      (*env)->GetArrayLength(env, kind_array) != count ||
      (*env)->GetArrayLength(env, has_id_array) != count ||
      (*env)->GetArrayLength(env, id_array) != count ||
      (*env)->GetArrayLength(env, name_array) != count ||
      (*env)->GetArrayLength(env, extension_array) != count ||
      (*env)->GetArrayLength(env, embedded_array) != count ||
      (*env)->GetArrayLength(env, contents_array) != count ||
      (*env)->GetArrayLength(env, flags_array) != count ||
      (*env)->GetArrayLength(env, external_payload_array) != external_count) {
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
  (*env)->GetIntArrayRegion(env, kind_array, 0, count, kinds);
  (*env)->GetBooleanArrayRegion(env, has_id_array, 0, count, has_ids);
  (*env)->GetLongArrayRegion(env, id_array, 0, count, ids);
  (*env)->GetBooleanArrayRegion(env, embedded_array, 0, count, embedded);
  (*env)->GetBooleanArrayRegion(env, contents_array, 0, count, contents);
  (*env)->GetIntArrayRegion(env, flags_array, 0, count, flags);
  if (external_count != 0) {
    (*env)->GetIntArrayRegion(env, external_ordinal_array, 0, external_count,
                             external_ordinals);
  }
  if ((*env)->ExceptionCheck(env)) goto cleanup_configured_import;

  for (jsize index = 0; index < count; index++) {
    if (ordinals[index] != index || ids[index] < 0 || ids[index] > UINT32_MAX) {
      goto cleanup_configured_import;
    }
    jbyteArray name_bytes = (jbyteArray)(*env)->GetObjectArrayElement(
        env, name_array, index);
    jbyteArray extension_bytes = (jbyteArray)(*env)->GetObjectArrayElement(
        env, extension_array, index);
    if (name_bytes == NULL || extension_bytes == NULL) {
      if (name_bytes != NULL) (*env)->DeleteLocalRef(env, name_bytes);
      if (extension_bytes != NULL) (*env)->DeleteLocalRef(env, extension_bytes);
      goto cleanup_configured_import;
    }
    jsize name_len = (*env)->GetArrayLength(env, name_bytes);
    jsize extension_len = (*env)->GetArrayLength(env, extension_bytes);
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
    }
    if (extension_len != 0) {
      (*env)->GetByteArrayRegion(env, extension_bytes, 0, extension_len,
                                 (jbyte *)extensions[index]);
    }
    (*env)->DeleteLocalRef(env, name_bytes);
    (*env)->DeleteLocalRef(env, extension_bytes);
    if ((*env)->ExceptionCheck(env)) goto cleanup_configured_import;
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
    if (bytes_value == NULL) goto cleanup_configured_import;
    jsize payload_len = (*env)->GetArrayLength(env, bytes_value);
    payloads[ordinal].data = malloc(payload_len == 0 ? 1 : (size_t)payload_len);
    if (payloads[ordinal].data == NULL) {
      (*env)->DeleteLocalRef(env, bytes_value);
      goto cleanup_configured_import;
    }
    if (payload_len != 0) {
      (*env)->GetByteArrayRegion(env, bytes_value, 0, payload_len,
                                 (jbyte *)payloads[ordinal].data);
    }
    (*env)->DeleteLocalRef(env, bytes_value);
    if ((*env)->ExceptionCheck(env)) goto cleanup_configured_import;
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

  static const char module_name[] = "nuxie";
  struct NuxHostCommandImportConfig host;
  memset(&host, 0, sizeof(host));
  host.struct_size = (uint32_t)sizeof(host);
  host.module_name.data = module_name;
  host.module_name.len = sizeof(module_name) - 1;
  host.max_script_memory_bytes = 64u * 1024u * 1024u;
  host.max_script_interrupts_per_callback = 50000u;
  host.max_commands_per_step = 256u;
  host.max_value_depth = 32u;
  host.max_value_nodes = 4096u;
  host.max_identifier_bytes = 4096u;
  host.max_string_bytes = 1024u * 1024u;
  host.max_value_bytes = 4u * 1024u * 1024u;
  host.max_command_bytes_per_step = 4u * 1024u * 1024u;

  struct NuxFileImportConfig config;
  memset(&config, 0, sizeof(config));
  config.struct_size = (uint32_t)sizeof(config);
  config.host_commands = &host;
  config.asset_hooks = &hooks;
  config.expected_assets = expected;
  config.expected_asset_count = (size_t)count;

  jsize file_len = (*env)->GetArrayLength(env, bytes);
  file_bytes = (*env)->GetByteArrayElements(env, bytes, NULL);
  if (file_bytes == NULL) goto cleanup_configured_import;
  status = nux_file_import_configured((const uint8_t *)file_bytes,
                                      (size_t)file_len, &config, &file, &result);
  log_and_free_result("file_import_configured", status, result);
  result = NULL;

cleanup_configured_import:
  if (file_bytes != NULL) {
    (*env)->ReleaseByteArrayElements(env, bytes, file_bytes, JNI_ABORT);
  }
  if (result != NULL) log_and_free_result("file_import_configured", status, result);
  if (status != NUX_STATUS_OK && file != NULL) {
    nux_file_free(file);
    file = NULL;
  }
  if ((*env)->ExceptionCheck(env)) (*env)->ExceptionClear(env);
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
