package ai.nuxie.sdk.runtime

import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Assert.*
import org.junit.Test

/** Exercises decoder seek delivery without a native scene, Vulkan, or TextureView. */
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
                            val center = ((frame.height / 2) * frame.width + frame.width / 2) * 4
                            val r = frame.rgba[center].toInt() and 255
                            val b = frame.rgba[center + 2].toInt() and 255
                            if (frame.generation == generation && (if (red) r > 180 && b < 70 else b > 180 && r < 70)) received[index] = true
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
            file.delete()
        }
    }
}
