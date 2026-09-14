package ai.nuxie.lint;

import com.android.tools.lint.client.api.IssueRegistry;
import com.android.tools.lint.detector.api.ApiKt;
import com.android.tools.lint.detector.api.Issue;
import java.util.Collections;
import java.util.List;

public final class NuxieIssueRegistry extends IssueRegistry {
    @Override public int getApi() { return ApiKt.CURRENT_API; }
    @Override public int getMinApi() { return ApiKt.CURRENT_API; }
    @Override public List<Issue> getIssues() { return Collections.singletonList(TestStoreDetector.ISSUE); }
}
