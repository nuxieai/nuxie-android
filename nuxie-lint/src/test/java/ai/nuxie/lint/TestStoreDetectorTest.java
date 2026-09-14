package ai.nuxie.lint;

import com.android.tools.lint.checks.infrastructure.TestFile;
import com.android.tools.lint.checks.infrastructure.TestLintTask;
import org.junit.Test;
import static com.android.tools.lint.checks.infrastructure.TestFiles.*;

public final class TestStoreDetectorTest {
    private final TestFile configuration = kotlin("package ai.nuxie.sdk\n"
        + "class NuxieConfiguration { var testStoreEnabled: Boolean = false }");
    private final TestFile buildConfig = java(""
        + "package test.pkg; public final class BuildConfig { public static final boolean DEBUG = true; }");
    private TestLintTask task(TestFile source) {
        return TestLintTask.lint().allowMissingSdk().files(
            manifest().pkg("test.pkg"), configuration, buildConfig, source
        ).issues(TestStoreDetector.ISSUE);
    }

    @Test public void rejectsJavaLiteral() {
        task(java("package test.pkg; import ai.nuxie.sdk.NuxieConfiguration; "
            + "class Usage { void setup(NuxieConfiguration c) { c.setTestStoreEnabled(true); } }"))
            .run().expectErrorCount(1);
    }

    @Test public void rejectsKotlinPropertyAssignment() {
        task(kotlin("package test.pkg\nimport ai.nuxie.sdk.NuxieConfiguration\n"
            + "fun setup() { NuxieConfiguration().apply { testStoreEnabled = true } }"))
            .run().expectErrorCount(1);
    }

    @Test public void rejectsRuntimeOnlyAndDisjunctionAndNegatedDebug() {
        task(kotlin("package test.pkg\nimport ai.nuxie.sdk.NuxieConfiguration\n"
            + "fun setup(c: NuxieConfiguration, flag: Boolean) {\n"
            + " c.testStoreEnabled = flag\n"
            + " c.testStoreEnabled = BuildConfig.DEBUG || flag\n"
            + " c.testStoreEnabled = !BuildConfig.DEBUG\n} "))
            .run().expectErrorCount(3);
    }

    @Test public void acceptsFalseAndDebugGates() {
        task(kotlin("package test.pkg\nimport ai.nuxie.sdk.NuxieConfiguration\n"
            + "fun setup(c: NuxieConfiguration, flag: Boolean) {\n"
            + " c.testStoreEnabled = false\n"
            + " c.testStoreEnabled = BuildConfig.DEBUG\n"
            + " c.testStoreEnabled = BuildConfig.DEBUG && flag\n"
            + " c.testStoreEnabled = flag && (BuildConfig.DEBUG)\n} "))
            .run().expectClean();
    }

    @Test public void rejectsForeignBuildConfig() {
        task(kotlin("package test.pkg\nimport ai.nuxie.sdk.NuxieConfiguration\n"
            + "class Foreign { object BuildConfig { const val DEBUG = true } }\n"
            + "fun setup(c: NuxieConfiguration) { c.testStoreEnabled = Foreign.BuildConfig.DEBUG }"))
            .run().expectErrorCount(1);
    }

    @Test public void acceptsJavaDebugGate() {
        task(java("package test.pkg; import ai.nuxie.sdk.NuxieConfiguration; "
            + "class Usage { void setup(NuxieConfiguration c, boolean flag) { "
            + "c.setTestStoreEnabled(BuildConfig.DEBUG && flag); } }"))
            .run().expectClean();
    }

    @Test public void ignoresUnrelatedSetter() {
        task(kotlin("package test.pkg\nclass Other { var testStoreEnabled = false }\n"
            + "fun setup(c: Other) { c.testStoreEnabled = true }"))
            .run().expectClean();
    }
}
