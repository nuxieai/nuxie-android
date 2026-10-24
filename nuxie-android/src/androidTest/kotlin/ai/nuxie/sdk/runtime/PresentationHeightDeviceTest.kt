package ai.nuxie.sdk.runtime

import ai.nuxie.sdk.presentation.*
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class PresentationHeightDeviceTest {
    private fun publishedDescriptor(): JsonObject {
        val assets = InstrumentationRegistry.getInstrumentation().context.assets
        val snapshot = assets.open("runtime/published-fit-content/published/snapshot.json")
            .bufferedReader().use { Json.parseToJsonElement(it.readText()).jsonObject }
        val screens = snapshot.getValue("document").jsonObject.getValue("nodes").jsonArray
            .map { it.jsonObject }.filter { it["kind"] == JsonPrimitive("artboard") }
        return buildJsonObject {
            put("state", buildJsonObject {}); put("responses", buildJsonObject {}); put("ruleGroups", buildJsonArray {})
            put("leg", buildJsonObject { put("screens", JsonArray(screens.map { buildJsonObject { put("id", it.getValue("id")) } })) })
            put("render", buildJsonObject {
                put("assets", buildJsonArray {}); put("transitions", buildJsonArray {}); put("textInputs", buildJsonArray {})
                put("screens", JsonArray(screens.map { screen -> buildJsonObject {
                    put("id", screen.getValue("id")); put("artboardName", screen.getValue("name"))
                    put("width", 300); put("height", 700)
                } }))
            })
        }
    }

    @Test fun compilerPublishedHeightMatrixUsesSourceOracle() = runBlocking {
        val assets = InstrumentationRegistry.getInstrumentation().context.assets
        val prefix = "runtime/published-fit-content/"
        val oracle = assets.open(prefix + "expectations.json").bufferedReader().use { Json.parseToJsonElement(it.readText()).jsonObject }
        val fixedHeight = oracle.getValue("fixedHeight").jsonPrimitive.float
        val independent = oracle.getValue("independentPlayer").jsonObject
        val otherWidth = independent.getValue("width").jsonPrimitive.float
        val otherHeight = independent.getValue("height").jsonPrimitive.float
        val lane = NuxieRuntimeLane()
        try { lane.call {
            val runtime = NuxieRuntime.shared
            assertTrue(runtime.isAvailable)
            val renderer = checkNotNull(runtime.newAndroidVulkanRenderer(500, 700))
            try {
                for (control in listOf(false, true)) {
                    val bytes = assets.open(prefix + "published/" + (if (control) "no-binding.riv" else "screen.riv")).use { it.readBytes() }
                    val file = checkNotNull(runtime.importFile(renderer, bytes))
                    try {
                        for (order in oracle.getValue("orders").jsonArray) {
                            for (visit in oracle.getValue("screenVisits").jsonArray) {
                                val name = visit.jsonPrimitive.content
                                val artboard = checkNotNull(file.newArtboard(name))
                                val otherBoard = checkNotNull(file.newArtboard(name))
                                try {
                                    val player = file.newExperiencePlayer(artboard, name)
                                    val other = file.newExperiencePlayer(otherBoard, name)
                                    val height = NuxiePresentationHeight()
                                    val independentHeight = NuxiePresentationHeight()
                                    try {
                                        independentHeight.apply(file, other, false, otherWidth, otherHeight)
                                        for (widthValue in oracle.getValue("widths").jsonArray) {
                                            val width = widthValue.jsonPrimitive.float
                                            for (modeValue in order.jsonArray) {
                                                val mode = modeValue.jsonPrimitive.int
                                                height.apply(file, player, mode == 2, width, fixedHeight)
                                                player.stepTyped(elapsedSeconds = 0.0)
                                                other.stepTyped(elapsedSeconds = 0.0)
                                                val expected = when {
                                                    control -> oracle.getValue("unboundHeightForEitherMode").jsonPrimitive.float
                                                    mode == 0 -> fixedHeight
                                                    name == "sheet" -> oracle.getValue("sheetHugHeight").jsonPrimitive.float
                                                    else -> oracle.getValue("upstreamEdgeObservations").jsonObject.getValue(name).jsonPrimitive.float
                                                }
                                                assertEquals("$name, mode $mode", width to expected, player.layoutSize())
                                                assertEquals(otherWidth to otherHeight, other.layoutSize())
                                            }
                                        }
                                    } finally { height.close(); independentHeight.close(); player.close(); other.close() }
                                } finally { artboard.close(); otherBoard.close() }
                            }
                        }
                    } finally { file.close() }
                }
            } finally { renderer.close() }
        } } finally { lane.shutdown(); assertTrue(lane.awaitQuiescence(2000)) }
    }

    @Test fun publishedPageInNativeFitContentSheetUses152DpAndResizesOnLaterScreen() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val directory = java.io.File(instrumentation.targetContext.cacheDir, "published-height-${java.util.UUID.randomUUID()}").apply { mkdirs() }
        val scene = java.io.File(directory, "screen.riv").apply {
            writeBytes(instrumentation.context.assets.open("runtime/published-fit-content/published/screen.riv").use { it.readBytes() })
        }
        val oracle = instrumentation.context.assets.open("runtime/published-fit-content/expectations.json")
            .bufferedReader().use { Json.parseToJsonElement(it.readText()).jsonObject }
        val initial = PreparedPresentation(scene, "sheet", 0xff000000.toInt(),
            PresentationShell.Sheet(PresentationShell.Sheet.Detent.FIT_CONTENT, true), "scr_screens_ssheet",
            publishedDescriptor(), artboardSize = ExperienceArtboardSize(300f, 700f))
        val monitor = android.app.Instrumentation.ActivityMonitor(NuxieExperienceActivity::class.java.name, null, false)
        val failure = java.util.concurrent.atomic.AtomicReference<Throwable?>()
        val id = java.util.UUID.randomUUID().toString()
        var activeId = id
        var activity: android.app.Activity? = null
        fun register(key: String, prepared: PreparedPresentation): java.util.concurrent.CountDownLatch {
            val ready = java.util.concurrent.CountDownLatch(1)
            PresentationRegistry.register(key, prepared, onFirstFrame = {
                runBlocking { PresentationRegistry.reveal(key) }; ready.countDown()
            }, onFailure = { failure.set(it); ready.countDown() }, onDismissed = {}, onOutcome = {})
            return ready
        }
        fun awaitHeight(expected: Float) {
            val deadline = android.os.SystemClock.uptimeMillis() + 15_000
            var measured = Float.NaN
            while (android.os.SystemClock.uptimeMillis() < deadline) {
                failure.get()?.let { throw AssertionError("Published height failed", it) }
                instrumentation.runOnMainSync {
                    val host = checkNotNull(activity)
                    val window = host.javaClass.getDeclaredField("baseWindow").apply { isAccessible = true }.get(host) as ExperienceNativeLayerWindow
                    val sheet = window.dialog.findViewById<android.view.View>(com.google.android.material.R.id.design_bottom_sheet)
                    measured = sheet.height / host.resources.displayMetrics.density
                }
                if (kotlin.math.abs(measured - expected) <= 1f) break
                android.os.SystemClock.sleep(16)
            }
            assertEquals("The runtime content-height query must resize the native sheet", expected, measured, 1f)
        }
        instrumentation.addMonitor(monitor)
        try {
            val ready = register(id, initial)
            instrumentation.targetContext.startActivity(android.content.Intent(instrumentation.targetContext, NuxieExperienceActivity::class.java)
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK).putExtra(NuxieExperienceActivity.EXTRA_PRESENTATION_ID, id))
            activity = checkNotNull(monitor.waitForActivityWithTimeout(15_000))
            assertTrue("Published sheet first frame: ${failure.get()}", ready.await(30, java.util.concurrent.TimeUnit.SECONDS))
            awaitHeight(oracle.getValue("sheetHugHeight").jsonPrimitive.float)
            // This fixture has fixed children, but its later fill-child Screen has an independent 32 dp oracle.
            val next = initial.copy(screenId = "scr_screens_sfill-child", artboardName = "fill-child",
                retainedViewModel = java.util.concurrent.atomic.AtomicReference(), screenLifecycle = ExperienceScreenLifecycle())
            val nextId = java.util.UUID.randomUUID().toString()
            val navigation = runBlocking { kotlinx.coroutines.withTimeout(15_000) {
                checkNotNull(PresentationRegistry.currentScreen(id)).prepareNavigation(nextId, next)
            } }
            val nextReady = register(nextId, next)
            runBlocking { checkNotNull(navigation).awaitExit() }
            PresentationRegistry.dismiss(id, CloseReason.JourneyNavigation)
            activeId = nextId
            checkNotNull(navigation).activate()
            assertTrue(nextReady.await(15, java.util.concurrent.TimeUnit.SECONDS))
            awaitHeight(oracle.getValue("upstreamEdgeObservations").jsonObject.getValue("fill-child").jsonPrimitive.float)
        } finally {
            instrumentation.runOnMainSync { PresentationRegistry.dismiss(activeId, CloseReason.HostDismissed); activity?.finish() }
            instrumentation.removeMonitor(monitor); directory.deleteRecursively()
        }
    }

    @Test fun boundHeightRestoresFixedAndControlNeverHugs() = runBlocking {
        val lane = NuxieRuntimeLane()
        try { lane.call {
            val runtime = NuxieRuntime.shared
            assertTrue(runtime.isAvailable)
            val renderer = checkNotNull(runtime.newAndroidVulkanRenderer(500, 700))
            try {
                for (control in listOf(false, true)) {
                    val name = if (control) "root-height-no-binding" else "root-height-binding"
                    val bytes = InstrumentationRegistry.getInstrumentation().context.assets
                        .open("runtime/fit-content/$name.riv").use { it.readBytes() }
                    val file = checkNotNull(runtime.importFile(renderer, bytes))
                    try {
                        val artboard = checkNotNull(file.newArtboard("Sheet"))
                        try {
                            artboard.bindDefaultViewModel("Root")
                            val player = checkNotNull(artboard.newPlayer("Main"))
                            val height = NuxiePresentationHeight()
                            try {
                                for ((width, available, hug, expected) in listOf(
                                    listOf(300f, 600f, 0f, 600f), listOf(300f, 600f, 1f, if (control) 600f else 152f),
                                    listOf(300f, 700f, 0f, 700f), listOf(500f, 600f, 1f, if (control) 600f else 152f))) {
                                    height.apply(file, player, hug == 1f, width, available)
                                    player.stepTyped(elapsedSeconds = 0.0)
                                    assertEquals(width to expected, player.layoutSize())
                                }
                            } finally { height.close(); player.close() }
                        } finally { artboard.close() }
                    } finally { file.close() }
                }
            } finally { renderer.close() }
        } } finally { lane.shutdown(); assertTrue(lane.awaitQuiescence(2000)) }
    }
    @Test fun contentMutationWrappingAndFillUseUpstreamProofNumbers() = runBlocking {
        val lane = NuxieRuntimeLane()
        try { lane.call {
            val runtime = NuxieRuntime.shared
            assertTrue(runtime.isAvailable)
            val renderer = checkNotNull(runtime.newAndroidVulkanRenderer(500, 700))
            try {
                for ((name, expected) in listOf("dynamic-child" to 152f, "wrapping-children" to 392f,
                    "fill-child" to 32f, "tall-child" to 1232f)) {
                    val bytes = InstrumentationRegistry.getInstrumentation().context.assets
                        .open("runtime/fit-content/$name.riv").use { it.readBytes() }
                    val file = checkNotNull(runtime.importFile(renderer, bytes))
                    try {
                        val artboard = checkNotNull(file.newArtboard("Sheet"))
                        val otherArtboard = checkNotNull(file.newArtboard("Sheet"))
                        try {
                            artboard.bindDefaultViewModel("Root")
                            otherArtboard.bindDefaultViewModel("Root")
                            val player = checkNotNull(artboard.newPlayer("Main"))
                            val other = checkNotNull(otherArtboard.newPlayer("Main"))
                            val height = NuxiePresentationHeight()
                            val otherHeight = NuxiePresentationHeight()
                            try {
                                height.apply(file, player, true, 300f, 600f)
                                otherHeight.apply(file, other, false, 300f, 700f)
                                player.stepTyped(elapsedSeconds = 0.0)
                                other.stepTyped(elapsedSeconds = 0.0)
                                assertEquals(300f to expected, player.layoutSize())
                                assertEquals(300f to 700f, other.layoutSize())
                                if (name == "dynamic-child") {
                                    assertTrue(artboard.setDefaultViewModelValue("contentHeight", NuxieViewModelScalarValue.NumberValue(240.0)))
                                    player.stepTyped(elapsedSeconds = 0.0)
                                    assertEquals(300f to 272f, player.layoutSize())
                                }
                                if (name == "wrapping-children") {
                                    height.apply(file, player, true, 500f, 600f)
                                    player.stepTyped(elapsedSeconds = 0.0)
                                    assertEquals(500f to 272f, player.layoutSize())
                                }
                            } finally { height.close(); otherHeight.close(); player.close(); other.close() }
                        } finally { artboard.close(); otherArtboard.close() }
                    } finally { file.close() }
                }
            } finally { renderer.close() }
        } } finally { lane.shutdown(); assertTrue(lane.awaitQuiescence(2000)) }
    }

    @Test fun nativeSheetUsesMeasuredWidthAndCapsAtLarge() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val activity = instrumentation.startActivitySync(android.content.Intent(instrumentation.context,
            ai.nuxie.sdk.presentation.SurfaceCompatibilityHostActivity::class.java)
            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
        var window: ai.nuxie.sdk.presentation.ExperienceNativeLayerWindow? = null
        try {
            val laidOut = java.util.concurrent.CountDownLatch(1)
            var expectedWidth = 0
            instrumentation.runOnMainSync {
                val shell = ai.nuxie.sdk.presentation.PresentationShell.Sheet(
                    ai.nuxie.sdk.presentation.PresentationShell.Sheet.Detent.FIT_CONTENT, true)
                val size = ai.nuxie.sdk.presentation.ExperienceNativeLayerWindow.availableSize(activity, shell)
                expectedWidth = (size.width * activity.resources.displayMetrics.density).toInt()
                val content = android.widget.FrameLayout(activity)
                content.addOnLayoutChangeListener { _, l, t, r, b, _, _, _, _ -> if (r > l && b > t) laidOut.countDown() }
                window = ai.nuxie.sdk.presentation.ExperienceNativeLayerWindow(activity, content, shell) {}
                window!!.show()
                assertFalse(window!!.dialog.isShowing)
                window!!.updateContentHeight(152f)
            }
            assertTrue("Native sheet laid out", laidOut.await(10, java.util.concurrent.TimeUnit.SECONDS))
            instrumentation.runOnMainSync {
                val sheet = window!!.dialog.findViewById<android.view.View>(com.google.android.material.R.id.design_bottom_sheet)
                assertEquals(expectedWidth, sheet.width)
                assertEquals(kotlin.math.floor(152f * activity.resources.displayMetrics.density).toInt(), sheet.height)
                window!!.updateContentHeight(10000f)
                // A sheet never extends under the system bars: the cap is the window height
                // less the status and navigation bar insets, whether or not the bars are shown.
                val windowHeight = ai.nuxie.sdk.presentation.ExperienceNativeLayerWindow.windowBounds(activity).height()
                val insets = checkNotNull(activity.window.decorView.rootWindowInsets)
                val bars = if (android.os.Build.VERSION.SDK_INT >= 30) {
                    insets.getInsetsIgnoringVisibility(android.view.WindowInsets.Type.systemBars()).let { it.top + it.bottom }
                } else {
                    @Suppress("DEPRECATION") (insets.stableInsetTop + insets.stableInsetBottom)
                }
                assertTrue("The window reports its system bars", bars > 0)
                assertEquals(windowHeight - bars, sheet.layoutParams.height)
                window!!.retire()
                window!!.updateContentHeight(272f)
                assertFalse(window!!.dialog.isShowing)
            }
        } finally { instrumentation.runOnMainSync { window?.retire(); activity.finish() } }
    }

    @Test fun olderFileWithoutPresentationGlobalKeepsAvailableSize() = runBlocking {
        val lane = NuxieRuntimeLane()
        try { lane.call {
            val runtime = NuxieRuntime.shared
            assertTrue(runtime.isAvailable)
            val renderer = checkNotNull(runtime.newAndroidVulkanRenderer(400, 700))
            try {
                val bytes = InstrumentationRegistry.getInstrumentation().context.assets
                    .open("runtime/global-values/globals.riv").use { it.readBytes() }
                val file = checkNotNull(runtime.importFile(renderer, bytes))
                try {
                    val artboard = checkNotNull(file.newArtboard("GlobalValuesScreen"))
                    try {
                        artboard.bindDefaultViewModel("Root")
                        val player = checkNotNull(artboard.newPlayer("Main"))
                        val height = NuxiePresentationHeight()
                        try {
                            assertFalse(height.apply(file, player, true, 400f, 700f))
                            assertFalse(height.measuresContent)
                            player.stepTyped(elapsedSeconds = 0.0)
                            assertEquals(400f to 700f, player.layoutSize())
                        } finally { height.close(); player.close() }
                    } finally { artboard.close() }
                } finally { file.close() }
            } finally { renderer.close() }
        } } finally { lane.shutdown(); assertTrue(lane.awaitQuiescence(2000)) }
    }

}
