package ai.nuxie.sdk.runtime

import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Test

/** Exercises the scripted import path on the device architecture. */
class ConfiguredImportSmokeTest {
    @Test
    fun publishedVideoCatalogCanBeInspectedWithoutStartingPlayback() {
        val bytes = InstrumentationRegistry.getInstrumentation().context.assets
            .open("video/greeting.nux").use { it.readBytes() }
        val runtime = NuxieRuntime.shared
        assertTrue(runtime.isAvailable)
        val video = checkNotNull(runtime.inspectFileAssets(bytes)).single { it.kind == FileAssetKind.VIDEO }
        assertEquals(0L, video.authoredId)
        assertEquals("video-clip-5afb4ece", video.name)
        assertEquals(4, video.requiredProviderFlags)
        assertEquals(false, video.isEmbedded)
    }

    @Test
    fun scriptedFileImportsThroughTheConfiguredRuntime() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val bytes = instrumentation.context.assets
            .open("scripted_interpolator.riv")
            .use { it.readBytes() }
        val runtime = NuxieRuntime.shared

        assertTrue("Engine library must load on the test device", runtime.isAvailable)

        val expectedAssets = checkNotNull(runtime.inspectFileAssets(bytes))
        val renderer = checkNotNull(runtime.newAndroidVulkanRenderer(100, 100))
        try {
            checkNotNull(
                runtime.importFile(
                    renderer = renderer,
                    bytes = bytes,
                    expectedAssets = expectedAssets,
                    externalAssets = emptyMap(),
                ),
            ).close()
        } finally {
            renderer.close()
        }
    }
}
