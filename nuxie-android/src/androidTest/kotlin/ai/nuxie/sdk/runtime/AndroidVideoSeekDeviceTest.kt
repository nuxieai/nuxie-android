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
    fun color(frame: AndroidVideoDecoder.Frame): Boolean? {
        player.videoPresentHardwareBuffer(renderer, video.componentId, NuxieVideoHardwareBufferFrame(
            video.generation, 0.0, frame.buffer, frame.cropLeft, frame.cropTop, frame.cropRight,
            frame.cropBottom, frame.rotationDegrees, frame.displayWidth, frame.displayHeight,
            frame.colorMatrix, frame.colorRange))
        player.step(0.0)
        val composed = renderer.renderToCpuFrame(player, 0, false)
        val offset = (80 * composed.width + 100) * 4
        val r = composed.rgba[offset].toInt() and 255
        val b = composed.rgba[offset + 2].toInt() and 255
        return when {
            r > 180 && b < 70 -> true
            b > 180 && r < 70 -> false
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
