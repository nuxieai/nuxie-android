package ai.nuxie.sdk.runtime

import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test

/** Uses the unchanged iOS native-input fixtures and their geometry/ownership oracle. */
class NativeTextInputDeviceTest {
    private val bridge = NuxieRuntimeBridge
    private val endpoint = "editable".encodeToByteArray()

    private fun withPlayer(fixture: String, test: (NuxieRuntimePlayer, NuxieAndroidVulkanRenderer) -> Unit) {
        val runtime = NuxieRuntime.shared
        assertTrue(runtime.isAvailable)
        val bytes = InstrumentationRegistry.getInstrumentation().context.assets
            .open(fixture).use { it.readBytes() }
        val renderer = checkNotNull(runtime.newAndroidVulkanRenderer(64, 64))
        try {
            val file = checkNotNull(runtime.importFile(renderer, bytes, checkNotNull(runtime.inspectFileAssets(bytes))))
            try {
                val artboard = checkNotNull(file.newArtboard())
                try {
                    val player = checkNotNull(artboard.newPlayer())
                    try {
                        assertEquals(0, bridge.nativePlayerEnableSemantics(player.requireHandle()))
                        test(player, renderer)
                    } finally { player.close() }
                } finally { artboard.close() }
            } finally { file.close() }
        } finally { renderer.close() }
    }

    private fun capture(player: NuxieRuntimePlayer, renderer: NuxieAndroidVulkanRenderer): Long {
        player.stepTyped(elapsedSeconds = 0.0)
        renderer.renderToCpuFrame(player, 0xff000000.toInt(), true)
        val status = intArrayOf(-1)
        val capture = bridge.nativePlayerSemanticSnapshot(player.requireHandle(), status)
        assertEquals(0, status.single())
        assertNotEquals(0L, capture)
        return capture
    }

    @Test fun nativeLayoutGeometryCrossesJniWithoutBorrowingTextRunGeometry() =
        withPlayer("native_input_layout.riv") { player, renderer ->
            val snapshot = capture(player, renderer)
            try {
                val status = intArrayOf(-1)
                val count = checkNotNull(bridge.nativeSemanticSnapshotInfo(snapshot, status))[2].toInt()
                val field = (0 until count).map {
                    checkNotNull(bridge.nativeSemanticSnapshotNode(snapshot, it, status))
                }.first { it.role == 6 }
                val geometry = checkNotNull(bridge.nativePlayerTextInputGeometry(
                    player.requireHandle(), snapshot, field.id, endpoint, status))
                assertEquals(0, status.single())
                assertTrue(geometry.hasLayout)
                assertArrayEquals(floatArrayOf(0f, 0f, 100f, 40f), geometry.layoutBounds, 0.0001f)
                assertArrayEquals(floatArrayOf(0f, 2f, -3f, 0f, 24f, 24f), geometry.layoutTransform, 0.0001f)
                assertEquals(-9f, geometry.worldTransform[4], 0.0001f)
                assertEquals(38f, geometry.worldTransform[5], 0.0001f)
                assertFalse(geometry.obscured)
                assertTrue(geometry.multiline)
                assertFalse(geometry.hasFirstBaseline)
            } finally { assertEquals(0, bridge.nativeSemanticSnapshotFree(snapshot)) }
        }

    @Test fun repeatedSecureFieldsKeepIndependentValuesAndRedactSemanticOutput() =
        withPlayer("native_input_occurrences.riv") { player, renderer ->
            val handle = player.requireHandle()
            val status = intArrayOf(-1)
            var snapshot = capture(player, renderer)
            try {
                fun fields(): List<NativeSemanticNode> {
                    val count = checkNotNull(bridge.nativeSemanticSnapshotInfo(snapshot, status))[2].toInt()
                    return (0 until count).map {
                        checkNotNull(bridge.nativeSemanticSnapshotNode(snapshot, it, status))
                    }.filter { it.role == 6 }
                }
                val before = fields()
                assertEquals(2, before.size)
                assertNotEquals(before[0].id, before[1].id)
                before.forEach {
                    assertTrue(checkNotNull(bridge.nativePlayerTextInputGeometry(handle, snapshot, it.id, endpoint, status)).obscured)
                }
                val other = bridge.nativePlayerFieldStringCopy(handle, snapshot, before[1].id, endpoint, status)
                assertEquals(0, status.single())
                val edited = "private é 🔒".encodeToByteArray()
                assertEquals(0, bridge.nativePlayerFieldStringSet(handle, snapshot, before[0].id, endpoint, edited))
                assertEquals(9, bridge.nativePlayerFieldStringSet(handle, snapshot, before[0].id, endpoint, edited))
                assertEquals(0, bridge.nativeSemanticSnapshotFree(snapshot))
                snapshot = 0L
                snapshot = capture(player, renderer)
                val after = fields()
                assertEquals(before.map { it.id }, after.map { it.id })
                assertArrayEquals(edited, bridge.nativePlayerFieldStringCopy(handle, snapshot, after[0].id, endpoint, status))
                assertEquals(0, status.single())
                assertArrayEquals(other, bridge.nativePlayerFieldStringCopy(handle, snapshot, after[1].id, endpoint, status))
                assertEquals(0, status.single())
                assertTrue(after.none { it.value.contains("private") })
            } finally { if (snapshot != 0L) assertEquals(0, bridge.nativeSemanticSnapshotFree(snapshot)) }
        }
}
