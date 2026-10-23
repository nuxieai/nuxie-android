package ai.nuxie.sdk.presentation

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ExperienceLayerPresentationTest {
    @Test fun `fit content uses the implicit large height until page measurements are published`() {
        org.robolectric.shadows.ShadowLog.clear()
        ExperienceSurfaceHost.requireLayerPresentation(true)
        assertTrue(org.robolectric.shadows.ShadowLog.getLogsForTag("Nuxie").any {
            it.msg.contains("fit-content uses the large sheet height")
        })
        ExperienceSurfaceHost.requireLayerPresentation(false)
    }
}
