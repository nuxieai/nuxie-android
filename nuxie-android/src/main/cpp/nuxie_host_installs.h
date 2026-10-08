#ifndef NUXIE_HOST_INSTALLS_H
#define NUXIE_HOST_INSTALLS_H

#include <jni.h>
#include <stdlib.h>
#include "nux_capi.generated.h"

/* All borrowed strings and arrays live until the synchronous install returns. */
struct InstallAllocation { void *value; struct InstallAllocation *next; };
struct InstallStorage { struct InstallAllocation *first; int failed; };

static void *install_array(struct InstallStorage *storage, size_t count, size_t size) {
  if (count == 0 || storage->failed) return NULL;
  struct InstallAllocation *item = malloc(sizeof(*item));
  if (item == NULL) { storage->failed = 1; return NULL; }
  item->value = calloc(count, size);
  if (item->value == NULL) { free(item); storage->failed = 1; return NULL; }
  item->next = storage->first;
  storage->first = item;
  return item->value;
}

static void install_storage_free(struct InstallStorage *storage) {
  while (storage->first != NULL) {
    struct InstallAllocation *item = storage->first;
    storage->first = item->next;
    free(item->value);
    free(item);
  }
}

static jfieldID install_field(JNIEnv *env, jobject object, const char *name, const char *type) {
  if (object == NULL || (*env)->ExceptionCheck(env)) return NULL;
  jclass cls = (*env)->GetObjectClass(env, object);
  if (cls == NULL) return NULL;
  jfieldID field = (*env)->GetFieldID(env, cls, name, type);
  (*env)->DeleteLocalRef(env, cls);
  return field;
}

static jobject install_object(JNIEnv *env, struct InstallStorage *storage,
                              jobject object, const char *name, const char *type) {
  jfieldID field = install_field(env, object, name, type);
  jobject value = field == NULL ? NULL : (*env)->GetObjectField(env, object, field);
  if (value == NULL || (*env)->ExceptionCheck(env)) storage->failed = 1;
  return value;
}

static struct NuxStringView install_bytes(JNIEnv *env, struct InstallStorage *storage, jbyteArray bytes) {
  struct NuxStringView value = {0};
  if (bytes == NULL || (*env)->ExceptionCheck(env)) { storage->failed = 1; return value; }
  value.len = (size_t)(*env)->GetArrayLength(env, bytes);
  value.data = install_array(storage, value.len, 1);
  if (value.data != NULL) (*env)->GetByteArrayRegion(env, bytes, 0, (jsize)value.len, (jbyte *)value.data);
  if ((*env)->ExceptionCheck(env)) storage->failed = 1;
  return value;
}

static struct NuxStringView install_string(JNIEnv *env, struct InstallStorage *storage,
                                          jobject object, const char *name) {
  jbyteArray bytes = install_object(env, storage, object, name, "[B");
  struct NuxStringView value = install_bytes(env, storage, bytes);
  if (bytes != NULL) (*env)->DeleteLocalRef(env, bytes);
  return value;
}

static jint install_int(JNIEnv *env, struct InstallStorage *storage, jobject object, const char *name) {
  jfieldID field = install_field(env, object, name, "I");
  if (field == NULL) { storage->failed = 1; return 0; }
  jint value = (*env)->GetIntField(env, object, field);
  if ((*env)->ExceptionCheck(env)) storage->failed = 1;
  return value;
}

static jlong install_long(JNIEnv *env, struct InstallStorage *storage, jobject object, const char *name) {
  jfieldID field = install_field(env, object, name, "J");
  if (field == NULL) { storage->failed = 1; return 0; }
  jlong value = (*env)->GetLongField(env, object, field);
  if ((*env)->ExceptionCheck(env)) storage->failed = 1;
  return value;
}

static jdouble install_double(JNIEnv *env, struct InstallStorage *storage, jobject object, const char *name) {
  jfieldID field = install_field(env, object, name, "D");
  if (field == NULL) { storage->failed = 1; return 0; }
  jdouble value = (*env)->GetDoubleField(env, object, field);
  if ((*env)->ExceptionCheck(env)) storage->failed = 1;
  return value;
}

static struct NuxStringView *install_strings(JNIEnv *env, struct InstallStorage *storage,
                                            jobjectArray array, size_t *count) {
  *count = 0;
  if (array == NULL || (*env)->ExceptionCheck(env)) { storage->failed = 1; return NULL; }
  *count = (size_t)(*env)->GetArrayLength(env, array);
  struct NuxStringView *values = install_array(storage, *count, sizeof(*values));
  for (size_t i = 0; i < *count && !storage->failed; ++i) {
    jbyteArray bytes = (*env)->GetObjectArrayElement(env, array, (jsize)i);
    values[i] = install_bytes(env, storage, bytes);
    if (bytes != NULL) (*env)->DeleteLocalRef(env, bytes);
  }
  return values;
}

static struct NuxValueMarker *install_ValueMarker(JNIEnv *env, struct InstallStorage *storage, jobjectArray array, size_t *count) {
  *count = 0;
  if (array == NULL || (*env)->ExceptionCheck(env)) { storage->failed = 1; return NULL; }
  *count = (size_t)(*env)->GetArrayLength(env, array);
  struct NuxValueMarker *values = install_array(storage, *count, sizeof(*values));
  for (size_t i = 0; i < *count && !storage->failed; ++i) {
    jobject entry = (*env)->GetObjectArrayElement(env, array, (jsize)i);
    values[i].model = install_string(env, storage, entry, "model");
    values[i].value = install_string(env, storage, entry, "value");
    values[i].marker = install_string(env, storage, entry, "marker");
    if (entry != NULL) (*env)->DeleteLocalRef(env, entry);
  }
  return values;
}

static struct NuxValueRule *install_ValueRule(JNIEnv *env, struct InstallStorage *storage, jobjectArray array, size_t *count) {
  *count = 0;
  if (array == NULL || (*env)->ExceptionCheck(env)) { storage->failed = 1; return NULL; }
  *count = (size_t)(*env)->GetArrayLength(env, array);
  struct NuxValueRule *values = install_array(storage, *count, sizeof(*values));
  for (size_t i = 0; i < *count && !storage->failed; ++i) {
    jobject entry = (*env)->GetObjectArrayElement(env, array, (jsize)i);
    values[i].model = install_string(env, storage, entry, "model");
    values[i].property = install_string(env, storage, entry, "property");
    values[i].kind = install_int(env, storage, entry, "kind");
    values[i].mode = install_int(env, storage, entry, "mode");
    values[i].number_bound = install_double(env, storage, entry, "numberBound");
    values[i].text = install_string(env, storage, entry, "text");
    values[i].picked_property = install_string(env, storage, entry, "pickedProperty");
    values[i].bound_flags = install_int(env, storage, entry, "boundFlags");
    values[i].minimum = install_long(env, storage, entry, "minimum");
    values[i].maximum = install_long(env, storage, entry, "maximum");
    values[i].code = install_string(env, storage, entry, "code");
    values[i].message = install_string(env, storage, entry, "message");
    jobjectArray allowed = install_object(env, storage, entry, "values", "[[B");
    values[i].values = install_strings(env, storage, allowed, &values[i].value_count);
    if (allowed != NULL) (*env)->DeleteLocalRef(env, allowed);
    if (entry != NULL) (*env)->DeleteLocalRef(env, entry);
  }
  return values;
}

static struct NuxRuleGroupMember *install_RuleGroupMember(JNIEnv *env, struct InstallStorage *storage, jobjectArray array, size_t *count) {
  *count = 0;
  if (array == NULL || (*env)->ExceptionCheck(env)) { storage->failed = 1; return NULL; }
  *count = (size_t)(*env)->GetArrayLength(env, array);
  struct NuxRuleGroupMember *values = install_array(storage, *count, sizeof(*values));
  for (size_t i = 0; i < *count && !storage->failed; ++i) {
    jobject entry = (*env)->GetObjectArrayElement(env, array, (jsize)i);
    values[i].property = install_string(env, storage, entry, "property");
    values[i].errors_path = install_string(env, storage, entry, "errorsPath");
    values[i].item_model = install_string(env, storage, entry, "itemModel");
    values[i].code_property = install_string(env, storage, entry, "codeProperty");
    values[i].message_property = install_string(env, storage, entry, "messageProperty");
    if (entry != NULL) (*env)->DeleteLocalRef(env, entry);
  }
  return values;
}

static struct NuxRuleGroup *install_RuleGroup(JNIEnv *env, struct InstallStorage *storage, jobjectArray array, size_t *count) {
  *count = 0;
  if (array == NULL || (*env)->ExceptionCheck(env)) { storage->failed = 1; return NULL; }
  *count = (size_t)(*env)->GetArrayLength(env, array);
  struct NuxRuleGroup *values = install_array(storage, *count, sizeof(*values));
  for (size_t i = 0; i < *count && !storage->failed; ++i) {
    jobject entry = (*env)->GetObjectArrayElement(env, array, (jsize)i);
    values[i].model = install_string(env, storage, entry, "model");
    values[i].valid = install_string(env, storage, entry, "valid");
    jobjectArray members = install_object(env, storage, entry, "members", "[Lai/nuxie/sdk/runtime/NativeRuleGroupMember;");
    values[i].members = install_RuleGroupMember(env, storage, members, &values[i].member_count);
    if (members != NULL) (*env)->DeleteLocalRef(env, members);
    if (entry != NULL) (*env)->DeleteLocalRef(env, entry);
  }
  return values;
}

#endif
