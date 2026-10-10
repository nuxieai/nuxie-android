#include <assert.h>
#include <stdio.h>
#include <string.h>
#include "nux_capi.generated.h"
#include "nuxie_host_installs.h"

/* A field-aware JNI source exercises the production mapper without a runtime. */
struct Field { const char *name; const char *type; jobject object; jlong integer; jdouble number; };
struct Object { struct Field *fields; size_t count; jobject *items; const unsigned char *bytes; };
static jboolean exception_check(JNIEnv *env) { (void)env; return JNI_FALSE; }
static jclass object_class(JNIEnv *env, jobject object) { (void)env; return (jclass)object; }
static void delete_ref(JNIEnv *env, jobject object) { (void)env; (void)object; }
static jfieldID field_id(JNIEnv *env, jclass cls, const char *name, const char *type) {
  (void)env;
  struct Field *field = ((struct Object *)cls)->fields;
  for (; field->name != NULL; ++field) {
    if (strcmp(field->name, name) == 0) { assert(strcmp(field->type, type) == 0); return (jfieldID)field; }
  }
  assert(!"Unexpected field");
  return NULL;
}
static jobject object_field(JNIEnv *env, jobject object, jfieldID id) {
  (void)env; (void)object; return ((struct Field *)id)->object;
}
static jint int_field(JNIEnv *env, jobject object, jfieldID id) {
  (void)env; (void)object; return (jint)((struct Field *)id)->integer;
}
static jlong long_field(JNIEnv *env, jobject object, jfieldID id) {
  (void)env; (void)object; return ((struct Field *)id)->integer;
}
static jdouble double_field(JNIEnv *env, jobject object, jfieldID id) {
  (void)env; (void)object; return ((struct Field *)id)->number;
}
static jsize array_length(JNIEnv *env, jarray array) { (void)env; return (jsize)((struct Object *)array)->count; }
static jobject array_item(JNIEnv *env, jobjectArray array, jsize index) {
  (void)env; assert(index >= 0 && (size_t)index < ((struct Object *)array)->count);
  return ((struct Object *)array)->items[index];
}
static void byte_region(JNIEnv *env, jbyteArray array, jsize start, jsize count, jbyte *out) {
  (void)env; assert(start >= 0 && count >= 0 && (size_t)(start + count) <= ((struct Object *)array)->count);
  memcpy(out, ((struct Object *)array)->bytes + start, (size_t)count);
}
static const struct JNINativeInterface_ functions = {
  .ExceptionCheck = exception_check, .GetObjectClass = object_class, .DeleteLocalRef = delete_ref,
  .GetFieldID = field_id, .GetObjectField = object_field, .GetIntField = int_field,
  .GetLongField = long_field, .GetDoubleField = double_field, .GetArrayLength = array_length,
  .GetObjectArrayElement = array_item, .GetByteArrayRegion = byte_region,
};
#define BYTES(name, text) struct Object name = {.count = sizeof(text) - 1, .bytes = (const unsigned char *)text}
#define STRING(name, value) {name, "[B", (jobject)&value, 0, 0}
static void string_is(struct NuxStringView view, const char *bytes, size_t count) {
  assert(view.len == count);
  if (count == 0) assert(view.data == NULL);
  else assert(memcmp(view.data, bytes, count) == 0);
}
int main(void) {
  JNIEnv env = &functions;
  struct InstallStorage storage = {0};
  struct Object empty = {0};
  size_t count = 99;
  assert(install_ValueMarker(&env, &storage, (jobjectArray)&empty, &count) == NULL && count == 0);
  assert(install_ValueRule(&env, &storage, (jobjectArray)&empty, &count) == NULL && count == 0);
  assert(install_RuleGroup(&env, &storage, (jobjectArray)&empty, &count) == NULL && count == 0);
  BYTES(utf8, "\xc3\xa9\0"); BYTES(m, "M"); BYTES(p, "P"); BYTES(v, "v"); BYTES(q, "Q");
  BYTES(t, "T"); BYTES(c, "C"); BYTES(b, "b"); BYTES(a, "a"); BYTES(n, "N");
  struct Field marker1[] = {STRING("model", utf8), STRING("value", v), STRING("marker", m), {0}};
  struct Field marker2[] = {STRING("model", n), STRING("value", empty), STRING("marker", q), {0}};
  struct Object marker_objects[] = {{.fields = marker1}, {.fields = marker2}};
  jobject marker_items[] = {(jobject)&marker_objects[0], (jobject)&marker_objects[1]};
  struct Object marker_array = {.count = 2, .items = marker_items};
  struct NuxValueMarker *markers = install_ValueMarker(&env, &storage, (jobjectArray)&marker_array, &count);
  assert(count == 2 && !storage.failed);
  string_is(markers[0].model, "\xc3\xa9\0", 3); string_is(markers[0].value, "v", 1); string_is(markers[0].marker, "M", 1);
  string_is(markers[1].model, "N", 1); string_is(markers[1].value, "", 0); string_is(markers[1].marker, "Q", 1);
  jobject allowed_items[] = {(jobject)&b, (jobject)&a, (jobject)&b, (jobject)&empty};
  struct Object allowed = {.count = 4, .items = allowed_items};
  struct Field rule_fields[] = {
    STRING("model", m), STRING("property", p), {"kind", "I", NULL, 7, 0}, {"mode", "I", NULL, 1, 0},
    {"numberBound", "D", NULL, 0, 2.5}, STRING("text", t), {"values", "[[B", (jobject)&allowed, 0, 0},
    STRING("pickedProperty", q), {"boundFlags", "I", NULL, 3, 0}, {"minimum", "J", NULL, 2, 0},
    {"maximum", "J", NULL, 9, 0}, STRING("code", c), STRING("message", utf8), {0},
  };
  struct Object rule_object = {.fields = rule_fields};
  jobject rule_items[] = {(jobject)&rule_object};
  struct Object rule_array = {.count = 1, .items = rule_items};
  struct NuxValueRule *rules = install_ValueRule(&env, &storage, (jobjectArray)&rule_array, &count);
  assert(count == 1 && !storage.failed);
  string_is(rules[0].model, "M", 1); string_is(rules[0].property, "P", 1);
  assert(rules[0].kind == 7 && rules[0].mode == 1 && rules[0].number_bound == 2.5);
  string_is(rules[0].text, "T", 1); string_is(rules[0].picked_property, "Q", 1);
  assert(rules[0].bound_flags == 3 && rules[0].minimum == 2 && rules[0].maximum == 9);
  string_is(rules[0].code, "C", 1); string_is(rules[0].message, "\xc3\xa9\0", 3);
  assert(rules[0].value_count == 4);
  string_is(rules[0].values[0], "b", 1); string_is(rules[0].values[1], "a", 1);
  string_is(rules[0].values[2], "b", 1); string_is(rules[0].values[3], "", 0);
  rule_fields[2].integer = 99; rule_fields[3].integer = 88; rule_fields[8].integer = 77;
  rule_fields[6].object = (jobject)&empty;
  rules = install_ValueRule(&env, &storage, (jobjectArray)&rule_array, &count);
  assert(rules[0].kind == 99 && rules[0].mode == 88 && rules[0].bound_flags == 77);
  assert(rules[0].values == NULL && rules[0].value_count == 0);
  BYTES(ep, "e/p"); BYTES(eq, "e/q"); BYTES(i, "I"); BYTES(j, "J"); BYTES(d, "d");
  struct Field member1[] = {STRING("property", p), STRING("errorsPath", ep), STRING("itemModel", i), STRING("codeProperty", c), STRING("messageProperty", m), {0}};
  struct Field member2[] = {STRING("property", q), STRING("errorsPath", eq), STRING("itemModel", j), STRING("codeProperty", d), STRING("messageProperty", n), {0}};
  struct Object member_objects[] = {{.fields = member1}, {.fields = member2}};
  jobject member_items[] = {(jobject)&member_objects[0], (jobject)&member_objects[1]};
  struct Object members = {.count = 2, .items = member_items};
  struct Field group1[] = {STRING("model", m), STRING("valid", v), {"members", "[Lai/nuxie/sdk/runtime/NativeRuleGroupMember;", (jobject)&members, 0, 0}, {0}};
  struct Field group2[] = {STRING("model", n), STRING("valid", q), {"members", "[Lai/nuxie/sdk/runtime/NativeRuleGroupMember;", (jobject)&empty, 0, 0}, {0}};
  struct Object group_objects[] = {{.fields = group1}, {.fields = group2}};
  jobject group_items[] = {(jobject)&group_objects[0], (jobject)&group_objects[1]};
  struct Object groups_array = {.count = 2, .items = group_items};
  struct NuxRuleGroup *groups = install_RuleGroup(&env, &storage, (jobjectArray)&groups_array, &count);
  assert(count == 2 && !storage.failed);
  string_is(groups[0].model, "M", 1); string_is(groups[0].valid, "v", 1);
  assert(groups[0].member_count == 2);
  string_is(groups[0].members[0].property, "P", 1); string_is(groups[0].members[1].property, "Q", 1);
  string_is(groups[0].members[0].errors_path, "e/p", 3); string_is(groups[0].members[1].errors_path, "e/q", 3);
  string_is(groups[0].members[0].item_model, "I", 1); string_is(groups[0].members[1].item_model, "J", 1);
  string_is(groups[0].members[0].code_property, "C", 1); string_is(groups[0].members[1].code_property, "d", 1);
  string_is(groups[0].members[0].message_property, "M", 1); string_is(groups[0].members[1].message_property, "N", 1);
  string_is(groups[1].model, "N", 1); string_is(groups[1].valid, "Q", 1);
  assert(groups[1].members == NULL && groups[1].member_count == 0);
  install_storage_free(&storage);
  assert(storage.first == NULL);
  puts("host install mapping: all fields, UTF-8, order and empty tables passed");
  return 0;
}
