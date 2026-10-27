#ifndef NUXIE_JNI_ATTACHMENT_H
#define NUXIE_JNI_ATTACHMENT_H

#include <jni.h>
#include <stdbool.h>

typedef enum {
  NUXIE_JNI_ATTACH_NORMAL,
  NUXIE_JNI_ATTACH_DAEMON,
} NuxieJniAttachmentMode;

// One library-lifetime TLS key owns attachments made by either adapter.
// Optional callers can initialize it before installing their callback.
__attribute__((visibility("hidden")))
bool nuxie_jni_attachment_init(void);

// Reuse the calling thread's environment, or attach a detached native thread.
// Only attachments made here are detached by the TLS destructor at thread exit.
// Mode applies only to a new attachment. did_attach may be NULL; when supplied,
// it is true only after attachment and TLS ownership registration both succeed.
// Does not inspect, clear, or translate Java exceptions.
__attribute__((visibility("hidden")))
JNIEnv *nuxie_jni_env(JavaVM *vm, NuxieJniAttachmentMode mode, bool *did_attach);

#endif
