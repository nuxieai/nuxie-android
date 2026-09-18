package ai.nuxie.sdk.runtime

import ai.nuxie.sdk.experiences.ExperienceVideoAssetBinding
import ai.nuxie.sdk.experiences.ExperienceVideoElement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class ExperienceVideoReadinessTest {
    private class Native : NuxieTypedRuntimeNative {
        var videos = listOf(video(1))
        var visible = true
        override fun videoIsVisible(player: Long, component: Long, viewport: VideoViewport) = visible
        val elapsed = mutableListOf<Pair<Long, Double>>()
        val commands = mutableListOf<Pair<Long, Int>>()
        var decision: (Long, Double) -> Int = { _, _ -> 0 }
        override fun videoOccurrences(player: Long) = videos
        override fun videoReadiness(player: Long, component: Long, elapsed: Double, timeout: Double, optional: Boolean): Int {
            assertEquals(2.0, timeout, 0.0)
            assertTrue(optional)
            this.elapsed += component to elapsed
            return decision(component, elapsed)
        }
        override fun videoCommand(player: Long, component: Long, kind: Int, value: Double, reason: Int): Int {
            commands += component to kind
            return 0
        }
        override fun videoStep(player: Long, component: Long, observation: Int, generation: Long, value: Double) = emptyList<NuxieVideoAction>()
    }

    private fun host(native: Native, clock: () -> Long = { 0L }) = ExperienceVideoPlayback(
        RuntimeEnvironment.getApplication(), NuxieRuntimePlayer(1, native),
        listOf(ExperienceVideoAssetBinding(0, 0, "asset:clip", null, false)),
        listOf(ExperienceVideoElement(0, "screen", "clip", "clip", 5, 2.0, true)), clock,
        initialViewport = VideoViewport(0f, 0f, 320f, 640f),
    )

    @Test fun suspensionDoesNotConsumeWaitAndFallbackIsLatched() {
        val native = Native()
        var now = 0L
        native.decision = { _, elapsed -> if (elapsed >= 2.0) 2 else 0 }
        val host = host(native) { now }
        host.setVisible(true)
        assertFalse(host.isReadyForPresentation())
        now = 1_000_000_000
        assertFalse(host.isReadyForPresentation())
        now = 1_500_000_000
        host.setVisible(false)
        now = 101_500_000_000
        assertFalse(host.isReadyForPresentation())
        assertEquals(1.5, native.elapsed.last().second, 0.0)
        host.setVisible(true)
        now = 102_000_000_000
        assertTrue(host.isReadyForPresentation())
        assertEquals(2.0, native.elapsed.last().second, 0.0)
        val queries = native.elapsed.size
        native.decision = { _, _ -> 1 }
        assertTrue(host.isReadyForPresentation())
        assertEquals(queries, native.elapsed.size)
        assertEquals(listOf(1L to 8), native.commands.filter { it.second == 8 })
        host.close()
    }

    @Test fun laterListRowsKeepTheirDeadlineWithoutHidingPresentedScreen() {
        val native = Native()
        native.decision = { id, _ -> if (id == 1L) 1 else 0 }
        val host = host(native)
        assertTrue(host.isReadyForPresentation())
        native.videos = listOf(video(1), video(2))
        assertTrue(host.isReadyForPresentation())
        assertEquals(2L to 0.0, native.elapsed.last())
        native.decision = { _, _ -> 3 }
        assertThrows(IllegalStateException::class.java) { host.isReadyForPresentation() }
        host.close()
    }

    @Test fun removedRowDecisionCannotAdmitItsReplacement() {
        val native = Native()
        native.decision = { _, _ -> 1 }
        val host = host(native)
        assertTrue(host.isReadyForPresentation())
        native.videos = emptyList()
        assertTrue(host.isReadyForPresentation())
        native.videos = listOf(video(2))
        native.decision = { _, _ -> 0 }
        assertTrue(host.isReadyForPresentation())
        assertEquals(2L to 0.0, native.elapsed.last())
        host.close()
    }

    @Test fun immediatePosterDoesNotGateOrDisposeDecoder() {
        val native = Native()
        native.videos = listOf(video(1).copy(readiness = 0))
        val host = host(native)
        assertTrue(host.isReadyForPresentation())
        assertTrue(native.elapsed.isEmpty())
        assertTrue(native.commands.none { it.second == 8 })
        host.close()
    }

    @Test fun offscreenVideoDoesNotGateAndReentryStartsItsOwnWait() {
        val native = Native()
        var now = 0L
        native.visible = false
        val host = host(native) { now }
        host.setVisible(true)
        assertTrue(host.isReadyForPresentation())
        now = 100_000_000_000
        assertTrue(host.isReadyForPresentation())
        assertTrue(native.elapsed.isEmpty())
        native.visible = true
        assertTrue(host.isReadyForPresentation())
        assertEquals(1L to 0.0, native.elapsed.last())
        now += 500_000_000
        assertTrue(host.isReadyForPresentation())
        assertEquals(1L to 0.5, native.elapsed.last())
        native.visible = false
        host.setViewport(VideoViewport(0f, 0f, 0f, 0f))
        now += 100_000_000_000
        assertTrue(host.isReadyForPresentation())
        native.visible = true
        assertTrue(host.isReadyForPresentation())
        assertEquals(1L to 0.5, native.elapsed.last())
        host.close()
    }

    companion object {
        private fun video(id: Long) = NuxieVideoOccurrence(id, 0, 0, 0, true, 0,
            "asset:clip", "video/mp4", false, readiness = 1, sourceArtboardIndex = 0, sourceComponentId = 5)
    }
}
