package ai.nuxie.lint;

import com.android.tools.lint.detector.api.*;
import com.android.tools.lint.client.api.UElementHandler;
import com.intellij.psi.PsiField;
import com.intellij.psi.PsiMethod;
import java.util.Collections;
import java.util.List;
import org.jetbrains.uast.*;

/** Require an expression whose release value is false at the SDK configuration boundary. */
public final class TestStoreDetector extends Detector implements SourceCodeScanner {
    public static final Issue ISSUE = Issue.create(
        "NuxieTestStoreRelease", "Test Store must be disabled in release builds",
        "Gate Test Store with the app's BuildConfig.DEBUG. Unconditional or runtime-only flags "
            + "can ship a no-charge checkout configuration. The SDK also rejects enabled Test Store "
            + "in non-debuggable applications at setup.",
        Category.CORRECTNESS, 9, Severity.FATAL,
        new Implementation(TestStoreDetector.class, Scope.JAVA_FILE_SCOPE));

    @Override public List<String> getApplicableMethodNames() {
        return Collections.singletonList("setTestStoreEnabled");
    }

    @Override public void visitMethodCall(JavaContext context, UCallExpression node, PsiMethod method) {
        if (context.isTestSource() || !context.getEvaluator().isMemberInClass(method, "ai.nuxie.sdk.NuxieConfiguration")) return;
        if (node.getValueArguments().size() != 1) return;
        UExpression value = node.getValueArguments().get(0);
        if (releaseDisabled(context, value)) return;
        context.report(ISSUE, node, context.getLocation(value),
            "Gate Test Store with BuildConfig.DEBUG (or set it to false) before shipping this configuration.");
    }

    // Kotlin properties (including compiled SDK fields) are not always dispatched as setter calls.
    @Override public List<Class<? extends UElement>> getApplicableUastTypes() {
        return Collections.singletonList(UBinaryExpression.class);
    }

    @Override public UElementHandler createUastHandler(JavaContext context) {
        return new UElementHandler() {
            @Override public void visitBinaryExpression(UBinaryExpression node) {
                if (context.isTestSource() || node.getOperator() != UastBinaryOperator.ASSIGN) return;
                UExpression target = node.getLeftOperand();
                while (target instanceof UParenthesizedExpression) target = ((UParenthesizedExpression) target).getExpression();
                if (target instanceof UQualifiedReferenceExpression) target = ((UQualifiedReferenceExpression) target).getSelector();
                if (!(target instanceof UReferenceExpression)) return;
                Object resolved = ((UReferenceExpression) target).resolve();
                if (resolved instanceof PsiField) {
                    PsiField field = (PsiField) resolved;
                    if (!"testStoreEnabled".equals(field.getName()) || field.getContainingClass() == null
                        || !"ai.nuxie.sdk.NuxieConfiguration".equals(field.getContainingClass().getQualifiedName())) return;
                    UExpression value = node.getRightOperand();
                    if (!releaseDisabled(context, value)) context.report(ISSUE, node, context.getLocation(value),
                        "Gate Test Store with BuildConfig.DEBUG (or set it to false) before shipping this configuration.");
                    return;
                }
                if (!(resolved instanceof PsiMethod)) return;
                PsiMethod method = (PsiMethod) resolved;
                if (!("getTestStoreEnabled".equals(method.getName()) || "setTestStoreEnabled".equals(method.getName()))
                    || !context.getEvaluator().isMemberInClass(method, "ai.nuxie.sdk.NuxieConfiguration")) return;
                UExpression value = node.getRightOperand();
                if (!releaseDisabled(context, value)) context.report(ISSUE, node, context.getLocation(value),
                    "Gate Test Store with BuildConfig.DEBUG (or set it to false) before shipping this configuration.");
            }
        };
    }

    private boolean releaseDisabled(JavaContext context, UExpression value) {
        while (value instanceof UParenthesizedExpression) value = ((UParenthesizedExpression) value).getExpression();
        if (value instanceof ULiteralExpression && Boolean.FALSE.equals(((ULiteralExpression) value).getValue())) return true;
        if (value instanceof UBinaryExpression) {
            UBinaryExpression binary = (UBinaryExpression) value;
            if (binary.getOperator() == UastBinaryOperator.LOGICAL_AND) {
                return releaseDisabled(context, binary.getLeftOperand()) || releaseDisabled(context, binary.getRightOperand());
            }
        }
        UExpression reference = value instanceof UQualifiedReferenceExpression
            ? ((UQualifiedReferenceExpression) value).getSelector() : value;
        if (!(reference instanceof UReferenceExpression)) return false;
        Object resolved = ((UReferenceExpression) reference).resolve();
        if (!(resolved instanceof PsiField)) return false;
        PsiField field = (PsiField) resolved;
        return "DEBUG".equals(field.getName()) && field.getContainingClass() != null
            && (context.getProject().getPackage() + ".BuildConfig").equals(field.getContainingClass().getQualifiedName());
    }
}
