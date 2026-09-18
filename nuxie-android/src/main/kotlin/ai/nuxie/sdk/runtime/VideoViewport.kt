package ai.nuxie.sdk.runtime

/** Display surface expressed in root-artboard coordinates. */
internal data class VideoViewport(val minX: Float, val minY: Float, val maxX: Float, val maxY: Float) {
    init {
        require(listOf(minX, minY, maxX, maxY).all { it.isFinite() })
        require(minX <= maxX && minY <= maxY)
    }

    companion object {
        /** Inverse of the Vulkan renderer's centered contain fit. */
        fun contain(artboardWidth: Float, artboardHeight: Float, surfaceWidth: Int, surfaceHeight: Int): VideoViewport {
            require(artboardWidth.isFinite() && artboardWidth > 0 && artboardHeight.isFinite() && artboardHeight > 0)
            if (surfaceWidth <= 0 || surfaceHeight <= 0) return VideoViewport(0f, 0f, 0f, 0f)
            val scale = minOf(surfaceWidth.toDouble() / artboardWidth, surfaceHeight.toDouble() / artboardHeight)
            val width = surfaceWidth / scale
            val height = surfaceHeight / scale
            return VideoViewport(((artboardWidth - width) / 2).toFloat(), ((artboardHeight - height) / 2).toFloat(),
                ((artboardWidth + width) / 2).toFloat(), ((artboardHeight + height) / 2).toFloat())
        }
    }
}
