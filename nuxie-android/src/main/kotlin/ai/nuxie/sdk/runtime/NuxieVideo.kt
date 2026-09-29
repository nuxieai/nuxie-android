package ai.nuxie.sdk.runtime

import android.hardware.HardwareBuffer

/** Owned copies of callback views; component identities belong to one player. */
internal data class NuxieVideoOccurrence(
    val componentId: Long,
    val assetId: Long,
    val generation: Long,
    val state: Int,
    val wantsPlay: Boolean,
    val audioPolicy: Int,
    val sourceKey: String,
    val contentType: String,
    val embedded: Boolean,
    val componentName: String = "",
    val priority: Int = 0,
    val readiness: Int = 0,
    val sourceArtboardIndex: Long,
    val sourceComponentId: Long,
)

internal data class NuxieVideoAction(val kind: Int, val value: Double, val generation: Long)

internal data class NuxieVideoClock(
    val generation: Long, val seconds: Double, val rate: Double,
    val playing: Boolean, val available: Boolean,
)

internal data class NuxieVideoFrame(
    val generation: Long, val seconds: Double, val width: Int, val height: Int, val rgba: ByteArray,
) {
    init {
        require(width in 1..8192 && height in 1..8192)
        val pixels = width.toLong() * height
        require(pixels <= 16_777_216 && rgba.size.toLong() == pixels * 4)
        require(seconds.isFinite() && seconds >= 0)
    }
}

/**
 * A decoded frame in the decoder's hardware buffer, which the runtime imports
 * and converts on the GPU. Crop edges are buffer pixels; rotation is clockwise
 * from buffer to display; the display size is after the rotation and differs
 * from the crop for video with non-square pixels; matrix is 1 BT.601, 2 BT.709
 * or 3 BT.2020, and range 1 limited or 2 full. The caller keeps the buffer
 * open until present returns.
 */
internal data class NuxieVideoHardwareBufferFrame(
    val generation: Long, val seconds: Double, val buffer: HardwareBuffer,
    val cropLeft: Int, val cropTop: Int, val cropRight: Int, val cropBottom: Int,
    val rotationDegrees: Int, val displayWidth: Int, val displayHeight: Int,
    val colorMatrix: Int, val colorRange: Int,
) {
    init {
        require(cropLeft >= 0 && cropTop >= 0 && cropRight > cropLeft && cropBottom > cropTop)
        require(displayWidth in 1..8192 && displayHeight in 1..8192)
        require(displayWidth.toLong() * displayHeight <= 16_777_216)
        require(rotationDegrees == 0 || rotationDegrees == 90 || rotationDegrees == 180 || rotationDegrees == 270)
        require(colorMatrix in 1..3 && colorRange in 1..2)
        require(seconds.isFinite() && seconds >= 0)
    }
}

internal data class NuxieVideoCaptionCue(val startSeconds: Double, val endSeconds: Double, val text: String)
internal data class NuxieVideoCaption(val language: String, val text: String)
