#include <jni.h>
#include <pthread.h>
#include <stdbool.h>
#include <stdint.h>
#include <stdlib.h>
#include "nux_capi.generated.h"

// The runtime owns the queue/thread. JNI owns JVM attachment and callback refs.
static pthread_once_t executor_key_once = PTHREAD_ONCE_INIT;
static pthread_key_t executor_attachment_key;
static int executor_key_status;

static void executor_detach(void *context) {
  JavaVM *vm = context;
  if (vm != NULL) (*vm)->DetachCurrentThread(vm);
}

static void executor_make_key(void) {
  executor_key_status = pthread_key_create(&executor_attachment_key, executor_detach);
}

static JNIEnv *executor_env(JavaVM *vm) {
  JNIEnv *env = NULL;
  if ((*vm)->GetEnv(vm, (void **)&env, JNI_VERSION_1_6) == JNI_OK) return env;
  pthread_once(&executor_key_once, executor_make_key);
  if (executor_key_status != 0) return NULL;
#ifdef __ANDROID__
  if ((*vm)->AttachCurrentThreadAsDaemon(vm, &env, NULL) != JNI_OK) return NULL;
#else
  if ((*vm)->AttachCurrentThreadAsDaemon(vm, (void **)&env, NULL) != JNI_OK) return NULL;
#endif
  if (pthread_setspecific(executor_attachment_key, vm) != 0) {
    (*vm)->DetachCurrentThread(vm);
    return NULL;
  }
  return env;
}

static void executor_clear_exception(JNIEnv *env) {
  if ((*env)->ExceptionCheck(env)) {
    (*env)->ExceptionDescribe(env);
    (*env)->ExceptionClear(env);
  }
}

typedef struct {
  JavaVM *vm;
  jobject job;
  jmethodID run;
  jmethodID close;
} ExecutorJob;

static void executor_job_run(void *context) {
  ExecutorJob *job = context;
  JNIEnv *env = executor_env(job->vm);
  if (env == NULL) return; // Release invokes close to resolve abandoned work.
  if ((*env)->PushLocalFrame(env, 32) != JNI_OK) { executor_clear_exception(env); return; }
  (*env)->CallVoidMethod(env, job->job, job->run);
  executor_clear_exception(env);
  (*env)->PopLocalFrame(env, NULL);
}

static void executor_job_release(void *context) {
  ExecutorJob *job = context;
  JNIEnv *env = executor_env(job->vm);
  if (env != NULL) {
    (*env)->CallVoidMethod(env, job->job, job->close);
    executor_clear_exception(env);
    (*env)->DeleteGlobalRef(env, job->job);
  }
  free(job);
}

static ExecutorJob *executor_job_new(JNIEnv *env, jobject callback) {
  ExecutorJob *job = calloc(1, sizeof(*job));
  if (job == NULL) return NULL;
  if ((*env)->GetJavaVM(env, &job->vm) != JNI_OK) { free(job); return NULL; }
  jclass cls = (*env)->GetObjectClass(env, callback);
  if (cls == NULL) { free(job); return NULL; }
  job->run = (*env)->GetMethodID(env, cls, "run", "()V");
  if (job->run != NULL) job->close = (*env)->GetMethodID(env, cls, "close", "()V");
  (*env)->DeleteLocalRef(env, cls);
  if (job->run == NULL || job->close == NULL) { free(job); return NULL; }
  job->job = (*env)->NewGlobalRef(env, callback);
  if (job->job == NULL) { free(job); return NULL; }
  return job;
}

// Rejection does not invoke close: Kotlin still owns result resolution.
static void executor_job_reject(JNIEnv *env, ExecutorJob *job) {
  (*env)->DeleteGlobalRef(env, job->job);
  free(job);
}

typedef struct {
  JavaVM *vm;
  pthread_mutex_t mutex;
  pthread_cond_t condition;
  bool ready;
  bool attached;
} ExecutorStartup;

static void executor_start(void *context) {
  ExecutorStartup *startup = context;
  startup->attached = executor_env(startup->vm) != NULL;
}
static void executor_started(void *context) {
  ExecutorStartup *startup = context;
  pthread_mutex_lock(&startup->mutex);
  startup->ready = true;
  pthread_cond_signal(&startup->condition);
  pthread_mutex_unlock(&startup->mutex);
}

JNIEXPORT jlong JNICALL
Java_ai_nuxie_sdk_runtime_NuxieRuntimeBridge_nativeExecutorNew(JNIEnv *env, jobject self) {
  (void)self;
  const NuxExecutor *executor = NULL;
  if (nux_executor_new(&executor) != NUX_STATUS_OK) return 0;
  ExecutorStartup startup = { .mutex = PTHREAD_MUTEX_INITIALIZER,
    .condition = PTHREAD_COND_INITIALIZER, .ready = false, .attached = false };
  (*env)->GetJavaVM(env, &startup.vm);
  NuxStatus status = nux_executor_submit(executor, &startup, executor_start, executor_started);
  if (status == NUX_STATUS_OK) {
    pthread_mutex_lock(&startup.mutex);
    while (!startup.ready) pthread_cond_wait(&startup.condition, &startup.mutex);
    pthread_mutex_unlock(&startup.mutex);
  }
  pthread_cond_destroy(&startup.condition);
  pthread_mutex_destroy(&startup.mutex);
  if (status != NUX_STATUS_OK || !startup.attached) {
    nux_executor_release(executor);
    return 0;
  }
  return (jlong)(uintptr_t)executor;
}
JNIEXPORT void JNICALL
Java_ai_nuxie_sdk_runtime_NuxieRuntimeBridge_nativeExecutorRetain(JNIEnv *env, jobject self, jlong handle) {
  (void)env; (void)self;
  nux_executor_retain((const NuxExecutor *)(uintptr_t)handle);
}
JNIEXPORT void JNICALL
Java_ai_nuxie_sdk_runtime_NuxieRuntimeBridge_nativeExecutorRelease(JNIEnv *env, jobject self, jlong handle) {
  (void)env; (void)self;
  nux_executor_release((const NuxExecutor *)(uintptr_t)handle);
}
JNIEXPORT jboolean JNICALL
Java_ai_nuxie_sdk_runtime_NuxieRuntimeBridge_nativeExecutorSubmit(JNIEnv *env, jobject self,
                                                               jlong handle, jobject callback) {
  (void)self;
  ExecutorJob *job = executor_job_new(env, callback);
  if (job == NULL) return JNI_FALSE;
  NuxStatus status = nux_executor_submit((const NuxExecutor *)(uintptr_t)handle,
                                       job, executor_job_run, executor_job_release);
  if (status != NUX_STATUS_OK) executor_job_reject(env, job);
  return status == NUX_STATUS_OK ? JNI_TRUE : JNI_FALSE;
}
JNIEXPORT jboolean JNICALL
Java_ai_nuxie_sdk_runtime_NuxieRuntimeBridge_nativeExecutorOnTerminated(JNIEnv *env, jobject self,
                                                                     jlong handle, jobject callback) {
  (void)self;
  ExecutorJob *job = executor_job_new(env, callback);
  if (job == NULL) return JNI_FALSE;
  NuxStatus status = nux_executor_on_terminated((const NuxExecutor *)(uintptr_t)handle,
                                               job, executor_job_run, executor_job_release);
  if (status != NUX_STATUS_OK) executor_job_reject(env, job);
  return status == NUX_STATUS_OK ? JNI_TRUE : JNI_FALSE;
}
JNIEXPORT void JNICALL
Java_ai_nuxie_sdk_runtime_NuxieRuntimeBridge_nativeExecutorClose(JNIEnv *env, jobject self, jlong handle) {
  (void)env; (void)self;
  nux_executor_close((const NuxExecutor *)(uintptr_t)handle);
}
JNIEXPORT jboolean JNICALL
Java_ai_nuxie_sdk_runtime_NuxieRuntimeBridge_nativeExecutorWait(JNIEnv *env, jobject self,
                                                             jlong handle, jlong timeout_ms) {
  (void)env; (void)self;
  bool terminated = false;
  NuxStatus status = nux_executor_wait((const NuxExecutor *)(uintptr_t)handle,
                                     timeout_ms < 0 ? 0 : (uint64_t)timeout_ms, &terminated);
  return status == NUX_STATUS_OK && terminated ? JNI_TRUE : JNI_FALSE;
}
