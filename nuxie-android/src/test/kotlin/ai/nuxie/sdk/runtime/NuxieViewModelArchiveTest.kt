package ai.nuxie.sdk.runtime

import org.junit.Assert.*
import org.junit.Test

class NuxieViewModelArchiveTest {
    @Test
    fun `restore remaps shared references and list order without replaying generated state`() {
        val native = RecordingNative()
        val archive = archive()
        val state = archive.restore(native, NuxieRuntimeFile(7, native), NuxieRuntimeArtboard(8, native) { native.catalog.toViewModelCatalog() })
        assertEquals(listOf(0, 1, 1), native.schemas.values.toList())
        assertEquals(100L, native.boundRoot)
        val list = native.writes.filter { it.second.path == "plans" }.map { it.second }
        assertEquals(listOf(NuxieViewModelMutationKind.LIST_CLEAR, NuxieViewModelMutationKind.LIST_INSERT, NuxieViewModelMutationKind.LIST_INSERT), list.map { it.kind })
        assertEquals(listOf(102L, 101L), list.drop(1).map { it.relatedViewModel })
        assertEquals(listOf(0L, 1L), list.drop(1).map { it.index })
        assertEquals(102L, native.writes.single { it.second.path == "selected" }.second.relatedViewModel)
        assertEquals("second", native.writes.single { it.first == 102L && it.second.path == "placementId" }.second.bytesValue.decodeToString())
        assertFalse(native.writes.any { it.second.path in setOf("env", "fontScale", "nuxieLayoutPaint", "generated") })
        state.close()
        assertEquals(setOf(100L, 101L, 102L), native.freed.toSet())
        assertEquals(3, native.freed.size)
    }

    @Test
    fun `capture owns its bytes and list membership`() {
        val bytes = "second".encodeToByteArray()
        val list = longArrayOf(20, 10)
        val archive = archive(bytes, list)
        bytes.fill(0)
        list.fill(10)
        val native = RecordingNative()
        val state = archive.restore(native, NuxieRuntimeFile(7, native), NuxieRuntimeArtboard(8, native) { native.catalog.toViewModelCatalog() })
        assertEquals("second", native.writes.single { it.first == 102L && it.second.path == "placementId" }.second.bytesValue.decodeToString())
        assertEquals(102L, native.writes.first { it.second.kind == NuxieViewModelMutationKind.LIST_INSERT }.second.relatedViewModel)
        state.close()
    }

    @Test
    fun `failed mutation releases every new handle without binding partial state`() {
        val native = RecordingNative(failWrites = true)
        assertThrows(IllegalStateException::class.java) {
            archive().restore(native, NuxieRuntimeFile(7, native), NuxieRuntimeArtboard(8, native) { native.catalog.toViewModelCatalog() })
        }
        assertNull(native.boundRoot)
        assertEquals(setOf(100L, 101L, 102L), native.freed.toSet())
        assertEquals(3, native.freed.size)
    }

    private fun archive(bytes: ByteArray = "second".encodeToByteArray(), list: LongArray = longArrayOf(20, 10)): NuxieViewModelArchive {
        fun entry(owner: Long, index: Long, name: String, kind: Int, ref: Long = 0, text: ByteArray = byteArrayOf(), items: LongArray = longArrayOf()) =
            NativeViewModelSnapshotValue(owner, index, name, kind, text, ref, listItemIds = items)
        return NuxieViewModelArchive.capture(NativeViewModelSnapshot(1,
            arrayOf(NativeViewModelSnapshotInstance(1, 0), NativeViewModelSnapshotInstance(10, 1), NativeViewModelSnapshotInstance(20, 1),
                NativeViewModelSnapshotInstance(30, 2), NativeViewModelSnapshotInstance(40, 2)),
            arrayOf(entry(1, 0, "plans", 8, items = list), entry(1, 1, "selected", 9, 20),
                entry(1, 2, "env", 9, 30), entry(1, 3, "fontScale", 2),
                entry(10, 0, "placementId", 1, text = "first".encodeToByteArray()),
                entry(20, 0, "placementId", 1, text = bytes), entry(20, 1, "nuxieLayoutPaint", 9, 40),
                entry(30, 0, "generated", 2), entry(40, 0, "generated", 2))),
            mapOf(0L to "Root", 1L to "Plan", 2L to "Generated"), mapOf("root" to 1, "first" to 10, "second" to 20))
    }

    private class RecordingNative(private val failWrites: Boolean = false) : NuxieTypedRuntimeNative {
        val schemas = linkedMapOf<Long, Int>()
        val writes = mutableListOf<Pair<Long, NativeViewModelWrite>>()
        val freed = mutableListOf<Long>()
        var boundRoot: Long? = null
        val catalog = NativeViewModelCatalog(
            arrayOf(NativeViewModelSchema(0, "Root", 0, 4, 0, 0, -1, false),
                NativeViewModelSchema(1, "Plan", 4, 2, 0, 0, -1, false),
                NativeViewModelSchema(2, "Generated", 6, 1, 0, 0, -1, false)),
            arrayOf(NativeViewModelProperty(0, 0, "plans", 8, 1, emptyArray()),
                NativeViewModelProperty(0, 1, "selected", 9, 1, emptyArray()),
                NativeViewModelProperty(0, 2, "env", 9, 2, emptyArray()),
                NativeViewModelProperty(0, 3, "fontScale", 2, -1, emptyArray()),
                NativeViewModelProperty(1, 0, "placementId", 1, -1, emptyArray()),
                NativeViewModelProperty(1, 1, "nuxieLayoutPaint", 9, 2, emptyArray()),
                NativeViewModelProperty(2, 0, "generated", 2, -1, emptyArray())), emptyArray())
        override fun viewModelCatalog(fileHandle: Long) = NativeCallResult(0, catalog)
        override fun newDefaultViewModel(artboardHandle: Long) = allocate(0)
        override fun newViewModel(fileHandle: Long, schemaIndex: Int, authoredInstanceIndex: Int?) = allocate(schemaIndex)
        private fun allocate(schema: Int): NativeCallResult<Long> {
            val handle = 100L + schemas.size
            schemas[handle] = schema
            return NativeCallResult(0, handle)
        }
        override fun viewModelRootSchemaIndex(viewModelHandle: Long) = NativeCallResult(0, schemas.getValue(viewModelHandle).toLong())
        override fun mutateViewModel(handle: Long, write: NativeViewModelWrite): Int {
            if (failWrites) return 4
            writes += handle to write
            return 0
        }
        override fun bindViewModel(artboardHandle: Long, viewModelHandle: Long): Int { boundRoot = viewModelHandle; return 0 }
        override fun snapshotViewModel(viewModelHandle: Long) = NativeCallResult(0, NativeViewModelSnapshot(viewModelHandle + 1000,
            arrayOf(NativeViewModelSnapshotInstance(viewModelHandle + 1000, schemas.getValue(viewModelHandle).toLong())), emptyArray()))
        override fun freeViewModel(handle: Long): Int { freed += handle; return 0 }
    }
}
