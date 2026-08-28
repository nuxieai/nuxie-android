package ai.nuxie.sdk.journey

import ai.nuxie.sdk.ExperienceRef
import ai.nuxie.sdk.LogLevel
import ai.nuxie.sdk.NuxieActivity
import ai.nuxie.sdk.NuxieEnvironment
import ai.nuxie.sdk.NuxieActivityInfo
import ai.nuxie.sdk.events.ActivityForwarder
import ai.nuxie.sdk.events.EventLog
import ai.nuxie.sdk.events.EventStore
import ai.nuxie.sdk.events.NuxieContextBuilder
import ai.nuxie.sdk.events.StoredEvent
import ai.nuxie.sdk.experiences.AuthenticatedRelease
import ai.nuxie.sdk.experiences.ExperienceReleaseIdentity
import ai.nuxie.sdk.experiences.ReleaseHighWaterStore
import ai.nuxie.sdk.experiences.SupportedRuntime
import ai.nuxie.sdk.identity.IdentityProvider
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class JourneyServiceTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var now = 1_784_462_400_000L

    private class Identity : IdentityProvider {
        override fun distinctId() = "customer-1"
        override fun anonymousId() = "customer-1"
        override fun rawDistinctId(): String? = null
        override val isIdentified = false
    }

    private class Store : EventStore {
        val events = linkedMapOf<String, StoredEvent>()
        val delivered = mutableSetOf<String>()
        var failNextPendingInsert = false
        var serverFactInsertStarted: CompletableDeferred<Unit>? = null
        var serverFactInsertGate: CompletableDeferred<Unit>? = null
        override suspend fun insertPending(event: StoredEvent) {
            if (failNextPendingInsert) {
                failNextPendingInsert = false
                error("pending insert failed")
            }
            check(events.putIfAbsent(event.id, event) == null) { "duplicate event" }
        }
        override suspend fun insertDeliveredIfAbsent(event: StoredEvent): Boolean {
            serverFactInsertStarted?.complete(Unit)
            serverFactInsertGate?.await()
            if (events.putIfAbsent(event.id, event) != null) return false
            delivered += event.id
            return true
        }
        override suspend fun markDelivered(ids: List<String>) { delivered += ids }
        override suspend fun hasEvent(name: String, distinctId: String, sinceMillis: Long?) = false
        override suspend fun countEvents(name: String, distinctId: String, sinceMillis: Long?, untilMillis: Long?) = 0
        override suspend fun getFirstEventTime(name: String, distinctId: String, sinceMillis: Long?, untilMillis: Long?) = null
        override suspend fun getLastEventTime(name: String, distinctId: String, sinceMillis: Long?, untilMillis: Long?) = null
        override suspend fun querySessionEvents(sessionId: String) = emptyList<StoredEvent>()
        override suspend fun reassignEvents(from: String, to: String) = 0
        override suspend fun deleteOldestDeliveredEvents(keeping: Int) = 0
        override suspend fun recordStableDrop(eventId: String, recordedAtMillis: Long) = true
        override suspend fun pendingBatch(limit: Int) = events.values.filterNot { it.id in delivered }.take(limit)
        override suspend fun close() = Unit
    }

    private data class Harness(val root: File, val store: Store, val log: EventLog, val service: JourneyService)

    private fun harness(
        reentry: JourneyReentry = JourneyReentry.EveryTime,
        forwardingEnabled: () -> Boolean = { false },
    ): Harness {
        val root = createTempDir(prefix = "nuxie-journey-")
        val eventStore = Store()
        val identity = Identity()
        val eventLog = EventLog(
            store = eventStore,
            contextBuilder = NuxieContextBuilder(RuntimeEnvironment.getApplication(), NuxieEnvironment.DEVELOPMENT, LogLevel.NONE, identity),
            identity = identity,
            beforeSend = null,
            scope = scope,
            nowMillis = { now },
            forwardingEnabled = forwardingEnabled,
        )
        val release = AdmittedJourneyRelease(
            experienceId = "experience-1",
            experienceVersion = "version-1",
            triggerEventName = "opened",
            reentry = reentry,
            settingsTemplate = buildJsonObject {
                put("goal", JsonPrimitive("goal"))
                put("conversion_anchor", JsonPrimitive("journey_start"))
                put("goal_window_ms", JsonPrimitive(1_000L))
                put("end_on_goal", JsonPrimitive(true))
            },
        )
        return Harness(
            root,
            eventStore,
            eventLog,
            JourneyService(
                JourneyStore(root),
                JourneyLedger(eventLog),
                JourneyReleaseProvider { _, name -> if (name == "opened") listOf(release) else emptyList() },
                { now },
            ),
        )
    }

    @After fun tearDown() = scope.cancel()

    @Test
    fun enrollmentAndFiveFactsUseTheDocumentedWireProperties() = runBlocking {
        val h = harness()
        try {
            val result = h.service.handleEventForTrigger(StoredEvent("trigger-1", "opened", timestampMillis = now, distinctId = "customer-1"))
            val id = (result.single() as ai.nuxie.sdk.events.TriggerService.JourneyTriggerResult.Started).ref.journeyId!!
            h.service.transition("customer-1", id, null, "screen-a")
            h.service.milestone("customer-1", id, "reached-a")
            val first = h.service.requestEffect("customer-1", id, "effect-a", 2, "send_push", JsonObject(emptyMap()))
            val retry = h.service.requestEffect("customer-1", id, "effect-a", 2, "send_push", JsonObject(emptyMap()))
            h.service.exit("customer-1", id, "completed")
            h.log.awaitBarrier()

            assertEquals(first, retry)
            assertEquals(
                setOf("journey_id", "epoch", "experience_id", "experience_version", "trigger_ref", "plane", "settings_snapshot"),
                h.store.events.values.first { it.name == JourneyEventNames.ENROLLED }.properties.keys.filterNot { it.startsWith("$") }.toSet(),
            )
            assertEquals(
                setOf("journey_id", "epoch", "to_node", "region", "plane"),
                h.store.events.values.first { it.name == JourneyEventNames.TRANSITION }.properties.keys.filterNot { it.startsWith("$") }.toSet(),
            )
            assertEquals(
                setOf("journey_id", "epoch", "milestone_id"),
                h.store.events.values.first { it.name == JourneyEventNames.MILESTONE }.properties.keys.filterNot { it.startsWith("$") }.toSet(),
            )
            assertEquals(
                setOf("journey_id", "epoch", "node_id", "invocation_id", "effect", "payload"),
                h.store.events.values.first { it.name == JourneyEventNames.EFFECT_REQUESTED }.properties.keys.filterNot { it.startsWith("$") }.toSet(),
            )
            assertEquals(
                setOf("journey_id", "epoch", "reason", "at"),
                h.store.events.values.first { it.name == JourneyEventNames.EXITED }.properties.keys.filterNot { it.startsWith("$") }.toSet(),
            )
        } finally { h.root.deleteRecursively() }
    }

    @Test
    fun reentryAndAlreadyActiveAdmissionsAreSuppressed() = runBlocking {
        val h = harness(JourneyReentry.OncePerWindow(1_000))
        try {
            val event = StoredEvent("trigger-1", "opened", timestampMillis = now, distinctId = "customer-1")
            val first = h.service.handleEventForTrigger(event).single() as ai.nuxie.sdk.events.TriggerService.JourneyTriggerResult.Started
            assertTrue(h.service.handleEventForTrigger(event).single() is ai.nuxie.sdk.events.TriggerService.JourneyTriggerResult.Suppressed)
            h.service.exit("customer-1", first.ref.journeyId!!, "completed")
            assertTrue(h.service.handleEventForTrigger(event).single() is ai.nuxie.sdk.events.TriggerService.JourneyTriggerResult.Suppressed)
            now += 1_000
            assertTrue(h.service.handleEventForTrigger(event).single() is ai.nuxie.sdk.events.TriggerService.JourneyTriggerResult.Started)
        } finally { h.root.deleteRecursively() }
    }

    @Test
    fun failedEnrollmentFactDoesNotPersistOrAdmitAndCanRetry() = runBlocking {
        val h = harness()
        try {
            h.store.failNextPendingInsert = true
            val event = StoredEvent("trigger-1", "opened", timestampMillis = now, distinctId = "customer-1")

            val failed = h.service.handleEventForTrigger(event).single()
            assertTrue(failed is ai.nuxie.sdk.events.TriggerService.JourneyTriggerResult.Failed)
            assertTrue(JourneyStore(h.root).loadActive("customer-1").isEmpty())
            assertTrue(h.service.handleEventForTrigger(event).single() is ai.nuxie.sdk.events.TriggerService.JourneyTriggerResult.Started)
        } finally { h.root.deleteRecursively() }
    }

    @Test
    fun downFactsCommitOnceNeverUploadAndSupersedeGhostsTheRun() = runBlocking {
        val h = harness()
        try {
            val started = h.service.handleEventForTrigger(StoredEvent("trigger-1", "opened", timestampMillis = now, distinctId = "customer-1"))
                .single() as ai.nuxie.sdk.events.TriggerService.JourneyTriggerResult.Started
            val journeyId = started.ref.journeyId!!
            val fact = buildJsonObject {
                put("id", JsonPrimitive("fact-1"))
                put("event", JsonPrimitive(JourneyEventNames.SUPERSEDED))
                put("timestamp", JsonPrimitive(1L))
                put("properties", buildJsonObject { put("journey_id", JsonPrimitive(journeyId)) })
            }
            val body = buildJsonObject { put("facts", JsonArray(listOf(fact))) }
            val committed = mutableListOf<StoredEvent>()
            h.log.subscribeCommitted({ it.id == "fact-1" }) { committed += it }
            h.service.applyDownFacts(body, "customer-1")
            h.service.applyDownFacts(body, "customer-1")
            h.service.exit("customer-1", journeyId, "completed")
            h.log.awaitBarrier()
            assertTrue(JourneyStore(h.root).load("customer-1", journeyId)!!.isGhost)
            assertEquals(1, h.store.events.values.count { it.id == "fact-1" })
            assertTrue("server fact must never upload", "fact-1" in h.store.delivered)
            assertEquals("fact routes only after its first atomic commit", 1, committed.size)
            assertEquals("fact-1", h.store.events.getValue("fact-1").properties.stringValue("\$server_fact_id"))
            assertEquals("server", h.store.events.getValue("fact-1").properties.stringValue("\$nuxie_event_origin"))
            assertNull(h.store.events.values.firstOrNull { it.name == JourneyEventNames.EXITED && it.properties["journey_id"]?.toString()?.contains(journeyId) == true })
        } finally { h.root.deleteRecursively() }
    }

    @Test
    fun convertedFactsPersistTheEarliestServerAuthoredAt() = runBlocking {
        val h = harness()
        try {
            val started = h.service.handleEventForTrigger(
                StoredEvent("trigger-1", "opened", timestampMillis = now, distinctId = "customer-1"),
            ).single() as ai.nuxie.sdk.events.TriggerService.JourneyTriggerResult.Started
            val journeyId = started.ref.journeyId!!
            fun fact(id: String, at: Long) = buildJsonObject {
                put("id", JsonPrimitive(id))
                put("event", JsonPrimitive(JourneyEventNames.CONVERTED))
                put("timestamp", JsonPrimitive(now))
                put("properties", buildJsonObject {
                    put("journey_id", JsonPrimitive(journeyId))
                    put("at", JsonPrimitive(at))
                })
            }

            h.service.applyDownFacts(buildJsonObject { put("facts", JsonArray(listOf(fact("later", 50L), fact("earlier", 10L)))) }, "customer-1")

            assertEquals(10L, JourneyStore(h.root).load("customer-1", journeyId)!!.convertedAtMillis)
        } finally { h.root.deleteRecursively() }
    }

    @Test
    fun convertedFactUsesTheConversionPayloadTimeForTheCommittedActivity() = runBlocking {
        val h = harness(forwardingEnabled = { true })
        try {
            val fact = buildJsonObject {
                put("id", JsonPrimitive("converted-fact"))
                put("event", JsonPrimitive(JourneyEventNames.CONVERTED))
                put("timestamp", JsonPrimitive(50L))
                put("properties", buildJsonObject {
                    put("journey_id", JsonPrimitive("journey-1"))
                    put("at", JsonPrimitive(10L))
                })
            }

            h.service.applyDownFacts(
                buildJsonObject { put("facts", JsonArray(listOf(fact))) },
                "customer-1",
            )

            val delivered = mutableListOf<NuxieActivityInfo>()
            ActivityForwarder(
                resolveExperience = { _, journeyId -> ExperienceRef("experience-1", "version-1", journeyId) },
                deliver = { delivered += it },
            ).onCommitted(h.store.events.getValue("converted-fact"))

            val info = delivered.single()
            assertEquals(10L, info.timestampMillis)
            assertTrue(info.activity is NuxieActivity.JourneyConverted)
        } finally { h.root.deleteRecursively() }
    }

    @Test
    fun downFactsFromOneResponseShareOneLocalReceiptTime() = runBlocking {
        val h = harness(forwardingEnabled = { true })
        try {
            fun fact(id: String, timestamp: Long) = buildJsonObject {
                put("id", JsonPrimitive(id))
                put("event", JsonPrimitive(JourneyEventNames.CONVERTED))
                put("timestamp", JsonPrimitive(timestamp))
                put("properties", buildJsonObject { put("journey_id", JsonPrimitive("journey-1")) })
            }
            h.log.subscribeCommitted({ it.id == "first" }) { now += 5_000L }

            h.service.applyDownFacts(
                buildJsonObject { put("facts", JsonArray(listOf(fact("first", 10L), fact("second", 20L)))) },
                "customer-1",
            )

            assertEquals(10L, h.store.events.getValue("first").timestampMillis)
            assertEquals(20L, h.store.events.getValue("second").timestampMillis)
            assertEquals(
                h.store.events.getValue("first").forwardingReceivedAtMillis,
                h.store.events.getValue("second").forwardingReceivedAtMillis,
            )
            assertEquals(1_784_462_400_000L, h.store.events.getValue("second").forwardingReceivedAtMillis)
        } finally { h.root.deleteRecursively() }
    }

    @Test
    fun supersedeCommitAndExitAreSerializedSoAGhostNeverEmitsAnExit() = runBlocking {
        val h = harness()
        try {
            val started = h.service.handleEventForTrigger(
                StoredEvent("trigger-1", "opened", timestampMillis = now, distinctId = "customer-1"),
            ).single() as ai.nuxie.sdk.events.TriggerService.JourneyTriggerResult.Started
            val journeyId = started.ref.journeyId!!
            val superseded = buildJsonObject {
                put("id", JsonPrimitive("superseded-race"))
                put("event", JsonPrimitive(JourneyEventNames.SUPERSEDED))
                put("timestamp", JsonPrimitive(now))
                put("properties", buildJsonObject { put("journey_id", JsonPrimitive(journeyId)) })
            }

            val insertStarted = CompletableDeferred<Unit>()
            val releaseInsert = CompletableDeferred<Unit>()
            h.store.serverFactInsertStarted = insertStarted
            h.store.serverFactInsertGate = releaseInsert
            val route = async(Dispatchers.Default) {
                h.service.applyDownFacts(buildJsonObject { put("facts", JsonArray(listOf(superseded))) }, "customer-1")
            }
            insertStarted.await()
            val exiting = async(Dispatchers.Default) { h.service.exit("customer-1", journeyId, "completed") }
            releaseInsert.complete(Unit)
            awaitAll(route, exiting)
            h.log.awaitBarrier()

            assertTrue(JourneyStore(h.root).load("customer-1", journeyId)!!.isGhost)
            assertTrue(h.store.events.values.none { it.name == JourneyEventNames.EXITED })
        } finally { h.root.deleteRecursively() }
    }

    @Test
    fun supersedeArrivingAfterATerminalExitIsANoOp() = runBlocking {
        // iOS parity (applySupersededDownFactIfNeeded guards on a live run):
        // when the exit durably commits before the supersede fact arrives,
        // the exit stands, the late supersede does not ghost the terminal
        // run, and the server reconciles the committed exit on its side.
        val h = harness()
        try {
            val started = h.service.handleEventForTrigger(
                StoredEvent("trigger-1", "opened", timestampMillis = now, distinctId = "customer-1"),
            ).single() as ai.nuxie.sdk.events.TriggerService.JourneyTriggerResult.Started
            val journeyId = started.ref.journeyId!!

            h.service.exit("customer-1", journeyId, "completed")
            h.log.awaitBarrier()
            assertTrue(h.store.events.values.any { it.name == JourneyEventNames.EXITED })

            val superseded = buildJsonObject {
                put("id", JsonPrimitive("superseded-late"))
                put("event", JsonPrimitive(JourneyEventNames.SUPERSEDED))
                put("timestamp", JsonPrimitive(now))
                put("properties", buildJsonObject { put("journey_id", JsonPrimitive(journeyId)) })
            }
            h.service.applyDownFacts(buildJsonObject { put("facts", JsonArray(listOf(superseded))) }, "customer-1")
            h.log.awaitBarrier()

            val run = JourneyStore(h.root).load("customer-1", journeyId)!!
            assertEquals(JourneyRunState.TERMINAL, run.state)
            assertTrue("a late supersede must not ghost a terminal run", !run.isGhost)
            // The fact itself still commits once for the durable ledger.
            assertTrue("superseded-late" in h.store.events)
        } finally { h.root.deleteRecursively() }
    }

    @Test
    fun catalogReleasesAreScopedToTheProfileDistinctId() {
        val identity = ExperienceReleaseIdentity(
            appId = "app", environment = "development", experienceId = "experience-1",
            experienceVersionId = "version-1", buildId = "build-1", versionNumber = 1,
            publishedAt = "2026-01-01T00:00:00.000Z", publishedAtSeq = 1,
        )
        val release = AuthenticatedRelease(
            keyId = "test", descriptorSha256 = "sha", identity = identity,
            descriptorBytes = ByteArray(0),
            descriptor = buildJsonObject {
                put("enrollment", buildJsonObject {
                    put("trigger", buildJsonObject {
                        put("type", JsonPrimitive("event"))
                        put("eventName", JsonPrimitive("opened"))
                    })
                })
                put("lifecycle", buildJsonObject {
                    put("reentry", buildJsonObject { put("type", JsonPrimitive("every_time")) })
                    put("exitPolicy", JsonPrimitive("manual"))
                })
            },
            publishedAtSeqToPromote = null,
        )
        val catalog = JourneyReleaseCatalog(
            trustedKeys = emptyMap(),
            highWater = ReleaseHighWaterStore(RuntimeEnvironment.getApplication()),
            supportedRuntime = { supportedRuntime() },
            authenticate = { _, _ -> release },
        )
        val profile = buildJsonObject {
            put("releases", buildJsonObject {
                put("delivery", buildJsonObject {
                    put("renderBaseUrl", JsonPrimitive("https://example.test/renders/"))
                    put("assetBaseUrl", JsonPrimitive("https://example.test/assets/"))
                })
                put("active", JsonArray(listOf(buildJsonObject {
                    put("locator", buildJsonObject {
                        put("appId", JsonPrimitive(identity.appId))
                        put("environment", JsonPrimitive(identity.environment))
                        put("experienceId", JsonPrimitive(identity.experienceId))
                        put("experienceVersionId", JsonPrimitive(identity.experienceVersionId))
                        put("buildId", JsonPrimitive(identity.buildId))
                        put("versionNumber", JsonPrimitive(identity.versionNumber))
                        put("publishedAt", JsonPrimitive(identity.publishedAt))
                        put("publishedAtSeq", JsonPrimitive(identity.publishedAtSeq))
                    })
                    put("descriptorSha256", JsonPrimitive("sha"))
                    put("envelopeBytesBase64", JsonPrimitive("eA=="))
                })))
                put("pinned", JsonArray(emptyList()))
            })
        }

        catalog.applyProfile("user-a", profile)
        assertEquals(1, catalog.releasesFor("user-a", "opened").size)
        assertTrue(catalog.releasesFor("user-b", "opened").isEmpty())

        catalog.applyProfile("user-b", profile)
        assertTrue(catalog.releasesFor("user-a", "opened").isEmpty())
        assertEquals(1, catalog.releasesFor("user-b", "opened").size)
    }

    private fun supportedRuntime() = SupportedRuntime(
        currentSdkVersion = "1.0.0", supportedRuntimeRevisions = emptySet(), supportedLuauRevisions = emptyMap(),
        sceneFormatMajor = 0, sceneFormatMinor = 0, timezoneDataRevision = "", timezoneDataSha256 = "",
        supportedCapabilities = emptySet(),
    )

    private fun JsonObject.stringValue(key: String): String? =
        (this[key] as? JsonPrimitive)?.content
}
