package ai.nuxie.sdk.runtime

import org.junit.Assert.*
import org.junit.Test

class NuxiePageStepNativeTest {
    private class Native : NuxieTypedRuntimeNative {
        val calls = mutableListOf<Triple<String, String, String?>>()
        var status = 0
        override fun playerStepBegin(playerHandle: Long, incoming: ByteArray, step: ByteArray?): Int {
            assertEquals(7L, playerHandle); calls += Triple("begin", incoming.decodeToString(), step?.decodeToString()); return status
        }
        override fun playerStepEnd(playerHandle: Long, incoming: ByteArray, step: ByteArray?): Int {
            calls += Triple("end", incoming.decodeToString(), step?.decodeToString()); return status
        }
        override fun setPlayerRowCollapsed(playerHandle: Long, list: ByteArray, index: Int, collapsed: Boolean): Int {
            calls += Triple("collapse", list.decodeToString(), "$index:$collapsed"); return status
        }
        override fun freePlayer(handle: Long) = Unit
    }
    @Test fun `UTF8 names and null cut names reach the native interface unchanged`() {
        val native = Native(); val player = NuxieRuntimePlayer(7, native)
        player.stepBegin("écran", null); player.stepEnd("écran", null)
        player.stepBegin("B", "t1.reverse"); player.stepEnd("B", "t1.reverse")
        player.setRowCollapsed("screens", 39, true)
        assertEquals(listOf(Triple("begin", "écran", null), Triple("end", "écran", null),
            Triple("begin", "B", "t1.reverse"), Triple("end", "B", "t1.reverse"), Triple("collapse", "screens", "39:true")), native.calls)
        player.close()
        assertThrows(IllegalStateException::class.java) { player.stepBegin("B", null) }
    }
    @Test fun `non OK statuses propagate and negative row indexes never reach native`() {
        val native = Native(); val player = NuxieRuntimePlayer(7, native)
        native.status = 11
        for (call in listOf<() -> Unit>({ player.stepBegin("B", null) }, { player.stepEnd("B", null) },
            { player.setRowCollapsed("screens", 0, true) })) {
            assertEquals(11, assertThrows(NuxieRuntimeCallException::class.java) { call() }.status)
        }
        assertThrows(IllegalArgumentException::class.java) { player.setRowCollapsed("screens", -1, false) }
        assertEquals(3, native.calls.size)
        player.close()
    }
}
