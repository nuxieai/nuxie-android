package ai.nuxie.sdk.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NuxieViewModelBooleanSnapshotTest {
    @Test
    fun `boolean snapshots preserve false and true independently through nested references`() {
        fun snapshot(value: Boolean) = NuxieViewModelSnapshot.fromNative(NativeViewModelSnapshot(1,
            arrayOf(NativeViewModelSnapshotInstance(1, 0), NativeViewModelSnapshotInstance(2, 1)),
            arrayOf(
                NativeViewModelSnapshotValue(1, 0, "env", 9, byteArrayOf(), 2),
                NativeViewModelSnapshotValue(2, 1, "reduceMotion", 3, byteArrayOf(), 0, boolValue = value),
                NativeViewModelSnapshotValue(2, 2, "number", 2, byteArrayOf(), 0, numberValue = 1f),
                NativeViewModelSnapshotValue(2, 3, "phase", 5, byteArrayOf(), 0, integerValue = 2),
            )))
        val before = snapshot(false)
        val after = snapshot(true)
        assertEquals(false, before.resolveBoolean("env/reduceMotion"))
        assertEquals(true, after.resolveBoolean("env.reduceMotion"))
        assertEquals(false, before.resolveBoolean("env.reduceMotion"))
        assertNull(after.resolveBoolean("env/number"))
        assertNull(after.resolveBoolean("env/missing"))
        assertNull(after.resolveBoolean("env//reduceMotion"))
        assertNull(after.resolveString("env/reduceMotion"))
        assertEquals(2L, after.resolveEnumOrdinal("env/phase"))
        assertNull(after.resolveBoolean("env/phase"))
        assertNull(after.resolveEnumOrdinal("env/number"))
    }
}
