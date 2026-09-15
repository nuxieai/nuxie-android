# JNI_OnLoad resolves this policy boundary by class and static method name.
-keep class ai.nuxie.sdk.logging.NuxieLog {
    public static void nativeWarning(java.lang.String, int, byte[], byte[]);
}

# Native semantic snapshots construct immutable copied nodes through JNI.
-keep class ai.nuxie.sdk.runtime.NativeSemanticNode { *; }
