# JNI_OnLoad resolves this policy boundary by class and static method name.
-keep class ai.nuxie.sdk.logging.NuxieLog {
    public static void nativeWarning(java.lang.String, int, byte[], byte[]);
}

# Native semantic snapshots construct immutable copied nodes through JNI.
-keep class ai.nuxie.sdk.runtime.NativeSemanticNode { *; }

# Settled text geometry is copied into these value objects by JNI.
-keep class ai.nuxie.sdk.runtime.NativeTextRunGeometry { *; }
-keep class ai.nuxie.sdk.runtime.NativeTextInputGeometry { *; }
-keep class ai.nuxie.sdk.runtime.NativeTextGeometryCapture { *; }

# Video callbacks copy borrowed runtime views into these JNI-constructed values.
-keep class ai.nuxie.sdk.runtime.NuxieVideoOccurrence { *; }
-keep class ai.nuxie.sdk.runtime.NuxieVideoAction { *; }
