package ai.nuxie.sdk.presentation

import ai.nuxie.sdk.runtime.NuxieViewModelScalarValue
import kotlin.math.max
import kotlin.math.min

/** Insets in the coordinate space of the rendering view or authored artboard. */
internal data class ExperienceSafeAreaInsets(
    val top: Double,
    val bottom: Double,
    val left: Double,
    val right: Double,
) {
    fun stateValues(): Map<String, NuxieViewModelScalarValue> = linkedMapOf(
        "safeArea/top" to NuxieViewModelScalarValue.NumberValue(top),
        "safeArea/bottom" to NuxieViewModelScalarValue.NumberValue(bottom),
        "safeArea/left" to NuxieViewModelScalarValue.NumberValue(left),
        "safeArea/right" to NuxieViewModelScalarValue.NumberValue(right),
    )

    companion object {
        val ZERO = ExperienceSafeAreaInsets(0.0, 0.0, 0.0, 0.0)
    }
}

internal object ExperienceSafeAreaInsetMapper {
    /** Same contain/center transform as rendering and native text overlays. */
    fun artboardInsets(
        viewInsets: ExperienceSafeAreaInsets,
        viewWidth: Double,
        viewHeight: Double,
        artboardWidth: Double,
        artboardHeight: Double,
    ): ExperienceSafeAreaInsets {
        if (listOf(viewWidth, viewHeight, artboardWidth, artboardHeight).any { !it.isFinite() || it <= 0 }) {
            return ExperienceSafeAreaInsets.ZERO
        }
        val scale = min(viewWidth / artboardWidth, viewHeight / artboardHeight)
        if (!scale.isFinite() || scale <= 0) return ExperienceSafeAreaInsets.ZERO
        val letterboxX = (viewWidth - artboardWidth * scale) / 2
        val letterboxY = (viewHeight - artboardHeight * scale) / 2
        fun corrected(inset: Double, letterbox: Double): Double =
            if (inset.isFinite()) max(0.0, (inset - letterbox) / scale) else 0.0
        return ExperienceSafeAreaInsets(
            corrected(viewInsets.top, letterboxY),
            corrected(viewInsets.bottom, letterboxY),
            corrected(viewInsets.left, letterboxX),
            corrected(viewInsets.right, letterboxX),
        )
    }
}
