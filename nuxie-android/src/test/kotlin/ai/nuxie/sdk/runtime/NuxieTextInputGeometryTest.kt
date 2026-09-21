package ai.nuxie.sdk.runtime

import org.junit.Assert.*
import org.junit.Test

class NuxieTextInputGeometryTest {
    private fun geometry() = NativeTextInputGeometry(
        -1L, floatArrayOf(2f, 0f, 0f, 3f, 40f, 50f), floatArrayOf(1f, 2f, 101f, 42f),
        true, floatArrayOf(1f, 0f, 0f, 1f, 10f, 20f), floatArrayOf(0f, 0f, 120f, 60f),
        true, 24f, true, false,
    )

    @Test fun `geometry preserves affine placement flags and unsigned revision without mutable arrays`() {
        val raw = geometry()
        val copied = raw.copied()
        raw.worldTransform.fill(0f)
        raw.textBounds.fill(0f)
        raw.layoutTransform.fill(0f)
        raw.layoutBounds.fill(0f)
        assertEquals(ULong.MAX_VALUE, copied.renderRevision)
        assertEquals(NuxieTextRunGeometry.Transform(2f, 0f, 0f, 3f, 40f, 50f), copied.worldTransform)
        assertEquals(NuxieTextRunGeometry.Bounds(1f, 2f, 101f, 42f), copied.textBounds)
        assertEquals(10f, copied.layout!!.transform.tx)
        assertEquals(120f, copied.layout.bounds.maxX)
        assertEquals(24f, copied.firstBaseline)
        assertTrue(copied.obscured)
        assertFalse(copied.multiline)
    }

    @Test fun `absent optional geometry ignores unspecified native values`() {
        val value = geometry().copy(hasLayout = false, layoutTransform = floatArrayOf(),
            layoutBounds = floatArrayOf(), hasFirstBaseline = false, firstBaseline = Float.NaN).copied()
        assertNull(value.layout)
        assertNull(value.firstBaseline)
    }

    @Test fun `malformed geometry fails rather than positioning an editor at a guessed location`() {
        for (raw in listOf(
            geometry().copy(renderRevision = 0),
            geometry().copy(worldTransform = floatArrayOf(1f)),
            geometry().copy(textBounds = floatArrayOf(10f, 0f, 1f, 20f)),
            geometry().copy(firstBaseline = Float.NaN),
            geometry().copy(layoutTransform = FloatArray(6) { Float.POSITIVE_INFINITY }),
        )) assertThrows(IllegalArgumentException::class.java) { raw.copied() }
    }
}
