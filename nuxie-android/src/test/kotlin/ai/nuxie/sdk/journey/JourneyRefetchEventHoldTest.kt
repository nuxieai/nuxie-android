package ai.nuxie.sdk.journey

import ai.nuxie.sdk.LogLevel
import ai.nuxie.sdk.NuxieEnvironment
import ai.nuxie.sdk.events.EventLog
import ai.nuxie.sdk.events.JourneyDeadline
import ai.nuxie.sdk.events.NuxieContextBuilder
import ai.nuxie.sdk.events.SQLiteEventStore
import ai.nuxie.sdk.events.StoredEvent
import ai.nuxie.sdk.experiences.JourneyProfileCatalog
import ai.nuxie.sdk.experiences.JourneyReleaseHighWaterStore
import ai.nuxie.sdk.experiences.JourneyReleaseLimits
import ai.nuxie.sdk.experiences.JourneyReleaseSupportedRuntime
import ai.nuxie.sdk.fixtures.FixtureRunner
import ai.nuxie.sdk.identity.IdentityProvider
import ai.nuxie.sdk.network.ProfileDeliveryAuthority
import ai.nuxie.sdk.util.IsoDates
import android.util.Base64
import java.io.File
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.Signature
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode

/**
 * While a customer's copy of the published setup is being fetched and they
 * have none, their events wait, then replay against the copy when it lands.
 * An event starts from the copy installed when it is handled (UNIV-5143,
 * the Android half of UNIV-4589 and UNIV-5164).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [23])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class JourneyRefetchEventHoldTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val keys = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
    private lateinit var directory: File
    private val releaseFixture = Json.parseToJsonElement(
        FixtureRunner.fixturesRoot().resolve("journeys/planes/release.json").readText(),
    ).jsonObject
    private val entry get() = releaseFixture.getValue("entry").jsonObject
    private val authority get() = ProfileDeliveryAuthority(
        entry.getValue("locator").jsonObject.getValue("appId").jsonPrimitive.content,
        entry.getValue("locator").jsonObject.getValue("environment").jsonPrimitive.content,
    )
    private val launches = mutableListOf<Launch>()

    @Before fun setUp() {
        directory = File(context.filesDir, "nuxie-refetch-hold")
        directory.deleteRecursively()
        context.getSharedPreferences("nuxie_journey_release_high_water", 0).edit().clear().commit()
    }

    @After fun tearDown() {
        runBlocking { launches.toList().forEach { it.close() } }
        scope.cancel()
        directory.deleteRecursively()
    }

    @Test fun `a failed first checkpoint save keeps the triggering route pending until retry`() = runBlocking {
        for (step in listOf(waitSteps().first().jsonObject, delayStep())) {
            directory.deleteRecursively()
            context.getSharedPreferences("nuxie_journey_release_high_water", 0).edit().clear().commit()
            val sleeper = WakeSleeper()
            val failPark = java.util.concurrent.atomic.AtomicBoolean(true)
            val parkWriteArmed = java.util.concurrent.atomic.AtomicBoolean(false)
            val releaseRejection = CompletableDeferred<Unit>()
            val deliveries = CopyOnWriteArrayList<Boolean>()
            val launch = Launch("customer", sleep = sleeper::sleep, prepare = { parkWriteArmed.set(true) },
                beforePersist = {
                    if (parkWriteArmed.get() && failPark.getAndSet(false)) error("First checkpoint save failed")
                }, afterJourney = { event, accepted ->
                    if (event.id == "first-park") {
                        deliveries += accepted
                        if (!accepted) releaseRejection.await()
                    }
                })
            try {
                val snapshot = launch.copy("customer", 1, "start_waiting", JsonArray(listOf(step) + waitSteps().drop(1)))
                launch.start(); launch.commit("customer", snapshot)
                assertTrue(launch.eventLog.captureIdempotently("start_waiting", emptyMap(), "first-park", "customer"))
                // Keep rejection observable even if this assertion task is descheduled past one second.
                withTimeout(10_000L) { while (deliveries.isEmpty()) delay(10) }
                assertEquals(listOf(false), deliveries.toList())
                val admitted = launch.journal().runs().single()
                assertTrue(admitted.park == null)
                assertTrue(launch.store.queryPendingLocalRoutes("customer").any { it.id == "first-park" })
                releaseRejection.complete(Unit)
                withTimeout(10_000L) { while (deliveries.lastOrNull() != true) delay(10) }
                launch.eventLog.awaitBarrier()
                val parked = launch.journal().runs().single()
                assertEquals(admitted.id, parked.id)
                assertEquals(1_000_000L, parked.park?.anchorAtMillis)
                assertEquals(1_060_000L, parked.park?.wakeAtMillis)
                assertEquals(1, launch.starts("customer"))
                assertTrue(launch.store.queryPendingLocalRoutes("customer").isEmpty())
                withTimeout(5_000L) { while (60_000L !in sleeper.pendingDurations) delay(10) }
                launch.clock.addAndGet(60_000L)
                sleeper.complete(60_000L)
                val outcome = if (step.getValue("id").jsonPrimitive.content == "delay") "took_go" else "timed_out"
                withTimeout(5_000L) { while (launch.outcomes("customer", outcome) == 0) delay(10) }
                launch.eventLog.awaitBarrier()
                assertEquals(1, launch.outcomes("customer", outcome))
            } finally { releaseRejection.complete(Unit); launch.close() }
        }
    }

    @Test fun `failed first profile fetch releases committed subscribers without a refresh`() = runBlocking {
        val committed = CopyOnWriteArrayList<String>()
        val deliveries = CopyOnWriteArrayList<Boolean>()
        val launch = Launch("customer", firstLaunch = true, beforeJourney = { event -> committed += event.name },
            afterJourney = { _, accepted -> deliveries += accepted })
        launch.start()
        launch.journeys.profileRefetchWillBegin("customer")
        launch.track("held_before_failure")
        assertTrue(committed.isEmpty())
        launch.journeys.profileRefetchDidSettle("customer")
        launch.eventLog.awaitBarrier()
        // This is the same committed subscription used by delivery.kick() in NuxieCore.
        launch.track("after_failure_1")
        launch.track("after_failure_2")
        assertEquals(listOf("held_before_failure", "after_failure_1", "after_failure_2"), committed.toList())
        assertEquals(listOf(true, true, true), deliveries.toList())
        assertTrue(launch.store.queryPendingLocalRoutes("customer").isEmpty())
        assertEquals(0, launch.starts("customer"))
    }

    @Test fun `failed no copy settle replay retries without leaving routing held`() = runBlocking {
        val failing = java.util.concurrent.atomic.AtomicBoolean(false)
        val attempts = java.util.concurrent.atomic.AtomicInteger()
        val recovered = CompletableDeferred<Unit>()
        val delivered = CopyOnWriteArrayList<String>()
        lateinit var launch: Launch
        launch = Launch("customer", replay = { owner ->
            if (failing.get()) {
                attempts.incrementAndGet()
                false
            } else launch.eventLog.replayPendingLocalRoutes(owner).also {
                if (attempts.get() > 0 && it) recovered.complete(Unit)
            }
        }, afterJourney = { event, accepted ->
            if (accepted && event.name in listOf("held", "later")) delivered += event.name
        })
        val snapshot = launch.copy("customer", 1, "start_waiting")
        launch.start(); launch.commit("customer", snapshot)
        launch.journeys.profileRefetchWillBegin("customer")
        launch.withdraw("customer")
        launch.track("held")
        assertTrue(delivered.isEmpty())
        failing.set(true)
        launch.journeys.profileRefetchDidSettle("customer")
        launch.eventLog.awaitBarrier()
        assertEquals("A replay failure must release the refetch hold", listOf("held"), delivered.toList())
        withTimeout(10_000L) { while (attempts.get() < 3) delay(10) }
        failing.set(false)
        // Storage recovery alone lets the shared one-second retry finish reconciliation.
        withTimeout(10_000L) { recovered.await() }
        launch.track("later")
        assertEquals(listOf("held", "later"), delivered.toList())
    }

    @Test fun `language change hold queues its deadline before a later event`() = runBlocking {
        val sleeper = WakeSleeper()
        val enqueued = CompletableDeferred<Unit>()
        val delivered = CopyOnWriteArrayList<String>()
        lateinit var launch: Launch
        launch = Launch("customer", sleep = sleeper::sleep, enqueueDeadline = { deadline ->
            launch.eventLog.enqueueJourneyDeadline(deadline)
            enqueued.complete(Unit)
        }, beforeJourney = { event -> if (event.name == "later") delivered += "event" })
        val snapshot = launch.copy("customer", 1, "start_waiting", waitSteps(maxTimeMillis = 10_000L))
        launch.start(); launch.commit("customer", snapshot); launch.track("start_waiting")
        launch.eventLog.setJourneyDeadlineHandler { deadline ->
            delivered += "deadline"
            launch.journeys.handleJourneyDeadline(deadline)
        }
        // Language changes begin the refetch before withdrawing the current copy.
        launch.journeys.profileRefetchWillBegin("customer")
        launch.withdraw("customer")
        withTimeout(5_000L) { while (10_000L !in sleeper.pendingDurations) delay(10) }
        launch.clock.addAndGet(10_000L)
        sleeper.complete(10_000L)
        withTimeout(5_000L) { enqueued.await() }
        launch.clock.addAndGet(5_000L)
        launch.track("later")
        assertTrue(delivered.isEmpty())
        launch.commit("customer", snapshot)
        launch.journeys.profileRefetchDidSettle("customer")
        launch.eventLog.awaitBarrier()
        assertEquals(listOf("deadline", "event"), delivered.toList())
        assertEquals(1, launch.outcomes("customer", "timed_out"))
    }

    @Test fun `saved matching event retries failed foreground resume before its deadline`() = runBlocking {
        val sleeper = WakeSleeper()
        val failedWrite = java.util.concurrent.atomic.AtomicBoolean(false)
        val failNextWrite = java.util.concurrent.atomic.AtomicBoolean(false)
        val failuresLeft = java.util.concurrent.atomic.AtomicInteger(3)
        val releaseRetry = CompletableDeferred<Unit>()
        val deadlines = CopyOnWriteArrayList<JourneyDeadline>()
        val launch = Launch("customer", withScreens = true, sleep = sleeper::sleep,
            readValues = { values ->
                if (failedWrite.get()) releaseRetry.await()
                values.journeyValues()
            }, beforeResume = {
                if (failuresLeft.get() > 0) failNextWrite.set(true)
            }, beforePersist = {
                if (failNextWrite.getAndSet(false)) {
                    failuresLeft.decrementAndGet()
                    failedWrite.set(true)
                    error("Saved match resume write failed")
                }
            })
        try {
            val snapshot = launch.copy("customer", 1, "start_waiting")
            launch.start(); launch.commit("customer", snapshot); launch.track("start_waiting")
            launch.journeys.onAppDidEnterBackground()
            launch.clock.addAndGet(100L)
            launch.track("go")
            val saved = launch.journal().runs().single()
            assertEquals("go", saved.park?.pendingEvent?.name)
            assertTrue(launch.store.queryPendingLocalRoutes("customer").isEmpty())
            launch.eventLog.setJourneyDeadlineHandler { deadline ->
                deadlines += deadline
                launch.journeys.handleJourneyDeadline(deadline)
            }
            launch.journeys.onAppWillEnterForeground()
            assertTrue(failedWrite.get())
            assertEquals(saved.park, launch.journal().runs().single().park)
            assertEquals(0, launch.outcomes("customer", "took_go"))
            assertTrue("The shared route timer owns retry, with no Journey wake", sleeper.pendingDurations.isEmpty())
            releaseRetry.complete(Unit)
            // No clock advance, capture, lifecycle callback or fake sleep rescues the saved match.
            withTimeout(10_000L) { while (launch.outcomes("customer", "took_go") == 0) delay(10) }
            launch.eventLog.awaitBarrier()
            assertEquals(0, failuresLeft.get())
            assertEquals(1, launch.outcomes("customer", "took_go"))
            assertEquals(0, launch.outcomes("customer", "timed_out"))
            assertEquals(1_000_100L, launch.clock.get())
            assertTrue(requireNotNull(saved.park?.wakeAtMillis) > launch.clock.get())
            assertTrue(deadlines.isEmpty())
            launch.clock.addAndGet(60_000L)
            sleeper.completeAll()
            launch.eventLog.awaitBarrier()
            assertTrue(deadlines.isEmpty())
            assertEquals(1, launch.outcomes("customer", "took_go"))
        } finally { releaseRetry.complete(Unit) }
    }

    @Test fun `held routing queues every deadline before events accepted after it`() = runBlocking {
        val sleeper = WakeSleeper()
        val secondWakeScheduled = CompletableDeferred<Unit>()
        val observed = CopyOnWriteArrayList<String>()
        lateinit var launch: Launch
        launch = Launch("customer", sleep = sleeper::sleep, readRuns = { journal ->
            journal.runs().also {
                if (launch.clock.get() == 1_020_000L) secondWakeScheduled.complete(Unit)
            }
        }, beforeJourney = { event -> if (event.name == "later") observed += "event25" })
        val snapshot = launch.copy("customer", 1, "start_waiting", waitSteps(maxTimeMillis = 20_000L))
        val journal = launch.journal()
        for (seconds in listOf(10L, 20L)) {
            val anchor = 1_000_000L + (seconds - 20L) * 1_000L
            val run = requireNotNull(journal.admit(snapshot.profile.armedLegs.single(), JourneyFrequency.EveryMatch,
                "wait", anchor, release = snapshot.profile.releases.single(),
                executionSnapshot = JourneyRun.ExecutionSnapshot(snapshot.profile.delivery,
                    snapshot.profile.facts.getValue("assignments").jsonObject)))
            journal.markStartedQueued(run)
            journal.transition(run.id, "wait", run.context,
                JourneyControlExecutor.Checkpoint(anchor, 1_000_000L + seconds * 1_000L))
        }
        launch.start()
        launch.eventLog.setJourneyDeadlineHandler { deadline ->
            observed += if (deadline.wakeAtMillis == 1_010_000L) "deadline10" else "deadline20"
            launch.journeys.handleJourneyDeadline(deadline)
        }
        val hold = launch.eventLog.deferCommittedRouting()
        try {
            withTimeout(5_000L) { while (10_000L !in sleeper.pendingDurations) delay(10) }
            launch.clock.addAndGet(10_000L)
            sleeper.complete(10_000L)
            withTimeout(5_000L) { while (10_000L !in sleeper.pendingDurations) delay(10) }
            assertTrue("The second timer is armed while the first deadline is held", observed.isEmpty())
            launch.clock.addAndGet(10_000L)
            sleeper.complete(10_000L)
            withTimeout(5_000L) { secondWakeScheduled.await() }
            launch.clock.addAndGet(5_000L)
            launch.track("later")
            assertTrue(observed.isEmpty())
            launch.clock.addAndGet(5_000L)
            launch.eventLog.releaseCommittedRouting(hold)
            launch.eventLog.awaitBarrier()
            assertEquals(listOf("deadline10", "deadline20", "event25"), observed.toList())
            assertEquals(2, launch.outcomes("customer", "timed_out"))
        } finally { launch.eventLog.releaseCommittedRouting(hold) }
    }

    @Test fun `native restoration retries in background only for screenless waits`() = runBlocking {
        for (hasScreens in listOf(false, true)) {
            directory.deleteRecursively()
            context.getSharedPreferences("nuxie_journey_release_high_water", 0).edit().clear().commit()
            val sleeper = WakeSleeper()
            val failing = java.util.concurrent.atomic.AtomicBoolean(true)
            val preparations = java.util.concurrent.atomic.AtomicInteger()
            val retryArmed = java.util.concurrent.atomic.AtomicBoolean(false)
            val retryReads = java.util.concurrent.atomic.AtomicInteger()
            val retryPass = CompletableDeferred<Unit>()
            val launch = Launch("customer", withScreens = hasScreens, sleep = sleeper::sleep,
                prepare = { preparations.incrementAndGet(); check(!failing.get()) { "Native restoration failed" } },
                readRuns = { journal -> journal.runs().also {
                    if (retryArmed.get() && retryReads.incrementAndGet() == 2) retryPass.complete(Unit)
                } })
            val snapshot = launch.copy("customer", 1, "start_waiting", waitSteps(maxTimeMillis = 10_000L))
            launch.start(); launch.commit("customer", snapshot); launch.track("start_waiting")
            val park = launch.journal().runs().single().park
            assertEquals(1_010_000L, park?.wakeAtMillis)
            withTimeout(5_000L) { while (5_000L !in sleeper.pendingDurations) delay(10) }
            assertEquals(listOf(5_000L), sleeper.pendingDurations)
            launch.journeys.onAppDidEnterBackground()
            val before = preparations.get()
            failing.set(false)
            launch.clock.addAndGet(5_000L)
            retryArmed.set(true)
            sleeper.complete(5_000L)
            withTimeout(5_000L) { retryPass.await() }
            assertEquals(0, launch.outcomes("customer", "timed_out"))
            assertEquals(park, launch.journal().runs().single().park)
            if (hasScreens) {
                assertEquals(before, preparations.get())
                assertTrue(sleeper.pendingDurations.isEmpty())
            } else {
                assertTrue(preparations.get() > before)
                withTimeout(5_000L) { while (5_000L !in sleeper.pendingDurations) delay(10) }
                assertEquals(listOf(5_000L), sleeper.pendingDurations)
            }
            launch.clock.addAndGet(5_000L)
            sleeper.complete(5_000L)
            if (hasScreens) {
                launch.eventLog.awaitBarrier()
                assertEquals(0, launch.outcomes("customer", "timed_out"))
                launch.journeys.onAppWillEnterForeground()
            }
            withTimeout(5_000L) { while (launch.outcomes("customer", "timed_out") == 0) delay(10) }
            launch.eventLog.awaitBarrier()
            assertEquals(1, launch.outcomes("customer", "timed_out"))
            assertTrue(preparations.get() > before)
            launch.close()
        }
    }

    @Test fun `matching event survives failed restoration and retries before the deadline`() = runBlocking {
        val sleeper = WakeSleeper()
        val failing = java.util.concurrent.atomic.AtomicBoolean(false)
        val launch = Launch("customer", sleep = sleeper::sleep, readValues = { values ->
            check(!failing.get()) { "Native restoration failed" }
            values.journeyValues()
        })
        val snapshot = launch.copy("customer", 1, "start_waiting")
        launch.start(); launch.commit("customer", snapshot); launch.track("start_waiting")
        val original = launch.journal().runs().single()
        launch.clock.addAndGet(100L)
        failing.set(true)
        launch.track("go")
        val retained = launch.journal().runs().single()
        assertEquals(original.park?.wakeAtMillis, retained.park?.wakeAtMillis)
        assertEquals(listOf("go"), retained.park?.candidateEvents?.map { it.name })
        assertEquals(0, launch.outcomes("customer", "took_go"))
        withTimeout(5_000L) { while (5_000L !in sleeper.pendingDurations) delay(10) }
        assertEquals(listOf(5_000L), sleeper.pendingDurations)
        assertTrue(launch.store.queryPendingLocalRoutes("customer").isEmpty())
        failing.set(false)
        launch.clock.addAndGet(5_000L)
        sleeper.complete(5_000L)
        withTimeout(5_000L) { while (launch.outcomes("customer", "took_go") == 0) delay(10) }
        launch.eventLog.awaitBarrier()
        assertEquals(1, launch.outcomes("customer", "took_go"))
        assertEquals(0, launch.outcomes("customer", "timed_out"))
        assertTrue(requireNotNull(original.park?.wakeAtMillis) > launch.clock.get())
        assertTrue(sleeper.pendingDurations.isEmpty())
    }

    @Test fun `failed journal inspection retries a head without any park or deadline`() = runBlocking {
        val failing = java.util.concurrent.atomic.AtomicBoolean(false)
        val head = CopyOnWriteArrayList<Boolean>()
        val later = CopyOnWriteArrayList<Boolean>()
        val launch = Launch("customer", readRuns = { journal ->
            check(!failing.get()) { "journal unavailable" }; journal.runs()
        }, afterJourney = { event, accepted ->
            if (event.name == "ordinary") head += accepted
            if (event.name == "later") later += accepted
        })
        val snapshot = launch.copy("customer", 1, "start_waiting")
        launch.start(); launch.commit("customer", snapshot)
        failing.set(true)
        launch.eventLog.capture("ordinary")
        launch.eventLog.capture("later")
        withTimeout(5_000L) { while (head.size < 3) delay(10) }
        assertTrue(head.all { !it })
        assertTrue(later.isEmpty())
        assertEquals(listOf("ordinary", "later"), launch.store.queryPendingLocalRoutes("customer").map { it.name })
        failing.set(false)
        // Only the route retry timer can release this head, with no timed runs.
        withTimeout(5_000L) { while (later.isEmpty()) delay(10) }
        assertEquals(true, head.last())
        assertEquals(listOf(true), later.toList())
        launch.eventLog.awaitBarrier()
        assertTrue(launch.journal().runs().isEmpty())
        assertTrue(launch.store.queryPendingLocalRoutes("customer").isEmpty())
    }

    @Test fun `future screenless deadline runs in background without another event`() = runBlocking {
        val sleeper = WakeSleeper()
        val launch = Launch("customer", dropJourneyStarted = true, sleep = sleeper::sleep)
        val snapshot = launch.copy("customer", 1, "start_waiting", waitSteps(maxTimeMillis = 500L))
        launch.start(); launch.commit("customer", snapshot); launch.track("start_waiting")
        launch.journeys.onAppDidEnterBackground()
        withTimeout(5_000L) { while (500L !in sleeper.pendingDurations) delay(10) }
        launch.clock.addAndGet(500L)
        sleeper.complete(500L)
        withTimeout(5_000L) { while (launch.outcomes("customer", "timed_out") == 0) delay(10) }
        assertEquals(1, launch.outcomes("customer", "timed_out"))
        assertTrue(launch.store.pendingBatch(100).none { it.name == JourneyEventNames.LEG_STARTED })
    }

    @Test fun `deadline failures are consumed and retry from the durable park`() = runBlocking {
        for (failure in listOf("read", "write", "native")) {
            directory.deleteRecursively()
            context.getSharedPreferences("nuxie_journey_release_high_water", 0).edit().clear().commit()
            val sleeper = WakeSleeper()
            val armed = java.util.concurrent.atomic.AtomicBoolean(false)
            val failedWrite = java.util.concurrent.atomic.AtomicBoolean(false)
            val launch = Launch("customer", sleep = sleeper::sleep,
                readRuns = { journal -> check(failure != "read" || !armed.get()); journal.runs() },
                readValues = { values -> check(failure != "native" || !armed.get()); values.journeyValues() },
                beforePersist = { if (failure == "write" && armed.get() && !failedWrite.getAndSet(true)) error("write failed") })
            val snapshot = launch.copy("customer", 1, "start_waiting", waitSteps(maxTimeMillis = 500L))
            launch.start(); launch.commit("customer", snapshot); launch.track("start_waiting")
            val parked = launch.journal().runs().single()
            val controls = CopyOnWriteArrayList<Boolean>()
            launch.eventLog.setJourneyDeadlineHandler { deadline ->
                launch.journeys.handleJourneyDeadline(deadline).also { controls += it }
            }
            armed.set(true)
            launch.clock.addAndGet(1_000L)
            launch.eventLog.enqueueJourneyDeadline(deadline(parked))
            launch.eventLog.awaitBarrier()
            assertEquals(listOf(true), controls.toList())
            assertEquals(parked.park, launch.journal().runs().single().park)
            if (failure == "write") assertTrue(failedWrite.get())
            if (failure == "native") {
                withTimeout(5_000L) { while (5_000L !in sleeper.pendingDurations) delay(10) }
                assertEquals(listOf(5_000L), sleeper.pendingDurations)
            }
            // A later event passes the consumed control even while the park is unavailable.
            launch.eventLog.capture(JourneyEventNames.LEG_STARTED)
            launch.eventLog.awaitBarrier()
            assertTrue(launch.store.queryPendingLocalRoutes("customer").isEmpty())
            armed.set(false)
            val retry = if (failure == "native") 5_000L else 1_000L
            launch.clock.addAndGet(retry)
            withTimeout(5_000L) {
                while (launch.outcomes("customer", "timed_out") == 0) {
                    sleeper.complete(retry)
                    delay(10)
                }
            }
            assertEquals(1, launch.outcomes("customer", "timed_out"))
            launch.close()
        }
    }

    @Test fun `background rendered deadline skips failing native reads and lets later events through`() = runBlocking {
        val failing = java.util.concurrent.atomic.AtomicBoolean(false)
        val reads = java.util.concurrent.atomic.AtomicInteger()
        val delivered = CopyOnWriteArrayList<Boolean>()
        val launch = Launch("customer", withScreens = true, readValues = { values ->
            reads.incrementAndGet(); check(!failing.get()); values.journeyValues()
        }, afterJourney = { event, accepted -> if (event.name == "unrelated") delivered += accepted })
        val snapshot = launch.copy("customer", 1, "start_waiting", waitSteps(maxTimeMillis = 500L))
        launch.start(); launch.commit("customer", snapshot); launch.track("start_waiting")
        val parked = launch.journal().runs().single()
        launch.journeys.onAppDidEnterBackground()
        val before = reads.get()
        failing.set(true)
        launch.clock.addAndGet(1_000L)
        launch.eventLog.enqueueJourneyDeadline(deadline(parked))
        launch.eventLog.awaitBarrier()
        assertEquals(before, reads.get())
        launch.track("unrelated")
        assertEquals(listOf(true), delivered.toList())
        assertTrue(launch.store.queryPendingLocalRoutes("customer").isEmpty())
        failing.set(false)
        // A separate screenless release can still start and finish in the background.
        val screenless = launch.copy("customer", 2, "start_screenless", waitSteps(maxTimeMillis = 500L), screens = false)
        launch.commit("customer", screenless)
        launch.clock.addAndGet(800L)
        assertTrue(launch.journeys.handleEvent(StoredEvent("screenless-trigger", "start_screenless",
            timestampMillis = launch.clock.get() - 800L, distinctId = "customer"), launch.journeys.eventAdmissionGeneration()))
        launch.eventLog.awaitBarrier()
        assertEquals(1, launch.outcomes("customer", "timed_out"))
        assertTrue(launch.journal().runs().any { it.id == parked.id && it.park != null })
        launch.clock.addAndGet(5_000L)
        launch.journeys.onAppWillEnterForeground()
        launch.eventLog.awaitBarrier()
        assertEquals(2, launch.outcomes("customer", "timed_out"))
    }

    @Test fun `replacing a failed recovery journal clears enqueued deadline markers`() = runBlocking {
        val queued = CopyOnWriteArrayList<JourneyDeadline>()
        var failRecovery = true
        lateinit var launch: Launch
        launch = Launch("customer", enqueueDeadline = { queued += it }, replay = {
            if (failRecovery) {
                launch.journeys.handleEvent(StoredEvent("recovery-trigger", "start_waiting",
                    timestampMillis = launch.clock.get() - 1_000L, distinctId = "customer"),
                    launch.journeys.eventAdmissionGeneration())
                false
            } else true
        })
        val snapshot = launch.copy("customer", 1, "start_waiting", waitSteps(maxTimeMillis = 500L))
        launch.start()
        launch.commit("customer", snapshot)
        assertEquals(1, queued.size)
        failRecovery = false
        launch.commit("customer", snapshot)
        assertEquals(2, queued.size)
        assertEquals(queued.first(), queued.last())
        assertTrue(launch.journeys.handleJourneyDeadline(queued.last()))
        assertEquals(1, launch.outcomes("customer", "timed_out"))
    }

    @Test fun `a stale deadline cannot consume a moved on checkpoint`() = runBlocking {
        val sleeper = WakeSleeper()
        val launch = Launch("customer", sleep = sleeper::sleep)
        val first = waitSteps(maxTimeMillis = 500L).first().jsonObject
        val second = waitSteps(eventName = "finish", maxTimeMillis = 2_000L).first().jsonObject
        val steps = JsonArray(listOf(JsonObject(first + ("outlets" to buildJsonObject {
            put("satisfied", "second_wait"); put("timeout", "timed_out")
        }))) + waitSteps().drop(1) + JsonObject(second + ("id" to JsonPrimitive("second_wait"))))
        val snapshot = launch.copy("customer", 1, "start_waiting", steps)
        launch.start(); launch.commit("customer", snapshot); launch.track("start_waiting")
        val stale = deadline(launch.journal().runs().single())
        launch.clock.addAndGet(250L)
        launch.track("go")
        val current = launch.journal().runs().single()
        assertEquals("second_wait", current.stepId)
        launch.clock.addAndGet(500L)
        assertTrue(launch.journeys.handleJourneyDeadline(stale))
        assertEquals(current.park, launch.journal().runs().single().park)
        assertEquals(0, launch.outcomes("customer", "timed_out"))
        launch.clock.addAndGet(2_000L)
        assertTrue(launch.journeys.handleJourneyDeadline(deadline(current)))
        assertEquals(1, launch.outcomes("customer", "timed_out"))
    }

    @Test fun `restart recreates overdue deadlines after recovery replay`() = runBlocking {
        for (followUp in listOf(true, false)) {
            directory.deleteRecursively()
            context.getSharedPreferences("nuxie_journey_release_high_water", 0).edit().clear().commit()
            val initial = Launch("customer", sleep = WakeSleeper()::sleep)
            val snapshot = initial.copy("customer", 1, "start_waiting", waitSteps(maxTimeMillis = 500L))
            initial.start(); initial.commit("customer", snapshot); initial.track("start_waiting")
            val parked = initial.journal().runs().single()
            if (followUp) initial.store.insertPendingIfAbsentAndStageRoute(StoredEvent(
                "restart-go", "go", timestampMillis = initial.clock.get() + 100L, distinctId = "customer"))
            initial.close()
            val recovered = Launch("customer")
            recovered.copy("customer", 1, "start_waiting", waitSteps(maxTimeMillis = 500L))
            recovered.clock.set(parked.startedAtMillis + 1_000L)
            recovered.start()
            recovered.eventLog.awaitBarrier()
            val outcome = if (followUp) "took_go" else "timed_out"
            assertEquals(1, recovered.outcomes("customer", outcome))
            assertTrue(recovered.store.queryPendingLocalRoutes("customer").isEmpty())
            recovered.close()
        }
    }

    private fun deadline(run: JourneyRun): JourneyDeadline {
        val park = requireNotNull(run.park)
        return JourneyDeadline("customer", run.id, run.stepId, park.anchorAtMillis, requireNotNull(park.wakeAtMillis))
    }

    @Test fun `overdue screenless waits and delays catch up in background when started is dropped`() = runBlocking {
        for (step in listOf(waitSteps(maxTimeMillis = 500L).first().jsonObject, delayStep(500L))) {
            directory.deleteRecursively()
            context.getSharedPreferences("nuxie_journey_release_high_water", 0).edit().clear().commit()
            val sleeper = WakeSleeper()
            lateinit var launch: Launch
            launch = Launch("customer", dropJourneyStarted = true, sleep = sleeper::sleep, beforeJourney = { event ->
                if (event.name == "start_waiting") launch.clock.addAndGet(800L)
            })
            val snapshot = launch.copy("customer", 1, "start_waiting", JsonArray(listOf(step) + waitSteps().drop(1)))
            launch.start()
            launch.commit("customer", snapshot)
            launch.journeys.onAppDidEnterBackground()
            launch.track("start_waiting")
            assertTrue(launch.store.pendingBatch(100).none { it.name == JourneyEventNames.LEG_STARTED })
            // Parking itself enqueues the overdue checkpoint, with no later activity.
            val outcome = if (step.getValue("id").jsonPrimitive.content == "delay") "took_go" else "timed_out"
            withTimeout(5_000L) { while (launch.outcomes("customer", outcome) == 0) delay(10) }
            launch.eventLog.awaitBarrier()
            assertEquals(1, launch.outcomes("customer", outcome))
            assertEquals(1_000_800L, launch.clock.get())
            launch.close()
        }
    }

    @Test fun `an overdue Journey with screens keeps background catch-up closed`() = runBlocking {
        val sleeper = WakeSleeper()
        val preparing = CompletableDeferred<Unit>()
        val releasePreparation = CompletableDeferred<Unit>()
        var firstPreparation = true
        lateinit var launch: Launch
        launch = Launch("customer", withScreens = true, dropJourneyStarted = true, sleep = sleeper::sleep,
            prepare = {
                if (firstPreparation) {
                    firstPreparation = false
                    preparing.complete(Unit)
                    releasePreparation.await()
                }
            }, beforeJourney = { event ->
                if (event.name == "start_waiting") launch.clock.addAndGet(800L)
            })
        val snapshot = launch.copy("customer", 1, "start_waiting", waitSteps(maxTimeMillis = 500L))
        launch.start()
        launch.commit("customer", snapshot)
        launch.eventLog.capture("start_waiting")
        try {
            withTimeout(5_000L) {
                preparing.await()
                // Admission happened in the foreground. Change visibility while
                // preparation is suspended, before its overdue park is saved.
                launch.journeys.onAppVisibilityChanged(false)
                val backgrounding = async(start = CoroutineStart.UNDISPATCHED) { launch.journeys.settleAppBackground() }
                releasePreparation.complete(Unit)
                backgrounding.await()
                launch.eventLog.awaitBarrier()
            }
            assertEquals(1_000_500L, launch.journal().runs().single().park?.wakeAtMillis)
            assertTrue(0L !in sleeper.pendingDurations)
            assertEquals(0, launch.outcomes("customer", "timed_out"))
            assertTrue(launch.store.pendingBatch(100).none { it.name == JourneyEventNames.LEG_STARTED })

            launch.journeys.onAppWillEnterForeground()
            launch.eventLog.awaitBarrier()
            assertEquals(1, launch.outcomes("customer", "timed_out"))
        } finally { releasePreparation.complete(Unit) }
    }

    @Test fun `initial park survives native preparation failure for waits and delays`() = runBlocking {
        for (step in listOf(waitSteps().first().jsonObject, delayStep())) {
            directory.deleteRecursively()
            context.getSharedPreferences("nuxie_journey_release_high_water", 0).edit().clear().commit()
            val sleeper = WakeSleeper()
            var failPreparation = true
            var preparations = 0
            lateinit var launch: Launch
            launch = Launch("customer", sleep = sleeper::sleep, prepare = {
                preparations++
                check(!failPreparation) { "native preparation failed" }
            }, beforeJourney = { event ->
                if (event.name == "start_waiting") launch.clock.set(1_000_800L)
            })
            val snapshot = launch.copy("customer", 1, "start_waiting", JsonArray(listOf(step) + waitSteps().drop(1)))
            launch.start()
            launch.commit("customer", snapshot)
            // Captured at 0 ms, handled 800 ms late, with no native reads
            // before the initial park tries and fails to prepare its values.
            launch.track("start_waiting")
            val parked = launch.journal().runs().single()
            assertTrue(preparations > 0)
            assertEquals(step.getValue("id").jsonPrimitive.content, parked.stepId)
            assertEquals(1_000_000L, parked.park?.anchorAtMillis)
            assertEquals(1_060_000L, parked.park?.wakeAtMillis)
            assertTrue(parked.completion == null)
            assertTrue(launch.store.queryPendingLocalRoutes("customer").isEmpty())
            // A fresh journal must retain the checkpoint rather than abandon
            // an admitted run with no recovery cursor.
            assertEquals(parked.park, launch.journal().recover(launch.clock.get()).single().park)

            withTimeout(5_000L) { while (5_000L !in sleeper.pendingDurations) delay(10) }
            assertEquals(listOf(5_000L), sleeper.pendingDurations)
            launch.track("unrelated")
            assertEquals("The checkpoint keeps its original restoration timer", listOf(5_000L), sleeper.pendingDurations)
            val attemptsBeforeRetry = preparations
            failPreparation = false
            launch.clock.addAndGet(5_000L)
            sleeper.complete(5_000L)
            // Only the retry timer enters the future checkpoint. Its original
            // deadline timer must then be restored, with 54.2 seconds left.
            withTimeout(5_000L) { while (54_200L !in sleeper.pendingDurations) delay(10) }
            assertTrue(preparations > attemptsBeforeRetry)
            assertEquals(parked.park, launch.journal().runs().single().park)
            assertEquals(1, launch.starts("customer"))

            launch.clock.set(1_060_800L)
            if (step.getValue("id").jsonPrimitive.content == "wait") {
                assertTrue(launch.journeys.handleEvent(StoredEvent("held-follow-up", "go",
                    timestampMillis = 1_000_100L, distinctId = "customer"), launch.journeys.eventAdmissionGeneration()))
            } else launch.journeys.onAppWillEnterForeground()
            launch.eventLog.awaitBarrier()
            assertEquals(1, launch.outcomes("customer", "took_go"))
            assertEquals(0, launch.outcomes("customer", "timed_out"))
            launch.close()
        }
    }

    @Test fun `a failed journal inspection keeps the follow-up route replayable and retries`() = runBlocking {
        val failInspection = java.util.concurrent.atomic.AtomicBoolean(false)
        val deliveries = CopyOnWriteArrayList<Boolean>()
        lateinit var launch: Launch
        launch = Launch("customer", readRuns = { journal ->
            check(!failInspection.get()) { "journal inspection failed" }
            journal.runs()
        }, afterJourney = { event, accepted ->
            if (event.id == "replayable-follow-up") deliveries += accepted
        })
        val snapshot = launch.copy("customer", 1, "start_waiting", waitSteps(maxTimeMillis = 500L))
        launch.start()
        launch.commit("customer", snapshot)
        launch.track("start_waiting")
        launch.clock.addAndGet(100L)
        failInspection.set(true)
        assertTrue(launch.eventLog.captureIdempotently("go", emptyMap(), "replayable-follow-up", "customer"))
        launch.eventLog.awaitBarrier()

        assertTrue(deliveries.isNotEmpty() && deliveries.all { !it })
        assertTrue(launch.store.queryPendingLocalRoutes("customer").any { it.id == "replayable-follow-up" })
        assertTrue(launch.journal().runs().single().park?.candidateEvents?.isEmpty() == true)

        failInspection.set(false)
        launch.clock.addAndGet(700L)
        // The route timer replays after the deadline, offering the captured
        // 100 ms event to its saved window without another capture or lifecycle call.
        withTimeout(5_000L) { while (deliveries.lastOrNull() != true) delay(10) }
        launch.eventLog.awaitBarrier()
        assertEquals(true, deliveries.last())
        assertTrue(deliveries.dropLast(1).all { !it })
        assertTrue(launch.store.queryPendingLocalRoutes("customer").isEmpty())
        assertEquals(1, launch.outcomes("customer", "took_go"))
        assertEquals(0, launch.outcomes("customer", "timed_out"))
    }

    @Test fun `queued live event timeout vectors`() = runBlocking {
        val vectors = Json.parseToJsonElement(FixtureRunner.fixturesRoot()
            .resolve("sdk/committed-event-timeout-order.json").readText()).jsonObject.getValue("cases").jsonArray
        assertTrue(vectors.isNotEmpty())
        for (vector in vectors.map { it.jsonObject }) {
            directory.deleteRecursively()
            context.getSharedPreferences("nuxie_journey_release_high_water", 0).edit().clear().commit()
            val name = vector.getValue("name").jsonPrimitive.content
            val trigger = vector.getValue("trigger").jsonObject
            val followUp = vector["followUp"] as? JsonObject
            val preparing = CompletableDeferred<Unit>()
            val releasePreparation = CompletableDeferred<Unit>()
            val origin = 1_000_000L
            var firstPreparation = true
            lateinit var launch: Launch
            launch = Launch("customer", prepare = {
                if (firstPreparation) {
                    firstPreparation = false
                    preparing.complete(Unit)
                    releasePreparation.await()
                }
            }, afterJourney = { event, accepted ->
                if (accepted && event.name == trigger.getValue("name").jsonPrimitive.content) {
                    // The trigger still occupies EventLog's route worker.
                    // Activation queues the deadline behind the accepted follow-up.
                    launch.journeys.onAppWillEnterForeground()
                    if (followUp != null) assertEquals(name, 0, launch.outcomes("customer", "timeout"))
                }
            }, beforeJourney = { event ->
                if (event.name == trigger.getValue("name").jsonPrimitive.content) {
                    launch.clock.set(origin + trigger.getValue("handledAtMillis").jsonPrimitive.long)
                }
            })
            val snapshot = launch.copy("customer", 1, trigger.getValue("name").jsonPrimitive.content,
                waitSteps(vector.getValue("waitEventName").jsonPrimitive.content,
                    vector.getValue("waitMaxTimeMillis").jsonPrimitive.long, "satisfied", "timeout"))
            launch.start()
            launch.commit("customer", snapshot)
            launch.clock.set(origin + trigger.getValue("atMillis").jsonPrimitive.long)
            launch.eventLog.capture(trigger.getValue("name").jsonPrimitive.content)
            try {
                withTimeout(5_000L) {
                    preparing.await()
                    if (followUp != null) {
                        launch.clock.set(origin + followUp.getValue("atMillis").jsonPrimitive.long)
                        assertTrue(launch.eventLog.captureIdempotently(
                            followUp.getValue("name").jsonPrimitive.content, emptyMap(), "queued-follow-up", "customer"))
                        assertTrue(name, launch.store.queryPendingLocalRoutes("customer").any { it.id == "queued-follow-up" })
                    }
                    launch.clock.set(origin + vector.getValue("preparationFinishesAtMillis").jsonPrimitive.long)
                    releasePreparation.complete(Unit)
                    launch.eventLog.awaitBarrier()
                    val outcome = vector.getValue("expectedOutcome").jsonPrimitive.content
                    while (launch.outcomes("customer", outcome) == 0) delay(10)
                    assertEquals(name, 1, launch.outcomes("customer", outcome))
                    assertEquals(name, 1, launch.starts("customer"))
                }
            } finally { releasePreparation.complete(Unit) }
            launch.close()
        }
    }

    @Test fun `an empty routing hold release catches up without another event`() = runBlocking {
        val launch = Launch("customer")
        val snapshot = launch.copy("customer", 1, "start_waiting", waitSteps(maxTimeMillis = 500L))
        launch.start(); launch.commit("customer", snapshot); launch.track("start_waiting")
        val held = launch.eventLog.deferCommittedRouting()
        launch.clock.addAndGet(1_000L)
        launch.journeys.onAppWillEnterForeground()
        assertEquals(0, launch.outcomes("customer", "timed_out"))
        launch.journeys.onAppDidEnterBackground()
        // Releasing the hold delivers its queued deadline without another capture.
        launch.eventLog.releaseCommittedRouting(held)
        launch.eventLog.awaitBarrier()
        assertEquals(1, launch.outcomes("customer", "timed_out"))
    }

    @Test fun `expired screenless waits and delays advance on a background event`() = runBlocking {
        for (step in listOf(waitSteps(maxTimeMillis = 500L).first().jsonObject, delayStep(500L))) {
            directory.deleteRecursively()
            context.getSharedPreferences("nuxie_journey_release_high_water", 0).edit().clear().commit()
            lateinit var launch: Launch
            launch = Launch("customer", beforeJourney = { event ->
                if (event.name == "background_tick") launch.clock.set(1_002_000L)
            })
            val snapshot = launch.copy("customer", 1, "start_waiting", JsonArray(listOf(step) + waitSteps().drop(1)))
            launch.start(); launch.commit("customer", snapshot); launch.track("start_waiting")
            launch.journeys.onAppDidEnterBackground()
            // Captured before the deadline, handled after it in the background.
            // This event must cause a queued catch-up even though its own time is early.
            launch.clock.addAndGet(100L)
            launch.track("background_tick")
            val outcome = if (step.getValue("id").jsonPrimitive.content == "delay") "took_go" else "timed_out"
            assertEquals(1, launch.outcomes("customer", outcome))
            launch.close()
        }
    }

    /** Both app events use the real first-launch hold and durable route replay. */
    @Test fun `held event time vectors`() = runBlocking {
        val vectors = Json.parseToJsonElement(
            FixtureRunner.fixturesRoot().resolve("sdk/held-event-time.json").readText(),
        ).jsonObject.getValue("cases").jsonArray
        assertTrue(vectors.isNotEmpty())
        for (vector in vectors.map { it.jsonObject }) {
            val name = vector.getValue("name").jsonPrimitive.content
            assertEquals(name, "first-launch", vector.getValue("hold").jsonPrimitive.content)
            directory.deleteRecursively()
            context.getSharedPreferences("nuxie_journey_release_high_water", 0).edit().clear().commit()
            val journey = vector.getValue("journey").jsonObject
            val trigger = journey.getValue("triggerEvent").jsonPrimitive.content
            val wait = journey.getValue("wait").jsonObject
            val expected = vector.getValue("expected").jsonObject
            val origin = 1_000_000L
            val handledAt = origin + vector.getValue("copyArrivesAtMillis").jsonPrimitive.long
            var observed: JourneyRun? = null
            val replayPaused = CompletableDeferred<Unit>()
            val releaseReplay = CompletableDeferred<Unit>()
            lateinit var launch: Launch
            launch = Launch("customer", firstLaunch = true, afterJourney = { event, accepted ->
                if (event.name == trigger && accepted) {
                    observed = launch.journal().runs().single()
                    replayPaused.complete(Unit)
                    releaseReplay.await()
                }
            })
            launch.start()
            for (event in vector.getValue("events").jsonArray.map { it.jsonObject }) {
                launch.clock.set(origin + event.getValue("atMillis").jsonPrimitive.long)
                launch.track(event.getValue("name").jsonPrimitive.content)
            }
            assertEquals(name, 0, launch.starts("customer"))
            assertEquals(name, 2, launch.store.queryPendingLocalRoutes("customer").size)
            launch.clock.set(handledAt)
            val snapshot = launch.copy("customer", 1, trigger, waitSteps(
                eventName = wait.getValue("eventName").jsonPrimitive.content,
                maxTimeMillis = wait.getValue("maxTimeMillis").jsonPrimitive.long,
                satisfied = "satisfied",
                timeout = "timeout",
            ))
            try {
                withTimeout(5_000L) {
                    val committing = async { launch.commit("customer", snapshot) }
                    replayPaused.await()
                    // Android serializes Journey commands. Queue activation
                    // between the trigger and follow-up; it and a catch-up wake
                    // must leave the held follow-up first access to the wait.
                    val activating = async(start = CoroutineStart.UNDISPATCHED) {
                        launch.journeys.onAppWillEnterForeground()
                    }
                    assertEquals(name, 0, launch.outcomes("customer", "timeout"))
                    releaseReplay.complete(Unit)
                    committing.await()
                    activating.await()
                    launch.eventLog.awaitBarrier()
                }
            } finally {
                releaseReplay.complete(Unit)
            }

            val parked = requireNotNull(observed) { name }
            val anchor = origin + expected.getValue("waitStartedAtMillis").jsonPrimitive.long
            assertEquals(name, anchor, parked.park?.anchorAtMillis)
            assertEquals(name, anchor + wait.getValue("maxTimeMillis").jsonPrimitive.long, parked.park?.wakeAtMillis)
            assertEquals(name, handledAt, parked.startedAtMillis)
            assertEquals(name, 1, launch.starts("customer"))
            assertEquals(name, 1, launch.outcomes("customer", expected.getValue("outcome").jsonPrimitive.content))
            assertEquals(name, 0, launch.outcomes("customer", "timeout"))
            assertEquals(name, IsoDates.formatMillis(handledAt), launch.report(JourneyEventNames.LEG_STARTED)["started_at"])
            assertEquals(name, handledAt, launch.store.pendingBatch(100).single {
                it.id == parked.startedEventId
            }.timestampMillis)
            assertTrue(name, launch.store.queryPendingLocalRoutes("customer").isEmpty())
            launch.close()
        }
    }

    @Test fun `event started waits and delays keep the initial lag across steps`() = runBlocking {
        val handledAt = 1_000_800L
        for (eventAt in listOf(1_000_000L, handledAt + 1_000L)) {
            for (timedStep in listOf(waitSteps().first().jsonObject, delayStep())) {
                directory.deleteRecursively()
                context.getSharedPreferences("nuxie_journey_release_high_water", 0).edit().clear().commit()
                lateinit var launch: Launch
                launch = Launch("customer", dispatcher = JourneyDispatching {
                    // Async work between the first evaluations must count.
                    delay(1)
                    launch.clock.addAndGet(200L)
                    JourneyDispatchResult.Outlet("next")
                })
                val select = Json.parseToJsonElement("""{
                    "id":"select","kind":"action","action":{"type":"experiment",
                    "experimentId":"experiment","fallbackVariantId":"variant",
                    "variants":[{"id":"variant","isHoldout":false}]},"outlets":{"variant":"work"}
                }""").jsonObject
                val work = Json.parseToJsonElement("""{
                    "id":"work","kind":"action","action":{"type":"app_action","name":"work"},
                    "outlets":{"next":"${timedStep.getValue("id").jsonPrimitive.content}"}
                }""").jsonObject
                val steps = JsonArray(listOf(select, work, timedStep) + waitSteps().drop(1))
                val snapshot = launch.copy("customer", 1, "start_waiting", steps)
                launch.clock.set(handledAt)
                launch.start()
                launch.commit("customer", snapshot)
                assertTrue(launch.journeys.handleEvent(
                    StoredEvent("lag-trigger", "start_waiting", timestampMillis = eventAt, distinctId = "customer"),
                    launch.journeys.eventAdmissionGeneration(),
                ))

                val run = launch.journal().runs().single()
                val anchor = handledAt + 200L - (handledAt - eventAt).coerceAtLeast(0L)
                assertEquals(anchor, run.park?.anchorAtMillis)
                assertEquals(anchor + 60_000L, run.park?.wakeAtMillis)
                assertEquals(handledAt, run.startedAtMillis)
                assertEquals(handledAt, run.experimentExposures.single().selectedAtMillis)
                assertEquals(IsoDates.formatMillis(handledAt), launch.report(JourneyEventNames.LEG_STARTED)["started_at"])
                launch.close()
            }
        }
    }

    @Test fun `recovered wait keeps its event anchor and a later delay uses the real clock`() = runBlocking {
        val initial = Launch("customer")
        val wait = waitSteps().first().jsonObject
        val changedWait = JsonObject(wait + ("outlets" to buildJsonObject {
            put("satisfied", "delay"); put("timeout", "timed_out")
        }))
        val steps = JsonArray(listOf(changedWait, delayStep()) + waitSteps().drop(1))
        val snapshot = initial.copy("customer", 1, "start_waiting", steps)
        val eventAt = 1_000_000L
        initial.clock.set(eventAt + 800L)
        initial.start()
        initial.commit("customer", snapshot)
        assertTrue(initial.journeys.handleEvent(
            StoredEvent("recovery-trigger", "start_waiting", timestampMillis = eventAt, distinctId = "customer"),
            initial.journeys.eventAdmissionGeneration(),
        ))
        initial.close()

        val recovered = Launch("customer")
        // Bind the new catalog's delivery authority before pinned recovery.
        recovered.copy("customer", 1, "start_waiting", steps)
        val later = eventAt + 2_800L
        recovered.clock.set(later)
        recovered.start()
        val saved = recovered.journal().runs().single()
        assertEquals(eventAt, saved.park?.anchorAtMillis)
        assertEquals(eventAt + 60_000L, saved.park?.wakeAtMillis)
        assertTrue(recovered.journeys.handleEvent(
            StoredEvent("held-go", "go", timestampMillis = eventAt + 100L, distinctId = "customer"),
            recovered.journeys.eventAdmissionGeneration(),
        ))
        val continued = recovered.journal().runs().single()
        assertEquals("delay", continued.stepId)
        assertEquals(later, continued.park?.anchorAtMillis)
        assertEquals(later + 60_000L, continued.park?.wakeAtMillis)
        assertEquals(0, recovered.starts("customer"))
    }

    @Test fun `initially overdue delay catches up without another event`() =
        assertInitiallyOverdueStepCatchesUp(delayStep(500L), "took_go")

    @Test fun `initially overdue wait times out without another event`() =
        assertInitiallyOverdueStepCatchesUp(waitSteps(maxTimeMillis = 500L).first().jsonObject, "timed_out")

    private fun assertInitiallyOverdueStepCatchesUp(step: JsonObject, outcome: String) = runBlocking {
        val launch = Launch("customer")
        val snapshot = launch.copy("customer", 1, "start_waiting", JsonArray(listOf(step) + waitSteps().drop(1)))
        launch.clock.set(1_000_800L)
        launch.start()
        launch.commit("customer", snapshot)
        assertTrue(launch.journeys.handleEvent(
            StoredEvent("overdue-trigger", "start_waiting", timestampMillis = 1_000_000L, distinctId = "customer"),
            launch.journeys.eventAdmissionGeneration(),
        ))
        // The clock stays at 800 ms. Only the scheduled wake can rescue it.
        withTimeout(5_000L) {
            while (launch.outcomes("customer", outcome) == 0) delay(10)
        }
        assertEquals(1, launch.outcomes("customer", outcome))
        assertEquals(1_000_800L, launch.clock.get())
        assertEquals(IsoDates.formatMillis(1_000_800L), launch.report(JourneyEventNames.LEG_COMPLETED)["completed_at"])
    }

    /** The shared contract, replayed operation by operation. */
    @Test fun `refetch event hold vectors`() = runBlocking {
        val vectors = Json.parseToJsonElement(
            FixtureRunner.fixturesRoot().resolve("sdk/refetch-event-hold.json").readText(),
        ).jsonObject.getValue("cases").jsonArray
        assertTrue(vectors.isNotEmpty())
        for (vector in vectors.map { it.jsonObject }) {
            val name = vector.getValue("name").jsonPrimitive.content
            directory.deleteRecursively()
            context.getSharedPreferences("nuxie_journey_release_high_water", 0).edit().clear().commit()
            val launch = Launch("customer")
            val saved = launch.copy("customer", 1, "start_waiting")
            val fresh = launch.copy("customer", 2, "go")
            launch.start()
            for (step in vector.getValue("steps").jsonArray.map { it.jsonObject }) {
                val op = step.getValue("op").jsonPrimitive.content
                when {
                    op == "commit-saved" -> launch.commit("customer", saved)
                    op == "commit-fresh" -> launch.commit("customer", fresh)
                    op == "refetch-begin" -> launch.journeys.profileRefetchWillBegin("customer")
                    op == "refetch-settle" -> launch.journeys.profileRefetchDidSettle("customer")
                    op == "withdraw" -> launch.withdraw("customer")
                    op == "advance-past-deadline" -> launch.clock.addAndGet(70_000L)
                    op == "app-active" -> launch.journeys.onAppWillEnterForeground()
                    op.startsWith("track ") -> launch.track(op.removePrefix("track "))
                    else -> error("$name: unknown operation $op")
                }
                launch.eventLog.awaitBarrier()
                assertEquals(
                    "$name after $op",
                    listOf(
                        step.getValue("starts").jsonPrimitive.int,
                        step.getValue("tookGo").jsonPrimitive.int,
                        step.getValue("timedOut").jsonPrimitive.int,
                    ),
                    listOf(launch.starts("customer"), launch.outcomes("customer", "took_go"),
                        launch.outcomes("customer", "timed_out")),
                )
            }
            launch.close()
        }
    }

    /** An event captured before the copy it is handled under starts from that copy. */
    @Test fun `early event admission vectors`() = runBlocking {
        val vectors = Json.parseToJsonElement(
            FixtureRunner.fixturesRoot().resolve("sdk/early-event-admission.json").readText(),
        ).jsonObject.getValue("cases").jsonArray
        assertTrue(vectors.isNotEmpty())
        for (vector in vectors.map { it.jsonObject }) {
            val name = vector.getValue("name").jsonPrimitive.content
            directory.deleteRecursively()
            context.getSharedPreferences("nuxie_journey_release_high_water", 0).edit().clear().commit()
            val launch = Launch("customer")
            val empty = launch.copy("customer", 1, "other_event")
            val armed = launch.copy("customer", 2, "trigger")
            launch.start()
            var captured: Pair<StoredEvent, Long>? = null
            val observed = mutableListOf<Int>()
            for (op in vector.getValue("operations").jsonArray.map { it.jsonPrimitive.content }) {
                when (op) {
                    "commit-empty" -> launch.commit("customer", empty)
                    "commit-armed" -> launch.commit("customer", armed)
                    "capture" -> captured = StoredEvent(
                        "captured-trigger", "trigger", timestampMillis = launch.clock.get(), distinctId = "customer",
                    ) to launch.journeys.eventAdmissionGeneration()
                    "route-captured" -> {
                        val (event, generation) = requireNotNull(captured) { name }
                        assertTrue(name, launch.journeys.handleEvent(event, generation))
                    }
                    else -> error("$name: unknown operation $op")
                }
                observed += launch.starts("customer")
            }
            assertEquals(name, vector.getValue("expectedStarts").jsonArray.map { it.jsonPrimitive.int }, observed)
            launch.close()
        }
    }

    /**
     * identify(B) before B's copy: a go sent meanwhile waits, then B's copy
     * starts its Journey from it once.
     */
    @Test fun `identify holds the arriving customer's event for their copy`() = runBlocking {
        val launch = Launch("customer-a")
        val arriving = launch.copy("customer-b", 1, "go")
        launch.start()
        launch.identity.distinctId = "customer-b"
        launch.journeys.handleUserChange("customer-a", "customer-b")
        launch.track("go")
        launch.eventLog.awaitBarrier()
        assertEquals("go waits for B's copy", 0, launch.starts("customer-b"))

        launch.commit("customer-b", arriving)
        launch.journeys.profileRefetchDidSettle("customer-b")
        launch.eventLog.awaitBarrier()
        assertEquals(1, launch.starts("customer-b"))
    }

    /**
     * B's fetch fails: the held go goes through without a copy, so a copy that
     * arrives later does not start a Journey from that old event.
     */
    @Test fun `a failed identify fetch lets the held events through without a copy`() = runBlocking {
        val launch = Launch("customer-a")
        val arriving = launch.copy("customer-b", 1, "go")
        launch.start()
        launch.identity.distinctId = "customer-b"
        launch.journeys.handleUserChange("customer-a", "customer-b")
        launch.track("go")
        launch.eventLog.awaitBarrier()
        launch.journeys.profileRefetchDidSettle("customer-b")
        launch.eventLog.awaitBarrier()

        launch.commit("customer-b", arriving)
        launch.eventLog.awaitBarrier()
        assertEquals(0, launch.starts("customer-b"))
        launch.track("go")
        launch.eventLog.awaitBarrier()
        assertEquals("Routing is live again", 1, launch.starts("customer-b"))
    }

    /**
     * An event captured before a wait's deadline but handled after it leaves
     * that timeout to a pass at the current time, which still runs.
     */
    @Test fun `a late event resolves an overdue wait when nothing is pending`() = runBlocking {
        val launch = Launch("customer")
        val saved = launch.copy("customer", 1, "start_waiting")
        launch.start()
        launch.commit("customer", saved)
        launch.track("start_waiting")
        val capturedAt = launch.clock.get() + 30_000L
        launch.clock.addAndGet(70_000L)
        assertTrue(launch.journeys.handleEvent(
            StoredEvent("late-event", "screen_viewed", timestampMillis = capturedAt, distinctId = "customer"),
            launch.journeys.eventAdmissionGeneration(),
        ))
        // The timeout now arrives through the queued deadline entry.
        launch.eventLog.awaitBarrier()
        assertEquals(1, launch.outcomes("customer", "timed_out"))
        assertEquals(0, launch.outcomes("customer", "took_go"))
    }

    /**
     * The fetch settles while the foreground check is still running, so the
     * held go cannot replay yet. Once the check completes it replays before
     * the wait that ran out meanwhile is resolved.
     */
    @Test fun `held events replay before waits resolve when the foreground check ends`() = runBlocking {
        val launch = Launch("customer")
        val saved = launch.copy("customer", 1, "start_waiting")
        launch.start()
        launch.commit("customer", saved)
        launch.track("start_waiting")
        launch.journeys.profileRefetchWillBegin("customer")
        launch.withdraw("customer")
        launch.journeys.onAppVisibilityChanged(false)
        launch.journeys.onAppVisibilityChanged(true)
        val token = requireNotNull(launch.journeys.foregroundRevalidationToken())
        launch.track("go")
        launch.clock.addAndGet(70_000L)
        launch.journeys.profileRefetchDidSettle("customer")
        launch.eventLog.awaitBarrier()
        assertEquals(0, launch.outcomes("customer", "took_go") + launch.outcomes("customer", "timed_out"))

        launch.journeys.completeForegroundRevalidation(token)
        launch.eventLog.awaitBarrier()
        assertEquals(1, launch.outcomes("customer", "took_go"))
        assertEquals(0, launch.outcomes("customer", "timed_out"))
    }

    /**
     * The copy lands while an already accepted go is still in EventLog's
     * route worker. The wait that ran out meanwhile resolves only once that go
     * has reached Journey, so it takes the go.
     */
    @Test fun `waits resolve only after events still in EventLog when the copy lands`() = runBlocking {
        val goReached = CompletableDeferred<Unit>()
        val releaseGo = CompletableDeferred<Unit>()
        val launch = Launch("customer") { event ->
            if (event.name == "go") {
                goReached.complete(Unit)
                releaseGo.await()
            }
        }
        val saved = launch.copy("customer", 1, "start_waiting")
        launch.start()
        launch.commit("customer", saved)
        launch.track("start_waiting")
        try {
            launch.eventLog.capture("go")
            withTimeout(5_000L) { goReached.await() }
            launch.journeys.profileRefetchWillBegin("customer")
            launch.withdraw("customer")
            launch.clock.addAndGet(70_000L)

            // Resolving the wait now would report it through the route worker
            // that go still occupies, so a regression shows as this timeout.
            withTimeout(5_000L) { launch.commit("customer", saved) }
            assertEquals(0, launch.outcomes("customer", "timed_out"))

            releaseGo.complete(Unit)
            launch.journeys.profileRefetchDidSettle("customer")
            launch.eventLog.awaitBarrier()
            withTimeout(5_000L) {
                while (launch.outcomes("customer", "took_go") + launch.outcomes("customer", "timed_out") == 0) delay(20)
            }
            assertEquals(1, launch.outcomes("customer", "took_go"))
            assertEquals(0, launch.outcomes("customer", "timed_out"))
        } finally {
            // Closing EventLog waits for its route worker, which the gate may hold.
            releaseGo.complete(Unit)
        }
    }

    /** One launch: Journey subscribed to a real EventLog as NuxieCore subscribes it. */
    private inner class Launch(
        distinctId: String,
        firstLaunch: Boolean = false,
        dispatcher: JourneyDispatching = JourneyDispatching { JourneyDispatchResult.Unsupported },
        private val afterJourney: (suspend (StoredEvent, Boolean) -> Unit)? = null,
        readRuns: suspend (JourneyRunJournal) -> List<JourneyRun> = { it.runs() },
        readValues: suspend (ai.nuxie.sdk.presentation.ExperienceRunValues) -> JsonObject = { it.journeyValues() },
        beforePersist: () -> Unit = {},
        beforeResume: suspend () -> Unit = {},
        replay: (suspend (String) -> Boolean)? = null,
        enqueueDeadline: ((JourneyDeadline) -> Unit)? = null,
        prepare: suspend () -> Unit = {},
        sleep: suspend (Long) -> Unit = { delay(it) },
        withScreens: Boolean = false,
        dropJourneyStarted: Boolean = false,
        /** Runs in EventLog's route worker before Journey sees each event. */
        private val beforeJourney: (suspend (StoredEvent) -> Unit)? = null,
    ) {
        private val launchScope = CoroutineScope(SupervisorJob(scope.coroutineContext[Job]) + Dispatchers.Default)
        private val sourceEntry = if (withScreens) releaseFixture.getValue("renderedEntry").jsonObject else entry
        val clock = AtomicLong(1_000_000L)
        val identity = MutableIdentity(distinctId)
        val store = SQLiteEventStore(context, nowMillis = { clock.get() })
        val catalog = JourneyProfileCatalog(
            mapOf(KEY_ID to keys.public.encoded.takeLast(32).toByteArray()),
            JourneyReleaseHighWaterStore(context),
        ) { runtime(sourceEntry) }
        private val reports = CopyOnWriteArrayList<Pair<String, Map<String, Any?>>>()
        val eventLog = EventLog(
            store,
            NuxieContextBuilder(context, NuxieEnvironment.DEVELOPMENT, LogLevel.DEBUG, identity),
            identity,
            beforeSend = { event ->
                if (dropJourneyStarted && event.name == JourneyEventNames.LEG_STARTED) null else event
            },
            scope = launchScope,
            nowMillis = { clock.get() },
        )
        val journeys = JourneyService(
            identity = identity,
            events = store,
            catalog = catalog,
            journalDirectory = directory,
            scope = launchScope,
            capture = { name, properties, eventId, owner ->
                reports += name to (properties + ("distinct_id" to owner))
                eventLog.captureIdempotently(name, properties, eventId, owner)
            },
            nowMillis = { clock.get() },
            replayPendingLocalRoutes = replay ?: eventLog::replayPendingLocalRoutes,
            readNativeValues = readValues,
            journalBeforePersist = beforePersist,
            beforeParkedResume = beforeResume,
            readJournalRuns = readRuns,
            prepareNativeValues = { _, _, _ -> prepare() },
            sleepMillis = sleep,
            enqueueJourneyDeadline = enqueueDeadline ?: eventLog::enqueueJourneyDeadline,
            requestJourneyResumeRetry = eventLog::requestJourneyResumeRetry,
            deferCommittedRouting = eventLog::deferCommittedRouting,
            releaseCommittedRouting = eventLog::releaseCommittedRouting,
            fixedStorageScope = if (firstLaunch) null else JourneyStorageScope(authority),
            dispatcher = dispatcher,
        )
        private var publication = 0L

        init {
            launches += this
            beforeJourney?.let { hook -> eventLog.subscribeCommitted { event -> hook(event) } }
            eventLog.setJourneyDeadlineHandler(journeys::handleJourneyDeadline)
            eventLog.setJourneyResumeRetryHandler(journeys::handleJourneyResumeRetry)
            eventLog.subscribeCommittedWithAdmission(sampleGeneration = journeys::eventAdmissionGeneration) { event, generation ->
                journeys.handleEvent(event, generation).also { afterJourney?.invoke(event, it) }
            }
        }

        suspend fun start() {
            journeys.initialize()
            journeys.onAppWillEnterForeground()
        }

        /** A signed copy whose Journey starts on [entryEvent] and executes [steps]. */
        suspend fun copy(
            owner: String,
            sequence: Int,
            entryEvent: String,
            steps: JsonArray = waitSteps(),
            screens: Boolean? = null,
        ): JourneyProfileCatalog.Snapshot {
            val condition = buildJsonObject {
                put("type", "event")
                put("eventName", entryEvent)
            }
            val publication = waitingPublication(sequence, condition, steps,
                if (screens == false) entry else sourceEntry)
            catalog.commit(owner, catalog.prepare(profile(condition, publication), authority))
            return requireNotNull(catalog.snapshot(owner))
        }

        suspend fun commit(owner: String, snapshot: JourneyProfileCatalog.Snapshot) {
            journeys.profileDidCommit(snapshot, authority, owner, ++publication)
        }

        suspend fun withdraw(owner: String) {
            journeys.profileDidWithdraw(owner, ++publication)
        }

        suspend fun track(name: String) {
            eventLog.capture(name)
            eventLog.awaitBarrier()
        }

        fun starts(owner: String): Int = reports.count {
            it.first == JourneyEventNames.LEG_STARTED && it.second["distinct_id"] == owner
        }

        fun outcomes(owner: String, outcome: String): Int = reports.count {
            it.first == JourneyEventNames.LEG_COMPLETED && it.second["distinct_id"] == owner &&
                it.second["outcome"] == outcome
        }

        fun journal() = JourneyRunJournal(directory, identity.distinctId, JourneyStorageScope(authority))

        fun report(name: String) = reports.single { it.first == name }.second

        suspend fun close() {
            if (!launches.remove(this)) return
            eventLog.closeWorkers()
            launchScope.cancel()
            store.close()
        }
    }

    private class WakeSleeper {
        private data class Request(val duration: Long, val done: CompletableDeferred<Unit>, val job: Job?)
        private val requests = CopyOnWriteArrayList<Request>()
        val pendingDurations: List<Long>
            get() = requests.filter { it.job?.isActive == true && !it.done.isCompleted }.map { it.duration }

        suspend fun sleep(duration: Long) {
            val request = Request(duration, CompletableDeferred(), kotlinx.coroutines.currentCoroutineContext()[Job])
            requests += request
            try { request.done.await() } finally { requests.remove(request) }
        }

        fun completeAll() {
            requests.filter { it.job?.isActive == true }.forEach { it.done.complete(Unit) }
        }

        fun complete(duration: Long) {
            requests.filter { it.duration == duration && it.job?.isActive == true }.forEach { it.done.complete(Unit) }
        }
    }

    private class MutableIdentity(@Volatile var distinctId: String) : IdentityProvider {
        override fun distinctId() = distinctId
        override fun anonymousId() = distinctId
        override fun rawDistinctId(): String? = null
        override val isIdentified = false
    }

    private fun waitSteps(
        eventName: String = "go",
        maxTimeMillis: Long = 60_000L,
        satisfied: String = "took_go",
        timeout: String = "timed_out",
    ) = buildJsonArray {
        addJsonObject {
            put("id", "wait")
            put("kind", "action")
            putJsonObject("action") {
                put("type", "wait_until")
                putJsonObject("trigger") { put("kind", "event"); put("eventName", eventName) }
                putJsonObject("condition") {
                    put("type", "Truthy")
                    putJsonObject("value") { put("type", "Boolean"); put("value", true) }
                }
                put("maxTimeMs", maxTimeMillis)
            }
            putJsonObject("outlets") { put("satisfied", satisfied); put("timeout", timeout) }
        }
        addJsonObject { put("id", satisfied); put("kind", "complete"); put("outcome", satisfied) }
        addJsonObject { put("id", timeout); put("kind", "complete"); put("outcome", timeout) }
    }

    private fun delayStep(durationMillis: Long = 60_000L) = buildJsonObject {
        put("id", "delay")
        put("kind", "action")
        putJsonObject("action") { put("type", "delay"); put("durationMs", durationMillis) }
        putJsonObject("outlets") { put("next", "took_go") }
    }

    private fun waitingPublication(sequence: Int, condition: JsonObject, steps: JsonArray, sourceEntry: JsonObject): JsonObject {
        val envelope = sourceEntry.getValue("envelope").jsonObject
        val source = Json.parseToJsonElement(Base64.decode(
            envelope.getValue("descriptorBytesBase64").jsonPrimitive.content, Base64.NO_WRAP,
        ).decodeToString()).jsonObject
        val originalIdentity = source.getValue("identity").jsonObject
        val changedIdentity = JsonObject(originalIdentity + mapOf(
            "experienceVersionId" to JsonPrimitive("version_hold_$sequence"),
            "buildId" to JsonPrimitive("build_hold_$sequence"),
            "versionNumber" to JsonPrimitive(originalIdentity.getValue("versionNumber").jsonPrimitive.int + sequence),
            "publishedAtSeq" to JsonPrimitive(originalIdentity.getValue("publishedAtSeq").jsonPrimitive.long + sequence),
        ))
        val boundary = buildJsonObject {
            putJsonArray("eventFields") {}
            putJsonArray("responseFields") {}
        }
        val experiments = steps.mapNotNull {
            (it.jsonObject["action"] as? JsonObject)?.takeIf { action ->
                action["type"] == JsonPrimitive("experiment")
            }?.get("experimentId")?.jsonPrimitive?.content
        }.distinct().sorted()
        val leg = JsonObject(source.getValue("leg").jsonObject + mapOf(
            "facts" to buildJsonObject {
                putJsonArray("propertyKeys") {}
                putJsonArray("segmentIds") {}
                put("experimentIds", JsonArray(experiments.map(::JsonPrimitive)))
            },
            "entryCondition" to condition,
            "policy" to buildJsonObject {
                putJsonObject("entry") {
                    put("trigger", condition)
                    putJsonObject("frequency") { put("type", "every_match") }
                }
                putJsonArray("exitWhenAny") {}
            },
            "entryStepId" to steps.first().jsonObject.getValue("id"),
            "completionOutputs" to buildJsonObject {
                for (step in steps.map { it.jsonObject }) {
                    if (step.getValue("kind").jsonPrimitive.content == "complete") {
                        put(step.getValue("outcome").jsonPrimitive.content, boundary)
                    }
                }
            },
            "steps" to steps,
            "routes" to JsonArray(emptyList()),
        ))
        val descriptor = JsonObject(source + mapOf("identity" to changedIdentity, "leg" to leg))
        val bytes = descriptor.toString().encodeToByteArray()
        val signature = Signature.getInstance("Ed25519").run {
            initSign(keys.private)
            update(JourneyReleaseLimits.SIGNATURE_DOMAIN.encodeToByteArray() + bytes)
            sign()
        }
        return JsonObject(sourceEntry + mapOf(
            "locator" to JsonObject(sourceEntry.getValue("locator").jsonObject + changedIdentity),
            "envelope" to JsonObject(envelope + mapOf(
                "descriptorBytesBase64" to JsonPrimitive(Base64.encodeToString(bytes, Base64.NO_WRAP)),
                "descriptorSizeBytes" to JsonPrimitive(bytes.size),
                "descriptorSha256" to JsonPrimitive(MessageDigest.getInstance("SHA-256")
                    .digest(bytes).joinToString("") { "%02x".format(it) }),
                "signature" to buildJsonObject {
                    put("version", 1); put("algorithm", "ed25519"); put("keyId", KEY_ID)
                    put("signatureBase64", Base64.encodeToString(signature, Base64.NO_WRAP))
                },
            )),
        ))
    }

    private fun profile(entryCondition: JsonObject, releaseEntry: JsonObject): JsonObject {
        val locator = releaseEntry.getValue("locator").jsonObject
        val envelope = releaseEntry.getValue("envelope").jsonObject
        val descriptor = Json.parseToJsonElement(Base64.decode(
            envelope.getValue("descriptorBytesBase64").jsonPrimitive.content, Base64.NO_WRAP,
        ).decodeToString()).jsonObject
        val experiments = descriptor.getValue("leg").jsonObject.getValue("facts").jsonObject
            .getValue("experimentIds").jsonArray
        return buildJsonObject {
            put("schemaVersion", "nuxie.journey-plane-profile.v2")
            put("status", "ok")
            putJsonObject("delivery") {
                put("renderBaseUrl", "https://renders.example.com/")
                put("assetBaseUrl", "https://assets.example.com/")
            }
            putJsonArray("features") {}
            putJsonObject("facts") {
                putJsonObject("properties") {}
                putJsonObject("memberships") {}
                putJsonObject("assignments") {
                    experiments.forEach { put(it.jsonPrimitive.content, JsonNull) }
                }
            }
            put("releases", JsonArray(listOf(releaseEntry)))
            putJsonArray("armedLegs") {
                addJsonObject {
                    putJsonObject("reference") {
                        put("experienceId", locator.getValue("experienceId"))
                        put("versionId", locator.getValue("experienceVersionId"))
                        put("legId", locator.getValue("legId"))
                        put("descriptorSha256", envelope.getValue("descriptorSha256"))
                    }
                    putJsonObject("binding") { put("type", "new") }
                    put("entryCondition", entryCondition)
                    putJsonObject("context") {
                        putJsonObject("event") {}
                        putJsonObject("responses") {}
                    }
                }
            }
        }
    }

    private fun runtime(sourceEntry: JsonObject): JourneyReleaseSupportedRuntime {
        val descriptor = Json.parseToJsonElement(Base64.decode(
            sourceEntry.getValue("envelope").jsonObject.getValue("descriptorBytesBase64").jsonPrimitive.content,
            Base64.NO_WRAP,
        ).decodeToString()).jsonObject
        val requirements = descriptor["requirements"] as? JsonObject
            ?: return JourneyReleaseSupportedRuntime("0.1.0", emptySet(), emptyMap(), 1, 0, "unused", "unused", emptySet())
        fun JsonObject.string(key: String) = getValue(key).jsonPrimitive.content
        val luau = requirements.getValue("luau").jsonObject
        val scene = requirements.getValue("sceneFormat").jsonObject
        val timezone = requirements.getValue("timezoneData").jsonObject
        return JourneyReleaseSupportedRuntime(
            requirements.string("minimumSdkVersion"),
            setOf(requirements.string("runtimeRevision")),
            mapOf(luau.string("revision") to luau.getValue("bytecodeVersions").jsonArray.map { it.jsonPrimitive.int }.toSet()),
            scene.getValue("major").jsonPrimitive.int,
            scene.getValue("minor").jsonPrimitive.int,
            timezone.string("revision"),
            timezone.string("sha256"),
            requirements.getValue("requiredCapabilities").jsonArray.map { it.jsonPrimitive.content }.toSet(),
        )
    }

    private companion object {
        const val KEY_ID = "TEST_ONLY_HOLD"
    }
}
