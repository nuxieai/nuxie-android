package ai.nuxie.sdk.journey

import ai.nuxie.sdk.LogLevel
import ai.nuxie.sdk.NuxieEnvironment
import ai.nuxie.sdk.events.EventLog
import ai.nuxie.sdk.events.JsonValueConverter
import ai.nuxie.sdk.events.NuxieContextBuilder
import ai.nuxie.sdk.events.SQLiteEventStore
import ai.nuxie.sdk.events.StableEventCaptureResult
import ai.nuxie.sdk.events.StoredEvent
import ai.nuxie.sdk.experiences.AcquiredRelease
import ai.nuxie.sdk.experiences.DeviceLegArtifactManager
import ai.nuxie.sdk.experiences.DeviceLegProfileCatalog
import ai.nuxie.sdk.experiences.JourneyPlaneProfile
import ai.nuxie.sdk.experiences.PreparedDeviceLegArtifacts
import ai.nuxie.sdk.experiences.ReleaseHighWaterStore
import ai.nuxie.sdk.experiences.SupportedRuntime
import ai.nuxie.sdk.fixtures.FixtureRunner
import ai.nuxie.sdk.identity.IdentityProvider
import ai.nuxie.sdk.identity.IdentityScope
import ai.nuxie.sdk.identity.IdentityService
import ai.nuxie.sdk.network.ProfileDeliveryAuthority
import ai.nuxie.sdk.presentation.DeviceLegPresentationRequest
import ai.nuxie.sdk.presentation.DeviceLegPresentationReservation
import ai.nuxie.sdk.presentation.DeviceLegPresentationResult
import ai.nuxie.sdk.presentation.DeviceLegPresenting
import ai.nuxie.sdk.presentation.DeviceLegScreenEmission
import ai.nuxie.sdk.presentation.DeviceLegScreenEmissionBatch
import ai.nuxie.sdk.presentation.DeviceLegScreenEmissionSource
import ai.nuxie.sdk.presentation.DeviceLegSurfaceOutcome
import android.util.Base64
import java.io.File
import java.io.Closeable
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [23])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class DeviceLegServiceTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var store: SQLiteEventStore
    private lateinit var directory: File

    private val fixture = Json.parseToJsonElement(
        FixtureRunner.fixturesRoot().resolve("journeys/planes/release.json").readText(),
    ).jsonObject
    private val entry get() = fixture.getValue("entry").jsonObject
    private val authority get() = ProfileDeliveryAuthority(
        entry.getValue("locator").jsonObject.getValue("appId").jsonPrimitive.content,
        entry.getValue("locator").jsonObject.getValue("environment").jsonPrimitive.content,
    )

    @Before fun setUp() {
        File(context.filesDir, "nuxie").deleteRecursively()
        directory = File(context.filesDir, "nuxie")
        store = SQLiteEventStore(context, nowMillis = { 0L })
        context.getSharedPreferences("nuxie_release_high_water", 0).edit().clear().commit()
    }

    @After fun tearDown() {
        runBlocking {
        store.close()
        scope.cancel()
        directory.deleteRecursively()
        }
    }

    @Test fun `foreground arm executes its authenticated leg once across revalidation`() = runBlocking {
        val identity = identity("customer")
        val catalog = catalog()
        val prepared = catalog.prepare(profile(), authority)
        catalog.commit("customer", prepared)
        val snapshot = requireNotNull(catalog.snapshot("customer"))
        val captures = CopyOnWriteArrayList<Pair<String, Map<String, Any?>>>()
        var now = 100_000L
        val service = DeviceLegService(
            identity = identity,
            events = store,
            catalog = catalog,
            journalDirectory = directory,
            scope = scope,
            capture = { name, properties, _, _ ->
                captures += name to properties
                true
            },
            nowMillis = { now },
        )

        service.initialize()

        service.onAppWillEnterForeground()
        service.profileDidCommit(snapshot, authority, "customer", 1)

        assertEquals(
            listOf(JourneyEventNames.LEG_STARTED, JourneyEventNames.LEG_COMPLETED),
            captures.map { it.first },
        )
        val completion = captures.last().second
        assertEquals("continue", completion["outcome"])
        assertEquals(entry.getValue("locator").jsonObject.getValue("legId").jsonPrimitive.content,
            completion["leg_id"])
        val journal = DeviceLegRunJournal(
            directory,
            "customer",
            DeviceLegStorageScope(authority),
        )
        assertTrue(journal.runs().isEmpty())
        assertEquals("continue", journal.checkmark("experience_golden")?.outcome)

        now += 1_000
        service.profileDidCommit(snapshot, authority, "customer", 2)
        assertEquals(2, captures.size)
    }

    @Test fun `busy presentation leaves the rendered arm unconsumed for a later evaluation`() = runBlocking {
        val identity = identity("customer")
        val renderedEntry = fixture.getValue("renderedEntry").jsonObject
        val catalog = catalog(renderedEntry)
        val renderedAuthority = authority(renderedEntry)
        val prepared = catalog.prepare(profile(releaseEntry = renderedEntry), renderedAuthority)
        catalog.commit("customer", prepared)
        val snapshot = requireNotNull(catalog.snapshot("customer"))
        val captures = CopyOnWriteArrayList<Pair<String, Map<String, Any?>>>()
        val presenter = RecordingDeviceLegPresenter(available = false)
        val service = DeviceLegService(
            identity = identity,
            events = store,
            catalog = catalog,
            journalDirectory = directory,
            scope = scope,
            capture = { name, properties, _, _ ->
                captures += name to properties
                true
            },
            presenter = presenter,
            nowMillis = { 100_000L },
        )
        service.initialize()
        service.onAppWillEnterForeground()

        service.profileDidCommit(snapshot, renderedAuthority, "customer", 1)

        val journal = DeviceLegRunJournal(
            directory,
            "customer",
            DeviceLegStorageScope(renderedAuthority),
        )
        assertTrue(captures.isEmpty())
        assertTrue(journal.runs().isEmpty())
        assertNull(journal.checkmark("experience_golden"))

        presenter.available = true
        service.profileDidCommit(snapshot, renderedAuthority, "customer", 2)

        assertEquals(listOf(JourneyEventNames.LEG_STARTED), captures.map { it.first })
        val active = journal.runs().single()
        assertNull("an active screen is not a resumable park point", active.park)
        assertNull(active.completion)
        assertEquals("screen_welcome", presenter.request?.screenId)
    }

    @Test fun `renderer batches durably publish once and route the owning run`() = runBlocking {
        val identity = identity("customer")
        val renderedEntry = fixture.getValue("renderedEntry").jsonObject
        val catalog = catalog(renderedEntry)
        val renderedAuthority = authority(renderedEntry)
        val prepared = catalog.prepare(profile(releaseEntry = renderedEntry), renderedAuthority)
        catalog.commit("customer", prepared)
        val snapshot = requireNotNull(catalog.snapshot("customer"))
        val presenter = RecordingDeviceLegPresenter()
        val systemCaptures = CopyOnWriteArrayList<String>()
        val ordinaryCaptures = linkedMapOf<String, StoredEvent>()
        val service = DeviceLegService(
            identity = identity,
            events = store,
            catalog = catalog,
            journalDirectory = directory,
            scope = scope,
            capture = { name, _, _, _ ->
                systemCaptures += name
                true
            },
            captureScreenEvent = { name, properties, eventId, distinctId, occurredAt, admission ->
                val event = StoredEvent(
                    id = eventId,
                    name = name,
                    properties = JsonValueConverter.fromMap(properties),
                    timestampMillis = occurredAt,
                    distinctId = distinctId,
                )
                val settled = admission?.commitIfCurrent {
                    ordinaryCaptures.putIfAbsent(eventId, event)
                    true
                } != null
                StableEventCaptureResult(
                    settled,
                    ordinaryCaptures[eventId].takeIf { settled },
                )
            },
            presenter = presenter,
            nowMillis = { 100_000L },
        )
        service.initialize()
        service.onAppWillEnterForeground()
        service.profileDidCommit(snapshot, renderedAuthority, "customer", 1)
        val request = requireNotNull(presenter.request)
        val run = DeviceLegRunJournal(
            directory,
            "customer",
            DeviceLegStorageScope(renderedAuthority),
        ).runs().single()
        val first = DeviceLegScreenEmissionBatch(
            journeyId = run.journeyId,
            batchSequence = 0,
            invocationId = "invocation-1",
            source = DeviceLegScreenEmissionSource("screen_welcome", "submit"),
            emissions = listOf(
                DeviceLegScreenEmission(
                    id = "emission-1",
                    sequence = 0,
                    occurredAtMillis = 90_000L,
                    name = "survey_submitted",
                    payload = JsonObject(mapOf("answer" to JsonPrimitive("premium"))),
                ),
            ),
        )

        assertTrue(request.onEmissionBatch(first))
        assertTrue(request.onEmissionBatch(first))

        val retained = DeviceLegRunJournal(
            directory,
            "customer",
            DeviceLegStorageScope(renderedAuthority),
        ).runs().single()
        assertEquals(1L, retained.nextPresentationBatchSequence)
        assertEquals(1L, retained.nextPresentationEmissionSequence)
        assertEquals(setOf("emission-1"), ordinaryCaptures.keys)
        val properties = ordinaryCaptures.getValue("emission-1").properties
        assertEquals(run.journeyId, properties.getValue("journey_id").jsonPrimitive.content)
        assertEquals("screen_welcome", properties.getValue("screen_id").jsonPrimitive.content)
        assertEquals(90_000L, ordinaryCaptures.getValue("emission-1").timestampMillis)

        assertTrue(
            request.onEmissionBatch(
                DeviceLegScreenEmissionBatch(
                    journeyId = run.journeyId,
                    batchSequence = 1,
                    invocationId = "invocation-2",
                    source = DeviceLegScreenEmissionSource("screen_welcome", "continue"),
                    emissions = listOf(
                        DeviceLegScreenEmission(
                            id = "emission-2",
                            sequence = 1,
                            occurredAtMillis = 95_000L,
                            name = "continue",
                            payload = JsonObject(emptyMap()),
                        ),
                    ),
                ),
            ),
        )
        // This lifecycle command is a FIFO barrier behind the routed continuation.
        service.onAppDidEnterBackground()

        assertEquals(setOf("emission-1", "emission-2"), ordinaryCaptures.keys)
        assertEquals(
            listOf(JourneyEventNames.LEG_STARTED, JourneyEventNames.LEG_COMPLETED),
            systemCaptures,
        )
        assertTrue(
            DeviceLegRunJournal(
                directory,
                "customer",
                DeviceLegStorageScope(renderedAuthority),
            ).runs().isEmpty(),
        )
    }

    @Test fun `shared renderer fixture replays one customer event through EventLog`() =
        runBlocking {
            val screenFixture = Json.parseToJsonElement(
                FixtureRunner.fixturesRoot()
                    .resolve("journeys/screen-emission-runtime/input-effect-persistence-replay.json")
                    .readText(),
            ).jsonObject
            val input = screenFixture.getValue("input").jsonObject
            val expected = screenFixture.getValue("expected").jsonObject
            val customerEventIds = expected.getValue("customer_event_ids").jsonArray.map {
                it.jsonPrimitive.content
            }
            val eventEffects = screenFixture.getValue("effects").jsonArray
                .map(JsonElement::jsonObject)
                .filter { it.getValue("kind").jsonPrimitive.content == "event" }
            assertEquals(customerEventIds.size, eventEffects.size)

            val identity = identity("customer")
            val renderedEntry = fixture.getValue("renderedEntry").jsonObject
            val catalog = catalog(renderedEntry)
            val renderedAuthority = authority(renderedEntry)
            val prepared = catalog.prepare(profile(releaseEntry = renderedEntry), renderedAuthority)
            catalog.commit("customer", prepared)
            val snapshot = requireNotNull(catalog.snapshot("customer"))
            val release = snapshot.releasesByDigest.values.single()
            val journal = DeviceLegRunJournal(
                directory,
                "customer",
                DeviceLegStorageScope(renderedAuthority),
            )
            val run = requireNotNull(
                journal.admit(
                    snapshot.profile.armedLegs.single(),
                    JourneyReentry.EveryTime,
                    release.leg.getValue("entryStepId").jsonPrimitive.content,
                    100_000L,
                    release = snapshot.profile.releases.single(),
                ),
            )
            journal.markStartedQueued(run)
            val responses = expected.getValue("response_values").jsonObject
            val publication = DeviceLegRun.PendingPresentationPublication(
                invocationId = "fixture-recovery-invocation",
                batchSequence = expected.getValue("batch_sequence").jsonPrimitive.long,
                nextEmissionSequence = expected.getValue("emission_sequences").jsonArray
                    .last().jsonPrimitive.long + 1,
                sourceScreenId = release.leg.getValue("screens").jsonArray.first()
                    .jsonObject.getValue("id").jsonPrimitive.content,
                sourceActionId = input.getValue("action_id").jsonPrimitive.content,
                sourceComponentId = input.getValue("component_id").jsonPrimitive.content,
                sourceInstanceId = input.getValue("instance_id").jsonPrimitive.content,
                responsesChanged = true,
                items = eventEffects.mapIndexed { index, effect ->
                    DeviceLegRun.PendingPresentationPublication.Item(
                        name = effect.getValue("name").jsonPrimitive.content,
                        properties = effect.getValue("payload").jsonObject,
                        eventId = customerEventIds[index],
                        occurredAtMillis = 101_000L,
                    )
                },
            )
            val runContext = JsonObject(run.context + ("responses" to responses))
            assertTrue(
                journal.stagePresentationPublication(
                    run.id,
                    run.stepId,
                    runContext,
                    publication,
                ) != null,
            )

            val eventLog = EventLog(
                store,
                NuxieContextBuilder(
                    this@DeviceLegServiceTest.context,
                    NuxieEnvironment.DEVELOPMENT,
                    LogLevel.DEBUG,
                    identity,
                ),
                identity,
                beforeSend = null,
                scope = scope,
                nowMillis = { 102_000L },
            )
            fun recoveringService() = DeviceLegService(
                identity = identity,
                events = store,
                catalog = catalog,
                journalDirectory = directory,
                scope = scope,
                capture = eventLog::captureSystemEvent,
                captureScreenEvent = eventLog::captureScreenEvent,
                nowMillis = { 102_000L },
                fixedStorageScope = DeviceLegStorageScope(renderedAuthority),
            )

            recoveringService().initialize()
            recoveringService().initialize()

            val replayedCustomerEvents = store.pendingBatch(100).filter {
                it.id in customerEventIds
            }
            assertEquals(
                expected.getValue("replay_customer_event_count").jsonPrimitive.long,
                replayedCustomerEvents.size.toLong(),
            )
            assertEquals(customerEventIds, replayedCustomerEvents.map { it.id })
            assertEquals(eventEffects.map { it.getValue("name").jsonPrimitive.content },
                replayedCustomerEvents.map { it.name })
        }

    @Test fun `declined presentation remains parked for a later evaluation`() = runBlocking {
        val identity = identity("customer")
        val renderedEntry = fixture.getValue("renderedEntry").jsonObject
        val catalog = catalog(renderedEntry)
        val renderedAuthority = authority(renderedEntry)
        val prepared = catalog.prepare(profile(releaseEntry = renderedEntry), renderedAuthority)
        catalog.commit("customer", prepared)
        val snapshot = requireNotNull(catalog.snapshot("customer"))
        val captures = CopyOnWriteArrayList<String>()
        val presenter = RecordingDeviceLegPresenter(
            presentationResult = DeviceLegPresentationResult.Declined,
        )
        val service = DeviceLegService(
            identity = identity,
            events = store,
            catalog = catalog,
            journalDirectory = directory,
            scope = scope,
            capture = { name, _, _, _ -> captures += name; true },
            presenter = presenter,
            nowMillis = { 100_000L },
        )
        service.initialize()
        service.onAppWillEnterForeground()

        service.profileDidCommit(snapshot, renderedAuthority, "customer", 1)

        val journal = DeviceLegRunJournal(
            directory,
            "customer",
            DeviceLegStorageScope(renderedAuthority),
        )
        assertEquals(100_000L, journal.runs().single().park?.wakeAtMillis)
        assertEquals(listOf(JourneyEventNames.LEG_STARTED), captures)

        presenter.presentationResult = DeviceLegPresentationResult.Shown
        service.profileDidCommit(snapshot, renderedAuthority, "customer", 2)

        assertEquals(2, presenter.shownCount.get())
        assertNull(journal.runs().single().park)
        assertNull(journal.runs().single().completion)
    }

    @Test fun `rendered state arm waits for foreground before admission`() = runBlocking {
        val identity = identity("customer")
        val renderedEntry = fixture.getValue("renderedEntry").jsonObject
        val catalog = catalog(renderedEntry)
        val renderedAuthority = authority(renderedEntry)
        val prepared = catalog.prepare(profile(releaseEntry = renderedEntry), renderedAuthority)
        catalog.commit("customer", prepared)
        val snapshot = requireNotNull(catalog.snapshot("customer"))
        val captures = CopyOnWriteArrayList<Pair<String, Map<String, Any?>>>()
        val presenter = RecordingDeviceLegPresenter()
        val service = DeviceLegService(
            identity = identity,
            events = store,
            catalog = catalog,
            journalDirectory = directory,
            scope = scope,
            capture = { name, properties, _, _ ->
                captures += name to properties
                true
            },
            presenter = presenter,
            nowMillis = { 100_000L },
        )
        service.initialize()

        service.profileDidCommit(snapshot, renderedAuthority, "customer", 1)

        assertTrue(captures.isEmpty())
        assertNull(presenter.request)
        val journal = DeviceLegRunJournal(
            directory,
            "customer",
            DeviceLegStorageScope(renderedAuthority),
        )
        assertTrue(journal.runs().isEmpty())
        assertNull(journal.checkmark("experience_golden"))

        service.onAppWillEnterForeground()

        assertEquals(listOf(JourneyEventNames.LEG_STARTED), captures.map { it.first })
        assertEquals("screen_welcome", presenter.request?.screenId)
        assertNull(journal.runs().single().park)
    }

    @Test fun `profile revocation tears down the active device leg surface`() = runBlocking {
        val identity = identity("customer")
        val renderedEntry = fixture.getValue("renderedEntry").jsonObject
        val catalog = catalog(renderedEntry)
        val renderedAuthority = authority(renderedEntry)
        val prepared = catalog.prepare(profile(releaseEntry = renderedEntry), renderedAuthority)
        catalog.commit("customer", prepared)
        val snapshot = requireNotNull(catalog.snapshot("customer"))
        val presenter = RecordingDeviceLegPresenter()
        val service = DeviceLegService(
            identity = identity,
            events = store,
            catalog = catalog,
            journalDirectory = directory,
            scope = scope,
            capture = { _, _, _, _ -> true },
            presenter = presenter,
            nowMillis = { 100_000L },
        )
        service.initialize()
        service.onAppWillEnterForeground()
        service.profileDidCommit(snapshot, renderedAuthority, "customer", 1)
        requireNotNull(presenter.request)

        service.profileDidClear("customer", 2)

        assertEquals(listOf("customer"), presenter.shutdowns)
    }

    @Test fun `background fences a suspended presentation before publication`() = runBlocking {
        val identity = identity("customer")
        val renderedEntry = fixture.getValue("renderedEntry").jsonObject
        val catalog = catalog(renderedEntry)
        val renderedAuthority = authority(renderedEntry)
        val prepared = catalog.prepare(profile(releaseEntry = renderedEntry), renderedAuthority)
        catalog.commit("customer", prepared)
        val snapshot = requireNotNull(catalog.snapshot("customer"))
        val presentationStarted = CompletableDeferred<Unit>()
        val allowPresentation = CompletableDeferred<Unit>()
        val captures = CopyOnWriteArrayList<Pair<String, Map<String, Any?>>>()
        val presenter = RecordingDeviceLegPresenter(
            beforePresent = {
                presentationStarted.complete(Unit)
                allowPresentation.await()
            },
        )
        val service = DeviceLegService(
            identity = identity,
            events = store,
            catalog = catalog,
            journalDirectory = directory,
            scope = scope,
            capture = { name, properties, _, _ ->
                captures += name to properties
                true
            },
            presenter = presenter,
            nowMillis = { 100_000L },
        )
        service.initialize()
        service.onAppWillEnterForeground()
        val commit = async {
            service.profileDidCommit(snapshot, renderedAuthority, "customer", 1)
        }
        withTimeout(5_000L) { presentationStarted.await() }

        val background = async(start = CoroutineStart.UNDISPATCHED) {
            service.onAppDidEnterBackground()
        }
        assertTrue(!requireNotNull(presenter.request).canPresent())
        allowPresentation.complete(Unit)
        commit.await()
        background.await()

        assertEquals(0, presenter.shownCount.get())
        assertEquals(
            listOf(JourneyEventNames.LEG_STARTED, JourneyEventNames.LEG_COMPLETED),
            captures.map { it.first },
        )
        assertEquals("abandoned", captures.last().second["outcome"])
    }

    @Test fun `unhandled host dismissal completes the active device leg`() = runBlocking {
        val identity = identity("customer")
        val renderedEntry = fixture.getValue("renderedEntry").jsonObject
        val catalog = catalog(renderedEntry)
        val renderedAuthority = authority(renderedEntry)
        val prepared = catalog.prepare(profile(releaseEntry = renderedEntry), renderedAuthority)
        catalog.commit("customer", prepared)
        val snapshot = requireNotNull(catalog.snapshot("customer"))
        val captures = CopyOnWriteArrayList<Pair<String, Map<String, Any?>>>()
        val presenter = RecordingDeviceLegPresenter()
        val service = DeviceLegService(
            identity = identity,
            events = store,
            catalog = catalog,
            journalDirectory = directory,
            scope = scope,
            capture = { name, properties, _, _ ->
                captures += name to properties
                true
            },
            presenter = presenter,
            nowMillis = { 100_000L },
        )
        service.initialize()
        service.onAppWillEnterForeground()
        service.profileDidCommit(snapshot, renderedAuthority, "customer", 1)

        requireNotNull(presenter.request).onOutcome(DeviceLegSurfaceOutcome.DISMISSED)

        assertEquals(
            listOf(JourneyEventNames.LEG_STARTED, JourneyEventNames.LEG_COMPLETED),
            captures.map { it.first },
        )
        assertEquals("host_dismissed", captures.last().second["outcome"])
        val journal = DeviceLegRunJournal(
            directory,
            "customer",
            DeviceLegStorageScope(renderedAuthority),
        )
        assertTrue(journal.runs().isEmpty())
        assertEquals(
            "host_dismissed",
            journal.checkmark("experience_golden")?.outcome,
        )
    }

    @Test fun `live rendered run retains artifacts across profile replacement until report`() =
        runBlocking {
            val identity = identity("customer")
            val renderedEntry = fixture.getValue("renderedEntry").jsonObject
            val catalog = catalog(renderedEntry)
            val renderedAuthority = authority(renderedEntry)
            val prepared = catalog.prepare(profile(releaseEntry = renderedEntry), renderedAuthority)
            catalog.commit("customer", prepared)
            val snapshot = requireNotNull(catalog.snapshot("customer"))
            val manager = RecordingArtifactManager()
            val firstLeaseCloses = AtomicInteger()
            val secondLeaseCloses = AtomicInteger()
            val presenter = RecordingDeviceLegPresenter()
            val service = DeviceLegService(
                identity = identity,
                events = store,
                catalog = catalog,
                journalDirectory = directory,
                scope = scope,
                capture = { _, _, _, _ -> true },
                presenter = presenter,
                artifactManager = manager,
                nowMillis = { 100_000L },
            )
            service.initialize()
            service.onAppWillEnterForeground()

            service.profileDidCommit(
                snapshot,
                renderedAuthority,
                "customer",
                1,
                preparedArtifacts(snapshot, firstLeaseCloses),
            )

            val journal = DeviceLegRunJournal(
                directory,
                "customer",
                DeviceLegStorageScope(renderedAuthority),
            )
            val run = journal.runs().single()
            assertEquals(setOf(ARTIFACT_DIGEST), run.artifactDigests)
            assertEquals(run.artifactDigests, manager.retainedRunDigests(deviceLegArtifactRunKey(run)))

            service.profileDidCommit(
                snapshot,
                renderedAuthority,
                "customer",
                2,
                preparedArtifacts(snapshot, secondLeaseCloses),
            )

            assertEquals(1, firstLeaseCloses.get())
            assertEquals(run.artifactDigests, manager.retainedRunDigests(deviceLegArtifactRunKey(run)))

            requireNotNull(presenter.request).onOutcome(DeviceLegSurfaceOutcome.DISMISSED)

            assertNull(manager.retainedRunDigests(deviceLegArtifactRunKey(run)))
            assertTrue(journal.runs().isEmpty())
        }

    @Test fun `parked runs share one bounded retained release authentication`() = runBlocking {
        val identity = identity("customer")
        val catalog = catalog()
        val snapshot = authenticatedSnapshot(catalog)
        val arm = snapshot.profile.armedLegs.single()
        val releaseEntry = snapshot.profile.releases.single()
        val entryStepId = snapshot.releasesByDigest.values.single().leg
            .getValue("entryStepId").jsonPrimitive.content
        val journal = DeviceLegRunJournal(
            directory,
            "customer",
            DeviceLegStorageScope(authority),
        )
        repeat(2) { index ->
            val run = requireNotNull(
                journal.admit(
                    arm,
                    JourneyReentry.EveryTime,
                    entryStepId,
                    100_000L + index,
                    release = releaseEntry,
                ),
            )
            journal.markStartedQueued(run)
            journal.park(run.id, entryStepId, 150_000L)
        }
        var authentications = 0
        val service = DeviceLegService(
            identity = identity,
            events = store,
            catalog = catalog,
            journalDirectory = directory,
            scope = scope,
            capture = { _, _, _, _ -> true },
            pinnedReleaseAuthenticator = { pin, reference ->
                authentications += 1
                catalog.authenticatePinnedRelease(pin, reference)
            },
            nowMillis = { 200_000L },
        )

        service.initialize()

        service.onAppWillEnterForeground()
        service.profileDidCommit(
            snapshot.copy(releasesByDigest = emptyMap()),
            authority,
            "customer",
            1,
        )

        assertEquals(1, authentications)
        assertTrue(
            DeviceLegRunJournal(
                directory,
                "customer",
                DeviceLegStorageScope(authority),
            ).runs().isEmpty(),
        )
    }

    @Test fun `profile clear fences parked recovery before retained release publication`() =
        runBlocking {
            val identity = identity("customer")
            val catalog = catalog()
            val snapshot = authenticatedSnapshot(catalog)
            val arm = snapshot.profile.armedLegs.single()
            val releaseEntry = snapshot.profile.releases.single()
            val entryStepId = snapshot.releasesByDigest.values.single().leg
                .getValue("entryStepId").jsonPrimitive.content
            val journal = DeviceLegRunJournal(
                directory,
                "customer",
                DeviceLegStorageScope(authority),
            )
            val run = requireNotNull(
                journal.admit(
                    arm,
                    JourneyReentry.EveryTime,
                    entryStepId,
                    100_000L,
                    release = releaseEntry,
                ),
            )
            journal.markStartedQueued(run)
            journal.park(run.id, entryStepId, 150_000L)
            val authenticationStarted = CompletableDeferred<Unit>()
            val resumeAuthentication = CountDownLatch(1)
            val dispatches = AtomicInteger()
            val service = DeviceLegService(
                identity = identity,
                events = store,
                catalog = catalog,
                journalDirectory = directory,
                scope = scope,
                capture = { _, _, _, _ -> true },
                dispatcher = DeviceLegDispatching {
                    dispatches.incrementAndGet()
                    DeviceLegDispatchResult.Unsupported
                },
                pinnedReleaseAuthenticator = { pin, reference ->
                    authenticationStarted.complete(Unit)
                    check(resumeAuthentication.await(5, TimeUnit.SECONDS))
                    catalog.authenticatePinnedRelease(pin, reference)
                },
                nowMillis = { 200_000L },
            )

            service.initialize()

            service.onAppWillEnterForeground()
            val commit = async {
                service.profileDidCommit(
                    snapshot.copy(releasesByDigest = emptyMap()),
                    authority,
                    "customer",
                    1,
                )
            }
            withTimeout(5_000L) { authenticationStarted.await() }
            val clear = async(start = CoroutineStart.UNDISPATCHED) {
                service.profileDidClear("customer", 2)
            }
            resumeAuthentication.countDown()
            commit.await()
            clear.await()

            assertEquals(0, dispatches.get())
            assertTrue(
                DeviceLegRunJournal(
                    directory,
                    "customer",
                    DeviceLegStorageScope(authority),
                ).runs().isEmpty(),
            )
        }

    @Test fun `profile replacement fences a parked resume before its journal mutation`() =
        runBlocking {
            val identity = identity("customer")
            val catalog = catalog()
            val snapshot = authenticatedSnapshot(catalog)
            val firstResumeStarted = CompletableDeferred<Unit>()
            val allowFirstResume = CompletableDeferred<Unit>()
            val replacementResumeStarted = CompletableDeferred<Unit>()
            val allowReplacementResume = CompletableDeferred<Unit>()
            val resumeAttempts = AtomicInteger()
            val dispatches = AtomicInteger()
            val service = DeviceLegService(
                identity = identity,
                events = store,
                catalog = catalog,
                journalDirectory = directory,
                scope = scope,
                capture = { _, _, _, _ -> true },
                dispatcher = DeviceLegDispatching {
                    dispatches.incrementAndGet()
                    DeviceLegDispatchResult.Unsupported
                },
                beforeParkedResume = {
                    when (resumeAttempts.incrementAndGet()) {
                        1 -> {
                            firstResumeStarted.complete(Unit)
                            allowFirstResume.await()
                        }
                        2 -> {
                            replacementResumeStarted.complete(Unit)
                            allowReplacementResume.await()
                        }
                    }
                },
                nowMillis = { 200_000L },
            )

            service.initialize()

            service.onAppWillEnterForeground()
            service.profileDidCommit(snapshot, authority, "customer", 1)
            val arm = snapshot.profile.armedLegs.single()
            val releaseEntry = snapshot.profile.releases.single()
            val entryStepId = snapshot.releasesByDigest.values.single().leg
                .getValue("entryStepId").jsonPrimitive.content
            val journal = DeviceLegRunJournal(
                directory,
                "customer",
                DeviceLegStorageScope(authority),
            )
            val run = requireNotNull(
                journal.admit(
                    arm,
                    JourneyReentry.EveryTime,
                    entryStepId,
                    100_000L,
                    release = releaseEntry,
                ),
            )
            journal.markStartedQueued(run)
            journal.park(run.id, entryStepId, 150_000L)
            service.onAppDidEnterBackground()

            val foreground = async { service.onAppWillEnterForeground() }
            withTimeout(5_000L) { firstResumeStarted.await() }
            val replacement = async(start = CoroutineStart.UNDISPATCHED) {
                service.profileDidCommit(snapshot, authority, "customer", 2)
            }
            allowFirstResume.complete(Unit)
            withTimeout(5_000L) { replacementResumeStarted.await() }

            val stillParked = DeviceLegRunJournal(
                directory,
                "customer",
                DeviceLegStorageScope(authority),
            ).runs().single { it.id == run.id }
            assertTrue(stillParked.park != null)
            assertEquals(0, dispatches.get())

            allowReplacementResume.complete(Unit)
            foreground.await()
            replacement.await()
        }

    @Test fun `queued initialization preserves an admitted startup event`() = runBlocking {
        val identity = identity("customer")
        val catalog = catalog()
        val authenticated = authenticatedSnapshot(catalog)
        val snapshot = DeviceLegProfileCatalog.Snapshot(
            profile = JourneyPlaneProfile.decode(
                profile(
                    buildJsonObject {
                        put("type", "event")
                        put("eventName", "inventory_opened")
                    },
                ).toString().encodeToByteArray(),
            ),
            releasesByDigest = authenticated.releasesByDigest,
        )
        val captures = CopyOnWriteArrayList<String>()
        val service = DeviceLegService(
            identity = identity,
            events = store,
            catalog = catalog,
            journalDirectory = directory,
            scope = scope,
            capture = { name, _, _, _ ->
                captures += name
                true
            },
            nowMillis = { 100_000L },
        )

        service.profileDidCommit(snapshot, authority, "customer", 1)
        val admittedGeneration = service.eventAdmissionGeneration()
        service.enqueueInitialization()
        service.handleEvent(
            StoredEvent(
                id = "startup-event",
                name = "inventory_opened",
                timestampMillis = 100_000L,
                distinctId = "customer",
            ),
            admittedGeneration,
        )

        assertEquals(
            listOf(JourneyEventNames.LEG_STARTED, JourneyEventNames.LEG_COMPLETED),
            captures,
        )
    }

    @Test fun `journal admission is fenced against an identity mutation`() = runBlocking {
        val identity = object : IdentityProvider {
            private var current = "customer"
            private var revision = 0L

            override fun distinctId() = current
            override fun anonymousId() = current
            override fun rawDistinctId(): String? = current
            override val isIdentified = true
            override fun captureScope() = IdentityScope(current, revision)
            override fun isCurrentScope(scope: IdentityScope) =
                scope.distinctId == current && scope.revision == revision

            override fun <T> withCurrentScope(scope: IdentityScope, block: () -> T): T? {
                current = "replacement"
                revision += 1
                return null
            }
        }
        val catalog = catalog()
        val snapshot = authenticatedSnapshot(catalog)
        val captures = CopyOnWriteArrayList<String>()
        val service = DeviceLegService(
            identity = identity,
            events = store,
            catalog = catalog,
            journalDirectory = directory,
            scope = scope,
            capture = { name, _, _, _ -> captures.add(name) },
            nowMillis = { 100_000L },
        )

        service.initialize()

        service.onAppWillEnterForeground()
        service.profileDidCommit(snapshot, authority, "customer", 1)

        assertTrue(captures.isEmpty())
        assertTrue(
            DeviceLegRunJournal(
                directory,
                "customer",
                DeviceLegStorageScope(authority),
            ).runs().isEmpty(),
        )
    }

    @Test fun `profile clear accepted before journal admission prevents the stale arm`() =
        runBlocking {
            val identity = identity("customer")
            val catalog = catalog()
            val snapshot = authenticatedSnapshot(catalog)
            val admissionStarted = CompletableDeferred<Unit>()
            val resumeAdmission = CompletableDeferred<Unit>()
            val captures = CopyOnWriteArrayList<String>()
            val service = DeviceLegService(
                identity = identity,
                events = store,
                catalog = catalog,
                journalDirectory = directory,
                scope = scope,
                capture = { name, _, _, _ -> captures.add(name) },
                beforeAdmission = {
                    admissionStarted.complete(Unit)
                    resumeAdmission.await()
                },
                nowMillis = { 100_000L },
            )

            service.initialize()

            service.onAppWillEnterForeground()
            val commit = async {
                service.profileDidCommit(snapshot, authority, "customer", 1)
            }
            withTimeout(5_000L) { admissionStarted.await() }
            val clear = async(start = CoroutineStart.UNDISPATCHED) {
                service.profileDidClear("customer", 2)
            }
            resumeAdmission.complete(Unit)
            commit.await()
            clear.await()

            assertTrue(captures.isEmpty())
            assertTrue(
                DeviceLegRunJournal(
                    directory,
                    "customer",
                    DeviceLegStorageScope(authority),
                ).runs().isEmpty(),
            )
        }

    @Test fun `newer profile publication fences delayed commits and clears`() = runBlocking {
        val identity = identity("customer")
        val catalog = catalog()
        val snapshot = authenticatedSnapshot(catalog)
        val captures = CopyOnWriteArrayList<String>()
        val service = DeviceLegService(
            identity = identity,
            events = store,
            catalog = catalog,
            journalDirectory = directory,
            scope = scope,
            capture = { name, _, _, _ ->
                captures += name
                true
            },
            nowMillis = { 100_000L },
        )
        service.initialize()
        service.onAppWillEnterForeground()

        service.profileDidClear("customer", admissionGeneration = 2)
        service.profileDidCommit(snapshot, authority, "customer", admissionGeneration = 1)
        assertEquals(0L, service.eventAdmissionGeneration())
        assertTrue(captures.isEmpty())

        service.profileDidCommit(snapshot, authority, "customer", admissionGeneration = 3)
        val acceptedGeneration = service.eventAdmissionGeneration()
        assertEquals(1L, acceptedGeneration)
        assertEquals(2, captures.size)

        service.profileDidClear("customer", admissionGeneration = 2)
        assertEquals(acceptedGeneration, service.eventAdmissionGeneration())
        assertEquals(2, captures.size)
    }

    @Test fun `profile revocation fences a suspended effect before queued cleanup`() = runBlocking {
        val identity = identity("customer")
        val renderedEntry = fixture.getValue("renderedEntry").jsonObject
        val catalog = catalog(renderedEntry)
        val renderedAuthority = ProfileDeliveryAuthority(
            renderedEntry.getValue("locator").jsonObject
                .getValue("appId").jsonPrimitive.content,
            renderedEntry.getValue("locator").jsonObject
                .getValue("environment").jsonPrimitive.content,
        )
        val prepared = catalog.prepare(profile(releaseEntry = renderedEntry), renderedAuthority)
        catalog.commit("customer", prepared)
        val snapshot = requireNotNull(catalog.snapshot("customer"))
        val dispatchStarted = CompletableDeferred<DeviceLegDispatchRequest>()
        val resumeDispatch = CompletableDeferred<Unit>()
        val effectPublications = AtomicInteger()
        val dispatcher = DeviceLegDispatching { request ->
            dispatchStarted.complete(request)
            resumeDispatch.await()
            val published = request.executionFence.performIfCurrent(
                request.executionFenceToken,
            ) {
                effectPublications.incrementAndGet()
            }
            if (published == null) {
                DeviceLegDispatchResult.Failed
            } else {
                DeviceLegDispatchResult.Unsupported
            }
        }
        val service = DeviceLegService(
            identity = identity,
            events = store,
            catalog = catalog,
            journalDirectory = directory,
            scope = scope,
            capture = { _, _, _, _ -> true },
            dispatcher = dispatcher,
            nowMillis = { 100_000L },
        )

        service.initialize()

        service.onAppWillEnterForeground()
        val commit = async {
            service.profileDidCommit(snapshot, renderedAuthority, "customer", 3)
        }
        val request = withTimeout(5_000L) { dispatchStarted.await() }

        // A delayed older clear is discarded without revoking current work.
        service.profileDidClear("customer", admissionGeneration = 2)
        assertTrue(request.executionFence.isCurrent(request.executionFenceToken))

        val clear = async {
            service.profileDidClear("customer", admissionGeneration = 4)
        }
        withTimeout(5_000L) {
            while (request.executionFence.isCurrent(request.executionFenceToken)) {
                kotlinx.coroutines.yield()
            }
        }
        resumeDispatch.complete(Unit)
        commit.await()
        clear.await()

        assertEquals(0, effectPublications.get())
    }

    @Test fun `profile revalidation preserves a suspended admitted effect`() = runBlocking {
        val identity = identity("customer")
        val renderedEntry = fixture.getValue("renderedEntry").jsonObject
        val catalog = catalog(renderedEntry)
        val renderedAuthority = ProfileDeliveryAuthority(
            renderedEntry.getValue("locator").jsonObject
                .getValue("appId").jsonPrimitive.content,
            renderedEntry.getValue("locator").jsonObject
                .getValue("environment").jsonPrimitive.content,
        )
        val prepared = catalog.prepare(profile(releaseEntry = renderedEntry), renderedAuthority)
        catalog.commit("customer", prepared)
        val snapshot = requireNotNull(catalog.snapshot("customer"))
        val dispatchStarted = CompletableDeferred<DeviceLegDispatchRequest>()
        val resumeDispatch = CompletableDeferred<Unit>()
        val effectPublications = AtomicInteger()
        val dispatcher = DeviceLegDispatching { request ->
            dispatchStarted.complete(request)
            resumeDispatch.await()
            val published = request.executionFence.performIfCurrent(
                request.executionFenceToken,
            ) {
                effectPublications.incrementAndGet()
            }
            if (published == null) {
                DeviceLegDispatchResult.Failed
            } else {
                DeviceLegDispatchResult.Unsupported
            }
        }
        val service = DeviceLegService(
            identity = identity,
            events = store,
            catalog = catalog,
            journalDirectory = directory,
            scope = scope,
            capture = { _, _, _, _ -> true },
            dispatcher = dispatcher,
            nowMillis = { 100_000L },
        )

        service.initialize()

        service.onAppWillEnterForeground()
        val initialCommit = async {
            service.profileDidCommit(snapshot, renderedAuthority, "customer", 3)
        }
        val request = withTimeout(5_000L) { dispatchStarted.await() }

        val replacementCommit = async(start = CoroutineStart.UNDISPATCHED) {
            service.profileDidCommit(snapshot, renderedAuthority, "customer", 4)
        }
        assertTrue(request.executionFence.isCurrent(request.executionFenceToken))
        resumeDispatch.complete(Unit)
        initialCommit.await()
        replacementCommit.await()

        assertEquals(1, effectPublications.get())
    }

    @Test fun `app action does not publish across an identity fence change`() = runBlocking {
        val identity = IdentityService(context).also { it.setDistinctId("customer") }
        val request = dispatchRequest(
            identity,
            buildJsonObject {
                put("type", "app_action")
                put("name", "open_inventory")
            },
        )
        val delivered = mutableListOf<String>()
        val dispatcher = DeviceLegEffectDispatcher(
            identity = identity,
            capture = { _, _, _, _, _ -> error("revoked app action must not capture its rider") },
            deliverAppAction = { action, publishIfCurrent ->
                identity.setDistinctId("replacement")
                publishIfCurrent { delivered += action.name }
            },
        )

        assertEquals(DeviceLegDispatchResult.Failed, dispatcher.dispatch(request))
        assertTrue(delivered.isEmpty())
    }

    @Test fun `event effect cannot commit after its identity fence is revoked`() = runBlocking {
        val identity = IdentityService(context).also { it.setDistinctId("customer") }
        val request = dispatchRequest(
            identity,
            buildJsonObject {
                put("type", "send_event")
                put("eventName", "inventory_checked")
            },
        )
        var commits = 0
        val dispatcher = DeviceLegEffectDispatcher(
            identity = identity,
            capture = { _, _, _, _, admission ->
                identity.setDistinctId("replacement")
                admission.commitIfCurrent {
                    commits += 1
                    true
                } != null
            },
            deliverAppAction = { _, _ -> error("send_event must not publish an app action") },
        )

        assertEquals(DeviceLegDispatchResult.Failed, dispatcher.dispatch(request))
        assertEquals(0, commits)
    }

    @Test fun `admitted app action publishes and captures its attributed rider`() = runBlocking {
        val identity = IdentityService(context).also { it.setDistinctId("customer") }
        val request = dispatchRequest(
            identity,
            buildJsonObject {
                put("type", "app_action")
                put("name", "open_inventory")
            },
        )
        val delivered = mutableListOf<String>()
        val captures = mutableListOf<Pair<String, Map<String, Any?>>>()
        val dispatcher = DeviceLegEffectDispatcher(
            identity = identity,
            capture = { name, properties, _, _, admission ->
                admission.commitIfCurrent {
                    captures += name to properties
                    true
                } != null
            },
            deliverAppAction = { action, publishIfCurrent ->
                publishIfCurrent { delivered += action.name }
            },
        )

        assertEquals(DeviceLegDispatchResult.Outlet("next"), dispatcher.dispatch(request))
        assertEquals(listOf("open_inventory"), delivered)
        assertEquals(listOf("\$app_action_requested"), captures.map { it.first })
        assertEquals(request.run.journeyId, captures.single().second["journey_id"])
        assertEquals(request.run.generation, captures.single().second["leg_generation"])
    }

    private fun catalog(
        releaseEntry: JsonObject = entry,
    ): DeviceLegProfileCatalog {
        val keys = mapOf(
            "TEST_ONLY_DEV_KEYPAIR" to Base64.decode(
                fixture.getValue("publicKeyBase64").jsonPrimitive.content,
                Base64.NO_WRAP,
            ),
        )
        return DeviceLegProfileCatalog(keys, ReleaseHighWaterStore(context)) {
            runtime(releaseEntry)
        }
    }

    private fun authority(releaseEntry: JsonObject): ProfileDeliveryAuthority =
        ProfileDeliveryAuthority(
            releaseEntry.getValue("locator").jsonObject
                .getValue("appId").jsonPrimitive.content,
            releaseEntry.getValue("locator").jsonObject
                .getValue("environment").jsonPrimitive.content,
        )

    private class RecordingDeviceLegPresenter(
        @Volatile var available: Boolean = true,
        @Volatile var presentationResult: DeviceLegPresentationResult? = null,
        private val beforePresent: suspend (DeviceLegPresentationRequest) -> Unit = {},
    ) : DeviceLegPresenting {
        @Volatile var request: DeviceLegPresentationRequest? = null
        val shutdowns = CopyOnWriteArrayList<String>()
        val shownCount = AtomicInteger()

        override fun reserve(ownerDistinctId: String): DeviceLegPresentationReservation? =
            if (available) Reservation() else null

        override suspend fun present(
            request: DeviceLegPresentationRequest,
        ): DeviceLegPresentationResult {
            this.request = request
            beforePresent(request)
            val selected = presentationResult
            return if (selected != null) {
                shownCount.incrementAndGet()
                selected
            } else if (request.canPresent()) {
                shownCount.incrementAndGet()
                DeviceLegPresentationResult.Shown
            } else {
                DeviceLegPresentationResult.Failed
            }
        }

        override suspend fun shutdownOwnedBy(ownerDistinctId: String) {
            shutdowns += ownerDistinctId
        }

        private class Reservation : DeviceLegPresentationReservation {
            override fun close() = Unit
        }
    }

    private fun authenticatedSnapshot(
        catalog: DeviceLegProfileCatalog,
    ): DeviceLegProfileCatalog.Snapshot {
        val prepared = catalog.prepare(profile(), authority)
        catalog.commit("customer", prepared)
        return requireNotNull(catalog.snapshot("customer"))
    }

    private fun dispatchRequest(
        identity: IdentityService,
        action: JsonObject,
    ): DeviceLegDispatchRequest {
        val catalog = catalog()
        val prepared = catalog.prepare(profile(), authority)
        catalog.commit("customer", prepared)
        val snapshot = requireNotNull(catalog.snapshot("customer"))
        val arm = snapshot.profile.armedLegs.single()
        val release = snapshot.releasesByDigest.values.single()
        val run = DeviceLegRun(
            journeyId = "018f0000-0000-7000-8000-000000000001",
            generation = 0,
            reference = arm.reference,
            startedAtMillis = 100_000,
            isEnrollment = true,
            startedEventId = "started",
            completedEventId = "completed",
            startedQueued = true,
            stepId = "effect",
            context = arm.context,
        )
        val fence = DeviceLegExecutionFence()
        return DeviceLegDispatchRequest(
            run = run,
            release = release,
            stepId = "effect",
            action = action,
            effectId = "effect-id",
            distinctId = "customer",
            identityScope = identity.captureScope(),
            executionFence = fence,
            executionFenceToken = fence.token(),
        )
    }

    private fun preparedArtifacts(
        snapshot: DeviceLegProfileCatalog.Snapshot,
        closes: AtomicInteger,
    ): PreparedDeviceLegArtifacts {
        val release = snapshot.releasesByDigest.values.single()
        val riv = File.createTempFile("device-leg-artifact-", ".riv").apply {
            writeText("fixture")
            deleteOnExit()
        }
        return PreparedDeviceLegArtifacts(
            mapOf(
                release.descriptorSha256 to AcquiredRelease(
                    identity = release.identity,
                    artifactsByKey = mapOf("renders/fixture.riv" to riv),
                    rivFile = riv,
                    artifactDigests = setOf(ARTIFACT_DIGEST),
                    protection = Closeable { closes.incrementAndGet() },
                ),
            ),
        )
    }

    private class RecordingArtifactManager : DeviceLegArtifactManager {
        private val retained = linkedMapOf<String, Set<String>>()

        override suspend fun prepareDeviceLegs(
            snapshot: DeviceLegProfileCatalog.Snapshot,
        ): PreparedDeviceLegArtifacts = error("ProfileService owns preparation")

        override fun retainForRun(runKey: String, digests: Set<String>) {
            val previous = retained.putIfAbsent(runKey, digests)
            check(previous == null || previous == digests)
        }

        override fun releaseRun(runKey: String) {
            retained.remove(runKey)
        }

        override fun retainedRunDigests(runKey: String): Set<String>? = retained[runKey]
    }

    private fun profile(
        entryCondition: JsonObject = buildJsonObject { put("type", "app_foregrounded") },
        releaseEntry: JsonObject = entry,
    ): JsonObject {
        val locator = releaseEntry.getValue("locator").jsonObject
        val envelope = releaseEntry.getValue("envelope").jsonObject
        return buildJsonObject {
            put("schemaVersion", "nuxie.journey-plane-profile.v1")
            put("status", "ok")
            putJsonObject("delivery") {
                put("renderBaseUrl", "https://renders.example.com/")
                put("assetBaseUrl", "https://assets.example.com/")
            }
            putJsonArray("features") {}
            putJsonObject("facts") {
                putJsonObject("properties") {}
                putJsonObject("memberships") {}
                putJsonObject("assignments") {}
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

    private fun runtime(
        releaseEntry: JsonObject = entry,
    ): SupportedRuntime {
        val envelope = releaseEntry.getValue("envelope").jsonObject
        val descriptor = Json.parseToJsonElement(
            Base64.decode(
                envelope.getValue("descriptorBytesBase64").jsonPrimitive.content,
                Base64.NO_WRAP,
            ).decodeToString(),
        ).jsonObject
        val requirements = descriptor["requirements"] as? JsonObject
            ?: return SupportedRuntime(
                "0.1.0",
                emptySet(),
                emptyMap(),
                1,
                0,
                "unused",
                "unused",
                emptySet(),
            )
        fun JsonObject.string(key: String) = getValue(key).jsonPrimitive.content
        val luau = requirements.getValue("luau").jsonObject
        val scene = requirements.getValue("sceneFormat").jsonObject
        val timezone = requirements.getValue("timezoneData").jsonObject
        return SupportedRuntime(
            requirements.string("minimumSdkVersion"),
            setOf(requirements.string("runtimeRevision")),
            mapOf(
                luau.string("revision") to luau.getValue("bytecodeVersions")
                    .jsonArray.map { it.jsonPrimitive.int }.toSet(),
            ),
            scene.getValue("major").jsonPrimitive.int,
            scene.getValue("minor").jsonPrimitive.int,
            timezone.string("revision"),
            timezone.string("sha256"),
            requirements.getValue("requiredCapabilities").jsonArray
                .map { it.jsonPrimitive.content }.toSet(),
        )
    }

    private fun identity(distinctId: String) = object : IdentityProvider {
        override fun distinctId() = distinctId
        override fun anonymousId() = distinctId
        override fun rawDistinctId(): String? = null
        override val isIdentified = false
    }

    private companion object {
        const val ARTIFACT_DIGEST =
            "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
    }
}
