package ai.nuxie.sdk.runtime

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

internal data class NuxieVideoCaptionCue(val startSeconds: Double, val endSeconds: Double, val text: String)
internal data class NuxieVideoCaption(val language: String, val text: String)
