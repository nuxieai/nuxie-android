package ai.nuxie.sdk.presentation

import ai.nuxie.sdk.runtime.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class ExperienceRunListCleanupTest {
    @Test fun `restoration closes every acquired handle and preserves its primary failure`() {
        for (failWrite in listOf(false, true)) {
            val primary = IllegalStateException("write failed")
            val cleanup = IllegalStateException("close failed")
            val freed = mutableListOf<Long>()
            val native = object : NuxieTypedRuntimeNative {
                override fun acquireListItem(owner: Long, path: String, index: Int, expectedIdentity: Long) =
                    NativeCallResult(0, listOf(20L, 30L)[index])
                override fun viewModelRootSchemaIndex(viewModelHandle: Long) = NativeCallResult(0, 1L)
                override fun snapshotViewModel(viewModelHandle: Long): NativeCallResult<NativeViewModelSnapshot> {
                    val snapshot = if (viewModelHandle == 10L) NativeViewModelSnapshot(10,
                        arrayOf(NativeViewModelSnapshotInstance(10, 0),
                            NativeViewModelSnapshotInstance(20, 1), NativeViewModelSnapshotInstance(30, 1)),
                        arrayOf(NativeViewModelSnapshotValue(10, 0, "goals", NuxieViewModelPropertyKind.LIST.nativeValue,
                            byteArrayOf(), 0, listItemIds = longArrayOf(20, 30))))
                        else NativeViewModelSnapshot(viewModelHandle,
                            arrayOf(NativeViewModelSnapshotInstance(viewModelHandle, 1)), emptyArray())
                    return NativeCallResult(0, snapshot)
                }
                override fun mutateViewModel(handle: Long, write: NativeViewModelWrite): Int {
                    if (failWrite) throw primary
                    return 0
                }
                override fun freeFile(handle: Long) {}
                override fun freeViewModel(handle: Long): Int {
                    freed += handle
                    if (handle == 30L) throw cleanup
                    return 0
                }
            }
            val root = NuxieRuntimeViewModelState(10, emptyList(), native,
                NuxieViewModelCatalog(emptyList(), emptyList(), emptyList()), 0)
            val graph = ExperienceRunListSnapshot(listOf(
                ExperienceRunListSnapshot.Node(0, emptyList(),
                    Json.parseToJsonElement("""[{"path":"count","kind":2,"value":2}]""").jsonArray,
                    listOf(ExperienceRunListSnapshot.Items("goals", listOf(1, 2))), emptyList()),
                ExperienceRunListSnapshot.Node(1, listOf(ExperienceRunListSnapshot.OriginStep("goals", 0)),
                    JsonArray(emptyList()), emptyList(), emptyList()),
                ExperienceRunListSnapshot.Node(1, listOf(ExperienceRunListSnapshot.OriginStep("goals", 1)),
                    JsonArray(emptyList()), emptyList(), emptyList()),
            ))
            val file = NuxieRuntimeFile(1, native)
            try {
                val thrown = assertThrows(IllegalStateException::class.java) {
                    graph.restore(file, root)
                }
                assertSame(if (failWrite) primary else cleanup, thrown)
                assertEquals(listOf(30L, 20L), freed)
                assertEquals(if (failWrite) listOf(cleanup) else emptyList<Throwable>(), thrown.suppressed.toList())
            } finally { try { root.close() } finally { file.close() } }
        }
    }
}
