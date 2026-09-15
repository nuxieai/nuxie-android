package ai.nuxie.sdk.runtime

import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class NativeSemanticsDeviceTest {
    @Test fun populatedNodesAndUnicodeTextOwnershipCrossJni() {
        val runtime = NuxieRuntime.shared
        assertTrue(runtime.isAvailable)
        val bytes = InstrumentationRegistry.getInstrumentation().context.assets
            .open("semantic_text.riv").use { it.readBytes() }
        val renderer = checkNotNull(runtime.newAndroidVulkanRenderer(200, 100))
        try {
            val file = checkNotNull(runtime.importFile(renderer, bytes, checkNotNull(runtime.inspectFileAssets(bytes))))
            try {
                val artboard = checkNotNull(file.newArtboard())
                try {
                    val player = checkNotNull(artboard.newPlayer())
                    val bridge = NuxieRuntimeBridge
                    val status = intArrayOf(-1)
                    var snapshot = 0L
                    try {
                        val handle = player.requireHandle()
                        assertEquals(0, bridge.nativePlayerEnableSemantics(handle))
                        player.stepTyped(elapsedSeconds = 0.0)
                        renderer.renderToCpuFrame(player, 0xff000000.toInt(), true)
                        snapshot = bridge.nativePlayerSemanticSnapshot(handle, status)
                        assertEquals(0, status.single())
                        assertNotEquals(0L, snapshot)
                        val info = checkNotNull(bridge.nativeSemanticSnapshotInfo(snapshot, status))
                        assertEquals(1L, info[2])
                        val node = checkNotNull(bridge.nativeSemanticSnapshotNode(snapshot, 0, status))
                        assertEquals(0, status.single())
                        assertEquals(6, node.role)
                        assertEquals("Prénom 👋", node.label)
                        assertEquals(-1, node.parentId)
                        assertTrue(node.id in 0..0xffff_ffffL)
                        assertEquals(node.id, bridge.nativePlayerSemanticNodeForTextRun(
                            handle, snapshot, "field/名前".toByteArray(Charsets.UTF_8), status))
                        assertEquals(0, status.single())
                        assertEquals(0L, bridge.nativePlayerSemanticNodeForTextRun(
                            handle, snapshot, "missing".toByteArray(Charsets.UTF_8), status))
                        assertEquals(3, status.single())
                    } finally {
                        if (snapshot != 0L) assertEquals(0, bridge.nativeSemanticSnapshotFree(snapshot))
                        player.close()
                    }
                } finally { artboard.close() }
            } finally { file.close() }
        } finally { renderer.close() }
    }

    @Test fun snapshotsRequireDeliveredFramesAndRetainOccurrenceOwnership() {
        val runtime = NuxieRuntime.shared
        assertTrue(runtime.isAvailable)
        val assets = InstrumentationRegistry.getInstrumentation().context.assets
        val contract = assets.open("sdk/interaction-player.json").bufferedReader().use {
            Json.parseToJsonElement(it.readText()).jsonObject.getValue("published").jsonObject
        }
        val bytes = assets.open(contract.getValue("fixture").jsonPrimitive.content).use { it.readBytes() }
        val renderer = checkNotNull(runtime.newAndroidVulkanRenderer(390, 844))
        try {
            val file = checkNotNull(runtime.importFile(renderer, bytes, checkNotNull(runtime.inspectFileAssets(bytes))))
            try {
                val artboard = checkNotNull(file.newArtboard(contract.getValue("artboard").jsonPrimitive.content))
                try {
                    val player = checkNotNull(artboard.newPlayer())
                    var snapshot = 0L
                    val bridge = NuxieRuntimeBridge
                    val status = intArrayOf(-1)
                    try {
                        val handle = player.requireHandle()
                        assertEquals(0, bridge.nativePlayerEnableSemantics(handle))
                        player.stepTyped(elapsedSeconds = 0.0)
                        assertEquals(0L, bridge.nativePlayerSemanticSnapshot(handle, status))
                        assertEquals(9, status.single()) // HANDLE_MISMATCH: no delivered revision.
                        renderer.renderToCpuFrame(player, 0xff000000.toInt(), true)
                        snapshot = bridge.nativePlayerSemanticSnapshot(handle, status)
                        assertEquals(0, status.single())
                        assertNotEquals(0L, snapshot)
                        val info = checkNotNull(bridge.nativeSemanticSnapshotInfo(snapshot, status))
                        assertEquals(0, status.single())
                        assertEquals(3, info.size)
                        assertTrue(info[0] > 0)
                        assertEquals(0, bridge.nativePlayerValidateSemanticSnapshot(handle, snapshot))
                        assertNull(bridge.nativeSemanticSnapshotNode(snapshot, info[2].toInt(), status))
                        assertEquals(3, status.single()) // NOT_FOUND, never a partial node.
                        assertEquals(5, bridge.nativePlayerQueueSemanticAction(handle, snapshot, -1, 0))
                        player.close()
                        // Copied captures remain readable after their occurrence is retired.
                        assertArrayEquals(info, bridge.nativeSemanticSnapshotInfo(snapshot, status))
                        assertEquals(0, status.single())
                    } finally {
                        if (snapshot != 0L) assertEquals(0, bridge.nativeSemanticSnapshotFree(snapshot))
                        player.close()
                    }
                } finally { artboard.close() }
            } finally { file.close() }
        } finally { renderer.close() }
    }
}
