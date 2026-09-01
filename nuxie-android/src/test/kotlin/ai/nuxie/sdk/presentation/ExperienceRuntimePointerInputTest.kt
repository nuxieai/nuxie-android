package ai.nuxie.sdk.presentation

import ai.nuxie.sdk.runtime.NuxiePlayerPointerEvent
import ai.nuxie.sdk.runtime.NuxiePlayerPointerKind
import android.view.MotionEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ExperienceRuntimePointerInputTest {
    @Test
    fun `tap down and up project through centered contain into one runtime batch`() {
        val input = ExperienceRuntimePointerInput(ExperienceArtboardSize(400f, 200f))
        val down = motion(MotionEvent.ACTION_DOWN, 1_000, 500f, 500f)
        val up = motion(MotionEvent.ACTION_UP, 1_200, 750f, 625f)

        try {
            assertTrue(input.enqueue(down, viewportWidth = 1_000, viewportHeight = 1_000))
            assertTrue(input.enqueue(up, viewportWidth = 1_000, viewportHeight = 1_000))
        } finally {
            down.recycle()
            up.recycle()
        }

        assertEquals(
            listOf(
                NuxiePlayerPointerEvent(
                    NuxiePlayerPointerKind.DOWN,
                    x = 200f,
                    y = 100f,
                    pointerId = 0,
                    timestampSeconds = 1f,
                ),
                NuxiePlayerPointerEvent(
                    NuxiePlayerPointerKind.UP,
                    x = 300f,
                    y = 150f,
                    pointerId = 0,
                    timestampSeconds = 1.2f,
                ),
            ),
            input.takeBatch(),
        )
        assertEquals(emptyList<NuxiePlayerPointerEvent>(), input.takeBatch())
    }

    @Test
    fun `release clears queued input and permanently rejects later motion`() {
        val input = ExperienceRuntimePointerInput(ExperienceArtboardSize(100f, 100f))
        val down = motion(MotionEvent.ACTION_DOWN, 100, 10f, 20f)
        val up = motion(MotionEvent.ACTION_UP, 200, 10f, 20f)

        try {
            assertTrue(input.enqueue(down, 100, 100))
            input.release()

            assertEquals(emptyList<NuxiePlayerPointerEvent>(), input.takeBatch())
            assertFalse(input.enqueue(up, 100, 100))
        } finally {
            down.recycle()
            up.recycle()
        }
    }

    @Test
    fun `presentation without authenticated artboard geometry does not consume touch`() {
        val input = ExperienceRuntimePointerInput(null)
        val event = motion(MotionEvent.ACTION_DOWN, 100, 10f, 20f)

        try {
            assertFalse(input.enqueue(event, 100, 100))
        } finally {
            event.recycle()
        }
    }

    private fun motion(action: Int, eventTime: Long, x: Float, y: Float): MotionEvent =
        MotionEvent.obtain(0, eventTime, action, x, y, 0)
}
