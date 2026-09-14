package ai.nuxie.sdk.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class NuxieViewModelGeometryTest {
    @Test
    fun geometryResolvesNestedNumbersAndOptionalRootLabel() {
        val snapshot = snapshot(
            value("input", NuxieViewModelPropertyKind.VIEW_MODEL, reference = 2),
            value("x", number = 12.5f, owner = 2),
            value("width", number = 80f),
        )
        assertEquals(12.5f, snapshot.resolveGeometryNumber("input/x"))
        assertEquals(12.5f, snapshot.resolveGeometryNumber("Root/input/x"))
        assertEquals(12.5f, snapshot.resolveGeometryNumber("/Root//input/x/"))
        assertEquals(80f, snapshot.resolveGeometryNumber("width"))
        assertEquals(80f, snapshot.resolveGeometryNumber("Root/width"))
        assertNull(snapshot.resolveGeometryNumber("Root/input/missing"))
        assertNull(snapshot.resolveGeometryNumber("Root/input"))
        assertNull(snapshot.resolveGeometryNumber("Root.input.x"))
        assertNull(snapshot.resolveGeometryNumber("/"))
        assertNull(snapshot.resolveString("input/x"))
    }

    @Test
    fun geometryRejectsNonFiniteAndWrongTypeWithoutSkippingARealRootProperty() {
        val snapshot = snapshot(
            value("input", NuxieViewModelPropertyKind.STRING),
            value("x", number = 5f),
            value("nan", number = Float.NaN),
            value("infinity", number = Float.POSITIVE_INFINITY),
        )
        assertNull(snapshot.resolveGeometryNumber("input/x"))
        assertNull(snapshot.resolveGeometryNumber("nan"))
        assertNull(snapshot.resolveGeometryNumber("infinity"))
        assertThrows(IllegalStateException::class.java) {
            snapshot(value("x", number = 1f), value("x", number = 2f))
        }
    }

    private fun snapshot(vararg values: NativeViewModelSnapshotValue) =
        NuxieViewModelSnapshot.fromNative(NativeViewModelSnapshot(
            rootInstanceId = 1,
            instances = arrayOf(
                NativeViewModelSnapshotInstance(1, 0),
                NativeViewModelSnapshotInstance(2, 1),
            ),
            values = arrayOf(*values),
        ))

    private fun value(
        name: String,
        kind: NuxieViewModelPropertyKind = NuxieViewModelPropertyKind.NUMBER,
        number: Float = 0f,
        owner: Long = 1,
        reference: Long = 0,
    ) = NativeViewModelSnapshotValue(owner, 0, name, kind.nativeValue, byteArrayOf(), reference, number)
}
