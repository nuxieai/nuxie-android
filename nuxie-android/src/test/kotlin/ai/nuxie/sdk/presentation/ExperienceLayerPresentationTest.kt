package ai.nuxie.sdk.presentation

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ExperienceLayerPresentationTest {
    @Test fun `fit content is refused until runtime can measure active row intrinsic height`() {
        val error = runCatching { ExperienceSurfaceHost.requireLayerPresentation(true) }.exceptionOrNull()
        assertTrue(error is IllegalArgumentException)
        assertTrue(error!!.message!!.contains("active Screen row"))
        ExperienceSurfaceHost.requireLayerPresentation(false)
    }
}
