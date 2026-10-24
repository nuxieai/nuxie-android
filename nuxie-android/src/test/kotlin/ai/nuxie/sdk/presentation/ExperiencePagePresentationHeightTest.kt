package ai.nuxie.sdk.presentation

import ai.nuxie.sdk.runtime.*
import android.app.Activity
import android.graphics.SurfaceTexture
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import java.util.concurrent.atomic.AtomicReference
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

    private val fitContent = PresentationShell.Sheet(PresentationShell.Sheet.Detent.FIT_CONTENT, true)

    @Test fun `mounted page delivers heights through active Activity Screen after navigation rollback Back and recreation`() = runBlocking {
        val scene = java.io.File.createTempFile("page-activity-height", ".riv").apply { writeBytes(byteArrayOf(1)) }
        val descriptor = Json.parseToJsonElement("""{"state":{},"responses":{},"ruleGroups":[],
            "leg":{"screens":[{"id":"a","defaultViewModelName":"A"},{"id":"b","defaultViewModelName":"B"}]},
            "render":{"assets":[],"screens":[{"id":"a","artboardName":"A"},{"id":"b","artboardName":"B"}],
            "transitions":[{"id":"pair","kind":"page","sourceScreenId":"a","destinationScreenId":"b","durationMs":0,
            "source":{"completeEventName":"leave"},"destination":{"completeEventName":"arrive"},
            "reverse":{"durationMs":0,"source":{"completeEventName":"leave.reverse"},
            "destination":{"completeEventName":"arrive.reverse"}}}]}}""").jsonObject
        val a = PreparedPresentation(scene, "A", 0, fitContent, "a", descriptor, pageHistory = listOf("a"))
        val b = a.copy(artboardName = "B", screenId = "b", pageHistory = listOf("a", "b"),
            screenLifecycle = ExperienceScreenLifecycle(), retainedViewModel = AtomicReference())
        var fixture: ActivityPage? = null
        try {
            var page = ActivityPage(a).also { fixture = it }
            page.start()
            page.frame()
            page.assertHeight(152f)
            page.navigate(b)
            page.frame()
            page.assertHeight(280f)
            page.text("First\nSecond")
            page.frame()
            page.assertHeight(360f)
            page.surface.onSurfaceTextureSizeChanged(page.texture, 300, 600)
            page.frame()
            page.assertHeight(400f)

            // Rebuild only B in a fresh Activity/player, preserving the service-owned page state.
            page.close(recreating = true)
            page = ActivityPage(b).also { fixture = it }
            page.start()
            page.frame()
            page.assertHeight(360f)
            page.text("Restored\nSecond\nThird")
            page.frame()
            page.assertHeight(440f)

            val back = a.copy(pageHistory = listOf("a"), isBack = true,
                transition = Json.parseToJsonElement("""{"type":"magic-move","transitionId":"pair"}""").jsonObject)
            page.navigate(back, cancel = true)
            page.text("Still B\nSecond\nThird\nFourth")
            page.frame()
            page.assertHeight(520f)
            page.navigate(back)
            page.assertHeight(520f)
            page.frame()
            page.assertHeight(152f)
            // A later visit uses the kept/recreated row and a fresh Activity listener.
            page.navigate(b, frameBeforeCommit = true)
            page.assertHeight(520f)
            page.frame()
            page.assertHeight(520f)
        } finally { fixture?.close(); scene.delete() }
    }

    /** Real mounted-page listener and Activity Screen callbacks; only the native runtime is faked. */
    private class ActivityPage(initial: PreparedPresentation) {
        private val controller = Robolectric.buildActivity(NuxieExperienceActivity::class.java)
        private val activity = controller.get()
        private val screenClass = NuxieExperienceActivity::class.java.declaredClasses.single { it.simpleName == "Screen" }
        private val layerClass = NuxieExperienceActivity::class.java.declaredClasses.single { it.simpleName == "Layer" }
        private var prepared = initial
        private fun screen(content: PreparedPresentation): ExperienceSurfaceHost.Listener =
            screenClass.declaredConstructors.single { it.parameterCount == 4 }.apply { isAccessible = true }
                .newInstance(activity, content.screenId, content, false) as ExperienceSurfaceHost.Listener
        private var current = screen(initial)
        private val failures = mutableListOf<Throwable>()
        private val mounted = ExperienceMountedScreen(activity, initial, current, { failures += it })
        val surface = mounted.surface
        val texture = SurfaceTexture(0)
        private val lane = field(mounted, "lane") as NuxieRuntimeLane
        private val native = ExperienceInteractivePageTest.RecordingNative().apply {
            presentation = 1
            contentHeights.putAll(mapOf(1 to 152f, 2 to 280f))
        }
        private val root = FrameLayout(activity)
        private val window = ExperienceNativeLayerWindow(activity, root, initial.shell) {}
        private val layer = layerClass.declaredConstructors.single { it.parameterCount == 5 }.apply { isAccessible = true }
            .newInstance(activity, root, current, initial.shell, false)
        private var clock = 1_000_000_000L
        private var closed = false

        suspend fun start() {
            val runtime = object : NuxieTypedRuntimeNative by native {
                private var size = floatArrayOf(393f, 852f)
                override fun inspectFileAssets(bytes: ByteArray) = emptyList<ExpectedFileAsset>()
                override fun newAndroidVulkanRenderer(pixelWidth: Int, pixelHeight: Int) = 4L
                override fun newFile(rendererHandle: Long, bytes: ByteArray, expectedAssets: List<ExpectedFileAsset>,
                    externalAssets: Map<Int, ByteArray>, imageDecoder: NuxImageDecoder, videoEnabled: Boolean) = 1L
                override fun installValueMarkers(file: Long, entries: Array<NativeValueMarker>) = 0
                override fun installValueRules(file: Long, entries: Array<NativeValueRule>) = NativeRuleInstallResult(0, null, null)
                override fun installRuleGroups(file: Long, entries: Array<NativeRuleGroup>) = 0
                override fun enableSemantics(player: Long) = 0
                override fun captureSemantics(player: Long) = NativeCallResult(0, 99L)
                override fun semanticInfo(snapshot: Long) = NativeCallResult(0, longArrayOf(1, 1, 0))
                override fun validateSemantics(player: Long, snapshot: Long) = 0
                override fun freeSemantics(snapshot: Long) = 0
                override fun acquireWindow(surface: android.view.Surface) = 5L
                override fun setPlayerLayoutSize(playerHandle: Long, width: Float, height: Float): Int {
                    size = floatArrayOf(width, height)
                    native.contentHeights[2] = if (width < 350f) 320f else 280f
                    return 0
                }
                override fun playerLayoutSize(playerHandle: Long) = NativeCallResult(0, size)
                override fun snapshotViewModel(viewModelHandle: Long): NativeCallResult<NativeViewModelSnapshot> {
                    val snapshot = checkNotNull(native.snapshotViewModel(viewModelHandle).value)
                    val label = native.writes.lastOrNull { it.first == viewModelHandle && it.second.path == "label" }
                        ?.second?.bytesValue ?: return NativeCallResult(0, snapshot)
                    return NativeCallResult(0, snapshot.copy(values = snapshot.values + NativeViewModelSnapshotValue(
                        viewModelHandle, 4, "label", 1, label, 0)))
                }
                override fun viewModelCatalog(fileHandle: Long): NativeCallResult<NativeViewModelCatalog> {
                    val catalog = checkNotNull(native.viewModelCatalog(fileHandle).value)
                    // Add the B text binding to the shared fake's schema.
                    val properties = catalog.properties.toMutableList()
                    properties.add(10, NativeViewModelProperty(2, 4, "label", 1, -1, emptyArray()))
                    val schemas = catalog.schemas.map {
                        if (it.index == 2L) it.copy(propertyCount = it.propertyCount + 1)
                        else if (it.firstProperty >= 10) it.copy(firstProperty = it.firstProperty + 1) else it
                    }.toTypedArray()
                    return NativeCallResult(0, catalog.copy(schemas = schemas, properties = properties.toTypedArray()))
                }
            }
            set(surface, "runtime", NuxieRuntime(runtime))
            set(surface, "usesSystemFrameCallbacks", false)
            set(current, "mounted", mounted)
            val view = mounted.mount()
            set(current, "view", view)
            root.addView(view)
            set(layer, "sheet", window)
            @Suppress("UNCHECKED_CAST")
            (field(activity, "layers") as MutableList<Any>).add(layer)
            set(activity, "currentScreen", current)
            set(activity, "contentRoot", root)
            window.show()
            lane.call {}; shadowOf(Looper.getMainLooper()).idle()
            surface.onSurfaceTextureAvailable(texture, 393, 852)
        }

        suspend fun frame() {
            surface.doFrame(clock); clock += 16_000_000L
            lane.call {}; shadowOf(Looper.getMainLooper()).idle()
            surface.onSurfaceTextureUpdated(texture); lane.call {}
            (field(current, "failure") as? Throwable)?.let { throw AssertionError("Activity Screen failed", it) }
            assertTrue(failures.joinToString { it.stackTraceToString() }, failures.isEmpty())
        }

        suspend fun text(value: String) {
            mounted.updateRowRuntimeValues(checkNotNull(prepared.screenId),
                mapOf("label" to NuxieViewModelScalarValue.StringValue(value)))
        }

        suspend fun navigate(target: PreparedPresentation, cancel: Boolean = false, frameBeforeCommit: Boolean = false) {
            val next = screen(target)
            set(next, "mounted", mounted)
            set(next, "view", field(current, "view"))
            set(next, "ownsMounted", false)
            mounted.prepareRow(target, next)
            if (target.transition != null) coroutineScope {
                val before = checkNotNull(prepared.layerContentHeight.get())
                lane.call { native.trace.clear() }
                val step = async(start = CoroutineStart.UNDISPATCHED) { mounted.navigateLayer(target, target.isBack) }
                withTimeout(5_000) {
                    while (!lane.call { native.trace.any { it.startsWith("begin:") } }) { frame(); yield() }
                }
                frame()
                assertFalse("Reverse completion has not landed", step.isCompleted)
                assertHeight(before)
                // No completion events: the normal watchdog lands this step after the asserted frame.
                step.await()
            } else mounted.navigateLayer(target, target.isBack)
            if (cancel) {
                mounted.restoreRow(prepared, checkNotNull(target.screenId))
                screenClass.getDeclaredMethod("close", Boolean::class.javaPrimitiveType).apply { isAccessible = true }.invoke(next, false)
            } else {
                // Navigation.activate transfers the page, replaces the layer Screen and closes the source.
                if (frameBeforeCommit) frame()
                set(layer, "screen", next)
                screenClass.getDeclaredMethod("takeLayerOwnership", screenClass).apply { isAccessible = true }
                    .invoke(next, current)
                screenClass.getDeclaredMethod("close", Boolean::class.javaPrimitiveType).apply { isAccessible = true }.invoke(current, false)
                current = next
                prepared = target
                set(activity, "currentScreen", next)
            }
        }

        fun assertHeight(expected: Float) {
            assertEquals(expected, prepared.layerContentHeight.get())
            assertEquals(expected, field(current, "measuredContentHeight"))
            assertEquals(expected.toInt(), window.dialog.findViewById<View>(
                com.google.android.material.R.id.design_bottom_sheet).layoutParams.height)
        }

        fun close(recreating: Boolean = false) {
            if (closed) return
            closed = true
            screenClass.getDeclaredMethod("close", Boolean::class.javaPrimitiveType).apply { isAccessible = true }.invoke(current, recreating)
            assertTrue(lane.awaitQuiescence(5_000))
            texture.release(); window.retire(); controller.destroy()
        }

        companion object {
            private fun field(owner: Any, name: String): Any? = owner.javaClass.getDeclaredField(name)
                .apply { isAccessible = true }.get(owner)
            private fun set(owner: Any, name: String, value: Any?) = owner.javaClass.getDeclaredField(name)
                .apply { isAccessible = true }.set(owner, value)
        }
    }

    @Test fun `forward measures first ordinary frame and Back waits for end removal and next frame`() = runBlocking {
        checkPreparation(fitContent, covered = false, hasSize = true) { host, lane, texture, window, heights, native, failures ->
            native.contentHeights.putAll(mapOf(1 to 152f, 2 to 280f))
            var clock = 1_000_000_000L
            suspend fun frame() {
                host.doFrame(clock); clock += 16_000_000L
                lane.call {}; shadowOf(Looper.getMainLooper()).idle()
                host.onSurfaceTextureUpdated(texture); lane.call {}
                assertTrue(failures.joinToString { it.stackTraceToString() }, failures.isEmpty())
            }
            host.onSurfaceTextureAvailable(texture, 393, 852)
            frame()
            assertEquals(listOf<Float?>(null, 152f), heights)
            val sheet = window.dialog.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)
            assertEquals(152, sheet.layoutParams.height)
            host.layerOperation { page ->
                page.prepareOnLane("b"); page.takeIncomingOnLane("b", "a", false); page.beginOnLane("b", "pair")
            }
            assertEquals(listOf<Float?>(null, 152f), heights)
            val before = native.contentHeightQueries
            frame()
            assertEquals(before + 1, native.contentHeightQueries)
            assertEquals(listOf<Float?>(null, 152f, 280f), heights)
            assertTrue(native.trace.indexOfLast { it.startsWith("step:") } < native.trace.lastIndexOf("height"))
            host.layerOperation { page -> page.endOnLane("b", "pair"); page.setCollapsedOnLane("a", true) }
            frame()
            host.layerOperation { page -> page.takeIncomingOnLane("a", "b", true); page.beginOnLane("a", "pair.reverse") }
            frame()
            assertEquals(listOf<Float?>(null, 152f, 280f), heights)
            host.layerOperation { page -> page.endOnLane("a", "pair.reverse"); page.retireOnLane(setOf("a"), "a") }
            assertEquals(280f, heights.last())
            frame()
            assertEquals(listOf<Float?>(null, 152f, 280f, 152f), heights)
            assertEquals(152, sheet.layoutParams.height)
            // Empty expanded stack returns NotFound and restores the large fallback.
            host.layerOperation { it.removeOnLane("a") }
            frame()
            assertNull(heights.last())
            assertEquals(ViewGroup.LayoutParams.MATCH_PARENT, sheet.layoutParams.height)
        }
    }

    @Test fun `bound text width and reveal remeasure while unchanged heights are not republished`() = runBlocking {
        checkPreparation(fitContent, covered = false, hasSize = true) { host, lane, texture, _, heights, native, failures ->
            native.contentHeights[1] = 152f
            var clock = 1_000_000_000L
            suspend fun frame() {
                host.doFrame(clock); clock += 16_000_000L
                lane.call {}; shadowOf(Looper.getMainLooper()).idle()
                host.onSurfaceTextureUpdated(texture); lane.call {}
            }
            host.onSurfaceTextureAvailable(texture, 393, 852)
            frame(); frame()
            assertEquals(listOf<Float?>(null, 152f), heights)
            // A bound value changes intrinsic height even when keepGoing is false.
            native.keepGoing = false
            host.layerOperation { it.applyJourneyValuesOnLane("a",
                mapOf("label" to NuxieViewModelScalarValue.StringValue("First line\nSecond line"))) }
            frame()
            assertEquals(listOf<Float?>(null, 152f, 232f), heights)
            host.onSurfaceTextureSizeChanged(texture, 300, 500)
            native.contentHeights[1] = 232f
            frame()
            assertEquals(listOf<Float?>(null, 152f, 232f, 312f), heights)
            host.onSurfaceTextureSizeChanged(texture, 300, 600)
            frame()
            assertEquals(4, heights.size)
            host.setPresentationVisible(false); lane.call {}
            val before = native.contentHeightQueries
            native.contentHeights[1] = 312f
            host.applyLayerTransition { it.setPhasesOnLane(listOf("a" to ExperienceScreenLifecycle.Phase.HIDDEN)) }
            frame()
            assertEquals(before, native.contentHeightQueries)
            assertEquals(312f, heights.last())
            host.setPresentationVisible(true)
            frame()
            assertEquals(392f, heights.last())
            assertTrue(failures.joinToString { it.stackTraceToString() }, failures.isEmpty())
        }
    }

    @Test fun `NotFound and zero keep fallback and other query failures fail the host`() = runBlocking {
        for (status in listOf(0, 3, 11)) checkPreparation(fitContent, covered = false, hasSize = true) {
            host, lane, texture, _, heights, native, failures ->
            native.contentHeightStatus = status
            native.contentHeights[1] = 0f
            host.onSurfaceTextureAvailable(texture, 393, 852)
            host.doFrame(1_000_000_000L); lane.call {}; shadowOf(Looper.getMainLooper()).idle()
            assertEquals(listOf<Float?>(null), heights)
            assertEquals(1, native.contentHeightQueries)
            assertEquals(if (status == 11) 1 else 0, failures.size)
            if (status == 11) assertEquals(ExperiencePresentationException.Reason.HOST_FAILED, failures.single().reason)
        }
    }

    @Test fun `ordinary frames outside a fit content sheet never query`() = runBlocking {
        checkPreparation(PresentationShell.FullScreen(), covered = false, hasSize = true) {
            host, lane, texture, _, heights, native, failures ->
            host.onSurfaceTextureAvailable(texture, 393, 852)
            host.doFrame(1_000_000_000L); lane.call {}; shadowOf(Looper.getMainLooper()).idle()
            assertEquals(0, native.contentHeightQueries)
            assertTrue(heights.isEmpty()); assertTrue(failures.isEmpty())
        }
    }

    private suspend fun checkPreparation(shell: PresentationShell, covered: Boolean, hasSize: Boolean,
        exercise: (suspend (ExperienceSurfaceHost, NuxieRuntimeLane, SurfaceTexture, ExperienceNativeLayerWindow,
            MutableList<Float?>, ExperienceInteractivePageTest.RecordingNative, MutableList<ExperiencePresentationException>) -> Unit)? = null) {
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
            "leg":{"screens":[{"id":"a","defaultViewModelName":"A"},{"id":"b","defaultViewModelName":"B"}]},
            "render":{"assets":[],"screens":[{"id":"a","artboardName":"A"},{"id":"b","artboardName":"B"}]}}""").jsonObject
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
            assertEquals(0, pageNative.contentHeightQueries)
            if (exercise != null) {
                exercise(host, lane, texture, window, heights, pageNative, failures)
            } else if (shell.fitsContent && hasSize) {
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
                assertEquals(listOf<Float?>(null), heights)
                assertNull(prepared.layerContentHeight.get())
                assertTrue(failures.joinToString { it.stackTraceToString() }, failures.isEmpty())
            }
        } finally {
            host.release(); lane.shutdown(); assertTrue(lane.awaitQuiescence(5_000))
            texture.release(); window.retire(); controller.pause().stop().destroy(); scene.delete()
        }
    }
}
