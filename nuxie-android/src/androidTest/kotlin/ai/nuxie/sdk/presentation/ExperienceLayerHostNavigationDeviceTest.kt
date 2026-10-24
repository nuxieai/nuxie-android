package ai.nuxie.sdk.presentation

import ai.nuxie.sdk.runtime.*
import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

/** Unsigned interim wrappers qualify Activity hosting independently of signed publisher admission. */
class ExperienceLayerHostNavigationDeviceTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private fun json(path: String) = instrumentation.context.assets.open(path).bufferedReader().use { Json.parseToJsonElement(it.readText()) }
    private fun copy(path: String, directory: File) = File(directory, path.substringAfterLast('/')).also { file ->
        instrumentation.context.assets.open(path).use { input -> file.outputStream().use(input::copyTo) }
    }
    private val host = buildJsonObject { put("artboardName", "LayerList"); put("list", "screens") }
    private fun intermediate(directory: File): PreparedPresentation {
        val entry = json("journeys/rendered-custom-transition/release-entry.json").jsonObject
        val descriptor = Json.parseToJsonElement(android.util.Base64.decode(entry.getValue("envelope").jsonObject
            .getValue("descriptorBytesBase64").jsonPrimitive.content, android.util.Base64.NO_WRAP).decodeToString()).jsonObject
        val render = descriptor.getValue("render").jsonObject
        val artifacts = render.getValue("assets").jsonArray.associate { asset ->
            val key = asset.jsonObject.getValue("key").jsonPrimitive.content
            key to copy("journeys/rendered-custom-transition/$key", directory)
        }
        val screens = descriptor.getValue("leg").jsonObject.getValue("screens").jsonArray.mapIndexed { index, screen ->
            JsonObject(screen.jsonObject + ("defaultViewModelName" to JsonPrimitive(if (index == 0) "Runtime" else "Runtime_Upsell")))
        }
        // The interim split changes the second root type, leaving the authored motion intact.
        val modified = JsonObject(descriptor + mapOf("leg" to JsonObject(descriptor.getValue("leg").jsonObject +
            ("screens" to JsonArray(screens))), "render" to JsonObject(render + ("layerHost" to host)),
            "viewModelValues" to JsonArray(emptyList())))
        return PreparedPresentation(copy("layer-host-interim-external.riv", directory), "Paywall", 0xff000000.toInt(),
            PresentationShell.FullScreen(), "screen_1", modified, artifacts, ExperienceArtboardSize(390f,844f))
    }
    private inner class Harness(val initial: PreparedPresentation, acquiring: Boolean = false) : AutoCloseable {
        var id = UUID.randomUUID().toString()
        val failures = AtomicReference<Throwable?>()
        val callbacks = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val events = CopyOnWriteArrayList<Pair<String,String>>()
        val revealedPhases = CopyOnWriteArrayList<Long?>()
        val retirements = java.util.concurrent.ConcurrentHashMap<String, CountDownLatch>()
        val monitor = Instrumentation.ActivityMonitor(NuxieExperienceActivity::class.java.name, null, false)
        var activity: Activity
            private set
        init {
            assertTrue(NuxieRuntime.shared.isAvailable)
            instrumentation.addMonitor(monitor)
            val authenticated = if (acquiring) AuthenticatedPresentationScreen::class.java.declaredConstructors.single { it.parameterCount == 5 }.let {
                it.isAccessible = true
                it.newInstance(initial.screenId, initial.artboardName, initial.artboardSize, initial.clearColor, initial.shell) as AuthenticatedPresentationScreen
            } else null
            if (authenticated != null) PresentationRegistry.registerAcquiring(id, authenticated) { failures.set(IllegalStateException("Acquisition closed: $it")) }
            val ready = if (!acquiring) register(id, initial) else null
            instrumentation.targetContext.startActivity(Intent(instrumentation.targetContext, NuxieExperienceActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK); putExtra(NuxieExperienceActivity.EXTRA_PRESENTATION_ID, id)
            })
            activity = checkNotNull(monitor.waitForActivityWithTimeout(15_000))
            if (acquiring) {
                eventually {
                    var installed = false
                    instrumentation.runOnMainSync { installed = activity.javaClass.getDeclaredField("baseWindow").apply { isAccessible = true }.get(activity) != null }
                    installed
                }
            }
            val mountedReady = ready ?: register(id, initial)
            assertTrue("Layer first frame", mountedReady.await(30, TimeUnit.SECONDS))
            checkFailure()
        }
        private fun register(id: String, prepared: PreparedPresentation): CountDownLatch {
            val ready = CountDownLatch(1)
            val retired = CountDownLatch(1).also { retirements[id] = it }
            PresentationRegistry.register(id, prepared,
                onFirstFrame = {
                    callbacks.launch {
                        try {
                            revealedPhases += mounted().surface.layerOperation {
                                it.snapshotOnLane(checkNotNull(prepared.screenId)).resolveEnumOrdinal("screen/phase")
                            }
                            PresentationRegistry.reveal(id)
                        } catch (error: Throwable) { failures.set(error) }
                        finally { ready.countDown() }
                    }
                },
                onFailure = { failures.set(it); ready.countDown() }, onDismissed = { retired.countDown() },
                onOutcome = {}, onRuntimeStep = { outcome, _, _, _, _ ->
                    outcome.events.forEach { events += checkNotNull(prepared.screenId) to it.name }
                })
            return ready
        }
        fun mounted(): ExperienceMountedScreen {
            val screen = checkNotNull(PresentationRegistry.currentScreen(id))
            return screen.javaClass.getDeclaredField("mounted").apply { isAccessible = true }.get(screen) as ExperienceMountedScreen
        }
        suspend fun navigate(prepared: PreparedPresentation): ExperienceSurfaceHost {
            val next = UUID.randomUUID().toString()
            val previous = id
            val source = checkNotNull(PresentationRegistry.currentScreen(previous))
            val navigation = withTimeout(15_000) { checkNotNull(source.prepareNavigation(next, prepared)) }
            withTimeout(15_000) { navigation.awaitExit() }
            val ready = register(next, prepared)
            if (!prepared.opensLayer) PresentationRegistry.dismiss(previous, CloseReason.JourneyNavigation)
            id = next
            navigation.activate()
            assertTrue("Incoming row activation", ready.await(15, TimeUnit.SECONDS))
            checkFailure()
            return mounted().surface
        }
        fun recreate() {
            val previous = activity
            val phases = revealedPhases.size
            val recreation = Instrumentation.ActivityMonitor(NuxieExperienceActivity::class.java.name, null, false)
            instrumentation.addMonitor(recreation)
            try {
                instrumentation.runOnMainSync { previous.recreate() }
                activity = checkNotNull(recreation.waitForActivityWithTimeout(15_000))
            } finally { instrumentation.removeMonitor(recreation) }
            assertNotSame(previous, activity)
            eventually { revealedPhases.size > phases }
            checkFailure()
        }
        fun checkFailure() { failures.get()?.let { throw AssertionError("Layer failed", it) } }
        override fun close() {
            instrumentation.runOnMainSync { PresentationRegistry.dismiss(id, CloseReason.HostDismissed); activity.finish() }
            assertTrue("Layer retirement", checkNotNull(retirements[id]).await(15, TimeUnit.SECONDS))
            instrumentation.removeMonitor(monitor)
            callbacks.cancel()
            failures.get()?.let { throw AssertionError("Layer failed", it) }
        }
    }
    private fun directory() = File(instrumentation.targetContext.cacheDir, "layer-${UUID.randomUUID()}").apply { mkdirs() }
    private val pair = Json.parseToJsonElement("""{"type":"magic-move","transitionId":"transition.native-contract"}""").jsonObject

    @Test fun activityUsesOneSurfaceRealCompletionsRetainedRowsAndAttributedTaps() = runBlocking {
        val directory = directory()
        try {
            val a = intermediate(directory)
            Harness(a).use { harness ->
                val surface = harness.mounted().surface
                val cached = surface.layerOperation { it.snapshotOnLane("screen_1") }
                val root = cached.nativeRootInstanceId
                assertEquals(1L, harness.revealedPhases.first())
                val player = surface.layerOperation { it.player }
                surface.layerOperation { it.applyJourneyValuesOnLane("screen_1", mapOf("keptValue" to NuxieViewModelScalarValue.NumberValue(42.0))) }
                val b = a.copy(screenId = "screen_2", artboardName = "Upsell", transition = pair,
                    artboardSize = ExperienceArtboardSize(430f,932f), retainedViewModel = AtomicReference(), screenLifecycle = ExperienceScreenLifecycle())
                assertSame(surface, harness.navigate(b))
                assertEquals(listOf("screen_2"), surface.layerOperation { it.mountedScreenIdsOnLane() })
                assertSame(player, surface.layerOperation { it.player })
                assertTrue(harness.events.contains("screen_1" to "nx_exit_done:transition.native-contract"))
                assertTrue(harness.events.contains("screen_2" to "nx_enter_done:transition.native-contract"))
                val now = SystemClock.uptimeMillis()
                instrumentation.runOnMainSync {
                    val density = surface.resources.displayMetrics.density
                    for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
                        val event = MotionEvent.obtain(now, now, action, 60f*density, 40f*density, 0)
                        surface.onTouchEvent(event); event.recycle()
                    }
                }
                eventually { harness.events.contains("screen_2" to "Nuxie Interaction") }
                assertFalse(harness.events.contains("screen_1" to "Nuxie Interaction"))
                a.retainedViewModel.set(cached)
                assertSame(surface, harness.navigate(a.copy(transition = pair, isBack = true)))
                val returned = surface.layerOperation { it.snapshotOnLane("screen_1") }
                assertEquals(root, returned.nativeRootInstanceId)
                assertEquals(42f, (returned.resolveScalar(listOf("keptValue")) as NuxieViewModelScalarValue.NumberValue).value.toFloat(), 0.01f)
                assertTrue(harness.events.contains("screen_2" to "nx_exit_done:transition.native-contract.reverse"))
                assertTrue(harness.events.contains("screen_1" to "nx_enter_done:transition.native-contract.reverse"))
            }
        } finally { directory.deleteRecursively() }
    }

    @Test fun nativeBackDuringForwardStepWaitsThenClosesTheExperience() = runBlocking {
        val directory = directory()
        try {
            val a = intermediate(directory).copy(pageHistory = listOf("screen_1"))
            Harness(a).use { active ->
                val surface = active.mounted().surface
                val forward = async(Dispatchers.Default) {
                    active.navigate(a.copy(screenId = "screen_2", artboardName = "Upsell", transition = pair,
                        pageHistory = listOf("screen_1", "screen_2"),
                        retainedViewModel = AtomicReference(), screenLifecycle = ExperienceScreenLifecycle()))
                }
                eventually {
                    var blocked = false
                    instrumentation.runOnMainSync { blocked = !surface.isEnabled }
                    blocked
                }
                instrumentation.runOnMainSync {
                    @Suppress("DEPRECATION")
                    active.activity.onBackPressed()
                }
                assertFalse(active.activity.isFinishing)
                assertSame(surface, forward.await())
                eventually { active.activity.isFinishing }
                assertFalse(active.events.any { it.second.endsWith(".reverse") })
            }
        } finally { directory.deleteRecursively() }
    }

    @Test fun acquisitionSheetBackDispatcherClosesDismissibleRootAndKeepsLockedRootOnB() = acquisitionSheetBack(platform = false)

    @Test fun acquisitionSheetPlatformBackClosesDismissibleRootAndKeepsLockedRootOnB() {
        org.junit.Assume.assumeTrue(android.os.Build.VERSION.SDK_INT >= 33)
        acquisitionSheetBack(platform = true)
    }

    private fun acquisitionSheetBack(platform: Boolean) = runBlocking {
        for (dismissible in listOf(true, false)) {
            val directory = directory()
            try {
                val a = intermediate(directory).copy(shell = PresentationShell.Sheet(PresentationShell.Sheet.Detent.LARGE, dismissible),
                    pageHistory = listOf("screen_1"))
                Harness(a, acquiring = true).use { active ->
                    val surface = active.mounted().surface
                    active.navigate(a.copy(screenId = "screen_2", artboardName = "Upsell", transition = pair,
                        pageHistory = listOf("screen_1", "screen_2"),
                        retainedViewModel = AtomicReference(), screenLifecycle = ExperienceScreenLifecycle()))
                    lateinit var dialog: com.google.android.material.bottomsheet.BottomSheetDialog
                    val backStack = AtomicReference<List<StackTraceElement>>()
                    instrumentation.runOnMainSync {
                        val window = active.activity.javaClass.getDeclaredField("baseWindow").apply { isAccessible = true }.get(active.activity)!!
                        dialog = (window as ExperienceNativeLayerWindow).dialog as com.google.android.material.bottomsheet.BottomSheetDialog
                        val handler = window.javaClass.getDeclaredField("backHandler").apply { isAccessible = true }
                        @Suppress("UNCHECKED_CAST")
                        val original = handler.get(window) as () -> Unit
                        // Observe the registered handler without re-registering it above Material.
                        handler.set(window, {
                            backStack.set(Throwable().stackTrace.toList())
                            original()
                        })
                        assertTrue(dialog.isShowing)
                        // Disable the SDK's key fallback so only dispatcher routing can pass.
                        dialog.setOnKeyListener { _, code, _ -> code == android.view.KeyEvent.KEYCODE_BACK }
                        assertEquals(dismissible, dialog.behavior.isHideable)
                        if (dismissible && android.os.Build.VERSION.SDK_INT >= 33) {
                            val orchestrator = dialog.javaClass.getDeclaredField("backOrchestrator").apply { isAccessible = true }.get(dialog)!!
                            val delegate = orchestrator.javaClass.getDeclaredField("backCallbackDelegate").apply { isAccessible = true }.get(orchestrator)!!
                            val callbackOwner = if (android.os.Build.VERSION.SDK_INT >= 34) delegate.javaClass.superclass!! else delegate.javaClass
                            val registered = callbackOwner.getDeclaredField("onBackInvokedCallback").apply { isAccessible = true }.get(delegate)
                            assertNotNull("Material's competing callback must be registered after restoring input", registered)
                        }
                        if (!platform) dialog.onBackPressedDispatcher.onBackPressed()
                    }
                    if (platform) {
                        instrumentation.waitForIdleSync()
                        assertTrue(instrumentation.uiAutomation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK))
                    }
                    eventually { backStack.get() != null }
                    if (dismissible) eventually { active.activity.isFinishing }
                    val stack = checkNotNull(backStack.get())
                    if (platform) {
                        // API 36's ViewRootImpl selects WindowOnBackInvokedDispatcher.getTopCallback()
                        // before invoking it. Gesture dispatch instead uses the framework wrapper.
                        assertTrue("System Back must invoke the registered OnBackInvokedCallback (dismissible=$dismissible)",
                            stack.any { it.methodName == "onBackInvoked" && it.className.startsWith(ExperienceNativeLayerWindow::class.java.name) })
                        assertTrue("The native callback must come from framework Back dispatch",
                            stack.any { it.className.startsWith("android.view.ViewRootImpl") ||
                                it.className.startsWith("android.window.WindowOnBackInvokedDispatcher") })
                    } else assertTrue("Back must arrive through OnBackPressedDispatcher, with the key fallback blocked",
                        stack.any { it.className == "androidx.activity.OnBackPressedDispatcher" })
                    if (!dismissible) {
                        assertSame(surface, active.mounted().surface)
                        assertEquals("screen_2", PresentationRegistry.resolve(active.id)?.screenId)
                        assertFalse(active.activity.isFinishing)
                        instrumentation.runOnMainSync { assertTrue("Locked root stays on B", dialog.isShowing) }
                    }
                }
            } finally { directory.deleteRecursively() }
        }
    }

    @Test fun queuedBackClosesTheExperienceWhenNavigationAborts() = runBlocking {
        val directory = directory()
        try {
            val a = intermediate(directory).copy(pageHistory = listOf("screen_1"))
            Harness(a).use { active ->
                val b = a.copy(screenId = "screen_2", artboardName = "Upsell", transition = pair,
                        pageHistory = listOf("screen_1", "screen_2"),
                    retainedViewModel = AtomicReference(), screenLifecycle = ExperienceScreenLifecycle())
                val surface = active.navigate(b)
                val source = checkNotNull(PresentationRegistry.currentScreen(active.id))
                val pending = withTimeout(15_000) { checkNotNull(source.prepareNavigation(UUID.randomUUID().toString(), a)) }
                instrumentation.runOnMainSync {
                    assertFalse(surface.isEnabled)
                    @Suppress("DEPRECATION")
                    active.activity.onBackPressed()
                }
                assertFalse("Back waits while navigation owns the page", active.activity.isFinishing)
                withTimeout(15_000) { pending.abort() }
                eventually { active.activity.isFinishing }
            }
        } finally { directory.deleteRecursively() }
    }

    @Test fun revisitedScreenKeepsItsLifecycleAndAuthoredValuesAcrossActivityRecreation() = runBlocking {
        val directory = directory()
        try {
            var a = intermediate(directory)
            Harness(a).use { harness ->
                val first = harness.mounted().surface
                first.layerOperation { it.applyJourneyValuesOnLane("screen_1", mapOf("keptValue" to NuxieViewModelScalarValue.NumberValue(42.0))) }
                suspend fun revisit(staleLifecycle: Boolean = false) {
                    val b = a.copy(screenId = "screen_2", artboardName = "Upsell", transition = pair,
                        retainedViewModel = AtomicReference(), screenLifecycle = ExperienceScreenLifecycle())
                    harness.navigate(b)
                    a = a.copy(transition = pair, isBack = true, screenLifecycle = if (staleLifecycle)
                        ExperienceScreenLifecycle() else a.screenLifecycle.copyForPreparation())
                    harness.navigate(a)
                }
                revisit()
                assertEquals(2L, (first.layerOperation { it.snapshotOnLane("screen_1").resolveScalar(listOf("screen", "appearances")) }
                    as NuxieViewModelScalarValue.NumberValue).value.toLong())
                for (appearances in listOf(2L, 3L)) {
                    val before = harness.mounted().surface
                    harness.recreate()
                    val restored = harness.mounted().surface
                    assertNotSame(before, restored)
                    val values = restored.layerOperation { it.snapshotOnLane("screen_1") }
                    assertEquals("Recreation must preserve the cumulative appearance count", appearances,
                        (values.resolveScalar(listOf("screen", "appearances")) as NuxieViewModelScalarValue.NumberValue).value.toLong())
                    assertEquals(42.0, (values.resolveScalar(listOf("keptValue")) as NuxieViewModelScalarValue.NumberValue).value, 0.01)
                    assertEquals(appearances.toULong(), a.screenLifecycle.appearances)
                    assertEquals(ExperienceScreenLifecycle.Phase.ACTIVE, a.screenLifecycle.phase)
                    if (appearances == 2L) revisit(staleLifecycle = true)
                }
            }
        } finally { directory.deleteRecursively() }
    }

    @Test fun rowInitiatedCaptureDeliversBothHalvesAndARemovedRowsLateAdvance() = runBlocking {
        val directory = directory()
        try {
            val a = intermediate(directory)
            Harness(a).use { harness ->
                val mounted = harness.mounted()
                val surface = mounted.surface
                val delivered = CopyOnWriteArrayList<Pair<String, String>>()
                val b = a.copy(screenId = "screen_2", artboardName = "Upsell", retainedViewModel = AtomicReference())
                withContext(Dispatchers.Main) {
                    mounted.prepareRow(b, object : ExperienceSurfaceHost.Listener {
                        override fun onFirstFrame() = Unit
                        override fun onFailure(error: ExperiencePresentationException) { throw AssertionError(error) }
                        override fun onRuntimeStep(outcome: NuxiePlayerStepOutcome, correlationId: ULong,
                            viewModelSnapshot: NuxieViewModelSnapshot?, saves: List<ExperienceResponseSaveRequest>) {
                            outcome.events.forEach { delivered += "screen_2" to it.name }
                        }
                    })
                }
                val capture = surface.javaClass.declaredMethods.single { it.name.startsWith("captureStepSnapshot-") }.apply { isAccessible = true }
                val publish = surface.javaClass.getDeclaredMethod("publishSteps").apply { isAccessible = true }
                surface.layerOperation { layer ->
                    layer.insertOnLane("screen_2", 1)
                    layer.setPhasesOnLane(listOf("screen_1" to ExperienceScreenLifecycle.Phase.EXITING,
                        "screen_2" to ExperienceScreenLifecycle.Phase.ENTERING), "transition.native-contract")
                    // This is the capture/delivery boundary used by native editing and save confirmation.
                    capture.invoke(surface, layer.player.stepTyped(elapsedSeconds = 0.0), 100L)
                    capture.invoke(surface, layer.player.stepTyped(elapsedSeconds = 0.6), 101L)
                    publish.invoke(surface)
                }
                assertEquals(1, harness.events.count { it == "screen_1" to "nx_exit_done:transition.native-contract" })
                assertEquals(1, delivered.count { it == "screen_2" to "nx_enter_done:transition.native-contract" })
                surface.removeLayerRow("screen_1")
                surface.layerOperation { layer ->
                    layer.setPhasesOnLane(listOf("screen_2" to ExperienceScreenLifecycle.Phase.EXITING), "transition.native-contract")
                    capture.invoke(surface, layer.player.stepTyped(elapsedSeconds = 0.0), 102L)
                    capture.invoke(surface, layer.player.stepTyped(elapsedSeconds = 0.6), 103L)
                    publish.invoke(surface)
                }
                assertEquals(1, delivered.count { it == "screen_2" to "nx_exit_done:transition.native-contract.reverse" })
                harness.checkFailure()
            }
        } finally { directory.deleteRecursively() }
    }

    @Test fun authoredBackCutsRecreatedCoveredPageToRetainedAThenPopsOnce() = runBlocking {
        val directory = directory()
        try {
            val a = intermediate(directory).copy(pageHistory = listOf("screen_1"))
            Harness(a).use { harness ->
                harness.mounted().surface.layerOperation {
                    it.applyJourneyValuesOnLane("screen_1", mapOf("keptValue" to NuxieViewModelScalarValue.NumberValue(42.0)))
                    a.retainedViewModel.set(it.snapshotOnLane("screen_1"))
                }
                harness.navigate(a.copy(screenId = "screen_2", artboardName = "Upsell", transition = pair,
                    pageHistory = listOf("screen_1", "screen_2"),
                    retainedViewModel = AtomicReference(), screenLifecycle = ExperienceScreenLifecycle()))
                val lowerId = harness.id
                val original = harness.mounted().surface
                var pops = 0
                harness.navigate(a.copy(opensLayer = true, isPushPage = true,
                    transition = Json.parseToJsonElement("""{"type":"push"}""").jsonObject,
                    layerAncestors = listOf(lowerId), retainedViewModel = AtomicReference(), screenLifecycle = ExperienceScreenLifecycle(),
                    closeLayer = { reveal ->
                        pops++
                        PresentationRegistry.dismiss(harness.id, CloseReason.JourneyNavigation)
                        reveal.prepare(); reveal.activate(); true
                    }))
                harness.recreate()
                val lower = checkNotNull(PresentationRegistry.currentScreen(lowerId))
                val mounted = lower.javaClass.getDeclaredField("mounted").apply { isAccessible = true }.get(lower) as ExperienceMountedScreen
                assertNotSame(original, mounted.surface)
                assertEquals(listOf("screen_2"), mounted.surface.layerOperation { it.mountedScreenIdsOnLane() })
                // The service supplies A's retained preparation, even though this player has only B.
                val restored = checkNotNull(lower.cutCoveredPage(a.copy(isBack = true)))
                PresentationRegistry.replaceCoveredContent(lowerId, restored)
                assertTrue(checkNotNull(PresentationRegistry.currentScreen(harness.id)).dismissPagesFromJourney(1))
                harness.id = lowerId
                assertEquals(1, pops)
                assertEquals(listOf("screen_1"), harness.mounted().surface.layerOperation { it.mountedScreenIdsOnLane() })
                val values = harness.mounted().surface.layerOperation { it.snapshotOnLane("screen_1") }
                assertEquals(42.0, (values.resolveScalar(listOf("keptValue")) as NuxieViewModelScalarValue.NumberValue).value, 0.01)
                harness.checkFailure()
            }
        } finally { directory.deleteRecursively() }
    }

    @Test fun modalLayerOwnsAnotherPlayerAndDismissesBackToBase() = runBlocking {
        val directory = directory()
        try {
            val a = intermediate(directory)
            Harness(a).use { harness ->
                val baseId = harness.id
                val baseHandle = checkNotNull(PresentationRegistry.currentScreen(baseId))
                val base = harness.mounted().surface
                val player = base.layerOperation { it.player }
                val top = a.copy(opensLayer = true, shell = PresentationShell.Sheet(PresentationShell.Sheet.Detent.MEDIUM, true),
                    transition = Json.parseToJsonElement("""{"type":"modal","height":"medium"}""").jsonObject,
                    layerAncestors = listOf(baseId), retainedViewModel = AtomicReference(), screenLifecycle = ExperienceScreenLifecycle(),
                    closeLayer = { reveal ->
                        PresentationRegistry.dismiss(harness.id, CloseReason.JourneyNavigation)
                        reveal.prepare(); reveal.activate(); true
                    })
                val upper = harness.navigate(top)
                assertNotSame(base, upper)
                assertNotSame(player, upper.layerOperation { it.player })
                assertTrue(checkNotNull(PresentationRegistry.currentScreen(harness.id)).dismissLayerFromJourney())
                harness.id = baseId
                instrumentation.waitForIdleSync()
                assertSame(baseHandle, PresentationRegistry.currentScreen(baseId))
                assertSame(base, harness.mounted().surface)
                assertEquals(listOf("screen_1"), base.layerOperation { it.mountedScreenIdsOnLane() })
            }
        } finally { directory.deleteRecursively() }
    }

    @Test fun activeRowEditorsWithdrawAndRevisitKeepsNativeEditingValues() = runBlocking {
        val directory = directory()
        val run = ExperienceRunValues()
        try {
            val scene = copy("runtime/layer-host-input/layer-host.riv", directory)
            val font = checkNotNull(NuxieRuntime.shared.inspectFileAssets(scene.readBytes())).single()
            val inputId = "scr_screens_sinput"
            val greetingId = "scr_screens_sgreeting"
            val descriptor = buildJsonObject {
                put("state", buildJsonObject {}); put("responses", buildJsonObject {}); put("ruleGroups", buildJsonArray {})
                put("leg", buildJsonObject { put("screens", buildJsonArray {
                    add(buildJsonObject { put("id", inputId); put("defaultViewModelName", "Runtime input scr_screens_sinput") })
                    add(buildJsonObject { put("id", greetingId); put("defaultViewModelName", "Runtime greeting scr_screens_sgreeting") })
                }) })
                put("render", buildJsonObject {
                    put("layerHost", host); put("transitions", buildJsonArray {})
                    put("textInputs", json("runtime/published-input/text-inputs.json"))
                    put("assets", buildJsonArray { add(buildJsonObject {
                        put("kind", "font"); put("authoredAssetId", font.authoredId); put("assetUniqueName", "${font.name}-${font.authoredId}")
                        put("location", "system"); put("family", "System"); put("weight", "400"); put("style", "normal"); put("required", true)
                    }) })
                    put("screens", buildJsonArray {
                        add(buildJsonObject { put("id", inputId); put("artboardName", "input") })
                        add(buildJsonObject { put("id", greetingId); put("artboardName", "greeting") })
                    })
                })
            }
            val input = PreparedPresentation(scene, "input", 0xffeeeeee.toInt(), PresentationShell.FullScreen(), inputId,
                descriptor, artboardSize = ExperienceArtboardSize(393f,852f), runValues = run)
            Harness(input).use { harness ->
                val surface = harness.mounted().surface
                var field: EditText? = null
                eventually { instrumentation.runOnMainSync { field = editors(harness.activity.window.decorView).firstOrNull { it.isShown } }; field != null }
                instrumentation.runOnMainSync { assertEquals("Ada", field!!.text.toString()); field!!.requestFocus(); field!!.setText("Grace") }
                withTimeout(15_000) {
                    while (surface.layerOperation { it.snapshotOnLane(inputId).resolveString("experience/name") } != "Grace") delay(25)
                }
                val greeting = input.copy(screenId = greetingId, artboardName = "greeting", retainedViewModel = AtomicReference(),
                    screenLifecycle = ExperienceScreenLifecycle(), textInputState = ExperienceTextInputState())
                assertSame(surface, harness.navigate(greeting))
                instrumentation.runOnMainSync { assertTrue(editors(harness.activity.window.decorView).none { it.isShown }) }
                assertSame(surface, harness.navigate(input.copy(isBack = true)))
                eventually { instrumentation.runOnMainSync { field = editors(harness.activity.window.decorView).firstOrNull { it.isShown } }; field != null }
                instrumentation.runOnMainSync { assertEquals("Grace", field!!.text.toString()) }
                val snapshot = surface.layerOperation { it.snapshotOnLane(inputId) }
                assertEquals("Grace", snapshot.resolveString("experience/name"))
                instrumentation.runOnMainSync { field!!.requestFocus(); field!!.setText("Lin") }
                withTimeout(15_000) {
                    while (surface.layerOperation { it.snapshotOnLane(inputId).resolveString("experience/name") } != "Lin") delay(25)
                }
            }
        } finally { run.retire(); directory.deleteRecursively() }
    }

    @Test fun nestedVideoPlaysThroughLayerAndStopsAfterRowRemoval() = runBlocking {
        val directory = directory()
        try {
            val inventory = json("video/inventory.json").jsonObject
            val asset = inventory.getValue("assets").jsonArray.single().jsonObject
            val media = copy("video/captions.mp4", directory)
            val key = asset.getValue("key").jsonPrimitive.content
            val render = JsonObject(inventory + mapOf("layerHost" to host, "renderer" to JsonPrimitive("nux")))
            val descriptor = buildJsonObject {
                put("state", buildJsonObject {}); put("responses", buildJsonObject {}); put("ruleGroups", buildJsonArray {})
                put("render", render)
                put("leg", buildJsonObject { put("screens", buildJsonArray {
                    add(buildJsonObject { put("id", "screen"); put("defaultViewModelName", "Runtime") })
                }) })
            }
            val prepared = PreparedPresentation(copy("runtime/layer-host-video/layer-host.riv", directory), "Video Frame",
                0xff000000.toInt(), PresentationShell.FullScreen(), "screen", descriptor, mapOf(key to media), ExperienceArtboardSize(320f,640f))
            Harness(prepared).use { harness ->
                val surface = harness.mounted().surface
                val playback = surface.javaClass.getDeclaredField("videoPlayback").apply { isAccessible = true }.get(surface) as ExperienceVideoPlayback
                withTimeout(15_000) { while (surface.layerOperation { playback.deliveredFrames } == 0L) delay(25) }
                assertEquals(1, surface.layerOperation { it.player.videos().size })
                val resized = surface.javaClass.getDeclaredField("layoutStepPending").apply { isAccessible = true }
                surface.layerOperation { layer -> layer.player.setLayoutSize(1f, 1f); resized.setBoolean(surface, true) }
                delay(250)
                val hiddenFrames = surface.layerOperation { playback.deliveredFrames }
                delay(250)
                assertEquals(hiddenFrames, surface.layerOperation { playback.deliveredFrames })
                surface.layerOperation { layer -> layer.player.setLayoutSize(320f, 640f); resized.setBoolean(surface, true) }
                withTimeout(10_000) { while (surface.layerOperation { playback.deliveredFrames } <= hiddenFrames) delay(25) }
                surface.removeLayerRow("screen")
                withTimeout(5_000) { while (surface.layerOperation { it.player.videos().isNotEmpty() }) delay(25) }
                val frames = surface.layerOperation { playback.deliveredFrames }
                delay(250)
                assertEquals(frames, surface.layerOperation { playback.deliveredFrames })
                harness.checkFailure()
            }
        } finally { directory.deleteRecursively() }
    }

    private fun editors(view: View): List<EditText> = when (view) {
        is EditText -> listOf(view)
        is ViewGroup -> (0 until view.childCount).flatMap { editors(view.getChildAt(it)) }
        else -> emptyList()
    }
    private fun eventually(condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 15_000
        while (!condition() && SystemClock.uptimeMillis() < deadline) SystemClock.sleep(25)
        assertTrue("Condition before deadline", condition())
    }
}
