package ai.nuxie.sdk.presentation

import ai.nuxie.sdk.core.supportedRuntimeForEmbeddedRuntime
import ai.nuxie.sdk.events.SQLiteEventStore
import ai.nuxie.sdk.events.StoredEvent
import ai.nuxie.sdk.experiences.*
import ai.nuxie.sdk.identity.IdentityProvider
import ai.nuxie.sdk.journey.*
import ai.nuxie.sdk.network.HttpTransport
import ai.nuxie.sdk.network.ProfileDeliveryAuthority
import ai.nuxie.sdk.runtime.*
import android.app.Activity
import android.app.Instrumentation
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import android.os.SystemClock
import android.util.Base64
import android.util.Log
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

/**
 * The real published Parla Journey (fixtures/journeys/parla-sheets) through the Journey
 * executor, the presentation service and native stacked windows. Controls are found where
 * they are rendered and pressed with real touch input, so the runtime's own hit testing
 * decides which authored control fires.
 */
class PublishedParlaSheetJourneyDeviceTest {
    @Test fun nativePublishedLayoutKeepsLessonControlInsideViewport() = runBlocking {
        assertTrue("The pinned native runtime loads", NuxieRuntime.shared.isAvailable)
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val prefix = "journeys/parla-sheets/"
        fun read(path: String) = instrumentation.context.assets.open(prefix + path).use { it.readBytes() }
        val descriptor = Json.parseToJsonElement(read("descriptor.json").decodeToString()).jsonObject
        val render = descriptor.getValue("render").jsonObject
        val bytes = read(render.getValue("nux").jsonObject.getValue("key").jsonPrimitive.content)
        val directory = File(instrumentation.targetContext.cacheDir, "parla-native-${System.nanoTime()}").apply { mkdirs() }
        val artifacts = render.getValue("assets").jsonArray.associate { asset ->
            val key = asset.jsonObject.getValue("key").jsonPrimitive.content
            key to File(directory, key.substringAfterLast('/')).apply { writeBytes(read(key)) }
        }
        val runtime = NuxieRuntime.shared
        val imports = ExperienceAssetImportBuilder.build(descriptor, artifacts, checkNotNull(runtime.inspectFileAssets(bytes)))
        val failures = mutableListOf<String>()
        try {
            // The first case has the iOS fixture's exact authored dimensions and
            // integer scale. Both cases initialize layout before inserting the row.
            for ((width, height, density) in listOf(Triple(402f, 874f, 3f), Triple(1080f / 2.625f, 2400f / 2.625f, 2.625f))) {
                val lane = NuxieRuntimeLane()
                val renderer = lane.call { checkNotNull(runtime.newAndroidVulkanRenderer((width * density).toInt(), (height * density).toInt())) }
                try {
                    val file = lane.call { checkNotNull(runtime.importFile(renderer, bytes, imports.expectedAssets, imports.externalAssets)) }
                    try {
                        lane.call {
                            // Drive the same C API root/row operations without the SDK page actor.
                            val root = checkNotNull(file.newArtboard("\$nuxie.page"))
                            var player: NuxieRuntimePlayer? = null
                            var environment: NuxieRuntimeViewModelState? = null
                            var row: NuxieRuntimeViewModelState? = null
                            try {
                                root.bindDefaultViewModel("\$nuxie.Page")
                                val active = checkNotNull(root.newPlayer()).also { player = it }
                                active.requireStateMachine("\$nuxie.page")
                                val env = checkNotNull(file.newGlobalViewModel("env")).also { environment = it }
                                active.bindGlobalViewModel("env", env)
                                active.setLayoutSize(width, height)
                                for ((path, value) in mapOf("fontScale" to 1.0, "safeArea/top" to 62.0,
                                    "safeArea/bottom" to 34.0, "width" to width.toDouble(), "height" to height.toDouble()))
                                    env.setValue(path, NuxieViewModelScalarValue.NumberValue(value))
                                val screen = checkNotNull(file.newArtboard(render.getValue("screens").jsonArray.single { it.jsonObject.getValue("id").jsonPrimitive.content == LESSON }.jsonObject.getValue("artboardName").jsonPrimitive.content))
                                val schema = try { screen.defaultSchemaIndex() } finally { screen.close() }
                                val values = file.newSchemaViewModel(schema).also { row = it }
                                values.setValue("screen/phase", NuxieViewModelScalarValue.StringValue("entering"))
                                root.insertDefaultListItem("screens", 0, values)
                                active.enableSemantics()
                                active.stepTyped(elapsedSeconds = 0.0)
                                renderer.renderToCpuFrame(active, 0xff000000.toInt(), density)
                                values.setValue("screen/phase", NuxieViewModelScalarValue.StringValue("active"))
                                active.stepTyped(elapsedSeconds = 1.0)
                                renderer.renderToCpuFrame(active, 0xff000000.toInt(), density)
                                assertEquals(2L, values.snapshot().resolveEnumOrdinal("screen/phase"))
                                val capture = active.captureSemantics()
                                try {
                                    val button = capture.tree.nodes.single { it.label == "Explore Parla Plus" }
                                    val observation = "viewport=${width}x$height scale=$density button=$button"
                                    Log.i(TAG, "native layout: $observation")
                                    if (button.minX < 0 || button.minY < 0 || button.maxX > width || button.maxY > height) failures += observation
                                } finally { capture.close() }
                            } finally {
                                player?.close(); row?.close(); environment?.close(); root.close()
                            }
                        }
                    } finally { lane.call { file.close() } }
                } finally { lane.call { renderer.close() }; lane.shutdown(); assertTrue(lane.awaitQuiescence(5_000)) }
            }
            assertTrue("Native Parla control must fit without SDK windows: $failures", failures.isEmpty())
        } finally { directory.deleteRecursively() }
    }

    @Test fun backToLearningClosesSavedAndRevealsTheSameOfferOnce() = parla(large = false) { run ->
        // Lesson as drawn before any sheet opens, so a reflow when the first sheet takes focus or
        // after the last one closes shows against it.
        val lessonLayers = run.checkpoint("lesson", layers = 1)
        val lessonDrawn = run.settledContent("Lesson", run.lessonArea(lessonLayers))
        run.tapControl()
        run.expectRevealed(OFFER)
        val offerLayers = run.checkpoint("offer", layers = 2, previous = lessonLayers) { layers ->
            assertEquals(PresentationShell.Sheet(PresentationShell.Sheet.Detent.MEDIUM, true), layers[1].prepared.shell)
        }
        run.assertSheetChrome(offerLayers[1], handle = true)
        // The rows above Offer's top edge, as Lesson drew them before Offer opened.
        val lessonBaseline = lessonDrawn.copyOf(run.lessonArea(offerLayers).height())
        run.assertSameContent("Lesson under Offer", lessonBaseline, run.content(run.lessonArea(offerLayers)))
        val offerBaseline = run.content(checkNotNull(offerLayers[1].panel))
        val offerAppearances = offerLayers[1].appearances

        run.tapControl()
        run.expectRevealed(SAVED)
        val savedLayers = run.checkpoint("saved", layers = 3, previous = offerLayers) { layers ->
            assertEquals(PresentationShell.Sheet(PresentationShell.Sheet.Detent.FIT_CONTENT, true), layers[2].prepared.shell)
            assertTrue("Saved fits its content inside the space Offer leaves",
                checkNotNull(layers[2].panel).height() < checkNotNull(layers[1].panel).height())
        }
        run.assertSheetChrome(savedLayers[2], handle = true)
        assertTrue("Covering is not a hidden lifecycle edge", run.dismissed.isEmpty())
        // The covered screens keep their own layout while Saved is prepared and shown.
        val savedTop = checkNotNull(savedLayers[2].panel).top
        run.assertSameContent("Lesson under Saved", lessonBaseline, run.content(run.lessonArea(savedLayers)))
        run.assertSameContent("Offer under Saved", offerBaseline.copyOf(savedTop - checkNotNull(offerLayers[1].panel).top),
            run.content(checkNotNull(savedLayers[1].panel), bottom = savedTop))

        run.tapControl()
        run.expectRevealed(OFFER)
        assertEquals(Triple(SAVED, OFFER, "journey"), run.dismissed.poll(10, TimeUnit.SECONDS))
        assertNull("Offer is revealed exactly once", run.revealed.poll(1, TimeUnit.SECONDS))
        val revealedOffer = run.checkpoint("offer revealed", layers = 2, previous = offerLayers)
        assertSame("The same Offer screen is revealed", offerLayers[1].screen, revealedOffer[1].screen)
        assertSame(offerLayers[1].prepared, revealedOffer[1].prepared)
        run.onMain { assertFalse(checkNotNull(savedLayers[2].window).dialog.isShowing) }
        assertEquals("Offer does not re-enter", offerAppearances, revealedOffer[1].appearances, 0.0)
        run.assertSameContent("Lesson after Saved closes", lessonBaseline, run.content(run.lessonArea(revealedOffer)))
        run.assertSameContent("Offer after Saved closes", offerBaseline, run.content(checkNotNull(revealedOffer[1].panel)))

        // The revealed Offer is live again: a second Save opens Saved, and Back to learning returns.
        run.tapControl()
        run.expectRevealed(SAVED)
        run.checkpoint("saved again", layers = 3, previous = offerLayers)
        run.tapControl()
        run.expectRevealed(OFFER)
        assertEquals(Triple(SAVED, OFFER, "journey"), run.dismissed.poll(10, TimeUnit.SECONDS))
        val again = run.checkpoint("offer revealed again", layers = 2, previous = offerLayers)

        // A real drag on the medium Offer is a positive system dismissal back to Lesson.
        run.swipeDown(checkNotNull(again[1].panel))
        run.expectRevealed(LESSON)
        assertEquals(Triple(OFFER, LESSON, "native_sheet"), run.dismissed.poll(10, TimeUnit.SECONDS))
        val lessonAgain = run.checkpoint("lesson revealed", layers = 1, previous = lessonLayers)
        assertEquals(lessonLayers[0].appearances, lessonAgain[0].appearances, 0.0)
        run.assertSameContent("Lesson after the last sheet closes", lessonDrawn, run.content(run.lessonArea(lessonAgain)))
    }

    @Test fun largeClosedByNoneOfferStaysOpenAfterSwipeAndBack() = parla(large = true) { run ->
        run.tapControl()
        run.expectRevealed(OFFER)
        val offerLayers = run.checkpoint("large offer", layers = 2) { layers ->
            assertEquals(PresentationShell.Sheet(PresentationShell.Sheet.Detent.LARGE, false), layers[1].prepared.shell)
        }
        run.assertSheetChrome(offerLayers[1], handle = false)
        run.swipeDown(checkNotNull(offerLayers[1].panel))
        SystemClock.sleep(1_000)
        run.checkpoint("large offer after swipe", layers = 2, previous = offerLayers)
        run.instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        SystemClock.sleep(1_000)
        run.checkpoint("large offer after back", layers = 2, previous = offerLayers)
        assertTrue("closedby none dismisses nothing", run.dismissed.isEmpty())
        assertNull(run.revealed.poll(500, TimeUnit.MILLISECONDS))
        // Authored controls stay live on the retained large sheet.
        run.tapControl()
        run.expectRevealed(SAVED)
        run.checkpoint("saved over large offer", layers = 3, previous = offerLayers)
        run.tapControl()
        run.expectRevealed(OFFER)
        assertEquals(Triple(SAVED, OFFER, "journey"), run.dismissed.poll(10, TimeUnit.SECONDS))
        run.checkpoint("large offer revealed", layers = 2, previous = offerLayers)
    }

    internal class LayerState(
        val screen: Any,
        val prepared: PreparedPresentation,
        val window: ExperienceNativeLayerWindow?,
        /** The rendering surface's rectangle on screen. */
        val surface: Rect,
        /** The native sheet panel's rectangle on screen, when the layer is a shown sheet. */
        val panel: Rect?,
        val phase: String,
        val appearances: Double,
        /** The sheet's drag handle on screen, or null when the layer shows none. */
        val handle: Rect?,
    )

    internal class Run(
        val instrumentation: Instrumentation,
        private val activity: Activity,
        val revealed: LinkedBlockingQueue<String>,
        val dismissed: LinkedBlockingQueue<Triple<String, String?, String>>,
    ) {
        private val density = activity.resources.displayMetrics.density

        fun onMain(block: () -> Unit) = instrumentation.runOnMainSync(block)

        private fun field(owner: Any, name: String): Any? =
            owner.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(owner)

        private fun screenRect(view: View): Rect {
            val location = IntArray(2)
            view.getLocationOnScreen(location)
            return Rect(location[0], location[1], location[0] + view.width, location[1] + view.height)
        }

        fun layers(): List<LayerState> {
            var result: List<LayerState> = emptyList()
            onMain {
                result = (field(activity, "layers") as List<*>).map { layer ->
                    val screen = checkNotNull(field(checkNotNull(layer), "screen"))
                    val prepared = field(screen, "prepared") as PreparedPresentation
                    val window = field(layer, "sheet") as ExperienceNativeLayerWindow?
                    val mounted = checkNotNull(field(screen, "mounted")) as ExperienceMountedScreen
                    val panel = window?.takeIf { it.dialog.isShowing }?.dialog
                        ?.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)
                    val snapshot = prepared.screenLifecycle.snapshot()
                    LayerState(screen, prepared, window, screenRect(mounted.surface), panel?.let(::screenRect),
                        (snapshot.getValue("screen/phase") as NuxieViewModelScalarValue.StringValue).value,
                        (snapshot.getValue("screen/appearances") as NuxieViewModelScalarValue.NumberValue).value,
                        window?.dragHandle?.takeIf { panel != null && it.isShown }?.let(::screenRect))
                }
            }
            return result
        }

        /** Every layer is active, and every retained layer keeps its native geometry. */
        fun checkpoint(name: String, layers: Int, previous: List<LayerState>? = null,
            extra: (List<LayerState>) -> Unit = {}): List<LayerState> {
            instrumentation.waitForIdleSync()
            // A newly revealed top screen activates just after its reveal is reported.
            val deadline = SystemClock.uptimeMillis() + 5_000
            var current = layers()
            while (current.any { it.phase != "active" } && SystemClock.uptimeMillis() < deadline) {
                SystemClock.sleep(50)
                current = layers()
            }
            Log.i(TAG, "checkpoint $name: " + current.joinToString(" | ") {
                "${it.prepared.screenId} ${it.phase} x${it.appearances} surface=${it.surface} panel=${it.panel}"
            })
            assertEquals("$name layer count", layers, current.size)
            current.forEach { assertEquals("$name: ${it.prepared.screenId} stays active", "active", it.phase) }
            previous?.let { before ->
                for (index in 0 until minOf(before.size, current.size)) {
                    if (current[index].screen !== before[index].screen) continue
                    assertEquals("$name: ${current[index].prepared.screenId} surface", before[index].surface, current[index].surface)
                    assertEquals("$name: ${current[index].prepared.screenId} sheet", before[index].panel, current[index].panel)
                }
            }
            extra(current)
            return current
        }

        fun expectRevealed(screenId: String) {
            assertEquals(screenId, revealed.poll(30, TimeUnit.SECONDS))
        }

        /** The part of Lesson that the Offer sheet (if open) leaves uncovered, below the status bar. */
        fun lessonArea(layers: List<LayerState>): Rect {
            var statusBar = 0
            onMain {
                val insets = activity.window.decorView.rootWindowInsets
                if (insets != null && android.os.Build.VERSION.SDK_INT >= 30) {
                    statusBar = insets.getInsetsIgnoringVisibility(android.view.WindowInsets.Type.statusBars()).top
                }
            }
            val surface = layers[0].surface
            val bottom = if (layers.size > 1) checkNotNull(layers[1].panel).top else surface.bottom
            return Rect(surface.left, maxOf(surface.top, statusBar), surface.right, bottom)
        }

        private fun screenshot(): Bitmap = checkNotNull(instrumentation.uiAutomation.takeScreenshot())

        /**
         * A scrim-independent description of what is drawn in [area]: for every row, whether it
         * holds ink (pixels much darker than that row's background). A covered screen that moves
         * or reflows changes which rows hold ink; one more scrim over it does not.
         */
        fun content(area: Rect, bottom: Int = area.bottom): BooleanArray {
            // Leave out the sheet's rounded corners: what shows through them depends on which
            // window the system dims, not on the covered screen's own drawing.
            val corner = (32 * density).toInt()
            val rect = Rect(area.left + corner, area.top, area.right - corner, minOf(area.bottom, bottom))
            val deadline = SystemClock.uptimeMillis() + 5_000
            while (true) {
                val shot = screenshot()
                val rows = BooleanArray(rect.height())
                var drawn = false
                for (row in rows.indices) {
                    val y = (rect.top + row).coerceIn(0, shot.height - 1)
                    val luminance = IntArray(rect.width() / 2) { column ->
                        val pixel = shot.getPixel((rect.left + column * 2).coerceIn(0, shot.width - 1), y)
                        (Color.red(pixel) * 299 + Color.green(pixel) * 587 + Color.blue(pixel) * 114) / 1000
                    }
                    val background = luminance.sorted()[luminance.size / 2]
                    if (background > 8) drawn = true
                    rows[row] = luminance.count { it * 10 < background * 6 } >= 2
                }
                // A host GPU capture can briefly return an undrawn (black) covered surface.
                if (drawn || SystemClock.uptimeMillis() > deadline) return rows
                SystemClock.sleep(200)
            }
        }

        /**
         * [content] once two captures half a second apart agree, so a screen's own entrance
         * drawing has finished before it becomes a baseline.
         */
        fun settledContent(name: String, area: Rect): BooleanArray {
            val deadline = SystemClock.uptimeMillis() + 10_000
            var last = inkBands(content(area))
            var captures = 1
            while (true) {
                SystemClock.sleep(500)
                val next = content(area)
                captures++
                val bands = inkBands(next)
                if (!moved(last, bands) && bands.isNotEmpty()) {
                    Log.i(TAG, "$name settled after $captures captures: $bands")
                    return next
                }
                assertTrue("$name settles (last $last, now $bands)", SystemClock.uptimeMillis() < deadline)
                last = bands
            }
        }

        /** Runs of ink rows (text lines, controls), with one- and two-row gaps closed. */
        private fun inkBands(rows: BooleanArray): List<IntRange> {
            val bands = mutableListOf<IntRange>()
            var start = -1
            for (row in 0..rows.size) {
                val ink = row < rows.size && rows[row]
                if (ink && start < 0) start = row
                if (!ink && start >= 0) {
                    val last = bands.lastOrNull()
                    if (last != null && start - last.last <= 3) bands[bands.lastIndex] = last.first until row
                    else bands += start until row
                    start = -1
                }
            }
            return bands.filter { it.last > it.first }
        }

        /**
         * The same drawing, allowing another scrim's rounding to move an antialiased band edge
         * (a line's descenders) by up to 4 points. A covered screen that shifts or reflows moves
         * whole bands by far more: the status bar alone is 50 points.
         */
        fun assertSameContent(name: String, expected: BooleanArray, actual: BooleanArray) {
            val before = inkBands(expected)
            val after = inkBands(actual)
            Log.i(TAG, "$name: ${expected.size}/${actual.size} rows, bands before $before, now $after")
            assertTrue("$name: drawn content", before.isNotEmpty())
            assertEquals("$name: area", expected.size, actual.size)
            assertFalse("$name: content moved from $before to $after", moved(before, after))
        }

        private fun moved(before: List<IntRange>, after: List<IntRange>): Boolean {
            val tolerance = 4 * density
            return before.size != after.size || before.zip(after).any { (old, new) ->
                Math.abs(old.first - new.first) > tolerance || Math.abs(old.last - new.last) > tolerance
            }
        }

        /**
         * Parla's authored pressables are full-width bars filled with one source color. Headings
         * use the same color as text, which never fills half a row. Returns the center of the
         * bar drawn in [area].
         */
        private fun renderedControl(area: Rect): Pair<Float, Float> {
            val deadline = SystemClock.uptimeMillis() + 10_000
            while (true) {
                val shot = screenshot()
                val left = maxOf(area.left, 0)
                val row = IntArray(maxOf(0, minOf(area.right, shot.width) - left))
                var bandStart = -1
                var found: Pair<Float, Float>? = null
                var minX = Int.MAX_VALUE; var maxX = -1
                val bottom = minOf(area.bottom, shot.height)
                for (y in maxOf(area.top, 0)..bottom) {
                    var count = 0; var rowMin = Int.MAX_VALUE; var rowMax = -1
                    if (y < bottom) {
                        shot.getPixels(row, 0, row.size, left, y, row.size, 1)
                        for ((offset, pixel) in row.withIndex()) {
                            if (Math.abs(Color.red(pixel) - Color.red(CONTROL_FILL)) <= 6 &&
                                Math.abs(Color.green(pixel) - Color.green(CONTROL_FILL)) <= 6 &&
                                Math.abs(Color.blue(pixel) - Color.blue(CONTROL_FILL)) <= 6) {
                                count++; rowMin = minOf(rowMin, left + offset); rowMax = maxOf(rowMax, left + offset)
                            }
                        }
                    }
                    if (count * 2 >= row.size) {
                        if (bandStart < 0) bandStart = y
                        minX = minOf(minX, rowMin); maxX = maxOf(maxX, rowMax)
                    } else {
                        // The source control is 52 points tall; its rounded ends shorten the band.
                        if (bandStart >= 0 && y - bandStart >= 40 * density) found = (minX + maxX) / 2f to (bandStart + y - 1) / 2f
                        bandStart = -1
                        minX = Int.MAX_VALUE; maxX = -1
                    }
                }
                found?.let { return it }
                assertTrue("A rendered authored control in $area", SystemClock.uptimeMillis() < deadline)
                SystemClock.sleep(200)
            }
        }

        /**
         * The sheet's own chrome, drawn over its screen: rounded top corners (the pixel just inside
         * each corner shows the scrimmed layer beneath, not the screen) and, when the sheet can be
         * swiped closed, a drag handle on its top edge.
         */
        fun assertSheetChrome(layer: LayerState, handle: Boolean) {
            val panel = checkNotNull(layer.panel)
            val name = layer.prepared.screenId
            if (handle) {
                val shown = checkNotNull(layer.handle) { "$name shows a drag handle" }
                assertTrue("$name: the handle sits on the sheet's top edge", shown.top >= panel.top && shown.top < panel.top + 24 * density)
                assertEquals("$name: the handle is centred", panel.centerX().toFloat(), shown.exactCenterX(), 2f)
            } else {
                assertNull("$name: closedby none shows no drag handle", layer.handle)
            }
            fun luminance(pixel: Int) = (Color.red(pixel) * 299 + Color.green(pixel) * 587 + Color.blue(pixel) * 114) / 1000
            val inset = (2 * density).toInt()
            val deadline = SystemClock.uptimeMillis() + 5_000
            while (true) {
                val shot = screenshot()
                val inside = luminance(shot.getPixel(panel.left + panel.width() / 4, panel.top + inset))
                val corners = listOf(panel.left + inset, panel.right - 1 - inset).map { luminance(shot.getPixel(it, panel.top + inset)) }
                Log.i(TAG, "$name corners $corners inside $inside")
                if (inside > 8 && corners.all { it * 10 < inside * 8 }) return
                assertTrue("$name: top corners show the layer beneath (corners $corners, inside $inside)",
                    SystemClock.uptimeMillis() < deadline)
                SystemClock.sleep(200)
            }
        }

        /** Presses the control rendered on the top layer with a real touch. */
        fun tapControl() {
            instrumentation.waitForIdleSync()
            val top = layers().last()
            val (x, y) = renderedControl(top.panel ?: top.surface)
            Log.i(TAG, "tap ${top.prepared.screenId} at $x,$y")
            val down = SystemClock.uptimeMillis()
            inject(down, down, MotionEvent.ACTION_DOWN, x, y)
            SystemClock.sleep(60)
            inject(down, SystemClock.uptimeMillis(), MotionEvent.ACTION_UP, x, y)
        }

        /** A real finger drag down the sheet at hand speed, starting just inside its top edge. */
        fun swipeDown(panel: Rect) {
            val x = panel.exactCenterX()
            val startY = panel.top + 24 * density
            val endY = panel.bottom - 8 * density
            val down = SystemClock.uptimeMillis()
            inject(down, down, MotionEvent.ACTION_DOWN, x, startY)
            val steps = 12
            for (step in 1..steps) {
                SystemClock.sleep(16)
                inject(down, SystemClock.uptimeMillis(), MotionEvent.ACTION_MOVE, x, startY + (endY - startY) * step / steps)
            }
            inject(down, SystemClock.uptimeMillis(), MotionEvent.ACTION_UP, x, endY)
            instrumentation.waitForIdleSync()
        }

        private fun inject(down: Long, time: Long, action: Int, x: Float, y: Float) {
            val event = MotionEvent.obtain(down, time, action, x, y, 0)
            event.source = InputDevice.SOURCE_TOUCHSCREEN
            try { assertTrue(instrumentation.uiAutomation.injectInputEvent(event, true)) } finally { event.recycle() }
        }
    }

    private fun parla(large: Boolean, body: suspend (Run) -> Unit) = runBlocking {
        assertTrue("The pinned native runtime loads", NuxieRuntime.shared.isAvailable)
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val prefix = "journeys/parla-sheets/" + if (large) "variants/large/" else ""
        fun read(path: String) = instrumentation.context.assets.open(prefix + path).use { it.readBytes() }
        val entry = Json.parseToJsonElement(read("release-entry.json").decodeToString()).jsonObject
        val locator = entry.getValue("locator").jsonObject
        val keys = mapOf("TEST_ONLY_DEV_KEYPAIR" to Base64.decode("IVL40Zt5HSRFMkLhXy6rbLfP+ntqXtMAl5YOBpiB2xI=", Base64.NO_WRAP))
        val release = JourneyReleaseVerifier.authenticate(
            entry.getValue("envelope").toString().encodeToByteArray(),
            keys,
            checkNotNull(JourneyReleaseIdentity.fromJson(locator, setOf("legId"))),
            locator.getValue("legId").jsonPrimitive.content,
            checkNotNull(supportedRuntimeForEmbeddedRuntime(nuxieRuntimeSourceRevision())),
            JourneyReleaseReplayPolicy.Active(0),
        )
        // Each artifact is served with the content type its signed declaration names.
        val render = release.descriptor.getValue("render").jsonObject
        val contentTypes = (listOf(render.getValue("nux")) + render.getValue("assets").jsonArray).associate {
            it.jsonObject.getValue("key").jsonPrimitive.content to it.jsonObject.getValue("contentType").jsonPrimitive.content
        }
        val directory = File(context.cacheDir, "parla-${System.nanoTime()}").apply { mkdirs() }
        val acquirer = JourneyReleaseArtifactAcquirer(JourneyReleaseArtifactCache(context,
            HttpTransport { request ->
                val key = request.url.path.removePrefix("/")
                HttpTransport.Response(200, read(key), mapOf("Content-Type" to checkNotNull(contentTypes[key]) { "Undeclared $key" }))
            }, cacheDirectory = directory))
        val acquired = acquirer.acquire(release,
            JourneyReleaseDelivery("https://parla.nuxie.test/", "https://parla.nuxie.test/"))
        val owner = "parla-${System.nanoTime()}"
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val store = SQLiteEventStore(context, databaseFile = File(directory, "events.db"))
        val presentations = ExperiencePresentationService(context, { _, _, _ -> }, scope,
            { NuxieRuntime.shared.isAvailable }, currentDistinctId = { owner })
        val monitor = Instrumentation.ActivityMonitor(NuxieExperienceActivity::class.java.name, null, false)
        instrumentation.addMonitor(monitor)
        val revealed = LinkedBlockingQueue<String>()
        val dismissed = LinkedBlockingQueue<Triple<String, String?, String>>()
        val catalog = JourneyProfileCatalog(keys, JourneyReleaseHighWaterStore(context)) {
            checkNotNull(supportedRuntimeForEmbeddedRuntime(nuxieRuntimeSourceRevision()))
        }
        val authority = ProfileDeliveryAuthority(locator.getValue("appId").jsonPrimitive.content,
            locator.getValue("environment").jsonPrimitive.content)
        // The signed leg enters on its own authored condition (Parla: the lesson_opened event).
        val entryCondition = release.leg.getValue("entryCondition").jsonObject
        val entryEvent = entryCondition.getValue("eventName").jsonPrimitive.content
        // Only the unsigned profile wrapper is constructed here; the signed entry is unchanged.
        val profile = buildJsonObject {
            put("schemaVersion", "nuxie.journey-plane-profile.v2"); put("status", "ok")
            putJsonObject("delivery") { put("renderBaseUrl", "https://parla.nuxie.test/"); put("assetBaseUrl", "https://parla.nuxie.test/") }
            putJsonArray("features") {}
            putJsonObject("facts") { putJsonObject("properties") {}; putJsonObject("memberships") {}; putJsonObject("assignments") {} }
            put("releases", JsonArray(listOf(entry)))
            putJsonArray("armedLegs") { addJsonObject {
                putJsonObject("reference") {
                    put("experienceId", locator.getValue("experienceId")); put("versionId", locator.getValue("experienceVersionId"))
                    put("legId", locator.getValue("legId")); put("descriptorSha256", entry.getValue("envelope").jsonObject.getValue("descriptorSha256"))
                }
                putJsonObject("binding") { put("type", "new") }
                put("entryCondition", entryCondition)
                putJsonObject("context") { putJsonObject("event") {}; putJsonObject("responses") {} }
            } }
        }
        val presenter = object : JourneyPresenting {
            override fun reserve(ownerDistinctId: String) = presentations.reserveJourney(ownerDistinctId)
            override fun hasPresentedLayer(owner: JourneyPresentationOwner) = presentations.hasPresentedJourneyLayer(owner)
            override suspend fun dispatchAction(owner: JourneyPresentationOwner, action: JsonObject, effectId: String) =
                presentations.dispatchJourneyAction(owner, action, effectId)
            override fun resolveAction(owner: JourneyPresentationOwner, action: JsonObject, source: JourneyScreenEmissionSource?, eventSource: JourneyRuntimeEventSource?) =
                presentations.resolveJourneyAction(owner, action, source, eventSource)
            override fun owns(owner: JourneyPresentationOwner) = presentations.ownsJourney(owner)
            override fun screenId(owner: JourneyPresentationOwner) = presentations.journeyScreenId(owner)
            override suspend fun openLink(owner: JourneyPresentationOwner, link: JourneyLinkRequest) = presentations.openJourneyLink(owner, link)
            override suspend fun shutdownOwnedBy(ownerDistinctId: String) = presentations.shutdownOwnedBy(ownerDistinctId)
            override suspend fun shutdownPresentation(ownerDistinctId: String, journeyId: String) = presentations.shutdownJourney(ownerDistinctId, journeyId)
            override suspend fun present(value: JourneyPresentationRequest): JourneyPresentationResult {
                presentations.presentJourney(value.fences, value.release, value.screenId, value.journeyId,
                    value.ownerDistinctId, value.reservation, value.canPresent, acquire = { acquirer.acquire(value.release, value.delivery) },
                    nextBatchSequence = value.nextBatchSequence, nextEmissionSequence = value.nextEmissionSequence,
                    onScreenChanged = value.onScreenChanged, onScreenDismissed = { hidden, shown, method ->
                        Log.i(TAG, "dismissed $hidden, shown $shown, method $method")
                        dismissed.add(Triple(hidden, shown, method))
                        value.onScreenDismissed(hidden, shown, method)
                    },
                    onLinkOpened = value.onLinkOpened, onEmissionBatch = value.onEmissionBatch,
                    onPresentationRevealed = {
                        value.onPresentationRevealed(it)
                        Log.i(TAG, "revealed $it")
                        revealed.add(it)
                    },
                    onOutcome = value.onOutcome, runValues = value.runValues, transition = value.transition)
                return JourneyPresentationResult.Shown
            }
        }
        val identity = object : IdentityProvider {
            override fun distinctId() = owner
            override fun anonymousId() = owner
            override fun rawDistinctId(): String? = null
            override val isIdentified = false
        }
        val service = JourneyService(identity, store, catalog, directory, scope,
            capture = { name, properties, eventId, distinctId ->
                store.insertPendingIfAbsent(StoredEvent(eventId, name,
                    ai.nuxie.sdk.events.JsonValueConverter.fromMap(properties), System.currentTimeMillis(), distinctId))
            }, presenter = presenter,
            prepareNativeValues = { values, selected, _ -> values.lane.call {
                values.prepare(acquired.sceneFile.readBytes(), selected.descriptor, acquired.artifactsByKey); Unit
            } })
        try {
            withTimeout(30_000) {
                catalog.commit(owner, catalog.prepare(profile, authority))
                service.initialize()
                service.onAppWillEnterForeground()
                service.profileDidCommit(checkNotNull(catalog.snapshot(owner)), authority, owner, 1)
                val opened = StoredEvent("parla-$entryEvent-${System.nanoTime()}", entryEvent,
                    timestampMillis = System.currentTimeMillis(), distinctId = owner)
                store.insertPendingIfAbsent(opened)
                assertTrue("$entryEvent enters the signed leg", service.handleEvent(opened, service.eventAdmissionGeneration()))
            }
            val activity = checkNotNull(monitor.waitForActivityWithTimeout(20_000))
            val run = Run(instrumentation, activity, revealed, dismissed)
            run.expectRevealed(LESSON)
            run.checkpoint("lesson", layers = 1)
            body(run)
        } finally {
            service.profileDidClearAll()
            presentations.shutdownOwnedBy(owner)
            store.close()
            scope.cancel()
            instrumentation.removeMonitor(monitor)
            acquired.close()
            directory.deleteRecursively()
        }
    }

    private companion object {
        const val TAG = "ParlaJourney"
        const val LESSON = "scr_screens_slesson"
        const val OFFER = "scr_screens_soffer"
        const val SAVED = "scr_screens_ssaved"
        /** The source fill of every Parla pressable (#173D2F in publish-source.json). */
        val CONTROL_FILL = Color.rgb(0x17, 0x3D, 0x2F)
    }
}
