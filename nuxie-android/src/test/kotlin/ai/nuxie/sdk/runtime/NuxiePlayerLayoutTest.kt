package ai.nuxie.sdk.runtime

import org.junit.Assert.*
import org.junit.Test

class NuxiePlayerLayoutTest {
    @Test fun `fixed size wrapper preserves point dimensions and native failures`() {
        val native = RecordingNative()
        val player = NuxieRuntimePlayer(42L, native)
        player.setLayoutSize(375f, 667f)
        assertEquals(listOf(Triple(42L, 375f, 667f)), native.sizes)
        assertEquals(393f to 852f, player.layoutSize())
        for (bad in listOf(0f, -1f, Float.NaN, Float.POSITIVE_INFINITY)) {
            assertThrows(IllegalArgumentException::class.java) { player.setLayoutSize(bad, 667f) }
            assertThrows(IllegalArgumentException::class.java) { player.setLayoutSize(375f, bad) }
        }
        assertEquals(1, native.sizes.size)
        native.status = 4
        assertThrows(NuxieRuntimeCallException::class.java) { player.setLayoutSize(375f, 667f) }
        assertThrows(NuxieRuntimeCallException::class.java) { player.layoutSize() }
        native.status = 0
        native.readSize = floatArrayOf(Float.NaN, 852f)
        assertThrows(IllegalStateException::class.java) { player.layoutSize() }
        player.close()
        assertThrows(IllegalStateException::class.java) { player.setLayoutSize(375f, 667f) }
        assertThrows(IllegalStateException::class.java) { player.layoutSize() }
    }

    @Test fun `GPU CPU copy and readback render paths forward fractional density`() {
        for (copiesToWindow in listOf(false, true)) {
            val native = RecordingNative().apply { copy = copiesToWindow }
            val player = NuxieRuntimePlayer(42L, native)
            val renderer = checkNotNull(NuxieRuntime(native).newAndroidVulkanRenderer(1050, 2100))
            val window = NuxieRuntimeWindow(43L, native)
            assertEquals(1, renderer.renderAndPresent(player, window, 0, 2.625f))
            renderer.renderToCpuFrame(player, 0, 3f)
            assertEquals(listOf((if (copiesToWindow) "copy" else "gpu") to 2.625f, "cpu" to 3f), native.scales)
            renderer.close()
            window.close()
            player.close()
        }
    }

    private class RecordingNative : NuxieTypedRuntimeNative {
        val sizes = mutableListOf<Triple<Long, Float, Float>>()
        val scales = mutableListOf<Pair<String, Float>>()
        var readSize = floatArrayOf(393f, 852f)
        var status = 0
        var copy = false
        override fun setPlayerLayoutSize(playerHandle: Long, width: Float, height: Float): Int {
            sizes += Triple(playerHandle, width, height)
            return status
        }
        override fun playerLayoutSize(playerHandle: Long) = NativeCallResult(status, readSize)
        override fun freePlayer(handle: Long) = Unit
        override fun newAndroidVulkanRenderer(pixelWidth: Int, pixelHeight: Int) = 41L
        override fun attachRendererSurface(rendererHandle: Long, windowHandle: Long) = if (copy) -1 else 0
        override fun detachRendererSurface(rendererHandle: Long) = 0
        override fun freeRenderer(handle: Long) = Unit
        override fun releaseWindow(handle: Long) = Unit
        override fun renderAndPresent(rendererHandle: Long, playerHandle: Long, windowHandle: Long,
            clearColor: Int, layoutScaleFactor: Float): Int { scales += "gpu" to layoutScaleFactor; return 1 }
        override fun copyPlayerToWindow(rendererHandle: Long, playerHandle: Long, windowHandle: Long,
            clearColor: Int, layoutScaleFactor: Float): Int { scales += "copy" to layoutScaleFactor; return 1 }
        override fun renderToCpuFrame(rendererHandle: Long, playerHandle: Long,
            clearColor: Int, layoutScaleFactor: Float): NuxieCpuFrame {
            scales += "cpu" to layoutScaleFactor
            return NuxieCpuFrame(1, 1, ByteArray(4))
        }
    }
}
