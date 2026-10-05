package ai.nuxie.sdk.presentation

internal data class ExperienceSurfaceLayout private constructor(
    val pixelWidth: Int,
    val pixelHeight: Int,
    val density: Float,
    val widthPoints: Float,
    val heightPoints: Float,
) {
    companion object {
        fun create(width: Int, height: Int, density: Float): ExperienceSurfaceLayout? {
            if (width <= 0 || height <= 0 || !density.isFinite() || density <= 0f) return null
            val widthPoints = width / density
            val heightPoints = height / density
            if (!widthPoints.isFinite() || widthPoints <= 0f || !heightPoints.isFinite() || heightPoints <= 0f) return null
            return ExperienceSurfaceLayout(width, height, density, widthPoints, heightPoints)
        }
    }
}
