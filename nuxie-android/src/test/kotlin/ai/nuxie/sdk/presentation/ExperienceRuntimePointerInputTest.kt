package ai.nuxie.sdk.presentation

import ai.nuxie.sdk.fixtures.FixtureRunner
import ai.nuxie.sdk.runtime.NuxiePlayerPointerEvent
import ai.nuxie.sdk.runtime.NuxiePlayerPointerKind
import android.view.MotionEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

@RunWith(RobolectricTestRunner::class)
class ExperienceRuntimePointerInputTest {
    @Test
    fun `shared hidden interval contract cancels only pointers delivered to the player`() {
        val fixture = Json.parseToJsonElement(FixtureRunner.fixturesRoot()
            .resolve("journeys/planes/runtime-visibility-android.json").readText()).jsonObject
        for (value in fixture.getValue("pointerCases").jsonArray) {
            val case = value.jsonObject
            val input = ExperienceRuntimePointerInput(ExperienceArtboardSize(100f, 100f))
            for ((index, raw) in case.getValue("steps").jsonArray.withIndex()) {
                val step = raw.jsonObject
                val action = step.getValue("action").jsonPrimitive.content
                when (action) {
                    "reset" -> input.reset()
                    "take" -> assertEquals(case.getValue("name").jsonPrimitive.content,
                        step.getValue("expected").jsonArray.map { it.jsonPrimitive.content },
                        input.takeBatch().map { it.kind.name.lowercase() })
                    else -> {
                        val code = when (action) {
                            "down" -> MotionEvent.ACTION_DOWN
                            "move" -> MotionEvent.ACTION_MOVE
                            "up" -> MotionEvent.ACTION_UP
                            else -> error("Unknown pointer fixture action: $action")
                        }
                        val event = motion(code, 1_000L + index, 10f + index, 20f)
                        try { assertTrue(input.enqueue(event, 100, 100)) } finally { event.recycle() }
                    }
                }
            }
            input.release()
        }
    }

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
