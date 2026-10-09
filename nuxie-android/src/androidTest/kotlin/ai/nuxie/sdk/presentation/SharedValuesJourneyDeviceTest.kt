package ai.nuxie.sdk.presentation

import ai.nuxie.sdk.core.supportedRuntimeForEmbeddedRuntime
import ai.nuxie.sdk.events.SQLiteEventStore
import ai.nuxie.sdk.experiences.*
import ai.nuxie.sdk.identity.IdentityProvider
import ai.nuxie.sdk.journey.*
import ai.nuxie.sdk.network.ProfileDeliveryAuthority
import ai.nuxie.sdk.runtime.*
import android.app.Instrumentation
import android.os.SystemClock
import android.util.Base64
import android.view.View
import android.view.MotionEvent
import android.view.ViewGroup
import androidx.test.platform.app.InstrumentationRegistry
import java.io.Closeable
import java.io.File
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.Signature
import java.util.UUID
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class SharedValuesJourneyDeviceTest {
    @Test fun nextPresentedScreenReadsTheRunWrite() = navigateWithValue(30.0, "long", false)

    @Test fun journeyBranchesOnThirtyNativeDays() = navigateWithValue(30.0, "long", true)

    @Test fun journeyBranchesOnSevenNativeDays() = navigateWithValue(7.0, "short", true)

    @Test fun reservedAnswerDoesNotChangeNativeBranch() = navigateWithValue(7.0, "short", true, true)

    @Test fun publishedContinueTapRoutesWithItsNewRunValue() = navigateWithValue(PublishedRunValuesFixture.expectations.getValue("tap").jsonObject
        .getValue("after").jsonPrimitive.double, "level", true, published = true)

    @Test fun publishedF5WaitedFailureThenRetryControlsItsJourney() = saveJourney(background = false)
    @Test fun publishedF5BackgroundSaveAllowsCompletionBeforeReply() = saveJourney(background = true)

    @Test fun publishedF5FormAnswerRoutesBothBranchesAndBoundary() {
        for ((days, event) in listOf(7.0 to "short_trip", 14.0 to "short_trip", 23.0 to "long_trip")) {
            saveJourney(background = true, days = days, branchEvent = event)
        }
    }

    private fun saveJourney(background: Boolean, days: Double = 30.0, branchEvent: String? = null) = runBlocking {
        assertTrue(NuxieRuntime.shared.isAvailable)
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val owner = "shared-values-${UUID.randomUUID()}"
        val directory = File(context.cacheDir, owner).apply { mkdirs() }
        val fixture = fixture(form = if (background) "departure" else "feedback", publishedFormRoutes = branchEvent != null)
        val scene = File(directory, "screen.riv").apply { writeBytes(fixture.scene) }
        val artifacts = fixture.descriptor.getValue("render").jsonObject.getValue("assets").jsonArray
            .map { it.jsonObject }.filter { it["key"] != null }.associate { asset ->
                val key = asset.getValue("key").jsonPrimitive.content
                key to File(directory, asset.getValue("sha256").jsonPrimitive.content).apply {
                    writeBytes(instrumentation.context.assets.open("runtime/forms-saves/$key").use { it.readBytes() })
                }
            }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val store = SQLiteEventStore(context, databaseFile = File(directory, "events.db"))
        val presentations = ExperiencePresentationService(context, { _, _, _ -> }, scope,
            { NuxieRuntime.shared.isAvailable }, currentDistinctId = { owner })
        val monitor = Instrumentation.ActivityMonitor(NuxieExperienceActivity::class.java.name, null, false)
        instrumentation.addMonitor(monitor)
        val request = AtomicReference<JourneyPresentationRequest?>()
        val revealed = LinkedBlockingQueue<String>()
        val captured = LinkedBlockingQueue<String>()
        val capturedProperties = LinkedBlockingQueue<Pair<String, Map<String, Any?>>>()
        val catalog = JourneyProfileCatalog(fixture.keys, JourneyReleaseHighWaterStore(context)) { fixture.supported }
        val presenter = object : JourneyPresenting {
            override fun reserve(ownerDistinctId: String) = presentations.reserveJourney(ownerDistinctId)
            override fun owns(owner: JourneyPresentationOwner) = presentations.ownsJourney(owner)
            override fun screenId(owner: JourneyPresentationOwner) = presentations.journeyScreenId(owner)
            override suspend fun openLink(owner: JourneyPresentationOwner, link: JourneyLinkRequest) = presentations.openJourneyLink(owner, link)
            override suspend fun shutdownOwnedBy(ownerDistinctId: String) = presentations.shutdownOwnedBy(ownerDistinctId)
            override suspend fun shutdownPresentation(ownerDistinctId: String, journeyId: String) = presentations.shutdownJourney(ownerDistinctId, journeyId)
            override suspend fun present(value: JourneyPresentationRequest): JourneyPresentationResult {
                request.set(value)
                checkNotNull(value.runValues).lane.call {
                    val native = checkNotNull(value.runValues).prepare(fixture.scene, value.release.descriptor, artifacts)
                    if (!background) checkNotNull(native.values).setValue("responses:feedback/stars", NuxieViewModelScalarValue.NumberValue(4.0))
                }
                presentations.presentJourney(value.fences, value.release, value.screenId, value.journeyId,
                    value.ownerDistinctId, value.reservation, value.canPresent,
                    acquire = { AcquiredJourneyRelease(value.release.identity, artifacts, scene, protection = Closeable {}) },
                    nextBatchSequence = value.nextBatchSequence, nextEmissionSequence = value.nextEmissionSequence,
                    onScreenChanged = value.onScreenChanged, onScreenDismissed = value.onScreenDismissed,
                    onLinkOpened = value.onLinkOpened, onEmissionBatch = value.onEmissionBatch,
                    onPresentationRevealed = { value.onPresentationRevealed(it); revealed.add(it) },
                    onOutcome = value.onOutcome, runValues = value.runValues, transition = value.transition)
                return JourneyPresentationResult.Shown
            }
        }
        val identity = ai.nuxie.sdk.identity.IdentityService(object : android.content.ContextWrapper(context) {
            override fun getApplicationContext(): android.content.Context = this
            override fun getFilesDir(): File = directory
        }).apply { setDistinctId(owner) }
        val pending = LinkedBlockingQueue<Pair<ai.nuxie.sdk.journey.JourneyResponseSave,
            CompletableDeferred<ai.nuxie.sdk.journey.JourneyResponseSaveReply>>>()
        val delivery = ai.nuxie.sdk.journey.JourneyResponseSaveDelivery(directory,
            ai.nuxie.sdk.journey.JourneyResponseSaveTransport { sheet ->
                val reply = CompletableDeferred<ai.nuxie.sdk.journey.JourneyResponseSaveReply>()
                pending.add(sheet to reply)
                reply.await()
            }, scope)
        val service = JourneyService(identity, store, catalog, directory, scope,
            capture = { name, properties, eventId, distinctId ->
                captured.add(name)
                capturedProperties.add(name to properties)
                store.insertPendingIfAbsent(ai.nuxie.sdk.events.StoredEvent(eventId, name,
                    ai.nuxie.sdk.events.JsonValueConverter.fromMap(properties), System.currentTimeMillis(), distinctId))
            }, dispatcher = JourneyEffectDispatcher(identity, capture = { name, properties, eventId, distinctId, admission, _ ->
                val committed = store.insertPendingIfAbsent(ai.nuxie.sdk.events.StoredEvent(eventId, name,
                    ai.nuxie.sdk.events.JsonValueConverter.fromMap(properties), System.currentTimeMillis(), distinctId), admission) == true
                if (committed) {
                    captured.add(name)
                    capturedProperties.add(name to properties)
                }
                committed
            }, deliverAppAction = { _, _ -> error("F5 does not dispatch an app action") }),
            presenter = presenter, responseSaveDelivery = delivery,
            prepareNativeValues = { values, release, _ -> values.lane.call {
                values.prepare(fixture.scene, release.descriptor, artifacts); Unit
            } })
        try {
            withTimeout(30_000) {
                catalog.commit(owner, catalog.prepare(fixture.profile, fixture.authority))
                service.initialize()
                service.onAppWillEnterForeground()
                service.profileDidCommit(checkNotNull(catalog.snapshot(owner)), fixture.authority, owner, 1)
            }
            val activity = checkNotNull(monitor.waitForActivityWithTimeout(10_000))
            assertEquals(if (background) "scr_screens_sdeparture" else "scr_screens_sfeedback", revealed.poll(20, TimeUnit.SECONDS))
            var surface: ExperienceSurfaceHost? = null
            instrumentation.runOnMainSync {
                fun find(view: View): ExperienceSurfaceHost? = when (view) {
                    is ExperienceSurfaceHost -> view.takeIf { it.isShown }
                    is ViewGroup -> (0 until view.childCount).firstNotNullOfOrNull { find(view.getChildAt(it)) }
                    else -> null
                }
                surface = find(activity.window.decorView)
            }
            val host = checkNotNull(surface)
            var point = 121f to 1f
            if (background) {
                val lane = ExperienceSurfaceHost::class.java.getDeclaredField("lane").apply { isAccessible = true }.get(host) as NuxieRuntimeLane
                val pendingFrame = ExperienceSurfaceHost::class.java.getDeclaredField("pendingPresentation").apply { isAccessible = true }
                point = withTimeout(20_000) {
                    var hit: Pair<Float, Float>? = null
                    while (hit == null) {
                        hit = lane.call {
                            if (pendingFrame.getBoolean(host)) return@call null
                            val artboard = ExperienceSurfaceHost::class.java.getDeclaredField("artboard").apply { isAccessible = true }.get(host) as NuxieRuntimeArtboard
                            val player = ExperienceSurfaceHost::class.java.getDeclaredField("player").apply { isAccessible = true }.get(host) as NuxieRuntimePlayer
                            assertTrue(artboard.setDefaultViewModelValue("state/days", NuxieViewModelScalarValue.NumberValue(days)))
                            val size = player.layoutSize()
                            val x = size.first / 2f
                            var found: Pair<Float, Float>? = null
                            var y = size.second - 1f
                            while (y >= 1f && found == null) {
                                fun probe(kind: NuxiePlayerPointerKind) = player.stepTyped(elapsedSeconds = 0.0,
                                    pointers = listOf(NuxiePlayerPointerEvent(kind, x, y, 0, 0f)))
                                val down = probe(NuxiePlayerPointerKind.DOWN)
                                val exit = probe(NuxiePlayerPointerKind.EXIT)
                                assertTrue("Hit probes must not consume a save", down.events.isEmpty() && exit.events.isEmpty() &&
                                    down.hostCommands.isEmpty() && exit.hostCommands.isEmpty())
                                if (down.pointerHits.any { it != NuxiePlayerPointerHit.NONE }) found = x to y - 4f
                                y -= 4f
                            }
                            checkNotNull(found) { "Published Continue must have a native hit" }
                        }
                        if (hit == null) delay(20)
                    }
                    checkNotNull(hit)
                }
            }
            fun tap() = instrumentation.runOnMainSync {
                val density = host.resources.displayMetrics.density
                val now = SystemClock.uptimeMillis()
                for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
                    val event = MotionEvent.obtain(now, now, action, point.first * density, point.second * density, 0)
                    try { assertTrue(host.dispatchTouchEvent(event)) } finally { event.recycle() }
                }
            }
            val values = checkNotNull(checkNotNull(request.get()).runValues)
            suspend fun display(): Triple<Boolean, Boolean, String> = values.lane.call {
                val snapshot = checkNotNull(values.prepare(fixture.scene, fixture.descriptor, artifacts).values).nativeSnapshot()
                val form = snapshot.values.single { it.ownerInstanceId == snapshot.rootInstanceId && it.name == "responses:feedback" }.referencedInstanceId
                val fields = snapshot.values.filter { it.ownerInstanceId == form }.associateBy { it.name }
                Triple(fields.getValue("saving").boolValue, fields.getValue("saved").boolValue,
                    fields.getValue("saveError").bytesValue.decodeToString())
            }
            tap()
            val first = checkNotNull(pending.poll(20, TimeUnit.SECONDS)) { "The published Send must request a save" }
            assertEquals(1L, first.first.sequence)
            assertEquals(owner, first.first.distinctId)
            if (background) {
                assertEquals("onboarding", first.first.formName)
                assertEquals(days, first.first.answers.getValue("trip_days").jsonPrimitive.double, 0.0)
                if (branchEvent != null) {
                    assertEquals("scr_screens_sitalian-level", revealed.poll(20, TimeUnit.SECONDS))
                    val routed = capturedProperties.filter { it.first in listOf("short_trip", "long_trip") }
                    assertEquals("Published condition must select the expected branch", listOf(branchEvent), routed.map { it.first })
                    assertEquals(days, (routed.single().second["trip_days"] as Number).toDouble(), 0.0)
                } else {
                    withTimeout(20_000) { while (!captured.contains(JourneyEventNames.LEG_COMPLETED)) delay(20) }
                }
                assertEquals(1, captured.count { it == "continue" })
                assertFalse(first.second.isCompleted)
                first.second.complete(ai.nuxie.sdk.journey.JourneyResponseSaveReply(
                    ai.nuxie.sdk.journey.JourneyResponseSaveReply.Code.SAVED, first.first.sequence))
                return@runBlocking
            }
            assertEquals("feedback", first.first.formName)
            assertEquals(4.0, first.first.answers.getValue("stars").jsonPrimitive.double, 0.0)
            assertFalse(captured.contains("sent"))
            first.second.complete(ai.nuxie.sdk.journey.JourneyResponseSaveReply.noAnswer)
            withTimeout(20_000) { while (display() != Triple(false, false, "no_answer")) delay(20) }
            assertFalse(captured.contains("sent"))
            assertFalse(captured.contains(JourneyEventNames.LEG_COMPLETED))
            tap()
            val second = checkNotNull(pending.poll(20, TimeUnit.SECONDS)) { "Retry must request a new save" }
            assertEquals(2L, second.first.sequence)
            assertFalse(captured.contains("sent"))
            second.second.complete(ai.nuxie.sdk.journey.JourneyResponseSaveReply(
                ai.nuxie.sdk.journey.JourneyResponseSaveReply.Code.SAVED, second.first.sequence))
            withTimeout(20_000) { while (!captured.contains(JourneyEventNames.LEG_COMPLETED)) delay(20) }
            assertEquals(1, captured.count { it == "sent" })
            assertTrue(captured.toList().indexOf("sent") < captured.toList().indexOf(JourneyEventNames.LEG_COMPLETED))
            assertTrue(pending.isEmpty())
        } finally {
            service.profileDidClearAll()
            presentations.shutdownOwnedBy(owner)
            delivery.shutdown()
            scope.cancel()
            store.close()
            instrumentation.removeMonitor(monitor)
            directory.deleteRecursively()
        }
    }

    private fun navigateWithValue(days: Double, destination: String, branch: Boolean, reserved: Boolean = false, published: Boolean = false) = runBlocking {
        assertTrue(NuxieRuntime.shared.isAvailable)
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val owner = "shared-values-${UUID.randomUUID()}"
        val directory = File(context.cacheDir, owner).apply { mkdirs() }
        val fixture = fixture(branch, published = published)
        val scene = File(directory, "screen.riv").apply { writeBytes(fixture.scene) }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val store = SQLiteEventStore(context, databaseFile = File(directory, "events.db"))
        val presentations = ExperiencePresentationService(context, { _, _, _ -> }, scope,
            { NuxieRuntime.shared.isAvailable }, currentDistinctId = { owner })
        val monitor = Instrumentation.ActivityMonitor(NuxieExperienceActivity::class.java.name, null, false)
        instrumentation.addMonitor(monitor)
        val request = AtomicReference<JourneyPresentationRequest?>()
        val revealed = LinkedBlockingQueue<String>()
        val captured = LinkedBlockingQueue<String>()
        val catalog = JourneyProfileCatalog(fixture.keys, JourneyReleaseHighWaterStore(context)) { fixture.supported }
        val presenter = object : JourneyPresenting {
            override fun reserve(ownerDistinctId: String) = presentations.reserveJourney(ownerDistinctId)
            override fun owns(owner: JourneyPresentationOwner) = presentations.ownsJourney(owner)
            override fun screenId(owner: JourneyPresentationOwner) = presentations.journeyScreenId(owner)
            override suspend fun openLink(owner: JourneyPresentationOwner, link: JourneyLinkRequest) = presentations.openJourneyLink(owner, link)
            override suspend fun shutdownOwnedBy(ownerDistinctId: String) = presentations.shutdownOwnedBy(ownerDistinctId)
            override suspend fun shutdownPresentation(ownerDistinctId: String, journeyId: String) = presentations.shutdownJourney(ownerDistinctId, journeyId)
            override suspend fun present(value: JourneyPresentationRequest): JourneyPresentationResult {
                request.set(value)
                presentations.presentJourney(value.fences, value.release, value.screenId, value.journeyId,
                    value.ownerDistinctId, value.reservation, value.canPresent,
                    acquire = { AcquiredJourneyRelease(value.release.identity, emptyMap(), scene, protection = Closeable {}) },
                    nextBatchSequence = value.nextBatchSequence, nextEmissionSequence = value.nextEmissionSequence,
                    onScreenChanged = value.onScreenChanged, onScreenDismissed = value.onScreenDismissed,
                    onLinkOpened = value.onLinkOpened, onEmissionBatch = value.onEmissionBatch,
                    onPresentationRevealed = { value.onPresentationRevealed(it); revealed.add(it) },
                    onOutcome = value.onOutcome, runValues = value.runValues)
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
                captured.add(name)
                store.insertPendingIfAbsent(ai.nuxie.sdk.events.StoredEvent(eventId, name,
                    ai.nuxie.sdk.events.JsonValueConverter.fromMap(properties), System.currentTimeMillis(), distinctId))
            }, presenter = presenter)
        try {
            withTimeout(30_000) {
                catalog.commit(owner, catalog.prepare(fixture.profile, fixture.authority))
                service.initialize()
                service.onAppWillEnterForeground()
                service.profileDidCommit(checkNotNull(catalog.snapshot(owner)), fixture.authority, owner, 1)
            }
            val activity = checkNotNull(monitor.waitForActivityWithTimeout(10_000))
            assertEquals(if (published) "tap" else "first", revealed.poll(20, TimeUnit.SECONDS))
            fun surface(previous: ExperienceSurfaceHost? = null): ExperienceSurfaceHost? {
                var result: ExperienceSurfaceHost? = null
                instrumentation.runOnMainSync {
                    fun find(view: View): ExperienceSurfaceHost? = when (view) {
                        is ExperienceSurfaceHost -> view.takeIf { it !== previous && it.isShown }
                        is ViewGroup -> (0 until view.childCount).firstNotNullOfOrNull { find(view.getChildAt(it)) }
                        else -> null
                    }
                    result = find((monitor.lastActivity ?: activity).window.decorView)
                }
                return result
            }
            fun lane(host: ExperienceSurfaceHost) = ExperienceSurfaceHost::class.java.getDeclaredField("lane").apply { isAccessible = true }.get(host) as NuxieRuntimeLane
            fun artboard(host: ExperienceSurfaceHost) = ExperienceSurfaceHost::class.java.getDeclaredField("artboard").apply { isAccessible = true }.get(host) as NuxieRuntimeArtboard
            val first = checkNotNull(surface())
            val firstId = lane(first).call {
                val root = artboard(first)
                if (published) assertEquals(NuxieViewModelScalarValue.NumberValue(PublishedRunValuesFixture.expectations
                    .getValue("tap").jsonObject.getValue("before").jsonPrimitive.double),
                    checkNotNull(root.defaultViewModelSnapshot()).resolveScalar(listOf("experience", "trip_days")))
                else assertTrue(root.setDefaultViewModelValue("experience/trip_days", NuxieViewModelScalarValue.NumberValue(days)))
                checkNotNull(root.defaultViewModelSnapshot()).nativeRootInstanceId
            }
            if (published) {
                val point = lane(first).call {
                    val player = ExperienceSurfaceHost::class.java.getDeclaredField("player")
                        .apply { isAccessible = true }.get(first) as NuxieRuntimePlayer
                    val bounds = ExperienceSurfaceHost::class.java.getDeclaredField("layoutBounds")
                        .apply { isAccessible = true }.get(first) as ExperienceArtboardSize
                    var hit: Pair<Float, Float>? = null
                    search@ for (y in 0 until bounds.height.toInt() step 4) {
                        for (x in 0 until bounds.width.toInt() step 4) {
                            val px = bounds.originX + x + 0.5f
                            val py = bounds.originY + y + 0.5f
                            fun probe(kind: NuxiePlayerPointerKind) = player.stepTyped(elapsedSeconds = 0.0,
                                pointers = listOf(NuxiePlayerPointerEvent(kind, px, py, 0, 0f)))
                            val down = probe(NuxiePlayerPointerKind.DOWN)
                            val exit = probe(NuxiePlayerPointerKind.EXIT)
                            assertTrue("Hit probes must not consume an event", down.events.isEmpty() && exit.events.isEmpty())
                            assertTrue(down.hostCommands.isEmpty() && exit.hostCommands.isEmpty())
                            if (down.pointerHits.any { it != NuxiePlayerPointerHit.NONE }) {
                                hit = (px - bounds.originX) to (py - bounds.originY)
                                break@search
                            }
                        }
                    }
                    assertEquals(NuxieViewModelScalarValue.NumberValue(PublishedRunValuesFixture.expectations
                        .getValue("tap").jsonObject.getValue("before").jsonPrimitive.double),
                        checkNotNull(artboard(first).defaultViewModelSnapshot()).resolveScalar(listOf("experience", "trip_days")))
                    checkNotNull(hit) { "The published Continue button has a native pointer hit" }
                }
                assertFalse(captured.contains("continue"))
                instrumentation.runOnMainSync {
                    val density = first.resources.displayMetrics.density
                    val now = SystemClock.uptimeMillis()
                    for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
                        val event = MotionEvent.obtain(now, now, action, point.first * density, point.second * density, 0)
                        try { assertTrue(first.dispatchTouchEvent(event)) } finally { event.recycle() }
                    }
                }
            } else {
                val active = checkNotNull(request.get())
                assertTrue(active.onEmissionBatch(JourneyScreenEmissionBatch(active.journeyId, active.nextBatchSequence,
                    "shared-host-continue", JourneyScreenEmissionSource("first", "scr_screens_sfirst::v3::on-click"),
                    (if (reserved) listOf(JourneyScreenEmission("retired-answer", active.nextEmissionSequence,
                        System.currentTimeMillis(), "\$response_set", buildJsonObject { put("field", "trip_days"); put("value", 999) })) else emptyList()) +
                    JourneyScreenEmission("shared-continue", active.nextEmissionSequence + if (reserved) 1 else 0,
                        System.currentTimeMillis(), "continue", JsonObject(emptyMap()))), null) == JourneyEmissionBatchResult.ACCEPTED)
            }
            withTimeout(20_000) {
                while (request.get()?.screenId != destination) delay(20)
            }
            assertEquals(destination, checkNotNull(request.get()).screenId)
            var next: ExperienceSurfaceHost? = null
            val deadline = SystemClock.uptimeMillis() + 20_000
            while (next == null && SystemClock.uptimeMillis() < deadline) {
                val candidate = surface(first)
                val presented = candidate != null && ExperienceSurfaceHost::class.java.getDeclaredField("firstFramePresented").apply { isAccessible = true }.getBoolean(candidate)
                if (presented) next = candidate else delay(20)
            }
            val shown = checkNotNull(next) {
                val states = mutableListOf<String>()
                instrumentation.runOnMainSync {
                    fun inspect(view: View) {
                        if (view is ExperienceSurfaceHost) {
                            val flags = listOf("running", "attached", "firstFramePresented", "firstFrameComposed", "pendingPresentation")
                                .joinToString { name -> "$name=" + ExperienceSurfaceHost::class.java.getDeclaredField(name).apply { isAccessible = true }.get(view) }
                            states += "old=${view === first}, shown=${view.isShown}, $flags"
                        }
                        if (view is ViewGroup) (0 until view.childCount).forEach { inspect(view.getChildAt(it)) }
                    }
                    inspect((monitor.lastActivity ?: activity).window.decorView)
                }
                "The next screen did not present a native frame: $states"
            }
            if (published) {
                assertEquals("The released Journey admits one native continue without a publisher action id",
                    1, captured.count { it == "continue" })
                assertFalse(captured.contains(JourneyEventNames.LEG_COMPLETED))
            }
            lane(shown).call {
                val snapshot = checkNotNull(artboard(shown).defaultViewModelSnapshot())
                assertNotEquals(firstId, snapshot.nativeRootInstanceId)
                assertEquals(NuxieViewModelScalarValue.NumberValue(days), snapshot.resolveScalar(listOf("experience", "trip_days")))
            }
        } finally {
            service.profileDidClearAll()
            presentations.shutdownOwnedBy(owner)
            store.close()
            scope.cancel()
            instrumentation.removeMonitor(monitor)
            directory.deleteRecursively()
        }
    }

    @Test fun publishedGoalsTimedWaitRestoresBeforeContinuing() = timedWait(goals = true)
    @Test fun nestedTimedWaitRestoresBeforeConditionAndEmitsDeepLeaf() = timedWait(nested = true)
    @Test fun threeDayWaitRestoresBeforeScreenPreparation() = timedWait()
    @Test fun abandonmentKeepsTheLastNativeValues() = timedWait(abandon = true)
    @Test fun failedRestoreRetainsCheckpointAndRetries() = timedWait(failFirstRestore = true)
    @Test fun revokedOwnerCannotCompleteAfterNativeRead() = timedWait(revokeCompletion = true)

    @Test fun revokedOwnerCannotCompleteAfterLifecycleCapture() = timedWait(revokeCapture = true)

    @Test fun revokedIdentityCannotParkAfterNativeRead() = timedWait(revokePark = true)
    @Test fun revokedIdentityCannotConsumeRestoredCheckpoint() = timedWait(revokeRestore = true)

    private fun timedWait(revokeCompletion: Boolean = false, revokeCapture: Boolean = false, failFirstRestore: Boolean = false, abandon: Boolean = false, revokePark: Boolean = false, revokeRestore: Boolean = false, goals: Boolean = false, nested: Boolean = false) = runBlocking {
        assertTrue(NuxieRuntime.shared.isAvailable)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val owner = "native-wait-${UUID.randomUUID()}"
        val directory = File(context.cacheDir, owner).apply { mkdirs() }
        val fixture = fixture(branch = true, wait = true, goals = goals, nested = nested)
        val valueKey = if (nested) "profile/minutes" else if (goals) "goals" else "trip_days"
        val expectedValue = if (nested) JsonPrimitive(20f) else if (goals) Json.parseToJsonElement("""[{"title":"Walk"},{"title":"Sleep"},{"title":"Read"}]""") else JsonPrimitive(30f)
        val catalog = JourneyProfileCatalog(fixture.keys, JourneyReleaseHighWaterStore(context)) { fixture.supported }
        val store = SQLiteEventStore(context, databaseFile = File(directory, "events.db"))
        val identityGeneration = java.util.concurrent.atomic.AtomicLong(0)
        val effectIdentity = if (nested) ai.nuxie.sdk.identity.IdentityService(object : android.content.ContextWrapper(context) {
            override fun getApplicationContext(): android.content.Context = this
            override fun getFilesDir(): File = directory
        }).apply { setDistinctId(owner) } else null
        val identity = effectIdentity ?: object : IdentityProvider {
            override fun captureScope() = ai.nuxie.sdk.identity.IdentityScope(owner, identityGeneration.get())
            override fun isCurrentScope(scope: ai.nuxie.sdk.identity.IdentityScope) = scope == captureScope()
            override fun distinctId() = owner
            override fun anonymousId() = owner
            override fun rawDistinctId(): String? = null
            override val isIdentified = false
        }
        var clock = System.currentTimeMillis()
        var scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val request = AtomicReference<JourneyPresentationRequest?>()
        val nestedEvents = LinkedBlockingQueue<Map<String, Any?>>()
        val completed = LinkedBlockingQueue<Map<String, Any?>>()
        val presenter = object : JourneyPresenting {
            override fun reserve(ownerDistinctId: String) = object : JourneyPresentationReservation { override fun close() {} }
            override suspend fun openLink(owner: JourneyPresentationOwner, link: JourneyLinkRequest): JourneyOpenedLink? = null
            override suspend fun shutdownOwnedBy(ownerDistinctId: String) {}
            override suspend fun present(value: JourneyPresentationRequest): JourneyPresentationResult {
                request.set(value)
                return JourneyPresentationResult.Shown
            }
        }
        val holdSnapshot = java.util.concurrent.atomic.AtomicBoolean(false)
        val snapshotEntered = java.util.concurrent.CountDownLatch(1)
        val releaseSnapshot = java.util.concurrent.CountDownLatch(1)
        val native = object : NuxieTypedRuntimeNative by JniNuxieTypedRuntimeNative {
            override fun snapshotViewModel(viewModelHandle: Long): NativeCallResult<NativeViewModelSnapshot> {
                if (holdSnapshot.compareAndSet(true, false)) {
                    snapshotEntered.countDown()
                    check(releaseSnapshot.await(15, TimeUnit.SECONDS)) { "Snapshot barrier timed out" }
                }
                return JniNuxieTypedRuntimeNative.snapshotViewModel(viewModelHandle)
            }
        }
        val runtime = NuxieRuntime(native)
        val captureEntered = CompletableDeferred<Unit>()
        val releaseCapture = CompletableDeferred<Unit>()
        var restorations = 0
        var restoredDays: JsonElement? = null
        val effects = effectIdentity?.let { effectOwner ->
            JourneyEffectDispatcher(effectOwner, capture = { name, properties, eventId, distinctId, admission, _ ->
                val committed = store.insertPendingIfAbsent(ai.nuxie.sdk.events.StoredEvent(eventId, name,
                    ai.nuxie.sdk.events.JsonValueConverter.fromMap(properties), clock, distinctId), admission) == true
                if (committed && name == "nested_restored") nestedEvents.add(properties)
                committed
            }, deliverAppAction = { _, _ -> error("Nested values do not dispatch app actions") })
        }
        fun service(restore: Boolean) = JourneyService(identity, store, catalog, directory, scope,
            capture = { name, properties, eventId, distinctId ->
                store.insertPendingIfAbsent(ai.nuxie.sdk.events.StoredEvent(eventId, name,
                    ai.nuxie.sdk.events.JsonValueConverter.fromMap(properties), clock, distinctId))
                if (name == "nested_restored") nestedEvents.add(properties)
                if (name == "\$journey_leg_completed") completed.add(properties)
                true
            }, dispatcher = JourneyDispatching { value -> effects?.dispatch(value) ?: JourneyDispatchResult.Unsupported },
            capturePresentationEvent = { name, properties, eventId, distinctId, occurredAt, admission ->
                val event = ai.nuxie.sdk.events.StoredEvent(eventId, name,
                    ai.nuxie.sdk.events.JsonValueConverter.fromMap(properties), occurredAt, distinctId)
                val settled = admission?.commitIfCurrent { true } != null
                if (revokeCapture) { captureEntered.complete(Unit); releaseCapture.await() }
                ai.nuxie.sdk.events.StableEventCaptureResult(settled, event.takeIf { settled })
            }, presenter = presenter, nowMillis = { clock },
            prepareNativeValues = { values, release, _ ->
                if (restore) {
                    restorations++
                    if (failFirstRestore && restorations == 1) throw java.io.IOException("Injected restoration failure")
                }
                values.lane.call { values.prepare(fixture.scene, release.descriptor, emptyMap()) }
                if (restore) {
                    restoredDays = values.journeyValues()[valueKey]
                    if (nested) {
                        assertEquals(JsonPrimitive("2026-10-10"), values.journeyValues()["profile/settings/day"])
                        val checkpoint = checkNotNull(values.snapshot())
                        assertEquals(JsonPrimitive(false), checkpoint.journeyValues["profile/isset:empty"])
                        assertEquals(JsonPrimitive(true), checkpoint.journeyValues["profile/isset:minutes"])
                        assertEquals(Json.parseToJsonElement("""[{"value":"Reading, writing","picked":true},{"value":"Travel","picked":true}]"""), checkpoint.journeyValues["profile/topics/options"])
                        assertTrue(completed.isEmpty())
                    }
                    if (goals) assertTrue("Restore must precede completion", completed.isEmpty())
                    if (revokeRestore) identityGeneration.addAndGet(2)
                }
            })
        var current = service(false)
        try {
            catalog.commit(owner, catalog.prepare(fixture.profile, fixture.authority))
            withTimeout(15_000) { current.initialize() }
            withTimeout(15_000) { current.onAppWillEnterForeground() }
            withTimeout(15_000) { current.profileDidCommit(checkNotNull(catalog.snapshot(owner)), fixture.authority, owner, 1) }
            val active = checkNotNull(request.get())
            checkNotNull(active.runValues).lane.call {
                val native = checkNotNull(active.runValues).prepare(fixture.scene, active.release.descriptor, emptyMap(), runtime = runtime)
                val values = checkNotNull(native.values)
                if (goals) {
                    val before = values.nativeSnapshot()
                    val ids = before.values.single { it.ownerInstanceId == before.rootInstanceId && it.name == "goals" }.listItemIds
                    val schema = before.instances.single { it.id == ids[0] }.schemaIndex.toInt()
                    val added = native.file.newSchemaViewModel(schema)
                    try {
                        added.setValue("title", NuxieViewModelScalarValue.StringValue("Sleep"))
                        values.restoreWrites(listOf(NativeViewModelWrite(NuxieViewModelMutationKind.LIST_MOVE,
                            "goals", index = 1, secondIndex = 0)))
                        values.insertListItem("goals", 1, added)
                    } finally { added.close() }
                } else if (nested) {
                    assertEquals(NuxieViewModelScalarValue.NumberValue(10.0), values.snapshot().resolveScalar(listOf("profile", "minutes")))
                    values.setValue("profile/minutes", NuxieViewModelScalarValue.NumberValue(20.0))
                    values.setValue("profile/settings/day", NuxieViewModelScalarValue.StringValue("2026-10-10"))
                    val choices = values.nativeSnapshot().values.single { it.name == "options" }.listItemIds
                    val picked = values.acquireListItem("profile/topics/options", 0, choices[0])
                    try { picked.setValue("picked", NuxieViewModelScalarValue.BooleanValue(true)) }
                    finally { picked.close() }
                } else values.setValue("trip_days", NuxieViewModelScalarValue.NumberValue(30.0))
                Unit
            }
            if (abandon) {
                active.onOutcome(JourneySurfaceOutcome.ABANDONED)
                val completion = checkNotNull(completed.poll(10, TimeUnit.SECONDS))
                assertEquals("abandoned", completion["outcome"])
                val outputs = completion["outputs"] as Map<*, *>
                val responses = outputs["responses"] as Map<*, *>
                assertEquals(30.0, (responses["trip_days"] as? Number)?.toDouble())
                return@runBlocking
            }
            if (revokeCapture) {
                val finishing = async { active.onScreenDismissed("first", null, "close") }
                withTimeout(10_000) { captureEntered.await() }
                val clearing = async { current.profileDidClear(owner, 2) }
                withTimeout(10_000) { while (active.fences.isCurrent()) yield() }
                releaseCapture.complete(Unit)
                withTimeout(10_000) { finishing.await(); clearing.await() }
                assertFalse(completed.toList().any { it["outcome"] == "host_dismissed" })
                return@runBlocking
            }
            if (revokeCompletion) {
                holdSnapshot.set(true)
                val finishing = async { active.onOutcome(JourneySurfaceOutcome.DISMISSED) }
                withContext(Dispatchers.IO) { assertTrue(snapshotEntered.await(10, TimeUnit.SECONDS)) }
                val clearing = async { current.profileDidClear(owner, 2) }
                withTimeout(10_000) { while (active.fences.isCurrent()) yield() }
                releaseSnapshot.countDown()
                withTimeout(10_000) { finishing.await(); clearing.await() }
                assertFalse(completed.toList().any { it["outcome"] == "host_dismissed" })
                return@runBlocking
            }
            if (revokePark) holdSnapshot.set(true)
            assertTrue(active.onEmissionBatch(JourneyScreenEmissionBatch(active.journeyId, 0, "wait-continue",
                JourneyScreenEmissionSource(if (goals) "goals" else "first", "scr_screens_sfirst::v3::on-click"),
                listOf(JourneyScreenEmission("wait-event", 0, clock, "continue", JsonObject(emptyMap())))), null) == JourneyEmissionBatchResult.ACCEPTED)
            val journal = JourneyRunJournal(directory, owner, JourneyStorageScope(fixture.authority))
            if (revokePark) {
                withContext(Dispatchers.IO) { assertTrue(snapshotEntered.await(10, TimeUnit.SECONDS)) }
                identityGeneration.addAndGet(2)
                releaseSnapshot.countDown()
                withTimeout(10_000) { current.onAppDidEnterBackground() }
                assertTrue(journal.runs().none { it.park != null })
                assertTrue(completed.isEmpty())
                return@runBlocking
            }
            withTimeout(10_000) { while (journal.runs().firstOrNull()?.park == null) delay(10) }
            val parked = journal.runs().single()
            assertEquals(clock + 259200000, parked.park?.wakeAtMillis)
            assertTrue(parked.context.getValue("responses").jsonObject.isEmpty())
            assertEquals(expectedValue, parked.nativeSnapshot?.journeyValues?.get(valueKey))
            withTimeout(15_000) { current.onAppDidEnterBackground() }
            scope.cancel()
            withTimeout(15_000) { checkNotNull(active.runValues).retire() }
            request.set(null)
            clock += 259200000
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            current = service(true)
            withTimeout(15_000) { current.initialize() }
            if (revokeRestore) {
                withTimeout(15_000) { current.onAppWillEnterForeground() }
                withTimeout(15_000) { current.profileDidCommit(checkNotNull(catalog.snapshot(owner)), fixture.authority, owner, 1) }
                assertEquals(1, restorations)
                assertNotNull(journal.runs().single().park)
                assertEquals(JsonPrimitive(30f), journal.runs().single().nativeSnapshot?.journeyValues?.get("trip_days"))
                assertTrue(completed.isEmpty())
                return@runBlocking
            }
            withTimeout(15_000) { current.onAppWillEnterForeground() }
            withTimeout(15_000) { current.profileDidCommit(checkNotNull(catalog.snapshot(owner)), fixture.authority, owner, 1) }
            if (failFirstRestore) {
                val retained = journal.runs().single()
                assertNotNull(retained.park)
                assertEquals(JsonPrimitive(30f), retained.nativeSnapshot?.journeyValues?.get("trip_days"))
                assertTrue(completed.isEmpty())
                clock += 5000
            }
            val event = completed.poll(10, TimeUnit.SECONDS)
            if (nested) {
                val emitted = checkNotNull(nestedEvents.poll(10, TimeUnit.SECONDS))
                assertEquals("2026-10-10", emitted["day"])
                assertEquals("Ana", emitted["name"])
            }
            assertEquals("long", event?.get("outcome"))
            assertEquals(if (failFirstRestore) 2 else 1, restorations)
            assertEquals(expectedValue, restoredDays)
            assertNull(request.get())
        } finally {
            releaseSnapshot.countDown()
            releaseCapture.complete(Unit)
            withTimeout(15_000) { current.profileDidClearAll() }
            scope.cancel()
            store.close()
            directory.deleteRecursively()
        }
    }

    private data class Fixture(val scene: ByteArray, val profile: JsonObject, val keys: Map<String, ByteArray>,
        val supported: JourneyReleaseSupportedRuntime, val authority: ProfileDeliveryAuthority, val descriptor: JsonObject)

    private fun fixture(branch: Boolean = false, wait: Boolean = false, published: Boolean = false, goals: Boolean = false, form: String? = null, publishedFormRoutes: Boolean = false, nested: Boolean = false): Fixture {
        val assets = InstrumentationRegistry.getInstrumentation().context.assets
        fun read(path: String) = assets.open(path).use { it.readBytes() }
        val entry = Json.parseToJsonElement(read("journeys/rendered-text-input/release-entry.json").decodeToString()).jsonObject
        val envelope = entry.getValue("envelope").jsonObject
        val original = Json.parseToJsonElement(Base64.decode(envelope.getValue("descriptorBytesBase64").jsonPrimitive.content, Base64.NO_WRAP).decodeToString()).jsonObject
        val folder = if (nested) "nested-values" else if (form != null) "forms-saves" else if (goals) "forms-saves/goals" else if (published) "run-values" else "shared-values"
        val scene = read("runtime/$folder/screen.riv")
        val provenance = Json.parseToJsonElement(read("runtime/$folder/provenance.json").decodeToString()).jsonObject
        fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        val names = if (nested) listOf("first") else if (goals) listOf("goals", "quiet") else if (published) PublishedRunValuesFixture.expectations.getValue("screens").jsonArray
            .map { it.jsonPrimitive.content } else listOf("first", "long", "short")
        val supported = checkNotNull(supportedRuntimeForEmbeddedRuntime(nuxieRuntimeSourceRevision()))
        val requirements = JsonObject(original.getValue("requirements").jsonObject + mapOf(
            "runtimeRevision" to JsonPrimitive(supported.supportedRuntimeRevisions.single()),
            "requiredCapabilities" to JsonArray(listOf("nux", "system-fonts").map(::JsonPrimitive))))
        fun navigate(id: String, screen: String) = buildJsonObject {
            put("id", id); put("kind", "action"); putJsonObject("action") { put("type", "navigate"); put("screenId", screen) }; putJsonObject("outlets") {}
        }
        val branchStep = Json.parseToJsonElement("""{"kind":"action","id":"next","action":{"type":"condition","branches":[{"id":"long","condition":{"type":"Compare","op":">","left":{"type":"Response.Field","key":"trip_days"},"right":{"type":"Number","value":14}}}]},"outlets":{"long":"long","default":"short"}}""").jsonObject
        var steps = if (branch) listOf(navigate("show", "first"), branchStep,
            navigate("long", "long"), navigate("short", "short")) else listOf(navigate("show", "first"), navigate("next", "long"))
        if (wait) {
            val parkedBranch = Json.parseToJsonElement(branchStep.toString()
                .replace("\"id\":\"next\"", "\"id\":\"branch\"")
                .replace("\"op\":\">\"", "\"op\":\"==\"")
                .replace("\"value\":14", "\"value\":30")).jsonObject
            val delay = Json.parseToJsonElement("""{"kind":"action","id":"next","action":{"type":"delay","durationMs":259200000},"outlets":{"next":"branch"}}""").jsonObject
            fun complete(outcome: String) = buildJsonObject { put("kind", "complete"); put("id", outcome); put("outcome", outcome) }
            steps = listOf(navigate("show", "first"), delay, parkedBranch, complete("long"), complete("short"))
        }
        if (nested) {
            steps = Json.parseToJsonElement("""[
              {"kind":"action","id":"show","action":{"type":"navigate","screenId":"first"},"outlets":{}},
              {"kind":"action","id":"next","action":{"type":"delay","durationMs":259200000},"outlets":{"next":"branch"}},
              {"kind":"action","id":"branch","action":{"type":"condition","branches":[{"id":"long","condition":{"type":"Compare","op":">","left":{"type":"Response.Field","key":"profile/minutes"},"right":{"type":"Number","value":15}}}]},"outlets":{"long":"emit","default":"short"}},
              {"kind":"action","id":"emit","action":{"type":"send_event","eventName":"nested_restored","payload":{"day":{"type":"Response.Field","key":"profile/settings/day"},"name":{"type":"Response.Field","key":"profile/name"}}},"outlets":{"next":"long"}},
              {"kind":"complete","id":"long","outcome":"long"},
              {"kind":"complete","id":"short","outcome":"short"}
            ]""").jsonArray.map { it.jsonObject }
        }
        if (goals) {
            val delay = Json.parseToJsonElement("""{"kind":"action","id":"next","action":{"type":"delay","durationMs":259200000},"outlets":{"next":"restored"}}""").jsonObject
            val restored = Json.parseToJsonElement("""{"kind":"action","id":"restored","action":{"type":"condition","branches":[{"id":"saved","condition":{"type":"Compare","op":"==","left":{"type":"Response.Field","key":"goals"},"right":{"type":"Array","items":[{"type":"Object","fields":{"title":{"type":"String","value":"Walk"}}},{"type":"Object","fields":{"title":{"type":"String","value":"Sleep"}}},{"type":"Object","fields":{"title":{"type":"String","value":"Read"}}}]}}}]},"outlets":{"saved":"long","default":"lost"}}""").jsonObject
            steps = listOf(navigate("show", "goals"), delay, restored,
                buildJsonObject { put("kind", "complete"); put("id", "long"); put("outcome", "long") },
                buildJsonObject { put("kind", "complete"); put("id", "lost"); put("outcome", "lost") })
        }
        if (published) {
            val condition = Json.parseToJsonElement("""{"kind":"action","id":"next","action":{"type":"condition","branches":[{"id":"changed","condition":{"type":"Compare","op":"==","left":{"type":"Response.Field","key":"trip_days"},"right":{"type":"Number","value":${PublishedRunValuesFixture.expectations.getValue("tap").jsonObject.getValue("after")}}}}]},"outlets":{"changed":"level","default":"stale"}}""").jsonObject
            steps = listOf(navigate("show", "tap"), condition, navigate("level", "level"),
                buildJsonObject { put("kind", "complete"); put("id", "stale"); put("outcome", "stale") })
        }
        val leg = JsonObject(original.getValue("leg").jsonObject + mapOf(
            "entryStepId" to JsonPrimitive("show"), "outputs" to JsonArray(emptyList()),
            "policy" to original.getValue("leg").jsonObject.getValue("policy").jsonObject.let { policy ->
                if (!wait) policy else JsonObject(policy + ("entry" to JsonObject(policy.getValue("entry").jsonObject +
                    ("frequency" to buildJsonObject { put("type", "one_time") }))))
            },
            "steps" to JsonArray(steps),
            "screens" to JsonArray(names.map { buildJsonObject {
                put("id", it); put("defaultViewModelName", "Runtime $it scr_screens_s$it"); put("defaultInstanceId", "$it-root"); putJsonArray("responseCaptures") {}
            } }),
            "routes" to buildJsonArray { addJsonObject { put("entryStepId", "next"); put("eventName", "continue"); putJsonObject("host") { put("kind", "screen"); put("screenId", names.first()) } } }))
        var descriptor = JsonObject(original + mapOf("leg" to leg, "requirements" to requirements,
            "viewModelValues" to JsonArray(emptyList()),
            "screenBehaviors" to JsonArray(names.sorted().map { buildJsonObject {
                put("screenId", it); putJsonArray("controls") {
                    if (it == "first" || (goals && it == "goals")) addJsonObject {
                        put("actionId", "scr_screens_sfirst::v3::on-click")
                        putJsonObject("behavior") { put("kind", "declarative"); putJsonArray("program") {
                            addJsonObject { put("type", "emit"); put("eventName", "continue"); putJsonObject("payload") {} }
                        } }
                    }
                }
            } }),
            "render" to buildJsonObject {
                put("renderer", "nux")
                putJsonObject("nux") { put("key", "renders/sha256/${hash(scene)}.nux"); put("sha256", hash(scene)); put("sizeBytes", scene.size); put("contentType", "application/vnd.nuxie.scene") }
                put("assets", JsonArray(provenance.getValue("fonts").jsonArray.map { JsonObject(it.jsonObject + ("kind" to JsonPrimitive("font"))) }))
                put("screens", JsonArray(names.map { buildJsonObject { put("id", it); put("artboardId", it); put("artboardName", it); put("width", 393); put("height", 852) } }))
                putJsonArray("transitions") {}; putJsonArray("textInputs") {}
            }))
        if (nested) descriptor = JsonObject(descriptor + ("state" to Json.parseToJsonElement(read("runtime/nested-values/state.json").decodeToString())))
        if (form != null) {
            val publishedForm = Json.parseToJsonElement(read("runtime/forms-saves/release.json").decodeToString()).jsonObject
            val screen = "scr_screens_s$form"
            descriptor = JsonObject(publishedForm + mapOf("identity" to original.getValue("identity"),
                "requirements" to requirements,
                "leg" to JsonObject(publishedForm.getValue("leg").jsonObject + mapOf(
                    "id" to original.getValue("leg").jsonObject.getValue("id"),
                    "policy" to original.getValue("leg").jsonObject.getValue("policy"),
                    "entryCondition" to original.getValue("leg").jsonObject.getValue("entryCondition"),
                    "entryStepId" to JsonPrimitive("show"),
                    "steps" to JsonArray(listOf(navigate("show", screen),
                        buildJsonObject { put("kind", "complete"); put("id", "done"); put("outcome", "done") })),
                    "routes" to buildJsonArray { addJsonObject {
                        put("entryStepId", "done"); put("eventName", if (form == "feedback") "sent" else "continue")
                        putJsonObject("host") { put("kind", "screen"); put("screenId", screen) }
                    } }
                ))))
            if (publishedFormRoutes) {
                val publishedLeg = publishedForm.getValue("leg").jsonObject
                // Keep every published route and action. Enter at its departure navigation.
                val entryStep = publishedLeg.getValue("steps").jsonArray.first {
                    (it.jsonObject["action"] as? JsonObject)?.get("screenId") == JsonPrimitive(screen)
                }.jsonObject.getValue("id")
                descriptor = JsonObject(descriptor + ("leg" to JsonObject(descriptor.getValue("leg").jsonObject + mapOf(
                    "steps" to publishedLeg.getValue("steps"), "routes" to publishedLeg.getValue("routes"), "entryStepId" to entryStep))))
            }
        }
        val bytes = descriptor.toString().encodeToByteArray()
        val publicKey: ByteArray
        val signature: ByteArray
        if (form != null) {
            val proof = Json.parseToJsonElement(read("f5-journey-signatures.json").decodeToString()).jsonObject
            val signedForm = proof.getValue("signatures").jsonObject.getValue(if (publishedFormRoutes) "departure-c11" else form).jsonObject
            assertEquals("Signed F5 test route must match its descriptor", signedForm.getValue("descriptorSha256").jsonPrimitive.content, hash(bytes))
            publicKey = Base64.decode(proof.getValue("publicKey").jsonPrimitive.content, Base64.NO_WRAP)
            signature = Base64.decode(signedForm.getValue("signature").jsonPrimitive.content, Base64.NO_WRAP)
        } else {
            val alias = "shared-values-test-${UUID.randomUUID()}"
            val pair = KeyPairGenerator.getInstance("Ed25519", "AndroidKeyStore").run {
                initialize(android.security.keystore.KeyGenParameterSpec.Builder(alias,
                    android.security.keystore.KeyProperties.PURPOSE_SIGN or android.security.keystore.KeyProperties.PURPOSE_VERIFY)
                    .setAlgorithmParameterSpec(java.security.spec.ECGenParameterSpec("ed25519"))
                    .setDigests(android.security.keystore.KeyProperties.DIGEST_NONE).build())
                generateKeyPair()
            }
            signature = try {
                Signature.getInstance("Ed25519").run { initSign(pair.private); update(JourneyReleaseLimits.SIGNATURE_DOMAIN.encodeToByteArray() + bytes); sign() }
            } finally {
                java.security.KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(alias)
            }
            publicKey = pair.public.encoded.takeLast(32).toByteArray()
        }
        val signed = JsonObject(envelope + mapOf("descriptorSha256" to JsonPrimitive(hash(bytes)),
            "descriptorSizeBytes" to JsonPrimitive(bytes.size), "descriptorBytesBase64" to JsonPrimitive(Base64.encodeToString(bytes, Base64.NO_WRAP)),
            "signature" to buildJsonObject { put("version", 1); put("algorithm", "ed25519"); put("keyId", "TEST_ONLY_SHARED"); put("signatureBase64", Base64.encodeToString(signature, Base64.NO_WRAP)) }))
        val locator = entry.getValue("locator").jsonObject
        val profile = buildJsonObject {
            put("schemaVersion", "nuxie.journey-plane-profile.v2"); put("status", "ok")
            putJsonObject("delivery") { put("renderBaseUrl", "https://renders.example.test/"); put("assetBaseUrl", "https://assets.example.test/") }
            putJsonArray("features") {}; putJsonObject("facts") { putJsonObject("properties") {}; putJsonObject("memberships") {}; putJsonObject("assignments") {} }
            put("releases", JsonArray(listOf(JsonObject(entry + ("envelope" to signed)))))
            putJsonArray("armedLegs") { addJsonObject {
                putJsonObject("reference") { put("experienceId", locator.getValue("experienceId")); put("versionId", locator.getValue("experienceVersionId")); put("legId", locator.getValue("legId")); put("descriptorSha256", hash(bytes)) }
                putJsonObject("binding") { put("type", "new") }; putJsonObject("entryCondition") { put("type", "app_foregrounded") }
                putJsonObject("context") { putJsonObject("event") {}; putJsonObject("responses") {} }
            } }
        }
        return Fixture(scene, profile, mapOf("TEST_ONLY_SHARED" to publicKey), supported,
            ProfileDeliveryAuthority(locator.getValue("appId").jsonPrimitive.content, locator.getValue("environment").jsonPrimitive.content), descriptor)
    }
}
