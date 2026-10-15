package ai.nuxie.sdk.runtime

import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Assert.*
import org.junit.Test

/**
 * Exercises decoder seek delivery. Frames stay in GPU memory, so each one is
 * drawn through the runtime's Vulkan video path to read its color back.
 */
class AndroidVideoSeekDeviceTest {
    @Test fun twoPausedDecodersDeliverEverySeekGeneration() = verifySeeks(false)

    @Test fun pausedAfterPlaybackWithClockPollingDeliversEverySeekGeneration() = verifySeeks(true)

    /**
     * The shared endpoint shape, the same with its last frame a sync frame, and
     * the same with AAC times reported without the encoder-delay shift.
     */
    @Test fun pausedSeekToDurationDeliversTheLastVideoSample() {
        for (name in listOf("endpoint-timecoded.mp4", "endpoint-last-keyframe.mp4", "endpoint-unshifted-audio.mp4")) {
            verifyEndpointSeek(name)
        }
    }

    private fun verifyEndpointSeek(name: String) {
        val file = endpointMedia(name)
        val decoder = AndroidVideoDecoder(InstrumentationRegistry.getInstrumentation().targetContext, file, 1, 64 * 1024 * 1024, 1)
        val probe = ComposedColorProbe()
        try {
            awaitReady(decoder)
            /** The decoded image's own frame time, read from its time code, and the player position. */
            fun seek(seconds: Double, generation: Long, red: Boolean): Pair<Double, Double> {
                decoder.action(2, seconds, generation)
                val deadline = SystemClock.elapsedRealtime() + 5_000
                while (SystemClock.elapsedRealtime() < deadline) {
                    assertNull(decoder.failure())
                    decoder.takeFrame()?.let { frame ->
                        try {
                            val rgb = probe.rgb(frame)
                            if (frame.generation == generation && ComposedColorProbe.color(rgb) == red) {
                                return timecodeSeconds(rgb) to frame.seconds
                            }
                        } finally { frame.close() }
                    }
                    Thread.sleep(5)
                }
                throw AssertionError("$name: paused decoder must deliver the requested image at $seconds seconds")
            }
            assertEquals("$name: seek to 0.1 decodes the 0.1-second sample", 0.1, seek(0.1, 2, true).first, 0.001)
            val (image, position) = seek(decoder.duration(), 3, false)
            assertEquals("$name: seek to duration decodes the final 1.9-second sample; player position=$position",
                1.9, image, 0.001)
            assertFalse(decoder.playing())
        } finally {
            decoder.close()
            probe.close()
            file.delete()
        }
    }

    @Test fun pausedSeekIntoAnAudioTailKeepsTheRequestedTime() {
        val file = endpointMedia("endpoint-audio-tail.mp4")
        val decoder = AndroidVideoDecoder(InstrumentationRegistry.getInstrumentation().targetContext, file, 1, 64 * 1024 * 1024, 1)
        try {
            awaitReady(decoder)
            assertEquals(2.25, decoder.duration(), 0.05)
            decoder.action(2, 2.2, 2)
            // The tail has no image to wait for. Read the player on its owner thread
            // once the seek has completed.
            val seeking = AndroidVideoDecoder::class.java.getDeclaredField("seeking").apply { isAccessible = true }
            val player = AndroidVideoDecoder::class.java.getDeclaredField("player").apply { isAccessible = true }
            val handler = AndroidVideoDecoder::class.java.getDeclaredField("handler").apply { isAccessible = true }
                .get(decoder) as android.os.Handler
            var position = -1
            val deadline = SystemClock.elapsedRealtime() + 5_000
            while (SystemClock.elapsedRealtime() < deadline) {
                assertNull(decoder.failure())
                val read = java.util.concurrent.CountDownLatch(1)
                var settled = false
                assertTrue(handler.post {
                    settled = !seeking.getBoolean(decoder)
                    if (settled) position = (player.get(decoder) as android.media.MediaPlayer).currentPosition
                    read.countDown()
                })
                assertTrue(read.await(5, java.util.concurrent.TimeUnit.SECONDS))
                if (settled && position >= 0) break
                Thread.sleep(20)
            }
            assertTrue("A seek into audio after the last image keeps its time: position=${position}ms", position >= 2_150)
        } finally {
            decoder.close()
            file.delete()
        }
    }

    @Test fun seekLimitReadsTheLastImageAndSkipsWhenItCannot() {
        val timecoded = endpointMedia("endpoint-timecoded.mp4")
        val tail = endpointMedia("endpoint-audio-tail.mp4")
        val broken = File.createTempFile("decoder-not-video-", ".mp4",
            InstrumentationRegistry.getInstrumentation().targetContext.cacheDir).apply { writeText("not a video") }
        try {
            assertEquals(1.9, AndroidVideoDecoder.seekLimitSeconds(timecoded, AndroidVideoDecoder.SEEK_LIMIT_SCAN_SAMPLES), 0.001)
            val keyframe = endpointMedia("endpoint-last-keyframe.mp4")
            val unshifted = endpointMedia("endpoint-unshifted-audio.mp4")
            try {
                assertEquals("A last sample that is a sync frame still ends at the track's end", 1.9,
                    AndroidVideoDecoder.seekLimitSeconds(keyframe, AndroidVideoDecoder.SEEK_LIMIT_SCAN_SAMPLES), 0.001)
                assertEquals("AAC encoder delay reported without its shift is not an audio tail", 1.9,
                    AndroidVideoDecoder.seekLimitSeconds(unshifted, AndroidVideoDecoder.SEEK_LIMIT_SCAN_SAMPLES), 0.001)
            } finally {
                keyframe.delete()
                unshifted.delete()
            }
            assertTrue("Audio after the last image keeps seeks unmoved",
                AndroidVideoDecoder.seekLimitSeconds(tail, AndroidVideoDecoder.SEEK_LIMIT_SCAN_SAMPLES).isNaN())
            assertTrue("A scan over its sample limit skips the limit",
                AndroidVideoDecoder.seekLimitSeconds(timecoded, 1).isNaN())
            assertTrue("An unreadable file skips the limit without throwing",
                AndroidVideoDecoder.seekLimitSeconds(broken, 100).isNaN())
        } finally {
            timecoded.delete()
            tail.delete()
            broken.delete()
        }
    }

    private fun endpointMedia(name: String): File {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val file = File.createTempFile("decoder-endpoint-", ".mp4", instrumentation.targetContext.cacheDir)
        instrumentation.context.assets.open("video-endpoint/$name").use { input -> file.outputStream().use { input.copyTo(it) } }
        return file
    }

    private fun awaitReady(decoder: AndroidVideoDecoder) {
        val prepared = SystemClock.elapsedRealtime() + 10_000
        while (!decoder.ready() && decoder.failure() == null && SystemClock.elapsedRealtime() < prepared) Thread.sleep(5)
        assertNull(decoder.failure())
        assertTrue(decoder.ready())
    }

    private fun verifySeeks(afterPlayback: Boolean) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val file = File(instrumentation.targetContext.cacheDir, "decoder-seek-fixture.mp4")
        instrumentation.context.assets.open("video/captions.mp4").use { input ->
            file.outputStream().use { input.copyTo(it) }
        }
        val decoders = List(if (afterPlayback) 1 else 2) { AndroidVideoDecoder(instrumentation.targetContext, file, 1, 64 * 1024 * 1024, 1) }
        val probe = ComposedColorProbe()
        var worstMillis = 0L
        try {
            val preparedDeadline = SystemClock.elapsedRealtime() + 10_000
            while (decoders.any { !it.ready() } && SystemClock.elapsedRealtime() < preparedDeadline) Thread.sleep(5)
            assertTrue(decoders.all { it.ready() && it.failure() == null })
            if (afterPlayback) {
                decoders.single().action(0, 0.0, 1)
                val playingDeadline = SystemClock.elapsedRealtime() + 3_000
                while (!decoders.single().playing() && SystemClock.elapsedRealtime() < playingDeadline) Thread.sleep(5)
                assertTrue("Decoder must actually start before testing paused seeks", decoders.single().playing())
                Thread.sleep(1_000)
                decoders.single().action(1, 0.0, 1)
                val pausedDeadline = SystemClock.elapsedRealtime() + 3_000
                while (decoders.single().playing() && SystemClock.elapsedRealtime() < pausedDeadline) Thread.sleep(5)
                assertFalse("Decoder must acknowledge pause", decoders.single().playing())
            }
            repeat(600) { iteration ->
                val generation = iteration + 2L
                val red = iteration % 2 == 0
                val start = SystemClock.elapsedRealtime()
                decoders.forEach { it.action(2, if (red) 0.1 else 1.2, generation) }
                val received = BooleanArray(decoders.size)
                while (!received.all { it } && SystemClock.elapsedRealtime() - start < 3_000) {
                    for ((index, decoder) in decoders.withIndex()) {
                        if (afterPlayback) decoder.clock()
                        assertNull(decoder.failure())
                        decoder.takeFrame()?.let { frame ->
                            try {
                                if (frame.generation == generation && probe.color(frame) == red) received[index] = true
                            } finally { frame.close() }
                        }
                    }
                    if (!received.all { it }) Thread.sleep(5)
                }
                worstMillis = maxOf(worstMillis, SystemClock.elapsedRealtime() - start)
                assertTrue("seek=$iteration generation=$generation received=${received.toList()} worstMs=$worstMillis", received.all { it })
            }
            println("decoder-seek: 600 seeks / ${decoders.size} owners afterPlayback=$afterPlayback, worstMs=$worstMillis")
        } finally {
            decoders.forEach { it.close() }
            probe.close()
            file.delete()
        }
    }
}

/** Draws decoded frames into the greeting scene's video and reads the composed color. */
private class ComposedColorProbe : AutoCloseable {
    private val renderer = run {
        check(NuxieRuntime.shared.isAvailable) { "Engine library must load on the test device" }
        checkNotNull(NuxieRuntime.shared.newAndroidVulkanRenderer(320, 640))
    }
    private val file: NuxieRuntimeFile
    private val artboard: NuxieRuntimeArtboard
    private val player: NuxieRuntimePlayer
    private val video: NuxieVideoOccurrence

    init {
        val bytes = InstrumentationRegistry.getInstrumentation().context.assets
            .open("video/greeting.nux").use { it.readBytes() }
        file = checkNotNull(NuxieRuntime.shared.importFile(renderer, bytes,
            checkNotNull(NuxieRuntime.shared.inspectFileAssets(bytes)), videoEnabled = true))
        artboard = checkNotNull(file.newArtboard("Video Frame"))
        player = checkNotNull(artboard.newPlayer())
        video = player.videos().single()
        player.videoStep(video.componentId, 1, video.generation, 2.022)
    }

    /** True for red, false for blue, null for neither. */
    fun color(frame: AndroidVideoDecoder.Frame): Boolean? = color(rgb(frame))

    /** The composed red, green and blue at the video's center. */
    fun rgb(frame: AndroidVideoDecoder.Frame): IntArray {
        player.videoPresentHardwareBuffer(renderer, video.componentId, NuxieVideoHardwareBufferFrame(
            video.generation, 0.0, frame.buffer, frame.cropLeft, frame.cropTop, frame.cropRight,
            frame.cropBottom, frame.rotationDegrees, frame.displayWidth, frame.displayHeight,
            frame.colorMatrix, frame.colorRange))
        player.step(0.0)
        val composed = renderer.renderToCpuFrame(player, 0, 1f)
        val offset = (80 * composed.width + 100) * 4
        return IntArray(3) { composed.rgba[offset + it].toInt() and 255 }
    }

    companion object {
        fun color(rgb: IntArray): Boolean? = when {
            rgb[0] > 180 && rgb[2] < 70 -> true
            rgb[2] > 180 && rgb[0] < 70 -> false
            else -> null
        }
    }

    override fun close() {
        player.close()
        artboard.close()
        file.close()
        renderer.close()
    }
}

/**
 * The frame time of a decoded image of the Android endpoint test media, read
 * from the image itself: each frame's green channel is its number times 12.
 */
internal fun timecodeSeconds(rgb: IntArray): Double = Math.round(rgb[1] / 12.0) / 10.0
