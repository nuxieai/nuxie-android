package ai.nuxie.sdk.presentation

import android.graphics.Rect
import android.view.View

/** Spatial selection in screen coordinates; authored order breaks equal-distance ties. */
internal object ExperienceDirectionalFocus {
    fun next(source: Rect, direction: Int, candidates: List<Pair<Long, Rect>>): Long? {
        val horizontal = direction == View.FOCUS_LEFT || direction == View.FOCUS_RIGHT
        val sign = when (direction) {
            View.FOCUS_RIGHT, View.FOCUS_DOWN -> 1
            View.FOCUS_LEFT, View.FOCUS_UP -> -1
            else -> return null
        }
        val sourceAxis = if (horizontal) (source.left.toDouble() + source.right) / 2 else (source.top.toDouble() + source.bottom) / 2
        val sourceCross = if (horizontal) (source.top.toDouble() + source.bottom) / 2 else (source.left.toDouble() + source.right) / 2
        data class Ranked(val id: Long, val offAxis: Boolean, val distance: Double)
        return candidates.mapNotNull { (id, rect) ->
            if (rect.isEmpty) return@mapNotNull null
            val axis = if (horizontal) (rect.left.toDouble() + rect.right) / 2 else (rect.top.toDouble() + rect.bottom) / 2
            val cross = if (horizontal) (rect.top.toDouble() + rect.bottom) / 2 else (rect.left.toDouble() + rect.right) / 2
            val forward = (axis - sourceAxis) * sign
            if (forward <= 0) return@mapNotNull null
            val aligned = if (horizontal) rect.top < source.bottom && rect.bottom > source.top
                else rect.left < source.right && rect.right > source.left
            val sideways = cross - sourceCross
            Ranked(id, !aligned, forward * forward + sideways * sideways)
        }.minWithOrNull(compareBy<Ranked> { it.offAxis }.thenBy { it.distance })?.id
    }
}
