package ai.nuxie.sdk.presentation

/** The view's pixels mapped to root layout points, including the artboard origin. */
internal data class ExperienceLayoutTransform(
    val scale: Float,
    val contentLeft: Float,
    val contentTop: Float,
) {
    fun project(x: Float, y: Float): Pair<Float, Float>? {
        val projectedX = (x - contentLeft) / scale
        val projectedY = (y - contentTop) / scale
        return if (projectedX.isFinite() && projectedY.isFinite()) projectedX to projectedY else null
    }

    companion object {
        fun create(
            artboard: ExperienceArtboardSize,
            viewportWidth: Float,
            viewportHeight: Float,
            density: Float,
        ): ExperienceLayoutTransform? {
            if (listOf(viewportWidth, viewportHeight, density).any { !it.isFinite() || it <= 0f }) return null
            val left = -artboard.originX * density
            val top = -artboard.originY * density
            if (!left.isFinite() || !top.isFinite()) return null
            return ExperienceLayoutTransform(density, left, top)
        }
    }
}
