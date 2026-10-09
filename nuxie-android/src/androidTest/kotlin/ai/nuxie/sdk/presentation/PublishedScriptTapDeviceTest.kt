package ai.nuxie.sdk.presentation

import ai.nuxie.sdk.core.supportedRuntimeForEmbeddedRuntime
import ai.nuxie.sdk.events.SQLiteEventStore
import ai.nuxie.sdk.experiences.*
import ai.nuxie.sdk.identity.IdentityProvider
import ai.nuxie.sdk.journey.*
import ai.nuxie.sdk.network.HttpTransport
import ai.nuxie.sdk.network.ProfileDeliveryAuthority
import ai.nuxie.sdk.runtime.*
import android.app.Instrumentation
import android.graphics.Bitmap
import android.os.SystemClock
import android.util.Base64
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

/** The unchanged signed F.6 release enters through SDK verification and acquisition. */
class PublishedScriptTapDeviceTest {
    @Test fun publishedScriptTapIncrementsAndDrawsCount() = runBlocking {
        assertTrue("The pinned native runtime loads", NuxieRuntime.shared.isAvailable)
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        fun read(path: String) = instrumentation.context.assets.open("runtime/script-tap/$path").use { it.readBytes() }
        val entry = Json.parseToJsonElement(read("profile-entry.json").decodeToString()).jsonObject
        val locator = entry.getValue("locator").jsonObject
        val release = JourneyReleaseVerifier.authenticate(
            entry.getValue("envelope").toString().encodeToByteArray(),
            mapOf("TEST_ONLY_DEV_KEYPAIR" to Base64.decode("IVL40Zt5HSRFMkLhXy6rbLfP+ntqXtMAl5YOBpiB2xI=", Base64.NO_WRAP)),
            checkNotNull(JourneyReleaseIdentity.fromJson(locator, setOf("legId"))),
            locator.getValue("legId").jsonPrimitive.content,
            checkNotNull(supportedRuntimeForEmbeddedRuntime(nuxieRuntimeSourceRevision())),
            JourneyReleaseReplayPolicy.Active(0),
        )
        val directory = File(context.cacheDir, "f6-${System.nanoTime()}").apply { mkdirs() }
        val acquirer = JourneyReleaseArtifactAcquirer(JourneyReleaseArtifactCache(context,
            HttpTransport { request ->
                HttpTransport.Response(200, read(request.url.path.removePrefix("/")),
                    mapOf("Content-Type" to "application/vnd.nuxie.scene"))
            }, cacheDirectory = directory))
        val acquired = acquirer.acquire(release,
            JourneyReleaseDelivery("https://f6.nuxie.test/", "https://f6.nuxie.test/"))
        val owner = "f6-${System.nanoTime()}"
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val store = SQLiteEventStore(context, databaseFile = File(directory, "events.db"))
        val presentations = ExperiencePresentationService(context, { _, _, _ -> }, scope,
            { NuxieRuntime.shared.isAvailable }, currentDistinctId = { owner })
        val monitor = Instrumentation.ActivityMonitor(NuxieExperienceActivity::class.java.name, null, false)
        instrumentation.addMonitor(monitor)
        val revealed = LinkedBlockingQueue<String>()
        val keys = mapOf("TEST_ONLY_DEV_KEYPAIR" to Base64.decode("IVL40Zt5HSRFMkLhXy6rbLfP+ntqXtMAl5YOBpiB2xI=", Base64.NO_WRAP))
        val catalog = JourneyProfileCatalog(keys, JourneyReleaseHighWaterStore(context)) {
            checkNotNull(supportedRuntimeForEmbeddedRuntime(nuxieRuntimeSourceRevision()))
        }
        val authority = ProfileDeliveryAuthority(locator.getValue("appId").jsonPrimitive.content,
            locator.getValue("environment").jsonPrimitive.content)
        // Only the unsigned profile wrapper is constructed here; the signed entry is unchanged.
        val profile = buildJsonObject {
            put("schemaVersion", "nuxie.journey-plane-profile.v2"); put("status", "ok")
            putJsonObject("delivery") { put("renderBaseUrl", "https://f6.nuxie.test/"); put("assetBaseUrl", "https://f6.nuxie.test/") }
            putJsonArray("features") {}
            putJsonObject("facts") { putJsonObject("properties") {}; putJsonObject("memberships") {}; putJsonObject("assignments") {} }
            put("releases", JsonArray(listOf(entry)))
            putJsonArray("armedLegs") { addJsonObject {
                putJsonObject("reference") {
                    put("experienceId", locator.getValue("experienceId")); put("versionId", locator.getValue("experienceVersionId"))
                    put("legId", locator.getValue("legId")); put("descriptorSha256", entry.getValue("envelope").jsonObject.getValue("descriptorSha256"))
                }
                putJsonObject("binding") { put("type", "new") }
                putJsonObject("entryCondition") { put("type", "app_foregrounded") }
                putJsonObject("context") { putJsonObject("event") {}; putJsonObject("responses") {} }
            } }
        }
        val presenter = object : JourneyPresenting {
            override fun reserve(ownerDistinctId: String) = presentations.reserveJourney(ownerDistinctId)
            override fun owns(owner: JourneyPresentationOwner) = presentations.ownsJourney(owner)
            override fun screenId(owner: JourneyPresentationOwner) = presentations.journeyScreenId(owner)
            override suspend fun openLink(owner: JourneyPresentationOwner, link: JourneyLinkRequest) = presentations.openJourneyLink(owner, link)
            override suspend fun shutdownOwnedBy(ownerDistinctId: String) = presentations.shutdownOwnedBy(ownerDistinctId)
            override suspend fun shutdownPresentation(ownerDistinctId: String, journeyId: String) = presentations.shutdownJourney(ownerDistinctId, journeyId)
            override suspend fun present(value: JourneyPresentationRequest): JourneyPresentationResult {
                assertEquals("scr_screens_stap", value.screenId)
                presentations.presentJourney(value.fences, value.release, value.screenId, value.journeyId,
                    value.ownerDistinctId, value.reservation, value.canPresent, acquire = { acquired },
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
                store.insertPendingIfAbsent(ai.nuxie.sdk.events.StoredEvent(eventId, name,
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
            }
            val activity = checkNotNull(monitor.waitForActivityWithTimeout(20_000))
            assertEquals("scr_screens_stap", revealed.poll(30, TimeUnit.SECONDS))
            var found: ExperienceSurfaceHost? = null
            instrumentation.runOnMainSync {
                fun find(view: View): ExperienceSurfaceHost? = when (view) {
                    is ExperienceSurfaceHost -> view.takeIf { it.isShown }
                    is ViewGroup -> (0 until view.childCount).firstNotNullOfOrNull { find(view.getChildAt(it)) }
                    else -> null
                }
                found = find(activity.window.decorView)
            }
            val surface = checkNotNull(found)
            val lane = ExperienceSurfaceHost::class.java.getDeclaredField("lane")
                .apply { isAccessible = true }.get(surface) as NuxieRuntimeLane
            // Read the mounted native artboard, never a separately constructed value instance.
            suspend fun count() = lane.call {
                val artboard = ExperienceSurfaceHost::class.java.getDeclaredField("artboard")
                    .apply { isAccessible = true }.get(surface) as NuxieRuntimeArtboard
                checkNotNull(artboard.defaultViewModelSnapshot()).resolveScalar(listOf("state", "count"))
            }
            var previousLabelPixels: IntArray? = null
            fun captureCount(expected: Int) {
                val deadline = SystemClock.uptimeMillis() + 10_000
                var captured = false
                while (!captured && SystemClock.uptimeMillis() < deadline) {
                    instrumentation.runOnMainSync {
                        val bitmap = surface.bitmap ?: return@runOnMainSync
                        try {
                            val density = surface.resources.displayMetrics.density
                            // Hand-authored count box: x20/y120, width160/height40.
                            val width = (160 * density).toInt()
                            val height = (40 * density).toInt()
                            val pixels = IntArray(width * height)
                            bitmap.getPixels(pixels, 0, width, (20 * density).toInt(), (120 * density).toInt(), width, height)
                            val white = 0xffffffff.toInt()
                            val previous = previousLabelPixels
                            if (pixels.any { it == white } && pixels.any { it != white } && (previous == null || !pixels.contentEquals(previous))) {
                                // Gradle retrieves this directory before uninstalling the test APK.
                                val output = InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")
                                    ?.let { File(it, "f6-script-tap") }
                                    ?: File(context.getExternalFilesDir(null), "f6-script-tap")
                                output.mkdirs()
                                File(output, "count-$expected.png").outputStream().use {
                                    assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
                                }
                                previousLabelPixels = pixels
                                captured = true
                            }
                        } finally { bitmap.recycle() }
                    }
                    if (!captured) SystemClock.sleep(20)
                }
                assertTrue("The count label is presented and redraws for $expected", captured)
            }
            assertEquals(NuxieViewModelScalarValue.NumberValue(0.0), count())
            captureCount(0)
            for (expected in listOf(1.0, 2.0, 3.0)) {
                instrumentation.runOnMainSync {
                    val density = surface.resources.displayMetrics.density
                    val now = SystemClock.uptimeMillis()
                    for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
                        val event = MotionEvent.obtain(now, now + 10, action, 100f * density, 50f * density, 0)
                        try { assertTrue(surface.dispatchTouchEvent(event)) } finally { event.recycle() }
                    }
                }
                val wanted = NuxieViewModelScalarValue.NumberValue(expected)
                val deadline = SystemClock.uptimeMillis() + 10_000
                while (count() != wanted && SystemClock.uptimeMillis() < deadline) {
                    SystemClock.sleep(20)
                }
                assertEquals("Tap must run the published Luau action", wanted, count())
                SystemClock.sleep(100)
                captureCount(expected.toInt())
            }
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
}
