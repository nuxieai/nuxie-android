package ai.nuxie.sdk

import ai.nuxie.sdk.core.NuxieCore
import ai.nuxie.sdk.events.SystemEventNames
import ai.nuxie.sdk.events.SQLiteEventStore
import ai.nuxie.sdk.identity.IdentityService
import ai.nuxie.sdk.journey.JourneyEventNames
import ai.nuxie.sdk.journey.JourneyRunJournal
import ai.nuxie.sdk.journey.JourneyStorageScope
import ai.nuxie.sdk.network.HttpTransport
import ai.nuxie.sdk.network.ProfileDeliveryAuthority
import ai.nuxie.sdk.presentation.ExperienceSurfaceHost
import ai.nuxie.sdk.presentation.NuxieExperienceActivity
import ai.nuxie.sdk.presentation.SurfaceCompatibilityHostActivity
import android.app.Instrumentation
import android.content.Intent
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class NuxiePublicStartupDeviceTest {
    @Test fun emptyDeliverySurvivesOfflineReconstructionAndRestoresTheSameRelease() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        fun read(path: String) = instrumentation.context.assets
            .open("journeys/rendered-screen-control/$path").use { it.readBytes() }
        val entry = Json.parseToJsonElement(read("release-entry.json").decodeToString()).jsonObject
        val locator = entry.getValue("locator").jsonObject
        val envelope = entry.getValue("envelope").jsonObject
        val descriptor = Json.parseToJsonElement(android.util.Base64.decode(
            envelope.getValue("descriptorBytesBase64").jsonPrimitive.content, android.util.Base64.NO_WRAP,
        ).decodeToString()).jsonObject
        val active = profile(entry, descriptor.getValue("leg").jsonObject.getValue("entryCondition").jsonObject)
        val empty = JsonObject(active + mapOf(
            "armedLegs" to JsonArray(emptyList()), "releases" to JsonArray(emptyList()),
        ))
        val render = descriptor.getValue("render").jsonObject
        val artifactKeys = (render.getValue("assets").jsonArray.map { it.jsonObject.getValue("key").jsonPrimitive.content } +
            render.getValue("nux").jsonObject.getValue("key").jsonPrimitive.content +
            descriptor.getValue("screenBehaviors").jsonArray.map {
                it.jsonObject.getValue("script").jsonObject.getValue("artifact").jsonObject.getValue("key").jsonPrimitive.content
            }).toSet()
        val online = java.util.concurrent.atomic.AtomicBoolean(true)
        val body = java.util.concurrent.atomic.AtomicReference(active)
        val blocked = java.util.concurrent.atomic.AtomicInteger()
        val transport = HttpTransport { request ->
            if (!online.get()) {
                blocked.incrementAndGet()
                throw java.io.IOException("qualification transport offline")
            }
            val path = request.url.path.trimStart('/')
            when {
                path == "profile" -> HttpTransport.Response(200, body.get().toString().encodeToByteArray(), mapOf(
                    "ETag" to if (body.get() == empty) "\"empty-delivery\"" else "\"active-delivery\"",
                    "Nuxie-App-Id" to locator.getValue("appId").jsonPrimitive.content,
                    "Nuxie-App-Environment" to locator.getValue("environment").jsonPrimitive.content,
                ))
                path in artifactKeys -> HttpTransport.Response(200, read(path), mapOf(
                    "Content-Type" to if (path.endsWith(".nux")) "application/vnd.nuxie.scene" else "application/octet-stream",
                ))
                else -> HttpTransport.Response(503, ByteArray(0))
            }
        }
        val directory = File(context.cacheDir, "delivery-recovery-${UUID.randomUUID()}").apply { mkdirs() }
        val identity = IdentityService(context).apply { setDistinctId(directory.name) }
        val apiKey = "pk_test_${directory.name}"
        fun createCore() = NuxieCore(
            context = context, apiKey = apiKey, environment = NuxieEnvironment.DEVELOPMENT,
            logLevel = LogLevel.NONE, beforeSend = null,
            overrides = NuxieCore.Overrides(
                identity = identity, transport = transport, registerLifecycle = false,
                requestInitialProfileRefresh = false,
                eventDatabaseFile = File(directory, "events.db"),
                profileCacheDirectory = File(directory, "profiles"),
            ),
        )
        var core = createCore()
        fun snapshot() = requireNotNull(core.journeyProfiles.snapshot(identity.distinctId()))
        try {
            assertTrue("Initial signed profile must be admitted", core.profile.refreshAndWait())
            assertEquals(1, snapshot().profile.armedLegs.size)
            val release = snapshot().releasesByDigest.values.single()
            val highWater = ai.nuxie.sdk.experiences.JourneyReleaseHighWaterStore(context)
            val floor = highWater.floor(release.identity.streamKey)

            body.set(empty)
            online.set(false)
            assertFalse(core.profile.refreshAndWait())
            assertEquals(1, snapshot().profile.armedLegs.size)
            online.set(true)
            assertTrue("Empty delivery must replace the active profile", core.profile.refreshAndWait())
            assertTrue(snapshot().profile.armedLegs.isEmpty())
            assertTrue(snapshot().releasesByDigest.isEmpty())
            assertEquals(floor, highWater.floor(release.identity.streamKey))

            core.stopAndAwait()
            online.set(false)
            core = createCore()
            assertFalse(core.profile.refreshAndWait())
            assertEquals(empty, core.profile.currentProfile()?.body)
            assertTrue(snapshot().profile.armedLegs.isEmpty())
            assertTrue(snapshot().releasesByDigest.isEmpty())
            assertEquals(floor, highWater.floor(release.identity.streamKey))

            body.set(active)
            online.set(true)
            assertTrue("The same release must be readmitted after recovery", core.profile.refreshAndWait())
            assertEquals(1, snapshot().profile.armedLegs.size)
            assertEquals(release.identity, snapshot().releasesByDigest.values.single().identity)
            assertEquals(floor, highWater.floor(release.identity.streamKey))
            assertTrue("Both offline refreshes must reach the impaired transport", blocked.get() >= 2)
        } finally {
            core.stopAndAwait()
            directory.deleteRecursively()
        }
    }

    @Test fun restoredDeliveryPresentsAndCommitsACompiledControlEvent() =
        exercise(eventEntry = false, deliveryRecovery = true)

    @Test fun initialProfileAdmitsASignedJourneyAndCommitsACompiledControlEvent() = exercise(eventEntry = false)

    @Test fun triggerCapturedBeforeTheInitialProfileEnrollsItsSignedJourney() = exercise(eventEntry = true)

    @Test fun backgroundDuringInitialRefreshCannotPresentOrEnrollABackgroundEvent() =
        exercise(eventEntry = true, backgroundDuringRefresh = true)

    @Test fun immediatelyAvailableProfileStillAdmitsThePublicTrigger() =
        exercise(eventEntry = true, holdInitialProfile = false)

    @Test fun processDeathRetainsThePublicTriggerUntilSignedProfileAdmission() {
        val arguments = InstrumentationRegistry.getArguments()
        val phase = arguments.getString("nuxie_process_phase")
        org.junit.Assume.assumeTrue("Run with scripts/test-startup-process-death.py", phase != null)
        require(phase == "seed" || phase == "recover")
        val run = checkNotNull(arguments.getString("nuxie_process_run"))
        require(run.matches(Regex("[a-f0-9-]{36}")))
        val boundary = arguments.getString("nuxie_process_boundary") ?: "pending-profile"
        require(boundary in listOf("pending-profile", "active-screen", "parked"))
        exercise(eventEntry = true, processPhase = phase, processRun = run, processBoundary = boundary)
    }

    private fun exercise(
        eventEntry: Boolean,
        backgroundDuringRefresh: Boolean = false,
        holdInitialProfile: Boolean = true,
        deliveryRecovery: Boolean = false,
        processPhase: String? = null,
        processRun: String? = null,
        processBoundary: String = "pending-profile",
    ) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val fixture = if (processBoundary == "parked") "journeys/rendered-startup-parked" else if (eventEntry) "journeys/rendered-startup-event" else "journeys/rendered-screen-control"
        fun read(path: String) = instrumentation.context.assets.open("$fixture/$path").use { it.readBytes() }
        val entry = Json.parseToJsonElement(read("release-entry.json").decodeToString()).jsonObject
        val locator = entry.getValue("locator").jsonObject
        val envelope = entry.getValue("envelope").jsonObject
        val descriptor = Json.parseToJsonElement(android.util.Base64.decode(
            envelope.getValue("descriptorBytesBase64").jsonPrimitive.content, android.util.Base64.NO_WRAP,
        ).decodeToString()).jsonObject
        val render = descriptor.getValue("render").jsonObject
        val artifactKeys = (render.getValue("assets").jsonArray.map { it.jsonObject.getValue("key").jsonPrimitive.content } +
            render.getValue("nux").jsonObject.getValue("key").jsonPrimitive.content +
            descriptor.getValue("screenBehaviors").jsonArray.map {
                it.jsonObject.getValue("script").jsonObject.getValue("artifact").jsonObject.getValue("key").jsonPrimitive.content
            }).toSet()
        check(Nuxie.core == null) { "This test requires an inactive SDK." }
        // Evict only this test fixture's content-addressed objects so reruns prove download too.
        if (!(processPhase == "recover" && processBoundary != "pending-profile")) artifactKeys.forEach { key ->
            val digest = key.substringAfterLast('/').substringBefore('.')
            check(digest.matches(Regex("[a-f0-9]{64}")))
            val cached = File(context.cacheDir, "nuxie/journey_release_objects/$digest")
            check(!cached.exists() || cached.delete()) { "Could not evict the fixture object." }
        }
        val downloaded = java.util.concurrent.CopyOnWriteArrayList<String>()
        val profileEntered = CountDownLatch(1)
        val releaseProfile = CountDownLatch(1)
        val profile = profile(entry, descriptor.getValue("leg").jsonObject.getValue("entryCondition").jsonObject)
        val deliverySuspended = java.util.concurrent.atomic.AtomicBoolean(deliveryRecovery)
        val emptyProfile = JsonObject(profile + mapOf(
            "armedLegs" to JsonArray(emptyList()), "releases" to JsonArray(emptyList()),
        ))
        val directory = if (processPhase == null) {
            File(context.cacheDir, "public-startup-${UUID.randomUUID()}").apply { mkdirs() }
        } else {
            File(context.filesDir, "process-startup-$processRun").apply { mkdirs() }
        }
        val markerFile = File(directory, "ready.json")
        val previous = if (processPhase == "recover") {
            Json.parseToJsonElement(markerFile.readText()).jsonObject.also {
                assertNotEquals("Recovery must use a new OS process", android.os.Process.myPid(),
                    it.getValue("pid").jsonPrimitive.int)
                assertTrue(File(directory, "events.db").isFile)
            }
        } else null
        val transport = HttpTransport { request ->
            val path = request.url.path.trimStart('/')
            when {
                path == "profile" -> {
                    profileEntered.countDown()
                    check(releaseProfile.await(10, TimeUnit.SECONDS)) { "Initial profile release timed out." }
                    val suspended = deliverySuspended.get()
                    HttpTransport.Response(200, (if (suspended) emptyProfile else profile).toString().encodeToByteArray(), mapOf(
                        "ETag" to if (suspended) "\"empty-delivery\"" else "\"public-startup\"",
                        "Nuxie-App-Id" to locator.getValue("appId").jsonPrimitive.content,
                        "Nuxie-App-Environment" to "test",
                    ))
                }
                path in artifactKeys -> {
                    downloaded += path
                    HttpTransport.Response(200, read(path), mapOf(
                        "Content-Type" to if (path.endsWith(".nux")) "application/vnd.nuxie.scene" else "application/octet-stream",
                    ))
                }
                else -> HttpTransport.Response(503, ByteArray(0)) // Retain captured events for the oracle.
            }
        }
        val monitor = Instrumentation.ActivityMonitor(NuxieExperienceActivity::class.java.name, null, false)
        instrumentation.addMonitor(monitor)
        var host = instrumentation.startActivitySync(Intent(context, SurfaceCompatibilityHostActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        val identity = IdentityService(context).apply {
            if (previous == null) setDistinctId("public-startup-${processRun ?: UUID.randomUUID()}")
            else assertEquals(previous.getValue("owner").jsonPrimitive.content, distinctId())
        }
        Nuxie.overridesForTesting = NuxieCore.Overrides(
            identity = identity,
            transport = transport,
            eventDatabaseFile = File(directory, "events.db"),
            profileCacheDirectory = File(directory, "profiles"),
        )
        try {
            if (!holdInitialProfile) releaseProfile.countDown()
            instrumentation.runOnMainSync {
                // A credential is durably bound to one app authority; these fixtures are separate apps.
                Nuxie.setup(host, NuxieConfiguration("pk_test_public_startup_${if (processBoundary == "parked") "parked" else if (eventEntry) "event" else "foreground"}").apply {
                    environment = NuxieEnvironment.DEVELOPMENT
                    testStoreEnabled = true
                })
            }
            assertTrue("Public setup starts the initial profile request", profileEntered.await(10, TimeUnit.SECONDS))
            if (processPhase != "recover") Nuxie.trigger("startup_probe")
            val core = checkNotNull(Nuxie.core)
            val owner = Nuxie.distinctId
            runBlocking { withTimeout(10_000) { core.eventLog.awaitBarrier() } }
            if (holdInitialProfile) assertEquals(0, monitor.hits)
            if (eventEntry && holdInitialProfile) runBlocking {
                assertEquals(if (processPhase == "recover" && processBoundary != "pending-profile") 0 else 1,
                    core.store.queryPendingLocalRoutes(owner).count { it.name == "startup_probe" })
            }
            if (processPhase == "seed" && processBoundary == "pending-profile") {
                val captured = runBlocking { core.store.queryPendingLocalRoutes(owner).single { it.name == "startup_probe" } }
                val marker = buildJsonObject {
                    put("pid", android.os.Process.myPid())
                    put("owner", owner)
                    put("eventId", captured.id)
                }
                awaitProcessKill(markerFile, marker)
            }
            if (backgroundDuringRefresh) {
                assertNotNull(core.journeys.foregroundRevalidationToken())
                instrumentation.runOnMainSync { host.finish() }
                runBlocking { withTimeout(5_000) {
                    while (core.journeys.foregroundRevalidationToken() != null) delay(10)
                } }
            }
            releaseProfile.countDown()
            if (deliveryRecovery) {
                runBlocking { withTimeout(10_000) {
                    assertTrue(core.profile.refreshAndWait())
                    core.eventLog.awaitBarrier()
                    assertEquals(emptyProfile, core.profile.currentProfile()?.body)
                    assertTrue(checkNotNull(core.journeyProfiles.snapshot(owner)).profile.armedLegs.isEmpty())
                    assertTrue(core.store.pendingBatch(200).none { it.name == SystemEventNames.EXPERIENCE_SHOWN })
                } }
                assertEquals("Empty delivery must not launch an Experience", 0, monitor.hits)
                assertTrue("Empty delivery must not acquire release artifacts", downloaded.isEmpty())
                deliverySuspended.set(false)
                runBlocking { withTimeout(10_000) { assertTrue(core.profile.refreshAndWait()) } }
            }
            if (backgroundDuringRefresh) {
                runBlocking { withTimeout(10_000) {
                    core.profile.refreshAndWait()
                    core.eventLog.awaitBarrier()
                } }
                assertEquals("The stopped host cannot present after refresh", 0, monitor.hits)
                Nuxie.trigger("startup_probe")
                runBlocking { withTimeout(10_000) { core.eventLog.awaitBarrier() } }
                assertTrue(runBlocking { core.store.queryPendingLocalRoutes(owner).none { it.name == "startup_probe" } })
                host = instrumentation.startActivitySync(Intent(context, SurfaceCompatibilityHostActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                runBlocking { withTimeout(10_000) {
                    core.profile.refreshAndWait()
                    core.eventLog.awaitBarrier()
                } }
                assertEquals("A background event must not enroll on return", 0, monitor.hits)
                Nuxie.trigger("startup_probe")
            }
            if (processBoundary == "parked") {
                val journal = JourneyRunJournal(File(context.filesDir, "nuxie"), owner, JourneyStorageScope(
                    ProfileDeliveryAuthority(locator.getValue("appId").jsonPrimitive.content, "test"),
                ))
                runBlocking { withTimeout(10_000) {
                    core.profile.refreshAndWait()
                    while (journal.runs().singleOrNull()?.park == null) delay(10)
                    core.eventLog.awaitBarrier()
                } }
                val parked = journal.runs().single()
                assertNull(parked.completion)
                assertEquals("wait", parked.stepId)
                assertTrue(parked.artifactDigests.isNotEmpty())
                assertEquals(0, monitor.hits)
                if (processPhase == "seed") {
                    val trigger = runBlocking { core.store.pendingBatch(200).single { it.name == "startup_probe" } }
                    awaitProcessKill(markerFile, buildJsonObject {
                        put("pid", android.os.Process.myPid()); put("owner", owner); put("eventId", trigger.id)
                        put("runId", parked.id); put("startedEventId", parked.startedEventId)
                        put("wakeAtMillis", checkNotNull(parked.park).wakeAtMillis)
                        put("artifactDigests", JsonArray(parked.artifactDigests.sorted().map(::JsonPrimitive)))
                    })
                } else {
                    val saved = checkNotNull(previous)
                    assertEquals(saved.getValue("runId").jsonPrimitive.content, parked.id)
                    assertEquals(saved.getValue("artifactDigests"), JsonArray(parked.artifactDigests.sorted().map(::JsonPrimitive)))
                    assertEquals(saved.getValue("wakeAtMillis").jsonPrimitive.long, checkNotNull(parked.park).wakeAtMillis)
                    Nuxie.trigger("unrelated_resume_probe")
                    runBlocking { withTimeout(10_000) { core.eventLog.awaitBarrier() } }
                    assertEquals("An unrelated event must preserve the park", parked.park, journal.runs().single().park)
                    assertEquals(0, monitor.hits)
                    Nuxie.trigger("resume_probe")
                }
            }
            if (processPhase == "recover" && processBoundary == "active-screen") {
                val saved = checkNotNull(previous)
                val recoveryContract = Json.parseToJsonElement(instrumentation.context.assets
                    .open("journeys/planes/run-recovery.json").bufferedReader().use { it.readText() }).jsonObject
                    .getValue("cases").jsonArray.first { it.jsonObject.getValue("beforeDeath").jsonPrimitive.content == "executing" }.jsonObject
                runBlocking { withTimeout(15_000) {
                    core.profile.refreshAndWait()
                    while (core.store.pendingBatch(200).none { it.name == JourneyEventNames.LEG_COMPLETED }) delay(25)
                    // A second revalidation must not replay the active effect or its terminal report.
                    core.profile.refreshAndWait()
                    core.eventLog.awaitBarrier()
                    val events = core.store.pendingBatch(200)
                    val completed = events.single { it.name == JourneyEventNames.LEG_COMPLETED }
                    assertEquals(saved.getValue("completedEventId").jsonPrimitive.content, completed.id)
                    assertEquals(recoveryContract.getValue("expectedOutcome"), completed.properties.getValue("outcome"))
                    assertEquals(saved.getValue("journeyId"), completed.properties.getValue("journey_id"))
                    assertEquals("pro", completed.properties.getValue("outputs").jsonObject
                        .getValue("responses").jsonObject.getValue("selection").jsonPrimitive.content)
                    for ((name, key) in listOf("startup_probe" to "eventId", "script_control_activated" to "controlEventId",
                        JourneyEventNames.LEG_STARTED to "startedEventId")) {
                        assertEquals(saved.getValue(key).jsonPrimitive.content, events.single { it.name == name }.id)
                    }
                    assertTrue(core.store.queryPendingLocalRoutes(owner).isEmpty())
                } }
                assertEquals("Interrupted active effects must not reopen automatically", 0, monitor.hits)
            } else {
                val experience = checkNotNull(monitor.waitForActivityWithTimeout(15_000)) {
                    runBlocking {
                        val runs = JourneyRunJournal(File(context.filesDir, "nuxie"), owner, JourneyStorageScope(
                            ProfileDeliveryAuthority(locator.getValue("appId").jsonPrimitive.content, "test"),
                        )).runs()
                        "Initial profile must present; profile=${core.profile.currentProfile() != null}, " +
                            "catalog=${core.journeyProfiles.snapshot(owner) != null}, " +
                            "pending=${core.store.queryPendingLocalRoutes(owner).map { it.name }}, " +
                            "runs=${runs.map { it.stepId to it.completion }}, downloads=$downloaded"
                    }
                }
                runBlocking {
                    withTimeout(15_000) {
                        while (core.store.pendingBatch(200).none { it.name == SystemEventNames.EXPERIENCE_SHOWN }) delay(25)
                    }
                }
                var surface: ExperienceSurfaceHost? = null
                instrumentation.runOnMainSync { surface = findSurface(experience.window.decorView) }
                val target = checkNotNull(surface)
                val down = SystemClock.uptimeMillis()
                instrumentation.runOnMainSync {
                    val scale = minOf(target.width / 390f, target.height / 844f)
                    val x = (target.width - 390f * scale) / 2f + 100f * scale
                    val y = (target.height - 844f * scale) / 2f + 728f * scale
                    for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
                        val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, x, y, 0)
                        try { assertTrue(target.dispatchTouchEvent(event)) } finally { event.recycle() }
                    }
                }
                runBlocking {
                    withTimeout(10_000) {
                        while (core.store.pendingBatch(200).none { it.name == "script_control_activated" }) delay(25)
                    }
                    val events = core.store.pendingBatch(200)
                    assertEquals(if (backgroundDuringRefresh) 3 else 1, events.count { it.name == "startup_probe" && it.distinctId == owner })
                    assertEquals(1, events.count { it.name == "script_control_activated" && it.distinctId == owner })
                    previous?.let {
                        assertEquals(it.getValue("eventId").jsonPrimitive.content,
                            events.single { event -> event.name == "startup_probe" }.id)
                    }
                    assertTrue(core.store.queryPendingLocalRoutes(owner).none { it.name == "startup_probe" })
                }
                val journal = JourneyRunJournal(File(context.filesDir, "nuxie"), owner, JourneyStorageScope(
                    ProfileDeliveryAuthority(locator.getValue("appId").jsonPrimitive.content, "test"),
                ))
                assertEquals("pro", journal.runs().single().context.getValue("responses").jsonObject
                    .getValue("selection").jsonPrimitive.content)
                if (processPhase == "recover" && processBoundary == "parked") {
                    val saved = checkNotNull(previous)
                    assertEquals(saved.getValue("runId").jsonPrimitive.content, journal.runs().single().id)
                    assertNull(journal.runs().single().park)
                    val events = runBlocking { core.store.pendingBatch(200) }
                    assertEquals(saved.getValue("startedEventId").jsonPrimitive.content,
                        events.single { it.name == JourneyEventNames.LEG_STARTED }.id)
                    assertTrue(events.none { it.name == JourneyEventNames.LEG_COMPLETED })
                }
                if (!(processPhase == "recover" && processBoundary == "parked"))
                    assertTrue("Signed render and behavior artifacts must be downloaded", downloaded.toSet().containsAll(artifactKeys))
                if (processPhase == "seed" && processBoundary == "active-screen") {
                    runBlocking { withTimeout(10_000) {
                        core.eventLog.awaitBarrier()
                        while (journal.runs().single().pendingPresentationPublication != null) delay(10)
                    } }
                    val run = journal.runs().single()
                    assertNull(run.completion)
                    assertNull(run.park)
                    val events = runBlocking { core.store.pendingBatch(200) }
                    val marker = buildJsonObject {
                        put("pid", android.os.Process.myPid())
                        put("owner", owner)
                        put("eventId", events.single { it.name == "startup_probe" }.id)
                        put("controlEventId", events.single { it.name == "script_control_activated" }.id)
                        put("startedEventId", run.startedEventId)
                        put("completedEventId", run.completedEventId)
                        put("journeyId", run.journeyId)
                    }
                    awaitProcessKill(markerFile, marker)
                }
            }
        } finally {
            releaseProfile.countDown()
            try { runBlocking { withTimeout(15_000) { Nuxie.shutdownAndAwait() } } }
            finally {
                Nuxie.overridesForTesting = null
                instrumentation.removeMonitor(monitor)
                instrumentation.runOnMainSync { host.finish() }
            }
        }
        val reopened = SQLiteEventStore(context, databaseFile = File(directory, "events.db"))
        try {
            runBlocking {
                val persisted = reopened.pendingBatch(200)
                assertEquals(1, persisted.count {
                    it.name == "script_control_activated" && it.distinctId == identity.distinctId()
                })
                if (processPhase == "recover" && processBoundary == "active-screen") {
                    val completed = persisted.single { it.name == JourneyEventNames.LEG_COMPLETED }
                    assertEquals(checkNotNull(previous).getValue("completedEventId").jsonPrimitive.content, completed.id)
                    assertEquals("pro", completed.properties.getValue("outputs").jsonObject
                        .getValue("responses").jsonObject.getValue("selection").jsonPrimitive.content)
                }
            }
        } finally { runBlocking { reopened.close() } }
    }

    private fun awaitProcessKill(markerFile: File, marker: JsonObject) {
        val temporaryMarker = File(markerFile.parentFile, "ready.tmp")
        temporaryMarker.writeText(marker.toString())
        check(temporaryMarker.renameTo(markerFile))
        // The external driver kills this exact process here; no shutdown/finally is run.
        check(CountDownLatch(1).await(60, TimeUnit.SECONDS)) { "Process-death driver did not kill the seed process" }
    }

    private fun findSurface(view: View): ExperienceSurfaceHost? {
        if (view is ExperienceSurfaceHost) return view
        if (view is ViewGroup) for (index in 0 until view.childCount) {
            findSurface(view.getChildAt(index))?.let { return it }
        }
        return null
    }

    private fun profile(entry: JsonObject, entryCondition: JsonObject): JsonObject {
        val locator = entry.getValue("locator").jsonObject
        val envelope = entry.getValue("envelope").jsonObject
        fun obj(vararg fields: Pair<String, JsonElement>) = JsonObject(mapOf(*fields))
        val empty = JsonObject(emptyMap())
        return obj(
            "schemaVersion" to JsonPrimitive("nuxie.journey-plane-profile.v1"), "status" to JsonPrimitive("ok"),
            "delivery" to obj("renderBaseUrl" to JsonPrimitive("https://renders.example.com/"), "assetBaseUrl" to JsonPrimitive("https://assets.example.com/")),
            "features" to JsonArray(emptyList()), "facts" to obj("properties" to empty, "memberships" to empty, "assignments" to empty),
            "releases" to JsonArray(listOf(entry)),
            "armedLegs" to JsonArray(listOf(obj(
                "reference" to obj("experienceId" to locator.getValue("experienceId"), "versionId" to locator.getValue("experienceVersionId"),
                    "legId" to locator.getValue("legId"), "descriptorSha256" to envelope.getValue("descriptorSha256")),
                "binding" to obj("type" to JsonPrimitive("new")),
                "entryCondition" to entryCondition,
                "context" to obj("event" to empty, "responses" to empty),
            ))),
        )
    }
}
