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
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
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

    @Test fun `a started page close lands even when its caller gives up`() = runBlocking {
        withPushPages { a, p, _ ->
            a.start(); a.frame(); p.start(); p.frame()
            p.popWhileItsCallerGivesUp()
            a.frame()
        }
    }

    @Test fun `a close while the Activity is stopped does not wait for the running move`() = runBlocking {
        withPushPages { a, p, content ->
            a.start(); a.frame(); p.start(); p.frame()
            val lands = CompletableDeferred<Unit>()
            val move = launch(start = CoroutineStart.UNDISPATCHED) {
                content.moves.run { check(it.start()); withContext(NonCancellable) { lands.await() } }
            }
            try {
                a.stopActivity()
                withTimeout(1_000) { content.moves.closeAfterLanding() }
                assertFalse("No slot is held after the close", content.moves.isBusy)
            } finally { lands.complete(Unit); move.join() }
        }
    }

    @Test fun `a close waits for the running move again once the Activity is back`() = runBlocking {
        withPushPages { a, p, content ->
            a.start(); a.frame(); p.start(); p.frame()
            val lands = CompletableDeferred<Unit>()
            val move = launch(start = CoroutineStart.UNDISPATCHED) {
                content.moves.run { check(it.start()); withContext(NonCancellable) { lands.await() } }
            }
            try {
                a.stopActivity(); a.startActivity()
                val close = async { content.moves.closeAfterLanding() }
                delay(200)
                assertFalse("Started again, a close lets the move land first", close.isCompleted)
                lands.complete(Unit)
                withTimeout(1_000) { close.await() }
            } finally { lands.complete(Unit); move.join() }
        }
    }

    @Test fun `a queue first seen while the Activity is stopped starts stopped`() = runBlocking {
        withPushPages { a, p, content ->
            a.start(); a.frame(); p.start(); p.frame()
            val lands = CompletableDeferred<Unit>()
            val move = launch(start = CoroutineStart.UNDISPATCHED) {
                content.moves.run { check(it.start()); withContext(NonCancellable) { lands.await() } }
            }
            try {
                a.stopActivity()
                // As a root mounted in the background: its queue arrives after onStop and never heard it.
                content.moves.setStopped(false)
                a.watchMovesAfresh()
                withTimeout(1_000) { content.moves.closeAfterLanding() }
                assertFalse("No slot is held after the close", content.moves.isBusy)
            } finally { lands.complete(Unit); move.join() }
        }
    }

    @Test fun `destroying the Activity ends the running move as closed`() = runBlocking {
        withPushPages { a, p, content ->
            a.start(); a.frame(); p.start(); p.frame()
            val lands = CompletableDeferred<Unit>()
            val move = launch(start = CoroutineStart.UNDISPATCHED) {
                content.moves.run { check(it.start()); withContext(NonCancellable) { lands.await() } }
            }
            val behind = async(start = CoroutineStart.UNDISPATCHED) { runCatching { content.moves.run { } } }
            try {
                a.destroyActivity()
                assertFalse("No slot is held once the Activity is gone", content.moves.isBusy)
                assertTrue(behind.await().exceptionOrNull() is ExperienceMoveQueue.Closed)
            } finally { lands.complete(Unit); move.join() }
        }
    }

    @Test fun `a rebuild closes the Experience as the host's close`() = runBlocking {
        withPushPages { a, p, content ->
            a.start(); a.frame(); p.start(); p.frame()
            // The Activity both pages share; the page at the base owns its destroy.
            a.rebuildActivity()
            p.settle { p.outcomes.isNotEmpty() && a.outcomes.isNotEmpty() }
            assertEquals("The Screen on top closes as the host's close", listOf(CloseReason.HostDismissed), p.outcomes)
            assertEquals("The page beneath it closes as a navigation", listOf(CloseReason.JourneyNavigation), a.outcomes)
            assertFalse("No slot is held after a rebuild", content.moves.isBusy)
        }
    }

    @Test fun `a rebuild ends the running move as closed, and a request behind it is closed too`() = runBlocking {
        withPushPages { a, p, content ->
            a.start(); a.frame(); p.start(); p.frame()
            val lands = CompletableDeferred<Unit>()
            val move = launch(start = CoroutineStart.UNDISPATCHED) {
                content.moves.run { check(it.start()); withContext(NonCancellable) { lands.await() } }
            }
            val behind = async(start = CoroutineStart.UNDISPATCHED) { runCatching { content.moves.run { } } }
            try {
                a.rebuildActivity()
                assertFalse("No slot is held once the Activity is rebuilt", content.moves.isBusy)
                assertTrue(behind.await().exceptionOrNull() is ExperienceMoveQueue.Closed)
            } finally { lands.complete(Unit); move.join() }
        }
    }

    @Test fun `a page close holds its move until the removed page has shut down`() = runBlocking {
        withPushPages { a, p, content ->
            a.start(); a.frame(); p.start(); p.frame()
            p.popHeldAtShutdown { close ->
                assertFalse("The close waits for the removed page's shutdown", close.isCompleted)
                assertTrue("The close still holds its move", content.moves.isBusy)
            }
            assertFalse("The slot is free once the page has shut down", content.moves.isBusy)
        }
    }

    @Test fun `a rebuilt Activity finishes without touching its presentation`() {
        val id = java.util.UUID.randomUUID().toString()
        val scene = java.io.File.createTempFile("page-rebuilt", ".riv").apply { writeBytes(byteArrayOf(1)) }
        val descriptor = Json.parseToJsonElement("""{"state":{},"responses":{},"ruleGroups":[],
            "leg":{"screens":[{"id":"a","defaultViewModelName":"A"}]},
            "render":{"assets":[],"screens":[{"id":"a","artboardName":"A"}]}}""").jsonObject
        val failures = mutableListOf<Throwable>()
        PresentationRegistry.register(id, PreparedPresentation(scene, "A", 0, fitContent, "a", descriptor),
            {}, { failures += it }, {}, {})
        try {
            val intent = android.content.Intent(org.robolectric.RuntimeEnvironment.getApplication(), NuxieExperienceActivity::class.java)
                .putExtra(NuxieExperienceActivity.EXTRA_PRESENTATION_ID, id)
            // What Android hands the Activity it builds after a rebuild: the old one's saved state.
            val controller = Robolectric.buildActivity(NuxieExperienceActivity::class.java, intent).create(android.os.Bundle())
            shadowOf(Looper.getMainLooper()).idle()
            assertTrue(controller.get().isFinishing)
            assertTrue("The rebuilt Activity reports nothing: $failures", failures.isEmpty())
            assertNull(PresentationRegistry.currentScreen(id))
            assertTrue(PresentationRegistry.observe(id)?.value is PresentationContentState.Ready)
            controller.destroy()
        } finally { PresentationRegistry.dismiss(id, CloseReason.HostDismissed); scene.delete() }
    }

    @Test fun `a nested sheet refuses a drag and outside taps while a move runs`() = runBlocking {
        withPushPages { a, p, content ->
            a.start(); a.frame(); p.start(); p.frame()
            val nested = ActivityPage(content.copy(isPushPage = false, layerContentHeight = AtomicReference()),
                lower = p)
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
                lower = p)
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
                p.frame()
            } finally { nested.close() }
        }
    }

    @Test fun `a nested sheet dismissed during a move closes after the move lands`() = runBlocking {
        withPushPages { a, p, content ->
            a.start(); a.frame(); p.start(); p.frame()
            val nested = ActivityPage(content.copy(isPushPage = false, layerContentHeight = AtomicReference()),
                lower = p)
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

    @Test fun `a page under a sheet keeps running, plays no exit, and comes back with no entrance`() = runBlocking {
        withPushPages { a, p, content ->
            a.start(); a.frame(); p.start(); p.frame()
            val phase = p.lifecycle.phase
            val appearances = p.lifecycle.appearances
            val nested = ActivityPage(content.copy(isPushPage = false, layerContentHeight = AtomicReference(),
                screenLifecycle = ExperienceScreenLifecycle()), lower = p)
            try {
                nested.start(); nested.frame()
                assertEquals("The page under the sheet plays no exit and isn't hidden", phase, p.lifecycle.phase)
                assertTrue("The page under the sheet keeps drawing", p.running)
                p.clearTrace(); p.frame()
                assertEquals("Its authored time goes on", 1, p.steps().size)
                assertEquals("It leaves TalkBack while covered",
                    View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS, p.accessibility)
                nested.pop()
                nested.settle { nested.layerCount() == 2 }
                assertEquals("The reveal activates it", ExperienceScreenLifecycle.Phase.ACTIVE, p.lifecycle.phase)
                assertEquals("No entrance replays", appearances, p.lifecycle.appearances)
                assertTrue(p.running)
            } finally { nested.close() }
        }
    }

    @Test fun `a page under a sheet resumes after onStart, and a page under a push stays stopped`() = runBlocking {
        withPushPages { a, p, content ->
            a.start(); a.frame(); p.start(); p.frame()
            val nested = ActivityPage(content.copy(isPushPage = false, layerContentHeight = AtomicReference(),
                screenLifecycle = ExperienceScreenLifecycle()), lower = p)
            try {
                nested.start(); nested.frame()
                assertFalse("The page under the push is stopped", a.running)
                p.stopActivity()
                assertFalse(p.running); assertFalse(nested.running)
                p.startActivity()
                assertTrue("The page under the sheet resumes", p.running)
                assertTrue(nested.running)
                assertFalse("The page under the push stays stopped", a.running)
            } finally { nested.close() }
        }
    }

    @Test fun `a held checkpoint stops authored time and keeps the page drawn`() = runBlocking {
        withPushPages { a, p, _ ->
            a.start(); a.frame(); p.start(); p.frame(); p.frame()
            p.clearTrace()
            p.hold(true)
            repeat(3) { p.frame() }
            assertEquals("No step while held", emptyList<String>(), p.steps())
            assertTrue("The page stays presented", p.running)
            p.hold(false)
            p.frame(); p.frame()
            assertEquals("Authored time resumes from where it stood", listOf("step:0.0", "step:0.016"), p.steps())
        }
    }

    @Test fun `an outside tap while nothing moves closes the nested sheet through the queue`() = runBlocking {
        withPushPages { a, p, content ->
            a.start(); a.frame(); p.start(); p.frame()
            val nested = ActivityPage(content.copy(isPushPage = false, layerContentHeight = AtomicReference()),
                lower = p)
            try {
                nested.start(); nested.frame(); nested.enableSheetInput()
                nested.cancelSheet()
                nested.settle { nested.layerCount() == 2 && !content.moves.isBusy }
            } finally { nested.close() }
        }
    }

    private suspend fun withPushPages(
        exercise: suspend (ActivityPage, ActivityPage, PreparedPresentation) -> Unit) {
        val scene = java.io.File.createTempFile("page-push-height", ".riv").apply { writeBytes(byteArrayOf(1)) }
        val descriptor = Json.parseToJsonElement("""{"state":{},"responses":{},"ruleGroups":[],
            "leg":{"screens":[{"id":"a","defaultViewModelName":"A"},{"id":"b","defaultViewModelName":"B"}]},
            "render":{"assets":[],"screens":[{"id":"a","artboardName":"A"},{"id":"b","artboardName":"B"}]}}""").jsonObject
        val lower = PreparedPresentation(scene, "A", 0, fitContent, "a", descriptor, pageHistory = listOf("a"))
        val pushed = lower.copy(artboardName = "B", screenId = "b", pageHistory = listOf("b"),
            isPushPage = true, opensLayer = true, layerAncestors = listOf("height-lower"),
            layerContentHeight = AtomicReference(), screenLifecycle = ExperienceScreenLifecycle(), retainedViewModel = AtomicReference())
        val a = ActivityPage(lower)
        val p = ActivityPage(pushed, lower = a)
        try { exercise(a, p, pushed) } finally { p.close(); a.close(); scene.delete() }
    }

    /** Real mounted-page listener and Activity Screen callbacks; only the native runtime is faked. */
    private class ActivityPage(initial: PreparedPresentation, private val lower: ActivityPage? = null) {
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
        private val native = ExperienceInteractivePageTest.RecordingNative().apply { presentation = 1 }
        private val root = FrameLayout(activity)
        private var window: ExperienceNativeLayerWindow = if (lower != null) lower.window
            else ExperienceNativeLayerWindow(activity, root, initial.shell) {}
        private val layer = layerClass.declaredConstructors.single { it.parameterCount == 5 }.apply { isAccessible = true }
            .newInstance(activity, root, current, initial.shell, initial.isPushPage)
        private var clock = 1_000_000_000L
        private var closed = false
        private var destroyed = false
        /** The close reasons the registry hears for this Screen. */
        val outcomes = mutableListOf<CloseReason>()

        /** The Activity's own lifecycle, through Robolectric's controller. */
        fun stopActivity() { controller.stop() }
        fun startActivity() { controller.start() }
        fun destroyActivity() { destroyed = true; controller.destroy() }
        /** A rebuild Android forces, such as a language change the manifest doesn't list. */
        fun rebuildActivity() {
            org.robolectric.util.ReflectionHelpers.setField(Activity::class.java, activity, "mChangingConfigurations", true)
            destroyActivity()
        }

        /** As a queue the Activity sees for the first time: it forgets the one it watches and watches again. */
        fun watchMovesAfresh() {
            set(activity, "watchedMoves", null)
            NuxieExperienceActivity::class.java.getDeclaredMethod("watchMoves").apply { isAccessible = true }.invoke(activity)
        }

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
                prepared, {}, { throw AssertionError(it) }, {}, { synchronized(outcomes) { outcomes += it } })
            set(current, "registered", true)
            @Suppress("UNCHECKED_CAST")
            (field(activity, "screens") as MutableSet<Any>).add(current)
            if (lower == null) {
                set(layer, "sheet", window)
                window.show()
            } else {
                // As the Activity does: a push hides the page below. A sheet's install covers it.
                if (prepared.isPushPage) lower.mounted.hide()
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
            // The fake lane runs on its own thread, so the exit may finish before the close waits on it.
            // Holding the close at its reveal keeps it running, whichever thread wins.
            val reveal = CompletableDeferred<Unit>()
            set(current, "prepared", prepared.copy(closeLayer = { reveal.await(); it.prepare(); it.activate(); true }))
            coroutineScope {
                val close = launch(start = CoroutineStart.UNDISPATCHED) {
                    (current as PresentationScreenHandle).dismissPagesFromJourney(1)
                }
                assertFalse("The close is still running", close.isCompleted)
                assertNotNull("The close has started", field(activity, "closingLayer"))
                close.cancel()
                reveal.complete(Unit)
                withTimeout(5_000) {
                    while (!close.isCompleted) { shadowOf(Looper.getMainLooper()).idle(); delay(1) }
                }
            }
            assertEquals("The started close lands: one page is left", 1, (field(activity, "layers") as List<*>).size)
            assertFalse(activity.isFinishing)
        }

        /** A close whose removed page can't finish shutting down until [whileHeld] returns: its lane is held. */
        suspend fun popHeldAtShutdown(whileHeld: (kotlinx.coroutines.Job) -> Unit) {
            set(mounted, "reduceMotionEnabled", true)
            val shutdown = java.util.concurrent.CountDownLatch(1)
            set(current, "prepared", prepared.copy(closeLayer = { reveal ->
                check(lane.enqueue { shutdown.await(10, java.util.concurrent.TimeUnit.SECONDS) })
                reveal.prepare(); reveal.activate(); true
            }))
            try {
                coroutineScope {
                    val close = launch(start = CoroutineStart.UNDISPATCHED) {
                        (current as PresentationScreenHandle).dismissPagesFromJourney(1)
                    }
                    // The reveal's preparation has removed the page; give the close every chance to finish early.
                    withTimeout(5_000) { while (layerCount() != 1) { idle(); delay(1) } }
                    repeat(50) { idle(); delay(1) }
                    whileHeld(close)
                    shutdown.countDown()
                    withTimeout(5_000) { while (!close.isCompleted) { idle(); delay(1) } }
                }
            } finally { shutdown.countDown() }
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
        /** The page's clock: whether its surface draws and steps. */
        val running: Boolean get() = field(surface, "running") as Boolean
        val lifecycle: ExperienceScreenLifecycle get() = prepared.screenLifecycle
        val accessibility: Int get() = (field(current, "view") as View).importantForAccessibility
        fun hold(held: Boolean) { mounted.setCheckpointHold(held); idle() }
        suspend fun steps(): List<String> = lane.call { native.trace.filter { it.startsWith("step:") } }
        suspend fun clearTrace() = lane.call { native.trace.clear() }
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
                lane.call { native.trace.clear() }
                val step = async(start = CoroutineStart.UNDISPATCHED) { mounted.navigateLayer(target, target.isBack) }
                withTimeout(5_000) {
                    while (!lane.call { native.trace.any { it.startsWith("begin:") } }) { frame(); yield() }
                }
                frame()
                assertFalse("Reverse completion has not landed", step.isCompleted)
                // No completion events: the normal watchdog lands this step after the asserted frame.
                step.await()
            } else mounted.navigateLayer(target, target.isBack)
            // Navigation.activate transfers the page, replaces the layer Screen and closes the source.
            if (frameBeforeCommit) frame()
            set(layer, "screen", next)
            screenClass.getDeclaredMethod("takeLayerOwnership", screenClass).apply { isAccessible = true }
                .invoke(next, current)
            screenClass.getDeclaredMethod("close", Boolean::class.javaPrimitiveType).apply { isAccessible = true }.invoke(current, false)
            @Suppress("UNCHECKED_CAST")
            (field(activity, "screens") as MutableSet<Any>).apply { remove(current); add(next) }
            current = next
            prepared = target
            set(activity, "currentScreen", next)
        }

        /** [forRetry] retires the Screen as a native retry does, keeping its final state. */
        fun close(forRetry: Boolean = false) {
            if (closed) return
            closed = true
            screenClass.getDeclaredMethod("close", Boolean::class.javaPrimitiveType).apply { isAccessible = true }.invoke(current, forRetry)
            assertTrue(lane.awaitQuiescence(5_000))
            texture.release()
            (field(layer, "sheet") as? ExperienceNativeLayerWindow)?.retire()
            if (lower == null && !destroyed) controller.destroy()
            PresentationRegistry.dismiss(registryId, CloseReason.HostDismissed)
        }

        companion object {
            private fun field(owner: Any, name: String): Any? = owner.javaClass.getDeclaredField(name)
                .apply { isAccessible = true }.get(owner)
            private fun set(owner: Any, name: String, value: Any?) = owner.javaClass.getDeclaredField(name)
                .apply { isAccessible = true }.set(owner, value)
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
            p.close(forRetry = true)
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
