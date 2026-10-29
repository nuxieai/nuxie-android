package ai.nuxie.sdk.presentation

import ai.nuxie.sdk.LogLevel
import ai.nuxie.sdk.logging.NuxieLog
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
        /** Every dismissal the registry reports, in order: what the Journey hears. */
        val dismissals = CopyOnWriteArrayList<Pair<String, CloseReason>>()
        /** Every outcome the registry reports, in order: what the service turns into its close fact. */
        val outcomeLog = CopyOnWriteArrayList<Pair<String, CloseReason>>()
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
                onFailure = { failures.compareAndSet(null, it); ready.countDown() },
                onDismissed = { reason -> dismissals += id to reason; retired.countDown() },
                onOutcome = { outcomes[id] = it; outcomeLog += id to it }, onRuntimeStep = { outcome, _, _, _, _ ->
                    outcome.events.forEach { events += checkNotNull(prepared.screenId) to it.name }
                })
            return ready
        }
        fun mounted(): ExperienceMountedScreen {
            val screen = checkNotNull(PresentationRegistry.currentScreen(id))
            return screen.javaClass.getDeclaredField("mounted").apply { isAccessible = true }.get(screen) as ExperienceMountedScreen
        }
        suspend fun navigate(prepared: PreparedPresentation, retire: Boolean = false): ExperienceSurfaceHost {
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
            // As the service does: a move holds its slot until the Screen it replaced has shut down (section 2).
            if (retire) withTimeout(15_000) { navigation.awaitRetired() }
            return mounted().surface
        }
        /** A Journey move through the Experience's one queue, the way the service runs one (pr1-port section 2). */
        suspend fun move(prepared: PreparedPresentation): ExperienceSurfaceHost =
            initial.moves.run { navigate(prepared, retire = true) }
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

    // The page host's four device tests (pr1-port, "Tests": "Device tests for the queue, the live page under a
    // sheet, Back across sheets, and a rebuild during a move run on the emulator through the pool's device lane").

    /** One system Back press: when the Activity handed it on, when its turn came, and what the turn did. */
    private class Press(val askedAt: Long) {
        @Volatile var decidedAt = 0L
        @Volatile var wentBack = false
        @Volatile var closed = false
        val done = CompletableDeferred<Unit>()
    }

    /**
     * The move queue (pr1-port section 2, "One move queue"). The System Back table: "In-page step ... Queued. It runs
     * on the Screen the move lands on". Section 1: "During a step: a close, a dismissal, a Back or a finished sheet
     * drag waits for the step to land, then acts on the Screen it landed on". S1c: what waits completes as closed.
     *
     * A platform Back during A to B is queued: its turn starts after the step's landing frame, decides on B and
     * plays B to A. Without the queue it would decide on A, the page's first Screen, and close the Experience
     * mid-step. Then a close asked during A to B lets the step land and closes B, and a press waiting behind the
     * step completes as closed. The step log's frame times and System.nanoTime read the same monotonic clock.
     */
    @Test fun aBackDuringAStepWaitsAndRunsOnTheLandedScreenAndACloseLetsTheStepLand() { withStepLog { runBlocking {
        val directory = directory()
        try {
            val queue = ExperienceMoveQueue()
            val harnessRef = AtomicReference<Harness>()
            val presses = CopyOnWriteArrayList<Press>()
            lateinit var a: PreparedPresentation
            a = hostedLiveMoveFixture(directory).copy(pageHistory = listOf("A"), moves = queue, systemBack = {
                // The service's systemBackFromJourney: one turn holds the press table, then Back 1 by position.
                val press = Press(System.nanoTime()).also { presses += it }
                val harness = harnessRef.get()
                withContext<Unit>(Dispatchers.Default) {
                    try {
                        queue.run<Unit> {
                            press.decidedAt = System.nanoTime()
                            if (checkNotNull(PresentationRegistry.currentScreen(harness.id)).decideSystemBack()) {
                                press.wentBack = true
                                harness.navigate(a.copy(transition = pair, isBack = true), retire = true)
                            }
                        }
                    } catch (closed: ExperienceMoveQueue.Closed) {
                        press.closed = true
                    } catch (error: Throwable) {
                        harness.failures.compareAndSet(null, error)
                    } finally { press.done.complete(Unit) }
                }
            })
            fun toB() = a.copy(screenId = "B", artboardName = "B", transition = pair, pageHistory = listOf("A", "B"),
                retainedViewModel = AtomicReference(), screenLifecycle = ExperienceScreenLifecycle())
            Harness(a).use { harness ->
                harnessRef.set(harness)
                val surface = harness.mounted().surface
                val before = lastStepId()
                val forward = async(Dispatchers.Default) { harness.move(toB()) }
                val started = awaitStep(before) { it.starts("A", "B", back = false) }
                pressBack(harness.activity)
                eventually { presses.size == 1 }
                assertSame(surface, withTimeout(15_000) { forward.await() })
                withTimeout(15_000) { presses[0].done.await() }
                harness.checkFailure()
                val landed = awaitStep(before) { it.kind == "land" && it.id == started.id }
                val back = awaitStep(started.id) { it.starts("B", "A", back = true) }
                awaitStep(started.id) { it.kind == "land" && it.id == back.id }
                val press = presses[0]
                assertTrue("The press came during the step: $started, asked at ${press.askedAt}, $landed",
                    press.askedAt > started.frameNanos && press.askedAt < landed.frameNanos)
                assertTrue("Its turn came only after the step landed: ${press.decidedAt}, $landed", press.decidedAt > landed.frameNanos)
                assertTrue("It decided on B, the Screen the step landed on, and went back", press.wentBack)
                assertTrue("The Back step started after the landing: $back, $landed", back.frameNanos > landed.frameNanos)
                assertEquals("A", PresentationRegistry.resolve(harness.id)?.screenId)
                assertEquals(listOf("A"), surface.layerOperation { it.mountedScreenIdsOnLane() })
                assertFalse(harness.activity.isFinishing)

                val again = lastStepId()
                val forward2 = async(Dispatchers.Default) { harness.move(toB()) }
                val second = awaitStep(again) { it.starts("A", "B", back = false) }
                pressBack(harness.activity)
                eventually { presses.size == 2 && queue.waitingCount() == 1 }
                val askedAt = System.nanoTime()
                // The service's dismiss: the close revokes the queue, lets the started step land, then closes.
                val close = async(Dispatchers.Default) {
                    queue.closeAfterLanding()
                    val closedAt = System.nanoTime()
                    val landedOn = harness.id
                    val screen = PresentationRegistry.resolve(landedOn)?.screenId
                    withContext(Dispatchers.Main) { PresentationRegistry.dismiss(landedOn, CloseReason.UserDismissed) }
                    Triple(closedAt, landedOn, screen)
                }
                val (closedAt, landedOn, screen) = withTimeout(15_000) { close.await() }
                assertSame(surface, withTimeout(15_000) { forward2.await() })
                val secondLanded = awaitStep(again) { it.kind == "land" && it.id == second.id }
                assertTrue("The close came during the step and waited for its landing: asked $askedAt, closed $closedAt, $secondLanded",
                    askedAt < secondLanded.frameNanos && closedAt > secondLanded.frameNanos)
                assertEquals("The close acts on the Screen the step landed on", "B", screen)
                withTimeout(15_000) { presses[1].done.await() }
                assertTrue("The press waiting behind the step completes as closed", presses[1].closed)
                assertFalse(presses[1].wentBack)
                eventually { harness.activity.isFinishing && harness.outcomes[landedOn] != null }
                assertEquals(CloseReason.UserDismissed, harness.outcomes[landedOn])
                assertEquals("Nothing moved after the close", emptyList<StepLine>(), stepLines(second.id))
            }
        } finally { directory.deleteRecursively() }
    } } }

    /**
     * The live page under a sheet (pr1-port section 3): "A page under a sheet or modal stays active with input off.
     * It keeps playing and drawing, and comes back announced, without replaying its entrance." Before PR 1 the page
     * played its exit, was hidden and stopped, and replayed its entrance on return: its phase would leave active,
     * its player and its texture would stand still under the sheet, and its appearances would grow. "Announced" is
     * the content gate of "Covering a page": the covered page's content leaves the accessibility tree and comes
     * back at the first semantic publication after the reveal returns input.
     */
    @Test fun aPageUnderASheetKeepsPlayingWithInputOffAndReturnsWithoutItsEntrance() { runBlocking {
        val directory = directory()
        try {
            val a = hostedLiveMoveFixture(directory).copy(pageHistory = listOf("A"))
            Harness(a).use { harness ->
                val stack = CopyOnWriteArrayList(listOf(harness.id))
                val mounted = harness.mounted()
                val base = mounted.surface
                val content = mounted.privateField("content") as View
                until { base.row("A").phase() == "active" }
                until("The page is in the accessibility tree before the sheet") {
                    onMain { content.importantForAccessibility } == View.IMPORTANT_FOR_ACCESSIBILITY_AUTO
                }
                assertTrue(onMain { base.isEnabled })
                val appearances = base.row("A").appearances()
                val phases = CopyOnWriteArrayList<String>()
                val sampler = launch(Dispatchers.Default) { while (isActive) { phases += base.row("A").phase(); delay(5) } }
                val sheet = harness.move(harness.sheet(a, "B", stack)).also { stack += harness.id }
                assertNotSame(base, sheet)
                until("Input is off under the sheet") { !onMain { base.isEnabled } }
                assertEquals("The covered page leaves the accessibility tree",
                    View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS, onMain { content.importantForAccessibility })
                val covered = base.lastSteppedFrame()
                val drawn = base.composedFrames()
                until("The page under the sheet keeps stepping its player") { base.lastSteppedFrame() - covered >= 300_000_000L }
                until("The page under the sheet keeps drawing: its texture takes new frames") { base.composedFrames() - drawn >= 10 }
                assertTrue(base.isRunning())
                assertFalse("Input stays off while the sheet is up", onMain { base.isEnabled })
                assertEquals("Still out of the accessibility tree under the sheet",
                    View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS, onMain { content.importantForAccessibility })
                assertTrue(checkNotNull(PresentationRegistry.currentScreen(harness.id)).dismissLayerFromJourney())
                assertEquals(stack.single(), harness.id)
                until("Input returns") { onMain { base.isEnabled } }
                until("The page comes back announced: its content returns to the accessibility tree") {
                    onMain { content.importantForAccessibility } == View.IMPORTANT_FOR_ACCESSIBILITY_AUTO
                }
                val revealed = base.lastSteppedFrame()
                until("The page keeps playing after the sheet") { base.lastSteppedFrame() > revealed }
                sampler.cancelAndJoin()
                assertTrue("Sampled throughout: ${phases.size}", phases.size > 20)
                assertEquals("The page stayed active throughout", setOf("active"), phases.toSet())
                assertEquals("No entrance replayed", appearances, base.row("A").appearances(), 0.0)
                assertSame(base, harness.mounted().surface)
            }
        } finally { directory.deleteRecursively() }
    } }

    /** One look at the three pages. [topRunning] is read after [middle], so it says what was true by then. */
    private class Sample(val at: Long, val top: Map<String, NuxieViewModelScalarValue>?,
        val middle: Map<String, NuxieViewModelScalarValue>?, val topRunning: Boolean, val middleFrame: Long?,
        val base: Map<String, NuxieViewModelScalarValue>, val baseFrame: Long)

    /**
     * Back across sheets (pr1-port section 3): "Back across several sheets plays each sheet's exit, with each page
     * beneath still live." Each Screen gets a 400 ms authored exit whose done event never comes, so each exit runs
     * to its watchdog and the order can be read. Before PR 1 only the top sheet played its exit: the middle sheet's
     * phase would never read exiting. The base page under both stays active with its clock running.
     */
    @Test fun backAcrossTwoSheetsPlaysEachSheetsExitWithThePagesBeneathLive() { runBlocking {
        val directory = directory()
        try {
            val fixture = hostedLiveMoveFixture(directory)
            val a = fixture.copy(pageHistory = listOf("A"), descriptor = withScreenExits(checkNotNull(fixture.descriptor), 400))
            Harness(a).use { harness ->
                val baseId = harness.id
                val stack = CopyOnWriteArrayList(listOf(baseId))
                val base = harness.mounted().surface
                until { base.row("A").phase() == "active" }
                val appearances = base.row("A").appearances()
                val middle = harness.move(harness.sheet(a, "B", stack)).also { stack += harness.id }
                until { middle.row("B").phase() == "active" }
                val top = harness.move(harness.sheet(a, "A", stack)).also { stack += harness.id }
                until { top.row("A").phase() == "active" }
                val samples = CopyOnWriteArrayList<Sample>()
                val sampler = launch(Dispatchers.Default) {
                    while (isActive) {
                        samples += Sample(System.nanoTime(), top.rowOrNull("A"), middle.rowOrNull("B"), top.isRunning(),
                            withTimeoutOrNull(100) { runCatching { middle.lastSteppedFrame() }.getOrNull() },
                            base.row("A"), base.lastSteppedFrame())
                        delay(5)
                    }
                }
                assertTrue(checkNotNull(PresentationRegistry.currentScreen(harness.id)).dismissPagesFromJourney(2))
                sampler.cancelAndJoin()
                val topExit = samples.indexOfFirst { it.top?.phase() == "exiting" }
                val middleExit = samples.indexOfFirst { it.middle?.phase() == "exiting" }
                assertTrue("The top sheet plays its exit", topExit >= 0)
                assertEquals("dismissed", samples[topExit].top!!.exitReason())
                assertTrue("Then the middle sheet plays its own exit: top $topExit, middle $middleExit", middleExit > topExit)
                assertEquals("dismissed", samples[middleExit].middle!!.exitReason())
                // The top page is hidden, which stops its surface, only once its exit has returned. Each look at the
                // middle exiting is followed by a look at the top's surface, so a middle exit that began while the
                // top was still exiting would show the top still running.
                assertTrue("The top's exit had ended before the middle's began",
                    samples.filter { it.middle?.phase() == "exiting" }.none { it.topRunning })
                // Each page beneath stays live: its player keeps stepping through every exit above it.
                fun List<Long>.span() = (maxOrNull() ?: 0L) - (minOrNull() ?: 0L)
                val duringTop = samples.filter { it.top?.phase() == "exiting" }
                val duringMiddle = samples.filter { it.middle?.phase() == "exiting" }
                assertTrue("The middle page steps during the top's exit",
                    duringTop.mapNotNull { it.middleFrame }.span() >= 200_000_000L)
                assertTrue("The base page steps during the top's exit", duringTop.map { it.baseFrame }.span() >= 200_000_000L)
                assertTrue("The middle page steps through its own exit",
                    duringMiddle.mapNotNull { it.middleFrame }.span() >= 200_000_000L)
                assertTrue("The base page steps during the middle's exit", duringMiddle.map { it.baseFrame }.span() >= 200_000_000L)
                assertEquals("The base page stays active", setOf("active"), samples.map { it.base.phase() }.toSet())
                assertTrue("No entrance replayed", samples.all { it.base.appearances() == appearances })
                assertEquals(listOf(baseId), stack.toList())
                assertEquals(baseId, harness.id)
                assertSame(base, harness.mounted().surface)
                until("Input returns to the base page") { onMain { base.isEnabled } }
                assertFalse(harness.activity.isFinishing)
            }
        } finally { directory.deleteRecursively() }
    } }

    /**
     * A rebuild during a move (pr1-port, "A rebuild closes the Experience", decision 174): "a rebuild during an
     * in-page step after its first batch: the same [the Experience closes, one close fact with reason "host", the
     * Journey hears one dismissal, and the rebuilt Activity finishes without attaching], and a Journey move queued
     * behind it gets the closed outcome", and "none of them leaves a slot held or a span open". The onDestroy table:
     * "In-page step ... The move ends as closed. Nothing lands".
     */
    @Test fun aRebuildDuringAStepClosesTheExperienceAndTheQueuedMoveGetsTheClosedOutcome() { withStepLog { runBlocking {
        val directory = directory()
        try {
            val a = hostedLiveMoveFixture(directory).copy(pageHistory = listOf("A"))
            Harness(a).use { harness ->
                val id = harness.id
                val before = lastStepId()
                val forward = async(Dispatchers.Default) {
                    runCatching { harness.move(a.copy(screenId = "B", artboardName = "B", transition = pair,
                        pageHistory = listOf("A", "B"), retainedViewModel = AtomicReference(),
                        screenLifecycle = ExperienceScreenLifecycle())) }
                }
                val started = awaitStep(before) { it.starts("A", "B", back = false) }
                val queued = async(Dispatchers.Default) { runCatching { harness.move(a.copy(transition = pair, isBack = true)) } }
                eventually { a.moves.waitingCount() == 1 }
                harness.rebuild()
                val queuedResult = withTimeout(15_000) { queued.await() }
                assertTrue("The queued move gets the closed outcome: $queuedResult",
                    queuedResult.exceptionOrNull() is ExperienceMoveQueue.Closed)
                val forwardResult = withTimeout(30_000) { forward.await() }
                assertTrue("The started step ends without landing: $forwardResult", forwardResult.isFailure)
                assertFalse("It ended, rather than hanging until the harness gave up: $forwardResult",
                    forwardResult.exceptionOrNull() is TimeoutCancellationException)
                assertEquals("No Screen was landed on", id, harness.id)
                assertEquals("Nothing lands", emptyList<StepLine>(), stepLines(before).filter { it.kind == "land" })
                assertTrue(stepLines(before).any { it.id == started.id })
                assertFalse("No slot stays held", a.moves.isBusy)
                assertTrue(a.moves.isRevoked)
                assertEquals("No span stays open", 0, a.moves.authoredTime.openSpans())
                assertEquals("One outcome, the host's: the one the service sends as its close fact",
                    listOf(id to CloseReason.HostDismissed), harness.outcomeLog.toList())
                assertEquals("The Journey hears one dismissal", listOf(id to CloseReason.HostDismissed), harness.dismissals.toList())
                assertNull(PresentationRegistry.currentScreen(id))
                // Teardown can't undo these two: the registry observation is set only by observing an id, and the
                // content root only by mounting a Screen. Both stay unset when onCreate finishes before either.
                val (finished, observed, root) = onMain {
                    Triple(harness.activity.isFinishing || harness.activity.isDestroyed,
                        harness.activity.privateField("registryObservation"), harness.activity.privateField("contentRoot"))
                }
                assertTrue("The rebuilt Activity finishes", finished)
                assertNull("The rebuilt Activity never reads the registry", observed)
                assertNull("The rebuilt Activity never mounts a Screen", root)
            }
        } finally { directory.deleteRecursively() }
    } } }

    /**
     * A Journey sheet over the page on top. It closes the way the service closes layers: each page it closes is
     * dismissed as a Journey navigation, then the page beneath is revealed and activated.
     */
    private fun Harness.sheet(base: PreparedPresentation, screenId: String, stack: MutableList<String>): PreparedPresentation {
        val harness = this
        return base.copy(screenId = screenId, artboardName = screenId, opensLayer = true,
            shell = PresentationShell.Sheet(PresentationShell.Sheet.Detent.MEDIUM, true),
            transition = Json.parseToJsonElement("""{"type":"modal","height":"medium"}""").jsonObject,
            layerAncestors = stack.toList(), pageHistory = listOf(screenId),
            retainedViewModel = AtomicReference(), screenLifecycle = ExperienceScreenLifecycle(),
            closeLayer = { reveal ->
                repeat(reveal.pagesToClose) {
                    PresentationRegistry.dismiss(stack.removeAt(stack.lastIndex), CloseReason.JourneyNavigation)
                }
                reveal.prepare()
                harness.id = stack.last()
                reveal.activate()
                true
            })
    }

    /** The descriptor with an authored exit on every Screen: [durationMs] long, with a done event that never comes. */
    private fun withScreenExits(descriptor: JsonObject, durationMs: Long): JsonObject {
        val render = descriptor.getValue("render").jsonObject
        val exit = buildJsonObject { put("completeEventName", "never"); put("durationMs", durationMs) }
        val screens = render.getValue("screens").jsonArray.map { JsonObject(it.jsonObject + ("exit" to exit)) }
        return JsonObject(descriptor + ("render" to JsonObject(render + ("screens" to JsonArray(screens)))))
    }

    /** System Back as the platform sends it: the framework's Back dispatch from API 33, the Activity's handler before. */
    private fun pressBack(activity: Activity) {
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            assertTrue(instrumentation.uiAutomation.performGlobalAction(
                android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK))
        } else instrumentation.runOnMainSync {
            @Suppress("DEPRECATION")
            activity.onBackPressed()
        }
    }

    /** One line of the debug step log (UNIV-5724): `step start|land id=<n> ... frameNanos=<Choreographer time>`. */
    private class StepLine(val kind: String, val id: Long, val fields: Map<String, String>) {
        val frameNanos: Long get() = fields.getValue("frameNanos").toLong()
        fun starts(from: String, to: String, back: Boolean) = kind == "start" && fields["from"] == from &&
            fields["to"] == to && fields["back"] == (if (back) "1" else "0")
        override fun toString() = "step $kind id=$id $fields"
    }
    private val stepPattern = Regex("""step (start|land) (.+)""")

    /** Debug builds write the step log at DEBUG. NuxieLog's default policy is WARN, with Screen ids redacted. */
    private fun withStepLog(body: () -> Unit) {
        NuxieLog.configure(LogLevel.DEBUG, redactSensitiveData = false)
        try { body() } finally { NuxieLog.configure(LogLevel.WARN) }
    }

    /** This process's step log lines with an id above [after], as logcat holds them. */
    private fun stepLines(after: Long): List<StepLine> {
        val dump = instrumentation.uiAutomation.executeShellCommand(
            "logcat -d -v raw --pid=${android.os.Process.myPid()} -s ${ExperienceStepLog.TAG}:D")
        val text = android.os.ParcelFileDescriptor.AutoCloseInputStream(dump).bufferedReader().use { it.readText() }
        return text.lineSequence().mapNotNull { line ->
            val match = stepPattern.matchEntire(line.trim()) ?: return@mapNotNull null
            val fields = match.groupValues[2].split(' ').mapNotNull { part ->
                part.split('=', limit = 2).takeIf { it.size == 2 }?.let { it[0] to it[1] }
            }.toMap()
            StepLine(match.groupValues[1], fields.getValue("id").toLong(), fields)
        }.filter { it.id > after }.toList()
    }

    private fun lastStepId(): Long = stepLines(0).maxOfOrNull { it.id } ?: 0L

    /** Waits for the step log line [matches] picks, polling logcat. */
    private fun awaitStep(after: Long, matches: (StepLine) -> Boolean): StepLine {
        val deadline = SystemClock.uptimeMillis() + 15_000
        var lines = stepLines(after)
        while (lines.none(matches) && SystemClock.uptimeMillis() < deadline) {
            SystemClock.sleep(25)
            lines = stepLines(after)
        }
        return lines.firstOrNull(matches) ?: throw AssertionError("No such step log line after id $after: $lines")
    }

    private fun Any.privateField(name: String): Any? = javaClass.getDeclaredField(name).apply { isAccessible = true }.get(this)

    private suspend fun <T> ExperienceSurfaceHost.onLane(work: (ExperienceSurfaceHost) -> T): T =
        (privateField("lane") as NuxieRuntimeLane).call { work(this) }

    /** A page row's lifecycle values, read on the surface's lane. */
    private suspend fun ExperienceSurfaceHost.row(screenId: String): Map<String, NuxieViewModelScalarValue> =
        onLane { (it.privateField("interactiveLayer") as ExperienceInteractivePage).lifecycleOnLane(screenId) }

    /** The same, or null once the surface has gone. */
    private suspend fun ExperienceSurfaceHost.rowOrNull(screenId: String): Map<String, NuxieViewModelScalarValue>? =
        withTimeoutOrNull(100) { runCatching { row(screenId) }.getOrNull() }

    /** The page clock as the surface runs it: the Choreographer time of the last frame that stepped this page's player. */
    private suspend fun ExperienceSurfaceHost.lastSteppedFrame(): Long = onLane { it.privateField("lastFrameNanos") as Long }

    private fun ExperienceSurfaceHost.isRunning(): Boolean = privateField("running") as Boolean

    /** Frames the TextureView took from the renderer (onSurfaceTextureUpdated): what the page has drawn. */
    private fun ExperienceSurfaceHost.composedFrames(): Long = (privateField("surfaceUpdates") as java.util.concurrent.atomic.AtomicLong).get()

    private fun Map<String, NuxieViewModelScalarValue>.text(key: String) = (getValue(key) as NuxieViewModelScalarValue.StringValue).value
    private fun Map<String, NuxieViewModelScalarValue>.phase() = text("screen/phase")
    private fun Map<String, NuxieViewModelScalarValue>.exitReason() = text("screen/exitReason")
    private fun Map<String, NuxieViewModelScalarValue>.appearances() =
        (getValue("screen/appearances") as NuxieViewModelScalarValue.NumberValue).value.toDouble()

    private fun ExperienceMoveQueue.waitingCount(): Int =
        synchronized(checkNotNull(privateField("lock"))) { (privateField("waiting") as Collection<*>).size }

    private fun ExperienceAuthoredTime.openSpans(): Int =
        synchronized(checkNotNull(privateField("lock"))) { privateField("running") as Int }

    private fun <T> onMain(block: () -> T): T {
        val result = AtomicReference<Any?>()
        instrumentation.runOnMainSync { result.set(block()) }
        @Suppress("UNCHECKED_CAST")
        return result.get() as T
    }

    private suspend fun until(message: String = "Condition before deadline", condition: suspend () -> Boolean) {
        assertTrue(message, withTimeoutOrNull(15_000) { while (!condition()) delay(20); true } == true)
    }

    private fun eventually(condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 15_000
        while (!condition() && SystemClock.uptimeMillis() < deadline) SystemClock.sleep(25)
        assertTrue("Condition before deadline", condition())
    }
}
