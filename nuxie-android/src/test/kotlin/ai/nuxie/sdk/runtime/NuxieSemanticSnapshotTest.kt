package ai.nuxie.sdk.runtime

import org.junit.Assert.*
import org.junit.Test

class NuxieSemanticSnapshotTest {
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

    private class RecordingNative : NuxieSemanticNative {
        var failNode = false
        var role = 1
        var associationStatus = 0
        var fieldStatus = 0
        var fieldValue = "private 😀".encodeToByteArray()
        var fieldCalls = 0
        override fun fieldStringCopy(player: Long, snapshot: Long, nodeId: Long, name: String): NativeCallResult<ByteArray> {
            assertEquals(10L, player)
            assertEquals(99L, snapshot)
            assertEquals(0xffff_ffffL, nodeId)
            assertEquals("editable", name)
            fieldCalls++
            return NativeCallResult(fieldStatus, fieldValue.copyOf().takeIf { fieldStatus == 0 })
        }
        override fun fieldStringSet(player: Long, snapshot: Long, nodeId: Long, name: String, value: ByteArray): Int {
            assertEquals(10L, player)
            assertEquals(99L, snapshot)
            assertEquals(0xffff_ffffL, nodeId)
            assertEquals("editable", name)
            fieldCalls++
            if (fieldStatus == 0) fieldValue = value.copyOf()
            return fieldStatus
        }
        override fun semanticNodeForTextRun(player: Long, snapshot: Long, name: String) =
            NativeCallResult(associationStatus, 0xffff_ffffL.takeIf { associationStatus == 0 })
        val freed = mutableListOf<Long>()
        override fun captureSemantics(player: Long) = NativeCallResult(0, 99L)
        override fun semanticInfo(snapshot: Long) = NativeCallResult(0, longArrayOf(7, 8, 1))
        override fun semanticNode(snapshot: Long, index: Int): NativeCallResult<NativeSemanticNode> =
            if (failNode) NativeCallResult(4, null) else NativeCallResult(0,
                NativeSemanticNode(0xffff_ffffL, -1, 0, role, 0, 0, 0, 1,
                    10f, 20f, 30f, 40f, "Continue", "", "Next step"))
        override fun freeSemantics(snapshot: Long): Int { freed += snapshot; return 0 }
        override fun validateSemantics(player: Long, snapshot: Long) = 0
        override fun queueSemanticAction(player: Long, snapshot: Long, nodeId: Long, action: Int) = 0
    }

    @Test fun `field bytes use captured ownership without entering semantic text`() {
        val native = RecordingNative().apply { role = NativeSemanticRole.TEXT_FIELD }
        val snapshot = NuxieSemanticSnapshot.capture(10, native)
        assertEquals("private 😀", snapshot.readFieldString(10, 0xffff_ffffL, "editable").decodeToString())
        val next = "changed é".encodeToByteArray()
        assertEquals(0, snapshot.writeFieldString(10, 0xffff_ffffL, "editable", next))
        assertArrayEquals(next, snapshot.readFieldString(10, 0xffff_ffffL, "editable"))
        assertEquals("", snapshot.tree.nodes.single().value)
        snapshot.close()
        val callsBeforeClose = native.fieldCalls
        assertThrows(IllegalStateException::class.java) { snapshot.readFieldString(10, 0xffff_ffffL, "editable") }
        assertThrows(IllegalStateException::class.java) { snapshot.writeFieldString(10, 0xffff_ffffL, "editable", next) }
        assertEquals(callsBeforeClose, native.fieldCalls)
    }

    @Test fun `field access preserves native stale rejection without exposing values in errors`() {
        val native = RecordingNative().apply { role = NativeSemanticRole.TEXT_FIELD; fieldStatus = 9 }
        val snapshot = NuxieSemanticSnapshot.capture(10, native)
        val error = assertThrows(NuxieRuntimeCallException::class.java) {
            snapshot.readFieldString(10, 0xffff_ffffL, "editable")
        }
        assertEquals(9, error.status)
        assertFalse(error.message.orEmpty().contains("private"))
        assertEquals(9, snapshot.writeFieldString(10, 0xffff_ffffL, "editable", byteArrayOf()))
        snapshot.close()
    }

    @Test fun `field access rejects absent and non-field nodes before native calls`() {
        val native = RecordingNative()
        val snapshot = NuxieSemanticSnapshot.capture(10, native)
        for (id in listOf(123L, 0xffff_ffffL)) {
            assertThrows(IllegalStateException::class.java) { snapshot.readFieldString(10, id, "editable") }
            assertThrows(IllegalStateException::class.java) { snapshot.writeFieldString(10, id, "editable", byteArrayOf()) }
        }
        assertEquals(0, native.fieldCalls)
        snapshot.close()
    }
}
