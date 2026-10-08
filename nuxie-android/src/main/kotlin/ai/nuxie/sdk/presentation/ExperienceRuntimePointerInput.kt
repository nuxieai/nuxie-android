package ai.nuxie.sdk.presentation

import ai.nuxie.sdk.runtime.NuxiePlayerPointerEvent
import ai.nuxie.sdk.runtime.NuxiePlayerPointerKind
import android.view.MotionEvent
import kotlin.math.min

/** Root extent in layout points. */
internal data class ExperienceArtboardSize(
    val width: Float,
    val height: Float,
    val originX: Float = 0f,
    val originY: Float = 0f,
) {
    init {
        require(originX.isFinite() && originY.isFinite())
        require(width.isFinite() && width > 0f) { "Artboard width must be finite and positive" }
        require(height.isFinite() && height > 0f) { "Artboard height must be finite and positive" }
    }
}

/**
 * Bounded UI-thread input staging for one runtime presentation.
 *
 * Android pixels are projected into the runtime's layout points. Events remain queued until one runtime
 * frame consumes them; release atomically clears the queue and permanently
 * closes this presentation's input seam.
 */
internal class ExperienceRuntimePointerInput(
    private var artboardSize: ExperienceArtboardSize?,
) {
    private val lock = Any()
    private val queue = PointerQueue()
    private var released = false

    fun updateBounds(bounds: ExperienceArtboardSize) {
        artboardSize = bounds
    }

    fun enqueue(event: MotionEvent, viewportWidth: Int, viewportHeight: Int, density: Float): Boolean {
        val size = artboardSize ?: return false
        val transform = ExperienceLayoutTransform.create(size, viewportWidth.toFloat(), viewportHeight.toFloat(), density)
            ?: return false
        val projected = event.projectedPointers(transform) ?: return false
        synchronized(lock) {
            if (released) return false
            queue.enqueue(projected)
        }
        return true
    }

    /** The already queued native edit must not consume a later scene gesture. */
    fun takeNativeTap(x: Float, y: Float): List<List<NuxiePlayerPointerEvent>> = synchronized(lock) {
        if (released) emptyList() else listOf(queue.takeNativeTap(x, y))
    }

    fun takeBatch(): List<NuxiePlayerPointerEvent> = synchronized(lock) {
        if (released) emptyList() else queue.takeBatch()
    }

    fun reset() {
        synchronized(lock) {
            if (!released) queue.cancelDelivered()
        }
    }

    fun release() {
        synchronized(lock) {
            released = true
            queue.clear()
        }
    }

    private class PointerQueue {
        private val events = mutableListOf<NuxiePlayerPointerEvent>()
        private val activePointerIds = mutableSetOf<Int>()
        private val deliveredPointers = mutableMapOf<Int, NuxiePlayerPointerEvent>()

        fun enqueue(incoming: List<NuxiePlayerPointerEvent>) {
            incoming.forEach { event ->
                when (event.kind) {
                    NuxiePlayerPointerKind.MOVE -> {
                        // Touch MOVE cannot establish a new gesture after hiding.
                        if (event.pointerId !in activePointerIds) return@forEach
                        removeSupersededMove(event.pointerId)
                        if (!hasCapacity(1)) return@forEach
                    }
                    NuxiePlayerPointerKind.DOWN -> {
                        if (event.pointerId in activePointerIds) {
                            if (!hasCapacity(1)) return@forEach
                        } else if (!reserveNewPointer(event.pointerId)) {
                            return@forEach
                        }
                    }
                    NuxiePlayerPointerKind.UP,
                    NuxiePlayerPointerKind.EXIT,
                    -> {
                        // A retained native control may still react to UP after
                        // EXIT. Only a DOWN in this visible interval owns release.
                        if (!activePointerIds.remove(event.pointerId)) return@forEach
                    }
                }
                events += event
            }
        }

        fun takeNativeTap(x: Float, y: Float): List<NuxiePlayerPointerEvent> {
            // A reserved two-event slot belongs to the admitted native edit.
            // Ordinary scene pointers keep their place for the next frame.
            events.addAll(0, listOf(
                NuxiePlayerPointerEvent(NuxiePlayerPointerKind.DOWN, x, y, 63, 0f),
                NuxiePlayerPointerEvent(NuxiePlayerPointerKind.UP, x, y, 63, 0f),
            ))
            return takeBatch(maximumCount = 2)
        }

        fun takeBatch(maximumCount: Int = MAXIMUM_ACTIVE_POINTERS): List<NuxiePlayerPointerEvent> {
            val count = min(events.size, maximumCount)
            if (count == 0) return emptyList()
            return events.subList(0, count).toList().also { batch ->
                events.subList(0, count).clear()
                batch.forEach { event ->
                    when (event.kind) {
                        NuxiePlayerPointerKind.DOWN, NuxiePlayerPointerKind.MOVE ->
                            deliveredPointers[event.pointerId] = event
                        NuxiePlayerPointerKind.UP, NuxiePlayerPointerKind.EXIT ->
                            deliveredPointers.remove(event.pointerId)
                    }
                }
            }
        }

        fun cancelDelivered() {
            events.clear()
            activePointerIds.clear()
            // A retained player still owns pointers from earlier frames.
            // Keep them until EXIT is consumed, so repeated hides cannot lose
            // cancellation, and enqueue exits before any resumed gesture.
            deliveredPointers.values.sortedBy { it.pointerId }.forEach { event ->
                events += event.copy(kind = NuxiePlayerPointerKind.EXIT)
            }
        }

        fun clear() {
            events.clear()
            activePointerIds.clear()
            deliveredPointers.clear()
        }

        private fun reserveNewPointer(pointerId: Int): Boolean {
            if (!hasCapacity(2)) return false
            if (activePointerIds.size >= MAXIMUM_ACTIVE_POINTERS) return false
            activePointerIds += pointerId
            return true
        }

        private fun hasCapacity(additionalEvents: Int): Boolean =
            events.size + activePointerIds.size + additionalEvents <= MAXIMUM_EVENT_COUNT

        private fun removeSupersededMove(pointerId: Int) {
            for (index in events.indices.reversed()) {
                val event = events[index]
                if (event.pointerId != pointerId) continue
                if (event.kind == NuxiePlayerPointerKind.MOVE) events.removeAt(index)
                return
            }
        }

        private companion object {
            const val MAXIMUM_ACTIVE_POINTERS = 64
            const val MAXIMUM_EVENT_COUNT = MAXIMUM_ACTIVE_POINTERS * 2
        }
    }
}

private fun MotionEvent.projectedPointers(
    transform: ExperienceLayoutTransform,
): List<NuxiePlayerPointerEvent>? {
    val timestampSeconds = eventTime / 1_000f
    if (!timestampSeconds.isFinite() || timestampSeconds < 0f) return emptyList()

    fun pointer(index: Int, kind: NuxiePlayerPointerKind): NuxiePlayerPointerEvent? {
        val projected = transform.project(getX(index), getY(index)) ?: return null
        return NuxiePlayerPointerEvent(
            kind = kind,
            x = projected.first,
            y = projected.second,
            pointerId = getPointerId(index),
            timestampSeconds = timestampSeconds,
        )
    }

    return when (actionMasked) {
        MotionEvent.ACTION_DOWN,
        MotionEvent.ACTION_POINTER_DOWN,
        -> listOfNotNull(pointer(actionIndex, NuxiePlayerPointerKind.DOWN))
        MotionEvent.ACTION_MOVE -> (0 until pointerCount).mapNotNull {
            pointer(it, NuxiePlayerPointerKind.MOVE)
        }
        MotionEvent.ACTION_UP,
        MotionEvent.ACTION_POINTER_UP,
        -> listOfNotNull(pointer(actionIndex, NuxiePlayerPointerKind.UP))
        MotionEvent.ACTION_CANCEL -> (0 until pointerCount).mapNotNull {
            pointer(it, NuxiePlayerPointerKind.EXIT)
        }
        MotionEvent.ACTION_OUTSIDE -> listOfNotNull(
            pointer(actionIndex, NuxiePlayerPointerKind.EXIT),
        )
        else -> null
    }
}
