#include <assert.h>
#include <errno.h>
#include <jni.h>
#include <pthread.h>
#include <stdbool.h>
#include <stdatomic.h>
#include <stdio.h>
#include <string.h>

static int controlled_key_create(pthread_key_t *, void (*)(void *));
static int controlled_setspecific(pthread_key_t, const void *);
// Inject only platform failures; the attachment and destructor code is real.
#define pthread_key_create controlled_key_create
#define pthread_setspecific controlled_setspecific
#include "../../main/cpp/nuxie_jni_attachment.c"
#undef pthread_key_create
#undef pthread_setspecific

static atomic_int keys, normal_attaches, daemon_attaches, detaches, registrations;
static atomic_bool start_workers;
static bool fail_key, fail_tls, fail_attach;
static jint get_env_error;
static _Thread_local bool is_attached, is_daemon, pending_exception;
static _Thread_local JNIEnv thread_env;

static jboolean exception_check(JNIEnv *env) { (void)env; return pending_exception; }
static void exception_clear(JNIEnv *env) { (void)env; pending_exception = false; }
static const struct JNINativeInterface_ env_table = {
  .ExceptionCheck = exception_check, .ExceptionClear = exception_clear,
};

static jint get_env(JavaVM *vm, void **out, jint version) {
  (void)vm;
  assert(version == JNI_VERSION_1_6);
  *out = NULL;
  if (get_env_error != 0) return get_env_error;
  if (!is_attached) return JNI_EDETACHED;
  thread_env = &env_table;
  *out = &thread_env;
  return JNI_OK;
}

static jint attach(void **out, bool daemon) {
  assert(!is_attached);
  if (daemon) atomic_fetch_add(&daemon_attaches, 1);
  else atomic_fetch_add(&normal_attaches, 1);
  if (fail_attach) return JNI_ERR;
  is_attached = true;
  is_daemon = daemon;
  thread_env = &env_table;
  *out = &thread_env;
  return JNI_OK;
}
static jint attach_normal(JavaVM *vm, void **out, void *args) {
  (void)vm; assert(args == NULL); return attach(out, false);
}
static jint attach_daemon(JavaVM *vm, void **out, void *args) {
  (void)vm; assert(args == NULL); return attach(out, true);
}
static jint detach(JavaVM *vm) {
  (void)vm;
  // macOS may destroy C thread-local storage before pthread key destructors.
  // The counters below verify exact detach ownership across thread exit.
  is_attached = false;
  atomic_fetch_add(&detaches, 1);
  return JNI_OK;
}
static const struct JNIInvokeInterface_ vm_table = {
  .GetEnv = get_env, .AttachCurrentThread = attach_normal,
  .AttachCurrentThreadAsDaemon = attach_daemon, .DetachCurrentThread = detach,
};
static JavaVM vm = &vm_table;

static int controlled_key_create(pthread_key_t *key, void (*destroy)(void *)) {
  atomic_fetch_add(&keys, 1);
  return fail_key ? EAGAIN : pthread_key_create(key, destroy);
}
static int controlled_setspecific(pthread_key_t key, const void *value) {
  atomic_fetch_add(&registrations, 1);
  return fail_tls ? ENOMEM : pthread_setspecific(key, value);
}

static void *native_worker(void *context) {
  NuxieJniAttachmentMode mode = *(NuxieJniAttachmentMode *)context;
  while (!atomic_load(&start_workers)) { /* Race the first initialization. */ }
  bool did_attach = false;
  JNIEnv *env = nuxie_jni_env(&vm, mode, &did_attach);
  assert(env == &thread_env && did_attach);
  assert(is_daemon == (mode == NUXIE_JNI_ATTACH_DAEMON));
  // A font callback on an executor worker, and vice versa, reuse one owner.
  NuxieJniAttachmentMode other = mode == NUXIE_JNI_ATTACH_DAEMON ?
      NUXIE_JNI_ATTACH_NORMAL : NUXIE_JNI_ATTACH_DAEMON;
  pending_exception = true;
  assert(nuxie_jni_env(&vm, other, &did_attach) == env && !did_attach);
  assert(nuxie_jni_env(&vm, mode, NULL) == env);
  assert(pending_exception);
  assert(is_daemon == (mode == NUXIE_JNI_ATTACH_DAEMON));
  pending_exception = false;
  return NULL;
}

static void *owned_worker(void *unused) {
  (void)unused;
  is_attached = true; // JVM/another library owns this thread, not our helper.
  pending_exception = true;
  bool did_attach = true;
  assert(nuxie_jni_env(&vm, NUXIE_JNI_ATTACH_DAEMON, &did_attach) == &thread_env);
  assert(!did_attach && !is_daemon && pending_exception);
  assert(nuxie_jni_env(&vm, NUXIE_JNI_ATTACH_NORMAL, NULL) == &thread_env);
  return NULL;
}

static void *failed_worker(void *context) {
  NuxieJniAttachmentMode mode = *(NuxieJniAttachmentMode *)context;
  bool did_attach = true;
  assert(nuxie_jni_env(&vm, mode, &did_attach) == NULL);
  assert(!did_attach && !is_attached);
  return NULL;
}

static void join_worker(void *(*body)(void *), void *context) {
  pthread_t worker;
  assert(pthread_create(&worker, NULL, body, context) == 0);
  assert(pthread_join(worker, NULL) == 0);
}

int main(int argc, char **argv) {
  assert(argc == 2);
  const char *scenario = argv[1];
  NuxieJniAttachmentMode normal = NUXIE_JNI_ATTACH_NORMAL;
  NuxieJniAttachmentMode daemon = NUXIE_JNI_ATTACH_DAEMON;
  bool did_attach = true;
  assert(nuxie_jni_env(NULL, normal, &did_attach) == NULL && !did_attach);
  if (strcmp(scenario, "concurrent") == 0) {
    pthread_t workers[16];
    for (int i = 0; i < 16; ++i)
      assert(pthread_create(&workers[i], NULL, native_worker, i % 2 ? &normal : &daemon) == 0);
    atomic_store(&start_workers, true);
    for (int i = 0; i < 16; ++i) assert(pthread_join(workers[i], NULL) == 0);
    assert(keys == 1 && normal_attaches == 8 && daemon_attaches == 8);
    assert(registrations == 16 && detaches == 16);
  } else if (strcmp(scenario, "owned") == 0) {
    fail_key = true; // Existing attachments do not require a TLS key.
    join_worker(owned_worker, NULL);
    assert(keys == 0 && registrations == 0 && detaches == 0);
    assert(normal_attaches == 0 && daemon_attaches == 0);
  } else {
    if (strcmp(scenario, "get-env-error") == 0) get_env_error = JNI_EVERSION;
    else if (strcmp(scenario, "attach-error") == 0) fail_attach = true;
    else if (strcmp(scenario, "key-error") == 0) fail_key = true;
    else if (strcmp(scenario, "tls-error") == 0) fail_tls = true;
    else assert(!"Unknown scenario");
    join_worker(failed_worker, &normal);
    join_worker(failed_worker, &daemon);
    if (get_env_error || fail_key) {
      assert(normal_attaches == 0 && daemon_attaches == 0);
      assert(registrations == 0 && detaches == 0);
      assert(keys == (get_env_error ? 0 : 1));
    } else {
      assert(keys == 1 && normal_attaches == 1 && daemon_attaches == 1);
      assert(registrations == (fail_tls ? 2 : 0));
      // TLS failure must roll back each attach immediately, with no later detach.
      assert(detaches == (fail_tls ? 2 : 0));
    }
  }
  printf("JNI attachment contract passed: %s\n", scenario);
  return 0;
}
