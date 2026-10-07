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
                        System.currentTimeMillis(), "continue", JsonObject(emptyMap()))), null))
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
    @Test fun threeDayWaitRestoresBeforeScreenPreparation() = timedWait()
    @Test fun abandonmentKeepsTheLastNativeValues() = timedWait(abandon = true)
    @Test fun failedRestoreRetainsCheckpointAndRetries() = timedWait(failFirstRestore = true)
    @Test fun revokedOwnerCannotCompleteAfterNativeRead() = timedWait(revokeCompletion = true)

    @Test fun revokedOwnerCannotCompleteAfterLifecycleCapture() = timedWait(revokeCapture = true)

    @Test fun revokedIdentityCannotParkAfterNativeRead() = timedWait(revokePark = true)
    @Test fun revokedIdentityCannotConsumeRestoredCheckpoint() = timedWait(revokeRestore = true)

    private fun timedWait(revokeCompletion: Boolean = false, revokeCapture: Boolean = false, failFirstRestore: Boolean = false, abandon: Boolean = false, revokePark: Boolean = false, revokeRestore: Boolean = false, goals: Boolean = false) = runBlocking {
        assertTrue(NuxieRuntime.shared.isAvailable)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val owner = "native-wait-${UUID.randomUUID()}"
        val directory = File(context.cacheDir, owner).apply { mkdirs() }
        val fixture = fixture(branch = true, wait = true, goals = goals)
        val valueKey = if (goals) "goals" else "trip_days"
        val expectedValue = if (goals) Json.parseToJsonElement("""[{"title":"Walk"},{"title":"Sleep"},{"title":"Read"}]""") else JsonPrimitive(30f)
        val catalog = JourneyProfileCatalog(fixture.keys, JourneyReleaseHighWaterStore(context)) { fixture.supported }
        val store = SQLiteEventStore(context, databaseFile = File(directory, "events.db"))
        val identityGeneration = java.util.concurrent.atomic.AtomicLong(0)
        val identity = object : IdentityProvider {
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
        fun service(restore: Boolean) = JourneyService(identity, store, catalog, directory, scope,
            capture = { name, properties, eventId, distinctId ->
                store.insertPendingIfAbsent(ai.nuxie.sdk.events.StoredEvent(eventId, name,
                    ai.nuxie.sdk.events.JsonValueConverter.fromMap(properties), clock, distinctId))
                if (name == "\$journey_leg_completed") completed.add(properties)
                true
            }, capturePresentationEvent = { name, properties, eventId, distinctId, occurredAt, admission ->
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
                } else values.setValue("trip_days", NuxieViewModelScalarValue.NumberValue(30.0))
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
                listOf(JourneyScreenEmission("wait-event", 0, clock, "continue", JsonObject(emptyMap())))), null))
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
        val supported: JourneyReleaseSupportedRuntime, val authority: ProfileDeliveryAuthority)

    private fun fixture(branch: Boolean = false, wait: Boolean = false, published: Boolean = false, goals: Boolean = false): Fixture {
        val assets = InstrumentationRegistry.getInstrumentation().context.assets
        fun read(path: String) = assets.open(path).use { it.readBytes() }
        val entry = Json.parseToJsonElement(read("journeys/rendered-text-input/release-entry.json").decodeToString()).jsonObject
        val envelope = entry.getValue("envelope").jsonObject
        val original = Json.parseToJsonElement(Base64.decode(envelope.getValue("descriptorBytesBase64").jsonPrimitive.content, Base64.NO_WRAP).decodeToString()).jsonObject
        val folder = if (goals) "forms-saves/goals" else if (published) "run-values" else "shared-values"
        val scene = read("runtime/$folder/screen.riv")
        val provenance = Json.parseToJsonElement(read("runtime/$folder/provenance.json").decodeToString()).jsonObject
        fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        val names = if (goals) listOf("goals", "quiet") else if (published) PublishedRunValuesFixture.expectations.getValue("screens").jsonArray
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
        val descriptor = JsonObject(original + mapOf("leg" to leg, "requirements" to requirements,
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
        val bytes = descriptor.toString().encodeToByteArray()
        val alias = "shared-values-test-${UUID.randomUUID()}"
        val pair = KeyPairGenerator.getInstance("Ed25519", "AndroidKeyStore").run {
            initialize(android.security.keystore.KeyGenParameterSpec.Builder(alias,
                android.security.keystore.KeyProperties.PURPOSE_SIGN or android.security.keystore.KeyProperties.PURPOSE_VERIFY)
                .setAlgorithmParameterSpec(java.security.spec.ECGenParameterSpec("ed25519"))
                .setDigests(android.security.keystore.KeyProperties.DIGEST_NONE).build())
            generateKeyPair()
        }
        val signature = try {
            Signature.getInstance("Ed25519").run { initSign(pair.private); update(JourneyReleaseLimits.SIGNATURE_DOMAIN.encodeToByteArray() + bytes); sign() }
        } finally {
            java.security.KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(alias)
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
        return Fixture(scene, profile, mapOf("TEST_ONLY_SHARED" to pair.public.encoded.takeLast(32).toByteArray()), supported,
            ProfileDeliveryAuthority(locator.getValue("appId").jsonPrimitive.content, locator.getValue("environment").jsonPrimitive.content))
    }
}
