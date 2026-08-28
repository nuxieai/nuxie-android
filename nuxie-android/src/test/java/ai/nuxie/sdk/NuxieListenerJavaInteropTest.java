package ai.nuxie.sdk;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import ai.nuxie.sdk.features.FeatureAccess;
import org.junit.Test;

public final class NuxieListenerJavaInteropTest {
    @Test
    public void javaImplementerCanLoadAfterImplementingOnlyOneMethod() throws NoSuchMethodException {
        NuxieListener listener = new OneMethodListener();

        assertNotNull(listener);
        assertTrue(
                NuxieListener.class
                        .getMethod("onActivityEmitted", Nuxie.class, NuxieActivityInfo.class)
                        .isDefault());
        assertTrue(
                NuxieListener.class
                        .getMethod(
                                "featureAccessDidChange",
                                String.class,
                                FeatureAccess.class,
                                FeatureAccess.class)
                        .isDefault());
    }

    private static final class OneMethodListener implements NuxieListener {
        @Override
        public void onAppActionRequested(Nuxie sdk, AppAction action) {}
    }
}
