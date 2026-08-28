package ai.nuxie.sdk.presentation

import ai.nuxie.sdk.LogLevel
import ai.nuxie.sdk.NuxieEnvironment
import ai.nuxie.sdk.events.EventLog
import ai.nuxie.sdk.events.EventStore
import ai.nuxie.sdk.events.JsonValueConverter
import ai.nuxie.sdk.events.NuxieContextBuilder
import ai.nuxie.sdk.events.StoredEvent
import ai.nuxie.sdk.events.SystemEventNames
import ai.nuxie.sdk.experiences.AcquiredRelease
import ai.nuxie.sdk.experiences.AuthenticatedRelease
import ai.nuxie.sdk.experiences.Delivery
import ai.nuxie.sdk.experiences.ExperienceReleaseIdentity
import ai.nuxie.sdk.fixtures.FixtureRunner
import ai.nuxie.sdk.identity.IdentityProvider
import ai.nuxie.sdk.journey.JourneyEventNames
import ai.nuxie.sdk.journey.JourneyLedger
import ai.nuxie.sdk.journey.JourneyPlane
import ai.nuxie.sdk.journey.JourneyReleaseProvider
import ai.nuxie.sdk.journey.JourneyRun
import ai.nuxie.sdk.journey.JourneyRunState
import ai.nuxie.sdk.journey.JourneyService
import ai.nuxie.sdk.journey.JourneyStore
import ai.nuxie.sdk.util.IsoDates
import java.io.Closeable
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class DismissalFixtureTest {
    private class Identity(private var id: String) : IdentityProvider {
        override fun distinctId() = id
        override fun anonymousId() = id
        override fun rawDistinctId(): String? = id
        override val isIdentified = true

        fun changeTo(distinctId: String) {
            id = distinctId
        }
    }

    private class Store : EventStore {
        val events = linkedMapOf<String, StoredEvent>()

        override suspend fun insertPending(event: StoredEvent) {
            check(events.putIfAbsent(event.id, event) == null)
        }

        override suspend fun insertPendingIfAbsent(event: StoredEvent): Boolean =
            events.putIfAbsent(event.id, event) == null

        override suspend fun hasStableOutcome(eventId: String) = eventId in events
        override suspend fun insertDeliveredIfAbsent(event: StoredEvent) = false
        override suspend fun markDelivered(ids: List<String>) = Unit
        override suspend fun hasEvent(name: String, distinctId: String, sinceMillis: Long?) = false
        override suspend fun countEvents(
            name: String,
            distinctId: String,
            sinceMillis: Long?,
            untilMillis: Long?,
        ) = 0
        override suspend fun getFirstEventTime(
            name: String,
            distinctId: String,
            sinceMillis: Long?,
            untilMillis: Long?,
        ): Long? = null
        override suspend fun getLastEventTime(
            name: String,
            distinctId: String,
            sinceMillis: Long?,
            untilMillis: Long?,
        ): Long? = null
        override suspend fun querySessionEvents(sessionId: String) = emptyList<StoredEvent>()
        override suspend fun reassignEvents(from: String, to: String) = 0
        override suspend fun deleteOldestDeliveredEvents(keeping: Int) = 0
        override suspend fun recordStableDrop(eventId: String, recordedAtMillis: Long) = true
        override suspend fun pendingBatch(limit: Int) = events.values.take(limit)
        override suspend fun close() = Unit
    }

    @Test
    fun hostFixtureRunsThroughJourneyAndPresentationSeams() {
        val fixtureFile = File(FixtureRunner.fixturesRoot(), FIXTURE_PATH)
        val fixture = Json.parseToJsonElement(fixtureFile.readText()).jsonObject
        val nowMillis = requireNotNull(IsoDates.parseMillis(fixture.getValue("now").jsonPrimitive.content))
        val journeyId = fixture.getValue("journeyId").jsonPrimitive.content
        val epoch = fixture.getValue("epoch").jsonPrimitive.long

        FixtureRunner.run(FIXTURE_PATH, "journeys/dismissal") { vector ->
            runBlocking {
                runVector(vector.body, nowMillis, journeyId, epoch)
            }
        }
    }

    @Test
    fun hostDismissedFactKeepsPresentationOwnerAcrossIdentityChange() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            runBlocking {
                val ownerDistinctId = "owner-customer"
                val identity = Identity(ownerDistinctId)
                val eventStore = Store()
                val eventLog = EventLog(
                    store = eventStore,
                    contextBuilder = NuxieContextBuilder(
                        RuntimeEnvironment.getApplication(),
                        NuxieEnvironment.DEVELOPMENT,
                        LogLevel.NONE,
                        identity,
                    ),
                    identity = identity,
                    beforeSend = null,
                    scope = scope,
                )
                val launched = mutableListOf<String>()
                val presentations = ExperiencePresentationService(
                    releases = PresentationReleaseProvider { release() },
                    acquire = { acquired() },
                    emit = eventLog::capture,
                    scope = scope,
                    runtimeAvailable = { true },
                    launch = launched::add,
                )
                val shown = scope.async {
                    presentations.present(
                        "flow-version-1",
                        "journey-1",
                        ownerDistinctId,
                    )
                }
                PresentationRegistry.reportFirstFrame(launched.single())
                shown.await()
                eventLog.awaitBarrier()

                identity.changeTo("current-customer")
                presentations.dismissFromHost(ownerDistinctId)
                eventLog.awaitBarrier()

                val dismissed = eventStore.events.values.single {
                    it.name == SystemEventNames.EXPERIENCE_DISMISSED
                }
                assertEquals(ownerDistinctId, dismissed.distinctId)
            }
        } finally {
            scope.cancel()
            PresentationRegistry.clearForTesting()
        }
    }

    private suspend fun runVector(
        vector: JsonObject,
        nowMillis: Long,
        journeyId: String,
        epoch: Long,
    ) {
        val root = createTempDirectory("nuxie-dismiss-fixture-").toFile()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val distinctId = "fixture-customer"
            val identity = Identity(distinctId)
            val eventStore = Store()
            val contextBuilder = NuxieContextBuilder(
                RuntimeEnvironment.getApplication(),
                NuxieEnvironment.DEVELOPMENT,
                LogLevel.NONE,
                identity,
            )
            val eventLog = EventLog(
                store = eventStore,
                contextBuilder = contextBuilder,
                identity = identity,
                beforeSend = null,
                scope = scope,
                nowMillis = { nowMillis },
            )
            val runStore = JourneyStore(root)
            runStore.save(
                JourneyRun(
                    id = journeyId,
                    distinctId = distinctId,
                    experienceId = "experience-1",
                    experienceVersion = "flow-version-1",
                    epoch = epoch,
                    plane = JourneyPlane.DEVICE,
                    settingsSnapshot = JsonObject(emptyMap()),
                    state = JourneyRunState.ACTIVE,
                ),
            )
            check(runStore.load(distinctId, journeyId) != null) { "fixture Journey did not persist" }
            val journeys = JourneyService(
                store = runStore,
                ledger = JourneyLedger(eventLog),
                releases = JourneyReleaseProvider { _, _ -> emptyList() },
                nowMillis = { nowMillis },
            )
            val launched = mutableListOf<String>()
            var semanticFailure: Throwable? = null
            val semanticCompletion = CompletableDeferred<Unit>()
            val presentations = ExperiencePresentationService(
                releases = PresentationReleaseProvider { release() },
                acquire = { acquired() },
                emit = { _, _, _ -> },
                scope = scope,
                runtimeAvailable = { true },
                launch = launched::add,
                reportOutcome = { outcome ->
                    runCatching {
                        journeys.presentationEnded(
                            outcome.ownerDistinctId ?: distinctId,
                            requireNotNull(outcome.ref.journeyId),
                            outcome.reason,
                        )
                    }.onFailure { semanticFailure = it }
                        .also { semanticCompletion.complete(Unit) }
                        .getOrThrow()
                },
                reserveHostDismissal = { outcome ->
                    journeys.reserveHostDismissal(
                        ownerDistinctId = requireNotNull(outcome.ownerDistinctId),
                        journeyId = requireNotNull(outcome.ref.journeyId),
                        initiatingDistinctId = requireNotNull(outcome.initiatingDistinctId),
                    )
                },
                releaseHostDismissalReservation = { outcome ->
                    journeys.releaseHostDismissalReservation(
                        ownerDistinctId = requireNotNull(outcome.ownerDistinctId),
                        journeyId = requireNotNull(outcome.ref.journeyId),
                    )
                },
            )

            val shown = scope.async {
                presentations.present("flow-version-1", journeyId, distinctId)
            }
            PresentationRegistry.reportFirstFrame(launched.single())
            shown.await()
            when (vector.getValue("dismissedBy").jsonPrimitive.content) {
                "host" -> presentations.dismissFromHost(distinctId)
                "user" -> {
                    presentations.dismiss(CloseReason.UserDismissed)
                    semanticCompletion.await()
                }
                else -> error("unknown dismissal source")
            }
            semanticFailure?.let { throw it }

            val expected = vector.getValue("expected").jsonObject
            val exit = eventStore.events.values.singleOrNull { it.name == JourneyEventNames.EXITED }
                ?: error(
                    "no Journey exit for ${vector.getValue("dismissedBy")}; " +
                        "captured=${eventStore.events.values.map(StoredEvent::name)}",
                )
            // Rebuild known SDK context around the fixture payload, then compare the full maps.
            val expectedProperties = JsonValueConverter.fromMap(
                contextBuilder.buildEnrichedProperties(JsonValueConverter.toNativeMap(expected)),
            )
            assertEquals(expectedProperties, exit.properties)
        } finally {
            scope.cancel()
            PresentationRegistry.clearForTesting()
            root.deleteRecursively()
        }
    }

    private fun release(): PresentationRelease {
        val identity = ExperienceReleaseIdentity(
            appId = "app",
            environment = "development",
            experienceId = "experience-1",
            experienceVersionId = "flow-version-1",
            buildId = "build",
            versionNumber = 1,
            publishedAt = "2026-08-23T00:00:00Z",
            publishedAtSeq = 1,
        )
        val descriptor = buildJsonObject {
            put("render", buildJsonObject { put("assets", buildJsonArray {}) })
            put("screenBehaviors", JsonArray(emptyList()))
        }
        return PresentationRelease(
            AuthenticatedRelease("key", "sha", identity, ByteArray(0), descriptor, 1),
            Delivery("https://render.example/", "https://assets.example/"),
        )
    }

    private fun acquired(): AcquiredRelease {
        val riv = File.createTempFile("dismiss-fixture-", ".riv").apply {
            writeBytes(byteArrayOf(1))
            deleteOnExit()
        }
        return AcquiredRelease(
            identity = ExperienceReleaseIdentity(
                "app",
                "development",
                "experience-1",
                "flow-version-1",
                "build",
                1,
                "2026-08-23T00:00:00Z",
                1,
            ),
            artifactsByKey = mapOf("renders/main.riv" to riv),
            rivFile = riv,
            protection = Closeable {},
        )
    }

    private companion object {
        const val FIXTURE_PATH = "journeys/dismissal/host.json"
    }
}
