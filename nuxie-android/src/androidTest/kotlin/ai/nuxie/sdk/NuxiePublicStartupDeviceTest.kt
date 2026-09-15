package ai.nuxie.sdk

import ai.nuxie.sdk.core.NuxieCore
import ai.nuxie.sdk.events.SystemEventNames
import ai.nuxie.sdk.events.SQLiteEventStore
import ai.nuxie.sdk.identity.IdentityService
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
    @Test fun initialProfileAdmitsASignedJourneyAndCommitsACompiledControlEvent() = exercise(eventEntry = false)

    @Test fun triggerCapturedBeforeTheInitialProfileEnrollsItsSignedJourney() = exercise(eventEntry = true)

    @Test fun backgroundDuringInitialRefreshCannotPresentOrEnrollABackgroundEvent() =
        exercise(eventEntry = true, backgroundDuringRefresh = true)

    @Test fun immediatelyAvailableProfileStillAdmitsThePublicTrigger() =
        exercise(eventEntry = true, holdInitialProfile = false)

    private fun exercise(
        eventEntry: Boolean,
        backgroundDuringRefresh: Boolean = false,
        holdInitialProfile: Boolean = true,
    ) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val fixture = if (eventEntry) "journeys/rendered-startup-event" else "journeys/rendered-screen-control"
        fun read(path: String) = instrumentation.context.assets.open("$fixture/$path").use { it.readBytes() }
        val entry = Json.parseToJsonElement(read("release-entry.json").decodeToString()).jsonObject
        val locator = entry.getValue("locator").jsonObject
        val envelope = entry.getValue("envelope").jsonObject
        val descriptor = Json.parseToJsonElement(android.util.Base64.decode(
            envelope.getValue("descriptorBytesBase64").jsonPrimitive.content, android.util.Base64.NO_WRAP,
        ).decodeToString()).jsonObject
        val render = descriptor.getValue("render").jsonObject
        val artifactKeys = (render.getValue("assets").jsonArray.map { it.jsonObject.getValue("key").jsonPrimitive.content } +
            render.getValue("riv").jsonObject.getValue("key").jsonPrimitive.content +
            descriptor.getValue("screenBehaviors").jsonArray.map {
                it.jsonObject.getValue("script").jsonObject.getValue("artifact").jsonObject.getValue("key").jsonPrimitive.content
            }).toSet()
        check(Nuxie.core == null) { "This test requires an inactive SDK." }
        // Evict only this test fixture's content-addressed objects so reruns prove download too.
        artifactKeys.forEach { key ->
            val digest = key.substringAfterLast('/').substringBefore('.')
            check(digest.matches(Regex("[a-f0-9]{64}")))
            val cached = File(context.cacheDir, "nuxie/journey_release_objects/$digest")
            check(!cached.exists() || cached.delete()) { "Could not evict the fixture object." }
        }
        val downloaded = java.util.concurrent.CopyOnWriteArrayList<String>()
        val profileEntered = CountDownLatch(1)
        val releaseProfile = CountDownLatch(1)
        val profile = profile(entry, descriptor.getValue("leg").jsonObject.getValue("entryCondition").jsonObject)
        val directory = File(context.cacheDir, "public-startup-${UUID.randomUUID()}").apply { mkdirs() }
        val transport = HttpTransport { request ->
            val path = request.url.path.trimStart('/')
            when {
                path == "profile" -> {
                    profileEntered.countDown()
                    check(releaseProfile.await(10, TimeUnit.SECONDS)) { "Initial profile release timed out." }
                    HttpTransport.Response(200, profile.toString().encodeToByteArray(), mapOf(
                        "ETag" to "\"public-startup\"",
                        "Nuxie-App-Id" to locator.getValue("appId").jsonPrimitive.content,
                        "Nuxie-App-Environment" to "test",
                    ))
                }
                path in artifactKeys -> {
                    downloaded += path
                    HttpTransport.Response(200, read(path), mapOf(
                        "Content-Type" to if (path.endsWith(".riv")) "application/vnd.rive" else "application/octet-stream",
                    ))
                }
                else -> HttpTransport.Response(503, ByteArray(0)) // Retain captured events for the oracle.
            }
        }
        val monitor = Instrumentation.ActivityMonitor(NuxieExperienceActivity::class.java.name, null, false)
        instrumentation.addMonitor(monitor)
        var host = instrumentation.startActivitySync(Intent(context, SurfaceCompatibilityHostActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        val identity = IdentityService(context).apply { setDistinctId("public-startup-${UUID.randomUUID()}") }
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
                Nuxie.setup(host, NuxieConfiguration("pk_test_public_startup_${if (eventEntry) "event" else "foreground"}").apply {
                    environment = NuxieEnvironment.DEVELOPMENT
                    testStoreEnabled = true
                })
            }
            assertTrue("Public setup starts the initial profile request", profileEntered.await(10, TimeUnit.SECONDS))
            Nuxie.trigger("startup_probe")
            val core = checkNotNull(Nuxie.core)
            val owner = Nuxie.distinctId
            runBlocking { withTimeout(10_000) { core.eventLog.awaitBarrier() } }
            if (holdInitialProfile) assertEquals(0, monitor.hits)
            if (eventEntry && holdInitialProfile) runBlocking {
                assertEquals(1, core.store.queryPendingLocalRoutes(owner).count { it.name == "startup_probe" })
            }
            if (backgroundDuringRefresh) {
                assertNotNull(core.journeys.foregroundRevalidationToken())
                instrumentation.runOnMainSync { host.finish() }
                runBlocking { withTimeout(5_000) {
                    while (core.journeys.foregroundRevalidationToken() != null) delay(10)
                } }
            }
            releaseProfile.countDown()
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
                assertTrue(core.store.queryPendingLocalRoutes(owner).none { it.name == "startup_probe" })
            }
            val journal = JourneyRunJournal(File(context.filesDir, "nuxie"), owner, JourneyStorageScope(
                ProfileDeliveryAuthority(locator.getValue("appId").jsonPrimitive.content, "test"),
            ))
            assertEquals("pro", journal.runs().single().context.getValue("responses").jsonObject
                .getValue("selection").jsonPrimitive.content)
            assertTrue("Signed render and behavior artifacts must be downloaded", downloaded.toSet().containsAll(artifactKeys))
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
                assertEquals(1, reopened.pendingBatch(200).count {
                    it.name == "script_control_activated" && it.distinctId == identity.distinctId()
                })
            }
        } finally { runBlocking { reopened.close() } }
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
