package ai.nuxie.sdk.runtime

import org.junit.Assert.*
import org.junit.Test

class NuxieFieldViewModelTest {
    private class Native : NuxieTypedRuntimeNative {
        var value = "before"
        var status = 0
        var reads = 0
        val freed = mutableListOf<Long>()
        val writes = mutableListOf<NativeViewModelWrite>()
        override fun viewModelRootSchemaIndex(viewModelHandle: Long) = NativeCallResult(0, 0L)
        override fun mutateViewModel(viewModelHandle: Long, write: NativeViewModelWrite): Int {
            assertEquals(77L, viewModelHandle)
            writes += write
            return status
        }
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

    @Test fun `input action writes its value before triggering the same occurrence owner`() {
        val native = Native()
        val owner = NuxieFieldViewModel(77, native)
        assertTrue(owner.commitTextInput(catalog(), "field", "typed value"))
        assertEquals(listOf(NuxieViewModelMutationKind.SET_STRING, NuxieViewModelMutationKind.FIRE_TRIGGER),
            native.writes.map { it.kind })
        assertEquals(listOf("controls/field/value", "controls/field/commit"), native.writes.map { it.path })
        assertEquals("typed value", native.writes.first().bytesValue.decodeToString())
        owner.close()
    }

    @Test fun `ordinary input has no scripted commit and malformed controls fail before writes`() {
        val native = Native()
        val owner = NuxieFieldViewModel(77, native)
        assertFalse(owner.commitTextInput(catalog().copy(properties = emptyList()), "field", "text"))
        val malformed = catalog().let { catalog -> catalog.copy(properties = catalog.properties.map {
            if (it.name == "commit") it.copy(kind = NuxieViewModelPropertyKind.STRING) else it
        }) }
        assertThrows(IllegalArgumentException::class.java) { owner.commitTextInput(malformed, "field", "text") }
        assertTrue(native.writes.isEmpty())
        owner.close()
    }

    @Test fun `failed value write never fires the action trigger`() {
        val native = Native().apply { status = 4 }
        val owner = NuxieFieldViewModel(77, native)
        assertThrows(NuxieRuntimeCallException::class.java) { owner.commitTextInput(catalog(), "field", "text") }
        assertEquals(listOf(NuxieViewModelMutationKind.SET_STRING), native.writes.map { it.kind })
        owner.close()
    }

    private fun catalog() = NuxieViewModelCatalog(emptyList(), listOf(
        NuxieViewModelCatalog.Property(0, 0, "controls", NuxieViewModelPropertyKind.VIEW_MODEL, 1, emptyList()),
        NuxieViewModelCatalog.Property(1, 0, "field", NuxieViewModelPropertyKind.VIEW_MODEL, 2, emptyList()),
        NuxieViewModelCatalog.Property(2, 0, "value", NuxieViewModelPropertyKind.STRING, null, emptyList()),
        NuxieViewModelCatalog.Property(2, 1, "commit", NuxieViewModelPropertyKind.TRIGGER, null, emptyList()),
    ), emptyList())

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
