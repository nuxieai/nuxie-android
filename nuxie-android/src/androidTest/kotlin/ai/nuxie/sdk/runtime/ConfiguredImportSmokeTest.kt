package ai.nuxie.sdk.runtime

import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Test

/** Exercises the scripted import path on the device architecture. */
class ConfiguredImportSmokeTest {
    @Test
    fun videoCommandsAndDecoderActionsCrossJni() {
        val bytes = InstrumentationRegistry.getInstrumentation().context.assets
            .open("video/greeting.nux").use { it.readBytes() }
        val runtime = NuxieRuntime.shared
        val catalog = checkNotNull(runtime.inspectFileAssets(bytes))
        val renderer = checkNotNull(runtime.newAndroidVulkanRenderer(320, 640))
        try {
            val file = checkNotNull(runtime.importFile(renderer, bytes, catalog, videoEnabled = true))
            try {
                val artboard = checkNotNull(file.newArtboard("Video Frame"))
                try {
                    val player = checkNotNull(artboard.newPlayer())
                    try {
                        val video = player.videos().single()
                        assertEquals("asset:clip", video.sourceKey)
                        assertEquals(false, video.embedded)
                        val ready = player.videoStep(video.componentId, 1, video.generation, 2.022)
                        assertTrue("Autoplay must produce a decoder play action", ready.any { it.kind == 0 })
                        player.videoCommand(video.componentId, 1)
                        player.videoStep(video.componentId, 0, video.generation)
                        assertEquals(false, player.videos().single().wantsPlay)
                        assertEquals(3, player.videos().single().state)
                        player.videoCommand(video.componentId, 2, 1.0)
                        val seek = player.videoStep(video.componentId, 0, video.generation).single { it.kind == 2 }
                        assertEquals(1.0, seek.value, 0.001)
                        assertTrue(seek.generation > video.generation)
                    } finally { player.close() }
                } finally { artboard.close() }
            } finally { file.close() }
        } finally { renderer.close() }
    }

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
