package ai.nuxie.sdk.presentation

import ai.nuxie.sdk.runtime.NuxiePlayerPointerKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ExperiencePointerQueueTest {
    @Test
    fun `coalesces moves without displacing pointer lifecycle events`() {
        val queue = ExperiencePointerQueue()
        queue.enqueue(pointer(NuxiePlayerPointerKind.DOWN, x = 1f))
        repeat(5_000) { offset ->
            queue.enqueue(pointer(NuxiePlayerPointerKind.MOVE, x = offset + 2f))
        }
        queue.enqueue(pointer(NuxiePlayerPointerKind.EXIT, x = 5_002f))

        val batch = queue.takeBatch()

        assertEquals(
            listOf(
                NuxiePlayerPointerKind.DOWN,
                NuxiePlayerPointerKind.MOVE,
                NuxiePlayerPointerKind.EXIT,
            ),
            batch.map(ViewPointer::kind),
        )
        assertEquals(5_001f, batch[1].x)
    }

    @Test
    fun `reserves a terminal slot for every admitted active pointer`() {
        val queue = ExperiencePointerQueue()
        repeat(ExperiencePointerQueue.MAXIMUM_ACTIVE_POINTERS) { pointerId ->
            queue.enqueue(pointer(NuxiePlayerPointerKind.DOWN, pointerId = pointerId))
        }
        queue.enqueue(
            pointer(
                NuxiePlayerPointerKind.DOWN,
                pointerId = ExperiencePointerQueue.MAXIMUM_ACTIVE_POINTERS,
            ),
        )

        val admitted = queue.takeBatch()
        assertEquals(ExperiencePointerQueue.MAXIMUM_ACTIVE_POINTERS, admitted.size)
        assertTrue(
            admitted.none {
                it.pointerId == ExperiencePointerQueue.MAXIMUM_ACTIVE_POINTERS
            },
        )

        admitted.forEach { down ->
            queue.enqueue(pointer(NuxiePlayerPointerKind.UP, pointerId = down.pointerId))
        }

        val terminals = queue.takeBatch()
        assertEquals(admitted.map(ViewPointer::pointerId), terminals.map(ViewPointer::pointerId))
        assertTrue(terminals.all { it.kind == NuxiePlayerPointerKind.UP })
    }

    @Test
    fun `returns bounded batches without reordering retained events`() {
        val queue = ExperiencePointerQueue()
        repeat(ExperiencePointerQueue.MAXIMUM_ACTIVE_POINTERS) { pointerId ->
            queue.enqueue(pointer(NuxiePlayerPointerKind.DOWN, pointerId = pointerId))
        }
        repeat(ExperiencePointerQueue.MAXIMUM_ACTIVE_POINTERS) { pointerId ->
            queue.enqueue(pointer(NuxiePlayerPointerKind.UP, pointerId = pointerId))
        }

        assertEquals(
            (0 until ExperiencePointerQueue.MAXIMUM_ACTIVE_POINTERS).toList(),
            queue.takeBatch().map(ViewPointer::pointerId),
        )
        assertEquals(
            (0 until ExperiencePointerQueue.MAXIMUM_ACTIVE_POINTERS).toList(),
            queue.takeBatch().map(ViewPointer::pointerId),
        )
        assertTrue(queue.takeBatch().isEmpty())
    }

    private fun pointer(
        kind: NuxiePlayerPointerKind,
        x: Float = 0f,
        pointerId: Int = 7,
    ) = ViewPointer(
        kind = kind,
        x = x,
        y = x,
        pointerId = pointerId,
        timestampSeconds = 1f,
    )
}
