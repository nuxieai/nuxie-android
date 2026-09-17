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
)

internal data class NuxieVideoAction(val kind: Int, val value: Double, val generation: Long)
