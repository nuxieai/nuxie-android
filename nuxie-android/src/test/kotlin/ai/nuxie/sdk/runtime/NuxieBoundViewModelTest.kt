package ai.nuxie.sdk.runtime

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class NuxieBoundViewModelTest {
    @Test
    fun `enum writes resolve the exact authored label to its ABI ordinal`() = runBlocking {
        val native = RecordingNative()
        val lane = NuxieRuntimeLane()
        val bound = NuxieBoundViewModel(
            lane = lane,
            nativeHandle = 42,
            rootSchemaIndex = 0,
            catalog = catalog(),
            native = native,
        )

        try {
            bound.setEnum("status", "busy")

            val write = native.writes.single()
            assertEquals(NuxieViewModelMutationKind.SET_ENUM, write.kind)
            assertEquals("status", write.path)
            assertEquals(1L, write.integerValue)

            assertThrows(IllegalArgumentException::class.java) {
                runBlocking { bound.setEnum("status", "missing") }
            }
            assertEquals("invalid enum names must not cross JNI", 1, native.writes.size)
        } finally {
            bound.close()
            lane.shutdown()
        }
    }

    @Test
    fun `typed writes preserve ABI values and reject lossy numbers`() = runBlocking {
        val native = RecordingNative()
        val lane = NuxieRuntimeLane()
        val bound = NuxieBoundViewModel(lane, 42, 0, catalog(), native)

        try {
            bound.setString("title", "Ready ✓")
            bound.setNumber("progress", 0.625)
            bound.setBoolean("enabled", true)
            bound.setColor("accent", 0x80FF4001.toInt())
            bound.fireTrigger("submit")

            assertArrayEquals("Ready ✓".encodeToByteArray(), native.writes[0].bytesValue)
            assertEquals(0.625f, native.writes[1].numberValue)
            assertEquals(true, native.writes[2].boolValue)
            assertEquals(
                "ARGB bit layout must cross JNI without channel conversion",
                0x80FF4001u.toLong(),
                native.writes[3].integerValue,
            )
            assertEquals(NuxieViewModelMutationKind.FIRE_TRIGGER, native.writes[4].kind)

            assertThrows(IllegalArgumentException::class.java) {
                runBlocking { bound.setNumber("progress", Double.POSITIVE_INFINITY) }
            }
            assertThrows(IllegalArgumentException::class.java) {
                runBlocking { bound.setNumber("progress", Double.MAX_VALUE) }
            }
            assertEquals("invalid numbers must not cross JNI", 5, native.writes.size)
        } finally {
            bound.close()
            lane.shutdown()
        }
    }

    @Test
    fun `invalid paths kinds native statuses and closed writes stay typed`() = runBlocking {
        val native = RecordingNative()
        val lane = NuxieRuntimeLane()
        val bound = NuxieBoundViewModel(lane, 42, 0, catalog(), native)

        try {
            assertThrows(IllegalArgumentException::class.java) {
                runBlocking { bound.setString("missing", "value") }
            }
            assertThrows(IllegalArgumentException::class.java) {
                runBlocking { bound.setBoolean("title", true) }
            }
            assertEquals(0, native.writes.size)

            native.mutationStatus = 3
            val nativeFailure = assertThrows(NuxieRuntimeCallException::class.java) {
                runBlocking { bound.setBoolean("enabled", true) }
            }
            assertEquals(3, nativeFailure.status)

            bound.close()
            assertThrows(IllegalStateException::class.java) {
                runBlocking { bound.setBoolean("enabled", false) }
            }
            assertEquals(1, native.writes.size)
        } finally {
            lane.shutdown()
        }
    }

    private fun catalog() = NuxieViewModelCatalog(
        schemas = listOf(
            NuxieViewModelCatalog.Schema(0, "Root", 0 until 6, 0 until 0, null, false),
        ),
        properties = listOf(
            NuxieViewModelCatalog.Property(
                schemaIndex = 0,
                index = 0,
                name = "status",
                kind = NuxieViewModelPropertyKind.ENUM,
                referencedSchemaIndex = null,
                enumLabels = listOf("idle", "busy"),
            ),
            property(1, "title", NuxieViewModelPropertyKind.STRING),
            property(2, "progress", NuxieViewModelPropertyKind.NUMBER),
            property(3, "enabled", NuxieViewModelPropertyKind.BOOLEAN),
            property(4, "accent", NuxieViewModelPropertyKind.COLOR),
            property(5, "submit", NuxieViewModelPropertyKind.TRIGGER),
        ),
        authoredInstances = emptyList(),
    )

    private fun property(
        index: Int,
        name: String,
        kind: NuxieViewModelPropertyKind,
    ) = NuxieViewModelCatalog.Property(0, index, name, kind, null, emptyList())

    private class RecordingNative : NuxieTypedRuntimeNative {
        val writes = mutableListOf<NativeViewModelWrite>()
        var mutationStatus = 0

        override fun mutateViewModel(handle: Long, write: NativeViewModelWrite): Int {
            assertEquals(42L, handle)
            writes += write
            return mutationStatus
        }

        override fun freeViewModel(handle: Long): Int = 0
    }
}
