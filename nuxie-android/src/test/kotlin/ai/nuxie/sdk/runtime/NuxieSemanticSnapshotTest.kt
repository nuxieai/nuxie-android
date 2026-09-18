package ai.nuxie.sdk.runtime

import ai.nuxie.sdk.fixtures.FixtureRunner
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.long
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.*
import org.junit.Test

class NuxieSemanticSnapshotTest {
    @Test fun `shared collection capture contract preserves unknown zero and ownership`() {
        val suite = Json.parseToJsonElement(FixtureRunner.fixturesRoot()
            .resolve("accessibility/collections.json").readText()).jsonObject
        assertEquals(1, suite.getValue("schemaVersion").jsonPrimitive.int)
        for (entry in suite.getValue("cases").jsonArray) {
            val scenario = entry.jsonObject
            val nodes = scenario.getValue("nodes").jsonArray.map { value ->
                val item = value.jsonObject
                val owner = item["collectionId"]?.jsonPrimitive?.long
                val count = item["itemCount"]?.jsonPrimitive?.long
                val position = item["itemPosition"]?.jsonPrimitive?.long
                NativeSemanticNode(item.getValue("id").jsonPrimitive.long,
                    item["parentId"]?.jsonPrimitive?.long?.toInt() ?: -1, 0,
                    item.getValue("role").jsonPrimitive.int, 0, 0, 0, 0,
                    0f, 0f, 0f, 0f, "Item", "", "",
                    (if (owner != null) 1 else 0) or (if (count != null) 2 else 0) or
                        (if (position != null) 4 else 0), owner ?: 0, count ?: 0, position ?: 0)
            }
            val id = scenario.getValue("id").jsonPrimitive.content
            if (scenario.getValue("valid").jsonPrimitive.boolean) {
                assertEquals(id, nodes, NuxieSemanticTree(1, 1, nodes).nodes)
            } else {
                val error = assertThrows(id, IllegalArgumentException::class.java) { NuxieSemanticTree(1, 1, nodes) }
                assertTrue(id, error.message.orEmpty().contains("collection", ignoreCase = true))
            }
        }
    }

    @Test fun `copy failure releases capture without returning a partial tree`() {
        val native = RecordingNative().apply { failNode = true }
        val error = assertThrows(NuxieRuntimeCallException::class.java) {
            NuxieSemanticSnapshot.capture(10, native)
        }
        assertEquals(4, error.status)
        assertEquals(listOf(99L), native.freed)
    }

    @Test fun `successful copy owns capture until idempotent close and keeps immutable data`() {
        val native = RecordingNative()
        val snapshot = NuxieSemanticSnapshot.capture(10, native)
        assertEquals(7L, snapshot.tree.renderRevision)
        assertEquals(8L, snapshot.tree.treeVersion)
        assertEquals("Continue", snapshot.tree.nodes.single().label)
        assertEquals(0xffff_ffffL, snapshot.tree.nodes.single().id)
        assertEquals(0, snapshot.validate(10))
        assertEquals(0, snapshot.queueAction(10, 0xffff_ffffL, 0))
        snapshot.close()
        snapshot.close()
        assertEquals(listOf(99L), native.freed)
        assertEquals("Continue", snapshot.tree.nodes.single().label)
        assertThrows(IllegalStateException::class.java) { snapshot.validate(10) }
        assertThrows(IllegalStateException::class.java) { snapshot.queueAction(10, 1, 0) }
    }

    @Test fun `native field association retains the captured node and distinguishes absence from stale ownership`() {
        val native = RecordingNative().apply { role = 6 }
        val snapshot = NuxieSemanticSnapshot.capture(10, native)
        try {
            assertEquals(0xffff_ffffL, snapshot.nodeForTextRun(10, "authored-run")?.id)
            native.associationStatus = 3
            assertNull(snapshot.nodeForTextRun(10, "authored-run"))
            native.associationStatus = 9
            assertEquals(9, assertThrows(NuxieRuntimeCallException::class.java) {
                snapshot.nodeForTextRun(10, "authored-run")
            }.status)
        } finally { snapshot.close() }
    }

    @Test fun `a native association cannot name an absent or non-field node`() {
        val snapshot = NuxieSemanticSnapshot.capture(10, RecordingNative())
        try {
            assertThrows(IllegalStateException::class.java) { snapshot.nodeForTextRun(10, "authored-run") }
        } finally { snapshot.close() }
    }

    @Test fun `unresolved modal withdraws native editable association`() {
        val native = RecordingNative().apply {
            role = NativeSemanticRole.TEXT_FIELD
            info = longArrayOf(7, 8, 1, 2, 0)
        }
        val snapshot = NuxieSemanticSnapshot.capture(10, native)
        try {
            assertNull(snapshot.nodeForTextRun(10, "authored-run"))
        } finally { snapshot.close() }
    }

    @Test fun `native scope includes only visible descendants of the active dialog`() {
        fun node(id: Long, parent: Int = -1, role: Int = NativeSemanticRole.TEXT_FIELD, flags: Int = 0) =
            NativeSemanticNode(id, parent, 0, role, flags, 0, 0, 0,
                0f, 0f, 10f, 10f, "Field", "", "")
        val nodes = listOf(node(4, 3), node(3, 2), node(1),
            node(2, role = NativeSemanticRole.DIALOG, flags = NativeSemanticState.MODAL),
            node(5, 2, flags = NativeSemanticState.HIDDEN), node(6, 5))
        assertEquals(setOf(2L, 3L, 4L),
            NuxieSemanticTree(1, 1, nodes, NativeSemanticModalScope.Active(2)).exposedNodeIds)
        assertEquals(setOf(1L, 2L, 3L, 4L), NuxieSemanticTree(1, 1, nodes).exposedNodeIds)
        assertTrue(NuxieSemanticTree(1, 1, nodes, NativeSemanticModalScope.Unresolved).exposedNodeIds.isEmpty())
    }

    @Test fun `capture owns full modal identity and unresolved scope`() {
        for ((value, expected) in listOf(
            0L to NativeSemanticModalScope.None,
            1L to NativeSemanticModalScope.Active(0xffff_ffffL),
            2L to NativeSemanticModalScope.Unresolved,
        )) {
            val native = RecordingNative().apply {
                info = longArrayOf(7, 8, 1, value, 0xffff_ffffL)
                role = NativeSemanticRole.DIALOG
                flags = NativeSemanticState.MODAL
            }
            val capture = NuxieSemanticSnapshot.capture(10, native)
            native.info[3] = 0
            assertEquals(expected, capture.tree.modalScope)
            capture.close()
        }
    }

    @Test fun `malformed modal scope releases the entire capture`() {
        for (metadata in listOf(
            longArrayOf(7, 8, 1),
            longArrayOf(7, 8, 1, 3, 0),
            longArrayOf(7, 8, 1, 1, 42),
            longArrayOf(7, 8, 1, 1, -1),
            longArrayOf(7, 8, 1, 1, 0xffff_ffffL), // Captured node is a button.
        )) {
            val native = RecordingNative().apply { info = metadata }
            assertThrows(RuntimeException::class.java) { NuxieSemanticSnapshot.capture(10, native) }
            assertEquals(listOf(99L), native.freed)
        }
        val hidden = RecordingNative().apply {
            info = longArrayOf(7, 8, 1, 1, 0xffff_ffffL)
            role = NativeSemanticRole.DIALOG
            flags = NativeSemanticState.MODAL or NativeSemanticState.HIDDEN
        }
        assertThrows(IllegalArgumentException::class.java) { NuxieSemanticSnapshot.capture(10, hidden) }
        assertEquals(listOf(99L), hidden.freed)
    }

    private class RecordingNative : NuxieSemanticNative {
        var failNode = false
        var role = 1
        var flags = 0
        var info = longArrayOf(7, 8, 1, 0, 0)
        var associationStatus = 0
        override fun semanticNodeForTextRun(player: Long, snapshot: Long, name: String) =
            NativeCallResult(associationStatus, 0xffff_ffffL.takeIf { associationStatus == 0 })
        val freed = mutableListOf<Long>()
        override fun captureSemantics(player: Long) = NativeCallResult(0, 99L)
        override fun semanticInfo(snapshot: Long) = NativeCallResult(0, info)
        override fun semanticNode(snapshot: Long, index: Int): NativeCallResult<NativeSemanticNode> =
            if (failNode) NativeCallResult(4, null) else NativeCallResult(0,
                NativeSemanticNode(0xffff_ffffL, -1, 0, role, flags, 0, 0, 1,
                    10f, 20f, 30f, 40f, "Continue", "", "Next step"))
        override fun freeSemantics(snapshot: Long): Int { freed += snapshot; return 0 }
        override fun validateSemantics(player: Long, snapshot: Long) = 0
        override fun queueSemanticAction(player: Long, snapshot: Long, nodeId: Long, action: Int) = 0
    }
}
