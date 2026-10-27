package ai.nuxie.sdk.presentation

import ai.nuxie.sdk.runtime.*
import android.app.Activity
import android.graphics.SurfaceTexture
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
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

    @Test fun `mounted page delivers heights through active Activity Screen after navigation Back and recreation`() = runBlocking {
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
            page.text("Restored\nSecond\nThird\nFourth")
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

    @Test fun `pushed page drives hosting sheet through text navigation pop and recreation`() = runBlocking {
        withPushPages { a, p, content ->
            a.start(); a.frame(); a.assertHeight(152f)
            p.start(install = false); p.frame()
            a.assertWindowHeight(152f)
            p.install()
            // A first frame delivered before installation must be applied immediately on push.
            p.assertHeight(400f)
            p.text("P\nSecond"); p.frame(); p.assertHeight(480f)
            p.navigate(content.copy(artboardName = "A", screenId = "a", pageHistory = listOf("b", "a"),
                closeLayer = { reveal -> reveal.prepare(); reveal.activate(); true },
                screenLifecycle = ExperienceScreenLifecycle(), retainedViewModel = AtomicReference()))
            p.frame(); p.assertHeight(240f)
            val before = a.queries()
            a.frame()
            assertEquals("Covered page does not measure", before, a.queries())
            p.assertWindowHeight(240f)
            p.navigate(content.copy(isBack = true, pageHistory = listOf("b"),
                closeLayer = { reveal -> reveal.prepare(); reveal.activate(); true }))
            p.frame(); p.assertHeight(480f)
            p.text("P\nSecond\nThird"); p.frame(); p.assertHeight(560f)
            p.pop()
            // Restore the lower page even before its next (unchanged-height) frame.
            a.assertHeight(152f)
            a.frame(); a.assertHeight(152f)
        }
        // Fresh Activity and players, as in the existing recreation test; restore only each page's top row.
        withPushPages { a, p, _ ->
            a.start(); a.frame(); p.start(); p.frame(); p.assertHeight(400f)
            p.text("Restored\nSecond"); p.frame(); p.assertHeight(480f)
            p.close(recreating = true); a.close(recreating = true)
            // Destroying the first Activity closed its Experience's move queue; the fresh pages start another.
            val moves = ExperienceMoveQueue()
            val restoredA = ActivityPage(a.content().copy(moves = moves))
            val restoredP = ActivityPage(p.content().copy(moves = moves), lower = restoredA, baseHeights = mapOf(1 to 240f, 2 to 400f))
            try {
                restoredA.start(covered = true); restoredP.start(); restoredP.frame()
                restoredP.assertHeight(480f)
                restoredP.text("Restored\nSecond\nThird"); restoredP.frame(); restoredP.assertHeight(560f)
                restoredP.pop(); restoredA.frame(); restoredA.assertHeight(152f)
            } finally { restoredP.close(); restoredA.close() }
        }
    }

    @Test fun `nested sheet isolates pushed page heights and reveals its current height on dismiss`() = runBlocking {
        withPushPages { a, p, content ->
            a.start(); a.frame(); p.start(); p.frame(); p.assertHeight(400f)
            val nested = ActivityPage(content.copy(isPushPage = false, layerContentHeight = AtomicReference()),
                lower = p, baseHeights = mapOf(1 to 180f, 2 to 320f))
            try {
                nested.start(); nested.frame(); nested.assertHeight(320f)
                val before = p.queries()
                p.text("Covered\nSecond"); p.frame()
                assertEquals(before, p.queries())
                nested.assertWindowHeight(320f)
                p.assertWindowHeight(400f)
                nested.pop()
                p.assertWindowHeight(400f)
                p.frame(); p.assertHeight(480f)
                p.pop(); a.assertHeight(152f)
            } finally { nested.close() }
        }
    }

    @Test fun `a started page close lands even when its caller gives up`() = runBlocking {
        withPushPages { a, p, _ ->
            a.start(); a.frame(); p.start(); p.frame(); p.assertHeight(400f)
            p.popWhileItsCallerGivesUp()
            a.assertHeight(152f)
            a.frame(); a.assertHeight(152f)
        }
    }

    @Test fun `skipping Back cuts recreated covered lower page then restores its height across push`() = runBlocking {
        withPushPages(lowerAtB = true) { a, p, content ->
            a.start(covered = true); p.start(); p.frame(); p.assertHeight(400f)
            val before = a.queries()
            a.cutCovered(content.copy(artboardName = "A", screenId = "a", pageHistory = listOf("a"),
                isPushPage = false, opensLayer = false, layerAncestors = emptyList(), isBack = true,
                layerContentHeight = AtomicReference(), screenLifecycle = ExperienceScreenLifecycle(),
                retainedViewModel = AtomicReference()))
            a.frame()
            assertEquals(before, a.queries())
            p.assertHeight(400f)
            p.pop(); a.frame(); a.assertHeight(152f)
        }
    }

    @Test fun `a nested sheet refuses a drag and outside taps while a move runs`() = runBlocking {
        withPushPages { a, p, content ->
            a.start(); a.frame(); p.start(); p.frame()
            val nested = ActivityPage(content.copy(isPushPage = false, layerContentHeight = AtomicReference()),
                lower = p, baseHeights = mapOf(1 to 180f, 2 to 320f))
            val running = CompletableDeferred<Unit>()
            try {
                nested.start(); nested.frame(); nested.enableSheetInput()
                assertTrue(nested.sheetDraggable)
                assertTrue(nested.sheetCancelsOnTouchOutside)
                val move = launch(start = CoroutineStart.UNDISPATCHED) { content.moves.run { running.await() } }
                nested.idle()
                assertFalse("A drag waits for the running move", nested.sheetDraggable)
                assertFalse("An outside tap waits for the running move", nested.sheetCancelsOnTouchOutside)
                running.complete(Unit); move.join(); nested.idle()
                assertTrue(nested.sheetDraggable)
                assertTrue(nested.sheetCancelsOnTouchOutside)
            } finally { running.complete(Unit); nested.close() }
        }
    }

    @Test fun `a nested sheet's drag holds the slot until it returns or its close lands`() = runBlocking {
        withPushPages { a, p, content ->
            a.start(); a.frame(); p.start(); p.frame()
            val nested = ActivityPage(content.copy(isPushPage = false, layerContentHeight = AtomicReference()),
                lower = p, baseHeights = mapOf(1 to 180f, 2 to 320f))
            try {
                nested.start(); nested.frame(); nested.enableSheetInput()
                nested.sheetState("STATE_DRAGGING")
                assertTrue("The drag holds the slot", content.moves.isBusy)
                assertTrue("Its own sheet stays draggable", nested.sheetDraggable)
                assertNull("Nothing else is admitted during the drag", content.moves.tryTake(null))
                nested.sheetState("STATE_EXPANDED")
                assertFalse("A drag that returns gives the slot back", content.moves.isBusy)
                assertEquals(3, nested.layerCount())

                nested.sheetState("STATE_DRAGGING")
                nested.sheetState("STATE_SETTLING")
                nested.sheetState("STATE_HIDDEN")
                assertTrue("A hidden sheet keeps the slot until its close lands", content.moves.isBusy)
                nested.settle { nested.layerCount() == 2 && !content.moves.isBusy }
                p.frame(); p.assertHeight(400f)
            } finally { nested.close() }
        }
    }

    @Test fun `a nested sheet dismissed during a move closes after the move lands`() = runBlocking {
        withPushPages { a, p, content ->
            a.start(); a.frame(); p.start(); p.frame()
            val nested = ActivityPage(content.copy(isPushPage = false, layerContentHeight = AtomicReference()),
                lower = p, baseHeights = mapOf(1 to 180f, 2 to 320f))
            val running = CompletableDeferred<Unit>()
            try {
                nested.start(); nested.frame(); nested.enableSheetInput()
                val move = launch(start = CoroutineStart.UNDISPATCHED) { content.moves.run { running.await() } }
                // TalkBack's dismiss action hides the sheet without a drag; Material then cancels the dialog.
                nested.sheetState("STATE_HIDDEN")
                nested.idle()
                assertEquals("The close waits for the running move", 3, nested.layerCount())
                running.complete(Unit); move.join()
                nested.settle { nested.layerCount() == 2 && !content.moves.isBusy }
            } finally { running.complete(Unit); nested.close() }
        }
    }

    @Test fun `an outside tap while nothing moves closes the nested sheet through the queue`() = runBlocking {
        withPushPages { a, p, content ->
            a.start(); a.frame(); p.start(); p.frame()
            val nested = ActivityPage(content.copy(isPushPage = false, layerContentHeight = AtomicReference()),
                lower = p, baseHeights = mapOf(1 to 180f, 2 to 320f))
            try {
                nested.start(); nested.frame(); nested.enableSheetInput()
                nested.cancelSheet()
                nested.settle { nested.layerCount() == 2 && !content.moves.isBusy }
            } finally { nested.close() }
        }
    }

    private suspend fun withPushPages(lowerAtB: Boolean = false,
        exercise: suspend (ActivityPage, ActivityPage, PreparedPresentation) -> Unit) {
        val scene = java.io.File.createTempFile("page-push-height", ".riv").apply { writeBytes(byteArrayOf(1)) }
        val descriptor = Json.parseToJsonElement("""{"state":{},"responses":{},"ruleGroups":[],
            "leg":{"screens":[{"id":"a","defaultViewModelName":"A"},{"id":"b","defaultViewModelName":"B"}]},
            "render":{"assets":[],"screens":[{"id":"a","artboardName":"A"},{"id":"b","artboardName":"B"}]}}""").jsonObject
        val lower = PreparedPresentation(scene, if (lowerAtB) "B" else "A", 0, fitContent,
            if (lowerAtB) "b" else "a", descriptor, pageHistory = if (lowerAtB) listOf("a", "b") else listOf("a"))
        val pushed = lower.copy(artboardName = "B", screenId = "b", pageHistory = listOf("b"),
            isPushPage = true, opensLayer = true, layerAncestors = listOf("height-lower"),
            layerContentHeight = AtomicReference(), screenLifecycle = ExperienceScreenLifecycle(), retainedViewModel = AtomicReference())
        val a = ActivityPage(lower)
        val p = ActivityPage(pushed, lower = a, baseHeights = mapOf(1 to 240f, 2 to 400f))
        try { exercise(a, p, pushed) } finally { p.close(); a.close(); scene.delete() }
    }

    /** Real mounted-page listener and Activity Screen callbacks; only the native runtime is faked. */
    private class ActivityPage(initial: PreparedPresentation, private val lower: ActivityPage? = null,
        private val baseHeights: Map<Int, Float> = mapOf(1 to 152f, 2 to 280f)) {
        private val controller: org.robolectric.android.controller.ActivityController<NuxieExperienceActivity> =
            lower?.controller ?: Robolectric.buildActivity(NuxieExperienceActivity::class.java)
        private val activity = controller.get()
        private val screenClass = NuxieExperienceActivity::class.java.declaredClasses.single { it.simpleName == "Screen" }
        private val layerClass = NuxieExperienceActivity::class.java.declaredClasses.single { it.simpleName == "Layer" }
        private val registryId = java.util.UUID.randomUUID().toString()
        private var prepared = initial.copy(closeLayer = { reveal -> reveal.prepare(); reveal.activate(); true })
        private fun screen(content: PreparedPresentation): ExperienceSurfaceHost.Listener =
            screenClass.declaredConstructors.single { it.parameterCount == 4 }.apply { isAccessible = true }
                .newInstance(activity, registryId, content, false) as ExperienceSurfaceHost.Listener
        private var current = screen(prepared)
        private val failures = mutableListOf<Throwable>()
        private val mounted = ExperienceMountedScreen(activity, prepared, current, { failures += it },
            createLane = ::TestRuntimeLane)
        val surface = mounted.surface
        val texture = SurfaceTexture(0)
        private val lane = field(mounted, "lane") as NuxieRuntimeLane
        private val native = ExperienceInteractivePageTest.RecordingNative().apply {
            presentation = 1
            contentHeights.putAll(baseHeights)
        }
        private val root = FrameLayout(activity)
        private var window: ExperienceNativeLayerWindow = if (lower != null) lower.window
            else ExperienceNativeLayerWindow(activity, root, initial.shell) {}
        private val layer = layerClass.declaredConstructors.single { it.parameterCount == 5 }.apply { isAccessible = true }
            .newInstance(activity, root, current, initial.shell, initial.isPushPage)
        private var clock = 1_000_000_000L
        private var closed = false

        suspend fun start(install: Boolean = true, covered: Boolean = false) {
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
                    native.contentHeights[2] = checkNotNull(baseHeights[2]) + if (width < 350f) 40f else 0f
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
            val view = mounted.mount(entering = !covered)
            set(current, "view", view)
            root.addView(view)
            if (install) install()
            if (covered) mounted.hide()
            lane.call {}; shadowOf(Looper.getMainLooper()).idle()
            surface.onSurfaceTextureAvailable(texture, 393, 852)
        }

        fun install() {
            @Suppress("UNCHECKED_CAST")
            (field(activity, "layers") as MutableList<Any>).add(layer)
            set(activity, "currentScreen", current)
            set(activity, "contentRoot", root)
            set(activity, "visible", true)
            PresentationRegistry.register(registryId,
                prepared, {}, { throw AssertionError(it) }, {}, {})
            if (lower == null) {
                set(layer, "sheet", window)
                window.show()
            } else {
                lower.mounted.hide()
                val method = if (prepared.isPushPage) "installPushedPage" else "installLayerWindow"
                if (prepared.isPushPage) NuxieExperienceActivity::class.java.getDeclaredMethod(method, layerClass, layerClass)
                    .apply { isAccessible = true }.invoke(activity, layer, lower.layer)
                else {
                    NuxieExperienceActivity::class.java.getDeclaredMethod(method, layerClass, Function0::class.java)
                        .apply { isAccessible = true }.invoke(activity, layer, null)
                    window = field(layer, "sheet") as ExperienceNativeLayerWindow
                }
            }
        }

        suspend fun pop() {
            // Exercise the real close/reveal path without scheduling a native slide animator.
            set(mounted, "reduceMotionEnabled", true)
            assertTrue(activityCall { (current as PresentationScreenHandle).dismissPagesFromJourney(1) })
            assertFalse(activity.isFinishing)
        }

        /** A close's caller is cancelled after the close started (S1c): the close still lands. */
        suspend fun popWhileItsCallerGivesUp() {
            set(mounted, "reduceMotionEnabled", true)
            coroutineScope {
                val close = launch(start = CoroutineStart.UNDISPATCHED) {
                    (current as PresentationScreenHandle).dismissPagesFromJourney(1)
                }
                assertFalse("The close is still running", close.isCompleted)
                assertNotNull("The close has started", field(activity, "closingLayer"))
                close.cancel()
                withTimeout(5_000) {
                    while (!close.isCompleted) { shadowOf(Looper.getMainLooper()).idle(); delay(1) }
                }
            }
            assertEquals("The started close lands: one page is left", 1, (field(activity, "layers") as List<*>).size)
            assertFalse(activity.isFinishing)
        }

        suspend fun cutCovered(target: PreparedPresentation) {
            prepared = checkNotNull(activityCall { (current as PresentationScreenHandle).cutCoveredPage(target) })
        }

        private suspend fun <T> activityCall(block: suspend () -> T): T = coroutineScope {
            val result = async(start = CoroutineStart.UNDISPATCHED) { block() }
            withTimeout(5_000) {
                while (!result.isCompleted) {
                    // Activity methods resume on Main after runtime-lane work; runBlocking
                    // alone does not dispatch Robolectric's queued main-looper callbacks.
                    shadowOf(Looper.getMainLooper()).idle()
                    delay(1)
                }
            }
            result.await()
        }

        fun content(): PreparedPresentation = prepared

        private val sheet: ExperienceNativeLayerWindow get() = field(layer, "sheet") as ExperienceNativeLayerWindow
        private val behavior get() = (sheet.dialog as com.google.android.material.bottomsheet.BottomSheetDialog).behavior

        /** What the reveal does for a dismissible sheet. */
        fun enableSheetInput() { sheet.setInputEnabled(true); idle() }
        val sheetDraggable: Boolean get() = behavior.isDraggable
        val sheetCancelsOnTouchOutside: Boolean get() = shadowOf(sheet.dialog).isCancelableOnTouchOutside
        fun layerCount() = (field(activity, "layers") as List<*>).size
        fun idle() = shadowOf(Looper.getMainLooper()).idle()

        /** Material's own state path, with its callbacks: what a drag or TalkBack's dismiss action selects. */
        fun sheetState(name: String) {
            check(org.robolectric.util.ReflectionHelpers.getField<Any?>(behavior, "viewRef") != null) {
                "The sheet has not been laid out, so Material sends no state callbacks"
            }
            org.robolectric.util.ReflectionHelpers.callInstanceMethod<Unit>(behavior, "setStateInternal",
                org.robolectric.util.ReflectionHelpers.ClassParameter.from(Int::class.javaPrimitiveType,
                    com.google.android.material.bottomsheet.BottomSheetBehavior::class.java.getField(name).getInt(null)))
        }

        /** An outside tap on a sheet that allows it. */
        fun cancelSheet() { check(sheetCancelsOnTouchOutside); sheet.dialog.cancel() }

        suspend fun settle(done: () -> Boolean) {
            withTimeout(5_000) { while (!done()) { idle(); delay(1) } }
        }

        suspend fun queries(): Int = lane.call { native.contentHeightQueries }

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

        suspend fun navigate(target: PreparedPresentation, frameBeforeCommit: Boolean = false) {
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

        fun assertHeight(expected: Float) {
            assertEquals(expected, prepared.layerContentHeight.get())
            assertEquals(expected, field(current, "measuredContentHeight"))
            assertWindowHeight(expected)
        }

        fun assertWindowHeight(expected: Float) {
            val hostedWindow = if (prepared.isPushPage) window else field(layer, "sheet") as ExperienceNativeLayerWindow
            assertEquals(expected.toInt(), hostedWindow.dialog.findViewById<View>(
                com.google.android.material.R.id.design_bottom_sheet).layoutParams.height)
        }

        fun close(recreating: Boolean = false) {
            if (closed) return
            closed = true
            screenClass.getDeclaredMethod("close", Boolean::class.javaPrimitiveType).apply { isAccessible = true }.invoke(current, recreating)
            assertTrue(lane.awaitQuiescence(5_000))
            texture.release()
            (field(layer, "sheet") as? ExperienceNativeLayerWindow)?.retire()
            if (lower == null) controller.destroy()
            PresentationRegistry.dismiss(registryId, CloseReason.HostDismissed)
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

    @Test fun `page retirement captures row mutations newer than the last presented frame`() = runBlocking {
        withPushPages { a, p, _ ->
            a.start(); a.frame()
            p.start(); p.frame()
            val retained = p.content().retainedViewModel
            p.surface.layerOperation { page ->
                page.valuesOnLane("b").setValue("label", NuxieViewModelScalarValue.StringValue("after the last frame"))
            }
            // No intervening ordinary step is allowed to refresh the retained capture.
            p.close(recreating = true)
            assertEquals("after the last frame", checkNotNull(retained.get()).resolveString("label"))
        }
    }

    @Test fun `page viewport environment follows initial logical size and surface resize before stepping`() = runBlocking {
        for (hasSize in listOf(false, true)) checkPreparation(PresentationShell.FullScreen(), covered = false,
            hasSize = hasSize, environmentViewport = true) { host, lane, texture, _, _, native, failures ->
            fun viewport() = native.writes.filter { it.second.path in setOf("width", "height") }
                .associate { (owner, write) ->
                    assertEquals("Only the page global env receives viewport values", 500L, owner)
                    write.path to write.numberValue
                }
            assertEquals(if (hasSize) mapOf("width" to 393f, "height" to 852f) else emptyMap(), viewport())
            native.writes.clear()
            host.onSurfaceTextureAvailable(texture, 300, 600)
            host.doFrame(1_000_000_000L); lane.call {}; shadowOf(Looper.getMainLooper()).idle()
            assertEquals(mapOf("width" to 300f, "height" to 600f), viewport())
            assertTrue(failures.isEmpty())
        }
    }

    private suspend fun checkPreparation(shell: PresentationShell, covered: Boolean, hasSize: Boolean, environmentViewport: Boolean = false,
        exercise: (suspend (ExperienceSurfaceHost, NuxieRuntimeLane, SurfaceTexture, ExperienceNativeLayerWindow,
            MutableList<Float?>, ExperienceInteractivePageTest.RecordingNative, MutableList<ExperiencePresentationException>) -> Unit)? = null) {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        val activity = controller.get()
        val lane = TestRuntimeLane()
        val pageNative = ExperienceInteractivePageTest.RecordingNative().apply { presentation = 1; globals = environmentViewport }
        val native = object : NuxieTypedRuntimeNative by pageNative {
            var size = floatArrayOf(393f, 852f)
            override fun viewModelCatalog(fileHandle: Long): NativeCallResult<NativeViewModelCatalog> {
                val catalog = checkNotNull(pageNative.viewModelCatalog(fileHandle).value)
                if (!environmentViewport) return NativeCallResult(0, catalog)
                val props = catalog.properties.toMutableList().apply {
                    addAll(16, listOf(NativeViewModelProperty(4, 2, "width", 2, -1, emptyArray()),
                        NativeViewModelProperty(4, 3, "height", 2, -1, emptyArray())))
                }
                return NativeCallResult(0, catalog.copy(properties = props.toTypedArray(), schemas = catalog.schemas.map {
                    if (it.index == 4L) it.copy(propertyCount = 4)
                    else if (it.firstProperty >= 16) it.copy(firstProperty = it.firstProperty + 2) else it
                }.toTypedArray()))
            }
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
