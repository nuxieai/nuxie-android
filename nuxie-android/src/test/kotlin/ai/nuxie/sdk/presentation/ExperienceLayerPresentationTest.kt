package ai.nuxie.sdk.presentation

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ExperienceLayerPresentationTest {
    @Test fun `a fit-content page opens at the large height and says so in iOS's words`() {
        org.robolectric.shadows.ShadowLog.clear()
        ExperienceSurfaceHost.requireLayerPresentation(true)
        assertEquals(1, org.robolectric.shadows.ShadowLog.getLogsForTag("Nuxie").count {
            it.msg.contains("ExperienceInteractivePage: page content height is unavailable; fit-content defaults to large")
        })
        org.robolectric.shadows.ShadowLog.clear()
        ExperienceSurfaceHost.requireLayerPresentation(false)
        assertTrue(org.robolectric.shadows.ShadowLog.getLogsForTag("Nuxie").isEmpty())
    }
}
