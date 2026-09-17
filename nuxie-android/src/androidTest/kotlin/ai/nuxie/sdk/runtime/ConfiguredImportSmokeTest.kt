package ai.nuxie.sdk.runtime

import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
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
                        player.videoSetCaptions(video.componentId, "en", listOf(
                            NuxieVideoCaptionCue(0.0, 0.9, "Hello 👋"),
                            NuxieVideoCaptionCue(1.0, 1.9, "Welcome")))
                        val ready = player.videoStep(video.componentId, 1, video.generation, 2.022)
                        assertTrue("Autoplay must produce a decoder play action", ready.any { it.kind == 0 })
                        fun pixels(red: Boolean) = ByteArray(64 * 32 * 4) { index ->
                            if (index % 4 == 3 || index % 4 == if (red) 0 else 2) 255.toByte() else 0
                        }
                        fun assertVideoColor(red: Boolean) {
                            player.step(0.0)
                            val frame = renderer.renderToCpuFrame(player, 0, false)
                            val offset = (80 * frame.width + 100) * 4
                            assertTrue("Expected uploaded video frame in the composed Vulkan scene",
                                (frame.rgba[offset + if (red) 0 else 2].toInt() and 255) > 180)
                            assertTrue((frame.rgba[offset + if (red) 2 else 0].toInt() and 255) < 70)
                        }
                        player.videoClock(video.componentId, 1.0,
                            NuxieVideoClock(video.generation, 0.0, 1.0, true, true))
                        assertEquals(NuxieVideoCaption("en", "Hello 👋"), player.videoCaption(video.componentId))
                        assertThrows(IllegalArgumentException::class.java) {
                            player.videoSetCaptions(video.componentId, "en", listOf(NuxieVideoCaptionCue(2.0, 1.0, "bad")))
                        }
                        assertTrue(NuxieRuntimeBridge.nativeVideoSetCaptions(player.requireHandle(), video.componentId,
                            "en".toByteArray(), doubleArrayOf(2.0, 1.0), intArrayOf(3), "bad".toByteArray()) != 0)
                        assertTrue(NuxieRuntimeBridge.nativeVideoSetCaptions(player.requireHandle(), video.componentId,
                            "en".toByteArray(), doubleArrayOf(0.0, 1.0), intArrayOf(4), "bad".toByteArray()) != 0)
                        assertEquals("Hello 👋", player.videoCaption(video.componentId).text)
                        val redBytes = pixels(true)
                        player.videoPresent(renderer, video.componentId, NuxieVideoFrame(video.generation, 0.0, 64, 32, redBytes))
                        redBytes.fill(0)
                        assertVideoColor(true)
                        assertThrows(IllegalArgumentException::class.java) {
                            NuxieVideoFrame(video.generation, 0.0, 1, 1, ByteArray(3))
                        }
                        val foreignRenderer = checkNotNull(runtime.newAndroidVulkanRenderer(320, 640))
                        try {
                            assertThrows(NuxieRuntimeCallException::class.java) {
                                player.videoPresent(foreignRenderer, video.componentId,
                                    NuxieVideoFrame(video.generation, 0.0, 64, 32, pixels(false)))
                            }
                        } finally { foreignRenderer.close() }

                        player.videoCommand(video.componentId, 1)
                        player.videoStep(video.componentId, 0, video.generation)
                        assertEquals(false, player.videos().single().wantsPlay)
                        assertEquals(3, player.videos().single().state)
                        player.videoCommand(video.componentId, 2, 1.0)
                        val seek = player.videoStep(video.componentId, 0, video.generation).single { it.kind == 2 }
                        assertEquals(1.0, seek.value, 0.001)
                        assertTrue(seek.generation > video.generation)
                        player.videoClock(video.componentId, 2.0,
                            NuxieVideoClock(seek.generation, 1.0, 0.0, false, true))
                        player.videoPresent(renderer, video.componentId, NuxieVideoFrame(seek.generation, 1.0, 64, 32, pixels(false)))
                        assertVideoColor(false)
                        assertEquals("Welcome", player.videoCaption(video.componentId).text)
                        player.videoSetCaptions(video.componentId, "", emptyList())
                        assertEquals("", player.videoCaption(video.componentId).text)
                        player.videoPresent(renderer, video.componentId, NuxieVideoFrame(video.generation, 0.0, 64, 32, pixels(true)))
                        assertVideoColor(false)

                    } finally { player.close() }
                } finally { artboard.close() }
            } finally { file.close() }
        } finally { renderer.close() }
    }

    @Test
    fun localMp4DecodesIntoVulkanAcrossTwoLoops() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val local = java.io.File.createTempFile("video-decoder-", ".mp4", instrumentation.targetContext.cacheDir)
        instrumentation.context.assets.open("video/greeting.mp4").use { input ->
            local.outputStream().use { input.copyTo(it) }
        }
        val bytes = instrumentation.context.assets.open("video/greeting.nux").use { it.readBytes() }
        val runtime = NuxieRuntime.shared
        assertTrue(runtime.isAvailable)
        val renderer = checkNotNull(runtime.newAndroidVulkanRenderer(320, 640))
        try {
            val file = checkNotNull(runtime.importFile(renderer, bytes, checkNotNull(runtime.inspectFileAssets(bytes)), videoEnabled = true))
            try {
                val artboard = checkNotNull(file.newArtboard("Video Frame"))
                try {
                    val player = checkNotNull(artboard.newPlayer())
                    try {
                        val initial = player.videos().single()
                        val playback = ExperienceVideoPlayback(instrumentation.targetContext, player,
                            listOf(ai.nuxie.sdk.experiences.ExperienceVideoAssetBinding(0, initial.assetId, initial.sourceKey, local, true)))
                        try {
                            playback.setVisible(true)
                            val deadline = android.os.SystemClock.elapsedRealtime() + 15_000
                            var suspended = false
                            val colors = mutableListOf<Boolean>()
                            while (android.os.SystemClock.elapsedRealtime() < deadline && colors.size < 4) {
                                playback.advance(renderer, System.nanoTime() / 1_000_000_000.0)
                                player.step(0.0)
                                val composed = renderer.renderToCpuFrame(player, 0, false)
                                val offset = (80 * composed.width + 100) * 4
                                val red = composed.rgba[offset].toInt() and 255
                                val blue = composed.rgba[offset + 2].toInt() and 255
                                if (red > 180 && blue < 70 && colors.lastOrNull() != true) colors.add(true)
                                if (blue > 180 && red < 70 && colors.lastOrNull() != false) colors.add(false)
                                if (!suspended && colors == listOf(true)) {
                                    playback.setVisible(false)
                                    playback.advance(renderer, System.nanoTime() / 1_000_000_000.0)
                                    assertTrue(player.videos().single().wantsPlay)
                                    assertTrue(player.videos().single().state != 2)
                                    Thread.sleep(150)
                                    playback.setVisible(true)
                                    suspended = true
                                }
                                Thread.sleep(16)
                            }
                            assertEquals("Decoded frames must repeat through native loop commands", listOf(true, false, true, false), colors)
                        } finally { playback.close() }
                    } finally { player.close() }
                } finally { artboard.close() }
            } finally { file.close() }
        } finally { renderer.close(); local.delete() }
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
