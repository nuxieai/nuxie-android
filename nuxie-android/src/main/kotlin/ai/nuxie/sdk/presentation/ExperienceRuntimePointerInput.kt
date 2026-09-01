package ai.nuxie.sdk.presentation

import ai.nuxie.sdk.runtime.NuxiePlayerPointerEvent
import ai.nuxie.sdk.runtime.NuxiePlayerPointerKind
import android.view.MotionEvent
import kotlin.math.min

/** Authored extent used by both centered-contain rendering and pointer projection. */
internal data class ExperienceArtboardSize(val width: Float, val height: Float) {
    init {
        require(width.isFinite() && width > 0f) { "Artboard width must be finite and positive" }
        require(height.isFinite() && height > 0f) { "Artboard height must be finite and positive" }
    }
}

/**
 * Bounded UI-thread input staging for one runtime presentation.
 *
 * Android coordinates are projected through the same centered-contain
 * geometry used by the renderer. Events remain queued until one runtime
 * frame consumes them; release atomically clears the queue and permanently
 * closes this presentation's input seam.
 */
internal class ExperienceRuntimePointerInput(
    private val artboardSize: ExperienceArtboardSize?,
) {
    private val lock = Any()
    private val queue = PointerQueue()
    private var released = false

    fun enqueue(event: MotionEvent, viewportWidth: Int, viewportHeight: Int): Boolean {
        val size = artboardSize ?: return false
        val transform = ContainCenterTransform.create(size, viewportWidth, viewportHeight)
            ?: return false
        val projected = event.projectedPointers(transform) ?: return false
        synchronized(lock) {
            if (released) return false
            queue.enqueue(projected)
        }
        return true
    }

    fun takeBatch(): List<NuxiePlayerPointerEvent> = synchronized(lock) {
        if (released) emptyList() else queue.takeBatch()
    }

    fun reset() {
        synchronized(lock) {
            if (!released) queue.clear()
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

        fun enqueue(incoming: List<NuxiePlayerPointerEvent>) {
            incoming.forEach { event ->
                when (event.kind) {
                    NuxiePlayerPointerKind.MOVE -> {
                        if (event.pointerId in activePointerIds) {
                            removeSupersededMove(event.pointerId)
                            if (!hasCapacity(1)) return@forEach
                        } else if (!reserveNewPointer(event.pointerId)) {
                            return@forEach
                        }
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
                        if (!activePointerIds.remove(event.pointerId) && !hasCapacity(1)) {
                            return@forEach
                        }
                    }
                }
                events += event
            }
        }

        fun takeBatch(): List<NuxiePlayerPointerEvent> {
            val count = min(events.size, MAXIMUM_ACTIVE_POINTERS)
            if (count == 0) return emptyList()
            return events.subList(0, count).toList().also {
                events.subList(0, count).clear()
            }
        }

        fun clear() {
            events.clear()
            activePointerIds.clear()
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

private data class ContainCenterTransform(
    val scale: Float,
    val contentLeft: Float,
    val contentTop: Float,
) {
    fun project(x: Float, y: Float): Pair<Float, Float>? {
        val projectedX = (x - contentLeft) / scale
        val projectedY = (y - contentTop) / scale
        return if (projectedX.isFinite() && projectedY.isFinite()) {
            projectedX to projectedY
        } else {
            null
        }
    }

    companion object {
        fun create(
            artboard: ExperienceArtboardSize,
            viewportWidth: Int,
            viewportHeight: Int,
        ): ContainCenterTransform? {
            if (viewportWidth <= 0 || viewportHeight <= 0) return null
            val scale = min(
                viewportWidth.toFloat() / artboard.width,
                viewportHeight.toFloat() / artboard.height,
            )
            if (!scale.isFinite() || scale <= 0f) return null
            val contentWidth = artboard.width * scale
            val contentHeight = artboard.height * scale
            return ContainCenterTransform(
                scale = scale,
                contentLeft = (viewportWidth - contentWidth) / 2f,
                contentTop = (viewportHeight - contentHeight) / 2f,
            )
        }
    }
}

private fun MotionEvent.projectedPointers(
    transform: ContainCenterTransform,
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
