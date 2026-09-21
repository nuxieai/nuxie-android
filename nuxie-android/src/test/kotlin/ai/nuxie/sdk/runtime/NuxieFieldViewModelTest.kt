package ai.nuxie.sdk.runtime

import org.junit.Assert.*
import org.junit.Test

class NuxieFieldViewModelTest {
    private class Native : NuxieTypedRuntimeNative {
        var value = "before"
        var status = 0
        var reads = 0
        val freed = mutableListOf<Long>()
        override fun snapshotViewModel(viewModelHandle: Long): NativeCallResult<NativeViewModelSnapshot> {
            assertEquals(77L, viewModelHandle)
            reads++
            return NativeCallResult(status, if (status == 0) NativeViewModelSnapshot(42,
                arrayOf(NativeViewModelSnapshotInstance(42, 0)),
                arrayOf(NativeViewModelSnapshotValue(42, 0, "value", 1, value.encodeToByteArray(), 0)),
            ) else null)
        }
        override fun freeViewModel(handle: Long): Int { freed += handle; return 0 }
    }

    @Test fun `field reference sees live occurrence state and closes exactly once`() {
        val native = Native()
        val owner = NuxieFieldViewModel(77, native)
        assertEquals(NuxieViewModelScalarValue.StringValue("before"), owner.snapshot().resolveScalar(listOf("value")))
        native.value = "after"
        assertEquals(NuxieViewModelScalarValue.StringValue("after"), owner.snapshot().resolveScalar(listOf("value")))
        owner.close()
        owner.close()
        assertEquals(listOf(77L), native.freed)
        assertThrows(IllegalStateException::class.java) { owner.snapshot() }
        assertEquals(2, native.reads)
    }

    @Test fun `failed snapshot neither leaks a value nor prevents cleanup`() {
        val native = Native().apply { status = 9; value = "private-value" }
        val owner = NuxieFieldViewModel(77, native)
        val error = assertThrows(NuxieRuntimeCallException::class.java) { owner.snapshot() }
        assertEquals(9, error.status)
        assertFalse(error.message.orEmpty().contains(native.value))
        owner.close()
        assertEquals(listOf(77L), native.freed)
    }
}
