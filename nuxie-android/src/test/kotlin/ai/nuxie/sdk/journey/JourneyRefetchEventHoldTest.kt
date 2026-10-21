package ai.nuxie.sdk.journey

import ai.nuxie.sdk.LogLevel
import ai.nuxie.sdk.NuxieEnvironment
import ai.nuxie.sdk.events.EventLog
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
import android.util.Base64
import java.io.File
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.Signature
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
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
    @Test fun `a late event does not strand a wait that is due`() = runBlocking {
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
        withTimeout(5_000L) {
            while (launch.outcomes("customer", "timed_out") == 0) delay(20)
        }
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
     * The copy lands while a go sent during the hold is still in EventLog's
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
        launch.journeys.profileRefetchWillBegin("customer")
        launch.withdraw("customer")
        launch.eventLog.capture("go")
        withTimeout(5_000L) { goReached.await() }
        launch.clock.addAndGet(70_000L)

        launch.commit("customer", saved)
        assertEquals(0, launch.outcomes("customer", "timed_out"))

        releaseGo.complete(Unit)
        launch.journeys.profileRefetchDidSettle("customer")
        launch.eventLog.awaitBarrier()
        withTimeout(5_000L) {
            while (launch.outcomes("customer", "took_go") + launch.outcomes("customer", "timed_out") == 0) delay(20)
        }
        assertEquals(1, launch.outcomes("customer", "took_go"))
        assertEquals(0, launch.outcomes("customer", "timed_out"))
    }

    /** One launch: Journey subscribed to a real EventLog as NuxieCore subscribes it. */
    private inner class Launch(
        distinctId: String,
        /** Runs in EventLog's route worker before Journey sees each event. */
        private val beforeJourney: (suspend (StoredEvent) -> Unit)? = null,
    ) {
        val clock = AtomicLong(1_000_000L)
        val identity = MutableIdentity(distinctId)
        private val store = SQLiteEventStore(context, nowMillis = { clock.get() })
        val catalog = JourneyProfileCatalog(
            mapOf(KEY_ID to keys.public.encoded.takeLast(32).toByteArray()),
            JourneyReleaseHighWaterStore(context),
        ) { runtime() }
        private val reports = CopyOnWriteArrayList<Pair<String, Map<String, Any?>>>()
        val eventLog = EventLog(
            store,
            NuxieContextBuilder(context, NuxieEnvironment.DEVELOPMENT, LogLevel.DEBUG, identity),
            identity,
            beforeSend = null,
            scope = scope,
            nowMillis = { clock.get() },
        )
        val journeys = JourneyService(
            identity = identity,
            events = store,
            catalog = catalog,
            journalDirectory = directory,
            scope = scope,
            capture = { name, properties, eventId, owner ->
                reports += name to (properties + ("distinct_id" to owner))
                eventLog.captureIdempotently(name, properties, eventId, owner)
            },
            nowMillis = { clock.get() },
            replayPendingLocalRoutes = eventLog::replayPendingLocalRoutes,
            awaitEventsDrained = eventLog::awaitBarrier,
            fixedStorageScope = JourneyStorageScope(authority),
        )
        private var publication = 0L

        init {
            launches += this
            beforeJourney?.let { hook -> eventLog.subscribeCommitted { event -> hook(event) } }
            eventLog.subscribeCommittedWithAdmission(sampleGeneration = journeys::eventAdmissionGeneration) { event, generation ->
                journeys.handleEvent(event, generation)
            }
        }

        suspend fun start() {
            journeys.initialize()
            journeys.onAppWillEnterForeground()
        }

        /** A signed copy whose Journey starts on [entryEvent] and waits a minute for go. */
        suspend fun copy(owner: String, sequence: Int, entryEvent: String): JourneyProfileCatalog.Snapshot {
            val condition = buildJsonObject {
                put("type", "event")
                put("eventName", entryEvent)
            }
            val publication = waitingPublication(sequence, condition)
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

        suspend fun close() {
            if (!launches.remove(this)) return
            eventLog.close()
            store.close()
        }
    }

    private class MutableIdentity(@Volatile var distinctId: String) : IdentityProvider {
        override fun distinctId() = distinctId
        override fun anonymousId() = distinctId
        override fun rawDistinctId(): String? = null
        override val isIdentified = false
    }

    private fun waitingPublication(sequence: Int, condition: JsonObject): JsonObject {
        val envelope = entry.getValue("envelope").jsonObject
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
        val leg = JsonObject(source.getValue("leg").jsonObject + mapOf(
            "entryCondition" to condition,
            "policy" to buildJsonObject {
                putJsonObject("entry") {
                    put("trigger", condition)
                    putJsonObject("frequency") { put("type", "every_match") }
                }
                putJsonArray("exitWhenAny") {}
            },
            "entryStepId" to JsonPrimitive("wait"),
            "completionOutputs" to buildJsonObject {
                put("took_go", boundary)
                put("timed_out", boundary)
            },
            "steps" to buildJsonArray {
                addJsonObject {
                    put("id", "wait")
                    put("kind", "action")
                    putJsonObject("action") {
                        put("type", "wait_until")
                        putJsonObject("trigger") {
                            put("kind", "event")
                            put("eventName", "go")
                        }
                        putJsonObject("condition") {
                            put("type", "Truthy")
                            putJsonObject("value") {
                                put("type", "Boolean")
                                put("value", true)
                            }
                        }
                        put("maxTimeMs", 60_000)
                    }
                    putJsonObject("outlets") {
                        put("satisfied", "took_go")
                        put("timeout", "timed_out")
                    }
                }
                addJsonObject { put("id", "took_go"); put("kind", "complete"); put("outcome", "took_go") }
                addJsonObject { put("id", "timed_out"); put("kind", "complete"); put("outcome", "timed_out") }
            },
        ))
        val descriptor = JsonObject(source + mapOf("identity" to changedIdentity, "leg" to leg))
        val bytes = descriptor.toString().encodeToByteArray()
        val signature = Signature.getInstance("Ed25519").run {
            initSign(keys.private)
            update(JourneyReleaseLimits.SIGNATURE_DOMAIN.encodeToByteArray() + bytes)
            sign()
        }
        return JsonObject(entry + mapOf(
            "locator" to JsonObject(entry.getValue("locator").jsonObject + changedIdentity),
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

    private fun runtime(): JourneyReleaseSupportedRuntime {
        val descriptor = Json.parseToJsonElement(Base64.decode(
            entry.getValue("envelope").jsonObject.getValue("descriptorBytesBase64").jsonPrimitive.content,
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
