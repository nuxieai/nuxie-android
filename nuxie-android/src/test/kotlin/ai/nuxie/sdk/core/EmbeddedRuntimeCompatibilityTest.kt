package ai.nuxie.sdk.core

import ai.nuxie.sdk.experiences.SemanticVersion
import ai.nuxie.sdk.runtime.NuxieEmbeddedRuntimeCompatibility
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EmbeddedRuntimeCompatibilityTest {
    @Test
    fun `shipped sdk version satisfies the first stable release minimum`() {
        val current = requireNotNull(
            SemanticVersion.parse(requireNotNull(supportedRuntimeForEmbeddedRuntime("native")).currentSdkVersion),
        )
        val minimum = requireNotNull(SemanticVersion.parse("0.1.0"))

        assertTrue(current >= minimum)
    }

    @Test
    fun `release admission uses the cross-platform compatibility revision not native provenance`() {
        val nativeSourceRevision = "native-runtime-build-revision"

        val supported = supportedRuntimeForEmbeddedRuntime(nativeSourceRevision)

        assertEquals(
            setOf(NuxieEmbeddedRuntimeCompatibility.SOURCE_REVISION),
            supported?.supportedRuntimeRevisions,
        )
        assertFalse(supported?.supportedRuntimeRevisions.orEmpty().contains(nativeSourceRevision))
    }

    @Test
    fun `release admission advertises current rive scene format`() {
        val supported = requireNotNull(supportedRuntimeForEmbeddedRuntime("native"))

        assertEquals(7, supported.sceneFormatMajor)
        assertEquals(3, supported.sceneFormatMinor)
    }

    @Test
    fun `missing native runtime provenance keeps release admission closed`() {
        assertNull(supportedRuntimeForEmbeddedRuntime(null))
        assertNull(supportedRuntimeForEmbeddedRuntime(""))
        assertNull(supportedRuntimeForEmbeddedRuntime("   "))
    }
}
