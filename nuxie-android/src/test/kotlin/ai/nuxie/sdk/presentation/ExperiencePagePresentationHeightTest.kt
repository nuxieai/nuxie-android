package ai.nuxie.sdk.presentation

import ai.nuxie.sdk.runtime.*
import android.app.Activity
import android.graphics.SurfaceTexture
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "w393dp-h852dp-mdpi")
class ExperiencePagePresentationHeightTest {
    @Test fun `page preparation releases fit content show gate before any surface including covered restore`() = runBlocking {
        for (covered in listOf(false, true)) for (hasSize in listOf(false, true)) {
            checkPreparation(PresentationShell.Sheet(PresentationShell.Sheet.Detent.FIT_CONTENT, true), covered, hasSize)
        }
    }

    @Test fun `other page shells do not publish preparation height`() = runBlocking {
        for (shell in listOf(PresentationShell.FullScreen(),
            PresentationShell.Sheet(PresentationShell.Sheet.Detent.LARGE, true),
            PresentationShell.Sheet(PresentationShell.Sheet.Detent.MEDIUM, true))) {
            checkPreparation(shell, covered = false, hasSize = true)
        }
    }

    private suspend fun checkPreparation(shell: PresentationShell, covered: Boolean, hasSize: Boolean) {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        val activity = controller.get()
        val lane = NuxieRuntimeLane()
        val pageNative = ExperienceInteractivePageTest.RecordingNative().apply { presentation = 1 }
        val native = object : NuxieTypedRuntimeNative by pageNative {
            var size = floatArrayOf(393f, 852f)
            override fun inspectFileAssets(bytes: ByteArray) = emptyList<ExpectedFileAsset>()
            override fun newAndroidVulkanRenderer(pixelWidth: Int, pixelHeight: Int) = 4L
            override fun newFile(rendererHandle: Long, bytes: ByteArray, expectedAssets: List<ExpectedFileAsset>,
                externalAssets: Map<Int, ByteArray>, imageDecoder: NuxImageDecoder, videoEnabled: Boolean) = 1L
            override fun installValueMarkers(file: Long, entries: Array<NativeValueMarker>) = 0
            override fun installValueRules(file: Long, entries: Array<NativeValueRule>) = NativeRuleInstallResult(0, null, null)
            override fun installRuleGroups(file: Long, entries: Array<NativeRuleGroup>) = 0
            override fun enableSemantics(player: Long) = 0
            override fun acquireWindow(surface: android.view.Surface) = 5L
            override fun setPlayerLayoutSize(playerHandle: Long, width: Float, height: Float): Int {
                size = floatArrayOf(width, height); return 0
            }
            override fun playerLayoutSize(playerHandle: Long) = NativeCallResult(0, size)
        }
        val descriptor = Json.parseToJsonElement("""{"state":{},"responses":{},"ruleGroups":[],
            "leg":{"screens":[{"id":"a","defaultViewModelName":"A"}]},
            "render":{"assets":[],"screens":[{"id":"a","artboardName":"A"}]}}""").jsonObject
        val scene = java.io.File.createTempFile("page-height", ".riv")
        val prepared = PreparedPresentation(scene, "A", 0, shell, "a", descriptor)
        val window = ExperienceNativeLayerWindow(activity, FrameLayout(activity), shell) {}
        val heights = mutableListOf<Float?>()
        val bounds = mutableListOf<ExperienceArtboardSize>()
        val order = mutableListOf<String>()
        val failures = mutableListOf<ExperiencePresentationException>()
        val host = ExperienceSurfaceHost(activity, lane, runtime = NuxieRuntime(native),
            fitsContentHeight = shell.fitsContent,
            initialPresentationSize = if (hasSize) ExperienceArtboardSize(393f, 852f) else null,
            usesSystemFrameCallbacks = false, listener = object : ExperienceSurfaceHost.Listener {
                override fun onFirstFrame() = Unit
                override fun onFailure(error: ExperiencePresentationException) { failures += error }
                override fun onLayoutBounds(value: ExperienceArtboardSize) { bounds += value }
                override fun onPresentationHeight(height: Float?) {
                    order += "height"; heights += height
                    prepared.layerContentHeight.set(height)
                    window.updateContentHeight(height)
                }
            })
        val texture = SurfaceTexture(0)
        try {
            window.show()
            if (shell.fitsContent) assertFalse(window.dialog.isShowing)
            host.setInputEnabled(false)
            host.loadArtboard(byteArrayOf(1), "A", descriptor, layerScreen = prepared, enteringPage = !covered) { loaded ->
                assertTrue("Preparation failed: $failures", loaded)
                Handler(Looper.getMainLooper()).post { order += "loaded" }
            }
            lane.call {}
            shadowOf(Looper.getMainLooper()).idle()
            assertTrue(failures.joinToString { it.stackTraceToString() }, failures.isEmpty())
            assertEquals(0, pageNative.renders)
            assertEquals(if (shell.fitsContent) listOf("height", "loaded") else listOf("loaded"), order)
            assertEquals(if (shell.fitsContent) listOf<Float?>(null) else emptyList<Float?>(), heights)
            assertNull(prepared.layerContentHeight.get())
            assertTrue(window.dialog.isShowing)
            if (shell.fitsContent) {
                assertEquals(ViewGroup.LayoutParams.MATCH_PARENT,
                    window.dialog.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet).layoutParams.height)
            }
            if (shell.fitsContent && hasSize) {
                assertEquals(listOf(ExperienceArtboardSize(393f, 852f)), bounds)
                host.onSurfaceTextureAvailable(texture, 393, 852)
                host.doFrame(1_000_000_000L)
                lane.call {}
                shadowOf(Looper.getMainLooper()).idle()
                assertEquals(1, pageNative.renders)
                assertEquals(listOf<Float?>(null), heights)
                assertEquals(1, bounds.size)
                host.onSurfaceTextureUpdated(texture)
                lane.call {}
                host.onSurfaceTextureSizeChanged(texture, 300, 500)
                host.doFrame(1_016_000_000L)
                lane.call {}
                shadowOf(Looper.getMainLooper()).idle()
                assertEquals(listOf(ExperienceArtboardSize(393f, 852f), ExperienceArtboardSize(300f, 500f)), bounds)
                assertEquals(listOf<Float?>(null, null), heights)
                assertNull(prepared.layerContentHeight.get())
                assertTrue(failures.joinToString { it.stackTraceToString() }, failures.isEmpty())
            }
        } finally {
            host.release(); lane.shutdown(); assertTrue(lane.awaitQuiescence(5_000))
            texture.release(); window.retire(); controller.pause().stop().destroy(); scene.delete()
        }
    }
}
