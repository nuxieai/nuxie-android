package ai.nuxie.sdk.journey

import ai.nuxie.sdk.presentation.JourneyEmissionBatchResult
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.test.resetMain
import kotlinx.serialization.json.boolean
import ai.nuxie.sdk.presentation.ExperiencePresentationService
import ai.nuxie.sdk.presentation.PresentationRegistry
import ai.nuxie.sdk.presentation.NuxieExperienceActivity
import ai.nuxie.sdk.presentation.CloseReason
import ai.nuxie.sdk.presentation.JourneyLinkRequest
import ai.nuxie.sdk.presentation.LinkStateRenderCapabilityShadow
import ai.nuxie.sdk.presentation.LinkStateNativeMountShadow
import ai.nuxie.sdk.presentation.openActivityLink
import ai.nuxie.sdk.LogLevel
import ai.nuxie.sdk.Nuxie
import ai.nuxie.sdk.NuxieConfiguration
import ai.nuxie.sdk.NuxieListener
import ai.nuxie.sdk.core.NuxieCore
import ai.nuxie.sdk.testsupport.FakeTransport
import ai.nuxie.sdk.testsupport.InertBillingClientAdapter
import kotlin.concurrent.thread
import ai.nuxie.sdk.core.supportedRuntimeForEmbeddedRuntime
import ai.nuxie.sdk.presentation.JourneyRuntimeEmissionCoordinator
import ai.nuxie.sdk.runtime.NuxieHostCommand
import ai.nuxie.sdk.runtime.NuxieHostValue
import ai.nuxie.sdk.runtime.NuxiePlayerStepOutcome
import ai.nuxie.sdk.NuxieEnvironment
import ai.nuxie.sdk.events.EventStore
import ai.nuxie.sdk.events.EventLog
import ai.nuxie.sdk.events.JsonValueConverter
import ai.nuxie.sdk.events.NuxieContextBuilder
import ai.nuxie.sdk.events.SQLiteEventStore
import ai.nuxie.sdk.events.StableEventCaptureResult
import ai.nuxie.sdk.events.StoredEvent
import ai.nuxie.sdk.events.SystemEventNames
import ai.nuxie.sdk.experiences.AcquiredJourneyRelease
import ai.nuxie.sdk.experiences.AuthenticatedJourneyRelease
import ai.nuxie.sdk.experiences.JourneyArtifactManager
import ai.nuxie.sdk.experiences.JourneyProfileCatalog
import ai.nuxie.sdk.experiences.JourneyPlaneProfile
import ai.nuxie.sdk.experiences.PreparedJourneyArtifacts
import ai.nuxie.sdk.experiences.JourneyReleaseHighWaterStore
import ai.nuxie.sdk.experiences.JourneyReleaseEnvelope
import ai.nuxie.sdk.experiences.JourneyReleaseSupportedRuntime
import ai.nuxie.sdk.fixtures.FixtureRunner
import ai.nuxie.sdk.testsupport.JourneyTestFixtures
import ai.nuxie.sdk.identity.IdentityProvider
import ai.nuxie.sdk.identity.IdentityScope
import ai.nuxie.sdk.identity.IdentityService
import ai.nuxie.sdk.network.ProfileDeliveryAuthority
import ai.nuxie.sdk.presentation.JourneyPresentationActionResult
import ai.nuxie.sdk.presentation.JourneyPresentationOwner
import ai.nuxie.sdk.presentation.JourneyPresentationRequest
import ai.nuxie.sdk.presentation.JourneyPresentationReservation
import ai.nuxie.sdk.presentation.JourneyPresentationResult
import ai.nuxie.sdk.presentation.JourneyPresenting
import ai.nuxie.sdk.presentation.JourneyScreenEmission
import ai.nuxie.sdk.presentation.JourneyScreenEmissionBatch
import ai.nuxie.sdk.presentation.JourneyScreenEmissionSource
import ai.nuxie.sdk.presentation.JourneyScreenDismissalResult
import ai.nuxie.sdk.presentation.JourneySurfaceOutcome
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
import kotlinx.serialization.json.contentOrNull
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
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
class JourneyServiceTest {
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

    @Test fun `killed screen run abandons on relaunch without presenting again`() = runBlocking {
        val renderedEntry = fixture.getValue("renderedEntry").jsonObject
        val catalog = catalog(renderedEntry)
        val renderedAuthority = authority(renderedEntry)
        val prepared = catalog.prepare(profile(releaseEntry = renderedEntry), renderedAuthority)
        runBlocking { catalog.commit("customer", prepared) }
        val snapshot = requireNotNull(catalog.snapshot("customer"))
        val firstScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val presenter = RecordingJourneyPresenter()
            val service = JourneyService(identity = identity("customer"), events = store, catalog = catalog,
                journalDirectory = directory, scope = firstScope, capture = { _, _, _, _ -> true },
                presenter = presenter, nowMillis = { 100_000L })
            service.initialize()
            service.onAppWillEnterForeground()
            service.profileDidCommit(snapshot, renderedAuthority, "customer", 1)
            assertEquals("screen_welcome", requireNotNull(presenter.request).screenId)
            val run = JourneyRunJournal(directory, "customer", JourneyStorageScope(renderedAuthority)).runs().single()
            assertNull(run.park)
            assertNull(run.completion)
            assertNull(run.pendingPresentationPublication)
        } finally {
            firstScope.cancel()
        }
        val presenter = RecordingJourneyPresenter()
        val completions = CopyOnWriteArrayList<Map<String, Any?>>()
        val recovered = JourneyService(identity = identity("customer"), events = store, catalog = catalog,
            journalDirectory = directory, scope = scope,
            capture = { name, properties, _, _ ->
                if (name == JourneyEventNames.LEG_COMPLETED) completions += properties
                true
            }, presenter = presenter, fixedStorageScope = JourneyStorageScope(renderedAuthority),
            nowMillis = { 200_000L })
        recovered.initialize()
        assertEquals(1, completions.size)
        assertEquals("abandoned", completions.single()["outcome"])
        assertNull(presenter.request)
    }

    @Before fun setUp() {
        File(context.filesDir, "nuxie").deleteRecursively()
        directory = File(context.filesDir, "nuxie")
        store = SQLiteEventStore(context, nowMillis = { 0L })
        context.getSharedPreferences("nuxie_journey_release_high_water", 0).edit().clear().commit()
    }

    @After fun tearDown() {
        runBlocking {
        store.close()
        scope.cancel()
        directory.deleteRecursively()
        }
    }

    @Test fun `recovery retries failed conversion acknowledgement without crediting another Journey`() = runBlocking {
        store.close()
        store = SQLiteEventStore(context, nowMillis = { 100_500L })
        val storageScope = JourneyStorageScope(authority)
        val journal = JourneyRunJournal(directory, "customer", storageScope)
        val arm = JourneyPlaneProfile.decode(profile().toString().encodeToByteArray()).armedLegs.single()
        val policy = Json.parseToJsonElement("""{
          "entry":{"trigger":{"type":"event","eventName":"start"},"frequency":{"type":"every_match"}},
          "goal":{"criterion":{"type":"event","eventName":"done"},"attribution":{"basis":"entry","window":{"amount":1,"unit":"day"}}},
          "exitWhenAny":[]
        }""").jsonObject
        val first = requireNotNull(journal.admit(arm, JourneyFrequency.EveryMatch, "step", 100_000, policy = policy))
        journal.markStartedQueued(first)
        journal.complete(first.id, "done", 100_100)
        journal.markCompletionQueued(first)
        val event = StoredEvent("conversion", "done", timestampMillis = 100_500, distinctId = "customer")
        store.insertPending(event)
        var acknowledgements = 0
        val interruptedStore = object : EventStore by store {
            override suspend fun acknowledgeConversionOccurrence(eventId: String, distinctId: String) {
                acknowledgements++
                throw java.io.IOException("Interrupted before inbox acknowledgement")
            }
        }
        val firstScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            JourneyService(identity = identity("customer"), events = interruptedStore, catalog = catalog(),
                journalDirectory = directory, scope = firstScope, capture = { _, _, _, _ -> true },
                fixedStorageScope = storageScope, nowMillis = { 100_500L }).initialize()
        } finally { firstScope.cancel() }
        assertEquals(1, acknowledgements)
        assertEquals(event.id, journal.conversionWatches().getValue(first.journeyId).conversion?.eventId)
        assertEquals(listOf(event.id), store.pendingConversionOccurrences("customer").map { it.event.id })

        val second = requireNotNull(journal.admit(arm, JourneyFrequency.EveryMatch, "step", 100_250, policy = policy))
        JourneyService(identity = identity("customer"), events = store, catalog = catalog(),
            journalDirectory = directory, scope = scope, capture = { _, _, _, _ -> true },
            fixedStorageScope = storageScope, nowMillis = { 100_500L }).initialize()
        val reopened = JourneyRunJournal(directory, "customer", storageScope)
        assertEquals(event.id, reopened.conversionWatches().getValue(first.journeyId).conversion?.eventId)
        assertNull(reopened.conversionWatches().getValue(second.journeyId).conversion)
        assertTrue(store.pendingConversionOccurrences("customer").isEmpty())
        assertEquals(listOf(event.id), store.pendingBatch(10).map { it.id })
    }

    @Test fun `lapsed access permits every match reentry after prior conversion`() = runBlocking {
        assertReentryAfterConversion("every_match", 2)
    }

    @Test fun `lapsed access preserves one time frequency after prior conversion`() = runBlocking {
        assertReentryAfterConversion("one_time", 1)
    }

    private suspend fun assertReentryAfterConversion(frequency: String, expectedStarts: Int) {
        val clock = java.util.concurrent.atomic.AtomicLong(100_000L)
        store.close()
        store = SQLiteEventStore(context, nowMillis = { clock.get() })
        val owned = java.util.concurrent.atomic.AtomicBoolean(false)
        val condition = Json.parseToJsonElement("""{
          "type":"app_foregrounded","condition":{"ir_version":1,
          "expr":{"type":"Not","arg":{"type":"Feature","op":"has","id":"premium"}}}
        }""").jsonObject
        val keys = java.security.KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
        val publication = policyPublication(keys, 1, "acquired", 1_440, frequency, condition)
        val catalog = policyCatalog(keys)
        catalog.commit("customer", catalog.prepare(profile(condition, publication), authority))
        val snapshot = requireNotNull(catalog.snapshot("customer"))
        val starts = CopyOnWriteArrayList<String>()
        val service = JourneyService(identity = identity("customer"), events = store, catalog = catalog,
            journalDirectory = directory, scope = scope, nowMillis = { clock.get() },
            capture = { name, properties, _, _ ->
                if (name == JourneyEventNames.LEG_STARTED) starts += requireNotNull(properties["journey_id"] as? String)
                true
            }, featureAccess = {
                ai.nuxie.sdk.features.FeatureAccess(owned.get(), true, null, ai.nuxie.sdk.features.FeatureType.BOOLEAN)
            })
        service.initialize()
        service.onAppWillEnterForeground()
        service.profileDidCommit(snapshot, authority, "customer", 1)
        assertEquals(1, starts.size)
        val originalJourney = starts.single()
        clock.set(100_100L)
        val outcome = StoredEvent("first-acquisition", "acquired", timestampMillis = clock.get(), distinctId = "customer")
        store.insertPending(outcome)
        assertTrue(service.handleEvent(outcome, service.eventAdmissionGeneration()))
        val journal = JourneyRunJournal(directory, "customer", JourneyStorageScope(authority))
        assertEquals(outcome.id, journal.conversionWatches().getValue(originalJourney).conversion?.eventId)
        owned.set(true)
        service.onAppDidEnterBackground()
        service.onAppWillEnterForeground()
        assertEquals(1, starts.size)
        owned.set(false)
        clock.set(100_200L)
        service.onAppDidEnterBackground()
        service.onAppWillEnterForeground()
        assertEquals(expectedStarts, starts.size)
        val retained = journal.conversionWatches()
        assertEquals(outcome.id, retained.getValue(originalJourney).conversion?.eventId)
        assertEquals(1, retained.values.count { it.conversion != null })
    }

    @Test fun `signed republish preserves earlier goal and window during native admission`() = runBlocking {
        val clock = java.util.concurrent.atomic.AtomicLong(1_000_000L)
        store.close()
        store = SQLiteEventStore(context, nowMillis = { clock.get() })
        val keys = java.security.KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
        val catalog = policyCatalog(keys)
        val service = JourneyService(identity = identity("customer"), events = store, catalog = catalog,
            journalDirectory = directory, scope = scope, capture = { _, _, _, _ -> true }, nowMillis = { clock.get() })
        service.initialize()
        service.onAppWillEnterForeground()
        val publications = listOf(
            policyPublication(keys, 1, "earlier_done", 1),
            policyPublication(keys, 2, "newer_done", 60),
        )
        publications.forEachIndexed { index, publication ->
            catalog.commit("customer", catalog.prepare(profile(releaseEntry = publication), authority))
            service.profileDidCommit(requireNotNull(catalog.snapshot("customer")), authority, "customer", index + 1L)
            clock.addAndGet(100_000L)
        }
        val journal = JourneyRunJournal(directory, "customer", JourneyStorageScope(authority))
        val watches = journal.conversionWatches()
        assertEquals(2, watches.size)
        val earlier = watches.values.single { it.versionId == "version_policy_1" }
        val newer = watches.values.single { it.versionId == "version_policy_2" }
        suspend fun deliver(name: String, id: String, at: Long) {
            val event = StoredEvent(id, name, timestampMillis = at, distinctId = "customer")
            store.insertPendingIfAbsent(event)
            assertTrue(service.handleEvent(event, service.eventAdmissionGeneration()))
        }
        deliver("earlier_done", "outside-earlier-window", 1_120_000L)
        deliver("newer_done", "newer-outcome", 1_120_000L)
        val afterNew = journal.conversionWatches()
        assertNull(afterNew.getValue(earlier.journeyId).conversion)
        assertEquals("newer-outcome", afterNew.getValue(newer.journeyId).conversion?.eventId)
        deliver("earlier_done", "delayed-earlier-outcome", 1_010_000L)
        deliver("earlier_done", "delayed-earlier-outcome", 1_010_000L)
        val retained = JourneyRunJournal(directory, "customer", JourneyStorageScope(authority)).conversionWatches()
        assertEquals("delayed-earlier-outcome", retained.getValue(earlier.journeyId).conversion?.eventId)
        assertEquals("newer-outcome", retained.getValue(newer.journeyId).conversion?.eventId)
    }

    private fun policyCatalog(keys: java.security.KeyPair) = JourneyProfileCatalog(
        mapOf("TEST_ONLY_POLICY" to keys.public.encoded.takeLast(32).toByteArray()),
        JourneyReleaseHighWaterStore(context),
    ) { runtime(entry) }

    private fun policyPublication(
        keys: java.security.KeyPair, sequence: Int, event: String, minutes: Int,
        frequency: String = "every_match",
        condition: JsonObject = buildJsonObject { put("type", "app_foregrounded") },
    ): JsonObject {
        val envelope = entry.getValue("envelope").jsonObject
        val source = Json.parseToJsonElement(Base64.decode(
            envelope.getValue("descriptorBytesBase64").jsonPrimitive.content, Base64.NO_WRAP,
        ).decodeToString()).jsonObject
        val originalIdentity = source.getValue("identity").jsonObject
        val changedIdentity = JsonObject(originalIdentity + mapOf(
            "experienceVersionId" to JsonPrimitive("version_policy_$sequence"),
            "buildId" to JsonPrimitive("build_policy_$sequence"),
            "versionNumber" to JsonPrimitive(originalIdentity.getValue("versionNumber").jsonPrimitive.int + sequence),
            "publishedAtSeq" to JsonPrimitive(originalIdentity.getValue("publishedAtSeq").jsonPrimitive.long + sequence),
        ))
        val policy = Json.parseToJsonElement("""{
          "entry":{"trigger":{"type":"event","eventName":"${'$'}app_opened"},"frequency":{"type":"$frequency"}},
          "goal":{"criterion":{"type":"event","eventName":"$event"},"attribution":{"basis":"entry","window":{"amount":$minutes,"unit":"minute"}}},
          "exitWhenAny":[]
        }""")
        val leg = JsonObject(source.getValue("leg").jsonObject + mapOf("policy" to policy, "entryCondition" to condition))
        val descriptor = JsonObject(source + mapOf("identity" to changedIdentity, "leg" to leg))
        val bytes = descriptor.toString().encodeToByteArray()
        val signature = java.security.Signature.getInstance("Ed25519").run {
            initSign(keys.private)
            update(ai.nuxie.sdk.experiences.JourneyReleaseLimits.SIGNATURE_DOMAIN.encodeToByteArray() + bytes)
            sign()
        }
        return JsonObject(entry + mapOf(
            "locator" to JsonObject(entry.getValue("locator").jsonObject + changedIdentity),
            "envelope" to JsonObject(envelope + mapOf(
                "descriptorBytesBase64" to JsonPrimitive(Base64.encodeToString(bytes, Base64.NO_WRAP)),
                "descriptorSizeBytes" to JsonPrimitive(bytes.size),
                "descriptorSha256" to JsonPrimitive(java.security.MessageDigest.getInstance("SHA-256")
                    .digest(bytes).joinToString("") { "%02x".format(it) }),
                "signature" to buildJsonObject {
                    put("version", 1); put("algorithm", "ed25519"); put("keyId", "TEST_ONLY_POLICY")
                    put("signatureBase64", Base64.encodeToString(signature, Base64.NO_WRAP))
                },
            )),
        ))
    }

    @Test fun `startup measurement stops at the earliest pending local route`() = runBlocking {
        store.close()
        store = SQLiteEventStore(context, nowMillis = { 100_500L })
        val entryEvent = StoredEvent("entry", "start", timestampMillis = 100_000, distinctId = "customer")
        val goalEvent = StoredEvent("goal", "done", timestampMillis = 100_500, distinctId = "customer")
        store.insertPendingAndStageRoute(entryEvent)
        store.insertPendingAndStageRoute(goalEvent)
        JourneyService(identity = identity("customer"), events = store, catalog = catalog(),
            journalDirectory = directory, scope = scope, capture = { _, _, _, _ -> true },
            fixedStorageScope = JourneyStorageScope(authority), nowMillis = { 100_500L }).initialize()

        // Entry routing still owns admission. Measurement must leave the later goal
        // available until that route has had the opportunity to create its watch.
        assertEquals(listOf(goalEvent.id), store.pendingConversionOccurrences("customer").map { it.event.id })
        assertEquals(listOf(entryEvent.id, goalEvent.id), store.queryPendingLocalRoutes("customer").map { it.id })
    }

    @Test fun `production core completes a screenless release without native preparation`() = runBlocking {
        val core = ai.nuxie.sdk.core.NuxieCore(
            context = RuntimeEnvironment.getApplication(), apiKey = "pk_test_native_screenless",
            environment = NuxieEnvironment.DEVELOPMENT, logLevel = LogLevel.NONE, beforeSend = null,
            overrides = ai.nuxie.sdk.core.NuxieCore.Overrides(
                transport = ai.nuxie.sdk.testsupport.FakeTransport(), registerLifecycle = false,
                requestInitialProfileRefresh = false,
                billingClientFactory = ai.nuxie.sdk.testsupport.InertBillingClientAdapter.factory,
                eventDatabaseFile = File(directory, "core-screenless.db")))
        try {
            val owner = core.identity.distinctId()
            val catalog = catalog()
            catalog.commit(owner, catalog.prepare(profile(), authority))
            val snapshot = checkNotNull(catalog.snapshot(owner))
            assertTrue(snapshot.releasesByDigest.values.single().leg.getValue("screens").jsonArray.isEmpty())
            core.journeys.initialize()
            core.journeys.onAppWillEnterForeground()
            core.journeys.profileDidCommit(snapshot, authority, owner, 1)
            assertTrue(core.store.hasEvent(JourneyEventNames.LEG_COMPLETED, owner))
        } finally { core.stopAndAwait() }
    }

    @Test fun `foreground arm executes its authenticated leg once across revalidation`() = runBlocking {
        val identity = identity("customer")
        val catalog = catalog()
        val prepared = catalog.prepare(profile(), authority)
        runBlocking { catalog.commit("customer", prepared) }
        val snapshot = requireNotNull(catalog.snapshot("customer"))
        val captures = CopyOnWriteArrayList<Pair<String, Map<String, Any?>>>()
        var now = 100_000L
        val service = JourneyService(
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
        val journal = JourneyRunJournal(
            directory,
            "customer",
            JourneyStorageScope(authority),
        )
        assertTrue(journal.runs().isEmpty())
        assertEquals("continue", journal.checkmark("experience_golden")?.outcome)

        now += 1_000
        service.profileDidCommit(snapshot, authority, "customer", 2)
        assertEquals(2, captures.size)
    }

    @Test fun `signed navigation transition reaches the presentation request`() = runBlocking {
        val contract = Json.parseToJsonElement(
            FixtureRunner.fixturesRoot().resolve("journeys/planes/text-input-navigation.json").readText(),
        ).jsonObject
        val entry = contract.getValue("transitionEntry").jsonObject
        val catalog = catalog(entry)
        val authority = authority(entry)
        catalog.commit("customer", catalog.prepare(profile(releaseEntry = entry), authority))
        val presenter = RecordingJourneyPresenter()
        val service = JourneyService(identity = identity("customer"), events = store, catalog = catalog,
            journalDirectory = directory, scope = scope, capture = { _, _, _, _ -> true },
            presenter = presenter, nowMillis = { 100_000L })
        service.initialize()
        service.onAppWillEnterForeground()
        service.profileDidCommit(requireNotNull(catalog.snapshot("customer")), authority, "customer", 1)
        assertEquals(contract.getValue("transition"), requireNotNull(presenter.request).transition)
    }

    @Test fun `busy presentation leaves the rendered arm unconsumed for a later evaluation`() = runBlocking {
        val identity = identity("customer")
        val renderedEntry = fixture.getValue("renderedEntry").jsonObject
        val catalog = catalog(renderedEntry)
        val renderedAuthority = authority(renderedEntry)
        val prepared = catalog.prepare(profile(releaseEntry = renderedEntry), renderedAuthority)
        runBlocking { catalog.commit("customer", prepared) }
        val snapshot = requireNotNull(catalog.snapshot("customer"))
        val captures = CopyOnWriteArrayList<Pair<String, Map<String, Any?>>>()
        val presenter = RecordingJourneyPresenter(available = false)
        val service = JourneyService(
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

        val journal = JourneyRunJournal(
            directory,
            "customer",
            JourneyStorageScope(renderedAuthority),
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

    @Test fun `goal exit preserves active presentation and its navigation handler`() = runBlocking {
        assertNavigationAfterConversion(true)
    }

    @Test fun `conversion without goal exit preserves active presentation and navigation`() = runBlocking {
        assertNavigationAfterConversion(false)
    }

    private suspend fun assertNavigationAfterConversion(exitEnabled: Boolean) {
        val clock = java.util.concurrent.atomic.AtomicLong(100_000L)
        store.close()
        store = SQLiteEventStore(context, nowMillis = { clock.get() })
        val renderedEntry = fixture.getValue("renderedEntry").jsonObject
        val catalog = catalog(renderedEntry)
        val renderedAuthority = authority(renderedEntry)
        catalog.commit("customer", catalog.prepare(profile(releaseEntry = renderedEntry), renderedAuthority))
        val baseline = requireNotNull(catalog.snapshot("customer"))
        val original = baseline.releasesByDigest.values.single()
        val exits = if (exitEnabled) """[{"type":"goal_met"}]""" else "[]"
        val policy = Json.parseToJsonElement("""{
          "entry":{"trigger":{"type":"event","eventName":"${'$'}app_opened"},"frequency":{"type":"one_time"}},
          "goal":{"criterion":{"type":"event","eventName":"reading_completed"},"attribution":{"basis":"first_shown","window":{"amount":1,"unit":"hour"}}},
          "exitWhenAny":$exits
        }""")
        val leg = JsonObject(original.leg + mapOf(
            "policy" to policy,
            "screens" to JsonArray(original.leg.getValue("screens").jsonArray +
                Json.parseToJsonElement("""{"id":"screen_details","responseCaptures":[]}""")),
            "steps" to JsonArray(original.leg.getValue("steps").jsonArray.map { step ->
                if (step.jsonObject["id"]?.jsonPrimitive?.content == "report")
                    Json.parseToJsonElement("""{"kind":"action","id":"report","action":{"type":"navigate","screenId":"screen_details"},"outlets":{}}""")
                else step
            }),
        ))
        val envelope = JourneyReleaseEnvelope.authenticate(renderedEntry.getValue("envelope").toString().encodeToByteArray(),
            mapOf("TEST_ONLY_DEV_KEYPAIR" to Base64.decode(fixture.getValue("publicKeyBase64").jsonPrimitive.content, Base64.NO_WRAP)))
        val release = AuthenticatedJourneyRelease(envelope, original.identity,
            JsonObject(original.descriptor + ("leg" to leg)), original.publishedAtSeqToPromote)
        val snapshot = baseline.copy(releasesByDigest = mapOf(release.descriptorSha256 to release))
        val captures = CopyOnWriteArrayList<String>()
        val presenter = RecordingJourneyPresenter()
        val service = JourneyService(identity = identity("customer"), events = store, catalog = catalog,
            journalDirectory = directory, scope = scope, capture = { name, _, _, _ -> captures += name; true },
            captureScreenEvent = { name, properties, eventId, distinctId, occurredAt, admission, origin ->
                val event = StoredEvent(eventId, name, JsonValueConverter.fromMap(properties), occurredAt, distinctId, journeyOrigin = origin)
                val settled = admission?.commitIfCurrent { true } != null
                StableEventCaptureResult(settled, event.takeIf { settled })
            }, presenter = presenter, nowMillis = { clock.get() })
        service.initialize()
        service.onAppWillEnterForeground()
        service.profileDidCommit(snapshot, renderedAuthority, "customer", 1)
        val request = requireNotNull(presenter.request)
        val journal = JourneyRunJournal(directory, "customer", JourneyStorageScope(renderedAuthority))
        // Supply the real presenter's exposure boundary; this presenter records ownership only.
        clock.set(100_100L)
        val shown = StoredEvent("shown", "${'$'}experience_shown", buildJsonObject {
            put("journey_id", request.journeyId)
            put("experience_id", release.identity.experienceId)
            put("experience_version_id", release.identity.experienceVersionId)
        }, 100_100L, "customer")
        store.insertPending(shown)
        assertTrue(service.handleEvent(shown, service.eventAdmissionGeneration()))
        clock.set(100_200L)
        val outcome = StoredEvent("reading-outcome", "reading_completed", timestampMillis = 100_200L, distinctId = "customer")
        store.insertPending(outcome)
        assertTrue(service.handleEvent(outcome, service.eventAdmissionGeneration()))
        assertEquals(outcome.id, journal.conversionWatches().getValue(request.journeyId).conversion?.eventId)
        assertTrue(presenter.shutdowns.isEmpty())
        assertFalse(captures.contains(JourneyEventNames.LEG_COMPLETED))
        assertNull(journal.runs().single().completion)
        assertTrue(request.onEmissionBatch(JourneyScreenEmissionBatch(request.journeyId, 0, "after-goal",
            JourneyScreenEmissionSource("screen_welcome", "continue"),
            listOf(JourneyScreenEmission("continue-event", 0, 100_300L, "continue", JsonObject(emptyMap())))), null) == JourneyEmissionBatchResult.ACCEPTED)
        withTimeout(5_000) {
            while (presenter.shownCount.get() < 2) kotlinx.coroutines.delay(10)
        }
        service.onAppDidEnterBackground() // FIFO barrier after navigation settles.
        assertEquals("screen_details", presenter.request?.screenId)
        assertEquals(request.journeyId, presenter.request?.journeyId)
        assertNull(journal.runs().single().completion)
        assertTrue(presenter.shutdowns.isEmpty())
        assertFalse(captures.contains(JourneyEventNames.LEG_COMPLETED))
    }

    @Test fun `unaccepted frame save prevents its following emission and completion`() = runBlocking {
        val renderedEntry = fixture.getValue("renderedEntry").jsonObject
        val catalog = catalog(renderedEntry)
        val authority = authority(renderedEntry)
        catalog.commit("customer", catalog.prepare(profile(releaseEntry = renderedEntry), authority))
        val presenter = RecordingJourneyPresenter()
        val captured = CopyOnWriteArrayList<String>()
        val service = JourneyService(identity("customer"), store, catalog, directory, scope,
            capture = { name, _, _, _ -> captured += name; true }, presenter = presenter)
        service.initialize()
        service.onAppWillEnterForeground()
        service.profileDidCommit(checkNotNull(catalog.snapshot("customer")), authority, "customer", 1)
        val request = checkNotNull(presenter.request)
        val batch = JourneyScreenEmissionBatch(request.journeyId, 0, "save-and-emit",
            JourneyScreenEmissionSource("screen_welcome", "runtime:1"), listOf(
                JourneyScreenEmission("after-save", 0, 100, "survey_submitted", JsonObject(emptyMap()))))
        val save = ai.nuxie.sdk.presentation.ExperienceFrameSave(
            ai.nuxie.sdk.presentation.ExperienceResponseSaveRequest("feedback", null, JsonObject(emptyMap())),
            "screen_welcome", { error("Unaccepted save cannot confirm") })
        assertFalse(request.onEmissionBatch(batch,
            ai.nuxie.sdk.presentation.JourneyRuntimeEmissionSources(saves = listOf(save))) == JourneyEmissionBatchResult.ACCEPTED)
        assertFalse(captured.contains("survey_submitted"))
        assertFalse(captured.contains(JourneyEventNames.LEG_COMPLETED))
        val run = JourneyRunJournal(directory, "customer", JourneyStorageScope(authority)).runs().single()
        assertEquals(0L, run.nextPresentationBatchSequence)
        assertNull(run.completion)
    }

    @Test fun `renderer batches durably publish once and route the owning run`() = runBlocking {
        val identity = identity("customer")
        val renderedEntry = fixture.getValue("renderedEntry").jsonObject
        val catalog = catalog(renderedEntry)
        val renderedAuthority = authority(renderedEntry)
        val prepared = catalog.prepare(profile(releaseEntry = renderedEntry), renderedAuthority)
        runBlocking { catalog.commit("customer", prepared) }
        val snapshot = requireNotNull(catalog.snapshot("customer"))
        val presenter = RecordingJourneyPresenter()
        val systemCaptures = CopyOnWriteArrayList<String>()
        val ordinaryCaptures = linkedMapOf<String, StoredEvent>()
        val service = JourneyService(
            identity = identity,
            events = store,
            catalog = catalog,
            journalDirectory = directory,
            scope = scope,
            capture = { name, _, _, _ ->
                systemCaptures += name
                true
            },
            captureScreenEvent = { name, properties, eventId, distinctId, occurredAt, admission, origin ->
                val event = StoredEvent(
                    id = eventId,
                    name = name,
                    properties = JsonValueConverter.fromMap(properties),
                    timestampMillis = occurredAt,
                    distinctId = distinctId,
                    journeyOrigin = origin,
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
        val run = JourneyRunJournal(
            directory,
            "customer",
            JourneyStorageScope(renderedAuthority),
        ).runs().single()
        val first = JourneyScreenEmissionBatch(
            journeyId = run.journeyId,
            batchSequence = 0,
            invocationId = "invocation-1",
            source = JourneyScreenEmissionSource("screen_welcome", "submit"),
            emissions = listOf(
                JourneyScreenEmission(
                    id = "emission-1",
                    sequence = 0,
                    occurredAtMillis = 90_000L,
                    name = "survey_submitted",
                    payload = JsonObject(mapOf("answer" to JsonPrimitive("premium"))),
                ),
            ),
        )

        assertTrue(request.onEmissionBatch(first, null) == JourneyEmissionBatchResult.ACCEPTED)
        assertTrue(request.onEmissionBatch(first, null) == JourneyEmissionBatchResult.ACCEPTED)

        val retained = JourneyRunJournal(
            directory,
            "customer",
            JourneyStorageScope(renderedAuthority),
        ).runs().single()
        assertEquals(1L, retained.nextPresentationBatchSequence)
        assertEquals(1L, retained.nextPresentationEmissionSequence)
        assertEquals(setOf("emission-1"), ordinaryCaptures.keys)
        val origin = requireNotNull(ordinaryCaptures.getValue("emission-1").journeyOrigin)
        assertEquals("screen_control", origin.source)
        assertEquals(run.journeyId, origin.journeyId)
        assertEquals(run.generation, origin.generation)
        assertEquals("screen_welcome", origin.screenId)
        assertEquals("submit", origin.actionId)
        assertEquals("invocation-1", origin.invocationId)
        assertEquals("emission-1", origin.occurrenceId)
        assertNull(origin.stepId)
        val properties = ordinaryCaptures.getValue("emission-1").properties
        assertEquals(run.journeyId, properties.getValue("journey_id").jsonPrimitive.content)
        assertEquals("screen_welcome", properties.getValue("screen_id").jsonPrimitive.content)
        assertEquals(90_000L, ordinaryCaptures.getValue("emission-1").timestampMillis)

        assertTrue(
            request.onEmissionBatch(
                JourneyScreenEmissionBatch(
                    journeyId = run.journeyId,
                    batchSequence = 1,
                    invocationId = "invocation-2",
                    source = JourneyScreenEmissionSource("screen_welcome", "continue"),
                    emissions = listOf(
                        JourneyScreenEmission(
                            id = "emission-2",
                            sequence = 1,
                            occurredAtMillis = 95_000L,
                            name = "continue",
                            payload = JsonObject(emptyMap()),
                        ),
                    ),
                ), null,
            ) == JourneyEmissionBatchResult.ACCEPTED,
        )
        // This lifecycle command is a FIFO barrier behind the routed continuation.
        service.onAppDidEnterBackground()

        assertEquals(setOf("emission-1", "emission-2"), ordinaryCaptures.keys)
        assertEquals(
            listOf(JourneyEventNames.LEG_STARTED, JourneyEventNames.LEG_COMPLETED),
            systemCaptures,
        )
        assertEquals(listOf("customer"), presenter.shutdowns)
        assertTrue(
            JourneyRunJournal(
                directory,
                "customer",
                JourneyStorageScope(renderedAuthority),
            ).runs().isEmpty(),
        )
    }

    @Test fun `shared renderer fixture replays one customer event through EventLog`() =
        runBlocking {
            val screenFixture = JourneyTestFixtures.rendererPublication
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
            runBlocking { catalog.commit("customer", prepared) }
            val snapshot = requireNotNull(catalog.snapshot("customer"))
            val release = snapshot.releasesByDigest.values.single()
            val journal = JourneyRunJournal(
                directory,
                "customer",
                JourneyStorageScope(renderedAuthority),
            )
            val run = requireNotNull(
                journal.admit(
                    snapshot.profile.armedLegs.single(),
                    JourneyFrequency.EveryMatch,
                    release.leg.getValue("entryStepId").jsonPrimitive.content,
                    100_000L,
                    release = snapshot.profile.releases.single(),
                    executionSnapshot = executionSnapshot(snapshot),
                ),
            )
            journal.markStartedQueued(run)
            val responses = expected.getValue("response_values").jsonObject
            val publication = JourneyRun.PendingPresentationPublication(
                invocationId = "fixture-recovery-invocation",
                batchSequence = expected.getValue("batch_sequence").jsonPrimitive.long,
                nextEmissionSequence = expected.getValue("emission_sequences").jsonArray
                    .last().jsonPrimitive.long + 1,
                sourceScreenId = release.leg.getValue("screens").jsonArray.first()
                    .jsonObject.getValue("id").jsonPrimitive.content,
                sourceActionId = input.getValue("action_id").jsonPrimitive.content,
                sourceComponentId = input.getValue("component_id").jsonPrimitive.content,
                sourceInstanceId = input.getValue("instance_id").jsonPrimitive.content,

                items = eventEffects.mapIndexed { index, effect ->
                    JourneyRun.PendingPresentationPublication.Item(
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
                    this@JourneyServiceTest.context,
                    NuxieEnvironment.DEVELOPMENT,
                    LogLevel.DEBUG,
                    identity,
                ),
                identity,
                beforeSend = null,
                scope = scope,
                nowMillis = { 102_000L },
            )
            fun recoveringService() = JourneyService(
                identity = identity,
                events = store,
                catalog = catalog,
                journalDirectory = directory,
                scope = scope,
                capture = eventLog::captureSystemEvent,
                captureScreenEvent = eventLog::captureScreenEvent,
                nowMillis = { 102_000L },
                fixedStorageScope = JourneyStorageScope(renderedAuthority),
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
            for (event in replayedCustomerEvents) {
                val origin = requireNotNull(event.journeyOrigin)
                assertEquals("screen_control", origin.source)
                assertEquals(run.journeyId, origin.journeyId)
                assertEquals(run.generation, origin.generation)
                assertEquals(publication.sourceScreenId, origin.screenId)
                assertEquals(publication.sourceActionId, origin.actionId)
                assertEquals("fixture-recovery-invocation", origin.invocationId)
                assertEquals(event.id, origin.occurrenceId)
            }
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
        runBlocking { catalog.commit("customer", prepared) }
        val snapshot = requireNotNull(catalog.snapshot("customer"))
        val captures = CopyOnWriteArrayList<String>()
        val presenter = RecordingJourneyPresenter(
            presentationResult = JourneyPresentationResult.Declined,
        )
        val service = JourneyService(
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

        val journal = JourneyRunJournal(
            directory,
            "customer",
            JourneyStorageScope(renderedAuthority),
        )
        assertEquals(100_000L, journal.runs().single().park?.wakeAtMillis)
        assertEquals(listOf(JourneyEventNames.LEG_STARTED), captures)

        presenter.presentationResult = JourneyPresentationResult.Shown
        val replacement = catalog.prepare(
            profile(
                releaseEntry = renderedEntry,
                renderBaseUrl = "https://replacement-renders.example.com/",
                assetBaseUrl = "https://replacement-assets.example.com/",
            ),
            renderedAuthority,
        )
        catalog.commit("customer", replacement)
        val replacementSnapshot = requireNotNull(catalog.snapshot("customer"))
        service.profileDidCommit(replacementSnapshot, renderedAuthority, "customer", 2)

        assertEquals(2, presenter.shownCount.get())
        assertEquals(snapshot.profile.delivery, presenter.request?.delivery)
        assertNull(journal.runs().single().park)
        assertNull(journal.runs().single().completion)
    }

    @Test fun `rendered state arm waits for foreground before admission`() = runBlocking {
        val identity = identity("customer")
        val renderedEntry = fixture.getValue("renderedEntry").jsonObject
        val catalog = catalog(renderedEntry)
        val renderedAuthority = authority(renderedEntry)
        val prepared = catalog.prepare(profile(releaseEntry = renderedEntry), renderedAuthority)
        runBlocking { catalog.commit("customer", prepared) }
        val snapshot = requireNotNull(catalog.snapshot("customer"))
        val captures = CopyOnWriteArrayList<Pair<String, Map<String, Any?>>>()
        val presenter = RecordingJourneyPresenter()
        val service = JourneyService(
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
        val journal = JourneyRunJournal(
            directory,
            "customer",
            JourneyStorageScope(renderedAuthority),
        )
        assertTrue(journal.runs().isEmpty())
        assertNull(journal.checkmark("experience_golden"))

        service.onAppWillEnterForeground()

        assertEquals(listOf(JourneyEventNames.LEG_STARTED), captures.map { it.first })
        assertEquals("screen_welcome", presenter.request?.screenId)
        assertNull(journal.runs().single().park)
    }

    @Test fun `unavailable products enter the authored Journey route`() = runBlocking {
        val identity = identity("customer")
        val renderedEntry = fixture.getValue("renderedEntry").jsonObject
        val catalog = catalog(renderedEntry)
        val renderedAuthority = authority(renderedEntry)
        val prepared = catalog.prepare(profile(releaseEntry = renderedEntry), renderedAuthority)
        runBlocking { catalog.commit("customer", prepared) }
        val baseline = requireNotNull(catalog.snapshot("customer"))
        val original = baseline.releasesByDigest.values.single()
        val route = buildJsonObject {
            putJsonObject("host") {
                put("kind", "screen")
                put("screenId", "screen_welcome")
            }
            put("eventName", SystemEventNames.PRODUCTS_UNAVAILABLE)
            put("entryStepId", "report")
        }
        val leg = JsonObject(
            original.leg + (
                "routes" to JsonArray(
                    original.leg.getValue("routes").jsonArray + route,
                )
            ),
        )
        val release = AuthenticatedJourneyRelease(
            JourneyReleaseEnvelope.authenticate(
                renderedEntry.getValue("envelope").toString().encodeToByteArray(),
                mapOf(
                    "TEST_ONLY_DEV_KEYPAIR" to Base64.decode(
                        fixture.getValue("publicKeyBase64").jsonPrimitive.content,
                        Base64.NO_WRAP,
                    ),
                ),
            ),
            original.identity,
            JsonObject(original.descriptor + ("leg" to leg)),
            original.publishedAtSeqToPromote,
        )
        val snapshot = baseline.copy(
            releasesByDigest = mapOf(release.descriptorSha256 to release),
        )
        val captured = CopyOnWriteArrayList<Pair<String, Map<String, Any?>>>()
        val presenter = RecordingJourneyPresenter(
            presentationResult = JourneyPresentationResult.ProductsUnavailable,
        )
        val service = JourneyService(
            identity = identity,
            events = store,
            catalog = catalog,
            journalDirectory = directory,
            scope = scope,
            capture = { name, properties, _, _ ->
                captured += name to properties
                true
            },
            presenter = presenter,
            nowMillis = { 100_000L },
        )
        service.initialize()
        service.onAppWillEnterForeground()

        service.profileDidCommit(snapshot, renderedAuthority, "customer", 1)

        val productEvent = captured.single { it.first == SystemEventNames.PRODUCTS_UNAVAILABLE }
        assertEquals(listOf("monthly", "yearly"), productEvent.second["product_ids"])
        val completion = captured.single { it.first == JourneyEventNames.LEG_COMPLETED }
        assertEquals("continue", completion.second["outcome"])
    }

    @Test fun `profile revocation tears down the active Journey surface`() = runBlocking {
        val identity = identity("customer")
        val renderedEntry = fixture.getValue("renderedEntry").jsonObject
        val catalog = catalog(renderedEntry)
        val renderedAuthority = authority(renderedEntry)
        val prepared = catalog.prepare(profile(releaseEntry = renderedEntry), renderedAuthority)
        runBlocking { catalog.commit("customer", prepared) }
        val snapshot = requireNotNull(catalog.snapshot("customer"))
        val presenter = RecordingJourneyPresenter()
        val service = JourneyService(
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

    @Test fun `profile withdrawal stops admission without abandoning the active Journey`() = runBlocking {
        val identity = identity("customer")
        val renderedEntry = fixture.getValue("renderedEntry").jsonObject
        val catalog = catalog(renderedEntry)
        val renderedAuthority = authority(renderedEntry)
        val prepared = catalog.prepare(profile(releaseEntry = renderedEntry), renderedAuthority)
        runBlocking { catalog.commit("customer", prepared) }
        val snapshot = requireNotNull(catalog.snapshot("customer"))
        val presenter = RecordingJourneyPresenter()
        val service = JourneyService(
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
        val journal = JourneyRunJournal(
            directory,
            "customer",
            JourneyStorageScope(renderedAuthority),
        )
        assertNull(journal.runs().single().completion)

        service.profileDidWithdraw("customer", 2)

        assertTrue(presenter.shutdowns.isEmpty())
        assertNull(journal.runs().single().completion)
    }

    @Test fun `background fences a suspended presentation before publication`() = runBlocking {
        val identity = identity("customer")
        val renderedEntry = fixture.getValue("renderedEntry").jsonObject
        val catalog = catalog(renderedEntry)
        val renderedAuthority = authority(renderedEntry)
        val prepared = catalog.prepare(profile(releaseEntry = renderedEntry), renderedAuthority)
        runBlocking { catalog.commit("customer", prepared) }
        val snapshot = requireNotNull(catalog.snapshot("customer"))
        val presentationStarted = CompletableDeferred<Unit>()
        val allowPresentation = CompletableDeferred<Unit>()
        val captures = CopyOnWriteArrayList<Pair<String, Map<String, Any?>>>()
        val presenter = RecordingJourneyPresenter(
            beforePresent = {
                presentationStarted.complete(Unit)
                allowPresentation.await()
            },
        )
        val service = JourneyService(
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

    @Test fun `unhandled host dismissal completes the active Journey`() = runBlocking {
        val identity = identity("customer")
        val renderedEntry = fixture.getValue("renderedEntry").jsonObject
        val catalog = catalog(renderedEntry)
        val renderedAuthority = authority(renderedEntry)
        val prepared = catalog.prepare(profile(releaseEntry = renderedEntry), renderedAuthority)
        runBlocking { catalog.commit("customer", prepared) }
        val snapshot = requireNotNull(catalog.snapshot("customer"))
        val captures = CopyOnWriteArrayList<Pair<String, Map<String, Any?>>>()
        val presenter = RecordingJourneyPresenter()
        val service = JourneyService(
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
        val journal = JourneyRunJournal(
            directory,
            "customer",
            JourneyStorageScope(renderedAuthority),
        )
        val active = journal.runs().single()
        journal.transition(active.id, "cursor_changed_after_show", active.context)

        requireNotNull(presenter.request).onOutcome(JourneySurfaceOutcome.DISMISSED)

        assertEquals(
            listOf(JourneyEventNames.LEG_STARTED, JourneyEventNames.LEG_COMPLETED),
            captures.map { it.first },
        )
        assertEquals("host_dismissed", captures.last().second["outcome"])
        assertTrue(journal.runs().isEmpty())
        assertEquals(
            "host_dismissed",
            journal.checkmark("experience_golden")?.outcome,
        )
    }

    @Test fun `screen lifecycle events publish with run identity and own user dismissal`() =
        runBlocking {
            val identity = identity("customer")
            val renderedEntry = fixture.getValue("renderedEntry").jsonObject
            val catalog = catalog(renderedEntry)
            val renderedAuthority = authority(renderedEntry)
            val prepared = catalog.prepare(profile(releaseEntry = renderedEntry), renderedAuthority)
            runBlocking { catalog.commit("customer", prepared) }
            val snapshot = requireNotNull(catalog.snapshot("customer"))
            val captures = CopyOnWriteArrayList<Pair<String, Map<String, Any?>>>()
            val presenter = RecordingJourneyPresenter()
            val service = JourneyService(
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
            val request = requireNotNull(presenter.request)

            assertTrue(request.onScreenChanged("screen_welcome"))
            assertEquals(
                JourneyScreenDismissalResult.COMPLETED,
                request.onScreenDismissed("screen_welcome", null, "user"),
            )

            assertEquals(
                listOf(
                    JourneyEventNames.LEG_STARTED,
                    SystemEventNames.SCREEN_SHOWN,
                    SystemEventNames.SCREEN_DISMISSED,
                    JourneyEventNames.LEG_COMPLETED,
                ),
                captures.map { it.first },
            )
            val shown = captures.single { it.first == SystemEventNames.SCREEN_SHOWN }.second
            val dismissed = captures.single {
                it.first == SystemEventNames.SCREEN_DISMISSED
            }.second
            for (properties in listOf(shown, dismissed)) {
                assertEquals("experience_golden", properties["experience_id"])
                assertEquals("version_golden", properties["experience_version_id"])
                assertEquals("screen_welcome", properties["screen_id"])
                assertEquals(0L, properties["leg_generation"])
                assertTrue((properties["journey_id"] as? String).orEmpty().isNotEmpty())
                assertEquals(
                    renderedEntry.getValue("locator").jsonObject
                        .getValue("legId").jsonPrimitive.content,
                    properties["leg_id"],
                )
            }
            assertEquals("user", dismissed["method"])
            assertEquals(
                "host_dismissed",
                captures.last().second["outcome"],
            )
        }

    @Test fun `presentation lifecycle callback cannot deadlock the blocked journey worker`() =
        runBlocking {
            val identity = identity("customer")
            val renderedEntry = fixture.getValue("renderedEntry").jsonObject
            val catalog = catalog(renderedEntry)
            val renderedAuthority = authority(renderedEntry)
            val prepared = catalog.prepare(profile(releaseEntry = renderedEntry), renderedAuthority)
            runBlocking { catalog.commit("customer", prepared) }
            val snapshot = requireNotNull(catalog.snapshot("customer"))
            val captures = CopyOnWriteArrayList<String>()
            val presenter = RecordingJourneyPresenter(
                beforePresent = { request ->
                    assertTrue(
                        scope.async {
                            request.onScreenChanged("screen_welcome")
                        }.await(),
                    )
                },
            )
            val service = JourneyService(
                identity = identity,
                events = store,
                catalog = catalog,
                journalDirectory = directory,
                scope = scope,
                capture = { name, _, _, _ -> captures.add(name) },
                presenter = presenter,
                nowMillis = { 100_000L },
            )
            service.initialize()
            service.onAppWillEnterForeground()

            withTimeout(5_000L) {
                service.profileDidCommit(snapshot, renderedAuthority, "customer", 1)
            }

            assertEquals(
                listOf(JourneyEventNames.LEG_STARTED, SystemEventNames.SCREEN_SHOWN),
                captures,
            )
        }

    @Test fun `presentation reveal publishes only exposures bound to that screen`() =
        runBlocking {
            val identity = identity("customer")
            val renderedEntry = fixture.getValue("renderedEntry").jsonObject
            val catalog = catalog(renderedEntry)
            val renderedAuthority = authority(renderedEntry)
            val prepared = catalog.prepare(profile(releaseEntry = renderedEntry), renderedAuthority)
            runBlocking { catalog.commit("customer", prepared) }
            val snapshot = requireNotNull(catalog.snapshot("customer"))
            val captures = CopyOnWriteArrayList<Pair<String, Map<String, Any?>>>()
            val presenter = RecordingJourneyPresenter()
            val service = JourneyService(
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
            val journal = JourneyRunJournal(
                directory,
                "customer",
                JourneyStorageScope(renderedAuthority),
            )
            val run = journal.runs().single()
            journal.transition(
                run.id,
                run.stepId,
                run.context,
                experimentExposure = JourneyRun.ExperimentExposure(
                    experimentId = "checkout",
                    variantId = "treatment",
                    isHoldout = true,
                    kind = JourneyRun.ExperimentExposure.Kind.ASSIGNED,
                    eventId = "exposure-1",
                    selectedAtMillis = 99_000L,
                ),
            )
            journal.preparePresentation(run.id, "screen_welcome")
            val request = requireNotNull(presenter.request)

            request.onPresentationRevealed("different_screen")
            assertTrue(captures.none { it.first == JourneyEventNames.EXPERIMENT_EXPOSURE })

            request.onPresentationRevealed("screen_welcome")
            request.onPresentationRevealed("screen_welcome")

            val exposures = captures.filter {
                it.first == JourneyEventNames.EXPERIMENT_EXPOSURE
            }
            assertEquals(1, exposures.size)
            assertEquals("checkout", exposures.single().second["experiment_key"])
            assertEquals("treatment", exposures.single().second["variant_key"])
            assertEquals("profile", exposures.single().second["assignment_source"])
            assertEquals(true, exposures.single().second["is_holdout"])
            val persisted = journal.runs().single().experimentExposures.single()
            assertEquals(100_000L, persisted.shownAtMillis)
            assertTrue(persisted.queued)
        }

    @Test fun `live rendered run retains artifacts across profile replacement until report`() =
        runBlocking {
            val identity = identity("customer")
            val renderedEntry = fixture.getValue("renderedEntry").jsonObject
            val catalog = catalog(renderedEntry)
            val renderedAuthority = authority(renderedEntry)
            val prepared = catalog.prepare(profile(releaseEntry = renderedEntry), renderedAuthority)
            runBlocking { catalog.commit("customer", prepared) }
            val snapshot = requireNotNull(catalog.snapshot("customer"))
            val manager = RecordingArtifactManager()
            val firstLeaseCloses = AtomicInteger()
            val secondLeaseCloses = AtomicInteger()
            val presenter = RecordingJourneyPresenter()
            val service = JourneyService(
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

            val journal = JourneyRunJournal(
                directory,
                "customer",
                JourneyStorageScope(renderedAuthority),
            )
            val run = journal.runs().single()
            assertEquals(setOf(ARTIFACT_DIGEST), run.artifactDigests)
            assertEquals(run.artifactDigests, manager.retainedRunDigests(journeyArtifactRunKey(run)))

            service.profileDidCommit(
                snapshot,
                renderedAuthority,
                "customer",
                2,
                preparedArtifacts(snapshot, secondLeaseCloses),
            )

            assertEquals(1, firstLeaseCloses.get())
            assertEquals(run.artifactDigests, manager.retainedRunDigests(journeyArtifactRunKey(run)))

            requireNotNull(presenter.request).onOutcome(JourneySurfaceOutcome.DISMISSED)

            assertNull(manager.retainedRunDigests(journeyArtifactRunKey(run)))
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
        val journal = JourneyRunJournal(
            directory,
            "customer",
            JourneyStorageScope(authority),
        )
        repeat(2) { index ->
            val run = requireNotNull(
                journal.admit(
                    arm,
                    JourneyFrequency.EveryMatch,
                    entryStepId,
                    100_000L + index,
                    release = releaseEntry,
                    executionSnapshot = executionSnapshot(snapshot),
                ),
            )
            journal.markStartedQueued(run)
            journal.park(run.id, entryStepId, 150_000L)
        }
        var authentications = 0
        val service = JourneyService(
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
            JourneyRunJournal(
                directory,
                "customer",
                JourneyStorageScope(authority),
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
            val journal = JourneyRunJournal(
                directory,
                "customer",
                JourneyStorageScope(authority),
            )
            val run = requireNotNull(
                journal.admit(
                    arm,
                    JourneyFrequency.EveryMatch,
                    entryStepId,
                    100_000L,
                    release = releaseEntry,
                    executionSnapshot = executionSnapshot(snapshot),
                ),
            )
            journal.markStartedQueued(run)
            journal.park(run.id, entryStepId, 150_000L)
            val authenticationStarted = CompletableDeferred<Unit>()
            val resumeAuthentication = CountDownLatch(1)
            val dispatches = AtomicInteger()
            val service = JourneyService(
                identity = identity,
                events = store,
                catalog = catalog,
                journalDirectory = directory,
                scope = scope,
                capture = { _, _, _, _ -> true },
                dispatcher = testDispatcher {
                    dispatches.incrementAndGet()
                    JourneyDispatchResult.Unsupported
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
                JourneyRunJournal(
                    directory,
                    "customer",
                    JourneyStorageScope(authority),
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
            val service = JourneyService(
                identity = identity,
                events = store,
                catalog = catalog,
                journalDirectory = directory,
                scope = scope,
                capture = { _, _, _, _ -> true },
                dispatcher = testDispatcher {
                    dispatches.incrementAndGet()
                    JourneyDispatchResult.Unsupported
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
            val journal = JourneyRunJournal(
                directory,
                "customer",
                JourneyStorageScope(authority),
            )
            val run = requireNotNull(
                journal.admit(
                    arm,
                    JourneyFrequency.EveryMatch,
                    entryStepId,
                    100_000L,
                    release = releaseEntry,
                    executionSnapshot = executionSnapshot(snapshot),
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

            val stillParked = JourneyRunJournal(
                directory,
                "customer",
                JourneyStorageScope(authority),
            ).runs().single { it.id == run.id }
            assertTrue(stillParked.park != null)
            assertEquals(0, dispatches.get())

            allowReplacementResume.complete(Unit)
            foreground.await()
            replacement.await()
        }

    @Test fun `warm journal reports finish before a queued startup event reaches admission`() = warmJournalOrdering("healthy")

    @Test fun `SQLite startup trigger retries under the replacing profile after failed warm recovery`() =
        integratedWarmRecovery(retryWithProfile = true)

    @Test fun `closing event pipelines after failed warm recovery preserves the SQLite trigger`() =
        integratedWarmRecovery(retryWithProfile = false)

    private fun integratedWarmRecovery(retryWithProfile: Boolean) = runBlocking {
        val identity = identity("customer")
        val catalog = catalog()
        val authenticated = authenticatedSnapshot(catalog)
        val snapshot = JourneyProfileCatalog.Snapshot(JourneyPlaneProfile.decode(profile(buildJsonObject {
            put("type", "event"); put("eventName", "inventory_opened")
        }).toString().encodeToByteArray()), authenticated.releasesByDigest)
        val journal = JourneyRunJournal(directory, "customer", JourneyStorageScope(authority))
        val retained = requireNotNull(journal.admit(snapshot.profile.armedLegs.single(), JourneyFrequency.EveryMatch,
            authenticated.releasesByDigest.values.single().leg.getValue("entryStepId").jsonPrimitive.content,
            1_000L, release = snapshot.profile.releases.single(), executionSnapshot = executionSnapshot(snapshot)))
        val eventLog = EventLog(store, NuxieContextBuilder(context, NuxieEnvironment.DEVELOPMENT, LogLevel.DEBUG, identity),
            identity, beforeSend = null, scope = scope, nowMillis = { 100_000L })
        val recovering = CompletableDeferred<Unit>()
        val releaseRecovery = CompletableDeferred<Unit>()
        val routed = CompletableDeferred<Unit>()
        val failFirst = java.util.concurrent.atomic.AtomicBoolean(true)
        val attempts = CopyOnWriteArrayList<Pair<Long, Boolean>>()
        var admissions = 0
        val service = JourneyService(identity, store, catalog, directory, scope,
            capture = { name, properties, eventId, distinctId ->
                if (eventId == retained.startedEventId && failFirst.compareAndSet(true, false)) {
                    recovering.complete(Unit)
                    releaseRecovery.await()
                    false
                } else eventLog.captureIdempotently(name, properties, eventId, distinctId)
            }, nowMillis = { 100_000L }, replayPendingLocalRoutes = eventLog::replayPendingLocalRoutes,
            beforeAdmission = {
                val persisted = store.pendingBatch(100).map { it.id }
                assertTrue(retained.startedEventId in persisted)
                assertTrue(retained.completedEventId in persisted)
                admissions++
            })
        eventLog.subscribeCommittedWithAdmission(sampleGeneration = service::eventAdmissionGeneration) { event, generation ->
            kotlinx.coroutines.coroutineScope {
                val result = async(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) { service.handleEvent(event, generation) }
                if (event.name == "inventory_opened") routed.complete(Unit)
                result.await().also { if (event.name == "inventory_opened") attempts += generation to it }
            }
        }
        try {
            service.profileDidCommit(snapshot, authority, "customer", 1)
            service.enqueueInitialization()
            withTimeout(5_000) { recovering.await() }
            eventLog.capture("inventory_opened")
            withTimeout(5_000) { routed.await() }
            assertEquals(0, admissions)
            assertEquals(1, store.queryPendingLocalRoutes("customer").count { it.name == "inventory_opened" })
            if (retryWithProfile) {
                val replacement = async(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
                    service.profileDidCommit(snapshot, authority, "customer", 2)
                }
                releaseRecovery.complete(Unit)
                withTimeout(5_000) { replacement.await(); eventLog.awaitBarrier() }
                assertEquals(listOf(1L to false, 2L to true), attempts)
                assertEquals(1, admissions)
                assertTrue(store.queryPendingLocalRoutes("customer").isEmpty())
                val persisted = store.pendingBatch(100)
                assertEquals(1, persisted.count { it.id == retained.startedEventId })
                assertEquals(1, persisted.count { it.id == retained.completedEventId })
            } else {
                val closing = async(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) { eventLog.closeWorkers() }
                assertTrue(!closing.isCompleted)
                releaseRecovery.complete(Unit)
                withTimeout(5_000) { closing.await() }
                assertEquals(listOf(1L to false), attempts)
                assertEquals(0, admissions)
                store.close()
                store = SQLiteEventStore(context, nowMillis = { 0L })
                val pending = store.queryPendingLocalRoutes("customer")
                assertEquals(listOf("inventory_opened"), pending.map { it.name })
                assertTrue(store.pendingBatch(100).none { it.id == retained.startedEventId || it.id == retained.completedEventId })
            }
        } finally {
            releaseRecovery.complete(Unit)
            withTimeout(5_000) { eventLog.closeWorkers() }
        }
    }

    @Test fun `live route deferred by revalidation retries when foreground opens`() = runBlocking {
        val contract = Json.parseToJsonElement(FixtureRunner.fixturesRoot()
            .resolve("sdk/android-foreground-admission.json").readText()).jsonObject
            .getValue("deferredLiveRoute").jsonObject
        val identity = identity("customer")
        val catalog = catalog()
        val authenticated = authenticatedSnapshot(catalog)
        val snapshot = JourneyProfileCatalog.Snapshot(JourneyPlaneProfile.decode(profile(buildJsonObject {
            put("type", "event"); put("eventName", "inventory_opened")
        }).toString().encodeToByteArray()), authenticated.releasesByDigest)
        val eventLog = EventLog(store, NuxieContextBuilder(context, NuxieEnvironment.DEVELOPMENT, LogLevel.DEBUG, identity),
            identity, beforeSend = null, scope = scope, nowMillis = { 100_000L })
        val deferred = CompletableDeferred<Unit>()
        val returnDeferred = CompletableDeferred<Unit>()
        val admissions = AtomicInteger()
        val service = JourneyService(identity, store, catalog, directory, scope,
            capture = { name, properties, eventId, customer ->
                eventLog.captureIdempotently(name, properties, eventId, customer)
            }, nowMillis = { 100_000L }, replayPendingLocalRoutes = eventLog::replayPendingLocalRoutes,
            beforeAdmission = { admissions.incrementAndGet() })
        eventLog.subscribeCommittedWithAdmission(sampleGeneration = service::eventAdmissionGeneration) { event, generation ->
            val accepted = service.handleEvent(event, generation)
            if (event.name == "inventory_opened" && !accepted && !deferred.isCompleted) {
                deferred.complete(Unit)
                returnDeferred.await()
            }
            accepted
        }
        try {
            service.onAppVisibilityChanged(true)
            val token = checkNotNull(service.foregroundRevalidationToken())
            service.initialize()
            service.profileDidCommit(snapshot, authority, "customer", 1)
            eventLog.capture("inventory_opened")
            withTimeout(5_000) { deferred.await() }
            assertEquals(0, admissions.get())
            // Reconciliation excludes the route still owned by EventLog's live worker.
            service.completeForegroundRevalidation(token)
            returnDeferred.complete(Unit)
            withTimeout(5_000) { eventLog.awaitBarrier() }
            assertEquals(contract.getValue("expectedNewRuns").jsonPrimitive.int, admissions.get())
            assertEquals(contract.getValue("expectedPendingLocalRoutes").jsonPrimitive.int,
                store.queryPendingLocalRoutes("customer").size)
        } finally {
            returnDeferred.complete(Unit)
            withTimeout(5_000) { eventLog.closeWorkers() }
        }
    }

    @Test fun `failed warm capture blocks admission until authenticated reconciliation retries`() = warmJournalOrdering("capture")

    @Test fun `failed recovered route blocks admission until authenticated reconciliation retries`() = warmJournalOrdering("replay")

    @Test fun `failed recovered presentation blocks admission until authenticated reconciliation retries`() = warmJournalOrdering("presentation")

    private fun warmJournalOrdering(failureMode: String) = runBlocking {
        val contract = Json.parseToJsonElement(FixtureRunner.fixturesRoot()
            .resolve("sdk/warm-start-recovery.json").readText()).jsonObject
        val identity = identity("customer")
        val catalog = catalog()
        val authenticated = authenticatedSnapshot(catalog)
        val snapshot = JourneyProfileCatalog.Snapshot(
            JourneyPlaneProfile.decode(profile(buildJsonObject {
                put("type", "event"); put("eventName", "inventory_opened")
            }).toString().encodeToByteArray()), authenticated.releasesByDigest)
        val journal = JourneyRunJournal(directory, "customer", JourneyStorageScope(authority))
        val retained = requireNotNull(journal.admit(snapshot.profile.armedLegs.single(), JourneyFrequency.EveryMatch,
            authenticated.releasesByDigest.values.single().leg.getValue("entryStepId").jsonPrimitive.content,
            1_000L, release = snapshot.profile.releases.single(), executionSnapshot = executionSnapshot(snapshot)))
        if (failureMode == "presentation") {
            val publication = JourneyRun.PendingPresentationPublication("retained-publication", 0, 1,
                "retained-screen", "retained-action",
                items = listOf(JourneyRun.PendingPresentationPublication.Item("retained-event", JsonObject(emptyMap()),
                    "retained-event-id", 1_000L)))
            assertTrue(journal.stagePresentationPublication(retained.id, retained.stepId, retained.context, publication) != null)
        }
        val recovering = CompletableDeferred<Unit>()
        val releaseRecovery = CompletableDeferred<Unit>()
        val order = CopyOnWriteArrayList<String>()
        var fail = failureMode != "healthy"
        val service = JourneyService(identity, store, catalog, directory, scope,
            capture = { _, _, eventId, _ ->
                if (eventId == retained.startedEventId) { recovering.complete(Unit); releaseRecovery.await() }
                order += if (eventId == retained.startedEventId) "started" else if (eventId == retained.completedEventId) "completed" else eventId
                !(fail && failureMode == "capture")
            }, nowMillis = { 100_000L }, beforeAdmission = { order += "fresh-admission" },
            captureScreenEvent = { _, _, _, _, _, _, _ ->
                recovering.complete(Unit)
                releaseRecovery.await()
                order += "presentation"
                StableEventCaptureResult(!fail, null)
            },
            replayPendingLocalRoutes = { !(fail && failureMode == "replay") })
        fun expected(key: String) = contract.getValue(key).jsonArray.map { it.jsonPrimitive.content }
        service.profileDidCommit(snapshot, authority, "customer", 1)
        service.enqueueInitialization()
        try {
            withTimeout(5_000) { recovering.await() }
            val pending = async { service.handleEvent(StoredEvent("startup-event", "inventory_opened",
                timestampMillis = 100_000L, distinctId = "customer"), service.eventAdmissionGeneration()) }
            kotlinx.coroutines.yield()
            assertTrue(order.isEmpty())
            assertTrue(!pending.isCompleted)
            releaseRecovery.complete(Unit)
            val admitted = withTimeout(5_000) { pending.await() }
            assertEquals(failureMode == "healthy", admitted)
            assertEquals(expected(if (fail) "${failureMode}FailureOrder" else "healthyOrder"), order)
            if (fail) {
                fail = false
                service.profileDidCommit(snapshot, authority, "customer", 2)
                assertTrue(service.handleEvent(StoredEvent("retry-event", "inventory_opened",
                    timestampMillis = 100_000L, distinctId = "customer"), service.eventAdmissionGeneration()))
                assertEquals(expected("${failureMode}RetryOrder"), order)
            }
        } finally { releaseRecovery.complete(Unit) }
    }

    @Test fun `queued initialization preserves an admitted startup event`() = runBlocking {
        val identity = identity("customer")
        val catalog = catalog()
        val authenticated = authenticatedSnapshot(catalog)
        val snapshot = JourneyProfileCatalog.Snapshot(
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
        val service = JourneyService(
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
        val service = JourneyService(
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
            JourneyRunJournal(
                directory,
                "customer",
                JourneyStorageScope(authority),
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
            val service = JourneyService(
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
                JourneyRunJournal(
                    directory,
                    "customer",
                    JourneyStorageScope(authority),
                ).runs().isEmpty(),
            )
        }

    @Test fun `newer profile publication fences delayed commits and clears`() = runBlocking {
        val identity = identity("customer")
        val catalog = catalog()
        val snapshot = authenticatedSnapshot(catalog)
        val captures = CopyOnWriteArrayList<String>()
        val service = JourneyService(
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
        runBlocking { catalog.commit("customer", prepared) }
        val snapshot = requireNotNull(catalog.snapshot("customer"))
        val dispatchStarted = CompletableDeferred<JourneyDispatchRequest>()
        val resumeDispatch = CompletableDeferred<Unit>()
        val effectPublications = AtomicInteger()
        val dispatcher = testDispatcher { request ->
            dispatchStarted.complete(request)
            resumeDispatch.await()
            val published = request.executionFence.performIfCurrent(
                request.executionFenceToken,
            ) {
                effectPublications.incrementAndGet()
            }
            if (published == null) {
                JourneyDispatchResult.Failed
            } else {
                JourneyDispatchResult.Unsupported
            }
        }
        val service = JourneyService(
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
        runBlocking { catalog.commit("customer", prepared) }
        val snapshot = requireNotNull(catalog.snapshot("customer"))
        val dispatchStarted = CompletableDeferred<JourneyDispatchRequest>()
        val resumeDispatch = CompletableDeferred<Unit>()
        val effectPublications = AtomicInteger()
        val dispatcher = testDispatcher { request ->
            dispatchStarted.complete(request)
            resumeDispatch.await()
            val published = request.executionFence.performIfCurrent(
                request.executionFenceToken,
            ) {
                effectPublications.incrementAndGet()
            }
            if (published == null) {
                JourneyDispatchResult.Failed
            } else {
                JourneyDispatchResult.Unsupported
            }
        }
        val service = JourneyService(
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

    @Test fun `opened links capture the shared attributed run record`() = runBlocking {
        val fixture = Json.parseToJsonElement(ai.nuxie.sdk.fixtures.FixtureRunner.fixturesRoot()
            .resolve("events/runtime-link-opened.json").readText()).jsonObject.getValue("record").jsonObject
        val expected = fixture.getValue("properties").jsonObject
        val identity = IdentityService(context).also { it.setDistinctId("customer") }
        val base = dispatchRequest(identity, JsonObject(emptyMap()))
        val request = base.copy(run = base.run.copy(journeyId = "journey", generation = 2,
            reference = buildJsonObject { put("experienceId", "experience"); put("versionId", "version"); put("legId", "leg") }))
        val captures = mutableListOf<Pair<String, JsonObject>>()
        val dispatcher = JourneyEffectDispatcher(identity = identity,
            capture = { name, properties, _, _, admission, _ -> admission.commitIfCurrent {
                captures += name to ai.nuxie.sdk.events.JsonValueConverter.fromMap(properties)
                true
            } == true }, deliverAppAction = { _, _ -> error("Unexpected app action") })
        assertTrue(dispatcher.captureLinkOpened(ai.nuxie.sdk.presentation.JourneyOpenedLink(
            expected.getValue("url").jsonPrimitive.content, expected.getValue("target").jsonPrimitive.content,
            "screen", "second", destination = expected.getValue("destination").jsonPrimitive.content), request))
        assertEquals(listOf(fixture.getValue("name").jsonPrimitive.content to expected), captures)
        val activity = ai.nuxie.sdk.events.ActivityCuration.activity(captures.single().first, captures.single().second)
        assertTrue(activity is ai.nuxie.sdk.NuxieActivity.LinkOpened)
        assertEquals("in_app", (activity as ai.nuxie.sdk.NuxieActivity.LinkOpened).destination)
        identity.setDistinctId("replacement")
        assertFalse(dispatcher.captureLinkOpened(ai.nuxie.sdk.presentation.JourneyOpenedLink("https://example.test", "_self", "screen", destination = "in_app"), request))
        assertEquals(1, captures.size)
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
        val dispatcher = JourneyEffectDispatcher(
            identity = identity,
            capture = { _, _, _, _, _, _ -> error("revoked app action must not capture its rider") },
            deliverAppAction = { action, publishIfCurrent ->
                identity.setDistinctId("replacement")
                publishIfCurrent { delivered += action.name }
            },
        )

        assertEquals(JourneyDispatchResult.Failed, dispatcher.dispatch(request))
        assertTrue(delivered.isEmpty())
    }

    @Test fun `authored event effect carries exact execution origin`() = runBlocking {
        val identity = IdentityService(context).also { it.setDistinctId("customer") }
        val request = dispatchRequest(identity, buildJsonObject {
            put("type", "send_event"); put("eventName", "finished")
        })
        var captured: ai.nuxie.sdk.events.JourneyEventOrigin? = null
        val dispatcher = JourneyEffectDispatcher(identity = identity,
            capture = { _, _, _, _, admission, origin ->
                admission.commitIfCurrent { captured = origin; true } == true
            }, deliverAppAction = { _, _ -> error("Unexpected app action") })
        assertEquals(JourneyDispatchResult.Outlet("next"), dispatcher.dispatch(request))
        assertEquals(request.run.journeyId, captured?.journeyId)
        assertEquals(request.run.reference.getValue("experienceId").jsonPrimitive.content, captured?.experienceId)
        assertEquals(request.run.reference.getValue("versionId").jsonPrimitive.content, captured?.versionId)
        assertEquals(request.run.reference.getValue("legId").jsonPrimitive.content, captured?.legId)
        assertEquals(request.run.generation, captured?.generation)
        assertEquals(request.stepId, captured?.stepId)
        assertEquals(request.effectId, captured?.occurrenceId)
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
        val dispatcher = JourneyEffectDispatcher(
            identity = identity,
            capture = { _, _, _, _, admission, _ ->
                identity.setDistinctId("replacement")
                admission.commitIfCurrent {
                    commits += 1
                    true
                } != null
            },
            deliverAppAction = { _, _ -> error("send_event must not publish an app action") },
        )

        assertEquals(JourneyDispatchResult.Failed, dispatcher.dispatch(request))
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
        val dispatcher = JourneyEffectDispatcher(
            identity = identity,
            capture = { name, properties, _, _, admission, _ ->
                admission.commitIfCurrent {
                    captures += name to properties
                    true
                } != null
            },
            deliverAppAction = { action, publishIfCurrent ->
                publishIfCurrent { delivered += action.name }
            },
        )

        assertEquals(JourneyDispatchResult.Outlet("next"), dispatcher.dispatch(request))
        assertEquals(listOf("open_inventory"), delivered)
        assertEquals(listOf("\$app_action_requested"), captures.map { it.first })
        assertEquals(request.run.journeyId, captures.single().second["journey_id"])
        assertEquals(request.run.generation, captures.single().second["leg_generation"])
    }

    @Test fun `app action handler that resets the SDK returns and fails the step`() {
        Nuxie.overridesForTesting = NuxieCore.Overrides(
            transport = FakeTransport(),
            registerLifecycle = false,
            requestInitialProfileRefresh = false,
            billingClientFactory = InertBillingClientAdapter.factory,
            eventDatabaseFile = File(directory, "app-action-reset-events.db"),
        )
        Nuxie.setup(context, NuxieConfiguration("pk_test_app_action_reset"))
        val callbackReturned = CountDownLatch(1)
        val listener = NuxieListener { sdk, action ->
            assertEquals("sign_out", action.name)
            sdk.reset()
            callbackReturned.countDown()
        }
        try {
            val core = requireNotNull(Nuxie.core)
            Nuxie.identify("customer")
            Nuxie.listener = listener
            val request = dispatchRequest(
                core.identity,
                buildJsonObject {
                    put("type", "app_action")
                    put("name", "sign_out")
                },
            )
            // Production wiring: the SDK identity, fenced capture, and the
            // facade's delivery, which calls the listener on Main under the fences.
            val dispatcher = JourneyEffectDispatcher(
                identity = core.identity,
                capture = core.eventLog::captureIdempotentlyIfCurrent,
                deliverAppAction = Nuxie::deliverAppAction,
            )
            // Robolectric's Main is this test thread. Interrupt a parked handler
            // so the case fails instead of hanging the suite.
            val main = Thread.currentThread()
            val settled = CountDownLatch(1)
            val watchdog = thread(isDaemon = true, name = "app-action-watchdog") {
                if (!settled.await(5, TimeUnit.SECONDS)) main.interrupt()
            }
            val result = try {
                runBlocking { dispatcher.dispatch(request) }
            } catch (parked: InterruptedException) {
                throw AssertionError("An App Action handler that calls sdk.reset() must return within 5 s", parked)
            } finally {
                settled.countDown()
                watchdog.join()
                Thread.interrupted()
            }

            assertEquals("the handler returned", 0L, callbackReturned.count)
            assertNotEquals("customer", Nuxie.distinctId)
            assertFalse(Nuxie.isIdentified)
            // The reset moved the identity the step was admitted under, so the
            // step fails instead of advancing, as at base and on iOS.
            assertEquals(JourneyDispatchResult.Failed, result)

            Nuxie.listener = null
            val identified = CountDownLatch(1)
            thread(isDaemon = true, name = "later-identify") {
                Nuxie.identify("customer-2")
                identified.countDown()
            }
            assertTrue(
                "A later identify from another thread must finish within 5 s",
                identified.await(5, TimeUnit.SECONDS),
            )
            assertEquals("customer-2", Nuxie.distinctId)
        } finally {
            Nuxie.listener = null
            Nuxie.resetForTesting()
            Nuxie.overridesForTesting = null
        }
    }

    @Test fun `posted app action handler can reset the SDK`() = postedIdentityAction(reset = true)

    @Test fun `posted app action handler can identify the SDK`() = postedIdentityAction(reset = false)

    private fun postedIdentityAction(reset: Boolean) {
        Nuxie.overridesForTesting = NuxieCore.Overrides(
            transport = FakeTransport(),
            registerLifecycle = false,
            requestInitialProfileRefresh = false,
            billingClientFactory = InertBillingClientAdapter.factory,
            eventDatabaseFile = File(directory, "posted-identity-events.db"),
        )
        Nuxie.setup(context, NuxieConfiguration("pk_test_posted_identity"))
        val calls = AtomicInteger()
        val result = java.util.concurrent.atomic.AtomicReference<JourneyDispatchResult?>()
        val failure = java.util.concurrent.atomic.AtomicReference<Throwable?>()
        val finished = CountDownLatch(1)
        val main = Thread.currentThread()
        var dispatchThread: Thread? = null
        var watchdog: Thread? = null
        try {
            val core = requireNotNull(Nuxie.core)
            Nuxie.identify("customer")
            val originalSession = core.sessions.getSessionId(readOnly = true)
            Nuxie.listener = NuxieListener { sdk, action ->
                assertEquals("switch_customer", action.name)
                assertEquals(main, Thread.currentThread())
                calls.incrementAndGet()
                if (reset) sdk.reset() else sdk.identify("replacement")
            }
            val request = dispatchRequest(core.identity, buildJsonObject {
                put("type", "app_action")
                put("name", "switch_customer")
            })
            val dispatcher = JourneyEffectDispatcher(
                identity = core.identity,
                capture = core.eventLog::captureIdempotentlyIfCurrent,
                deliverAppAction = Nuxie::deliverAppAction,
            )
            // Starting off Main forces deliverAppAction through its posted
            // Runnable, rather than the inline path covered above.
            val looper = org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper())
            looper.idle()
            dispatchThread = thread(isDaemon = true, name = "posted-app-action") {
                try {
                    assertNotEquals(android.os.Looper.getMainLooper(), android.os.Looper.myLooper())
                    result.set(runBlocking {
                        val dispatched = dispatcher.dispatch(request)
                        // Draining capture can deliver activity to Main too.
                        // Keep the test thread pumping its looper until this
                        // worker has checked the durable identify consequence.
                        if (!reset) {
                            core.eventLog.awaitBarrier()
                            core.userTransitions.drain()
                            // Upload completion must not erase the durable-event oracle.
                            core.delivery.flushAll()
                            val session = requireNotNull(core.sessions.getSessionId(readOnly = true))
                            val identify = core.store.querySessionEvents(session).single {
                                it.name == "\$identify" && it.properties["distinct_id"] == JsonPrimitive("replacement")
                            }
                            assertEquals("replacement", identify.distinctId)
                        }
                        dispatched
                    })
                } catch (error: Throwable) {
                    failure.set(error)
                } finally {
                    finished.countDown()
                }
            }
            assertEquals("handler cannot run before Main consumes the Runnable", 0, calls.get())
            watchdog = thread(isDaemon = true, name = "posted-identity-watchdog") {
                if (!finished.await(5, TimeUnit.SECONDS)) main.interrupt()
            }
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            do {
                looper.idle()
            } while (!finished.await(10, TimeUnit.MILLISECONDS) && System.nanoTime() < deadline)
            assertEquals("posted App Action must settle", 0L, finished.count)
            assertNull(failure.get())
            assertEquals(1, calls.get())
            assertEquals(JourneyDispatchResult.Failed, result.get())
            if (reset) {
                assertFalse(Nuxie.isIdentified)
                assertNotEquals("customer", Nuxie.distinctId)
            } else {
                assertTrue(Nuxie.isIdentified)
                assertEquals("replacement", Nuxie.distinctId)
                assertNotEquals(originalSession, core.sessions.getSessionId(readOnly = true))
            }
        } finally {
            finished.countDown()
            dispatchThread?.interrupt()
            dispatchThread?.join(5_000)
            watchdog?.join(5_000)
            Thread.interrupted()
            Nuxie.listener = null
            Nuxie.resetForTesting()
            Nuxie.overridesForTesting = null
        }
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Config(shadows = [LinkStateRenderCapabilityShadow::class, LinkStateNativeMountShadow::class],
        instrumentedPackages = ["ai.nuxie.sdk.presentation.NuxieExperienceActivity", "ai.nuxie.sdk.presentation.AndroidRenderCapability"])
    @Test fun `shared link states and same frame completion use real Journey and Activity paths`() = sharedLinkStates()

    @Config(shadows = [LinkStateRenderCapabilityShadow::class, LinkStateNativeMountShadow::class],
        instrumentedPackages = ["ai.nuxie.sdk.presentation.NuxieExperienceActivity", "ai.nuxie.sdk.presentation.AndroidRenderCapability"])
    @Test fun `profile clear routes runtime link externally before shutdown`() =
        sharedLinkStates("profile-clear-runtime-before-shutdown")

    @Config(shadows = [LinkStateRenderCapabilityShadow::class, LinkStateNativeMountShadow::class],
        instrumentedPackages = ["ai.nuxie.sdk.presentation.NuxieExperienceActivity", "ai.nuxie.sdk.presentation.AndroidRenderCapability"])
    @Test fun `identity roundtrip routes runtime link externally before shutdown`() =
        sharedLinkStates("identity-roundtrip-runtime-before-shutdown")

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private fun sharedLinkStates(onlyName: String? = null) = runTest {
        Dispatchers.setMain(kotlinx.coroutines.test.StandardTestDispatcher(testScheduler))
        val vectors = Json.parseToJsonElement(FixtureRunner.fixturesRoot().resolve("events/link-open-states.json").readText())
            .jsonObject.getValue("cases").jsonArray + Json.parseToJsonElement("""{
                "name":"runtime-complete","state":"settled","complete":true,
                "link":{"kind":"runtime","url":{"type":"String","value":"https://example.test/path"},"target":"_self","canOpen":true},
                "expected":{"destination":"in_app","opened":true,"recorded":true}}
            """)
        if (onlyName != null) require(vectors.any { it.jsonObject["name"] == JsonPrimitive(onlyName) })
        val app = RuntimeEnvironment.getApplication()
        val lifecycle = ai.nuxie.sdk.core.NuxieLifecycleCoordinator(
            ai.nuxie.sdk.core.AppLifecycleTracker(app.getSharedPreferences("link-state-lifecycle", 0), { "1" }, { 100_000L }, { _, _ -> }),
            ai.nuxie.sdk.session.SessionService { 100_000L }, backgroundScope)
        app.registerActivityLifecycleCallbacks(lifecycle)
        try {
            for (item in vectors) {
                val vector = item.jsonObject
                val name = vector.getValue("name").jsonPrimitive.content
                if (onlyName != null && name != onlyName) continue
                val state = vector.getValue("state").jsonPrimitive.content
                val link = vector.getValue("link").jsonObject
                val expected = vector.getValue("expected").jsonObject
                val journeyLink = link.getValue("kind").jsonPrimitive.content == "journey"
                if (journeyLink && !expected.getValue("opened").jsonPrimitive.boolean) continue
                val screenless = state == "screenless"
                val completes = vector["complete"] == JsonPrimitive(true)
                val host = org.robolectric.Robolectric.buildActivity(android.app.Activity::class.java).setup().visible()
                var controller: org.robolectric.android.controller.ActivityController<NuxieExperienceActivity>? = null
                var dialog: android.app.Dialog? = null
                var pendingClose: kotlinx.coroutines.Deferred<Unit>? = null
                val allowIdentityShutdown = CompletableDeferred<Unit>()
                val beforeShutdown = vector["beforeShutdown"] == JsonPrimitive(true)
                var profileShutdownEntered = false
                var retirementCloseReason: CloseReason? = null
                var handoffCount = 0
                var handoffWhileResumed = false
                var handoffBeforeShutdown = false
                val identity = IdentityService(app).also { it.setDistinctId("customer") }
                val releaseEntry = fixture.getValue("renderedEntry").jsonObject
                val catalog = catalog(releaseEntry)
                val authority = authority(releaseEntry)
                catalog.commit("customer", catalog.prepare(profile(releaseEntry = releaseEntry), authority))
                val baseline = requireNotNull(catalog.snapshot("customer"))
                val original = baseline.releasesByDigest.values.single()
                val leg = JsonObject(original.leg + mapOf(
                    "entryStepId" to JsonPrimitive(if (screenless) "link" else "present"),
                    "screens" to if (screenless) JsonArray(emptyList()) else original.leg.getValue("screens"),
                    "routes" to if (screenless) JsonArray(emptyList()) else Json.parseToJsonElement("""[{"host":{"kind":"screen","screenId":"screen_welcome"},"eventName":"table_link","entryStepId":"${if (journeyLink) "link" else if (completes) "done" else "wait"}"}]"""),
                    "steps" to JsonArray(listOf(
                        Json.parseToJsonElement("""{"kind":"action","id":"present","action":{"type":"navigate","screenId":"screen_welcome"},"outlets":{}}"""),
                        buildJsonObject { put("kind", "action"); put("id", "link"); putJsonObject("action") { put("type", "open_link"); put("url", link.getValue("url")); put("target", link["target"] ?: JsonPrimitive("")) }; putJsonObject("outlets") { put("next", "done") } },
                        Json.parseToJsonElement("""{"kind":"action","id":"wait","action":{"type":"delay","durationMs":60000},"outlets":{}}"""),
                        Json.parseToJsonElement("""{"kind":"complete","id":"done","outcome":"completed"}"""))
                        .filter { (!screenless || it.jsonObject["id"] != JsonPrimitive("present")) && (journeyLink || it.jsonObject["id"] != JsonPrimitive("link")) })))
                val envelope = JourneyReleaseEnvelope.authenticate(releaseEntry.getValue("envelope").toString().encodeToByteArray(),
                    mapOf("TEST_ONLY_DEV_KEYPAIR" to Base64.decode(fixture.getValue("publicKeyBase64").jsonPrimitive.content, Base64.NO_WRAP)))
                val descriptor = JsonObject(original.descriptor + mapOf("leg" to leg) + if (screenless) mapOf(
                    "render" to kotlinx.serialization.json.JsonNull, "requirements" to kotlinx.serialization.json.JsonNull,
                    "screenBehaviors" to JsonArray(emptyList())) else emptyMap())
                ai.nuxie.sdk.experiences.JourneySchemaValidator.validate(descriptor)
                val release = AuthenticatedJourneyRelease(envelope, original.identity, descriptor, original.publishedAtSeqToPromote)
                val snapshot = baseline.copy(releasesByDigest = mapOf(release.descriptorSha256 to release))
                val captures = mutableListOf<Triple<String, Map<String, Any?>, String>>()
                val recorder = JourneyEffectDispatcher(identity, capture = { event, props, id, _, admission, _ ->
                    admission.commitIfCurrent { captures += Triple(event, props, id); true } == true }, deliverAppAction = { _, _ -> false })
                val launched = mutableListOf<String>()
                val presentations = ExperiencePresentationService(currentDistinctId = identity::distinctId, scope = backgroundScope, emit = { _, _, _ -> }, runtimeAvailable = { true },
                    launch = { id ->
                        launched += id; host.pause()
                        controller = org.robolectric.Robolectric.buildActivity(NuxieExperienceActivity::class.java,
                            android.content.Intent(app, NuxieExperienceActivity::class.java).putExtra(NuxieExperienceActivity.EXTRA_PRESENTATION_ID, id)).setup().visible()
                    }, foregroundActivity = lifecycle::resumedActivity, isAppForeground = lifecycle::isAppForeground,
                    openLink = { route, activity ->
                        if (!link.getValue("canOpen").jsonPrimitive.boolean) throw android.content.ActivityNotFoundException()
                        handoffCount++
                        handoffWhileResumed = lifecycle.resumedActivity() === controller?.get()
                        handoffBeforeShutdown = controller?.get()?.isFinishing == false &&
                            launched.singleOrNull()?.let(PresentationRegistry::currentScreen)?.screenCloseReason() == null
                        val opened = openActivityLink(app, route, activity)
                        if (vector["retirement"] == JsonPrimitive("identity_change") || beforeShutdown) {
                            // The route is chosen before queued shutdown starts, while the Activity stays resumed.
                            allowIdentityShutdown.complete(Unit)
                            runCurrent()
                        }
                        opened
                    })
                lateinit var journeys: JourneyService
                val transitions = ai.nuxie.sdk.identity.UserTransitionCoordinator(store, backgroundScope)
                transitions.addObserver { _, from, _ ->
                    allowIdentityShutdown.await()
                    presentations.shutdownOwnedBy(from)
                }
                transitions.addObserver { _, from, to -> journeys.handleUserChange(from, to) }
                var currentRequest: JourneyPresentationRequest? = null
                var changedState = false
                suspend fun changeState() {
                    if (changedState || screenless) return
                    changedState = true
                    val activity = requireNotNull(controller).get()
                    when (state) {
                        "sheet_active" -> dialog = android.app.Dialog(activity).also { it.setContentView(android.view.View(activity)); it.show() }
                        "button_dismissing" -> org.robolectric.util.ReflectionHelpers.callInstanceMethod<Void>(activity, "finishTerminal",
                            org.robolectric.util.ReflectionHelpers.ClassParameter.from(CloseReason::class.java, CloseReason.UserDismissed))
                        "swipe_dismissing" -> activity.onBackPressed()
                        "paused_foreground" -> controller!!.pause()
                        "host_dismissed" -> presentations.dismiss(CloseReason.HostDismissed)
                        "owner_retired", "presentation_finished" -> {
                            val retirement = if (state == "owner_retired") vector.getValue("retirement").jsonPrimitive.content else null
                            if (retirement == "identity_change" || retirement == "identity_roundtrip") {
                                identity.setDistinctId("replacement-owner")
                                if (retirement == "identity_roundtrip") identity.setDistinctId("customer")
                            }
                            val close = async {
                                when (retirement) {
                                    null -> presentations.shutdownJourney("customer", requireNotNull(currentRequest).journeyId)
                                    "identity_change", "identity_roundtrip" -> {
                                        transitions.enqueue(ai.nuxie.sdk.identity.UserTransitionCoordinator.Transition(
                                            ai.nuxie.sdk.identity.UserTransitionCoordinator.Kind.IDENTIFY,
                                            "customer", "replacement-owner", migrateEvents = false))
                                        if (retirement == "identity_roundtrip") {
                                            transitions.enqueue(ai.nuxie.sdk.identity.UserTransitionCoordinator.Transition(
                                                ai.nuxie.sdk.identity.UserTransitionCoordinator.Kind.IDENTIFY,
                                                "replacement-owner", "customer", migrateEvents = false))
                                        }
                                        transitions.drain()
                                    }
                                    "profile_clear" -> journeys.profileDidClear("customer", 2)
                                    else -> error("unknown retirement $retirement")
                                }
                            }
                            runCurrent()
                            retirementCloseReason = PresentationRegistry.currentScreen(launched.single())?.screenCloseReason()
                            if (retirement == "profile_clear" && beforeShutdown) {
                                assertTrue("$name must reach shutdown after revoking execution", profileShutdownEntered)
                            }
                            pendingClose = close
                        }
                        "background" -> { controller!!.pause().stop(); host.stop() }
                    }
                }
                val presenter = object : JourneyPresenting {
                    override suspend fun openLink(owner: JourneyPresentationOwner, link: JourneyLinkRequest): ai.nuxie.sdk.presentation.JourneyOpenedLink? {
                        changeState(); return presentations.openJourneyLink(owner, link)
                    }
                    override fun reserve(ownerDistinctId: String) = presentations.reserveJourney(ownerDistinctId)
                    override fun owns(owner: JourneyPresentationOwner) = presentations.ownsJourney(owner)
                    override fun screenId(owner: JourneyPresentationOwner) = presentations.journeyScreenId(owner)
                    override suspend fun present(request: JourneyPresentationRequest): JourneyPresentationResult {
                        currentRequest = request
                        val file = File(directory, "link-scene").apply { writeBytes(byteArrayOf(1)) }
                        presentations.presentJourney(request.fences, request.release, request.screenId, request.journeyId, request.ownerDistinctId, request.reservation,
                            acquire = { AcquiredJourneyRelease(identity = request.release.identity, artifactsByKey = mapOf("renders/main.riv" to file), sceneFile = file, protection = Closeable {}) },
                            onScreenChanged = request.onScreenChanged, onScreenDismissed = request.onScreenDismissed,
                            onPresentationRevealed = request.onPresentationRevealed, onLinkOpened = request.onLinkOpened,
                            onEmissionBatch = { batch, sources -> if (!journeyLink) changeState(); request.onEmissionBatch(batch, sources) }, onOutcome = request.onOutcome)
                        return JourneyPresentationResult.Shown
                    }
                    override suspend fun shutdownPresentation(ownerDistinctId: String, journeyId: String) { presentations.shutdownJourney(ownerDistinctId, journeyId) }
                    override suspend fun shutdownOwnedBy(ownerDistinctId: String) {
                        if (beforeShutdown) {
                            profileShutdownEntered = true
                            allowIdentityShutdown.await()
                        }
                        presentations.shutdownOwnedBy(ownerDistinctId)
                    }
                }
                currentRequest = null
                val journeyScope = CoroutineScope(backgroundScope.coroutineContext + SupervisorJob(backgroundScope.coroutineContext[kotlinx.coroutines.Job]))
                journeys = JourneyService(identity, store, catalog, File(directory, name).apply { mkdirs() }, journeyScope,
                    capture = { event, props, id, _ -> captures += Triple(event, props, id); true }, presenter = presenter, linkRecorder = recorder, nowMillis = { 100_000L })
                val admission = async { journeys.initialize(); journeys.onAppWillEnterForeground(); journeys.profileDidCommit(snapshot, authority, "customer", 1) }
                runCurrent()
                if (!screenless) {
                    for (attempt in 0 until 200) {
                        runCurrent()
                        if (launched.isNotEmpty()) break
                        Thread.sleep(10)
                    }
                    assertTrue("$name must launch its real Activity", launched.isNotEmpty())
                    org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
                    val screen = requireNotNull(PresentationRegistry.currentScreen(launched.single()))
                    org.robolectric.util.ReflectionHelpers.callInstanceMethod<Void>(screen, "onFirstFrame")
                    runCurrent(); org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
                    admission.await()
                    val runtimeEvents = mutableListOf(ai.nuxie.sdk.runtime.NuxieRuntimeEvent(0, 128, "table_link", "", "", 0f, emptyList()))
                    if (!journeyLink) runtimeEvents += ai.nuxie.sdk.runtime.NuxieRuntimeEvent(1, 131, "", link.getValue("url").jsonObject.getValue("value").jsonPrimitive.content,
                        link["target"]?.jsonPrimitive?.contentOrNull ?: "", 0f, emptyList())
                    PresentationRegistry.reportRuntimeStep(launched.single(), NuxiePlayerStepOutcome(true, emptyList(), runtimeEvents, emptyList(), emptyList()), 1uL, null)
                    runCurrent()
                } else { admission.await(); runCurrent() }
                if (expected.getValue("opened").jsonPrimitive.boolean) {
                    for (attempt in 0 until 200) {
                        runCurrent()
                        val recorded = captures.any { it.first == JourneyEventNames.LINK_OPENED }
                        val completed = !completes || captures.any { it.first == JourneyEventNames.LEG_COMPLETED }
                        if (handoffCount > 0 && completed && (!expected.getValue("recorded").jsonPrimitive.boolean || recorded)) break
                        Thread.sleep(10)
                    }
                }
                pendingClose?.let { close ->
                    allowIdentityShutdown.complete(Unit)
                    runCurrent()
                    if (vector["retirement"] == JsonPrimitive("identity_change") || beforeShutdown) {
                        retirementCloseReason = PresentationRegistry.currentScreen(launched.single())?.screenCloseReason()
                    }
                    controller!!.pause().stop().destroy(); host.resume(); runCurrent()
                    close.await()
                }
                runCurrent()
                if (state == "owner_retired") {
                    assertEquals(name, 1, handoffCount)
                    assertTrue("$name must hand off before the test destroys the resumed Activity", handoffWhileResumed)
                    assertEquals(name, CloseReason.IdentityChanged, retirementCloseReason)
                    if (vector["retirement"] == JsonPrimitive("identity_change") || beforeShutdown) {
                        assertTrue("$name must route before queued identity shutdown starts", handoffBeforeShutdown)
                    }
                }
                val records = captures.filter { it.first == JourneyEventNames.LINK_OPENED }
                assertEquals(name, if (expected.getValue("recorded").jsonPrimitive.boolean) 1 else 0, records.size)
                records.firstOrNull()?.let { record ->
                    assertEquals(name, expected["destination"]?.jsonPrimitive?.contentOrNull, record.second["destination"])
                }
                val intent = controller?.get()?.let { org.robolectric.Shadows.shadowOf(it).nextStartedActivity }
                    ?: org.robolectric.Shadows.shadowOf(host.get()).nextStartedActivity ?: org.robolectric.Shadows.shadowOf(app).nextStartedActivity
                assertEquals(name, expected.getValue("opened").jsonPrimitive.boolean, intent != null)
                if (intent != null) {
                    assertEquals(android.content.Intent.ACTION_VIEW, intent.action)
                    assertEquals(name, expected["destination"] == JsonPrimitive("in_app"), intent.hasExtra(androidx.browser.customtabs.CustomTabsIntent.EXTRA_SESSION))
                    if (state == "paused_foreground") assertTrue(intent.flags and android.content.Intent.FLAG_ACTIVITY_NEW_TASK != 0)
                }
                if (completes) {
                    val names = captures.map { it.first }
                    assertTrue(names.indexOf(JourneyEventNames.LINK_OPENED) >= 0)
                    assertTrue(names.indexOf(JourneyEventNames.LINK_OPENED) < names.indexOf(JourneyEventNames.LEG_COMPLETED))
                }
                runCurrent()
                dialog?.dismiss(); presentations.close()
                controller?.let { if (!it.get().isDestroyed) { if (state !in setOf("background", "paused_foreground")) it.pause(); if (state != "background") it.stop(); it.destroy() } }
                if (screenless || state in setOf("owner_retired", "presentation_finished")) host.pause()
                if (state != "background") host.stop()
                host.destroy(); runCurrent(); PresentationRegistry.clearForTesting()
                journeyScope.cancel()
            }
        } finally { app.unregisterActivityLifecycleCallbacks(lifecycle); lifecycle.close(); Dispatchers.resetMain() }
    }

    @Test fun `Journey worker propagates frame link cancellation without rejecting it`() = runBlocking {
        val identity = IdentityService(context).also { it.setDistinctId("customer") }
        val releaseEntry = fixture.getValue("renderedEntry").jsonObject
        val catalog = catalog(releaseEntry)
        val authority = authority(releaseEntry)
        catalog.commit("customer", catalog.prepare(profile(releaseEntry = releaseEntry), authority))
        val presenter = RecordingJourneyPresenter()
        val service = JourneyService(identity, store, catalog, directory, scope,
            capture = { _, _, _, _ -> true }, presenter = presenter, nowMillis = { 100_000L })
        service.initialize(); service.onAppWillEnterForeground()
        service.profileDidCommit(requireNotNull(catalog.snapshot("customer")), authority, "customer", 1)
        val request = requireNotNull(presenter.request)
        val cancelled = kotlinx.coroutines.CancellationException("platform link cancelled")
        val sources = ai.nuxie.sdk.presentation.JourneyRuntimeEmissionSources(frameLinks =
            ai.nuxie.sdk.presentation.JourneyFrameLinks { throw cancelled })
        val batch = JourneyScreenEmissionBatch(request.journeyId, 0, "cancelled-frame",
            JourneyScreenEmissionSource(request.screenId, "runtime:1"),
            listOf(JourneyScreenEmission("00000000-0000-7000-8000-000000000997", 0, 100_000L, "unrouted", JsonObject(emptyMap()))))
        try {
            request.onEmissionBatch(batch, sources)
            org.junit.Assert.fail("The worker must propagate cancellation")
        } catch (failure: kotlinx.coroutines.CancellationException) { assertEquals(cancelled.message, failure.message) }
        withTimeout(1_000) { service.onAppDidEnterBackground() }
    }

    @Test fun `shared broken link states use JourneyService and the real presentation service`() = runBlocking {
        val vectors = Json.parseToJsonElement(FixtureRunner.fixturesRoot().resolve("events/link-open-states.json").readText()).jsonObject.getValue("cases").jsonArray
        var count = 0
        for (entry in vectors) {
            val link = entry.jsonObject.getValue("link").jsonObject
            val expected = entry.jsonObject.getValue("expected").jsonObject
            if (link["kind"] != JsonPrimitive("journey") || expected["opened"] != JsonPrimitive(false)) continue
            assertLinkStep(link["url"], (link["target"] as? JsonPrimitive)?.contentOrNull, false, realPresenter = true)
            count++
        }
        assertEquals(5, count)
    }

    @Test fun `unresolved link advances without dismissal`() = runBlocking { assertLinkStep(Json.parseToJsonElement("""{"type":"Event.Field","key":"absent"}"""), "external", false) }
    @Test fun `empty link advances without dismissal`() = runBlocking { assertLinkStep(Json.parseToJsonElement("""{"type":"String","value":""}"""), "external", false) }
    @Test fun `missing link advances without dismissal`() = runBlocking { assertLinkStep(null, "external", false) }
    @Test fun `non string link advances without dismissal`() = runBlocking { assertLinkStep(JsonPrimitive(true), "external", false) }
    @Test fun `missing target advances without dismissal`() = runBlocking { assertLinkStep(Json.parseToJsonElement("""{"type":"String","value":"https://example.test"}"""), null, false) }
    @Test fun `external link without presentation opens and advances`() = runBlocking { assertLinkStep(Json.parseToJsonElement("""{"type":"String","value":"https://example.test"}"""), "external", true) }
    @Test fun `in app link without presentation opens externally`() = runBlocking { assertLinkStep(Json.parseToJsonElement("""{"type":"String","value":"https://example.test"}"""), "in_app", true) }
    @Test fun `link record precedes completion and uses step effect id`() = runBlocking { assertLinkStep(Json.parseToJsonElement("""{"type":"String","value":"https://example.test"}"""), "external", true, complete = true) }
    @Test fun `recording failure cannot abandon an opened link`() = runBlocking { assertLinkStep(Json.parseToJsonElement("""{"type":"String","value":"https://example.test"}"""), "external", true, recordingFails = true) }

    @Test fun `presented link record precedes completion and uses step effect id`() = runBlocking { assertLinkStep(Json.parseToJsonElement("""{"type":"String","value":"https://example.test"}"""), "in_app", true, complete = true, owned = true) }

    private suspend fun assertLinkStep(url: JsonElement?, target: String?, opens: Boolean, complete: Boolean = false, recordingFails: Boolean = false, owned: Boolean = false, realPresenter: Boolean = false) {
        val directory = java.io.File(this.directory, java.util.UUID.randomUUID().toString()).apply { mkdirs() }
        val identity = IdentityService(context).also { it.setDistinctId("customer") }
        val entry = fixture.getValue("renderedEntry").jsonObject
        val catalog = catalog(entry)
        val authority = authority(entry)
        catalog.commit("customer", catalog.prepare(profile(releaseEntry = entry), authority))
        val baseline = requireNotNull(catalog.snapshot("customer"))
        val original = baseline.releasesByDigest.values.single()
        val action = buildJsonObject { put("type", "open_link"); url?.let { put("url", it) }; target?.let { put("target", it) } }
        val next = if (realPresenter) Json.parseToJsonElement("""{"kind":"action","id":"next","action":{"type":"delay","durationMs":60000},"outlets":{}}""")
            else if (complete) Json.parseToJsonElement("""{"kind":"complete","id":"next","outcome":"completed"}""")
            else Json.parseToJsonElement("""{"kind":"action","id":"next","action":{"type":"navigate","screenId":"screen_welcome"},"outlets":{}}""")
        val leg = JsonObject(original.leg + mapOf("entryStepId" to JsonPrimitive(if (owned) "present" else "link"), "routes" to (if (owned) Json.parseToJsonElement("""[{"host":{"kind":"screen","screenId":"screen_welcome"},"eventName":"open","entryStepId":"link"}]""") else JsonArray(emptyList())),
            "steps" to JsonArray(listOf(Json.parseToJsonElement("""{"kind":"action","id":"present","action":{"type":"navigate","screenId":"screen_welcome"},"outlets":{}}"""), buildJsonObject { put("kind", "action"); put("id", "link"); put("action", action); putJsonObject("outlets") { put("next", "next") } }, next))))
        val envelope = JourneyReleaseEnvelope.authenticate(entry.getValue("envelope").toString().encodeToByteArray(),
            mapOf("TEST_ONLY_DEV_KEYPAIR" to Base64.decode(fixture.getValue("publicKeyBase64").jsonPrimitive.content, Base64.NO_WRAP)))
        val release = AuthenticatedJourneyRelease(envelope, original.identity, JsonObject(original.descriptor + ("leg" to leg)), original.publishedAtSeqToPromote)
        val snapshot = baseline.copy(releasesByDigest = mapOf(release.descriptorSha256 to release))
        val journal = JourneyRunJournal(directory, "customer", JourneyStorageScope(authority))
        val captures = CopyOnWriteArrayList<Triple<String, Map<String, Any?>, String>>()
        val opened = CopyOnWriteArrayList<String>()
        var effectId: String? = null
        val presenter = RecordingJourneyPresenter()
        val recorder = JourneyEffectDispatcher(identity,
            capture = { name, properties, id, _, admission, _ ->
                if (recordingFails) error("Injected recording failure")
                admission.commitIfCurrent { captures += Triple(name, properties, id); true } == true
            }, deliverAppAction = { _, _ -> false })
        val actualPresentation = ai.nuxie.sdk.presentation.ExperiencePresentationService(currentDistinctId = identity::distinctId, scope = scope, emit = { _, _, _ -> },
            runtimeAvailable = { true }, launch = { error("Broken link must not launch an Experience") },
            openLink = { _, _ -> error("Broken link must not reach platform handoff") })
        val presentationAttempts = CopyOnWriteArrayList<String>()
        val actualPresenter = object : JourneyPresenting by presenter {
            override suspend fun openLink(owner: JourneyPresentationOwner, link: ai.nuxie.sdk.presentation.JourneyLinkRequest): ai.nuxie.sdk.presentation.JourneyOpenedLink? {
                presentationAttempts += "open"
                return actualPresentation.openJourneyLink(owner, link)
            }
            override suspend fun present(request: JourneyPresentationRequest): JourneyPresentationResult {
                presentationAttempts += "present"
                return presenter.present(request)
            }
            override suspend fun dispatchAction(owner: JourneyPresentationOwner, action: JsonObject, effectId: String): JourneyPresentationActionResult {
                presentationAttempts += "dispatch"
                return actualPresentation.dispatchJourneyAction(owner, action, effectId)
            }
            override suspend fun shutdownPresentation(ownerDistinctId: String, journeyId: String) {
                presentationAttempts += "finish"
                actualPresentation.shutdownJourney(ownerDistinctId, journeyId)
            }
            override suspend fun shutdownOwnedBy(ownerDistinctId: String) {
                presentationAttempts += "shutdown"
                actualPresentation.shutdownOwnedBy(ownerDistinctId)
            }
        }
        val service = JourneyService(identity = identity, events = store, catalog = catalog, journalDirectory = directory, scope = scope,
            capture = { name, properties, id, _ -> captures += Triple(name, properties, id); true }, presenter = if (realPresenter) actualPresenter else presenter,
            linkRecorder = recorder, nowMillis = { 100_000L })
        if (!owned) presenter.linkHandler = { link ->
            opened += link.url
            effectId = journal.runs().first().effectReceipts["link"]
            link.opened("external")
        }
        service.initialize(); service.onAppWillEnterForeground(); service.profileDidCommit(snapshot, authority, "customer", 1)
        if (owned) {
            presenter.recordsOpenedLinks = true
            val request = requireNotNull(presenter.request)
            request.onEmissionBatch(JourneyScreenEmissionBatch(request.journeyId, 0, "open-link", JourneyScreenEmissionSource("screen_welcome", "runtime:1"),
                listOf(JourneyScreenEmission("00000000-0000-7000-8000-000000000922", 0, 100_000L, "open", JsonObject(emptyMap())))), null)
            service.onAppDidEnterBackground()
            effectId = presenter.actions.first().second
        }
        if (realPresenter) assertEquals("Broken steps must be rejected before foreground or presentation gating", emptyList<String>(), presentationAttempts)
        assertEquals(if (opens && !owned) 1 else 0, opened.size)
        val records = captures.filter { it.first == JourneyEventNames.LINK_OPENED }
        assertEquals(if (opens && !recordingFails) 1 else 0, records.size)
        records.firstOrNull()?.let { assertEquals(effectId, it.third); assertEquals(if (owned) "in_app" else "external", it.second["destination"]); assertEquals(target, it.second["target"]) }
        if (complete) {
            val names = captures.map { it.first }
            if (opens) assertTrue(names.indexOf(JourneyEventNames.LINK_OPENED) < names.indexOf(JourneyEventNames.LEG_COMPLETED))
            else assertEquals("completed", captures.single { it.first == JourneyEventNames.LEG_COMPLETED }.second["outcome"])
        } else {
            assertEquals("next", journal.runs().first().stepId)
            assertTrue(presenter.shutdowns.isEmpty())
        }
    }

    @Test fun `second row frame reaches relative purchase through batch admission`() = runBlocking {
        assertPurchaseOfferRoute(ai.nuxie.sdk.features.FeatureAccess(false, false, null, ai.nuxie.sdk.features.FeatureType.BOOLEAN), relativeRow = true)
    }

    @Test fun `purchase advances only from its correlated Journey outcome`() = runBlocking {
        assertPurchaseOfferRoute(ai.nuxie.sdk.features.FeatureAccess(false, false, null, ai.nuxie.sdk.features.FeatureType.BOOLEAN))
    }

    @Test fun `unknown offer access takes its alternative without presenting or purchasing`() = runBlocking {
        assertPurchaseOfferRoute(null)
    }

    @Test fun `owned offer access takes its alternative without presenting or purchasing`() = runBlocking {
        assertPurchaseOfferRoute(ai.nuxie.sdk.features.FeatureAccess(true, true, null, ai.nuxie.sdk.features.FeatureType.BOOLEAN))
    }

    @Test fun `offer alternatives can dismiss before presentation using the shared contract`() = runBlocking {
        val corpus = Json.parseToJsonElement(java.io.File(
            FixtureRunner.fixturesRoot(), "journeys/planes/presentationless-dismiss.json",
        ).readText()).jsonObject
        for (element in corpus.getValue("cases").jsonArray) {
            val vector = element.jsonObject
            val access = if (vector.getValue("access").jsonPrimitive.content == "owned")
                ai.nuxie.sdk.features.FeatureAccess(true, true, null, ai.nuxie.sdk.features.FeatureType.BOOLEAN)
            else null
            assertPurchaseOfferRoute(access, vector.getValue("action").jsonObject,
                vector.getValue("outcome").jsonPrimitive.content)
        }
    }

    @Test fun `purchase then authored dismiss completes the same Journey once`() = runBlocking {
        assertAuthoredDismiss("purchase")
    }

    @Test fun `restore then authored dismiss completes the same Journey once`() = runBlocking {
        assertAuthoredDismiss("restore")
    }

    @Test fun `genuine user close still reports host dismissed through the real presenter`() = runBlocking {
        assertAuthoredDismiss(null)
    }

    @Test fun `cancelled purchase without outlet permits Subscribe again`() = runBlocking {
        assertAuthoredDismiss("purchase", SystemEventNames.PURCHASE_CANCELLED)
    }

    @Test fun `failed purchase without outlet permits Subscribe again`() = runBlocking {
        assertAuthoredDismiss("purchase", SystemEventNames.PURCHASE_FAILED)
    }

    @Test fun `runtime second purchase tap stays open and cancelled retry completes`() = runBlocking {
        assertAuthoredDismiss("purchase", SystemEventNames.PURCHASE_CANCELLED, runtimeFrames = true)
    }

    @Test fun `runtime second restore tap stays open and failed retry completes`() = runBlocking {
        assertAuthoredDismiss("restore", SystemEventNames.RESTORE_FAILED, runtimeFrames = true)
    }

    @Test fun `deferred purchase allows runtime authored close then completes once`() = runBlocking {
        assertAuthoredDismiss("purchase", runtimeFrames = true, pendingClose = true)
    }

    @Test fun `authored close then cancelled without outlet uses close outcome`() = runBlocking {
        assertAuthoredDismiss("purchase", runtimeFrames = true, pendingClose = true, terminalOutcome = SystemEventNames.PURCHASE_CANCELLED)
    }

    @Test fun `authored close then failed without outlet uses close outcome`() = runBlocking {
        assertAuthoredDismiss("purchase", runtimeFrames = true, pendingClose = true, terminalOutcome = SystemEventNames.PURCHASE_FAILED)
    }

    @Test fun `terminal purchase waits for accepted authored route while publication is held`() = runBlocking {
        assertAuthoredDismiss("purchase", runtimeFrames = true, pendingClose = true, holdClosePublication = true)
    }

    @Test fun `failed completion keeps closed purchase correlation for retry`() = runBlocking {
        assertAuthoredDismiss("purchase", runtimeFrames = true, pendingClose = true,
            terminalOutcome = SystemEventNames.PURCHASE_FAILED, failCompletionOnce = true)
    }

    @Test fun `terminal purchase recovers a previously failed authored publication`() = runBlocking {
        assertAuthoredDismiss("purchase", failPublicationOnce = true)
    }

    @Test fun `settled closed purchase does not finish a later cancelled purchase`() = runBlocking {
        assertAuthoredDismiss("purchase", runtimeFrames = true, pendingClose = true, secondPurchase = true)
    }

    @Test fun `deferred commerce still starts an armed terminal event journey`() = runBlocking {
        assertAuthoredDismiss("purchase", runtimeFrames = true, pendingClose = true,
            holdClosePublication = true, terminalStartsEntry = true)
    }

    @Test fun `durably deferred failure retries completion without event replay`() = runBlocking {
        assertAuthoredDismiss("purchase", runtimeFrames = true, pendingClose = true,
            terminalOutcome = SystemEventNames.PURCHASE_FAILED, holdClosePublication = true,
            deferredFailureRetry = true)
    }

    @Test fun `durably completed Journey closes its screen when report publication throws`() = runBlocking {
        assertAuthoredDismiss("purchase", failReportOnce = true)
    }

    @Test fun `dismissed lifecycle route retains pending purchase`() = runBlocking {
        assertAuthoredDismiss("purchase", runtimeFrames = true, pendingClose = true, lifecycleRoute = SystemEventNames.SCREEN_DISMISSED)
    }

    @Test fun `shown lifecycle route retains pending purchase`() = runBlocking {
        assertAuthoredDismiss("purchase", runtimeFrames = true, pendingClose = true, lifecycleRoute = SystemEventNames.SCREEN_SHOWN)
    }

    @Test fun `deferred restore allows authored close then follows restored outlet once`() = runBlocking {
        assertAuthoredDismiss("restore", runtimeFrames = true, pendingClose = true)
    }

    @Test fun `deferred purchase allows exit then follows completed outlet once`() = runBlocking {
        assertAuthoredDismiss("purchase", runtimeFrames = true, pendingClose = true, exitClose = true)
    }

    @Test fun `exit then cancelled purchase keeps authored close outcome`() = runBlocking {
        assertAuthoredDismiss("purchase", runtimeFrames = true, pendingClose = true,
            terminalOutcome = SystemEventNames.PURCHASE_CANCELLED, exitClose = true)
    }

    private suspend fun assertAuthoredDismiss(commerce: String?, retryOutcome: String? = null, runtimeFrames: Boolean = false,
        pendingClose: Boolean = false, terminalOutcome: String? = null, holdClosePublication: Boolean = false,
        failCompletionOnce: Boolean = false, failPublicationOnce: Boolean = false, secondPurchase: Boolean = false, terminalStartsEntry: Boolean = false, failReportOnce: Boolean = false, deferredFailureRetry: Boolean = false, lifecycleRoute: String? = null, exitClose: Boolean = false) {
        val vector = Json.parseToJsonElement(FixtureRunner.fixturesRoot()
            .resolve("journeys/planes/authored-dismiss.json").readText()).jsonObject
            .getValue("cases").jsonArray.single { it.jsonObject.getValue("name").jsonPrimitive.content == (commerce ?: "user_close") }.jsonObject
        val pendingCorpus = Json.parseToJsonElement(FixtureRunner.fixturesRoot()
            .resolve("journeys/planes/pending-commerce.json").readText()).jsonObject
        val pendingExpected = pendingCorpus.getValue("cases").jsonArray.map(JsonElement::jsonObject).single {
            it.getValue("terminalEvent").jsonPrimitive.content == (terminalOutcome ?: if (commerce == "restore") SystemEventNames.RESTORE_COMPLETED else SystemEventNames.PURCHASE_COMPLETED)
        }
        val identity = IdentityService(context).also { it.setDistinctId("customer") }
        val entry = fixture.getValue("renderedEntry").jsonObject
        val catalog = catalog(entry)
        val authority = authority(entry)
        val firstProfile = profile(releaseEntry = entry)
        val profileDocument = if (terminalStartsEntry) {
            val secondProfile = profile(releaseEntry = fixture.getValue("entry").jsonObject)
            JsonObject(firstProfile + mapOf(
                "armedLegs" to JsonArray(firstProfile.getValue("armedLegs").jsonArray + secondProfile.getValue("armedLegs").jsonArray),
                "releases" to JsonArray(firstProfile.getValue("releases").jsonArray + secondProfile.getValue("releases").jsonArray)))
        } else firstProfile
        catalog.commit("customer", catalog.prepare(profileDocument, authority))
        val baseline = requireNotNull(catalog.snapshot("customer"))
        if (!terminalStartsEntry) assertEquals(1, baseline.releasesByDigest.size)
        val original = baseline.releasesByDigest.getValue(entry.getValue("envelope").jsonObject.getValue("descriptorSha256").jsonPrimitive.content)
        val type = commerce ?: "purchase"
        val action = buildJsonObject {
            put("type", type)
            if (type == "purchase") put("placementId", "golden:monthly")
        }
        var leg = JsonObject(original.leg + mapOf(
            "entryStepId" to JsonPrimitive("present"),
            "offers" to JsonArray(emptyList()),
            "steps" to JsonArray(listOf(
                Json.parseToJsonElement("""{"kind":"action","id":"present","action":{"type":"navigate","screenId":"screen_welcome"},"outlets":{}}"""),
                buildJsonObject {
                    put("kind", "action"); put("id", "commerce"); put("action", action)
                    putJsonObject("outlets") { put(if (type == "purchase") "completed" else "restored", "dismiss") }
                },
                buildJsonObject {
                    put("kind", "action"); put("id", "dismiss")
                    putJsonObject("action") {
                        put("type", "dismiss")
                        if (pendingClose) put("reason", if (type == "restore") "restore_finished" else "purchase_finished")
                    }
                    putJsonObject("outlets") {}
                },
                Json.parseToJsonElement("""{"kind":"action","id":"close","action":{"type":"${if (exitClose) "exit" else "dismiss"}","reason":"author_closed"},"outlets":{}}"""),
                Json.parseToJsonElement("""{"kind":"action","id":"close_marker","action":{"type":"send_event","eventName":"authored_close_requested","payload":{}},"outlets":{"next":"close"}}"""),
            )),
            "routes" to Json.parseToJsonElement("""[{"host":{"kind":"screen","screenId":"screen_welcome"},"eventName":"buy","entryStepId":"commerce"},{"host":{"kind":"screen","screenId":"${if (lifecycleRoute == SystemEventNames.SCREEN_SHOWN) "screen_thanks" else "screen_welcome"}"},"eventName":"${lifecycleRoute ?: "close"}","entryStepId":"close_marker"}]"""),
        ))
        if (lifecycleRoute == SystemEventNames.SCREEN_SHOWN) {
            leg = JsonObject(leg + ("screens" to JsonArray(leg.getValue("screens").jsonArray +
                buildJsonObject { put("id", "screen_thanks") })))
        }
        if (failReportOnce) {
            leg = JsonObject(leg + ("steps" to JsonArray(leg.getValue("steps").jsonArray.map {
                if (it.jsonObject["id"] == JsonPrimitive("dismiss")) Json.parseToJsonElement(
                    """{"kind":"complete","id":"dismiss","outcome":"completed"}""") else it
            })))
        }
        if (secondPurchase) {
            val steps = leg.getValue("steps").jsonArray.map { step ->
                if (step.jsonObject["id"] == JsonPrimitive("commerce")) JsonObject(step.jsonObject +
                    ("outlets" to buildJsonObject { put("completed", "thanks") })) else step
            } + listOf(
                Json.parseToJsonElement("""{"kind":"action","id":"thanks","action":{"type":"navigate","screenId":"screen_thanks"},"outlets":{}}"""),
                buildJsonObject { put("kind", "action"); put("id", "commerce_again"); put("action", action)
                    putJsonObject("outlets") { put("completed", "dismiss") } },
            )
            leg = JsonObject(leg + mapOf("steps" to JsonArray(steps),
                "screens" to JsonArray(leg.getValue("screens").jsonArray + buildJsonObject { put("id", "screen_thanks") }),
                "routes" to JsonArray(leg.getValue("routes").jsonArray + Json.parseToJsonElement(
                    """{"host":{"kind":"screen","screenId":"screen_thanks"},"eventName":"buy_more","entryStepId":"commerce_again"}"""))))
        }
        val products = JsonArray(original.descriptor.getValue("products").jsonArray.map {
            JsonObject(it.jsonObject + ("type" to JsonPrimitive("consumable")))
        })
        val envelope = JourneyReleaseEnvelope.authenticate(entry.getValue("envelope").toString().encodeToByteArray(),
            mapOf("TEST_ONLY_DEV_KEYPAIR" to Base64.decode(fixture.getValue("publicKeyBase64").jsonPrimitive.content, Base64.NO_WRAP)))
        val render = original.descriptor.getValue("render").jsonObject
        val fixtureRender = if (secondPurchase || lifecycleRoute == SystemEventNames.SCREEN_SHOWN) JsonObject(render + ("screens" to JsonArray(
            render.getValue("screens").jsonArray + JsonObject(render.getValue("screens").jsonArray.first().jsonObject +
                ("id" to JsonPrimitive("screen_thanks")))))) else render
        val release = AuthenticatedJourneyRelease(envelope, original.identity,
            JsonObject(original.descriptor + mapOf("leg" to leg, "products" to products, "render" to fixtureRender)), original.publishedAtSeqToPromote)
        val terminalEntry = buildJsonObject { put("type", "event"); put("eventName", SystemEventNames.PURCHASE_COMPLETED) }
        val snapshot = baseline.copy(
            profile = if (terminalStartsEntry) JourneyPlaneProfile.decode(JsonObject(profileDocument + ("armedLegs" to JsonArray(
                profileDocument.getValue("armedLegs").jsonArray.map {
                    if (it.jsonObject.getValue("reference").jsonObject["descriptorSha256"] != JsonPrimitive(release.descriptorSha256))
                        JsonObject(it.jsonObject + ("entryCondition" to terminalEntry)) else it
                }))).toString().encodeToByteArray()) else baseline.profile,
            releasesByDigest = (baseline.releasesByDigest + (release.descriptorSha256 to release)).mapValues { (digest, value) ->
                if (terminalStartsEntry && digest != release.descriptorSha256) AuthenticatedJourneyRelease(JourneyReleaseEnvelope.authenticate(fixture.getValue("entry").jsonObject.getValue("envelope").toString().encodeToByteArray(),
                    mapOf("TEST_ONLY_DEV_KEYPAIR" to Base64.decode(fixture.getValue("publicKeyBase64").jsonPrimitive.content, Base64.NO_WRAP))), value.identity,
                    JsonObject(value.descriptor + ("leg" to JsonObject(value.leg + mapOf(
                        "entryCondition" to terminalEntry,
                        "policy" to JsonObject(value.leg.getValue("policy").jsonObject + ("entry" to JsonObject(
                            value.leg.getValue("policy").jsonObject.getValue("entry").jsonObject +
                                ("frequency" to buildJsonObject { put("type", "every_match") })))))))), value.publishedAtSeqToPromote)
                else value
            })
        val launched = CopyOnWriteArrayList<String>()
        val presentations = ExperiencePresentationService(emit = { _, _, _ -> }, scope = scope,
            runtimeAvailable = { true }, currentDistinctId = identity::distinctId, launch = launched::add)
        val requestReady = CompletableDeferred<JourneyPresentationRequest>()
        val requests = CopyOnWriteArrayList<JourneyPresentationRequest>()
        val publications = CopyOnWriteArrayList<JourneyScreenEmissionBatch>()
        val claimedEffect = CompletableDeferred<String>()
        val claimedEffects = CopyOnWriteArrayList<String>()
        val failCompletionClock = java.util.concurrent.atomic.AtomicBoolean()
        val completionFailureCount = java.util.concurrent.atomic.AtomicInteger()
        val presenter = object : JourneyPresenting {
            override suspend fun openLink(owner: JourneyPresentationOwner, link: JourneyLinkRequest) = presentations.openJourneyLink(owner, link)
            override fun reserve(ownerDistinctId: String) = presentations.reserveJourney(ownerDistinctId)
            override fun owns(owner: JourneyPresentationOwner) = presentations.ownsJourney(owner)
            override fun screenId(owner: JourneyPresentationOwner) = presentations.journeyScreenId(owner)
            override fun resolveAction(owner: JourneyPresentationOwner, action: JsonObject, source: JourneyScreenEmissionSource?, eventSource: ai.nuxie.sdk.presentation.JourneyRuntimeEventSource?) =
                presentations.resolveJourneyAction(owner, action, source, eventSource)
            override suspend fun present(request: JourneyPresentationRequest): JourneyPresentationResult {
                val file = File(directory, "dismiss-scene").apply { writeBytes(byteArrayOf(1)) }
                val showing = scope.async {
                    presentations.presentJourney(request.fences, request.release, request.screenId, request.journeyId,
                        request.ownerDistinctId, request.reservation,
                        acquire = { AcquiredJourneyRelease(identity = request.release.identity,
                            artifactsByKey = mapOf("renders/main.riv" to file), sceneFile = file, protection = Closeable {}) },
                        onScreenChanged = request.onScreenChanged, onScreenDismissed = request.onScreenDismissed,
                        onPresentationRevealed = request.onPresentationRevealed, onEmissionBatch = { batch, sources ->
                            request.onEmissionBatch(batch, sources).also { publications += batch }
                        },
                        onOutcome = request.onOutcome)
                }
                withTimeout(5_000) {
                    while (launched.isEmpty() || PresentationRegistry.observe(launched.last())?.value !is
                        ai.nuxie.sdk.presentation.PresentationContentState.Ready) kotlinx.coroutines.delay(10)
                }
                PresentationRegistry.reportFirstFrame(launched.last())
                showing.await()
                requests += request
                requestReady.complete(request)
                return JourneyPresentationResult.Shown
            }
            override suspend fun dispatchAction(owner: JourneyPresentationOwner, action: JsonObject, effectId: String): JourneyPresentationActionResult {
                if (action["type"] == JsonPrimitive(type)) {
                    // The store boundary is controlled; authored dismissal uses the real service.
                    claimedEffects += effectId
                    claimedEffect.complete(effectId)
                    return JourneyPresentationActionResult.AwaitingOutcome
                }
                return presentations.dispatchJourneyAction(owner, action, effectId)
            }
            override suspend fun shutdownPresentation(ownerDistinctId: String, journeyId: String) {
                presentations.shutdownJourney(ownerDistinctId, journeyId)
                if (deferredFailureRetry && completionFailureCount.get() == 0) failCompletionClock.set(true)
            }
            override suspend fun shutdownOwnedBy(ownerDistinctId: String) = presentations.shutdownOwnedBy(ownerDistinctId)
        }
        val closeCaptureEntered = CompletableDeferred<Unit>()
        val releaseCloseCapture = CompletableDeferred<Unit>()
        val failPublication = java.util.concurrent.atomic.AtomicBoolean(failPublicationOnce)
        val captures = CopyOnWriteArrayList<Pair<String, Map<String, Any?>>>()
        val failReport = java.util.concurrent.atomic.AtomicBoolean(failReportOnce)
        val journeys = JourneyService(identity = identity, events = store, catalog = catalog, journalDirectory = directory,
            scope = scope, capture = { name, properties, _, _ ->
                captures += name to properties
                if (name == JourneyEventNames.LEG_COMPLETED && failReport.compareAndSet(true, false)) {
                    throw java.io.IOException("Completion publication unavailable")
                }
                true
            },
            dispatcher = JourneyEffectDispatcher(identity, capture = { name, properties, _, _, admission, _ ->
                admission.commitIfCurrent { captures += name to properties; true } == true
            }, deliverAppAction = { _, _ -> error("No app action authored") }),
            captureScreenEvent = { name, properties, id, distinctId, occurredAt, admission, origin ->
                if (name == "checkout_publication" && failPublication.compareAndSet(true, false)) {
                    throw java.io.IOException("Renderer event capture unavailable")
                }
                if (holdClosePublication && name == "close") {
                    closeCaptureEntered.complete(Unit)
                    releaseCloseCapture.await()
                }
                val event = StoredEvent(id, name, JsonValueConverter.fromMap(properties), occurredAt, distinctId, journeyOrigin = origin)
                val settled = admission?.commitIfCurrent { true } != null
                StableEventCaptureResult(settled, event.takeIf { settled })
            },
            capturePresentationEvent = { name, properties, id, distinctId, occurredAt, admission ->
                val event = StoredEvent(id, name, JsonValueConverter.fromMap(properties), occurredAt, distinctId)
                val settled = admission?.commitIfCurrent { true } != null
                StableEventCaptureResult(settled, event.takeIf { settled })
            }, presenter = presenter, nowMillis = {
                if (failCompletionClock.compareAndSet(true, false)) {
                    completionFailureCount.incrementAndGet()
                    throw java.io.IOException("Completion clock unavailable")
                }
                100_000L
            })
        try {
            journeys.initialize()
            journeys.onAppWillEnterForeground()
            journeys.profileDidCommit(snapshot, authority, "customer", 1)
            val request = withTimeout(5_000) { requestReady.await() }
            suspend fun runtimeTap(eventName: String = "buy") {
                val prior = publications.size
                PresentationRegistry.reportRuntimeStep(launched.last(), NuxiePlayerStepOutcome(true,
                    emptyList(), listOf(ai.nuxie.sdk.runtime.NuxieRuntimeEvent(0, 128, eventName, "", "", 0f, emptyList())),
                    emptyList(), emptyList()), (prior + 1).toULong(), null)
                withTimeout(5_000) { while (publications.size == prior) kotlinx.coroutines.delay(10) }
            }
            if (commerce != null) {
                val batch = JourneyScreenEmissionBatch(request.journeyId, 0, "commerce-dismiss",
                    JourneyScreenEmissionSource("screen_welcome", "buy"), listOf(JourneyScreenEmission(
                        "00000000-0000-7000-8000-000000000901", 0, 100_000L, "buy", JsonObject(emptyMap()))))
                if (runtimeFrames) runtimeTap() else assertTrue(request.onEmissionBatch(batch, null) == JourneyEmissionBatchResult.ACCEPTED)
                var effectId = withTimeout(5_000) { claimedEffect.await() }
                if (retryOutcome != null) {
                    val retry = batch.copy(batchSequence = 1, invocationId = "commerce-retry",
                        emissions = listOf(JourneyScreenEmission("00000000-0000-7000-8000-000000000902",
                            1, 100_001L, "buy", JsonObject(emptyMap()))))
                    if (runtimeFrames) {
                        runtimeTap()
                        assertTrue("A pending operation must not close the paywall", presentations.ownsJourney(JourneyPresentationOwner(request.journeyId, "customer")))
                        assertTrue("A declined tap must not abandon the Journey", captures.none { it.first == JourneyEventNames.LEG_COMPLETED })
                        assertEquals(1, claimedEffects.size)
                    } else {
                        val pending = request.onEmissionBatch(retry, null)
                        assertFalse("Pending purchase rejects a second operation", pending == JourneyEmissionBatchResult.ACCEPTED)
                        assertEquals(JourneyEmissionBatchResult.DECLINED, pending)
                    }
                    suspend fun outcome(id: String, owner: String, name: String = retryOutcome) {
                        journeys.handleEvent(StoredEvent(id, name,
                            buildJsonObject { put("placement_id", "golden:monthly") }, 100_002L, owner),
                            journeys.eventAdmissionGeneration())
                    }
                    outcome("unrelated-effect", "customer")
                    outcome(effectId, "other-customer")
                    if (runtimeFrames) runtimeTap()
                    else {
                        val unrelated = request.onEmissionBatch(retry, null)
                        assertFalse("Only this owner's correlated outcome releases the operation", unrelated == JourneyEmissionBatchResult.ACCEPTED)
                        assertEquals(JourneyEmissionBatchResult.DECLINED, unrelated)
                    }
                    assertEquals(1, claimedEffects.size)
                    outcome(effectId, "customer")
                    assertTrue(presentations.ownsJourney(JourneyPresentationOwner(request.journeyId, "customer")))
                    assertTrue(captures.none { it.first == JourneyEventNames.LEG_COMPLETED })
                    if (runtimeFrames) {
                        runtimeTap()
                        assertEquals(listOf(0L, 1L, 1L, 1L), publications.map { it.batchSequence })
                        assertEquals(listOf(0L, 1L, 1L, 1L), publications.map { it.emissions.single().sequence })
                    } else assertTrue("Settled purchase permits Subscribe again", request.onEmissionBatch(retry, null) == JourneyEmissionBatchResult.ACCEPTED)
                    withTimeout(5_000) { while (claimedEffects.size < 2) kotlinx.coroutines.delay(10) }
                    assertEquals(2, claimedEffects.size)
                    val previous = effectId
                    effectId = claimedEffects.last()
                    assertNotEquals(previous, effectId)
                    outcome(previous, "customer", if (commerce == "purchase") SystemEventNames.PURCHASE_COMPLETED else SystemEventNames.RESTORE_COMPLETED)
                    assertTrue(presentations.ownsJourney(JourneyPresentationOwner(request.journeyId, "customer")))
                    assertTrue(captures.none { it.first == JourneyEventNames.LEG_COMPLETED })
                }
                if (pendingClose) {
                    journeys.handleEvent(StoredEvent("pending-telemetry", SystemEventNames.PURCHASE_PENDING,
                        buildJsonObject { put("placement_id", "golden:monthly") }, 100_001L, "customer"),
                        journeys.eventAdmissionGeneration())
                    if (holdClosePublication) {
                        val close = scope.async { runtimeTap("close") }
                        withTimeout(5_000) { closeCaptureEntered.await() }
                        val terminal = scope.async(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
                            journeys.handleEvent(StoredEvent(effectId, terminalOutcome ?: SystemEventNames.PURCHASE_COMPLETED,
                                buildJsonObject { put("placement_id", "golden:monthly") }, 100_001L, "customer"),
                                journeys.eventAdmissionGeneration())
                        }
                        releaseCloseCapture.complete(Unit)
                        val terminalAccepted = withTimeout(5_000) { close.await(); terminal.await() }
                        assertTrue("Deferral must acknowledge durable handling", terminalAccepted)
                    } else if (lifecycleRoute == SystemEventNames.SCREEN_SHOWN) {
                        assertTrue(request.onScreenChanged("screen_thanks"))
                    } else if (lifecycleRoute == SystemEventNames.SCREEN_DISMISSED) {
                        request.onScreenDismissed("screen_welcome", null, "user")
                    } else runtimeTap("close")
                    withTimeout(5_000) {
                        while (presentations.ownsJourney(JourneyPresentationOwner(request.journeyId, "customer"))) kotlinx.coroutines.delay(10)
                    }
                    assertEquals("Accepted non-commerce route executes once", pendingExpected.getValue("authoredActions").jsonPrimitive.int, captures.count { it.first == "authored_close_requested" })
                    if (!holdClosePublication) assertTrue("Closing the screen keeps the pending Journey active", captures.none { it.first == JourneyEventNames.LEG_COMPLETED })
                    assertEquals(1, claimedEffects.size)
                    if (!holdClosePublication) {
                        val waiting = JourneyRunJournal(directory, "customer", JourneyStorageScope(authority)).runs().single()
                        assertEquals(effectId, waiting.pendingCommerce?.effectId)
                        assertEquals("commerce", waiting.pendingCommerce?.stepId)
                        assertEquals("author_closed", waiting.authoredCloseOutcome)
                        for ((id, owner) in listOf("unrelated" to "customer", effectId to "another-customer")) {
                            journeys.handleEvent(StoredEvent(id,
                                if (commerce == "restore") SystemEventNames.RESTORE_COMPLETED else SystemEventNames.PURCHASE_COMPLETED,
                                if (commerce == "restore") JsonObject(emptyMap()) else buildJsonObject { put("placement_id", "golden:monthly") }, 100_002L, owner),
                                journeys.eventAdmissionGeneration())
                        }
                        assertTrue(captures.none { it.first == JourneyEventNames.LEG_COMPLETED })
                    }
                }
                if (failPublicationOnce) {
                    val failed = batch.copy(batchSequence = 1, invocationId = "failed-checkout-publication",
                        emissions = listOf(JourneyScreenEmission(effectId, 1, 100_001L,
                            "checkout_publication", JsonObject(emptyMap()))))
                    assertEquals(JourneyEmissionBatchResult.REJECTED, request.onEmissionBatch(failed, null))
                    assertFalse(failPublication.get())
                    assertEquals(1, claimedEffects.size)
                }
                if (failCompletionOnce) {
                    failCompletionClock.set(true)
                    assertFalse(journeys.handleEvent(StoredEvent(effectId, checkNotNull(terminalOutcome),
                        buildJsonObject { put("placement_id", "golden:monthly") }, 100_003L, "customer"),
                        journeys.eventAdmissionGeneration()))
                    assertFalse(failCompletionClock.get())
                    assertTrue(captures.none { it.first == JourneyEventNames.LEG_COMPLETED })
                }
                if (!holdClosePublication) journeys.handleEvent(StoredEvent(effectId,
                    terminalOutcome ?: (if (commerce == "purchase") SystemEventNames.PURCHASE_COMPLETED else SystemEventNames.RESTORE_COMPLETED),
                    buildJsonObject { if (commerce == "purchase") put("placement_id", "golden:monthly") },
                    100_001L, "customer"), journeys.eventAdmissionGeneration())
                if (secondPurchase) {
                    withTimeout(5_000) { while (requests.size < 2) kotlinx.coroutines.delay(10) }
                    val next = requests.last()
                    assertEquals("screen_thanks", next.screenId)
                    assertTrue(next.onScreenChanged("screen_thanks"))
                    val nextBatch = JourneyScreenEmissionBatch(next.journeyId, 2, "second-purchase",
                        JourneyScreenEmissionSource("screen_thanks", "buy_more"), listOf(JourneyScreenEmission(
                            "00000000-0000-7000-8000-000000000909", 2, 100_002L, "buy_more", JsonObject(emptyMap()))))
                    assertEquals(JourneyEmissionBatchResult.ACCEPTED, next.onEmissionBatch(nextBatch, null))
                    withTimeout(5_000) { while (claimedEffects.size < 2) kotlinx.coroutines.delay(10) }
                    val secondEffect = claimedEffects.last()
                    assertNotEquals(effectId, secondEffect)
                    journeys.handleEvent(StoredEvent(secondEffect, SystemEventNames.PURCHASE_CANCELLED,
                        buildJsonObject { put("placement_id", "golden:monthly") }, 100_003L, "customer"),
                        journeys.eventAdmissionGeneration())
                    assertTrue(JourneyRunJournal(directory, "customer", JourneyStorageScope(authority)).runs().any { it.completion == null })
                    assertTrue(presentations.ownsJourney(JourneyPresentationOwner(next.journeyId, "customer")))
                    assertEquals("screen_thanks", presentations.journeyScreenId(JourneyPresentationOwner(next.journeyId, "customer")))
                    assertTrue(captures.none { it.first == JourneyEventNames.LEG_COMPLETED })
                    return
                }
            } else presentations.dismiss(CloseReason.UserDismissed)
            // This new retry case must span the production five-second retry interval.
            withTimeout(if (deferredFailureRetry) 10_000 else 5_000) {
                while (captures.none { it.first == JourneyEventNames.LEG_COMPLETED && it.second["journey_id"] == request.journeyId }) kotlinx.coroutines.delay(10)
            }
            if (deferredFailureRetry) assertEquals("One failed settlement is retried without another event", 1, completionFailureCount.get())
            if (!secondPurchase) assertEquals("A single-screen case launches exactly once", 1, launched.size)
            if (!terminalStartsEntry) assertEquals("No extra Journey completion", 1, captures.count { it.first == JourneyEventNames.LEG_COMPLETED })
            val completed = captures.filter { it.first == JourneyEventNames.LEG_COMPLETED && it.second["journey_id"] == request.journeyId }
            assertEquals((if (pendingClose) pendingExpected else vector).getValue("reports").jsonPrimitive.int, completed.size)
            assertEquals(request.journeyId, completed.single().second["journey_id"])
            assertEquals((if (pendingClose) pendingExpected else vector).getValue("outcome").jsonPrimitive.content, completed.single().second["outcome"])
            assertFalse(presentations.ownsJourney(JourneyPresentationOwner(request.journeyId, "customer")))
            if (failReportOnce) {
                assertFalse(failReport.get())
                assertEquals("completed", JourneyRunJournal(directory, "customer", JourneyStorageScope(authority)).runs().single().completion?.outcome)
            }
            if (pendingClose && !terminalStartsEntry && !deferredFailureRetry) {
                journeys.handleEvent(StoredEvent(claimedEffects.single(),
                    if (commerce == "restore") SystemEventNames.RESTORE_COMPLETED else SystemEventNames.PURCHASE_COMPLETED,
                    if (commerce == "restore") JsonObject(emptyMap()) else buildJsonObject { put("placement_id", "golden:monthly") }, 100_002L, "customer"),
                    journeys.eventAdmissionGeneration())
                assertEquals(1, captures.count { it.first == JourneyEventNames.LEG_COMPLETED && it.second["journey_id"] == request.journeyId })
            }
            if (terminalStartsEntry) {
                val other = captures.filter { it.first == JourneyEventNames.LEG_COMPLETED && it.second["journey_id"] != request.journeyId }
                assertEquals("The terminal event starts its other armed Journey without reconcile", 1, other.size)
                assertEquals("continue", other.single().second["outcome"])
            }
        } finally {
            presentations.shutdownOwnedBy("customer")
            PresentationRegistry.clearForTesting()
        }
    }

    private suspend fun assertPurchaseOfferRoute(
        access: ai.nuxie.sdk.features.FeatureAccess?,
        dismissAlternative: JsonObject? = null,
        expectedOutcome: String = "continue",
        relativeRow: Boolean = false,
    ) {
        val directory = java.io.File(this.directory, java.util.UUID.randomUUID().toString()).apply { mkdirs() }
        val identity = IdentityService(context).also { it.setDistinctId("customer") }
        val renderedEntry = fixture.getValue("renderedEntry").jsonObject
        val catalog = catalog(renderedEntry)
        val renderedAuthority = authority(renderedEntry)
        val prepared = catalog.prepare(profile(releaseEntry = renderedEntry), renderedAuthority)
        runBlocking { catalog.commit("customer", prepared) }
        val baseline = requireNotNull(catalog.snapshot("customer"))
        val original = baseline.releasesByDigest.values.single()
        val leg = original.leg
        val purchaseStep = buildJsonObject {
            put("kind", "action")
            put("id", "purchase")
            putJsonObject("action") {
                put("type", "purchase")
                put("placementId", if (relativeRow) Json.parseToJsonElement("""{"ref":{"kind":"path","path":"placementId","isRelative":true}}""") else JsonPrimitive("golden:monthly"))
            }
            putJsonObject("outlets") {
                put("completed", "completed")
                put("failed", "failed")
                put("cancelled", "cancelled")
            }
        }
        fun completion(id: String) = buildJsonObject {
            put("kind", "complete")
            put("id", id)
            put("outcome", "continue")
        }
        val commerceLeg = JsonObject(
            leg + mapOf(
                "steps" to JsonArray(
                    listOf(
                        leg.getValue("steps").jsonArray.first(),
                        purchaseStep,
                        completion("completed"),
                        completion("failed"),
                        completion("cancelled"),
                        Json.parseToJsonElement("""{"kind":"action","id":"skip_offer","action":{"type":"send_event","eventName":"offer_owned"},"outlets":{"next":"skipped"}}"""),
                        completion("skipped"),
                        Json.parseToJsonElement("""{"kind":"action","id":"skip_unknown","action":{"type":"send_event","eventName":"offer_unknown"},"outlets":{"next":"skipped_unknown"}}"""),
                        completion("skipped_unknown"),
                    ),
                ),
                "offers" to Json.parseToJsonElement("""[{"screenId":"screen_welcome","placementIds":["golden:monthly"],"alreadyEntitledStepId":"skip_offer","unknownStepId":"skip_unknown"}]"""),
                "routes" to JsonArray(
                    listOf(
                        buildJsonObject {
                            putJsonObject("host") {
                                put("kind", "screen")
                                put("screenId", "screen_welcome")
                            }
                            put("eventName", if (relativeRow) "buy" else SystemEventNames.SCREEN_SHOWN)
                            put("entryStepId", "purchase")
                        },
                        buildJsonObject {
                            putJsonObject("host") { put("kind", "screen"); put("screenId", "screen_welcome") }
                            put("eventName", "\$offer_already_entitled"); put("entryStepId", "skip_offer")
                        },
                        buildJsonObject {
                            putJsonObject("host") { put("kind", "screen"); put("screenId", "screen_welcome") }
                            put("eventName", "\$offer_access_unknown"); put("entryStepId", "skip_unknown")
                        },
                    ),
                ),
            ),
        )
        val products = JsonArray(original.descriptor.getValue("products").jsonArray.map {
            JsonObject(it.jsonObject + ("entitlements" to Json.parseToJsonElement("""[{"id":"premium-grant","featureId":"premium","featureExternalId":null,"purchaseUsageFeatureIds":[]}]""")))
        })
        val qualifiedLeg = if (dismissAlternative == null) commerceLeg else JsonObject(commerceLeg + (
            "steps" to JsonArray(commerceLeg.getValue("steps").jsonArray.map { step ->
                if (step.jsonObject["id"]?.jsonPrimitive?.content in setOf("skip_offer", "skip_unknown")) {
                    JsonObject(step.jsonObject + mapOf("action" to dismissAlternative, "outlets" to JsonObject(emptyMap())))
                } else step
            })
        ))
        val descriptor = JsonObject(original.descriptor + mapOf("leg" to qualifiedLeg, "products" to products))
        val envelope = JourneyReleaseEnvelope.authenticate(
            renderedEntry.getValue("envelope").toString().encodeToByteArray(),
            mapOf(
                "TEST_ONLY_DEV_KEYPAIR" to Base64.decode(
                    fixture.getValue("publicKeyBase64").jsonPrimitive.content,
                    Base64.NO_WRAP,
                ),
            ),
        )
        val release = AuthenticatedJourneyRelease(
            envelope,
            original.identity,
            descriptor,
            original.publishedAtSeqToPromote,
        )
        val snapshot = baseline.copy(
            releasesByDigest = mapOf(release.descriptorSha256 to release),
        )
        val presenter = RecordingJourneyPresenter(
            actionResult = JourneyPresentationActionResult.AwaitingOutcome,
        )
        val captures = CopyOnWriteArrayList<Pair<String, Map<String, Any?>>>()
        val service = JourneyService(
            identity = identity,
            events = store,
            catalog = catalog,
            journalDirectory = directory,
            scope = scope,
            capture = { name, properties, _, _ ->
                captures += name to properties
                true
            },
            capturePresentationEvent = {
                    name,
                    properties,
                    eventId,
                    distinctId,
                    occurredAt,
                    admission,
                ->
                val event = StoredEvent(
                    eventId,
                    name,
                    JsonValueConverter.fromMap(properties),
                    occurredAt,
                    distinctId,
                )
                val settled = admission?.commitIfCurrent { true } != null
                StableEventCaptureResult(settled, event.takeIf { settled })
            },
            featureAccess = { access },
            dispatcher = JourneyEffectDispatcher(
                identity = identity,
                capture = { name, properties, _, _, admission, _ ->
                    admission.commitIfCurrent { captures += name to properties; true } == true
                },
                deliverAppAction = { _, _ -> error("Offer alternative must not invoke an app action") },
            ),
            presenter = presenter,
            nowMillis = { 100_000L },
        )
        service.initialize()
        service.onAppWillEnterForeground()
        service.profileDidCommit(snapshot, renderedAuthority, "customer", 1)

        if (access == null || access.allowed) {
            assertEquals(null, presenter.request)
            assertEquals(0, presenter.shownCount.get())
            assertTrue(presenter.actions.isEmpty())
            val journal = JourneyRunJournal(directory, "customer", JourneyStorageScope(renderedAuthority))
            assertTrue(journal.runs().isEmpty())
            assertEquals(expectedOutcome, journal.checkmark(release.identity.experienceId)?.outcome)
            assertEquals(
                if (dismissAlternative != null) listOf(JourneyEventNames.LEG_STARTED, JourneyEventNames.LEG_COMPLETED)
                else listOf(JourneyEventNames.LEG_STARTED, if (access == null) "offer_unknown" else "offer_owned", JourneyEventNames.LEG_COMPLETED),
                captures.map { it.first },
            )
            return
        }

        val request = requireNotNull(presenter.request)
        if (relativeRow) {
            val frame = ai.nuxie.sdk.runtime.NuxieViewModelSnapshot.fromNative(ai.nuxie.sdk.runtime.NativeViewModelSnapshot(1,
                (1L..4L).map { ai.nuxie.sdk.runtime.NativeViewModelSnapshotInstance(it, 0) }.toTypedArray(),
                (listOf(ai.nuxie.sdk.runtime.NativeViewModelSnapshotValue(1, 0, "rows", ai.nuxie.sdk.runtime.NuxieViewModelPropertyKind.LIST.nativeValue, byteArrayOf(), 0, listItemIds = longArrayOf(2, 3, 4))) +
                    listOf(2L to "first", 3L to "golden:monthly", 4L to "third").map { (id, value) -> ai.nuxie.sdk.runtime.NativeViewModelSnapshotValue(id, 0, "placementId", ai.nuxie.sdk.runtime.NuxieViewModelPropertyKind.STRING.nativeValue, value.encodeToByteArray(), 0) }).toTypedArray()))
            val batch = JourneyScreenEmissionBatch(request.journeyId, 0, "second-row",
                JourneyScreenEmissionSource("screen_welcome", "runtime:1"),
                listOf(JourneyScreenEmission("00000000-0000-7000-8000-000000000911", 0, 100_000L, "buy", JsonObject(emptyMap()))))
            assertTrue(request.onEmissionBatch(batch, ai.nuxie.sdk.presentation.JourneyRuntimeEmissionSources(drafts = listOf(ai.nuxie.sdk.presentation.JourneyRuntimeEventSource(3, frame))).bound(batch)) == JourneyEmissionBatchResult.ACCEPTED)
            withTimeout(5_000L) { while (presenter.actions.isEmpty()) kotlinx.coroutines.delay(10) }
            assertEquals(JsonPrimitive("golden:monthly"), presenter.actions.single().first["placementId"])
            assertEquals(3L, presenter.resolvedNativeIds.single())
        } else assertTrue(request.onScreenChanged("screen_welcome"))
        val effectId = presenter.actions.single().second
        val journal = JourneyRunJournal(
            directory,
            "customer",
            JourneyStorageScope(renderedAuthority),
        )
        assertEquals("purchase", journal.runs().single().stepId)

        val outcomeProperties = JsonObject(
            mapOf(
                "placement_id" to JsonPrimitive("golden:monthly"),
                "experience_id" to JsonPrimitive(release.identity.experienceId),
            ),
        )
        service.handleEvent(
            StoredEvent(
                "wrong-effect",
                SystemEventNames.PURCHASE_COMPLETED,
                outcomeProperties,
                100_001L,
                "customer",
            ),
            service.eventAdmissionGeneration(),
        )
        assertEquals("purchase", journal.runs().single().stepId)

        service.handleEvent(
            StoredEvent(
                effectId,
                SystemEventNames.PURCHASE_COMPLETED,
                outcomeProperties,
                100_002L,
                "customer",
            ),
            service.eventAdmissionGeneration(),
        )

        assertTrue(journal.runs().isEmpty())
        assertEquals("continue", journal.checkmark(release.identity.experienceId)?.outcome)
        assertEquals(
            listOf(JourneyEventNames.LEG_STARTED) + (if (relativeRow) listOf("buy") else emptyList()) + JourneyEventNames.LEG_COMPLETED,
            captures.map { it.first },
        )
    }

    private fun catalog(
        releaseEntry: JsonObject = entry,
    ): JourneyProfileCatalog {
        val keys = mapOf(
            "TEST_ONLY_DEV_KEYPAIR" to Base64.decode(
                fixture.getValue("publicKeyBase64").jsonPrimitive.content,
                Base64.NO_WRAP,
            ),
        )
        return JourneyProfileCatalog(keys, JourneyReleaseHighWaterStore(context)) {
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

    private class RecordingJourneyPresenter(
        @Volatile var available: Boolean = true,
        @Volatile var presentationResult: JourneyPresentationResult? = null,
        @Volatile var actionResult: JourneyPresentationActionResult? = null,
        private val beforePresent: suspend (JourneyPresentationRequest) -> Unit = {},
    ) : JourneyPresenting {
        @Volatile var request: JourneyPresentationRequest? = null
        val shutdowns = CopyOnWriteArrayList<String>()
        val shownCount = AtomicInteger()
        val actions = CopyOnWriteArrayList<Pair<JsonObject, String>>()
        var recordsOpenedLinks = false

        override fun reserve(ownerDistinctId: String): JourneyPresentationReservation? =
            if (available) Reservation() else null

        override suspend fun present(
            request: JourneyPresentationRequest,
        ): JourneyPresentationResult {
            this.request = request
            beforePresent(request)
            val selected = presentationResult
            return if (selected != null) {
                shownCount.incrementAndGet()
                selected
            } else if (request.canPresent()) {
                shownCount.incrementAndGet()
                JourneyPresentationResult.Shown
            } else {
                JourneyPresentationResult.Failed
            }
        }

        var linkHandler: (suspend (ai.nuxie.sdk.presentation.JourneyLinkRequest) -> ai.nuxie.sdk.presentation.JourneyOpenedLink?)? = null
        override suspend fun openLink(owner: JourneyPresentationOwner, link: ai.nuxie.sdk.presentation.JourneyLinkRequest): ai.nuxie.sdk.presentation.JourneyOpenedLink? {
            linkHandler?.let { return it(link) }
            actions += buildJsonObject { put("type", "open_link"); put("url", link.url); put("target", link.target) } to requireNotNull(link.effectId)
            return if (recordsOpenedLinks) link.opened("in_app") else null
        }

        override fun owns(owner: JourneyPresentationOwner): Boolean = request?.let {
            it.journeyId == owner.journeyId && it.ownerDistinctId == owner.distinctId
        } == true

        override fun screenId(owner: JourneyPresentationOwner): String? =
            request?.takeIf {
                it.journeyId == owner.journeyId && it.ownerDistinctId == owner.distinctId
            }?.screenId

        val resolvedNativeIds = mutableListOf<Long>()
        override fun resolveAction(owner: JourneyPresentationOwner, action: JsonObject,
            source: JourneyScreenEmissionSource?, eventSource: ai.nuxie.sdk.presentation.JourneyRuntimeEventSource?): JsonObject? {
            val ref = (action["placementId"] as? JsonObject)?.get("ref") as? JsonObject ?: return action
            if (ref["isRelative"] != JsonPrimitive(true)) return action
            val frame = eventSource ?: return null
            resolvedNativeIds += frame.nativeId
            val value = frame.snapshot.resolveNativeString(ref.getValue("path").jsonPrimitive.content, null, frame.nativeId) ?: return null
            return JsonObject(action + ("placementId" to JsonPrimitive(value)))
        }

        override suspend fun dispatchAction(
            owner: JourneyPresentationOwner,
            action: JsonObject,
            effectId: String,
        ): JourneyPresentationActionResult {
            actions += action to effectId
            if (recordsOpenedLinks && action["type"] == JsonPrimitive("open_link")) {
                request?.onLinkOpened?.invoke(ai.nuxie.sdk.presentation.JourneyOpenedLink(action.getValue("url").jsonPrimitive.content,
                    action.getValue("target").jsonPrimitive.content, request?.screenId, effectId = effectId, destination = "in_app"))
                return JourneyPresentationActionResult.Advanced("next")
            }
            return actionResult ?: JourneyPresentationActionResult.Failed
        }

        override suspend fun shutdownOwnedBy(ownerDistinctId: String) {
            shutdowns += ownerDistinctId
        }

        private class Reservation : JourneyPresentationReservation {
            override fun close() = Unit
        }
    }

    private fun authenticatedSnapshot(
        catalog: JourneyProfileCatalog,
    ): JourneyProfileCatalog.Snapshot {
        val prepared = catalog.prepare(profile(), authority)
        runBlocking { catalog.commit("customer", prepared) }
        return requireNotNull(catalog.snapshot("customer"))
    }

    private fun executionSnapshot(
        snapshot: JourneyProfileCatalog.Snapshot,
    ) = JourneyRun.ExecutionSnapshot(
        delivery = snapshot.profile.delivery,
        assignments = snapshot.profile.facts.getValue("assignments").jsonObject,
    )

    private fun dispatchRequest(
        identity: IdentityService,
        action: JsonObject,
    ): JourneyDispatchRequest {
        val catalog = catalog()
        val prepared = catalog.prepare(profile(), authority)
        runBlocking { catalog.commit("customer", prepared) }
        val snapshot = requireNotNull(catalog.snapshot("customer"))
        val arm = snapshot.profile.armedLegs.single()
        val release = snapshot.releasesByDigest.values.single()
        val run = JourneyRun(
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
        val fence = JourneyExecutionFence()
        return JourneyDispatchRequest(
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
        snapshot: JourneyProfileCatalog.Snapshot,
        closes: AtomicInteger,
    ): PreparedJourneyArtifacts {
        val release = snapshot.releasesByDigest.values.single()
        val riv = File.createTempFile("journey-artifact-", ".riv").apply {
            writeText("fixture")
            deleteOnExit()
        }
        return PreparedJourneyArtifacts(
            mapOf(
                release.descriptorSha256 to AcquiredJourneyRelease(
                    identity = release.identity,
                    artifactsByKey = mapOf("renders/fixture.riv" to riv),
                    sceneFile = riv,
                    artifactDigests = setOf(ARTIFACT_DIGEST),
                    protection = Closeable { closes.incrementAndGet() },
                ),
            ),
        )
    }

    private class RecordingArtifactManager : JourneyArtifactManager {
        private val retained = linkedMapOf<String, Set<String>>()

        override suspend fun prepareJourneys(
            snapshot: JourneyProfileCatalog.Snapshot,
        ): PreparedJourneyArtifacts = error("ProfileService owns preparation")

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
        renderBaseUrl: String = "https://renders.example.com/",
        assetBaseUrl: String = "https://assets.example.com/",
    ): JsonObject {
        val locator = releaseEntry.getValue("locator").jsonObject
        val envelope = releaseEntry.getValue("envelope").jsonObject
        return buildJsonObject {
            put("schemaVersion", "nuxie.journey-plane-profile.v2")
            put("status", "ok")
            putJsonObject("delivery") {
                put("renderBaseUrl", renderBaseUrl)
                put("assetBaseUrl", assetBaseUrl)
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
    ): JourneyReleaseSupportedRuntime {
        val envelope = releaseEntry.getValue("envelope").jsonObject
        val descriptor = Json.parseToJsonElement(
            Base64.decode(
                envelope.getValue("descriptorBytesBase64").jsonPrimitive.content,
                Base64.NO_WRAP,
            ).decodeToString(),
        ).jsonObject
        val requirements = descriptor["requirements"] as? JsonObject
            ?: return JourneyReleaseSupportedRuntime(
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
        return JourneyReleaseSupportedRuntime(
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

private fun testDispatcher(dispatch: suspend (JourneyDispatchRequest) -> JourneyDispatchResult): JourneyDispatching = object : JourneyDispatching {
    override suspend fun dispatch(request: JourneyDispatchRequest) = dispatch.invoke(request)
}
