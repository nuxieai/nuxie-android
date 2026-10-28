package ai.nuxie.sdk.presentation

import ai.nuxie.sdk.runtime.*
import ai.nuxie.sdk.experiences.*
import ai.nuxie.sdk.network.HttpTransport
import android.util.Base64
import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
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

/** The same hosted A/B live-move fixture and native Activity boundary as iOS. */
class ExperienceLayerHostNavigationDeviceTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private fun json(path: String) = instrumentation.context.assets.open(path).bufferedReader().use { Json.parseToJsonElement(it.readText()) }
    private fun copy(path: String, directory: File) = File(directory, path.substringAfterLast('/')).also { file ->
        instrumentation.context.assets.open(path).use { input -> file.outputStream().use(input::copyTo) }
    }
    // Same exporter contract as iOS hostedLiveMovePayload, injected explicitly after acquisition.
    private fun hostedLiveMoveFixture(directory: File): PreparedPresentation {
        assertTrue(NuxieRuntime.shared.isAvailable)
        val lifecycle = json("page-host-live-moves/lifecycle2.json").jsonObject.getValue("steps").jsonObject
        val signed = json("hosted-live-move-release.json").jsonObject
        val entry = signed.getValue("entry").jsonObject
        val locator = entry.getValue("locator").jsonObject
        // iOS authenticatedRenderedSnapshot uses this fixture's synthetic
        // compatibility requirements; real native import still uses the staged runtime.
        val envelope = entry.getValue("envelope").jsonObject
        val requirements = Json.parseToJsonElement(Base64.decode(envelope.getValue("descriptorBytesBase64").jsonPrimitive.content,
            Base64.NO_WRAP).decodeToString()).jsonObject.getValue("requirements").jsonObject
        val luau = requirements.getValue("luau").jsonObject
        val sceneFormat = requirements.getValue("sceneFormat").jsonObject
        val timezone = requirements.getValue("timezoneData").jsonObject
        val supported = JourneyReleaseSupportedRuntime(requirements.getValue("minimumSdkVersion").jsonPrimitive.content,
            setOf(requirements.getValue("runtimeRevision").jsonPrimitive.content),
            mapOf(luau.getValue("revision").jsonPrimitive.content to luau.getValue("bytecodeVersions").jsonArray.map { it.jsonPrimitive.int }.toSet()),
            sceneFormat.getValue("major").jsonPrimitive.int, sceneFormat.getValue("minor").jsonPrimitive.int,
            timezone.getValue("revision").jsonPrimitive.content, timezone.getValue("sha256").jsonPrimitive.content,
            requirements.getValue("requiredCapabilities").jsonArray.map { it.jsonPrimitive.content }.toSet())
        val release = JourneyReleaseVerifier.authenticate(entry.getValue("envelope").toString().encodeToByteArray(),
            mapOf("TEST_ONLY_DEV_KEYPAIR" to Base64.decode(signed.getValue("publicKeyBase64").jsonPrimitive.content, Base64.NO_WRAP)),
            checkNotNull(JourneyReleaseIdentity.fromJson(locator, setOf("legId"))), locator.getValue("legId").jsonPrimitive.content,
            supported, JourneyReleaseReplayPolicy.Active(0))
        val descriptor = release.descriptor
        val scene = copy("page-host-live-moves/page2.riv", directory)
        val acquired = runBlocking {
            val cache = JourneyReleaseArtifactCache(instrumentation.targetContext, HttpTransport {
                HttpTransport.Response(200, scene.readBytes(), mapOf("Content-Type" to "application/vnd.nuxie.scene"))
            }, cacheDirectory = File(directory, "acquired"))
            JourneyReleaseArtifactAcquirer(cache).acquire(release,
                JourneyReleaseDelivery("https://live-move.nuxie.test/", "https://live-move.nuxie.test/"))
        }
        try { assertArrayEquals(scene.readBytes(), acquired.sceneFile.readBytes()) } finally { acquired.close() }
        return PreparedPresentation(scene, "A", 0xff000000.toInt(),
            PresentationShell.FullScreen(), "A", descriptor, artboardSize = ExperienceArtboardSize(800f, 600f),
            pageHost = ai.nuxie.sdk.experiences.ExperiencePageHostContract.Host("Page", "screens", "Page", "run",
                mapOf("t1.reverse" to "back")),
            pageTransitionsFixture = listOf(ExperiencePageHostContract.Transition("t1", ExperiencePageHostContract.Kind.PAGE, "A", "B",
                ExperiencePageHostContract.Endpoints(lifecycle.getValue("t1").jsonObject.getValue("durationMs").jsonPrimitive.long, true, "", "nx_enter_done:t1"),
                ExperiencePageHostContract.Endpoints(lifecycle.getValue("back").jsonObject.getValue("durationMs").jsonPrimitive.long, false, "", "nx_enter_done:back"))),
            // Like hostedLiveMovePreparationCache on iOS, these exporter video slots
            // have no media artifact. This lane qualifies navigation, not video admission.
            importPageFixture = { runtime, renderer, bytes ->
                runtime.importFile(renderer, bytes, checkNotNull(runtime.inspectFileAssets(bytes)), videoEnabled = true)
            },
            initializePageFixture = { artboard ->
                val snapshot = checkNotNull(artboard.captureDefaultSnapshot()).native
                val rows = snapshot.values.single { it.ownerInstanceId == snapshot.rootInstanceId && it.name == "screens" }.listItemIds
                for (index in rows.indices.reversed()) artboard.removeDefaultListItem("screens", index)
            })
    }
    private inner class Harness(val initial: PreparedPresentation, acquiring: Boolean = false) : AutoCloseable {
        var id = UUID.randomUUID().toString()
        val failures = AtomicReference<Throwable?>()
        val callbacks = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val events = CopyOnWriteArrayList<Pair<String,String>>()
        val revealedPhases = CopyOnWriteArrayList<String>()
        val retirements = java.util.concurrent.ConcurrentHashMap<String, CountDownLatch>()
        val outcomes = java.util.concurrent.ConcurrentHashMap<String, CloseReason>()
        val monitor = Instrumentation.ActivityMonitor(NuxieExperienceActivity::class.java.name, null, false)
        lateinit var activity: Activity
            private set
        init {
            try {
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
            } catch (error: Throwable) {
                instrumentation.removeMonitor(monitor)
                instrumentation.runOnMainSync {
                    PresentationRegistry.dismiss(id, CloseReason.HostDismissed)
                    if (::activity.isInitialized) activity.finish()
                }
                callbacks.cancel()
                throw error
            }
        }
        private fun register(id: String, prepared: PreparedPresentation): CountDownLatch {
            val ready = CountDownLatch(1)
            val retired = CountDownLatch(1).also { retirements[id] = it }
            PresentationRegistry.register(id, prepared,
                onFirstFrame = {
                    callbacks.launch {
                        try {
                            val surface = mounted().surface
                            val lane = surface.javaClass.getDeclaredField("lane").apply { isAccessible = true }.get(surface) as NuxieRuntimeLane
                            // This is a read of the first presented revision. A gated
                            // mutation would wait for composition while reveal is still held.
                            revealedPhases += lane.call {
                                val page = surface.javaClass.getDeclaredField("interactiveLayer").apply { isAccessible = true }.get(surface) as ExperienceInteractivePage
                                (page.lifecycleOnLane(checkNotNull(prepared.screenId)).getValue("screen/phase") as NuxieViewModelScalarValue.StringValue).value
                            }
                            PresentationRegistry.reveal(id)
                        } catch (error: Throwable) { failures.compareAndSet(null, error) }
                        finally { ready.countDown() }
                    }
                },
                onFailure = { failures.compareAndSet(null, it); ready.countDown() }, onDismissed = { retired.countDown() },
                onOutcome = { outcomes[id] = it }, onRuntimeStep = { outcome, _, _, _, _ ->
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
        /** A rebuild Android forces closes the Experience, and the rebuilt Activity finishes (decision 174). */
        fun rebuild() {
            val previous = activity
            val rebuilt = Instrumentation.ActivityMonitor(NuxieExperienceActivity::class.java.name, null, false)
            instrumentation.addMonitor(rebuilt)
            try {
                instrumentation.runOnMainSync { previous.recreate() }
                activity = checkNotNull(rebuilt.waitForActivityWithTimeout(15_000))
            } finally { instrumentation.removeMonitor(rebuilt) }
            assertNotSame(previous, activity)
            assertTrue("Layer retirement", checkNotNull(retirements[id]).await(15, TimeUnit.SECONDS))
            eventually { activity.isFinishing || activity.isDestroyed }
            checkFailure()
        }
        fun checkFailure() { failures.get()?.let { throw AssertionError("Layer failed", it) } }
        override fun close() {
            try {
                instrumentation.runOnMainSync { PresentationRegistry.dismiss(id, CloseReason.HostDismissed); activity.finish() }
                assertTrue("Layer retirement", checkNotNull(retirements[id]).await(15, TimeUnit.SECONDS))
                checkFailure()
            } finally {
                instrumentation.removeMonitor(monitor)
                callbacks.cancel()
            }
        }
    }
    private fun directory() = File(instrumentation.targetContext.cacheDir, "layer-${UUID.randomUUID()}").apply { mkdirs() }
    private val pair = Json.parseToJsonElement("""{"type":"magic-move","transitionId":"t1"}""").jsonObject

    @Test fun activityUsesOneSurfaceRealCompletionsRetainedRowsAndAttributedTaps() = runBlocking {
        val directory = directory()
        try {
            val a = hostedLiveMoveFixture(directory).copy(pageHistory = listOf("A"))
            Harness(a).use { harness ->
                val surface = harness.mounted().surface
                val cached = surface.layerOperation { it.snapshotOnLane("A") }
                val root = cached.nativeRootInstanceId
                assertEquals("entering", harness.revealedPhases.first())
                val player = surface.layerOperation { it.player }
                surface.layerOperation { it.applyJourneyValuesOnLane("A", mapOf("offset" to NuxieViewModelScalarValue.NumberValue(42.0))) }
                val b = a.copy(screenId = "B", artboardName = "B", transition = pair,
                    pageHistory = listOf("A", "B"),
                    artboardSize = ExperienceArtboardSize(800f,600f), retainedViewModel = AtomicReference(), screenLifecycle = ExperienceScreenLifecycle())
                assertSame(surface, harness.navigate(b))
                assertEquals(listOf("A", "B"), surface.layerOperation { it.mountedScreenIdsOnLane() })
                assertSame(player, surface.layerOperation { it.player })
                assertFalse(harness.events.contains("A" to "nx_exit_done:t1"))
                assertTrue(harness.events.contains("B" to "nx_enter_done:t1"))
                a.retainedViewModel.set(surface.layerOperation { it.snapshotOnLane("A") })
                assertSame(surface, harness.navigate(a.copy(transition = pair, isBack = true)))
                val returned = surface.layerOperation { it.snapshotOnLane("A") }
                assertEquals(root, returned.nativeRootInstanceId)
                assertEquals(42f, (returned.resolveScalar(listOf("offset")) as NuxieViewModelScalarValue.NumberValue).value.toFloat(), 0.01f)
                assertFalse(harness.events.contains("B" to "nx_exit_done:back"))
                assertTrue(harness.events.contains("A" to "nx_enter_done:back"))
            }
        } finally { directory.deleteRecursively() }
    }

    @Test fun nativeBackDuringForwardStepWaitsThenClosesTheExperience() = runBlocking {
        val directory = directory()
        try {
            val a = hostedLiveMoveFixture(directory).copy(pageHistory = listOf("A"))
            Harness(a).use { active ->
                val surface = active.mounted().surface
                val forward = async(Dispatchers.Default) {
                    active.navigate(a.copy(screenId = "B", artboardName = "B", transition = pair,
                    pageHistory = listOf("A", "B"),
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
                assertFalse(active.events.any { it.second == "nx_enter_done:back" })
            }
        } finally { directory.deleteRecursively() }
    }

    @Test fun twoNativeBacksDuringForwardStepPopBothPagesAndLeaveNoBackForRootNavigation() = runBlocking {
        val directory = directory()
        try {
            val a = hostedLiveMoveFixture(directory).copy(pageHistory = listOf("A"))
            Harness(a).use { harness ->
                val rootId = harness.id
                val root = harness.mounted().surface
                val pops = CopyOnWriteArrayList<String>()
                suspend fun push(name: String, ancestors: List<String>): PreparedPresentation {
                    val lowerId = harness.id
                    val page = a.copy(opensLayer = true, isPushPage = true,
                        transition = Json.parseToJsonElement("""{"type":"push"}""").jsonObject,
                        layerAncestors = ancestors, retainedViewModel = AtomicReference(),
                        screenLifecycle = ExperienceScreenLifecycle(), closeLayer = { reveal ->
                            assertEquals("native_back", reveal.method)
                            assertEquals(1, reveal.pagesToClose)
                            PresentationRegistry.dismiss(harness.id, CloseReason.JourneyNavigation)
                            reveal.prepare()
                            harness.id = lowerId
                            reveal.activate()
                            pops += name
                            true
                        })
                    harness.navigate(page)
                    return page
                }
                push("middle", listOf(rootId))
                val middleId = harness.id
                val top = push("top", listOf(rootId, middleId))
                val surface = harness.mounted().surface
                val forward = async(Dispatchers.Default) {
                    harness.navigate(top.copy(opensLayer = false, isPushPage = false,
                        screenId = "B", artboardName = "B", transition = pair,
                    pageHistory = listOf("A", "B"),
                        retainedViewModel = AtomicReference(), screenLifecycle = ExperienceScreenLifecycle()))
                }
                eventually {
                    var blocked = false
                    instrumentation.runOnMainSync { blocked = !surface.isEnabled }
                    blocked
                }
                instrumentation.runOnMainSync {
                    @Suppress("DEPRECATION")
                    harness.activity.onBackPressed()
                    @Suppress("DEPRECATION")
                    harness.activity.onBackPressed()
                    assertTrue("Both Back presses wait for the forward step", pops.isEmpty())
                    assertFalse(harness.activity.isFinishing)
                }
                forward.await()
                eventually { pops.size == 2 }
                instrumentation.waitForIdleSync()
                assertEquals(listOf("top", "middle"), pops.toList())
                assertEquals(rootId, harness.id)
                assertSame(root, harness.mounted().surface)
                assertFalse(harness.activity.isFinishing)
                assertEquals(listOf("A"), root.layerOperation { it.mountedScreenIdsOnLane() })

                assertSame(root, harness.navigate(a.copy(screenId = "B", artboardName = "B",
                    transition = pair, pageHistory = listOf("A", "B"),
                    retainedViewModel = AtomicReference(), screenLifecycle = ExperienceScreenLifecycle())))
                instrumentation.waitForIdleSync()
                assertEquals(listOf("top", "middle"), pops.toList())
                assertFalse("No stale Back closes the root after later navigation", harness.activity.isFinishing)
                assertSame(root, harness.mounted().surface)
                assertNotNull(PresentationRegistry.currentScreen(harness.id))
                harness.checkFailure()
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
                val a = hostedLiveMoveFixture(directory).copy(shell = PresentationShell.Sheet(PresentationShell.Sheet.Detent.LARGE, dismissible),
                    pageHistory = listOf("A"))
                Harness(a, acquiring = true).use { active ->
                    val surface = active.mounted().surface
                    active.navigate(a.copy(screenId = "B", artboardName = "B", transition = pair,
                    pageHistory = listOf("A", "B"),
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
                        assertEquals("B", PresentationRegistry.resolve(active.id)?.screenId)
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
            val a = hostedLiveMoveFixture(directory).copy(pageHistory = listOf("A"))
            Harness(a).use { active ->
                val b = a.copy(screenId = "B", artboardName = "B", transition = pair,
                    pageHistory = listOf("A", "B"),
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

    @Test fun aRebuildClosesTheExperienceAsTheHostsClose() = runBlocking {
        val directory = directory()
        try {
            val a = hostedLiveMoveFixture(directory).copy(pageHistory = listOf("A"))
            Harness(a).use { harness ->
                harness.navigate(a.copy(screenId = "B", artboardName = "B", transition = pair,
                    pageHistory = listOf("A", "B"),
                    retainedViewModel = AtomicReference(), screenLifecycle = ExperienceScreenLifecycle()))
                val id = harness.id
                harness.rebuild()
                assertEquals(CloseReason.HostDismissed, harness.outcomes[id])
                assertNull(PresentationRegistry.currentScreen(id))
            }
        } finally { directory.deleteRecursively() }
    }

    @org.junit.Ignore("Stage C: A/B fixture has only incoming completion events; cannot prove delivery of both authored halves")
    @Test fun rowInitiatedCaptureDeliversBothHalvesAndARemovedRowsLateAdvance() = Unit

    @Test fun modalLayerOwnsAnotherPlayerAndDismissesBackToBase() = runBlocking {
        val directory = directory()
        try {
            val a = hostedLiveMoveFixture(directory).copy(pageHistory = listOf("A"))
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
                assertEquals(listOf("A"), base.layerOperation { it.mountedScreenIdsOnLane() })
            }
        } finally { directory.deleteRecursively() }
    }

    @org.junit.Ignore("Stage C: layer-host-input lacks live-move records; A/B fixture lacks the two published editable Screen declarations required by this scenario")
    @Test fun activeRowEditorsWithdrawAndRevisitKeepsNativeEditingValues() = Unit

    @org.junit.Ignore("Stage C X1: legacy per-Screen signed release is correctly refused by P8; retire this path")
    @Test fun nestedVideoPlaysThroughLayerAndStopsAfterRowRemoval() = Unit

    @org.junit.Ignore("Stage C: A/B fixture has no generated Nuxie Interaction pressable at the interim probe coordinates")
    @Test fun tapFromActiveNestedRowKeepsItsScreenOwner() = Unit

    @org.junit.Ignore("Stage C: A/B live-move fixture has no outgoing completion event or leave duration; requires a published two-half cut fixture")
    @Test fun cutWithPublishedHalvesEmitsOutgoingCompletionBeforeSwapping() = Unit

    @Test fun removedRowsLateAdvanceStillDeliversTheMountedRowsEffects() = runBlocking {
        val directory = directory()
        try {
            val a = hostedLiveMoveFixture(directory)
            Harness(a).use { harness ->
                val mounted = harness.mounted()
                val surface = mounted.surface
                val delivered = CopyOnWriteArrayList<String>()
                withContext(Dispatchers.Main) {
                    mounted.prepareRow(a.copy(screenId = "B", artboardName = "B", retainedViewModel = AtomicReference()),
                        object : ExperienceSurfaceHost.Listener {
                            override fun onFirstFrame() = Unit
                            override fun onFailure(error: ExperiencePresentationException) { harness.failures.compareAndSet(null, error) }
                            override fun onRuntimeStep(outcome: NuxiePlayerStepOutcome, correlationId: ULong,
                                viewModelSnapshot: NuxieViewModelSnapshot?, saves: List<ExperienceResponseSaveRequest>) {
                                delivered.addAll(outcome.events.map { it.name })
                            }
                        })
                }
                val capture = surface.javaClass.declaredMethods.single { it.name.startsWith("captureStepSnapshot-") }.apply { isAccessible = true }
                val publish = surface.javaClass.getDeclaredMethod("publishSteps").apply { isAccessible = true }
                surface.layerOperation { page ->
                    page.insertOnLane("B", 1)
                    page.setPhasesOnLane(listOf("A" to ExperienceScreenLifecycle.Phase.EXITING,
                        "B" to ExperienceScreenLifecycle.Phase.ENTERING), "t1")
                    page.player.stepTyped(elapsedSeconds = 0.0)
                    page.removeOnLane("A")
                    capture.invoke(surface, page.player.stepTyped(elapsedSeconds = 1.1), 101L)
                    publish.invoke(surface)
                }
                eventually { delivered.contains("nx_enter_done:t1") }
                assertEquals(1, delivered.count { it == "nx_enter_done:t1" })
                harness.checkFailure()
            }
        } finally { directory.deleteRecursively() }
    }

    private fun eventually(condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 15_000
        while (!condition() && SystemClock.uptimeMillis() < deadline) SystemClock.sleep(25)
        assertTrue("Condition before deadline", condition())
    }
}
