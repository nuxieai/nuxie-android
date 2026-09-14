package ai.nuxie.lint;

import com.android.tools.lint.checks.infrastructure.TestFile;
import com.android.tools.lint.checks.infrastructure.TestLintTask;
import org.junit.Test;
import static com.android.tools.lint.checks.infrastructure.TestFiles.*;

public final class LoggingPolicyDetectorTest {
    private final TestFile logger = kotlin("package ai.nuxie.sdk.logging\n"
        + "object NuxieLog { fun w(tag: String, message: String, error: Throwable? = null, vararg fields: Any) {}\n"
        + "fun sensitive(name: String, value: Any?): Any = value ?: 0\n"
        + "fun status(name: String, value: Number): Any = value\n"
        + "fun nativeWarning(operation: String, status: Int, code: ByteArray?, details: ByteArray?) {} }");
    private TestLintTask task(String namespace, TestFile source) {
        return TestLintTask.lint().allowMissingSdk().files(manifest().pkg(namespace), logger, source)
            .issues(LoggingPolicyDetector.ISSUE);
    }
    @Test public void rejectsDirectPlatformOutput() {
        task("ai.nuxie.sdk", kotlin("package ai.nuxie.sdk.events\nimport android.util.Log as Output\n"
            + "fun bad(secret: String) { Output.w(\"Nuxie\", secret); System.err.println(secret) }"))
            .run().expectErrorCount(2);
    }
    @Test public void rejectsKotlinConsoleAndThrowableOutput() {
        task("ai.nuxie.sdk", kotlin("package ai.nuxie.sdk.events\n"
            + "fun bad(secret: String, error: Throwable) { println(secret); print(secret); error.printStackTrace() }"))
            .run().expectErrorCount(3);
    }
    @Test public void permitsCentralPlatformSink() {
        task("ai.nuxie.sdk", kotlin("package ai.nuxie.sdk.logging\n"
            + "class NuxieLogger { fun output(message: String) { android.util.Log.println(5, \"Nuxie\", message) } }"))
            .run().expectClean();
    }
    @Test public void rejectsDynamicStructureIncludingNamedArguments() {
        task("ai.nuxie.sdk", kotlin("package ai.nuxie.sdk.events\nimport ai.nuxie.sdk.logging.NuxieLog as Log\n"
            + "fun bad(secret: String) { Log.w(message = secret, tag = \"Nuxie\"); "
            + "Log.w(secret, \"Diagnostic\"); Log.sensitive(secret, secret) }"))
            .run().expectErrorCount(3);
    }
    @Test public void acceptsConstantStructureAndSensitiveValues() {
        task("ai.nuxie.sdk", kotlin("package ai.nuxie.sdk.events\nimport ai.nuxie.sdk.logging.NuxieLog as Log\n"
            + "const val TAG = \"Nuxie\"\nfun good(secret: String) { "
            + "Log.w(TAG, \"Diagnostic\", null, Log.sensitive(\"customer\", secret), Log.status(\"status\", 503)) }"))
            .run().expectClean();
    }
    @Test public void ignoresConsumerLogging() {
        task("customer.app", kotlin("package customer.app\n"
            + "fun own(secret: String) { android.util.Log.w(\"Customer\", secret); System.err.println(secret) }"))
            .run().expectClean();
    }
    @Test public void rejectsNativeCallbackBypass() {
        task("ai.nuxie.sdk", kotlin("package ai.nuxie.sdk.events\nimport ai.nuxie.sdk.logging.NuxieLog\n"
            + "fun bad(secret: String) { NuxieLog.nativeWarning(secret, 0, null, null) }"))
            .run().expectErrorCount(1);
    }
}
