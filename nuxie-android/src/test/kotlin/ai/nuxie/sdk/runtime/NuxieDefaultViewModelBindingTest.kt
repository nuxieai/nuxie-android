package ai.nuxie.sdk.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class NuxieDefaultViewModelBindingTest {
    @Test
    fun `declared default is validated and bound before player creation and freed once`() {
        val native = RecordingNative()
        val file = NuxieRuntimeFile(10L, native)
        val artboard = checkNotNull(file.newArtboard())

        artboard.bindDefaultViewModel("Root")
        artboard.bindDefaultViewModel("Root")
        val player = checkNotNull(artboard.newPlayer())
        player.close()
        artboard.close()
        artboard.close()
        file.close()

        assertEquals(
            listOf("artboard", "default", "root-schema", "catalog", "bind", "player",
                "free-player", "free-default", "free-artboard", "free-file"),
            native.calls,
        )
        assertThrows(IllegalStateException::class.java) { artboard.bindDefaultViewModel("Root") }
    }

    @Test
    fun `no declaration does not activate a file view model`() {
        val native = RecordingNative()
        val artboard = checkNotNull(NuxieRuntimeFile(10L, native).newArtboard())
        checkNotNull(artboard.newPlayer()).close()
        artboard.close()
        assertEquals(listOf("artboard", "player", "free-player", "free-artboard"), native.calls)
    }

    @Test
    fun `missing declared default and other native creation failures are fatal`() {
        for (status in listOf(3, 4, 10)) {
            val native = RecordingNative().apply { defaultStatus = status }
            val artboard = checkNotNull(NuxieRuntimeFile(10L, native).newArtboard())
            assertThrows(NuxieRuntimeCallException::class.java) {
                artboard.bindDefaultViewModel("Root")
            }
            assertEquals(listOf("artboard", "default"), native.calls)
            artboard.close()
        }
    }

    @Test
    fun `catalog membership cannot hide a different actual root schema`() {
        val native = RecordingNative().apply { rootSchemaIndex = 1L }
        val artboard = checkNotNull(NuxieRuntimeFile(10L, native).newArtboard())
        assertThrows(IllegalStateException::class.java) { artboard.bindDefaultViewModel("Root") }
        assertEquals(listOf("artboard", "default", "root-schema", "catalog", "free-default"), native.calls)
        artboard.close()
    }

    @Test
    fun `snapshot catalog and bind failures free the created instance and permit retry`() {
        for (failure in listOf("root-schema", "catalog", "bind")) {
            val native = RecordingNative().apply { failOperation = failure }
            val artboard = checkNotNull(NuxieRuntimeFile(10L, native).newArtboard())
            assertThrows(NuxieRuntimeCallException::class.java) { artboard.bindDefaultViewModel("Root") }
            assertEquals("free-default", native.calls.last())
            native.failOperation = null
            artboard.bindDefaultViewModel("Root")
            artboard.close()
            assertEquals(2, native.calls.count { it == "default" })
            assertEquals(2, native.calls.count { it == "free-default" })
            assertEquals(1, native.calls.count { it == "free-artboard" })
        }
    }

    @Test
    fun `invalid missing or ambiguous actual root schema is rejected before binding`() {
        for (index in listOf(-1L, 99L)) {
            val native = RecordingNative().apply { rootSchemaIndex = index }
            val artboard = checkNotNull(NuxieRuntimeFile(10L, native).newArtboard())
            assertThrows(IllegalStateException::class.java) { artboard.bindDefaultViewModel("Root") }
            assertEquals("free-default", native.calls.last())
            assertEquals(0, native.calls.count { it == "bind" })
            artboard.close()
        }
        val native = RecordingNative().apply { duplicateSchema = true }
        val artboard = checkNotNull(NuxieRuntimeFile(10L, native).newArtboard())
        assertThrows(IllegalStateException::class.java) { artboard.bindDefaultViewModel("Root") }
        assertEquals("free-default", native.calls.last())
        artboard.close()
    }

    @Test
    fun `bound declaration cannot change and cleanup failure still frees artboard`() {
        val native = RecordingNative()
        val artboard = checkNotNull(NuxieRuntimeFile(10L, native).newArtboard())
        artboard.bindDefaultViewModel("Root")
        assertThrows(IllegalStateException::class.java) { artboard.bindDefaultViewModel("Other") }
        native.failOperation = "free-default"
        assertThrows(NuxieRuntimeCallException::class.java) { artboard.close() }
        artboard.close()
        assertEquals(listOf("free-default", "free-artboard"), native.calls.takeLast(2))
        assertEquals(1, native.calls.count { it == "free-default" })
    }

    @Test
    fun `binding error is preserved when freeing its failed instance also fails`() {
        val native = RecordingNative().apply {
            failOperation = "bind"
            failFree = true
        }
        val artboard = checkNotNull(NuxieRuntimeFile(10L, native).newArtboard())
        val error = assertThrows(NuxieRuntimeCallException::class.java) {
            artboard.bindDefaultViewModel("Root")
        }
        assertEquals(1, error.suppressed.size)
        assertEquals("free-default", native.calls.last())
        artboard.close()
    }

    private class RecordingNative : NuxieTypedRuntimeNative {
        override val isAvailable = true
        val calls = mutableListOf<String>()
        var defaultStatus = 0
        var rootSchemaIndex = 0L
        var failOperation: String? = null
        var failFree = false
        var duplicateSchema = false

        override fun newDefaultArtboard(fileHandle: Long): Long {
            calls += "artboard"
            return 20L
        }

        override fun newDefaultViewModel(artboardHandle: Long): NativeCallResult<Long> {
            calls += "default"
            assertEquals(20L, artboardHandle)
            return NativeCallResult(defaultStatus, 40L.takeIf { defaultStatus == 0 })
        }

        override fun viewModelRootSchemaIndex(viewModelHandle: Long): NativeCallResult<Long> {
            assertEquals(40L, viewModelHandle)
            return NativeCallResult(status("root-schema"), rootSchemaIndex)
        }

        override fun viewModelCatalog(fileHandle: Long): NativeCallResult<NativeViewModelCatalog> {
            assertEquals(10L, fileHandle)
            val root = NativeViewModelSchema(0, "Root", 0, 0, 0, 0, -1, false)
            val other = NativeViewModelSchema(1, "Other", 0, 0, 0, 0, -1, false)
            return NativeCallResult(status("catalog"), NativeViewModelCatalog(
                if (duplicateSchema) arrayOf(root, root) else arrayOf(root, other),
                emptyArray(), emptyArray(),
            ))
        }

        override fun bindViewModel(artboardHandle: Long, viewModelHandle: Long): Int {
            assertEquals(20L, artboardHandle)
            assertEquals(40L, viewModelHandle)
            return status("bind")
        }

        override fun freeViewModel(handle: Long): Int {
            assertEquals(40L, handle)
            val result = status("free-default")
            return if (failFree) 4 else result
        }

        override fun newDefaultPlayer(artboardHandle: Long): Long {
            calls += "player"
            return 30L
        }

        override fun freePlayer(handle: Long) { calls += "free-player" }
        override fun freeArtboard(handle: Long) { calls += "free-artboard" }
        override fun freeFile(handle: Long) { calls += "free-file" }

        private fun status(operation: String): Int {
            calls += operation
            return if (failOperation == operation) 4 else 0
        }
    }
}
