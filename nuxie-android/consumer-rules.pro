# JNI_OnLoad resolves this policy boundary by class and static method name.
-keep class ai.nuxie.sdk.logging.NuxieLog {
    public static void nativeWarning(java.lang.String, int, byte[], byte[]);
    public static void nativeFontException(java.lang.Throwable);
}

# Asset inspection constructs these records through JNI, outside R8's call graph.
-keep class ai.nuxie.sdk.runtime.NativeExpectedFileAsset {
    public <init>(int, int, boolean, long, java.lang.String, java.lang.String, boolean, boolean, int);
}

# Native CPU frame readback also constructs this value through JNI.
-keep class ai.nuxie.sdk.runtime.NuxieCpuFrame {
    public <init>(int, int, byte[]);
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

# Android restores this platform Fragment through its public no-argument constructor.
-keep,allowobfuscation class ai.nuxie.sdk.core.NuxieResumedActivityProbe {
    public <init>();
}

# Focus inputs are read by field name for the synchronous JNI step.
-keep class ai.nuxie.sdk.runtime.NativeFocusInput { *; }

# Native table operands are read by field name during synchronous JNI installation.
-keep class ai.nuxie.sdk.runtime.NativeValueMarker { *; }
-keep class ai.nuxie.sdk.runtime.NativeValueRule { *; }
-keep class ai.nuxie.sdk.runtime.NativeRuleGroupMember { *; }
-keep class ai.nuxie.sdk.runtime.NativeRuleGroup { *; }

# The process font hook resolves this static callback before native shaping threads start.
-keep class ai.nuxie.sdk.runtime.SystemFontFallback {
    public static long request(int, int, int);
    public static void warm();
    private native long[] nativeProbe(java.lang.String, int);
    private native boolean nativeCovers(long, int);
    private native boolean nativeFontCovers(long, int);
    private native long[] nativeMakeFont(long);
    private native long[] nativeWithWeight(long, int);
    private native boolean nativeTestingEnabled();
}

# The executor resolves these callbacks on their concrete object through JNI.
-keepclassmembers class ai.nuxie.sdk.runtime.NativeRuntimeLane$Job {
    public void run();
    public void close();
}
