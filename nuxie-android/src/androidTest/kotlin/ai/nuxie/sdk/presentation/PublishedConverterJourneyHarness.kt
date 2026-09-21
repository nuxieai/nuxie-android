package ai.nuxie.sdk.presentation

import ai.nuxie.sdk.core.supportedRuntimeForEmbeddedRuntime
import ai.nuxie.sdk.events.SQLiteEventStore
import ai.nuxie.sdk.experiences.JourneyProfileCatalog
import ai.nuxie.sdk.experiences.JourneyReleaseHighWaterStore
import ai.nuxie.sdk.identity.IdentityProvider
import ai.nuxie.sdk.journey.JourneyRunJournal
import ai.nuxie.sdk.journey.JourneyService
import ai.nuxie.sdk.journey.JourneyStorageScope
import ai.nuxie.sdk.network.ProfileDeliveryAuthority
import ai.nuxie.sdk.runtime.NuxiePlayerStepOutcome
import ai.nuxie.sdk.runtime.NuxieViewModelSnapshot
import ai.nuxie.sdk.runtime.nuxieRuntimeSourceRevision
import android.content.Context
import android.util.Base64
import java.io.Closeable
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*

/** Real admission, emission coordination, service, and disk journal around a mounted device screen. */
internal class PublishedConverterJourneyHarness(
    context: Context,
    private val directory: File,
    entry: JsonObject,
) : Closeable {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val store = SQLiteEventStore(context, databaseFile = File(directory, "events.db"))
    private val customer = directory.name
    private val locator = entry.getValue("locator").jsonObject
    private val authority = ProfileDeliveryAuthority(locator.getValue("appId").jsonPrimitive.content,
        locator.getValue("environment").jsonPrimitive.content)
    private val events = CopyOnWriteArrayList<String>()
    private val catalog = JourneyProfileCatalog(mapOf("TEST_ONLY_DEV_KEYPAIR" to Base64.decode(
        "IVL40Zt5HSRFMkLhXy6rbLfP+ntqXtMAl5YOBpiB2xI=", Base64.NO_WRAP)), JourneyReleaseHighWaterStore(context)) {
        supportedRuntimeForEmbeddedRuntime(nuxieRuntimeSourceRevision())
    }
    private val presenter = object : JourneyPresenting {
        var request: JourneyPresentationRequest? = null
        override fun reserve(ownerDistinctId: String) = object : JourneyPresentationReservation {
            override fun close() = Unit
        }
        override suspend fun present(request: JourneyPresentationRequest): JourneyPresentationResult {
            check(request.canPresent())
            this.request = request
            return JourneyPresentationResult.Shown
        }
        override fun owns(owner: JourneyPresentationOwner) = request?.let {
            it.journeyId == owner.journeyId && it.ownerDistinctId == owner.distinctId
        } == true
        override fun screenId(owner: JourneyPresentationOwner) = request?.screenId
        override suspend fun shutdownOwnedBy(ownerDistinctId: String) = Unit
    }
    private val service = JourneyService(
        identity = object : IdentityProvider {
            override fun distinctId() = customer
            override fun anonymousId() = customer
            override fun rawDistinctId(): String? = null
            override val isIdentified = false
        }, events = store, catalog = catalog, journalDirectory = directory, scope = scope,
        capture = { name, _, _, _ -> events += name; true }, presenter = presenter)
    private val coordinator: JourneyRuntimeEmissionCoordinator

    init {
        val request = runBlocking {
            val profile = buildJsonObject {
                put("schemaVersion", "nuxie.journey-plane-profile.v1"); put("status", "ok")
                putJsonObject("delivery") {
                    put("renderBaseUrl", "https://renders.example.com/")
                    put("assetBaseUrl", "https://assets.example.com/")
                }
                putJsonArray("features") {}
                putJsonObject("facts") {
                    putJsonObject("properties") {}; putJsonObject("memberships") {}; putJsonObject("assignments") {}
                }
                put("releases", JsonArray(listOf(entry)))
                putJsonArray("armedLegs") {
                    addJsonObject {
                        putJsonObject("reference") {
                            put("experienceId", locator.getValue("experienceId"))
                            put("versionId", locator.getValue("experienceVersionId"))
                            put("legId", locator.getValue("legId"))
                            put("descriptorSha256", entry.getValue("envelope").jsonObject.getValue("descriptorSha256"))
                        }
                        putJsonObject("binding") { put("type", "new") }
                        putJsonObject("entryCondition") { put("type", "app_foregrounded") }
                        putJsonObject("context") { putJsonObject("event") {}; putJsonObject("responses") {} }
                    }
                }
            }
            catalog.commit(customer, catalog.prepare(profile, authority))
            service.initialize()
            service.onAppWillEnterForeground()
            service.profileDidCommit(checkNotNull(catalog.snapshot(customer)), authority, customer, 1)
            checkNotNull(presenter.request)
        }
        coordinator = JourneyRuntimeEmissionCoordinator(
            journeyId = request.journeyId, screenId = request.screenId, descriptor = request.release.descriptor,
            nextBatchSequence = request.nextBatchSequence, nextEmissionSequence = request.nextEmissionSequence,
            onEmissionBatch = request.onEmissionBatch, onScreenChanged = request.onScreenChanged,
            onPresentationRevealed = request.onPresentationRevealed)
        runBlocking { check(coordinator.reveal()) }
    }

    fun publish(outcome: NuxiePlayerStepOutcome, correlationId: ULong, snapshot: NuxieViewModelSnapshot?) {
        runBlocking { check(coordinator.publish(outcome, correlationId, snapshot = snapshot)) }
    }

    fun responses(): JsonObject = JourneyRunJournal(directory, customer, JourneyStorageScope(authority))
        .runs().single().context.getValue("responses").jsonObject

    fun submissions(): Int = events.count { it == "duration_ready" }

    override fun close() {
        runBlocking { service.onAppDidEnterBackground(); store.close() }
        scope.cancel()
    }
}
