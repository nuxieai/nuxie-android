package ai.nuxie.sdk.journey

import ai.nuxie.sdk.ExperienceRef
import ai.nuxie.sdk.JourneyExitReason
import ai.nuxie.sdk.LogLevel
import ai.nuxie.sdk.NuxieActivity
import ai.nuxie.sdk.NuxieEnvironment
import ai.nuxie.sdk.NuxieActivityInfo
import ai.nuxie.sdk.NuxieEvent
import ai.nuxie.sdk.SuppressReason
import ai.nuxie.sdk.TriggerUpdate
import ai.nuxie.sdk.events.ActivityForwarder
import ai.nuxie.sdk.events.EventLog
import ai.nuxie.sdk.events.EventStore
import ai.nuxie.sdk.events.NuxieContextBuilder
import ai.nuxie.sdk.events.StoredEvent
import ai.nuxie.sdk.events.TriggerBroker
import ai.nuxie.sdk.experiences.AuthenticatedRelease
import ai.nuxie.sdk.experiences.ExperienceReleaseIdentity
import ai.nuxie.sdk.experiences.ReleaseHighWaterStore
import ai.nuxie.sdk.experiences.SupportedRuntime
import ai.nuxie.sdk.identity.IdentityProvider
import ai.nuxie.sdk.presentation.CloseReason
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.shadows.ShadowLog

@RunWith(RobolectricTestRunner::class)
class JourneyServiceTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var now = 1_784_462_400_000L

    private class Identity : IdentityProvider {
        var currentDistinctId = "customer-1"

        override fun distinctId() = currentDistinctId
        override fun anonymousId() = currentDistinctId
        override fun rawDistinctId(): String? = null
        override val isIdentified = false
    }

    private class Store : EventStore {
        val events = linkedMapOf<String, StoredEvent>()
        val delivered = mutableSetOf<String>()
        var failNextPendingInsert = false
        var beforePendingInsert: ((StoredEvent) -> Unit)? = null
        var serverFactInsertStarted: CompletableDeferred<Unit>? = null
        var serverFactInsertGate: CompletableDeferred<Unit>? = null
        override suspend fun insertPending(event: StoredEvent) {
            beforePendingInsert?.invoke(event)
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

    private data class Harness(
        val root: File,
        val store: Store,
        val log: EventLog,
        val service: JourneyService,
        val broker: TriggerBroker,
        val releases: JourneyReleaseProvider,
    )

    private fun harness(
        reentry: JourneyReentry = JourneyReentry.EveryTime,
        forwardingEnabled: () -> Boolean = { false },
        identity: Identity = Identity(),
        beforeSend: ((NuxieEvent) -> NuxieEvent?)? = null,
        onJourneyClockRead: () -> Unit = {},
        hostDismissRetrySleep: suspend (Long) -> Unit = { kotlinx.coroutines.delay(it) },
    ): Harness {
        val root = createTempDir(prefix = "nuxie-journey-")
        val eventStore = Store()
        val eventLog = EventLog(
            store = eventStore,
            contextBuilder = NuxieContextBuilder(RuntimeEnvironment.getApplication(), NuxieEnvironment.DEVELOPMENT, LogLevel.NONE, identity),
            identity = identity,
            beforeSend = beforeSend,
            scope = scope,
            nowMillis = { now },
        ).also { log ->
            log.subscribeForwarding(isEnabled = forwardingEnabled) {}
        }
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
        val broker = TriggerBroker()
        val releases = JourneyReleaseProvider { _, name ->
            if (name == "opened") listOf(release) else emptyList()
        }
        return Harness(
            root,
            eventStore,
            eventLog,
            JourneyService(
                JourneyStore(root),
                JourneyLedger(eventLog),
                releases,
                {
                    onJourneyClockRead()
                    now
                },
                triggerBroker = broker,
                hostDismissRetrySleep = hostDismissRetrySleep,
            ),
            broker,
            releases,
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
                setOf("journey_id", "epoch", "experience_id", "experience_version", "reason", "at"),
                h.store.events.values.first { it.name == JourneyEventNames.EXITED }.properties.keys.filterNot { it.startsWith("$") }.toSet(),
            )
        } finally { h.root.deleteRecursively() }
    }

    @Test
    fun presentationDrivenExitForwardsJourneyEndedWithExperienceReference() = runBlocking {
        val h = harness(forwardingEnabled = { true })
        val forwarded = mutableListOf<NuxieActivityInfo>()
        h.log.subscribeForwarding(
            handler = ActivityForwarder(
                resolveExperience = { _, _ -> null },
                deliver = { forwarded += it },
            )::onCommitted,
        )
        try {
            val started = h.service.handleEventForTrigger(
                StoredEvent("trigger-1", "opened", timestampMillis = now, distinctId = "customer-1"),
            ).single() as ai.nuxie.sdk.events.TriggerService.JourneyTriggerResult.Started

            h.service.presentationEnded("customer-1", started.ref.journeyId!!, CloseReason.UserDismissed)
            h.log.awaitBarrier()

            assertEquals(
                NuxieActivity.JourneyEnded(
                    ExperienceRef("experience-1", "version-1", started.ref.journeyId),
                    JourneyExitReason.DISMISSED,
                ),
                forwarded.single { it.activity is NuxieActivity.JourneyEnded }.activity,
            )
        } finally { h.root.deleteRecursively() }
    }

    @Test
    fun hostDismissalBookkeepingPersistsTombstoneBeforeCapturingAttributedExit() = runBlocking {
        val h = harness()
        try {
            val started = h.service.handleEventForTrigger(
                StoredEvent("trigger-1", "opened", timestampMillis = now, distinctId = "customer-1"),
            ).single() as ai.nuxie.sdk.events.TriggerService.JourneyTriggerResult.Started
            val journeyId = started.ref.journeyId!!
            markHostDismissed(h, journeyId)
            var persistedAtCapture: JourneyRun? = null
            h.store.beforePendingInsert = { event ->
                if (event.name == JourneyEventNames.EXITED) {
                    persistedAtCapture = JourneyStore(h.root).load("customer-1", journeyId)
                }
            }

            val completed = h.service.presentationEnded(
                "customer-1",
                journeyId,
                CloseReason.HostDismissed,
            )

            assertTrue(completed)
            val durableTombstone = requireNotNull(persistedAtCapture)
            assertEquals(JourneyRunState.TERMINAL, durableTombstone.state)
            assertEquals("dismissed", durableTombstone.terminalReason)
            assertEquals(now, durableTombstone.completedAtMillis)
            assertTrue(durableTombstone.pendingHostExitCapture)
            assertTrue(durableTombstone.pendingHostCompletion)
            assertTrue(durableTombstone.pendingHostTriggerCompletion)
            val exit = h.store.events.values.single { it.name == JourneyEventNames.EXITED }
            assertEquals("journey-exited:$journeyId:0", exit.id)
            assertEquals("customer-1", exit.distinctId)
            assertEquals("dismissed", exit.properties.stringValue("reason"))
            assertEquals("host", exit.properties.stringValue("dismissed_by"))
            assertEquals("2026-07-19T12:00:00.000Z", exit.properties.stringValue("at"))
        } finally {
            h.root.deleteRecursively()
        }
    }

    @Test
    fun hostDismissalRemainsAuthoritativeForAGhostJourney() = runBlocking {
        val h = harness()
        try {
            val started = h.service.handleEventForTrigger(
                StoredEvent("trigger-1", "opened", timestampMillis = now, distinctId = "customer-1"),
            ).single() as ai.nuxie.sdk.events.TriggerService.JourneyTriggerResult.Started
            val journeyId = started.ref.journeyId!!
            h.service.applyDownFacts(
                buildJsonObject {
                    put("facts", JsonArray(listOf(buildJsonObject {
                        put("id", JsonPrimitive("superseded-before-dismissal"))
                        put("event", JsonPrimitive(JourneyEventNames.SUPERSEDED))
                        put("timestamp", JsonPrimitive(now))
                        put("properties", buildJsonObject {
                            put("journey_id", JsonPrimitive(journeyId))
                        })
                    })))
                },
                "customer-1",
            )
            markHostDismissed(h, journeyId)

            assertTrue(
                h.service.presentationEnded(
                    "customer-1",
                    journeyId,
                    CloseReason.HostDismissed,
                ),
            )

            val exit = h.store.events.values.single { it.name == JourneyEventNames.EXITED }
            assertEquals("host", exit.properties.stringValue("dismissed_by"))
            assertEquals("dismissed", exit.properties.stringValue("reason"))
        } finally {
            h.root.deleteRecursively()
        }
    }

    @Test
    fun userDismissalPreservesCancelledWireReasonAndUserAttribution() = runBlocking {
        val h = harness()
        try {
            val updates = mutableListOf<TriggerUpdate>()
            h.broker.register("trigger-1", updates::add)
            val started = h.service.handleEventForTrigger(
                StoredEvent("trigger-1", "opened", timestampMillis = now, distinctId = "customer-1"),
            ).single() as ai.nuxie.sdk.events.TriggerService.JourneyTriggerResult.Started

            assertTrue(
                h.service.presentationEnded(
                    "customer-1",
                    started.ref.journeyId!!,
                    CloseReason.UserDismissed,
                ),
            )

            val exit = h.store.events.values.single { it.name == JourneyEventNames.EXITED }
            assertEquals("cancelled", exit.properties.stringValue("reason"))
            assertEquals("user", exit.properties.stringValue("dismissed_by"))
            assertEquals("2026-07-19T12:00:00.000Z", exit.properties.stringValue("at"))
            val terminal = requireNotNull(
                JourneyStore(h.root).load("customer-1", started.ref.journeyId!!),
            )
            assertEquals("dismissed", terminal.terminalReason)
            assertTrue(JourneyStore(h.root).hasCompleted("customer-1", "experience-1"))
            val completion = updates.single() as TriggerUpdate.Journey
            assertEquals(JourneyExitReason.DISMISSED, completion.update.exitReason)
        } finally {
            h.root.deleteRecursively()
        }
    }

    @Test
    fun userDismissalPersistsOwnerAttributedExitWhenBeforeSendDropsAndIdentityChanges() = runBlocking {
        val identity = Identity()
        var changeIdentityOnJourneyClockRead = false
        val h = harness(
            identity = identity,
            beforeSend = { event ->
                if (event.name == JourneyEventNames.EXITED) null else event
            },
            onJourneyClockRead = {
                if (changeIdentityOnJourneyClockRead) {
                    identity.currentDistinctId = "customer-2"
                }
            },
        )
        try {
            val started = h.service.handleEventForTrigger(
                StoredEvent(
                    "trigger-1",
                    "opened",
                    timestampMillis = now,
                    distinctId = "customer-1",
                ),
            ).single() as ai.nuxie.sdk.events.TriggerService.JourneyTriggerResult.Started
            changeIdentityOnJourneyClockRead = true

            assertTrue(
                h.service.presentationEnded(
                    "customer-1",
                    started.ref.journeyId!!,
                    CloseReason.UserDismissed,
                ),
            )

            assertEquals("customer-2", identity.currentDistinctId)
            val exit = h.store.events.values.single { it.name == JourneyEventNames.EXITED }
            assertEquals("journey-exited:${started.ref.journeyId}:0", exit.id)
            assertEquals("customer-1", exit.distinctId)
            assertEquals("customer-1", exit.properties.stringValue("\$distinct_id"))
            assertEquals("cancelled", exit.properties.stringValue("reason"))
            assertEquals("user", exit.properties.stringValue("dismissed_by"))
        } finally {
            h.root.deleteRecursively()
        }
    }

    @Test
    fun hostDismissalResolvesTheOriginatingTriggerAsJourneyCompletedDismissed() = runBlocking {
        val h = harness()
        try {
            val updates = mutableListOf<TriggerUpdate>()
            h.broker.register("trigger-1", updates::add)
            val started = h.service.handleEventForTrigger(
                StoredEvent("trigger-1", "opened", timestampMillis = now, distinctId = "customer-1"),
            ).single() as ai.nuxie.sdk.events.TriggerService.JourneyTriggerResult.Started
            val journeyId = started.ref.journeyId!!
            h.service.applyDownFacts(
                buildJsonObject {
                    put("facts", JsonArray(listOf(buildJsonObject {
                        put("id", JsonPrimitive("converted-before-dismissal"))
                        put("event", JsonPrimitive(JourneyEventNames.CONVERTED))
                        put("timestamp", JsonPrimitive(now))
                        put("properties", buildJsonObject {
                            put("journey_id", JsonPrimitive(journeyId))
                            put("at", JsonPrimitive(now - 1))
                        })
                    })))
                },
                "customer-1",
            )
            markHostDismissed(h, journeyId)

            assertTrue(
                h.service.presentationEnded(
                    "customer-1",
                    started.ref.journeyId!!,
                    CloseReason.HostDismissed,
                ),
            )

            val completion = updates.single() as TriggerUpdate.Journey
            assertEquals(started.ref, completion.update.ref)
            assertEquals(JourneyExitReason.DISMISSED, completion.update.exitReason)
            assertTrue(completion.update.goalMet)
        } finally {
            h.root.deleteRecursively()
        }
    }

    @Test
    fun hostDismissalWinsConcurrentTerminalRacesAndWriteBehindConvergesUnderLoad() = runBlocking {
        val blockNextJourneyClockRead = AtomicBoolean(false)
        var hostWriterInsideTransition = CountDownLatch(0)
        var releaseHostWriter = CountDownLatch(0)
        val h = harness(
            reentry = JourneyReentry.OneTime,
            onJourneyClockRead = {
                if (blockNextJourneyClockRead.compareAndSet(true, false)) {
                    hostWriterInsideTransition.countDown()
                    check(releaseHostWriter.await(5, TimeUnit.SECONDS))
                }
            },
        )
        try {
            repeat(32) { attempt ->
                val distinctId = "host-winner-customer-$attempt"
                val started = h.service.handleEventForTrigger(
                    StoredEvent(
                        "host-winner-trigger-$attempt",
                        "opened",
                        timestampMillis = now,
                        distinctId = distinctId,
                    ),
                ).single() as ai.nuxie.sdk.events.TriggerService.JourneyTriggerResult.Started
                val journeyId = requireNotNull(started.ref.journeyId)
                hostWriterInsideTransition = CountDownLatch(1)
                releaseHostWriter = CountDownLatch(1)
                blockNextJourneyClockRead.set(true)

                val hostWriter = async(Dispatchers.Default) {
                    h.service.markHostDismissedInMemory(distinctId, journeyId, distinctId)
                }
                assertTrue(hostWriterInsideTransition.await(5, TimeUnit.SECONDS))
                // Undispatched startup makes both detached lanes reach the
                // held run lock before the winning transition is released.
                val competingWriter = async(
                    context = Dispatchers.Default,
                    start = CoroutineStart.UNDISPATCHED,
                ) {
                    h.service.exit(distinctId, journeyId, "error")
                }
                val writeBehind = async(
                    context = Dispatchers.Default,
                    start = CoroutineStart.UNDISPATCHED,
                ) {
                    h.service.presentationEnded(distinctId, journeyId, CloseReason.HostDismissed)
                }

                releaseHostWriter.countDown()

                assertTrue("attempt $attempt: host writer did not win in memory", hostWriter.await())
                competingWriter.await()
                assertTrue("attempt $attempt: write-behind rejected the in-memory winner", writeBehind.await())
                assertNull(JourneyStore(h.root).load(distinctId, journeyId))
                assertTrue(JourneyStore(h.root).hasCompleted(distinctId, "experience-1"))
                val admission = h.service.handleEventForTrigger(
                    StoredEvent(
                        "host-winner-retrigger-$attempt",
                        "opened",
                        timestampMillis = now,
                        distinctId = distinctId,
                    ),
                ).single() as ai.nuxie.sdk.events.TriggerService.JourneyTriggerResult.Suppressed
                assertEquals(SuppressReason.REENTRY_LIMITED, admission.reason)
            }
        } finally {
            releaseHostWriter.countDown()
            h.root.deleteRecursively()
        }
    }

    @Test
    fun competingExitWinsConcurrentTerminalRacesAndLoserWriteBehindCannotOverwriteUnderLoad() = runBlocking {
        val blockNextJourneyClockRead = AtomicBoolean(false)
        var competingWriterInsideTransition = CountDownLatch(0)
        var releaseCompetingWriter = CountDownLatch(0)
        val h = harness(
            reentry = JourneyReentry.OneTime,
            onJourneyClockRead = {
                if (blockNextJourneyClockRead.compareAndSet(true, false)) {
                    competingWriterInsideTransition.countDown()
                    check(releaseCompetingWriter.await(5, TimeUnit.SECONDS))
                }
            },
        )
        try {
            repeat(32) { attempt ->
                val distinctId = "exit-winner-customer-$attempt"
                val started = h.service.handleEventForTrigger(
                    StoredEvent(
                        "exit-winner-trigger-$attempt",
                        "opened",
                        timestampMillis = now,
                        distinctId = distinctId,
                    ),
                ).single() as ai.nuxie.sdk.events.TriggerService.JourneyTriggerResult.Started
                val journeyId = requireNotNull(started.ref.journeyId)
                competingWriterInsideTransition = CountDownLatch(1)
                releaseCompetingWriter = CountDownLatch(1)
                blockNextJourneyClockRead.set(true)

                val competingWriter = async(Dispatchers.Default) {
                    h.service.exit(distinctId, journeyId, "error")
                }
                assertTrue(competingWriterInsideTransition.await(5, TimeUnit.SECONDS))
                // Both losing host lanes are queued before the winner exits
                // the critical section, including its detached write-behind.
                val hostWriter = async(
                    context = Dispatchers.Default,
                    start = CoroutineStart.UNDISPATCHED,
                ) {
                    h.service.markHostDismissedInMemory(distinctId, journeyId, distinctId)
                }
                val loserWriteBehind = async(
                    context = Dispatchers.Default,
                    start = CoroutineStart.UNDISPATCHED,
                ) {
                    h.service.presentationEnded(distinctId, journeyId, CloseReason.HostDismissed)
                }

                releaseCompetingWriter.countDown()

                competingWriter.await()
                assertFalse("attempt $attempt: both terminal writers won in memory", hostWriter.await())
                assertFalse("attempt $attempt: loser write-behind accepted the winner", loserWriteBehind.await())
                val durableRun = requireNotNull(JourneyStore(h.root).load(distinctId, journeyId))
                assertEquals(JourneyRunState.TERMINAL, durableRun.state)
                assertEquals("error", durableRun.terminalReason)
                assertFalse(JourneyStore(h.root).hasCompleted(distinctId, "experience-1"))
                assertTrue(
                    h.service.handleEventForTrigger(
                        StoredEvent(
                            "exit-winner-retrigger-$attempt",
                            "opened",
                            timestampMillis = now,
                            distinctId = distinctId,
                        ),
                    ).single() is ai.nuxie.sdk.events.TriggerService.JourneyTriggerResult.Started,
                )
            }
        } finally {
            releaseCompetingWriter.countDown()
            h.root.deleteRecursively()
        }
    }

    @Test
    fun terminalRunRefusesRunFactsBeforeWriteBehindPersistence() = runBlocking {
        val h = harness()
        try {
            val started = h.service.handleEventForTrigger(
                StoredEvent("trigger-1", "opened", timestampMillis = now, distinctId = "customer-1"),
            ).single() as ai.nuxie.sdk.events.TriggerService.JourneyTriggerResult.Started
            val journeyId = requireNotNull(started.ref.journeyId)

            markHostDismissed(h, journeyId)
            assertEquals(
                JourneyRunState.ACTIVE,
                JourneyStore(h.root).load("customer-1", journeyId)?.state,
            )

            h.service.transition("customer-1", journeyId, null, "screen-a")
            h.service.milestone("customer-1", journeyId, "reached-a")
            val invocationId = h.service.requestEffect(
                "customer-1",
                journeyId,
                "effect-a",
                1,
                "send_push",
                JsonObject(emptyMap()),
            )
            h.log.awaitBarrier()

            assertNull(invocationId)
            assertTrue(
                h.store.events.values.none {
                    it.name == JourneyEventNames.TRANSITION ||
                        it.name == JourneyEventNames.MILESTONE ||
                        it.name == JourneyEventNames.EFFECT_REQUESTED
                },
            )
        } finally {
            h.root.deleteRecursively()
        }
    }

    @Test
    fun terminalRunRefusesDownFactMutationsBeforeWriteBehindPersistence() = runBlocking {
        val h = harness()
        try {
            val started = h.service.handleEventForTrigger(
                StoredEvent("trigger-1", "opened", timestampMillis = now, distinctId = "customer-1"),
            ).single() as ai.nuxie.sdk.events.TriggerService.JourneyTriggerResult.Started
            val journeyId = requireNotNull(started.ref.journeyId)
            markHostDismissed(h, journeyId)
            val superseded = buildJsonObject {
                put("id", JsonPrimitive("superseded-after-host-exit"))
                put("event", JsonPrimitive(JourneyEventNames.SUPERSEDED))
                put("timestamp", JsonPrimitive(now))
                put("properties", buildJsonObject {
                    put("journey_id", JsonPrimitive(journeyId))
                })
            }
            val converted = buildJsonObject {
                put("id", JsonPrimitive("converted-after-host-exit"))
                put("event", JsonPrimitive(JourneyEventNames.CONVERTED))
                put("timestamp", JsonPrimitive(now))
                put("properties", buildJsonObject {
                    put("journey_id", JsonPrimitive(journeyId))
                    put("at", JsonPrimitive(now))
                })
            }

            h.service.applyDownFacts(
                buildJsonObject { put("facts", JsonArray(listOf(superseded, converted))) },
                "customer-1",
            )

            val durableRun = requireNotNull(JourneyStore(h.root).load("customer-1", journeyId))
            assertEquals(JourneyRunState.ACTIVE, durableRun.state)
            assertFalse(durableRun.isGhost)
            assertNull(durableRun.convertedAtMillis)
            assertTrue("superseded-after-host-exit" in h.store.events)
            assertTrue("converted-after-host-exit" in h.store.events)
        } finally {
            h.root.deleteRecursively()
        }
    }

    @Test
    fun failedInitialTombstoneWriteRetriesWithBackoffOnTheDetachedLane() = runBlocking {
        val retryDelays = Channel<Long>(Channel.UNLIMITED)
        val allowRetry = Channel<Unit>(Channel.UNLIMITED)
        val h = harness(
            hostDismissRetrySleep = { delayMillis ->
                retryDelays.send(delayMillis)
                allowRetry.receive()
            },
        )
        try {
            val started = h.service.handleEventForTrigger(
                StoredEvent("trigger-1", "opened", timestampMillis = now, distinctId = "customer-1"),
            ).single() as ai.nuxie.sdk.events.TriggerService.JourneyTriggerResult.Started
            val journeyId = requireNotNull(started.ref.journeyId)
            markHostDismissed(h, journeyId)
            val runFile = File(h.root, "nuxie/journeys/runs")
                .walkTopDown()
                .single { it.isFile && it.name == "$journeyId.json" }
            val blockedTemporary = File(runFile.parentFile, ".$journeyId.json.new")
            assertTrue(blockedTemporary.mkdir())

            val bookkeeping = async {
                h.service.presentationEnded(
                    "customer-1",
                    journeyId,
                    CloseReason.HostDismissed,
                )
            }
            assertEquals(1_000L, retryDelays.receive())
            assertEquals(
                JourneyRunState.ACTIVE,
                JourneyStore(h.root).load("customer-1", journeyId)?.state,
            )
            assertFalse("failed bookkeeping did not enter backoff", bookkeeping.isCompleted)

            allowRetry.send(Unit)
            assertEquals(2_000L, retryDelays.receive())
            assertTrue(blockedTemporary.delete())
            allowRetry.send(Unit)
            assertTrue(bookkeeping.await())

            assertEquals(1, h.store.events.values.count { it.name == JourneyEventNames.EXITED })
            assertTrue(JourneyStore(h.root).hasCompleted("customer-1", "experience-1"))
            assertNull(JourneyStore(h.root).load("customer-1", journeyId))
        } finally {
            h.root.deleteRecursively()
        }
    }

    @Test
    fun foregroundReattemptPersistsFailedInitialTombstoneForStartupRecovery() = runBlocking {
        val retryDelays = Channel<Long>(Channel.UNLIMITED)
        val holdDetachedRetry = Channel<Unit>()
        val h = harness(
            hostDismissRetrySleep = { delayMillis ->
                retryDelays.send(delayMillis)
                holdDetachedRetry.receive()
            },
        )
        try {
            val updates = mutableListOf<TriggerUpdate>()
            var failTriggerCompletion = true
            h.broker.register("trigger-1") { update ->
                if (failTriggerCompletion) error("trigger completion failed")
                updates += update
            }
            val started = h.service.handleEventForTrigger(
                StoredEvent("trigger-1", "opened", timestampMillis = now, distinctId = "customer-1"),
            ).single() as ai.nuxie.sdk.events.TriggerService.JourneyTriggerResult.Started
            val journeyId = requireNotNull(started.ref.journeyId)
            markHostDismissed(h, journeyId)
            val runFile = File(h.root, "nuxie/journeys/runs")
                .walkTopDown()
                .single { it.isFile && it.name == "$journeyId.json" }
            val blockedTemporary = File(runFile.parentFile, ".$journeyId.json.new")
            assertTrue(blockedTemporary.mkdir())
            val bookkeeping = async {
                h.service.presentationEnded(
                    "customer-1",
                    journeyId,
                    CloseReason.HostDismissed,
                )
            }
            assertEquals(1_000L, retryDelays.receive())

            assertTrue(blockedTemporary.delete())
            h.store.beforePendingInsert = { event ->
                if (event.name == JourneyEventNames.EXITED) error("exit capture failed")
            }
            val blockedCompletionsDirectory = File(h.root, "nuxie/journeys/completions").apply {
                parentFile!!.mkdirs()
                writeText("blocked")
            }

            h.service.recoverPendingHostDismissals()

            val tombstone = requireNotNull(JourneyStore(h.root).load("customer-1", journeyId))
            assertEquals(JourneyRunState.TERMINAL, tombstone.state)
            assertTrue(tombstone.pendingHostExitCapture)
            assertTrue(tombstone.pendingHostCompletion)
            assertTrue(tombstone.pendingHostTriggerCompletion)
            bookkeeping.cancelAndJoin()

            h.store.beforePendingInsert = null
            assertTrue(blockedCompletionsDirectory.delete())
            failTriggerCompletion = false
            val restartedService = JourneyService(
                JourneyStore(h.root),
                JourneyLedger(h.log),
                h.releases,
                { now },
                initialDistinctId = "customer-1",
                triggerBroker = h.broker,
            )
            restartedService.recoverPendingHostDismissals()

            assertEquals(1, h.store.events.values.count { it.name == JourneyEventNames.EXITED })
            assertTrue(JourneyStore(h.root).hasCompleted("customer-1", "experience-1"))
            assertEquals(1, updates.size)
            assertNull(JourneyStore(h.root).load("customer-1", journeyId))
        } finally {
            h.root.deleteRecursively()
        }
    }

    @Test
    fun postTerminalHostExitCaptureFailureTearsDownAndRecovers() = runBlocking {
        val h = harness()
        try {
            val updates = mutableListOf<TriggerUpdate>()
            h.broker.register("trigger-1", updates::add)
            val started = h.service.handleEventForTrigger(
                StoredEvent("trigger-1", "opened", timestampMillis = now, distinctId = "customer-1"),
            ).single() as ai.nuxie.sdk.events.TriggerService.JourneyTriggerResult.Started
            val journeyId = started.ref.journeyId!!
            markHostDismissed(h, journeyId)
            h.store.failNextPendingInsert = true

            assertTrue(
                h.service.presentationEnded("customer-1", journeyId, CloseReason.HostDismissed),
            )
            assertEquals(1, updates.size)
            assertTrue(JourneyStore(h.root).hasCompleted("customer-1", "experience-1"))
            assertTrue(h.store.events.values.none { it.name == JourneyEventNames.EXITED })

            val afterReturn = requireNotNull(JourneyStore(h.root).load("customer-1", journeyId))
            assertEquals(JourneyRunState.TERMINAL, afterReturn.state)
            assertTrue(afterReturn.pendingHostExitCapture)
            assertFalse(afterReturn.pendingHostCompletion)
            assertFalse(afterReturn.pendingHostTriggerCompletion)

            val restartedService = JourneyService(
                JourneyStore(h.root),
                JourneyLedger(h.log),
                h.releases,
                { now },
                triggerBroker = h.broker,
            )
            restartedService.recoverPendingHostDismissals()
            assertEquals(1, updates.size)
            assertEquals(1, h.store.events.values.count { it.name == JourneyEventNames.EXITED })
            assertNull(JourneyStore(h.root).load("customer-1", journeyId))
        } finally {
            h.root.deleteRecursively()
        }
    }

    @Test
    fun pendingHostExitCaptureRecoversAtStartup() = runBlocking {
        val h = harness()
        try {
            val started = h.service.handleEventForTrigger(
                StoredEvent("trigger-1", "opened", timestampMillis = now, distinctId = "customer-1"),
            ).single() as ai.nuxie.sdk.events.TriggerService.JourneyTriggerResult.Started
            markHostDismissed(h, started.ref.journeyId!!)
            h.store.failNextPendingInsert = true
            assertTrue(
                h.service.presentationEnded(
                    "customer-1",
                    started.ref.journeyId!!,
                    CloseReason.HostDismissed,
                ),
            )

            h.service.recoverPendingHostDismissals()
            h.service.recoverPendingHostDismissals()

            assertEquals(1, h.store.events.values.count { it.name == JourneyEventNames.EXITED })
            val recovered = JourneyStore(h.root).load("customer-1", started.ref.journeyId!!)
            assertNull(recovered)
        } finally {
            h.root.deleteRecursively()
        }
    }

    @Test
    fun foregroundRecoveryIsolatesTombstoneFailuresAndRetriesThemOnTheNextScan() = runBlocking {
        val h = harness()
        try {
            val journeyStore = JourneyStore(h.root)
            val journeyIds = listOf("customer-a", "customer-b").associateWith { distinctId ->
                val started = h.service.handleEventForTrigger(
                    StoredEvent(
                        "trigger-$distinctId",
                        "opened",
                        timestampMillis = now,
                        distinctId = distinctId,
                    ),
                ).single() as ai.nuxie.sdk.events.TriggerService.JourneyTriggerResult.Started
                val journeyId = requireNotNull(started.ref.journeyId)
                val active = requireNotNull(journeyStore.load(distinctId, journeyId))
                journeyStore.save(
                    active.copy(
                        state = JourneyRunState.TERMINAL,
                        terminalReason = "dismissed",
                        completedAtMillis = now,
                        pendingHostExitCapture = true,
                        pendingHostCompletion = true,
                        pendingHostTriggerCompletion = true,
                    ),
                )
                journeyId
            }
            val firstJourneyId = requireNotNull(journeyIds["customer-a"])
            val firstRunFile = File(h.root, "nuxie/journeys/runs")
                .walkTopDown()
                .single { it.isFile && it.name == "$firstJourneyId.json" }
            val blockedFirstRewrite = File(firstRunFile.parentFile, ".$firstJourneyId.json.new")
            assertTrue(blockedFirstRewrite.mkdir())
            ShadowLog.clear()
            val recoveringService = JourneyService(
                JourneyStore(h.root),
                JourneyLedger(h.log),
                h.releases,
                { now },
                triggerBroker = h.broker,
            )

            recoveringService.recoverPendingHostDismissals()

            assertTrue(JourneyStore(h.root).load("customer-a", firstJourneyId)?.pendingHostExitCapture == true)
            assertNull(JourneyStore(h.root).load("customer-b", requireNotNull(journeyIds["customer-b"])))
            val failureLog = ShadowLog.getLogsForTag("Nuxie").single {
                it.msg.contains("Pending host-dismissal recovery failed")
            }
            assertTrue(failureLog.msg.contains(firstJourneyId))
            assertTrue(failureLog.throwable is java.io.FileNotFoundException)

            assertTrue(blockedFirstRewrite.delete())
            recoveringService.recoverPendingHostDismissals()

            assertNull(JourneyStore(h.root).load("customer-a", firstJourneyId))
        } finally {
            h.root.deleteRecursively()
        }
    }

    @Test
    fun interruptedInitialTombstoneReplacementRecoversAfterRestart() = runBlocking {
        val h = harness()
        try {
            val started = h.service.handleEventForTrigger(
                StoredEvent("trigger-1", "opened", timestampMillis = now, distinctId = "customer-1"),
            ).single() as ai.nuxie.sdk.events.TriggerService.JourneyTriggerResult.Started
            val journeyId = requireNotNull(started.ref.journeyId)
            val journeyStore = JourneyStore(h.root)
            val active = requireNotNull(journeyStore.load("customer-1", journeyId))
            val runFile = File(h.root, "nuxie/journeys/runs")
                .walkTopDown()
                .single { it.isFile && it.name == "$journeyId.json" }
            val activeJson = runFile.readText()
            journeyStore.save(
                active.copy(
                    state = JourneyRunState.TERMINAL,
                    terminalReason = "dismissed",
                    completedAtMillis = now,
                    pendingHostExitCapture = true,
                    pendingHostCompletion = true,
                    pendingHostTriggerCompletion = true,
                ),
            )
            val terminalJson = runFile.readText()
            runFile.writeText(activeJson)
            File(runFile.parentFile, ".$journeyId.json.new").writeText(terminalJson)

            val restartedService = JourneyService(
                JourneyStore(h.root),
                JourneyLedger(h.log),
                h.releases,
                { now },
                triggerBroker = h.broker,
            )
            restartedService.recoverPendingHostDismissals()

            assertEquals(1, h.store.events.values.count { it.name == JourneyEventNames.EXITED })
            assertTrue(JourneyStore(h.root).hasCompleted("customer-1", "experience-1"))
            assertNull(JourneyStore(h.root).load("customer-1", journeyId))
        } finally {
            h.root.deleteRecursively()
        }
    }

    @Test
    fun pendingHostCompletionBlocksOneTimeReenrollmentUntilReceiptRecovery() = runBlocking {
        val h = harness(JourneyReentry.OneTime)
        try {
            val started = h.service.handleEventForTrigger(
                StoredEvent("trigger-1", "opened", timestampMillis = now, distinctId = "customer-1"),
            ).single() as ai.nuxie.sdk.events.TriggerService.JourneyTriggerResult.Started
            val journeyId = started.ref.journeyId!!
            markHostDismissed(h, journeyId)
            val blockedCompletionsDirectory = File(h.root, "nuxie/journeys/completions").apply {
                parentFile!!.mkdirs()
                writeText("blocked")
            }

            assertTrue(
                h.service.presentationEnded("customer-1", journeyId, CloseReason.HostDismissed),
            )
            val tombstone = requireNotNull(JourneyStore(h.root).load("customer-1", journeyId))
            assertTrue(tombstone.pendingHostCompletion)
            assertFalse(JourneyStore(h.root).hasCompleted("customer-1", "experience-1"))

            val whilePending = h.service.handleEventForTrigger(
                StoredEvent("trigger-2", "opened", timestampMillis = now, distinctId = "customer-1"),
            ).single()
            assertTrue(whilePending is ai.nuxie.sdk.events.TriggerService.JourneyTriggerResult.Suppressed)
            assertEquals(
                SuppressReason.REENTRY_LIMITED,
                (whilePending as ai.nuxie.sdk.events.TriggerService.JourneyTriggerResult.Suppressed).reason,
            )

            assertTrue(blockedCompletionsDirectory.delete())
            h.service.recoverPendingHostDismissals()
            assertTrue(JourneyStore(h.root).hasCompleted("customer-1", "experience-1"))
            assertNull(JourneyStore(h.root).load("customer-1", journeyId))

            val afterRecovery = h.service.handleEventForTrigger(
                StoredEvent("trigger-3", "opened", timestampMillis = now, distinctId = "customer-1"),
            ).single()
            assertTrue(afterRecovery is ai.nuxie.sdk.events.TriggerService.JourneyTriggerResult.Suppressed)
            assertEquals(
                SuppressReason.REENTRY_LIMITED,
                (afterRecovery as ai.nuxie.sdk.events.TriggerService.JourneyTriggerResult.Suppressed).reason,
            )
        } finally {
            h.root.deleteRecursively()
        }
    }

    @Test
    fun pendingHostCompletionUsesItsCompletionTimeForWindowedReenrollment() = runBlocking {
        val h = harness(JourneyReentry.OncePerWindow(1_000))
        try {
            val started = h.service.handleEventForTrigger(
                StoredEvent("trigger-1", "opened", timestampMillis = now, distinctId = "customer-1"),
            ).single() as ai.nuxie.sdk.events.TriggerService.JourneyTriggerResult.Started
            val journeyId = started.ref.journeyId!!
            markHostDismissed(h, journeyId)
            val blockedCompletionsDirectory = File(h.root, "nuxie/journeys/completions").apply {
                parentFile!!.mkdirs()
                writeText("blocked")
            }

            assertTrue(
                h.service.presentationEnded("customer-1", journeyId, CloseReason.HostDismissed),
            )
            val whilePending = h.service.handleEventForTrigger(
                StoredEvent("trigger-2", "opened", timestampMillis = now, distinctId = "customer-1"),
            ).single()
            assertTrue(whilePending is ai.nuxie.sdk.events.TriggerService.JourneyTriggerResult.Suppressed)
            assertEquals(
                SuppressReason.REENTRY_LIMITED,
                (whilePending as ai.nuxie.sdk.events.TriggerService.JourneyTriggerResult.Suppressed).reason,
            )

            assertTrue(blockedCompletionsDirectory.delete())
            h.service.recoverPendingHostDismissals()
            val afterRecovery = h.service.handleEventForTrigger(
                StoredEvent("trigger-3", "opened", timestampMillis = now, distinctId = "customer-1"),
            ).single()
            assertTrue(afterRecovery is ai.nuxie.sdk.events.TriggerService.JourneyTriggerResult.Suppressed)

            now += 1_000
            assertTrue(
                h.service.handleEventForTrigger(
                    StoredEvent("trigger-4", "opened", timestampMillis = now, distinctId = "customer-1"),
                ).single() is ai.nuxie.sdk.events.TriggerService.JourneyTriggerResult.Started,
            )
        } finally {
            h.root.deleteRecursively()
        }
    }

    @Test
    fun terminalRunImmediatelyGatesOneTimeReentryBeforeWriteBehind() = runBlocking {
        val h = harness(JourneyReentry.OneTime)
        try {
            val started = h.service.handleEventForTrigger(
                StoredEvent("trigger-1", "opened", timestampMillis = now, distinctId = "customer-1"),
            ).single() as ai.nuxie.sdk.events.TriggerService.JourneyTriggerResult.Started
            val journeyId = requireNotNull(started.ref.journeyId)

            markHostDismissed(h, journeyId)

            val durableRun = requireNotNull(JourneyStore(h.root).load("customer-1", journeyId))
            assertEquals(JourneyRunState.ACTIVE, durableRun.state)
            assertFalse(JourneyStore(h.root).hasCompleted("customer-1", "experience-1"))
            val retrigger = h.service.handleEventForTrigger(
                StoredEvent("trigger-2", "opened", timestampMillis = now, distinctId = "customer-1"),
            ).single() as ai.nuxie.sdk.events.TriggerService.JourneyTriggerResult.Suppressed
            assertEquals(SuppressReason.REENTRY_LIMITED, retrigger.reason)
        } finally {
            h.root.deleteRecursively()
        }
    }

    @Test
    fun terminalRunImmediatelyGatesWindowedReentryBeforeWriteBehind() = runBlocking {
        val h = harness(JourneyReentry.OncePerWindow(1_000))
        try {
            val started = h.service.handleEventForTrigger(
                StoredEvent("trigger-1", "opened", timestampMillis = now, distinctId = "customer-1"),
            ).single() as ai.nuxie.sdk.events.TriggerService.JourneyTriggerResult.Started

            markHostDismissed(h, requireNotNull(started.ref.journeyId))

            val retrigger = h.service.handleEventForTrigger(
                StoredEvent("trigger-2", "opened", timestampMillis = now, distinctId = "customer-1"),
            ).single() as ai.nuxie.sdk.events.TriggerService.JourneyTriggerResult.Suppressed
            assertEquals(SuppressReason.REENTRY_LIMITED, retrigger.reason)
        } finally {
            h.root.deleteRecursively()
        }
    }

    @Test
    fun terminalRunImmediatelyAllowsEveryTimeReentryBeforeWriteBehind() = runBlocking {
        val h = harness(JourneyReentry.EveryTime)
        try {
            val started = h.service.handleEventForTrigger(
                StoredEvent("trigger-1", "opened", timestampMillis = now, distinctId = "customer-1"),
            ).single() as ai.nuxie.sdk.events.TriggerService.JourneyTriggerResult.Started
            val journeyId = requireNotNull(started.ref.journeyId)

            markHostDismissed(h, journeyId)

            assertEquals(
                JourneyRunState.ACTIVE,
                JourneyStore(h.root).load("customer-1", journeyId)?.state,
            )
            val retrigger = h.service.handleEventForTrigger(
                StoredEvent("trigger-2", "opened", timestampMillis = now, distinctId = "customer-1"),
            ).single() as ai.nuxie.sdk.events.TriggerService.JourneyTriggerResult.Started
            assertTrue(retrigger.ref.journeyId != journeyId)
        } finally {
            h.root.deleteRecursively()
        }
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
                    put("experience_id", JsonPrimitive("experience-1"))
                    put("experience_version", JsonPrimitive("version-1"))
                    put("at", JsonPrimitive(10L))
                })
            }

            h.service.applyDownFacts(
                buildJsonObject { put("facts", JsonArray(listOf(fact))) },
                "customer-1",
            )

            val delivered = mutableListOf<NuxieActivityInfo>()
            ActivityForwarder(
                resolveExperience = { _, _ -> null },
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

    private suspend fun markHostDismissed(h: Harness, journeyId: String) {
        assertTrue(
            h.service.markHostDismissedInMemory(
                ownerDistinctId = "customer-1",
                journeyId = journeyId,
                initiatingDistinctId = "customer-1",
            ),
        )
    }
}
