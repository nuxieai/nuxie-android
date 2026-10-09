package ai.nuxie.sdk.runtime

import org.junit.Assert.*
import org.junit.Test

class NuxieFileGlobalTest {
    @Test fun `file owns one schema default global and ignores ordinary models`() {
        val native = RecordingNative()
        val file = NuxieRuntimeFile(1, native)
        assertNull(file.globalViewModel("missing"))
        assertNull(file.globalViewModel("Root"))
        val env = checkNotNull(file.globalViewModel("env"))
        assertSame(env, file.globalViewModel("env"))
        assertEquals(listOf(Triple(1L, 0, null)), native.created)
        file.close()
        assertEquals(listOf(30L, 1L), native.freed)
        assertThrows(IllegalStateException::class.java) { file.globalViewModel("env") }
        assertThrows(IllegalStateException::class.java) { env.requireHandle() }
    }

    @Test fun `global binds every state machine including auxiliary and propagates refusal`() {
        for (kind in listOf(0, 1, 2)) {
            val native = RecordingNative().apply { primaryKind = kind }
            val file = NuxieRuntimeFile(1, native)
            val env = checkNotNull(file.globalViewModel("env"))
            val player = NuxieRuntimePlayer(10, native)
            player.installInteractionPlayer(NuxieRuntimePlayer(11, native))
            player.bindGlobalViewModel("env", env)
            val handles = if (kind == 1) listOf(10L, 11L) else listOf(11L)
            assertEquals(handles.map { Triple(it, "env", 30L) }, native.bindings)
            native.status = 4
            assertThrows(NuxieRuntimeCallException::class.java) { player.bindGlobalViewModel("env", env) }
            player.close()
            file.close()
        }
    }

    private class RecordingNative : NuxieTypedRuntimeNative {
        var primaryKind = 1
        var status = 0
        val created = mutableListOf<Triple<Long, Int, Int?>>()
        val bindings = mutableListOf<Triple<Long, String, Long>>()
        val freed = mutableListOf<Long>()
        override fun viewModelCatalog(fileHandle: Long) = NativeCallResult(0, NativeViewModelCatalog(
            arrayOf(NativeViewModelSchema(0, "env", 0, 0, 0, 0, -1, true),
                NativeViewModelSchema(1, "Root", 0, 0, 0, 0, -1, false)), emptyArray(), emptyArray()))
        override fun newViewModel(fileHandle: Long, schemaIndex: Int, authoredInstanceIndex: Int?): NativeCallResult<Long> {
            created += Triple(fileHandle, schemaIndex, authoredInstanceIndex)
            return NativeCallResult(0, 30L)
        }
        override fun freeViewModel(handle: Long): Int { freed += handle; return 0 }
        override fun freeFile(handle: Long) { freed += handle }
        override fun freePlayer(handle: Long) { freed += handle }
        override fun playerKind(playerHandle: Long) = NativeCallResult(0, if (playerHandle == 10L) primaryKind else 1)
        override fun setPlayerGlobalViewModel(playerHandle: Long, name: ByteArray, viewModelHandle: Long): Int {
            bindings += Triple(playerHandle, name.decodeToString(), viewModelHandle)
            return status
        }
    }
}
