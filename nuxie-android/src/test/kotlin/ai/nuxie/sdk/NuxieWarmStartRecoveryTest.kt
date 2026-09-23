package ai.nuxie.sdk

import ai.nuxie.sdk.core.NuxieCore
import ai.nuxie.sdk.events.EventRouteCommit
import ai.nuxie.sdk.events.EventStore
import ai.nuxie.sdk.events.SQLiteEventStore
import ai.nuxie.sdk.events.StoredEvent
import ai.nuxie.sdk.experiences.JourneyPlaneProfile
import ai.nuxie.sdk.identity.IdentityService
import ai.nuxie.sdk.journey.JourneyFrequency
import ai.nuxie.sdk.journey.JourneyRunJournal
import ai.nuxie.sdk.journey.JourneyStorageScope
import ai.nuxie.sdk.network.HttpTransport
import ai.nuxie.sdk.network.ProfileDeliveryAuthority
import ai.nuxie.sdk.testsupport.canonicalJourneyProfileResponse
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [23])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class NuxieWarmStartRecoveryTest {
    @get:Rule val temporary = TemporaryFolder()

    @After fun cleanup() {
        Nuxie.resetForTesting()
        Nuxie.overridesForTesting = null
    }

    @Test fun profileRefreshRetriesWarmReportsAndThePublicTrigger() = exercise(retry = true)
    @Test fun publicShutdownPreservesTheTriggerWhenWarmReportStorageFails() = exercise(retry = false)

    @Test fun identityReplacementRejectsTheHeldStartupProfile() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val oldCustomer = "old-${temporary.root.name}"
        val newCustomer = "new-${temporary.root.name}"
        val identity = IdentityService(context).apply { setDistinctId(oldCustomer) }
        val oldRequest = CompletableDeferred<Unit>()
        val newRequest = CompletableDeferred<Unit>()
        val releaseOld = CompletableDeferred<Unit>()
        val releaseNew = CompletableDeferred<Unit>()
        val transport = HttpTransport { request ->
            if (request.url.path != "/profile") HttpTransport.Response(503, ByteArray(0))
            else {
                val customer = Json.parseToJsonElement(request.body.decodeToString()).jsonObject
                    .getValue("distinct_id").jsonPrimitive.content
                val feature = when (customer) {
                    oldCustomer -> { oldRequest.complete(Unit); runBlocking { releaseOld.await() }; "old_only" }
                    newCustomer -> { newRequest.complete(Unit); runBlocking { releaseNew.await() }; "new_only" }
                    else -> error("Unexpected profile customer: $customer")
                }
                canonicalJourneyProfileResponse(
                    """[{"id":"$feature","type":"metered","balance":3,"unlimited":false}]""",
                    etag = "\"$feature\"",
                )
            }
        }
        val store = SQLiteEventStore(context, databaseFile = File(temporary.root, "identity-events.db"))
        // Load Robolectric's native SQLite before timing the identity protocol.
        // A cold native-library load must not consume an event-barrier deadline.
        store.pendingBatch(1)
        Nuxie.overridesForTesting = NuxieCore.Overrides(identity = identity, transport = transport,
            registerLifecycle = false, store = store,
            profileCacheDirectory = File(temporary.root, "identity-profiles"))
        Nuxie.setup(context, NuxieConfiguration("pk_test_warm_identity"))
        val core = checkNotNull(Nuxie.core)
        try {
            withTimeout(5_000) { oldRequest.await() }
            Nuxie.trigger("before_identity_change")
            withTimeout(5_000) { core.eventLog.awaitBarrier() }
            Nuxie.identify(newCustomer)
            withTimeout(5_000) { core.userTransitions.drain() }
            assertEquals(newCustomer, Nuxie.distinctId)
            assertNull(core.profile.currentProfile())
            releaseOld.complete(Unit)
            withTimeout(5_000) { newRequest.await() }
            // Hold the new response: the old response must not populate this gap.
            assertNull(core.profile.currentProfile())
            assertTrue(core.featureInfo.all.value.isEmpty())
            releaseNew.complete(Unit)
            withTimeout(5_000) { core.featureInfo.all.first { "new_only" in it } }
            assertEquals(newCustomer, core.profile.currentProfile()?.distinctId)
            assertFalse(core.featureInfo.all.value.containsKey("old_only"))
            core.eventLog.awaitBarrier()
            val captured = core.store.pendingBatch(100).single { it.name == "before_identity_change" }
            assertEquals(oldCustomer, captured.distinctId)
        } finally {
            releaseOld.complete(Unit)
            releaseNew.complete(Unit)
            withTimeout(5_000) { Nuxie.shutdownAndAwait() }
        }
    }

    private fun exercise(retry: Boolean) = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val customer = "warm-${temporary.root.name}"
        val identity = IdentityService(context).apply { setDistinctId(customer) }
        val authority = ProfileDeliveryAuthority("app_test", "test")
        val directory = File(context.filesDir, "nuxie")
        val journal = JourneyRunJournal(directory, customer, JourneyStorageScope(authority))
        fun record(vararg values: Pair<String, String>) = JsonObject(values.associate { it.first to JsonPrimitive(it.second) })
        // Models storage left after completion but before the report outbox drains.
        // No native screen or fresh Journey enrollment is asserted by this test.
        val arm = JourneyPlaneProfile.Arm(
            reference = record("experienceId" to "warm-experience", "versionId" to "warm-version",
                "legId" to "a".repeat(64), "descriptorSha256" to "b".repeat(64)),
            binding = record("type" to "new"),
            entryCondition = record("type" to "event", "eventName" to "unused"),
            context = JsonObject(mapOf("event" to JsonObject(emptyMap()), "responses" to JsonObject(emptyMap()))),
        )
        val retained = checkNotNull(journal.admit(arm, JourneyFrequency.EveryMatch, "completed-step", 1_000L))
        journal.complete(retained.id, "completed", 2_000L)
        val database = File(temporary.root, "events.db")
        val sqlite = SQLiteEventStore(context, databaseFile = database)
        val failReport = AtomicBoolean(true)
        val failedReport = CompletableDeferred<Unit>()
        val writes = java.util.concurrent.CopyOnWriteArrayList<String>()
        val store = object : EventStore by sqlite {
            override suspend fun insertPendingIfAbsentAndStageRoute(event: StoredEvent): EventRouteCommit {
                if (event.id == retained.startedEventId && failReport.get()) {
                    failedReport.complete(Unit)
                    throw IOException("Injected recovered-report write failure")
                }
                return sqlite.insertPendingIfAbsentAndStageRoute(event).also {
                    if (it.inserted) writes += event.id
                }
            }
        }
        val profiles = AtomicInteger()
        val transport = HttpTransport { request ->
            if (request.url.path == "/profile") canonicalJourneyProfileResponse(etag = "\"warm-${profiles.incrementAndGet()}\"")
            else HttpTransport.Response(503, ByteArray(0))
        }
        Nuxie.overridesForTesting = NuxieCore.Overrides(store = store, identity = identity,
            transport = transport, registerLifecycle = false,
            profileCacheDirectory = File(temporary.root, "profiles"))
        Nuxie.setup(context, NuxieConfiguration("pk_test_warm_start").apply { environment = NuxieEnvironment.DEVELOPMENT })
        val core = checkNotNull(Nuxie.core)
        withTimeout(5_000) { failedReport.await() }
        Nuxie.trigger("inventory_opened")
        withTimeout(5_000) { core.eventLog.awaitBarrier() }
        assertNull(sqlite.stableEvent(retained.startedEventId))
        assertEquals(1, sqlite.queryPendingLocalRoutes(customer).count { it.name == "inventory_opened" })
        if (retry) {
            failReport.set(false)
            withTimeout(5_000) {
                assertTrue(core.profile.refreshAndWait())
                core.eventLog.awaitBarrier()
            }
            assertEquals(customer, sqlite.stableEvent(retained.startedEventId)?.distinctId)
            assertEquals(customer, sqlite.stableEvent(retained.completedEventId)?.distinctId)
            assertEquals(listOf(retained.startedEventId, retained.completedEventId),
                writes.filter { it == retained.startedEventId || it == retained.completedEventId })
            assertTrue(sqlite.queryPendingLocalRoutes(customer).isEmpty())
            assertTrue(profiles.get() >= 2)
        }
        withTimeout(5_000) { Nuxie.shutdownAndAwait() }
        val reopened = SQLiteEventStore(context, databaseFile = database)
        try {
            assertEquals(if (retry) 0 else 1,
                reopened.queryPendingLocalRoutes(customer).count { it.name == "inventory_opened" })
            assertEquals(1, reopened.pendingBatch(100).count { it.name == "inventory_opened" })
        } finally { reopened.close() }
    }
}
