package ai.nuxie.sdk.runtime

import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/** Exercises the scripted import path on the device architecture. */
class ConfiguredImportSmokeTest {
    @Test
    fun unsignedVideoPrioritiesSurviveOccurrenceEnumeration() {
        assertTrue("Engine library must load on the test device", NuxieRuntime.shared.isAvailable)
        val baseline = InstrumentationRegistry.getInstrumentation().context.assets
            .open("video/greeting.nux").use { it.readBytes() }
        // RIV varuint property 60008 (priority), with the canonical fixture's zero value.
        // Change only its value; retain the production publisher's scene and assets.
        val marker = byteArrayOf(0xe8.toByte(), 0xd4.toByte(), 3, 0)
        val offsets = (0..baseline.size - marker.size).filter { offset ->
            marker.indices.all { baseline[offset + it] == marker[it] }
        }
        assertEquals("Expected exactly one zero-priority property", 1, offsets.size)
        val valueOffset = offsets.single() + 3
        for (priority in listOf(0x8000_0000L, 0xffff_ffffL)) {
            var remaining = priority
            val encoded = ArrayList<Byte>()
            do {
                val low = (remaining and 127).toInt()
                remaining = remaining ushr 7
                encoded.add((low or if (remaining != 0L) 128 else 0).toByte())
            } while (remaining != 0L)
            val bytes = baseline.copyOfRange(0, valueOffset) + encoded.toByteArray() +
                baseline.copyOfRange(valueOffset + 1, baseline.size)
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
                            assertEquals(priority, player.videos().single().priority.toLong() and 0xffff_ffffL)
                        } finally { player.close() }
                    } finally { artboard.close() }
                } finally { file.close() }
            } finally { renderer.close() }
        }
    }

    private data class VideoMeasurement(val file: String, val width: Int, val height: Int, val cadence: Int) {
        companion object {
            val HD = VideoMeasurement("captions-720p.mp4", 1280, 720, 31)
            val UHD = VideoMeasurement("captions-4k60.mp4", 3840, 2160, 61)
        }
    }

    @Test
    fun captionsAreExtractedFromTheRetainedMp4() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val local = java.io.File.createTempFile("caption-extraction-", ".mp4", instrumentation.targetContext.cacheDir)
        try {
            instrumentation.context.assets.open("video/captions.mp4").use { input ->
                local.outputStream().use { input.copyTo(it) }
            }
            val cues = ExperienceVideoCaptions.read(local, 2)
            assertEquals(listOf("Hello 👋", "Welcome"), cues.map { it.text })
            assertEquals(0.0, cues[0].startSeconds, 0.001)
            assertEquals(0.9, cues[0].endSeconds, 0.001)
            assertEquals(1.0, cues[1].startSeconds, 0.001)
            assertEquals(1.9, cues[1].endSeconds, 0.001)
            assertThrows(IllegalArgumentException::class.java) { ExperienceVideoCaptions.read(local, 0) }
        } finally { local.delete() }
    }

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
                        assertEquals("Video", video.componentName)
                        assertEquals(0, video.priority)
                        assertEquals(0, video.readiness)
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
    fun localMp4DecodesIntoVulkanAcrossTwoLoops() = verifyDecodedVideo(frenchCaptions = false)

    @Test
    fun preferredFrenchTrackFollowsActualVideoPlayback() = verifyDecodedVideo(frenchCaptions = true)

    @Test
    fun video4k60DeliveryMeasurements() = verifyDecodedVideo(frenchCaptions = false, measurement = VideoMeasurement.UHD)

    @Test
    fun video720pDeliveryMeasurements() = verifyDecodedVideo(frenchCaptions = false, measurement = VideoMeasurement.HD)

    @Test
    fun concurrent720pOwnersShareDecoderCapacity() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val local = java.io.File.createTempFile("concurrent-video-", ".mp4", instrumentation.targetContext.cacheDir)
        val artboards = mutableListOf<NuxieRuntimeArtboard>()
        val players = mutableListOf<NuxieRuntimePlayer>()
        val playbacks = mutableListOf<ExperienceVideoPlayback>()
        assertTrue(NuxieRuntime.shared.isAvailable)
        val renderer = checkNotNull(NuxieRuntime.shared.newAndroidVulkanRenderer(320, 640))
        try {
            instrumentation.context.assets.open("video/captions-720p.mp4").use { input ->
                local.outputStream().use { input.copyTo(it) }
            }
            val bytes = instrumentation.context.assets.open("video/greeting.nux").use { it.readBytes() }
            val file = checkNotNull(NuxieRuntime.shared.importFile(renderer, bytes,
                checkNotNull(NuxieRuntime.shared.inspectFileAssets(bytes)), videoEnabled = true))
            try {
                val pool = ExperienceVideoDecoderPool { NuxieVideoDecoderBudget(2, 2, 0, 2L * 1280 * 720 * 31, 0) }
                val inventory = kotlinx.serialization.json.Json.parseToJsonElement(
                    instrumentation.context.assets.open("video/inventory.json").bufferedReader().use { it.readText() }) as kotlinx.serialization.json.JsonObject
                val targets = ai.nuxie.sdk.experiences.JourneyRenderSchema.videoElements(
                    kotlinx.serialization.json.JsonObject(inventory + ("renderer" to kotlinx.serialization.json.JsonPrimitive("nux"))))
                repeat(2) { index ->
                    val artboard = checkNotNull(file.newArtboard("Video Frame")).also { artboards.add(it) }
                    val player = checkNotNull(artboard.newPlayer()).also { players.add(it) }
                    val video = player.videos().single()
                    // One audible greeting plus one motion background. Two
                    // exclusive audio owners intentionally compete for focus.
                    if (index == 1) player.videoCommand(video.componentId, 5, 1.0)
                    playbacks.add(ExperienceVideoPlayback(instrumentation.targetContext, player,
                        listOf(ai.nuxie.sdk.experiences.ExperienceVideoAssetBinding(0, video.assetId,
                            video.sourceKey, local, true,
                            listOf(ai.nuxie.sdk.experiences.ExperienceVideoCaptionTrack(2, "eng")))),
                        targets, decoderPool = pool, preferredCaptionLanguages = listOf("en")))
                }
                playbacks.forEach { it.setVisible(true) }
                val colors = List(2) { mutableListOf<Boolean>() }
                val captions = List(2) { mutableSetOf<String>() }
                val started = System.nanoTime()
                val deadline = android.os.SystemClock.elapsedRealtime() + 15_000
                while (android.os.SystemClock.elapsedRealtime() < deadline && colors.any { it.size < 4 }) {
                    val cycle = System.nanoTime()
                    playbacks.forEachIndexed { index, playback ->
                        playback.advance(renderer, System.nanoTime() / 1_000_000_000.0)
                        captions[index].addAll(playback.captionSnapshot().values.map { it.text })
                        players[index].step(0.0)
                        val frame = renderer.renderToCpuFrame(players[index], 0, false)
                        val offset = (80 * frame.width + 100) * 4
                        val red = frame.rgba[offset].toInt() and 255
                        val blue = frame.rgba[offset + 2].toInt() and 255
                        if (red > 180 && blue < 70 && colors[index].lastOrNull() != true) colors[index].add(true)
                        if (blue > 180 && red < 70 && colors[index].lastOrNull() != false) colors[index].add(false)
                    }
                    java.util.concurrent.locks.LockSupport.parkNanos(maxOf(0L, 16_666_667L - (System.nanoTime() - cycle)))
                }
                val elapsed = (System.nanoTime() - started) / 1_000_000_000.0
                colors.forEach { assertEquals(listOf(true, false, true, false), it.take(4)) }
                captions.forEach { assertTrue(it.containsAll(listOf("Hello 👋", "Welcome"))) }
                assertEquals(listOf(1, 1), playbacks.map { it.activeDecoderCount })
                println("NUXIE_VIDEO_MEASUREMENT " + org.json.JSONObject()
                    .put("width", 1280).put("height", 720).put("players", 2)
                    .put("independentOwners", true).put("elapsedSeconds", elapsed)
                    .put("mutedOwners", 1)
                    .put("deliveredFrames", playbacks.sumOf { it.deliveredFrames })
                    .put("framesPerOwner", org.json.JSONArray(playbacks.map { it.deliveredFrames }))
                    .put("aggregateDeliveredFps", playbacks.sumOf { it.deliveredFrames } / elapsed)
                    .put("deliveredRGBABytes", playbacks.sumOf { it.deliveredRGBABytes })
                    .put("includesForcedVulkanReadback", true).put("targetTickPeriodMs", 1000.0 / 60))
                playbacks[0].setVisible(false)
                playbacks[0].advance(renderer, System.nanoTime() / 1_000_000_000.0)
                assertEquals(0, playbacks[0].activeDecoderCount)
                val survivingFrames = playbacks[1].deliveredFrames
                val survivorDeadline = android.os.SystemClock.elapsedRealtime() + 3_000
                while (playbacks[1].deliveredFrames == survivingFrames && android.os.SystemClock.elapsedRealtime() < survivorDeadline) {
                    playbacks[1].advance(renderer, System.nanoTime() / 1_000_000_000.0)
                    Thread.sleep(16)
                }
                assertTrue("The other owner must continue delivering frames", playbacks[1].deliveredFrames > survivingFrames)
                assertTrue(players[0].videos().single().wantsPlay)
                assertEquals(1, playbacks[1].activeDecoderCount)
            } finally {
                playbacks.asReversed().forEach { it.close(); assertEquals(0, it.activeDecoderCount) }
                players.asReversed().forEach { it.close() }
                artboards.asReversed().forEach { it.close() }
                file.close()
            }
        } finally { renderer.close(); local.delete() }
    }

    private fun verifyDecodedVideo(frenchCaptions: Boolean, measurement: VideoMeasurement? = null) {
        val preparationStarted = System.nanoTime()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val local = java.io.File.createTempFile("video-decoder-", ".mp4", instrumentation.targetContext.cacheDir)
        instrumentation.context.assets.open("video/" + (measurement?.file ?: if (frenchCaptions) "multilingual.mp4" else "captions.mp4")).use { input ->
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
                        val inventory = kotlinx.serialization.json.Json.parseToJsonElement(
                            instrumentation.context.assets.open("video/inventory.json").bufferedReader().use { it.readText() }) as kotlinx.serialization.json.JsonObject
                        val targets = ai.nuxie.sdk.experiences.JourneyRenderSchema.videoElements(
                            kotlinx.serialization.json.JsonObject(inventory + ("renderer" to kotlinx.serialization.json.JsonPrimitive("nux"))))
                        var slots = 1
                        val pool = ExperienceVideoDecoderPool { NuxieVideoDecoderBudget(slots, slots, 0, measurement?.let { it.width.toLong() * it.height * it.cadence } ?: 100_000, 0) }
                        val tracks = mutableListOf(ai.nuxie.sdk.experiences.ExperienceVideoCaptionTrack(2, "eng"))
                        if (frenchCaptions) tracks.add(ai.nuxie.sdk.experiences.ExperienceVideoCaptionTrack(3, "fra"))
                        val playback = ExperienceVideoPlayback(instrumentation.targetContext, player,
                            listOf(ai.nuxie.sdk.experiences.ExperienceVideoAssetBinding(0, initial.assetId, initial.sourceKey, local, true,
                                tracks)), targets,
                            decoderPool = pool, preferredCaptionLanguages = if (frenchCaptions) listOf("fr-CA", "en") else listOf("en"))
                        try {
                            playback.setVisible(true)
                            val measuringStarted = System.nanoTime()
                            var firstDelivered: Long? = null
                            val tickMilliseconds = mutableListOf<Double>()
                            val deadline = android.os.SystemClock.elapsedRealtime() + 15_000
                            val seenCaptions = mutableSetOf<String>()
                            var suspended = false
                            val colors = mutableListOf<Boolean>()
                            while (android.os.SystemClock.elapsedRealtime() < deadline && colors.size < 4) {
                                val cycleStarted = System.nanoTime()
                                playback.advance(renderer, System.nanoTime() / 1_000_000_000.0)
                                tickMilliseconds += (System.nanoTime() - cycleStarted) / 1_000_000.0
                                if (firstDelivered == null && playback.deliveredFrames > 0) firstDelivered = System.nanoTime()
                                seenCaptions += checkNotNull(playback.captionSnapshot()[initial.componentId]).text
                                player.step(0.0)
                                val composed = renderer.renderToCpuFrame(player, 0, false)
                                val offset = (80 * composed.width + 100) * 4
                                val red = composed.rgba[offset].toInt() and 255
                                val blue = composed.rgba[offset + 2].toInt() and 255
                                if (red > 180 && blue < 70 && colors.lastOrNull() != true) colors.add(true)
                                if (blue > 180 && red < 70 && colors.lastOrNull() != false) colors.add(false)
                                if (measurement == null && !suspended && colors == listOf(true)) {
                                    playback.setVisible(false)
                                    assertTrue(playback.captionSnapshot().isEmpty())
                                    playback.advance(renderer, System.nanoTime() / 1_000_000_000.0)
                                    assertTrue(player.videos().single().wantsPlay)
                                    assertTrue(player.videos().single().state != 2)
                                    Thread.sleep(150)
                                    playback.setVisible(true)
                                    suspended = true
                                }
                                if (measurement != null) java.util.concurrent.locks.LockSupport.parkNanos(
                                    maxOf(0L, 16_666_667L - (System.nanoTime() - cycleStarted)))
                                else Thread.sleep(16)
                            }
                            if (measurement != null) {
                                val elapsed = (System.nanoTime() - measuringStarted) / 1_000_000_000.0
                                val ordered = tickMilliseconds.sorted()
                                val metrics = org.json.JSONObject()
                                    .put("width", measurement.width).put("height", measurement.height).put("players", 1)
                                    .put("elapsedSeconds", elapsed).put("deliveredFrames", playback.deliveredFrames)
                                    .put("aggregateDeliveredFps", playback.deliveredFrames / elapsed)
                                    .put("firstFrameFromPreparationMs", ((firstDelivered ?: System.nanoTime()) - preparationStarted) / 1_000_000.0)
                                    .put("tickP95Ms", ordered[(ordered.size * 0.95).toInt().coerceAtMost(ordered.lastIndex)])
                                    .put("deliveredRGBABytes", playback.deliveredRGBABytes)
                                    .put("activeDecoders", playback.activeDecoderCount)
                                    .put("includesForcedVulkanReadback", true).put("targetTickPeriodMs", 1000.0 / 60)
                                println("NUXIE_VIDEO_MEASUREMENT $metrics")
                            }
                            assertTrue(seenCaptions.containsAll(if (frenchCaptions) listOf("Bonjour 👋", "Bienvenue") else listOf("Hello 👋", "Welcome")))
                            if (frenchCaptions) assertFalse(seenCaptions.contains("Hello 👋"))
                            assertEquals("Decoded frames must repeat through native loop commands", listOf(true, false, true, false), colors)
                            fun command(type: String, view: String = "clip-view") = ai.nuxie.sdk.experiences.JourneyVideoAction.parse(
                                kotlinx.serialization.json.Json.parseToJsonElement("""{"type":"video","target":{"artboardId":"screen","viewNodeId":"$view"},"command":{"type":"$type"}}"""))
                            assertThrows(IllegalArgumentException::class.java) { playback.apply(command("pause", "missing")) }
                            playback.apply(command("pause"))
                            playback.advance(renderer, System.nanoTime() / 1_000_000_000.0)
                            assertEquals(false, player.videos().single().wantsPlay)
                            val beforeRetirement = player.videos().single().generation
                            slots = 0
                            playback.advance(renderer, System.nanoTime() / 1_000_000_000.0)
                            val retired = player.videos().single()
                            assertTrue(retired.generation > beforeRetirement)
                            assertEquals(false, retired.wantsPlay)
                            slots = 1
                            playback.advance(renderer, System.nanoTime() / 1_000_000_000.0)
                            assertTrue(player.videos().single().generation > retired.generation)
                            assertEquals(false, player.videos().single().wantsPlay)
                            playback.setVisible(false)
                            playback.apply(command("play"))
                            playback.advance(renderer, System.nanoTime() / 1_000_000_000.0)
                            assertTrue(player.videos().single().wantsPlay)
                            assertTrue(player.videos().single().state != 2)
                        } finally { playback.close(); assertEquals(0, playback.activeDecoderCount) }
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
