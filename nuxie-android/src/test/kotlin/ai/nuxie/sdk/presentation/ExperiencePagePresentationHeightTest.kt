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

    @Test fun `Back across two sheets plays each sheet's exit, top down, with the page beneath live`() = runBlocking {
        withPushPages { a, p, content ->
            a.start(); a.frame(); p.start(); p.frame()
            fun sheet(lower: ActivityPage) = ActivityPage(content.copy(isPushPage = false, layerContentHeight = AtomicReference(),
                screenLifecycle = ExperienceScreenLifecycle()), lower = lower)
            val first = sheet(p)
            try {
                first.start(); first.frame()
                val second = sheet(first)
                try {
                    second.start(); second.frame()
                    first.reduceMotion(); first.tracePhases(); second.tracePhases(); p.tracePhases()
                    second.pop(pages = 2)
                    second.settle { second.layerCount() == 2 }
                    assertEquals("The top sheet's page exits, then hides", listOf("exiting", "hidden"), second.phaseTrace())
                    assertEquals("The sheet below it exits too, then hides", listOf("exiting", "hidden"), first.phaseTrace())
                    assertTrue("The page beneath both stays live", p.running)
                    assertFalse("It replays no entrance", "entering" in p.phaseTrace())
                } finally { second.close() }
            } finally { first.close() }
        }
    }

    @Test fun `in a debug app authored time on a stopped page clock stops the app`() = runBlocking {
        withPushPages { a, p, _ ->
            a.start(); a.frame(); p.start(); p.frame()
            p.debuggable(); a.reduceMotion(); p.reduceMotion()
            assertEquals("Hidden below a push", "Page clock: authored time on a stopped clock", a.exitFailure()?.message)
            p.hold(true)
            assertEquals("Held at a checkpoint", "Page clock: authored time on a stopped clock", p.exitFailure()?.message)
            p.hold(false)
            assertNull("A running page may wait", p.exitFailure())
        }
    }

    @Test fun `in a debug app a page whose Activity is stopped may wait on authored time`() = runBlocking {
        withPushPages { a, p, _ ->
            a.start(); a.frame(); p.start(); p.frame()
            p.debuggable(); p.reduceMotion()
            p.stopActivity()
            try { assertNull(p.exitFailure()) } finally { p.startActivity() }
        }
    }

    @Test fun `a page move that starts before a reveal's first publication leaves the page in TalkBack`() = runBlocking {
        withPushPages { a, p, content ->
            a.start(); a.frame(); p.start(); p.frame()
            val nested = ActivityPage(content.copy(isPushPage = false, layerContentHeight = AtomicReference(),
                screenLifecycle = ExperienceScreenLifecycle()), lower = p)
            try {
                nested.start(); nested.frame()
                nested.pop()
                nested.settle { nested.layerCount() == 2 }
            } finally { nested.close() }
            assertTrue("Revealed, it waits for its first publication", p.awaitingPublication)
            val move = p.pageMove("a")
            assertEquals("Out of TalkBack during the move", View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS, p.accessibility)
            p.land(move)
            // The step gives input back as it ends, so the page publishes before the move's landing.
            p.frame()
            assertFalse("It published", p.awaitingPublication)
            p.activate(move)
            assertEquals("After landing it is in TalkBack", View.IMPORTANT_FOR_ACCESSIBILITY_AUTO, p.accessibility)
        }
    }

    @Test fun `a sheet the person holds is authored time until the finger lifts`() = runBlocking {
        withPushPages { a, p, content ->
            a.start(); a.frame(); p.start(); p.frame()
            val nested = ActivityPage(content.copy(isPushPage = false, layerContentHeight = AtomicReference(),
                screenLifecycle = ExperienceScreenLifecycle()), lower = p)
            try {
                nested.start(); nested.frame(); nested.enableSheetInput()
                nested.sheetState("STATE_DRAGGING")
                val native = async { content.moves.authoredTime.delayNative(100) }
                delay(400); nested.idle()
                assertFalse("While held, the close's native clock pauses", native.isCompleted)
                nested.sheetState("STATE_SETTLING")
                withTimeout(2_000) { native.await() }
                nested.sheetState("STATE_EXPANDED")
                nested.settle { !content.moves.isBusy }
            } finally { nested.close() }
        }
    }

    @Test fun `a field under a sheet follows the page as it plays, disabled and out of TalkBack`() = runBlocking {
        withPushPages(field = true) { a, p, content ->
            a.start(); a.frame(); p.start(); p.frame()
            // Publications start once the first frame is composed, so the field is drawn from the next one.
            p.showField("Ada", x = 0f)
            p.frame()
            val editor = p.fieldEditor
            assertTrue("Before the sheet the field is editable", editor.isEnabled)
            assertEquals(0f, (editor.parent as View).x, 0.001f)
            val nested = ActivityPage(content.copy(isPushPage = false, layerContentHeight = AtomicReference(),
                screenLifecycle = ExperienceScreenLifecycle()), lower = p)
            try {
                nested.start(); nested.frame()
                // The page plays on under the sheet: its field moves and its text changes.
                p.showField("Ada Lovelace", x = 40f)
                p.frame()
                assertSame(editor, p.fieldEditor)
                assertEquals("Its editor follows the text", "Ada Lovelace", editor.text.toString())
                assertEquals("and the spot", 40f, (editor.parent as View).x, 0.001f)
                assertFalse("It stays disabled", editor.isEnabled)
                assertEquals("It isn't announced", View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS, p.accessibility)
                assertTrue("The content gate waits for input to return", p.awaitingPublication)
                nested.pop()
                nested.settle { nested.layerCount() == 2 }
            } finally { nested.close() }
            p.frame()
            assertFalse("The first publication after input returns lifts the gate", p.awaitingPublication)
            assertTrue(editor.isEnabled)
        }
    }

    @Test fun `a host close while a page pop is paused at onStop closes at once`() = runBlocking {
        withPushPages { a, p, content ->
            a.start(); a.frame(); p.start(); p.frame()
            val pop = p.popSliding()
            try {
                p.frameUntil { p.sliding }
                a.stopActivity()
                p.idleFor(1_000)
                assertTrue("Stopped, the slide waits", p.sliding)
                withTimeout(1_000) { content.moves.closeAfterLanding() }
                assertFalse("No slot is held after the close", content.moves.isBusy)
            } finally { a.startActivity() }
        }
    }

    @Test fun `a page pop paused at onStop resumes at onStart and lands once`() = runBlocking {
        withPushPages { a, p, content ->
            a.start(); a.frame(); p.start(); p.frame()
            val appearances = a.lifecycle.appearances
            val pop = p.popSliding()
            p.frameUntil { p.sliding }
            a.stopActivity()
            p.idleFor(1_000)
            assertFalse("Stopped, the pop waits", pop.isCompleted)
            a.startActivity()
            p.settleFor { pop.isCompleted }
            assertTrue(pop.await())
            assertEquals(1, a.layerCount())
            assertFalse(content.moves.isBusy)
            assertEquals("The page beneath enters once", appearances + 1uL, a.lifecycle.appearances)
        }
    }

    @Test fun `a drag held at onStop ends when the platform settles the sheet`() = runBlocking {
        withPushPages { a, p, content ->
            a.start(); a.frame(); p.start(); p.frame()
            val nested = ActivityPage(content.copy(isPushPage = false, layerContentHeight = AtomicReference(),
                screenLifecycle = ExperienceScreenLifecycle()), lower = p)
            try {
                nested.start(); nested.frame(); nested.enableSheetInput()
                nested.sheetState("STATE_DRAGGING")
                assertTrue(content.moves.isBusy)
                p.stopActivity()
                // The window leaves the screen: the system cancels the touch, and Material settles the sheet.
                nested.sheetState("STATE_SETTLING"); nested.sheetState("STATE_EXPANDED")
                assertFalse("The drag's move ended at one of its ends", content.moves.isBusy)
                assertEquals(3, nested.layerCount())
            } finally { p.startActivity(); nested.close() }
        }
    }

    @Test fun `system Back during a drag that hides the sheet runs on the page beneath once the dismissal lands`() = runBlocking {
        withPushPages { a, p, content ->
            a.start(); a.frame(); p.start(); p.frame()
            p.reduceMotion()
            val nested = ActivityPage(content.copy(isPushPage = false, layerContentHeight = AtomicReference(),
                screenLifecycle = ExperienceScreenLifecycle()), lower = p)
            try {
                nested.start(); nested.frame(); nested.enableSheetInput()
                nested.sheetState("STATE_DRAGGING")
                nested.pressBack()
                assertEquals("Back waits behind the drag", 3, nested.layerCount())
                nested.sheetState("STATE_SETTLING"); nested.sheetState("STATE_HIDDEN")
                // The sheet's dismissal closes it, and then Back closes the page that sheet covered.
                nested.settle { nested.layerCount() == 1 && !content.moves.isBusy }
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

    /** [field]: Screen b has one text field, which [ActivityPage.showField] draws. */
    private suspend fun withPushPages(field: Boolean = false,
        exercise: suspend (ActivityPage, ActivityPage, PreparedPresentation) -> Unit) {
        val scene = java.io.File.createTempFile("page-push-height", ".riv").apply { writeBytes(byteArrayOf(1)) }
        val inputs = if (!field) "" else ""","textInputs":[{"id":"name","screenId":"b","textInputName":"headline","value":"",
            "editable":true,"secureTextEntry":false,"multiline":false,
            "geometry":{"xPath":"x","yPath":"y","widthPath":"width","heightPath":"height",
              "rotationPath":"rotation","scaleXPath":"scaleX","scaleYPath":"scaleY"},
            "style":{"fontFamily":"sans-serif","fontWeight":"400","fontStyle":"normal","fontSize":16,
              "lineHeight":20,"letterSpacing":0,"color":4278190080,"fontAssetUniqueName":"font"}}]"""
        val descriptor = Json.parseToJsonElement("""{"state":{},"responses":{},"ruleGroups":[],
            "leg":{"screens":[{"id":"a","defaultViewModelName":"A"},{"id":"b","defaultViewModelName":"B"}]},
            "render":{"assets":[],"screens":[{"id":"a","artboardName":"A"},{"id":"b","artboardName":"B"}]$inputs}}""").jsonObject
        val lower = PreparedPresentation(scene, "A", 0, fitContent, "a", descriptor, pageHistory = listOf("a"),
            artboardSize = if (field) ExperienceArtboardSize(393f, 852f) else null)
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
        /** This page's one text field as the runtime draws it, or none. */
        @Volatile private var fieldText: String? = null
        @Volatile private var fieldX = 0f

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
                override fun semanticInfo(snapshot: Long) = NativeCallResult(0, longArrayOf(1, 1, if (fieldText == null) 0 else 1))
                override fun semanticNode(snapshot: Long, index: Int) = NativeCallResult(0, NativeSemanticNode(10, -1, 0,
                    NativeSemanticRole.TEXT_FIELD, 0, 0, 0, 0, fieldX, 0f, 100f, 40f, "Name", "", ""))
                override fun textInputGeometry(player: Long, snapshot: Long, nodeId: Long, name: String) = NativeCallResult(0,
                    NativeTextInputGeometry(1, floatArrayOf(1f, 0f, 0f, 1f, fieldX, 0f), floatArrayOf(0f, 0f, 100f, 40f), true,
                        floatArrayOf(1f, 0f, 0f, 1f, fieldX, 0f), floatArrayOf(0f, 0f, 100f, 40f), false, 0f, false, false))
                override fun fieldStringCopy(player: Long, snapshot: Long, nodeId: Long, name: String) =
                    NativeCallResult(0, checkNotNull(fieldText).encodeToByteArray())
                override fun fieldViewModel(player: Long, snapshot: Long, nodeId: Long, name: String) = NativeCallResult(0, FIELD_OWNER)
                override fun freeViewModel(handle: Long) = if (handle == FIELD_OWNER) 0 else native.freeViewModel(handle)
                override fun validateSemantics(player: Long, snapshot: Long) = 0
                override fun freeSemantics(snapshot: Long) = 0
                override fun acquireWindow(surface: android.view.Surface) = 5L
                override fun setPlayerLayoutSize(playerHandle: Long, width: Float, height: Float): Int {
                    size = floatArrayOf(width, height)
                    return 0
                }
                override fun playerLayoutSize(playerHandle: Long) = NativeCallResult(0, size)
                override fun snapshotViewModel(viewModelHandle: Long): NativeCallResult<NativeViewModelSnapshot> {
                    if (viewModelHandle == FIELD_OWNER) return NativeCallResult(0, NativeViewModelSnapshot(FIELD_OWNER,
                        arrayOf(NativeViewModelSnapshotInstance(FIELD_OWNER, 0)), emptyArray()))
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

        suspend fun pop(pages: Int = 1) {
            // Exercise the real close/reveal path without scheduling a native slide animator.
            set(mounted, "reduceMotionEnabled", true)
            assertTrue(activityCall { (current as PresentationScreenHandle).dismissPagesFromJourney(pages) })
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
        val accessibility: Int get() = (field(mounted, "content") as View).importantForAccessibility
        val awaitingPublication: Boolean get() = field(mounted, "awaitingSemanticPublication") as Boolean
        /** A Journey move to another Screen in this page, through the Activity's own navigation. */
        suspend fun pageMove(screenId: String): PreparedScreenNavigation {
            val target = prepared.copy(screenId = screenId, artboardName = screenId.uppercase(),
                pageHistory = prepared.pageHistory + screenId, opensLayer = false, isPushPage = false, transition = null,
                screenLifecycle = ExperienceScreenLifecycle(), retainedViewModel = AtomicReference())
            val id = "$registryId-$screenId"
            movedIds += id
            PresentationRegistry.register(id, target, {}, { throw AssertionError(it) }, {}, {})
            return activityCall { checkNotNull((current as PresentationScreenHandle).prepareNavigation(id, target)) }
        }
        suspend fun land(move: PreparedScreenNavigation) = activityCall { move.awaitExit { true } }
        /** The move takes over: the Activity hands this page to the move's Screen, which [close] then closes. */
        fun activate(move: PreparedScreenNavigation) {
            move.activate(); idle()
            current = field(layer, "screen") as ExperienceSurfaceHost.Listener
        }
        private val movedIds = mutableListOf<String>()
        fun hold(held: Boolean) { mounted.setCheckpointHold(held); idle() }
        /** The runtime draws this page's field with [text] at [x]; the next [frame] captures it. */
        fun showField(text: String, x: Float) {
            fieldText = text; fieldX = x
            // Robolectric need not lay the window out; the overlay places its editors only at a real size.
            val overlay = field(mounted, "textOverlay") as ExperienceTextInputOverlay
            if (overlay.width == 0) { overlay.layout(0, 0, 393, 852); idle() }
        }
        val fieldEditor: android.widget.EditText get() =
            (field(mounted, "textOverlay") as ExperienceTextInputOverlay).semanticViews().getValue(10) as android.widget.EditText
        suspend fun steps(): List<String> = lane.call { native.trace.filter { it.startsWith("step:") } }
        suspend fun clearTrace() = lane.call { native.trace.clear() }
        fun reduceMotion() = set(mounted, "reduceMotionEnabled", true)
        /** A debug build of the host app, where the page clock check stops the app. */
        fun debuggable() { activity.applicationInfo.flags = activity.applicationInfo.flags or android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE }
        /** This page's own exit, as a close or a Journey move plays it. */
        suspend fun exitFailure(): Throwable? = runCatching {
            activityCall { mounted.awaitExit(ExperienceScreenLifecycle.ExitReason.DISMISSED) }
        }.exceptionOrNull()
        suspend fun tracePhases() = lane.call { native.tracePhases = true; native.trace.clear() }
        /** The phases this page's rows were written, as the fake's labels: hidden, entering, active, exiting. */
        suspend fun phaseTrace(): List<String> {
            // A page a close removed has shut its lane down, so its trace is final.
            val trace = if (lane.awaitQuiescence(0)) native.trace.toList() else lane.call { native.trace.toList() }
            return trace.filter { it.startsWith("phase:") }.map { listOf("hidden", "entering", "active", "exiting")[it.substringAfterLast(':').toInt()] }
        }
        fun idle() = shadowOf(Looper.getMainLooper()).idle()
        /** Moves the main looper's clock on, which runs view animations such as a page pop's slide. */
        fun idleFor(millis: Long) = shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(millis))
        suspend fun settleFor(done: () -> Boolean) {
            withTimeout(5_000) { while (!done()) { idleFor(16); delay(1) } }
        }
        /** Draws frames until [done]. With motion on, a close's exit waits for a presented frame before its slide. */
        suspend fun frameUntil(done: () -> Boolean) {
            withTimeout(5_000) { while (!done()) { frame(); delay(1) } }
        }
        /**
         * A Journey pop of this page with its slide: the Activity's own close, motion on. It isn't a child of the
         * test's runBlocking: once the body ends nothing runs the main looper, so a pop left mid-landing would hold
         * runBlocking forever. [close] cancels it. Robolectric's Choreographer would run the whole slide inside one
         * idle, so it is paused here: the slide moves only when the test moves the clock ([idleFor]).
         */
        fun popSliding(): kotlinx.coroutines.Deferred<Boolean> {
            org.robolectric.shadows.ShadowChoreographer.setPaused(true)
            return kotlinx.coroutines.CoroutineScope(detached + kotlinx.coroutines.Dispatchers.Main.immediate)
                .async(start = CoroutineStart.UNDISPATCHED) { (current as PresentationScreenHandle).dismissPagesFromJourney(1) }
        }
        private val detached = kotlinx.coroutines.SupervisorJob()
        val sliding: Boolean get() = field(activity, "pagePopAnimation") != null
        /** The system Back button, as the Activity hears it. */
        @Suppress("DEPRECATION")
        fun pressBack() { activity.onBackPressed(); idle() }

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
            detached.cancel()
            org.robolectric.shadows.ShadowChoreographer.setPaused(false)
            screenClass.getDeclaredMethod("close", Boolean::class.javaPrimitiveType).apply { isAccessible = true }.invoke(current, forRetry)
            assertTrue(lane.awaitQuiescence(5_000))
            texture.release()
            (field(layer, "sheet") as? ExperienceNativeLayerWindow)?.retire()
            if (lower == null && !destroyed) controller.destroy()
            PresentationRegistry.dismiss(registryId, CloseReason.HostDismissed)
            movedIds.forEach { PresentationRegistry.dismiss(it, CloseReason.HostDismissed) }
        }

        companion object {
            private const val FIELD_OWNER = 4101L
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
