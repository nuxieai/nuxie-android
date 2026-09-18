package ai.nuxie.sdk.runtime

import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class NativeSemanticsDeviceTest {
    @Test fun collectionMetadataCrossesJniAfterPresentation() {
        val runtime = NuxieRuntime.shared
        assertTrue(runtime.isAvailable)
        val bytes = InstrumentationRegistry.getInstrumentation().context.assets
            .open("accessibility/collections.riv").use { it.readBytes() }
        val renderer = checkNotNull(runtime.newAndroidVulkanRenderer(200, 200))
        try {
            val file = checkNotNull(runtime.importFile(renderer, bytes, checkNotNull(runtime.inspectFileAssets(bytes))))
            try {
                val artboard = checkNotNull(file.newArtboard())
                try {
                    val player = checkNotNull(artboard.newPlayer())
                    try {
                        player.enableSemantics()
                        player.stepTyped(elapsedSeconds = 0.0)
                        renderer.renderToCpuFrame(player, 0xff000000.toInt(), true)
                        val capture = player.captureSemantics()
                        val nodes = try { capture.tree.nodes } finally { capture.close() }
                        assertEquals(9, nodes.size)
                        val plans = nodes.single { it.label == "Plans" }
                        assertEquals(10L, plans.itemCount)
                        assertNull(plans.collectionId)
                        assertNull(plans.itemPosition)
                        for (position in 4L..6L) {
                            val item = nodes.single { it.label == "Plan $position" }
                            assertEquals(plans.id, item.collectionId)
                            assertEquals(position, item.itemPosition)
                            assertNull(item.itemCount)
                        }
                        val nested = nodes.single { it.label == "Nested" }
                        val nestedItem = nodes.single { it.label == "Nested item" }
                        assertEquals(1L, nested.itemCount)
                        assertEquals(nested.id, nestedItem.collectionId)
                        assertEquals(0L, nestedItem.itemPosition)
                        assertEquals(0L, nodes.single { it.label == "Empty" }.itemCount)
                        val unknown = nodes.single { it.label == "Unknown" }
                        assertNull(unknown.itemCount)
                        val item = nodes.single { it.label == "Unknown item" }
                        assertEquals(unknown.id, item.collectionId)
                        assertNull(item.itemPosition)
                    } finally { player.close() }
                } finally { artboard.close() }
            } finally { file.close() }
        } finally { renderer.close() }
    }

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
                        assertEquals(5, info.size)
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
