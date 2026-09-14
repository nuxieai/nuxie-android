# JNI_OnLoad resolves this policy boundary by class and static method name.
-keep class ai.nuxie.sdk.logging.NuxieLog {
    public static void nativeWarning(java.lang.String, int, byte[], byte[]);
}
