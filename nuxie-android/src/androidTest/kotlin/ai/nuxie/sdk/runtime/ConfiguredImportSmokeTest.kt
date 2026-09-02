package ai.nuxie.sdk.runtime

import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Test

/** Exercises the scripted import path on the device architecture. */
class ConfiguredImportSmokeTest {
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
