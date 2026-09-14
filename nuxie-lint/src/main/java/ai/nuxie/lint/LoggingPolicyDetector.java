package ai.nuxie.lint;

import com.android.tools.lint.detector.api.*;
import com.intellij.psi.PsiMethod;
import java.util.Arrays;
import java.util.List;
import org.jetbrains.uast.*;

/** SDK implementation guard; consumer applications retain their own logging policy. */
public final class LoggingPolicyDetector extends Detector implements SourceCodeScanner {
    public static final Issue ISSUE = Issue.create(
        "NuxieLoggingPolicy", "SDK diagnostics must use the privacy boundary",
        "Route SDK logs through NuxieLog. Tags, message structure and field names must be "
            + "compile-time strings; customer values belong in sensitive fields. Only the "
            + "central sink may call platform output directly.",
        Category.SECURITY, 9, Severity.FATAL,
        new Implementation(LoggingPolicyDetector.class, Scope.JAVA_FILE_SCOPE));

    @Override public List<String> getApplicableMethodNames() {
        return Arrays.asList("v", "d", "i", "w", "e", "wtf", "println", "print", "printStackTrace",
            "log", "sensitive", "status", "publicValue", "nativeWarning");
    }

    @Override public void visitMethodCall(JavaContext context, UCallExpression node, PsiMethod method) {
        if (context.isTestSource() || !"ai.nuxie.sdk".equals(context.getProject().getPackage())) return;
        UFile file = UastUtils.getContainingUFile(node);
        if (file == null || !file.getPackageName().startsWith("ai.nuxie.sdk")) return;
        UClass caller = UastUtils.getContainingUClass(node);
        String callerName = caller == null ? "" : caller.getQualifiedName();
        if ("ai.nuxie.sdk.logging.NuxieLogger".equals(callerName)
            || "ai.nuxie.sdk.logging.NuxieLogger.Field.Companion".equals(callerName)
            || "ai.nuxie.sdk.logging.NuxieLog".equals(callerName)) return;
        String owner = method.getContainingClass() == null ? "" : method.getContainingClass().getQualifiedName();
        if ((owner != null && owner.startsWith("kotlin.io.ConsoleKt")) || "android.util.Log".equals(owner) || "java.io.PrintStream".equals(owner)
            || ("printStackTrace".equals(method.getName()) && context.getEvaluator().isMemberInSubClassOf(method, "java.lang.Throwable", false))) {
            report(context, node, "Route SDK output through NuxieLog so level and redaction controls apply.");
            return;
        }
        if (owner == null || !owner.startsWith("ai.nuxie.sdk.logging.Nuxie")) return;
        String name = method.getName();
        if ("publicValue".equals(name) || "nativeWarning".equals(name) || "log".equals(name)) {
            report(context, node, "Use NuxieLog severity methods and sensitive/status fields; this entry point belongs to the logging boundary.");
            return;
        }
        int count = Arrays.asList("v", "d", "i", "w", "e").contains(name) ? 2 : 1;
        for (int i = 0; i < Math.min(count, method.getParameterList().getParametersCount()); i++) {
            final int index = i;
            UExpression argument = context.getEvaluator().computeArgumentMapping(node, method).entrySet().stream()
                .filter(entry -> entry.getValue().equals(method.getParameterList().getParameters()[index]))
                .map(entry -> entry.getKey()).findFirst().orElse(null);
            if (argument != null && !(ConstantEvaluator.evaluate(context, argument) instanceof String)) {
                report(context, argument, "Keep log structure constant; put dynamic values in NuxieLog.sensitive fields.");
            }
        }
    }

    private void report(JavaContext context, UElement node, String message) {
        context.report(ISSUE, node, context.getLocation(node), message);
    }
}
