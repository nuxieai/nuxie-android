package ai.nuxie.sdk.runtime

/** Display surface expressed in root-artboard coordinates. */
internal data class VideoViewport(val minX: Float, val minY: Float, val maxX: Float, val maxY: Float) {
    init {
        require(listOf(minX, minY, maxX, maxY).all { it.isFinite() })
        require(minX <= maxX && minY <= maxY)
    }

    companion object {
        fun layout(surfaceWidth: Int, surfaceHeight: Int, density: Float,
            originX: Float = 0f, originY: Float = 0f): VideoViewport {
            require(density.isFinite() && density > 0f)
            if (surfaceWidth <= 0 || surfaceHeight <= 0) return VideoViewport(originX, originY, originX, originY)
            return VideoViewport(originX, originY, originX + surfaceWidth / density, originY + surfaceHeight / density)
        }
    }
}
