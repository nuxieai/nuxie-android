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
import android.os.SystemClock
import android.util.Base64
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

/** Published Parla routes through the real Journey executor and native stacked windows. */
class PublishedParlaSheetJourneyDeviceTest {
    @Test fun doneClosesSavedAndRevealsTheSameActiveOfferOnce() = runBlocking {
        assertTrue("The pinned native runtime loads", NuxieRuntime.shared.isAvailable)
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        fun read(path: String) = instrumentation.context.assets.open("journeys/parla-sheets/$path").use { it.readBytes() }
        val entry = Json.parseToJsonElement(read("release-entry.json").decodeToString()).jsonObject
        val locator = entry.getValue("locator").jsonObject
        val release = JourneyReleaseVerifier.authenticate(
            entry.getValue("envelope").toString().encodeToByteArray(),
            mapOf("TEST_ONLY_DEV_KEYPAIR" to Base64.decode("IVL40Zt5HSRFMkLhXy6rbLfP+ntqXtMAl5YOBpiB2xI=", Base64.NO_WRAP)),
            checkNotNull(JourneyReleaseIdentity.fromJson(locator, setOf("legId"))),
            locator.getValue("legId").jsonPrimitive.content,
            checkNotNull(supportedRuntimeForEmbeddedRuntime(nuxieRuntimeSourceRevision())),
            JourneyReleaseReplayPolicy.Active(0),
        )
        val directory = File(context.cacheDir, "parla-${System.nanoTime()}").apply { mkdirs() }
        val acquirer = JourneyReleaseArtifactAcquirer(JourneyReleaseArtifactCache(context,
            HttpTransport { request ->
                HttpTransport.Response(200, read(request.url.path.removePrefix("/")),
                    mapOf("Content-Type" to "application/vnd.nuxie.scene"))
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
        val dismissedScreens = LinkedBlockingQueue<Triple<String, String?, String>>()
        val keys = mapOf("TEST_ONLY_DEV_KEYPAIR" to Base64.decode("IVL40Zt5HSRFMkLhXy6rbLfP+ntqXtMAl5YOBpiB2xI=", Base64.NO_WRAP))
        val catalog = JourneyProfileCatalog(keys, JourneyReleaseHighWaterStore(context)) {
            checkNotNull(supportedRuntimeForEmbeddedRuntime(nuxieRuntimeSourceRevision()))
        }
        val authority = ProfileDeliveryAuthority(locator.getValue("appId").jsonPrimitive.content,
            locator.getValue("environment").jsonPrimitive.content)
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
                putJsonObject("entryCondition") { put("type", "app_foregrounded") }
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
                        dismissedScreens.add(Triple(hidden, shown, method))
                        value.onScreenDismissed(hidden, shown, method)
                    },
                    onLinkOpened = value.onLinkOpened, onEmissionBatch = value.onEmissionBatch,
                    onPresentationRevealed = { value.onPresentationRevealed(it); revealed.add(it) },
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
            val lessonId = "scr_screens_slesson"
            val offerId = "scr_screens_soffer"
            val savedId = "scr_screens_ssaved"
            assertEquals(lessonId, revealed.poll(30, TimeUnit.SECONDS))
            fun field(owner: Any, name: String): Any = checkNotNull(owner.javaClass.getDeclaredField(name)
                .apply { isAccessible = true }.get(owner))
            fun layers(): List<*> = field(activity, "layers") as List<*>
            fun prepared(layer: Any) = field(field(layer, "screen"), "prepared") as PreparedPresentation
            fun window(layer: Any) = field(layer, "sheet") as ExperienceNativeLayerWindow
            fun assertActive(content: PreparedPresentation) {
                assertEquals(NuxieViewModelScalarValue.StringValue("active"), content.screenLifecycle.snapshot()["screen/phase"])
            }
            fun tap(label: String) {
                val deadline = SystemClock.uptimeMillis() + 10_000
                var button: android.view.accessibility.AccessibilityNodeInfo? = null
                while (button == null && SystemClock.uptimeMillis() < deadline) {
                    button = instrumentation.uiAutomation.rootInActiveWindow?.findAccessibilityNodeInfosByText(label)
                        ?.singleOrNull { it.text?.toString() == label && it.isClickable }
                    if (button == null) SystemClock.sleep(20)
                }
                assertTrue("Published authored control $label", checkNotNull(button).performAction(
                    android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK))
            }
            var lesson: PreparedPresentation? = null
            instrumentation.runOnMainSync { lesson = prepared(checkNotNull(layers().single())) }
            tap("Explore Parla Plus")
            assertEquals(offerId, revealed.poll(30, TimeUnit.SECONDS))
            var offerLayer: Any? = null
            var offer: PreparedPresentation? = null
            instrumentation.runOnMainSync {
                offerLayer = layers().last()
                offer = prepared(checkNotNull(offerLayer))
                assertActive(checkNotNull(lesson))
                assertActive(checkNotNull(offer))
                assertEquals(PresentationShell.Sheet(PresentationShell.Sheet.Detent.MEDIUM, true), offer!!.shell)
            }
            val appearances = offer!!.screenLifecycle.snapshot()["screen/appearances"]
            tap("Save this offer")
            assertEquals(savedId, revealed.poll(30, TimeUnit.SECONDS))
            var savedWindow: ExperienceNativeLayerWindow? = null
            instrumentation.runOnMainSync {
                assertEquals(3, layers().size)
                savedWindow = window(checkNotNull(layers().last()))
                assertTrue(prepared(checkNotNull(layers().last())).fitsContentHeight)
                assertActive(checkNotNull(offer))
                assertActive(checkNotNull(lesson))
            }
            assertTrue("Covering is not a hidden lifecycle edge", dismissedScreens.isEmpty())
            tap("Done")
            assertEquals(offerId, revealed.poll(30, TimeUnit.SECONDS))
            assertEquals(Triple(savedId, offerId, "journey"), dismissedScreens.poll(10, TimeUnit.SECONDS))
            assertNull("Offer is revealed exactly once", revealed.poll(500, TimeUnit.MILLISECONDS))
            instrumentation.runOnMainSync {
                assertEquals(2, layers().size)
                assertSame(offerLayer, layers().last())
                assertSame(offer, prepared(checkNotNull(layers().last())))
                assertFalse(savedWindow!!.dialog.isShowing)
                assertTrue(window(checkNotNull(offerLayer)).dialog.isShowing)
                assertActive(checkNotNull(offer))
                assertEquals(appearances, offer!!.screenLifecycle.snapshot()["screen/appearances"])
                assertFalse(activity.isFinishing)
            }
            // A second Save proves Done did not complete or abandon the run.
            tap("Save this offer")
            assertEquals(savedId, revealed.poll(30, TimeUnit.SECONDS))
            tap("Done")
            assertEquals(offerId, revealed.poll(30, TimeUnit.SECONDS))
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
