#include "nuxie_jni_attachment.h"
#include <pthread.h>

static pthread_once_t attachment_once = PTHREAD_ONCE_INIT;
static pthread_key_t attachment_key;
static int attachment_key_status;

static void detach_thread(void *context) {
  JavaVM *vm = context;
  if (vm != NULL) (*vm)->DetachCurrentThread(vm);
}

static void make_attachment_key(void) {
  attachment_key_status = pthread_key_create(&attachment_key, detach_thread);
}

bool nuxie_jni_attachment_init(void) {
  return pthread_once(&attachment_once, make_attachment_key) == 0 &&
      attachment_key_status == 0;
}

JNIEnv *nuxie_jni_env(JavaVM *vm, NuxieJniAttachmentMode mode, bool *did_attach) {
  if (did_attach != NULL) *did_attach = false;
  if (vm == NULL) return NULL;
  JNIEnv *env = NULL;
  jint status = (*vm)->GetEnv(vm, (void **)&env, JNI_VERSION_1_6);
  if (status == JNI_OK) return env;
  if (status != JNI_EDETACHED || !nuxie_jni_attachment_init()) return NULL;
#ifdef __ANDROID__
  if (mode == NUXIE_JNI_ATTACH_DAEMON)
    status = (*vm)->AttachCurrentThreadAsDaemon(vm, &env, NULL);
  else
    status = (*vm)->AttachCurrentThread(vm, &env, NULL);
#else
  if (mode == NUXIE_JNI_ATTACH_DAEMON)
    status = (*vm)->AttachCurrentThreadAsDaemon(vm, (void **)&env, NULL);
  else
    status = (*vm)->AttachCurrentThread(vm, (void **)&env, NULL);
#endif
  if (status != JNI_OK) return NULL;
  if (pthread_setspecific(attachment_key, vm) != 0) {
    (*vm)->DetachCurrentThread(vm);
    return NULL;
  }
  if (did_attach != NULL) *did_attach = true;
  return env;
}
