package ai.nuxie.sdk.presentation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RenderCapabilityProbeTest {
    @Test
    fun successfulNativeRendererCreationIsFreedAndCached() {
        var created = 0
        var freed = 0
        val probe = RenderCapabilityProbe(
            libraryAvailable = { true },
            probeRenderer = {
                created += 1
                freed += 1
                true
            },
        )

        assertTrue(probe.isAvailable())
        assertTrue(probe.isAvailable())
        assertEquals(1, created)
        assertEquals(1, freed)
    }

    @Test
    fun loadedLibraryWithoutRendererCapabilityIsUnavailableAndCached() {
        var created = 0
        val probe = RenderCapabilityProbe(
            libraryAvailable = { true },
            probeRenderer = {
                created += 1
                false
            },
        )

        assertFalse(probe.isAvailable())
        assertFalse(probe.isAvailable())
        assertEquals(1, created)
    }
}
