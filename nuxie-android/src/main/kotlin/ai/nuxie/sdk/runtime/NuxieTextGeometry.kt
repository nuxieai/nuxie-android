package ai.nuxie.sdk.runtime

/** Immutable values copied from the exact native step that settled the field. */
internal data class NuxieTextRunGeometry(
    val renderRevision: ULong,
    val worldTransform: Transform,
    val contentTransform: Transform,
    val textBounds: Bounds,
    val layout: Layout?,
    val firstBaseline: Float?,
) {
    data class Transform(val a: Float, val b: Float, val c: Float, val d: Float, val tx: Float, val ty: Float)
    data class Bounds(val minX: Float, val minY: Float, val maxX: Float, val maxY: Float)
    data class Layout(val transform: Transform, val bounds: Bounds)
}

/** A geometry failure must not discard an already-committed step journal. */
internal sealed interface NuxieTextGeometryCapture {
    data object NotRequested : NuxieTextGeometryCapture
    data class Captured(val fields: Map<String, NuxieTextRunGeometry>) : NuxieTextGeometryCapture
    data class Failed(val status: Int) : NuxieTextGeometryCapture
}

/** JNI transfer shapes. Mutable arrays are converted to immutable scalar values before delivery. */
internal data class NativeTextRunGeometry(
    val name: String,
    val renderRevision: Long,
    val worldTransform: FloatArray,
    val contentTransform: FloatArray,
    val textBounds: FloatArray,
    val hasLayout: Boolean,
    val layoutTransform: FloatArray,
    val layoutBounds: FloatArray,
    val hasFirstBaseline: Boolean,
    val firstBaseline: Float,
)

internal data class NativeTextGeometryCapture(val status: Int, val fields: Array<NativeTextRunGeometry>)

internal fun NativeTextGeometryCapture?.toTextGeometryCapture(): NuxieTextGeometryCapture {
    if (this == null) return NuxieTextGeometryCapture.NotRequested
    if (status != 0) return NuxieTextGeometryCapture.Failed(status)
    val copied = linkedMapOf<String, NuxieTextRunGeometry>()
    try {
        for (field in fields) {
            if (field.name in copied) return NuxieTextGeometryCapture.Failed(5) // NUX_STATUS_INVALID_ARGUMENT.
            require(field.renderRevision != 0L)
            require(field.firstBaseline.isFinite())
            copied[field.name] = NuxieTextRunGeometry(
                renderRevision = field.renderRevision.toULong(),
                worldTransform = field.worldTransform.toGeometryTransform(),
                contentTransform = field.contentTransform.toGeometryTransform(),
                textBounds = field.textBounds.toGeometryBounds(),
                layout = if (field.hasLayout) NuxieTextRunGeometry.Layout(
                    field.layoutTransform.toGeometryTransform(), field.layoutBounds.toGeometryBounds(),
                ) else null,
                firstBaseline = field.firstBaseline.takeIf { field.hasFirstBaseline },
            )
        }
    } catch (_: IllegalArgumentException) {
        return NuxieTextGeometryCapture.Failed(4) // NUX_STATUS_RUNTIME_ERROR.
    }
    return NuxieTextGeometryCapture.Captured(copied)
}

private fun FloatArray.toGeometryTransform(): NuxieTextRunGeometry.Transform {
    require(size == 6 && all(Float::isFinite))
    return NuxieTextRunGeometry.Transform(this[0], this[1], this[2], this[3], this[4], this[5])
}

private fun FloatArray.toGeometryBounds(): NuxieTextRunGeometry.Bounds {
    require(size == 4 && all(Float::isFinite) && this[2] >= this[0] && this[3] >= this[1])
    return NuxieTextRunGeometry.Bounds(this[0], this[1], this[2], this[3])
}
