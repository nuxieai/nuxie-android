package ai.nuxie.sdk.billing

import ai.nuxie.sdk.LogLevel
import ai.nuxie.sdk.NuxieEnvironment
import ai.nuxie.sdk.core.NuxieCore
import ai.nuxie.sdk.features.FeatureType
import ai.nuxie.sdk.fixtures.FixtureRunner
import ai.nuxie.sdk.network.HttpTransport
import ai.nuxie.sdk.network.NuxieApi
import ai.nuxie.sdk.testsupport.featureCommandResponse
import ai.nuxie.sdk.testsupport.FakeTransport
import ai.nuxie.sdk.testsupport.canonicalJourneyProfileResponse
import android.app.Activity
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingResult
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import ai.nuxie.sdk.identity.CustomerKind

@RunWith(RobolectricTestRunner::class)
@OptIn(ExperimentalCoroutinesApi::class)
class PurchaseFeatureUseTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val cores = mutableListOf<NuxieCore>()
    private var coreSequence = 0
    private val serviceClockOrigin = System.nanoTime()

    private companion object {
        const val SERVICE_CLOCK_START = 1_784_462_400_000L
    }

    @After
    fun closeCores() {
        cores.asReversed().forEach(NuxieCore::stop)
        cores.clear()
    }

    @Test
    fun identityChangeMidFlightCommitsForTheOwnerThenCancelsTheCaller() = runBlocking {
        val fixture = concurrentFixture(firstStatus = 200)
        val use = async(Dispatchers.Default) { runCatching { fixture.use() } }
        assertTrue(fixture.transport.firstRequestStarted.await(5, TimeUnit.SECONDS))

        fixture.core.identity.setDistinctId("customer-b")
        fixture.transport.releaseFirst.countDown()

        val failure = use.await().exceptionOrNull()
        assertTrue(failure is CancellationException)
        val accepted = fixture.store.load().getValue("token-1")
        assertTrue(accepted.synced)
        assertNotNull(accepted.backendSyncedAtMillis)
        assertFalse(fixture.core.featureInfo.isAllowed("credits"))
        fixture.core.stop()
    }

    @Test
    fun evidencePayloadChangeMidFlightDoesNotMarkTheReplacementSynchronized() = runBlocking {
        val fixture = concurrentFixture(firstStatus = 200)
        val use = async(Dispatchers.Default) { runCatching { fixture.use() } }
        assertTrue(fixture.transport.firstRequestStarted.await(5, TimeUnit.SECONDS))

        fixture.store.upsert(evidence(packageName = "com.example.replaced"))
        fixture.transport.releaseFirst.countDown()

        assertNotNull("The server consumed the Feature: its result reaches the caller", use.await().getOrNull())
        val replacement = fixture.store.load().getValue("token-1")
        assertEquals("com.example.replaced", replacement.packageName)
        assertFalse(replacement.synced)
        assertEquals(null, replacement.backendSyncedAtMillis)
        fixture.core.stop()
    }

    @Test
    fun missingTokenRestoreDoesNotRevokeAClaimedAtomicSpend() = runBlocking {
        val fixture = concurrentFixture(firstStatus = 200)
        val use = async(Dispatchers.Default) { fixture.use() }
        assertTrue(fixture.transport.firstRequestStarted.await(5, TimeUnit.SECONDS))

        // Play no longer owns the purchase.
        fixture.ownedOnPlay.clear()
        assertEquals(RestoreResult.NoPurchases, fixture.service.restorePurchases())
        assertNotNull("A claimed spend keeps its owed send", fixture.store.load().getValue("token-1").pendingSend)

        fixture.transport.releaseFirst.countDown()
        assertNotNull(use.await())
        val accepted = fixture.store.load().getValue("token-1")
        assertTrue(accepted.synced)
        assertFalse(accepted.refused)
        fixture.core.stop()
    }

    @Test
    fun restoreWaitsForAClaimedSpendBeforeTakingOverItsEntry() = runBlocking {
        val fixture = concurrentFixture(firstStatus = 200)
        val use = async(Dispatchers.Default) { runCatching { fixture.use() } }
        assertTrue(fixture.transport.firstRequestStarted.await(5, TimeUnit.SECONDS))

        // A Restore claims the same purchase while the spend holds it.
        val restore = async(Dispatchers.Default) { fixture.service.restorePurchases() }
        val tookOver = withTimeoutOrNull(1_000) {
            while (fixture.store.load().getValue("token-1").pendingSend?.reason != PurchaseSyncReason.RESTORE) {
                delay(10)
            }
        }
        assertNull("The claim waits for the spend", tookOver)
        fixture.transport.releaseFirst.countDown()

        val spent = use.await()
        assertTrue("The server consumed the Feature: ${spent.exceptionOrNull()}", spent.getOrNull() != null)
        restore.await()
        fixture.core.stop()
    }

    @Test
    fun aDueEntryASpendHoldsCountsAFailedAttemptAndMovesOn() = runBlocking {
        val fixture = concurrentFixture(firstStatus = 200, outboxRetryMillis = 500)
        // The launch sent the entry and it got no answer, so it is due again in 500 ms.
        assertEquals(1, fixture.store.load().getValue("token-1").syncAttempts)

        val use = async(Dispatchers.Default) { runCatching { fixture.use() } }
        assertTrue(fixture.transport.firstRequestStarted.await(5, TimeUnit.SECONDS))
        // The spend holds the purchase when the entry falls due.
        assertEquals(1, fixture.store.load().getValue("token-1").syncAttempts)
        val movedOn = withTimeoutOrNull(3_000) {
            while (fixture.store.load().getValue("token-1").syncAttempts < 2) delay(10)
        }
        assertNotNull("The due entry it could not send counts one more attempt", movedOn)
        fixture.transport.releaseFirst.countDown()
        assertNotNull(use.await().getOrNull())
        fixture.core.stop()
    }

    @Test
    fun aConsumedCommandReturnsItsResultEvenWhenANewerAdmissionReplacedItsEntry() = runBlocking {
        val captured = mutableListOf<String>()
        val fixture = concurrentFixture(firstStatus = 200, capture = { name, _, _, _ -> captured += name; true })
        val use = async(Dispatchers.Default) { runCatching { fixture.use() } }
        assertTrue(fixture.transport.firstRequestStarted.await(5, TimeUnit.SECONDS))

        // While the command is out, a newer admission takes the purchase's entry.
        val newer = PurchaseSend.admit(PurchaseSyncReason.RESTORE, "customer-a", CustomerKind.IDENTIFIED)
        fixture.store.upsert(fixture.store.load().getValue("token-1").copy(pendingSend = newer))
        fixture.transport.releaseFirst.countDown()

        val result = use.await()
        assertNotNull("The server consumed the Feature: ${result.exceptionOrNull()}", result.getOrNull())
        assertEquals("The newer admission stays owed", newer, fixture.store.load().getValue("token-1").pendingSend)
        assertTrue("A superseded answer is not captured as purchase synced", captured.isEmpty())
        fixture.core.stop()
    }

    @Test
    fun aSupersededCommandAnswerReturnsItsResultButChangesNoFeatureAuthority() = runBlocking {
        val fixture = concurrentFixture(firstStatus = 200)
        val use = async(Dispatchers.Default) { runCatching { fixture.use() } }
        assertTrue(fixture.transport.firstRequestStarted.await(5, TimeUnit.SECONDS))

        // While the command is out, recovery changes what the entry sends (a new
        // admission), and a newer profile gives the wallet its current balance.
        val entry = fixture.store.load().getValue("token-1")
        fixture.store.upsert(entry.copy(offerId = "launch", pendingSend = checkNotNull(entry.pendingSend).readmitted()))
        fixture.core.features.hydrateProfile(
            "customer-a",
            Json.parseToJsonElement(
                """{"features":[{"id":"credits","type":"creditSystem","balance":7,"unlimited":false}]}""",
            ).jsonObject,
        )
        assertEquals(7.0, fixture.core.features.getCached("credits", null)!!.balance!!, 0.0)
        // What the app sees now: the profile's balance, plus the early unlock of the still-owed entry.
        val published = fixture.core.featureInfo.balance("credits")
        fixture.transport.releaseFirst.countDown()

        // The server consumed the Feature: the caller gets the command's result. Its
        // answer is superseded, so it changes no Feature authority.
        val result = checkNotNull(use.await().getOrThrow())
        assertTrue(result.success)
        assertEquals(1.0, result.authoritativeAccess!!.balance!!, 0.0)
        assertEquals("The newer authority stands", 7.0, fixture.core.features.getCached("credits", null)!!.balance!!, 0.0)
        assertEquals(published, fixture.core.featureInfo.balance("credits"))
        assertNotNull("The newer admission stays owed", fixture.store.load().getValue("token-1").pendingSend)
        fixture.core.stop()
    }

    @Test
    fun whileTheOutboxIsOffAPurchaseBackedUseTakesTheOrdinaryFeatureCommand() = runBlocking {
        val transport = FakeTransport()
        val core = core(transport)
        // The buyer's own owed entry is readable, but no entry can be saved (a full disk, say).
        val store = EntryWritesFailStore(evidence())
        val service = service(core, store)
        // The first entry write the outbox tries fails, so it is off for the session.
        withTimeout(5_000) { while (store.refusedWrites.get() < 1) delay(10) }

        val result = service.useFeatureWithPendingPurchase(
            distinctId = "customer-a",
            featureId = "credits",
            amount = 1.0,
            entityId = null,
            metadata = null,
        )

        assertNull("No early unlock: the use takes the ordinary Feature command", result)
        assertTrue(transport.requests.none { it.url.path == "/feature/consume" })
        core.stop()
    }

    @Test
    fun aCorruptEvidenceFileIsQuarantinedAndPlayRedeliveryRebuildsIt() = runBlocking {
        ai.nuxie.sdk.logging.NuxieLog.configure(LogLevel.WARN)
        org.robolectric.shadows.ShadowLog.clear()
        val directory = temporaryFolder.newFolder()
        val corrupt = corruptEvidenceFile(directory, "retryDueAtMillis", kotlinx.serialization.json.JsonPrimitive("soon"))
        val billing = RecordingBilling()
        val sent = java.util.concurrent.CopyOnWriteArrayList<String>()
        val core = core(FakeTransport())
        val store = FilePurchaseEvidenceStore(directory)
        val service = service(
            core,
            store,
            synchronizer = PurchaseSynchronizer { evidence, _ ->
                sent += evidence.purchaseToken
                PurchaseSyncOutcome.Accepted(purchaseResponse("customer-a"))
            },
            billing = billing,
        )

        // Play redelivers the unacknowledged purchase.
        service.recover()

        val quarantined = quarantinedFiles(directory, "purchase-evidence")
        assertEquals("The corrupt file is moved aside", 1, quarantined.size)
        assertTrue("Its bytes are kept as they were", corrupt.contentEquals(quarantined.single().readBytes()))
        // An empty store took its place and the outbox is on: the redelivered purchase
        // is saved, sent and completed with Play.
        assertNull(store.load().getValue("token-1").pendingSend)
        assertEquals(listOf("token-1"), sent.toList())
        assertEquals(1, billing.acknowledgeCalls + billing.consumeCalls)
        assertEquals(
            "One log line for the quarantine",
            1,
            org.robolectric.shadows.ShadowLog.getLogsForTag("NuxieBilling")
                .count { it.msg.contains("Quarantined an unreadable purchase evidence file.") },
        )
        core.stop()
    }

    @Test
    fun aStoreFileThatCannotBeReadNowStaysInPlaceAndIsTriedAgainNextLaunch() = runBlocking {
        val directory = temporaryFolder.newFolder()
        // A read error with no bytes read (here a directory where the file belongs).
        val unreadable = File(directory, "purchase-evidence.json").apply { mkdirs() }
        val billing = RecordingBilling()
        val sent = java.util.concurrent.CopyOnWriteArrayList<String>()
        val synchronizer = PurchaseSynchronizer { evidence, _ ->
            sent += evidence.purchaseToken
            PurchaseSyncOutcome.Accepted(purchaseResponse("customer-a"))
        }
        val core = core(FakeTransport())
        val store = FilePurchaseEvidenceStore(directory)
        service(core, store, synchronizer = synchronizer, billing = billing).recover()

        assertTrue("Nothing is moved aside", quarantinedFiles(directory, "purchase-evidence").isEmpty())
        assertTrue(unreadable.isDirectory)
        // The outbox is off for the session: the delivered purchase is sent once, unsaved.
        assertEquals(listOf("token-1"), sent.toList())
        assertTrue(store.load().isEmpty())
        assertEquals(0, billing.acknowledgeCalls + billing.consumeCalls)
        core.stop()

        // The next launch reads the store again; it reads now.
        unreadable.delete()
        sent.clear()
        val relaunched = core(FakeTransport())
        service(relaunched, FilePurchaseEvidenceStore(directory), synchronizer = synchronizer, billing = billing).recover()
        assertEquals(listOf("token-1"), sent.toList())
        assertNull(FilePurchaseEvidenceStore(directory).load().getValue("token-1").pendingSend)
        assertEquals(1, billing.acknowledgeCalls + billing.consumeCalls)
        relaunched.stop()
    }

    @Test
    fun repeatedCorruptionKeepsOnlyTheNewestQuarantinedFile() = runBlocking {
        val directory = temporaryFolder.newFolder()
        val first = corruptEvidenceFile(directory, "acknowledged", kotlinx.serialization.json.JsonPrimitive("yes"))
        val firstCore = core(FakeTransport())
        service(firstCore, FilePurchaseEvidenceStore(directory), ownedOnPlay = mutableSetOf())
        withTimeout(5_000) {
            while (quarantinedBytes(directory).none { first.contentEquals(it) }) delay(10)
        }
        firstCore.stop()

        Thread.sleep(5)
        val second = corruptEvidenceFile(directory, "retryDueAtMillis", kotlinx.serialization.json.JsonPrimitive("soon"))
        val secondCore = core(FakeTransport())
        service(secondCore, FilePurchaseEvidenceStore(directory), ownedOnPlay = mutableSetOf())
        withTimeout(5_000) {
            while (quarantinedBytes(directory).let { files -> files.size != 1 || !second.contentEquals(files.single()) }) {
                delay(10)
            }
        }
        assertEquals("Only the newest quarantined file is kept", 1, quarantinedFiles(directory, "purchase-evidence").size)
        secondCore.stop()
    }

    @Test
    fun aCorruptCatalogFileIsQuarantinedAndTheCatalogStartsEmpty() = runBlocking {
        ai.nuxie.sdk.logging.NuxieLog.configure(LogLevel.WARN)
        org.robolectric.shadows.ShadowLog.clear()
        val directory = temporaryFolder.newFolder()
        // A catalog row whose licensing key is not a string.
        val catalog = File(directory, "purchase-catalog.json").apply {
            writeText("""[{"storeProductId":"play-credit-pack","nuxieProductId":"credit-pack","productType":"inapp","licensingPublicKey":42}]""")
        }
        val corrupt = catalog.readBytes()
        val core = core(FakeTransport())
        val store = FilePurchaseEvidenceStore(directory)
        service(core, store, ownedOnPlay = mutableSetOf())
        withTimeout(5_000) { while (quarantinedFiles(directory, "purchase-catalog").isEmpty()) delay(10) }

        assertTrue(corrupt.contentEquals(quarantinedFiles(directory, "purchase-catalog").single().readBytes()))
        assertTrue("An empty catalog takes its place", store.isReadable())
        assertTrue(store.loadProductMappings().isEmpty())
        assertTrue("It is written again", store.upsertProductMapping(featureDescriptor()))
        assertEquals(
            1,
            org.robolectric.shadows.ShadowLog.getLogsForTag("NuxieBilling")
                .count { it.msg.contains("Quarantined an unreadable purchase catalog file.") },
        )
        core.stop()
    }

    /** Write the buyer's own owed entry with one field that is not of its type; returns the file's bytes. */
    private suspend fun corruptEvidenceFile(
        directory: File,
        field: String,
        value: kotlinx.serialization.json.JsonElement,
    ): ByteArray {
        val file = File(directory, "purchase-evidence.json")
        file.delete()
        assertTrue(FilePurchaseEvidenceStore(directory).upsert(evidence()))
        val rows = Json.parseToJsonElement(file.readText()).jsonObject
        file.writeText(
            kotlinx.serialization.json.JsonObject(
                rows + ("token-1" to kotlinx.serialization.json.JsonObject(rows.getValue("token-1").jsonObject + (field to value))),
            ).toString(),
        )
        return file.readBytes()
    }

    /** The quarantined entries files' bytes; one deleted while it is read is skipped. */
    private fun quarantinedBytes(directory: File): List<ByteArray> =
        quarantinedFiles(directory, "purchase-evidence").mapNotNull { runCatching { it.readBytes() }.getOrNull() }

    private fun quarantinedFiles(directory: File, store: String): List<File> =
        directory.listFiles().orEmpty().filter { it.name.startsWith("$store.unreadable-") && it.name.endsWith(".json") }

    @Test
    fun aCommandThatSettlesItsEntryStillAsksForTheRequestersProfile() = runBlocking {
        val transport = FakeTransport().apply {
            respond = { request ->
                when (request.url.path) {
                    "/feature/consume" -> featureCommandResponse(request, balance = 0.0)
                    else -> canonicalJourneyProfileResponse()
                }
            }
        }
        val core = core(transport)
        val store = InMemoryPurchaseEvidenceStore()
        store.upsert(evidence())
        val refreshes = java.util.concurrent.atomic.AtomicInteger()
        val service = service(core, store, refreshFeatureAuthority = { refreshes.incrementAndGet() })
        withTimeout(5_000) { while ((store.load().getValue("token-1").syncAttempts) < 1) delay(10) }
        val before = refreshes.get()

        assertTrue(checkNotNull(service.useFeatureWithPendingPurchase("customer-a", "credits", 1.0, null, null)).success)

        assertTrue("The command settled its entry", store.load().getValue("token-1").synced)
        assertEquals(
            "Its profile is fetched again, for the product's other Features",
            before + 1,
            refreshes.get(),
        )
        core.stop()
    }

    @Test
    fun aCommandWhoseAnswerCannotBeSavedReportsNoPurchaseSynced() = runBlocking {
        val transport = FakeTransport().apply {
            respond = { request ->
                when (request.url.path) {
                    "/feature/consume" -> featureCommandResponse(request, balance = 0.0)
                    else -> canonicalJourneyProfileResponse()
                }
            }
        }
        val core = core(transport)
        val captured = mutableListOf<String>()
        // The entry's owed send saves, but its answer does not.
        val store = AnswerWritesFailStore(evidence())
        val refreshes = java.util.concurrent.atomic.AtomicInteger()
        val service = service(
            core,
            store,
            capture = { name, _, _, _ -> captured += name; true },
            refreshFeatureAuthority = { refreshes.incrementAndGet() },
        )
        withTimeout(5_000) { while ((store.load().getValue("token-1").syncAttempts) < 1) delay(10) }
        val before = refreshes.get()

        val result = service.useFeatureWithPendingPurchase("customer-a", "credits", 1.0, null, null)

        assertTrue("The server consumed the Feature: the caller gets its result", checkNotNull(result).success)
        assertTrue("Nothing is reported before its answer is saved", captured.isEmpty())
        assertEquals("The profile is still fetched again", before + 1, refreshes.get())
        core.stop()
    }

    @Test
    fun aSendHeldBackByAFeatureCommandCountsAFailedAttemptAndIsRetried() = runBlocking {
        // The launch sent the entry once without an answer; it is due again in a second.
        val fixture = concurrentFixture(firstStatus = 503, outboxRetryMillis = 1_000)
        val use = async(Dispatchers.Default) { runCatching { fixture.use() } }
        assertTrue(fixture.transport.firstRequestStarted.await(5, TimeUnit.SECONDS))

        // A recovery while the command holds the purchase cannot send its entry.
        fixture.service.recover()
        assertEquals("Held back, it counts a failed attempt", 2, fixture.store.load().getValue("token-1").syncAttempts)

        // The command fails, so it does not settle the entry: the retry sends it.
        fixture.transport.releaseFirst.countDown()
        assertTrue(use.await().isFailure)
        val retried = withTimeoutOrNull(6_000) {
            while (fixture.store.load().getValue("token-1").syncAttempts < 3) delay(10)
        }
        assertNotNull("Its retry was scheduled by its own due time", retried)
        fixture.core.stop()
    }

    @Test
    fun aCatalogChangeRunsThePlayRecoveryQuery() = runBlocking {
        val core = core(FakeTransport())
        val store = InMemoryPurchaseEvidenceStore()
        val sent = java.util.concurrent.CopyOnWriteArrayList<String>()
        // Play owns token-1, a purchase this device has not stored.
        service(core, store, synchronizer = PurchaseSynchronizer { evidence, _ ->
            sent += evidence.purchaseToken
            PurchaseSyncOutcome.Rejected()
        })
        delay(300)
        assertTrue(sent.isEmpty())

        // A changed mapping is a catalog change: Play is asked, and what it owns is sent.
        store.upsertProductMapping(
            featureDescriptor().copy(
                featureAllowances = listOf(
                    StoredFeatureAllowance("credits", FeatureType.CREDIT_SYSTEM.name, false, allowance = 2.0),
                ),
            ),
        )
        withTimeoutOrNull(3_000) { while (sent.isEmpty()) delay(10) }
        assertEquals(listOf("token-1"), sent.distinct())
        core.stop()
    }

    @Test
    fun onlyACatalogChangeResendsKeptEntries() = runBlocking {
        val core = core(FakeTransport())
        val store = InMemoryPurchaseEvidenceStore()
        // A kept entry that failed once and is not due for an hour.
        store.upsert(evidence().copy(syncAttempts = 1, retryDueAtMillis = 1_784_462_400_000L + 3_600_000L))
        val sends = java.util.concurrent.atomic.AtomicInteger()
        service(core, store, synchronizer = PurchaseSynchronizer { _, _ ->
            sends.incrementAndGet()
            PurchaseSyncOutcome.Rejected()
        })

        // Resolving the same product again changes nothing: nothing is sent.
        store.upsertProductMapping(featureDescriptor())
        delay(300)
        assertEquals(0, sends.get())

        // A changed mapping is a catalog change: the kept entry is sent once.
        store.upsertProductMapping(
            featureDescriptor().copy(
                featureAllowances = listOf(
                    StoredFeatureAllowance("credits", FeatureType.CREDIT_SYSTEM.name, false, allowance = 2.0),
                ),
            ),
        )
        withTimeoutOrNull(3_000) { while (sends.get() < 1) delay(10) }
        delay(300)
        assertEquals(1, sends.get())
        core.stop()
    }

    @Test
    fun concurrentSpendWaitsThenReevaluatesAfterTheFirstSucceeds() = runBlocking {
        val fixture = concurrentFixture(firstStatus = 200)
        val first = async(Dispatchers.Default) { fixture.use() }
        assertTrue(fixture.transport.firstRequestStarted.await(5, TimeUnit.SECONDS))
        fixture.store.observeNextLoad()
        val second = async(Dispatchers.Default) { fixture.use() }
        assertTrue(fixture.store.nextLoadObserved.await(5, TimeUnit.SECONDS))

        assertFalse(second.isCompleted)
        assertEquals(1, fixture.transport.requestCount())
        fixture.transport.releaseFirst.countDown()

        assertNotNull(first.await())
        assertEquals(null, second.await())
        assertEquals(1, fixture.transport.requestCount())
        fixture.core.stop()
    }

    @Test
    fun failedAtomicSpendReleasesTheClaimForAWaitingCaller() = runBlocking {
        val fixture = concurrentFixture(firstStatus = 503)
        val first = async(Dispatchers.Default) { runCatching { fixture.use() } }
        assertTrue(fixture.transport.firstRequestStarted.await(5, TimeUnit.SECONDS))
        fixture.store.observeNextLoad()
        val second = async(Dispatchers.Default) { fixture.use() }
        assertTrue(fixture.store.nextLoadObserved.await(5, TimeUnit.SECONDS))

        fixture.transport.releaseFirst.countDown()

        assertTrue(first.await().isFailure)
        assertNotNull(second.await())
        assertEquals(2, fixture.transport.requestCount())
        val eventIds = fixture.transport.requestBodies().map { body ->
            Json.parseToJsonElement(body).jsonObject.getValue("operationId").toString().trim('"')
        }
        assertEquals(1, eventIds.distinct().size)
        fixture.core.stop()
    }

    @Test
    fun waitingCallerRechecksExactlyOneAfterClaimHandoff() = runBlocking {
        val fixture = concurrentFixture(firstStatus = 503)
        val first = async(Dispatchers.Default) { runCatching { fixture.use() } }
        assertTrue(fixture.transport.firstRequestStarted.await(5, TimeUnit.SECONDS))
        fixture.store.observeNextLoad()
        val second = async(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) { fixture.use() }
        assertTrue(fixture.store.nextLoadObserved.await(5, TimeUnit.SECONDS))
        assertFalse(second.isCompleted)

        fixture.store.serveCurrentSnapshotOnNextLoad()
        fixture.store.upsert(evidence(token = "token-2"))
        fixture.transport.releaseFirst.countDown()
        assertTrue(first.await().isFailure)

        assertEquals(null, second.await())
        assertEquals(1, fixture.transport.requestCount())
        fixture.core.stop()
    }

    @Test
    fun inFlightSuccessfulSyncIsAwaitedThenFallsBack() = runTest {
        val fixture = syncFixture(PurchaseSyncOutcome.Accepted(purchaseResponse("customer-a")))
        val recovery = async { fixture.service.recover() }
        fixture.synchronizer.started.await()

        val use = async { fixture.use() }
        runCurrent()
        assertFalse(use.isCompleted)
        fixture.synchronizer.release.complete(Unit)

        recovery.await()
        assertEquals(null, use.await())
        assertTrue(fixture.transport.requests.none { it.url.path == "/feature/consume" })
        fixture.core.stop()
    }

    @Test
    fun inFlightFailedSyncIsAwaitedThenTheAtomicGateRuns() = runTest {
        val fixture = syncFixture(PurchaseSyncOutcome.Rejected())
        val recovery = async { fixture.service.recover() }
        fixture.synchronizer.started.await()

        val use = async { fixture.use() }
        runCurrent()
        assertFalse(use.isCompleted)
        fixture.synchronizer.release.complete(Unit)

        recovery.await()
        assertNotNull(use.await())
        assertEquals(1, fixture.transport.requests.count { it.url.path == "/feature/consume" })
        fixture.core.stop()
    }

    @Test
    fun multipleEligiblePurchasesFallBackInsteadOfChoosingFirst() = runTest {
        val fixture = fallbackFixture()
        fixture.store.upsert(evidence(token = "token-1"))
        fixture.store.upsert(evidence(token = "token-2"))

        assertEquals(null, fixture.use())
        assertTrue(fixture.transport.requests.none { it.url.path == "/feature/consume" })
        fixture.core.stop()
    }

    @Test
    fun foreignOwnerEvidenceIsNotEligible() = assertDisqualified(
        evidence(buyerDistinctId = "customer-b"),
    )

    @Test
    fun foreignAuthorityScopeEvidenceIsNotEligible() = assertDisqualified(
        evidence(authorityScope = "scope-b"),
    )

    @Test
    fun anOwedPurchasePlayNoLongerReturnsStaysEligible() = runTest {
        // Play's queries no longer return the purchase (a refund, say). Its entry
        // is still owed, so the spend goes to the server, which checks it with Google.
        val fixture = fallbackFixture(ownedOnPlay = mutableSetOf())
        fixture.transport.respond = { request ->
            if (request.url.path == "/feature/consume") {
                featureCommandResponse(request, balance = 1.0)
            } else {
                canonicalJourneyProfileResponse()
            }
        }
        fixture.store.upsert(evidence())
        fixture.service.recover()

        assertNotNull(fixture.use())
        assertEquals(1, fixture.transport.requests.count { it.url.path == "/feature/consume" })
        fixture.core.stop()
    }

    @Test
    fun permanentlyRejectedEvidenceIsNotEligible() = assertDisqualified(
        evidence(refused = true),
    )

    @Test
    fun synchronizedEvidenceIsNotEligible() = assertDisqualified(
        evidence(synced = true),
    )

    @Test
    fun evidenceWithBackendSyncTimestampIsNotEligible() = assertDisqualified(
        evidence(backendSyncedAtMillis = 1_784_462_350_000L),
    )

    @Test
    fun evidenceWithoutAPlayPayloadIsNotEligible() = assertDisqualified(
        evidence(token = ""),
    )

    @Test
    fun evidenceWithoutAPackageNameIsNotEligible() = assertDisqualified(
        evidence(packageName = ""),
    )

    @Test
    fun evidenceWithoutAProductIdIsNotEligible() = assertDisqualified(
        evidence(storeProductIds = listOf("")),
    )

    @Test
    fun evidenceWithoutTheRequestedFeatureIsNotEligible() = assertDisqualified(
        evidence(storeProductIds = listOf("play-other-pack")),
    )

    @Test
    fun appManagedEvidenceIsEligibleForAtomicGateWithoutManagedCompletion() = runTest {
        val transport = FakeTransport().apply {
            respond = { request ->
                if (request.url.path == "/feature/consume") {
                    featureCommandResponse(request, balance = 1.0)
                } else {
                    canonicalJourneyProfileResponse()
                }
            }
        }
        val core = core(transport)
        val store = InMemoryPurchaseEvidenceStore().also {
            it.upsert(evidence(nuxieManaged = false))
        }
        val billing = RecordingBilling()
        val service = service(core, store, billing = billing)

        assertNotNull(
            service.useFeatureWithPendingPurchase(
                distinctId = "customer-a",
                featureId = "credits",
                amount = 1.0,
                entityId = null,
                metadata = null,
            ),
        )

        assertEquals(1, transport.requests.count { it.url.path == "/feature/consume" })
        assertTrue(store.load().getValue("token-1").synced)
        assertEquals(0, billing.acknowledgeCalls)
        assertEquals(0, billing.consumeCalls)
        core.stop()
    }

    @Test
    fun pendingEvidenceIsNotEligible() = assertDisqualified(
        evidence(purchaseState = StoredPurchaseState.PENDING),
    )

    @Test
    fun unverifiedRequiredSignatureEvidenceIsNotEligible() = runTest {
        val fixture = fallbackFixture()
        // The current catalog's licensing key requires the signature.
        fixture.store.upsertProductMapping(featureDescriptor().copy(licensingPublicKey = "catalog-key"))
        fixture.store.upsert(evidence(signatureVerified = false))

        assertEquals(null, fixture.use())
        assertTrue(fixture.transport.requests.none { it.url.path == "/feature/consume" })
        fixture.core.stop()
    }

    @Test
    fun noLicensingKeyEvidenceUsesThePendingPurchaseSpendPathWhenUnverified() = runTest {
        val fixture = fallbackFixture()
        fixture.transport.respond = { request ->
            if (request.url.path == "/feature/consume") {
                featureCommandResponse(request, balance = 1.0)
            } else {
                canonicalJourneyProfileResponse()
            }
        }
        fixture.store.upsert(evidence(signatureVerified = false))

        assertNotNull(fixture.use())
        assertEquals(1, fixture.transport.requests.count { it.url.path == "/feature/consume" })
        fixture.core.stop()
    }

    @Test
    fun singleEligiblePurchaseIsVerifiedAndSpentAtomically() = runTest {
        val contract = Json.parseToJsonElement(
            File(FixtureRunner.fixturesRoot(), "events/atomic-purchase-sync.json").readText(),
        ).jsonObject.getValue("acceptance").jsonObject
        val postUseAccess = contract.getValue("post_use_access").jsonObject
        val transport = FakeTransport().apply {
            respond = { request ->
                when (request.url.path) {
                    "/feature/consume" -> featureCommandResponse(request, balance = 0.0)
                    else -> canonicalJourneyProfileResponse()
                }
            }
        }
        val core = core(transport)
        val store = InMemoryPurchaseEvidenceStore()
        store.upsert(evidence(context = StoredPurchaseContext("placement-1", "experience-1", "version-1")))
        val captured = mutableListOf<CapturedEvent>()
        val service = service(core, store, capture = { name, properties, eventId, distinctId ->
            captured += CapturedEvent(name, properties, eventId, distinctId)
            true
        })

        val result = service.useFeatureWithPendingPurchase(
            distinctId = "customer-a",
            featureId = "credits",
            amount = 2.0,
            entityId = "workspace-1",
            metadata = mapOf("source" to "export"),
        )

        assertNotNull(result)
        assertTrue(result!!.success)
        assertEquals("credits", result.featureId)
        assertEquals(2.0, result.amountUsed, 0.0)
        assertEquals(
            postUseAccess.getValue("allowed_after_final_finite_unit").jsonPrimitive.boolean,
            result.authoritativeAccess!!.allowed,
        )
        assertEquals(
            postUseAccess.getValue("balance_after_final_finite_unit").jsonPrimitive.double,
            result.authoritativeAccess!!.balance!!,
            0.0,
        )
        assertFalse(contract.getValue("ordinary_usage_fallback").jsonPrimitive.boolean)
        val accepted = store.load().getValue("token-1")
        assertTrue(accepted.synced)
        assertNotNull(accepted.backendSyncedAtMillis)
        assertTrue(accepted.syncedEventEmitted)
        assertEquals(
            mapOf(
                "transaction_id" to "token-1",
                "original_transaction_id" to "token-1",
                "product_id" to "play-credit-pack",
                "customer_id" to "customer-a",
                "experience_id" to "experience-1",
                "experience_version_id" to "version-1",
            ),
            captured.single().properties,
        )
        assertEquals("\$purchase_synced", captured.single().name)
        assertEquals("customer-a", captured.single().distinctId)
        assertTrue(captured.single().eventId.startsWith("purchase-synced:"))
        val request = transport.requests.single { it.url.path == "/feature/consume" }
        val body = Json.parseToJsonElement(request.body.decodeToString()).jsonObject
        assertTrue(body.getValue("operationId").jsonPrimitive.content.startsWith("purchase-use:"))
        assertEquals("token-1", body.getValue("purchase").jsonObject.getValue("purchaseToken").jsonPrimitive.content)
        core.stop()
    }

    private fun core(transport: HttpTransport): NuxieCore {
        val storageDirectory = temporaryFolder.newFolder("core-${++coreSequence}")
        return NuxieCore(
            context = RuntimeEnvironment.getApplication(),
            apiKey = "pk_test_purchase_feature_use",
            environment = NuxieEnvironment.DEVELOPMENT,
            logLevel = LogLevel.NONE,
            beforeSend = null,
            overrides = NuxieCore.Overrides(
                transport = transport,
                registerLifecycle = false,
                purchaseEvidenceStore = InMemoryPurchaseEvidenceStore(),
                eventDatabaseFile = File(storageDirectory, "events.db"),
                profileCacheDirectory = File(storageDirectory, "profiles"),
            ),
        ).also {
            it.identity.setDistinctId("customer-a")
            runBlocking { it.purchases.awaitInitialProjection() }
            cores += it
        }
    }

    private fun service(
        core: NuxieCore,
        store: PurchaseEvidenceStore,
        capture: suspend (String, Map<String, Any?>, String, String) -> Boolean = { _, _, _, _ -> true },
        synchronizer: PurchaseSynchronizer = PurchaseSynchronizer { _, _ ->
            PurchaseSyncOutcome.Rejected()
        },
        billing: PlayBillingGateway = NoopBilling,
        ownedOnPlay: MutableSet<String> = mutableSetOf("token-1"),
        outboxRetryMillis: Long = 30_000,
        refreshFeatureAuthority: () -> Unit = {},
    ): PurchaseService {
        kotlinx.coroutines.runBlocking { store.upsertProductMapping(featureDescriptor()) }
        return PurchaseService(
            ioDispatcher = kotlinx.coroutines.Dispatchers.Unconfined,
            billing = OwningBilling(billing, ownedOnPlay),
            evidenceStore = store,
            synchronizer = synchronizer,
            features = core.features,
            distinctId = core.identity::distinctId,
            customerKind = core.identity::customerKind,
            emit = { _, _ -> },
            settings = PurchaseSettings(null, PurchaseHandlingMode.NUXIE_MANAGED),
            scope = core.scope,
            // The clock moves with real time, as the retry timers' delays do: a timer
            // sends only once the entry's stored due time has passed.
            nowMillis = { SERVICE_CLOCK_START + (System.nanoTime() - serviceClockOrigin) / 1_000_000 },
            api = core.api,
            purchaseStorageScope = "scope-a",
            capturePurchaseSynced = capture,
            outboxRetryInitialDelayMillis = outboxRetryMillis,
            outboxRetryMaxDelayMillis = outboxRetryMillis * 4,
            refreshFeatureAuthority = refreshFeatureAuthority,
        ).also { it.start() }
    }

    private fun evidence(
        token: String = "token-1",
        authorityScope: String = "scope-a",
        buyerDistinctId: String = "customer-a",
        packageName: String = "com.example.app",
        storeProductIds: List<String> = listOf("play-credit-pack"),
        purchaseState: StoredPurchaseState = StoredPurchaseState.PURCHASED,
        nuxieManaged: Boolean = true,
        refused: Boolean = false,
        synced: Boolean = false,
        backendSyncedAtMillis: Long? = null,
        signatureVerified: Boolean = true,
        context: StoredPurchaseContext? = null,
    ) = PurchaseEvidence(
        purchaseToken = token,
        authorityScope = authorityScope,
        packageName = packageName,
        storeProductIds = storeProductIds,
        nuxieProductId = "credit-pack",
        productType = BillingClient.ProductType.INAPP,
        purchaseState = purchaseState,
        obfuscatedAccountId = playAccountId(buyerDistinctId),
        buyerDistinctId = buyerDistinctId,
        // A backend sync time means the server accepted it for its buyer.
        ownerDistinctId = buyerDistinctId.takeIf { synced || backendSyncedAtMillis != null },
        refused = refused,
        pendingSend = PurchaseSend(PurchaseSyncReason.PURCHASE, buyerDistinctId, CustomerKind.ANONYMOUS)
            .takeUnless { refused || synced || backendSyncedAtMillis != null },
        acknowledged = false,
        firstSeenMillis = 1_784_462_300_000L,
        catalogResolved = true,
        nuxieManaged = nuxieManaged,
        backendSyncedAtMillis = backendSyncedAtMillis,
        signatureVerified = signatureVerified,
        context = context,
    )

    private fun featureDescriptor() = StoredProductMapping(
        storeProductId = "play-credit-pack",
        nuxieProductId = "credit-pack",
        productType = BillingClient.ProductType.INAPP,
        consumable = true,
        featureAllowances = listOf(
            StoredFeatureAllowance("credits", FeatureType.CREDIT_SYSTEM.name, false, allowance = 1.0),
        ),
    )

    private fun assertDisqualified(disqualified: PurchaseEvidence) = runTest {
        val fixture = fallbackFixture()
        fixture.store.upsert(disqualified)

        assertEquals(null, fixture.use())
        assertTrue(fixture.transport.requests.none { it.url.path == "/feature/consume" })
        fixture.core.stop()
    }

    private fun fallbackFixture(ownedOnPlay: MutableSet<String> = mutableSetOf("token-1")): FallbackFixture {
        val transport = FakeTransport()
        val core = core(transport)
        val store = InMemoryPurchaseEvidenceStore()
        return FallbackFixture(core, transport, store, service(core, store, ownedOnPlay = ownedOnPlay))
    }

    private suspend fun syncFixture(outcome: PurchaseSyncOutcome): SyncFixture {
        val transport = FakeTransport().apply {
            respond = { request ->
                if (request.url.path == "/feature/consume") {
                    featureCommandResponse(request, balance = 1.0)
                } else {
                    canonicalJourneyProfileResponse()
                }
            }
        }
        val core = core(transport)
        val store = InMemoryPurchaseEvidenceStore().also { it.upsert(evidence()) }
        val synchronizer = ControlledSynchronizer(outcome)
        return SyncFixture(
            core,
            transport,
            service(core, store, synchronizer = synchronizer, billing = ActiveBilling),
            synchronizer,
        )
    }

    private fun concurrentFixture(
        firstStatus: Int,
        ownedOnPlay: MutableSet<String> = mutableSetOf("token-1"),
        outboxRetryMillis: Long = 30_000,
        capture: suspend (String, Map<String, Any?>, String, String) -> Boolean = { _, _, _, _ -> true },
    ): ConcurrentFixture {
        val transport = ControlledAtomicTransport(firstStatus)
        val core = core(transport)
        val store = ObservingEvidenceStore(evidence())
        val service = service(core, store, capture = capture, ownedOnPlay = ownedOnPlay, outboxRetryMillis = outboxRetryMillis)
        // The launch sends the stored entry; it gets no answer. Settle that first.
        kotlinx.coroutines.runBlocking {
            withTimeout(5_000) { while ((store.load()["token-1"]?.syncAttempts ?: 0) < 1) delay(10) }
        }
        return ConcurrentFixture(core, transport, store, service, ownedOnPlay)
    }

    private data class FallbackFixture(
        val core: NuxieCore,
        val transport: FakeTransport,
        val store: InMemoryPurchaseEvidenceStore,
        val service: PurchaseService,
    ) {
        suspend fun use() = service.useFeatureWithPendingPurchase(
            distinctId = "customer-a",
            featureId = "credits",
            amount = 1.0,
            entityId = null,
            metadata = null,
        )
    }

    private data class SyncFixture(
        val core: NuxieCore,
        val transport: FakeTransport,
        val service: PurchaseService,
        val synchronizer: ControlledSynchronizer,
    ) {
        suspend fun use() = service.useFeatureWithPendingPurchase(
            distinctId = "customer-a",
            featureId = "credits",
            amount = 1.0,
            entityId = null,
            metadata = null,
        )
    }

    private data class ConcurrentFixture(
        val core: NuxieCore,
        val transport: ControlledAtomicTransport,
        val store: ObservingEvidenceStore,
        val service: PurchaseService,
        /** The tokens Play returns to its next query. */
        val ownedOnPlay: MutableSet<String>,
    ) {
        suspend fun use() = service.useFeatureWithPendingPurchase(
            distinctId = "customer-a",
            featureId = "credits",
            amount = 1.0,
            entityId = null,
            metadata = null,
        )
    }

    /** Saves an entry's owed send, but never an answer (an entry without a send). */
    private class AnswerWritesFailStore(initial: PurchaseEvidence) : PurchaseEvidenceStore {
        private val delegate = InMemoryPurchaseEvidenceStore()

        init {
            runBlocking { delegate.upsert(initial) }
        }

        override suspend fun load(): Map<String, PurchaseEvidence> = delegate.load()

        override suspend fun upsert(evidence: PurchaseEvidence): Boolean =
            evidence.pendingSend != null && delegate.upsert(evidence)

        override suspend fun loadBindings(): List<StoredPurchaseBinding> = delegate.loadBindings()

        override suspend fun upsertBinding(binding: StoredPurchaseBinding): Boolean = delegate.upsertBinding(binding)

        override suspend fun loadProductMappings(): List<StoredProductMapping> = delegate.loadProductMappings()

        override suspend fun upsertProductMapping(mapping: StoredProductMapping, catalogChange: Boolean): Boolean =
            delegate.upsertProductMapping(mapping, catalogChange)
    }

    /** Reads its entries and installs the catalog, but cannot save an entry. */
    private class EntryWritesFailStore(initial: PurchaseEvidence) : PurchaseEvidenceStore {
        private val delegate = InMemoryPurchaseEvidenceStore()
        val refusedWrites = java.util.concurrent.atomic.AtomicInteger()

        init {
            runBlocking { delegate.upsert(initial) }
        }

        override suspend fun load(): Map<String, PurchaseEvidence> = delegate.load()

        override suspend fun upsert(evidence: PurchaseEvidence): Boolean {
            refusedWrites.incrementAndGet()
            return false
        }

        override suspend fun loadBindings(): List<StoredPurchaseBinding> = delegate.loadBindings()

        override suspend fun upsertBinding(binding: StoredPurchaseBinding): Boolean = delegate.upsertBinding(binding)

        override suspend fun loadProductMappings(): List<StoredProductMapping> = delegate.loadProductMappings()

        override suspend fun upsertProductMapping(mapping: StoredProductMapping, catalogChange: Boolean): Boolean =
            delegate.upsertProductMapping(mapping, catalogChange)
    }

    private class ObservingEvidenceStore(initial: PurchaseEvidence) : PurchaseEvidenceStore {
        private val entries = linkedMapOf(initial.purchaseToken to initial)
        @Volatile private var observe = false
        private var nextLoadSnapshot: Map<String, PurchaseEvidence>? = null
        val nextLoadObserved = CountDownLatch(1)

        fun observeNextLoad() {
            observe = true
        }

        fun serveCurrentSnapshotOnNextLoad() = synchronized(entries) {
            nextLoadSnapshot = entries.toMap()
        }

        override suspend fun load(): Map<String, PurchaseEvidence> = synchronized(entries) {
            if (observe) {
                observe = false
                nextLoadObserved.countDown()
            }
            val snapshot = nextLoadSnapshot
            nextLoadSnapshot = null
            snapshot ?: entries.toMap()
        }

        override suspend fun upsert(evidence: PurchaseEvidence): Boolean = synchronized(entries) {
            entries[evidence.purchaseToken] = evidence
            true
        }

        private var mapping: StoredProductMapping? = null

        override suspend fun loadProductMappings(): List<StoredProductMapping> = listOfNotNull(mapping)

        override suspend fun upsertProductMapping(mapping: StoredProductMapping, catalogChange: Boolean): Boolean {
            this.mapping = mapping
            return true
        }
    }

    private class ControlledAtomicTransport(
        private val firstStatus: Int,
    ) : HttpTransport {
        val firstRequestStarted = CountDownLatch(1)
        val releaseFirst = CountDownLatch(1)
        private val requests = mutableListOf<HttpTransport.Request>()

        override fun execute(request: HttpTransport.Request): HttpTransport.Response {
            if (request.url.path != "/feature/consume") {
                return canonicalJourneyProfileResponse()
            }
            val requestNumber = synchronized(requests) {
                requests += request
                requests.size
            }
            if (requestNumber == 1) {
                firstRequestStarted.countDown()
                check(releaseFirst.await(5, TimeUnit.SECONDS)) { "Timed out waiting to release atomic request" }
                if (firstStatus !in 200..299) {
                    return HttpTransport.Response(firstStatus, ByteArray(0))
                }
            }
            return featureCommandResponse(request, balance = 1.0)
        }

        fun requestCount(): Int = synchronized(requests) { requests.size }
        fun requestBodies(): List<String> = synchronized(requests) {
            requests.map { it.body.decodeToString() }
        }
    }

    private class ControlledSynchronizer(
        private val outcome: PurchaseSyncOutcome,
    ) : PurchaseSynchronizer {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()

        override suspend fun sync(
            evidence: PurchaseEvidence,
            request: PurchaseSyncRequest,
        ): PurchaseSyncOutcome {
            started.complete(Unit)
            release.await()
            return outcome
        }
    }

    private fun purchaseResponse(customerId: String) = NuxieApi.PurchaseResponse(
        body = Json.parseToJsonElement(
            """{"success":true,"customer_id":"$customerId","features":[]}""",
        ).jsonObject,
        success = true,
        customerId = customerId,
        catalogProduct = NuxieApi.VerifiedCatalogProduct(
            productId = "credit-pack",
            storeProductId = "play-credit-pack",
            basePlanId = null,
            purchaseOptionId = null,
            offerId = null,
            storeProductType = "nonConsumable",
        ),
    )

    private data class CapturedEvent(
        val name: String,
        val properties: Map<String, Any?>,
        val eventId: String,
        val distinctId: String,
    )

    /** Play returns [owned] as active one-time purchases, besides what [delegate] returns. */
    private class OwningBilling(
        private val delegate: PlayBillingGateway,
        private val owned: MutableSet<String>,
    ) : PlayBillingGateway by delegate {
        override suspend fun queryActive(productType: String): ActivePurchasesResult {
            val result = delegate.queryActive(productType)
            if (result !is ActivePurchasesResult.Success || productType != BillingClient.ProductType.INAPP) return result
            val known = result.purchases.mapTo(mutableSetOf()) { it.purchaseToken }
            return ActivePurchasesResult.Success(
                result.purchases + (owned - known).map { token ->
                    PlayPurchase(
                        purchaseToken = token,
                        packageName = "com.example.app",
                        products = listOf("play-credit-pack"),
                        state = StoredPurchaseState.PURCHASED,
                        acknowledged = false,
                        obfuscatedAccountId = playAccountId("customer-a"),
                        originalJson = "{}",
                        signature = "",
                    )
                },
            )
        }
    }

    private object NoopBilling : PlayBillingGateway {
        override suspend fun launch(activity: Activity, request: CheckoutRequest): BillingResult = ok()
        override suspend fun queryActive(productType: String): ActivePurchasesResult =
            ActivePurchasesResult.Success(emptyList())
        override suspend fun acknowledge(purchaseToken: String): BillingResult = ok()
        override suspend fun consume(purchaseToken: String): BillingResult = ok()

        private fun ok() = BillingResult.newBuilder()
            .setResponseCode(BillingClient.BillingResponseCode.OK)
            .build()
    }

    private class RecordingBilling : PlayBillingGateway {
        var acknowledgeCalls = 0
        var consumeCalls = 0

        override suspend fun launch(activity: Activity, request: CheckoutRequest): BillingResult = ok()
        override suspend fun queryActive(productType: String): ActivePurchasesResult =
            ActivePurchasesResult.Success(emptyList())
        override suspend fun acknowledge(purchaseToken: String): BillingResult {
            acknowledgeCalls += 1
            return ok()
        }
        override suspend fun consume(purchaseToken: String): BillingResult {
            consumeCalls += 1
            return ok()
        }

        private fun ok() = BillingResult.newBuilder()
            .setResponseCode(BillingClient.BillingResponseCode.OK)
            .build()
    }

    private object ActiveBilling : PlayBillingGateway {
        private val purchase = PlayPurchase(
            purchaseToken = "token-1",
            packageName = "com.example.app",
            products = listOf("play-credit-pack"),
            state = StoredPurchaseState.PURCHASED,
            acknowledged = false,
            // The buyer's own purchase names the account the SDK set at their checkout.
            obfuscatedAccountId = playAccountId("customer-a"),
            originalJson = "{}",
            signature = "",
        )

        override suspend fun launch(activity: Activity, request: CheckoutRequest): BillingResult = ok()
        override suspend fun queryActive(productType: String): ActivePurchasesResult =
            ActivePurchasesResult.Success(
                if (productType == BillingClient.ProductType.INAPP) listOf(purchase) else emptyList(),
            )
        override suspend fun acknowledge(purchaseToken: String): BillingResult = ok()
        override suspend fun consume(purchaseToken: String): BillingResult = ok()

        private fun ok() = BillingResult.newBuilder()
            .setResponseCode(BillingClient.BillingResponseCode.OK)
            .build()
    }
}
