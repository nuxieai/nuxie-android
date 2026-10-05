package ai.nuxie.sdk.presentation

import ai.nuxie.sdk.runtime.NuxieViewModelScalarValue

/** Insets relative to the rendering view. */
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

/** Convert local Android pixels to layout points at the view's density. */
internal fun experienceSafeAreaInsets(pixels: ExperienceSafeAreaInsets, density: Float): ExperienceSafeAreaInsets {
    if (!density.isFinite() || density <= 0f) return ExperienceSafeAreaInsets.ZERO
    fun points(value: Double) = if (value.isFinite()) value / density else 0.0
    return ExperienceSafeAreaInsets(points(pixels.top), points(pixels.bottom), points(pixels.left), points(pixels.right))
}
