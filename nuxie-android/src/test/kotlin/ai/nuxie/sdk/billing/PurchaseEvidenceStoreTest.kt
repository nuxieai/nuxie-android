package ai.nuxie.sdk.billing

import ai.nuxie.sdk.NuxieEnvironment
import android.util.Base64
import java.io.File
import java.math.BigDecimal
import java.nio.file.Files
import java.security.KeyPairGenerator
import java.security.Signature
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import ai.nuxie.sdk.identity.CustomerKind

@RunWith(RobolectricTestRunner::class)
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class PurchaseEvidenceStoreTest {
    @Test
    fun consumeDeclarationsAreScopedToCustomersAndSurviveBookkeeping(): Unit = kotlinx.coroutines.runBlocking {
        val directory = Files.createTempDirectory("nuxie-consume-declarations").toFile()
        try {
            val store = FilePurchaseEvidenceStore(directory)
            val original = owed("token")
            assertEquals(emptyMap<String, StoredFeatureUseDeclarations>(), original.featureUseDeclarations)
            val admitted = original.admitFeatureUse("requester", "same-use", CustomerKind.ANONYMOUS)
                .admitFeatureUse("customer-b", "same-use", CustomerKind.IDENTIFIED)
                .admitFeatureUse("requester", "same-use", CustomerKind.IDENTIFIED)
            assertEquals(mapOf("same-use" to CustomerKind.ANONYMOUS), admitted.featureUseCustomerKinds("requester"))
            assertEquals(mapOf("same-use" to CustomerKind.IDENTIFIED), admitted.featureUseCustomerKinds("customer-b"))
            assertEquals(emptyMap<String, CustomerKind>(), admitted.featureUseCustomerKinds("new-customer"))
            assertTrue(store.upsert(admitted.copy(syncAttempts = 1, retryDueAtMillis = 100)))
            val reopened = FilePurchaseEvidenceStore(directory).load().getValue("token")
            assertEquals(admitted.featureUseDeclarations, reopened.featureUseDeclarations)
            assertEquals(original.pendingSend, reopened.pendingSend)
            val raw = Json.parseToJsonElement(File(directory, "purchase-evidence.json").readText()).jsonObject
                .getValue("token").jsonObject
            assertFalse(raw.containsKey("featureUseCustomerKinds"))
            assertEquals(JsonPrimitive("anonymous"), raw.getValue("featureUseDeclarations").jsonObject
                .getValue("requester").jsonObject.getValue("customerKinds").jsonObject["same-use"])
            assertTrue(store.upsert(reopened.copy(pendingSend = null, ownerDistinctId = "requester")))
            assertEquals(admitted.featureUseDeclarations, store.load().getValue("token").featureUseDeclarations)
        } finally { directory.deleteRecursively() }
    }

    @Test
    fun missingDeclarationsAndTheUnmergedFlatFieldDecodeAsLegacyForTheRecordedCustomerOnly(): Unit = kotlinx.coroutines.runBlocking {
        val directory = Files.createTempDirectory("nuxie-legacy-declarations").toFile()
        try {
            val store = FilePurchaseEvidenceStore(directory)
            val original = owed("token").copy(ownerDistinctId = "previous-owner", buyerDistinctId = "original-buyer")
            assertTrue(store.upsert(original))
            val file = File(directory, "purchase-evidence.json")
            val row = Json.parseToJsonElement(file.readText()).jsonObject.getValue("token").jsonObject - "featureUseDeclarations"
            for (withFlatField in listOf(false, true)) {
                val legacyRow = if (withFlatField) row + ("featureUseCustomerKinds" to
                    JsonObject(mapOf("same-use" to JsonPrimitive("identified")))) else row
                file.writeText(JsonObject(mapOf("token" to JsonObject(legacyRow))).toString())
                val older = FilePurchaseEvidenceStore(directory).load().getValue("token")
                assertEquals(null, older.featureUseDeclarations)
                assertEquals(null, older.featureUseCustomerKinds("requester"))
                assertEquals(emptyMap<String, CustomerKind>(), older.featureUseCustomerKinds("customer-b"))
                assertEquals(emptyMap<String, CustomerKind>(), older.featureUseCustomerKinds("previous-owner"))
                assertEquals(emptyMap<String, CustomerKind>(), older.featureUseCustomerKinds("original-buyer"))
                val moved = older.copy(
                    pendingSend = PurchaseSend.admit(PurchaseSyncReason.PURCHASE, "customer-b", CustomerKind.IDENTIFIED),
                    featureUseDeclarations = older.retainedFeatureUseDeclarations,
                ).admitFeatureUse("requester", "same-use", CustomerKind.IDENTIFIED)
                    .admitFeatureUse("customer-b", "same-use", CustomerKind.IDENTIFIED)
                assertTrue(store.upsert(moved.copy(syncAttempts = 2)))
                val reopened = FilePurchaseEvidenceStore(directory).load().getValue("token")
                assertEquals(null, reopened.featureUseCustomerKinds("requester"))
                assertEquals(mapOf("same-use" to CustomerKind.IDENTIFIED), reopened.featureUseCustomerKinds("customer-b"))
                val history = Json.parseToJsonElement(file.readText()).jsonObject.getValue("token").jsonObject
                    .getValue("featureUseDeclarations").jsonObject
                assertEquals(JsonObject(emptyMap()), history["requester"])
            }
            // With no owed send, the server's owner is the recorded customer;
            // without an owner either, the original buyer is the only named customer.
            val noSend = original.copy(pendingSend = null, featureUseDeclarations = null)
            assertEquals(null, noSend.featureUseCustomerKinds("previous-owner"))
            assertEquals(emptyMap<String, CustomerKind>(), noSend.featureUseCustomerKinds("requester"))
            assertEquals(null, noSend.copy(ownerDistinctId = null).featureUseCustomerKinds("original-buyer"))
        } finally { directory.deleteRecursively() }
    }

    @Test
    fun unreadableAndCorruptEvidenceFilesAreLogged(): Unit = kotlinx.coroutines.runBlocking {
        val directory = Files.createTempDirectory("nuxie-corrupt-evidence").toFile()
        ai.nuxie.sdk.logging.NuxieLog.configure(ai.nuxie.sdk.LogLevel.WARN)
        org.robolectric.shadows.ShadowLog.clear()
        try {
            File(directory, "purchase-evidence.json").writeText("{bad-json")
            File(directory, "purchase-bindings.json").mkdir()
            File(directory, "purchase-catalog.json").writeText("[broken")
            val store = FilePurchaseEvidenceStore(directory)
            assertTrue(store.load().isEmpty())
            assertTrue(store.loadBindings().isEmpty())
            assertTrue(store.loadProductMappings().isEmpty())
            val warnings = org.robolectric.shadows.ShadowLog.getLogsForTag("NuxieBilling").map { it.msg }
            for (description in listOf("evidence", "bindings", "catalog")) {
                assertTrue("Unreadable $description must be distinguishable from no retained data", warnings.any {
                    it.contains("Could not read purchase $description")
                })
            }
            // The unreadable evidence file fails closed: it is never written over.
            assertFalse(store.upsert(owed("new-token")))
            assertEquals("{bad-json", File(directory, "purchase-evidence.json").readText())
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun invalidRowsInReadableEvidenceFilesAreLogged(): Unit = kotlinx.coroutines.runBlocking {
        val directory = Files.createTempDirectory("nuxie-invalid-evidence").toFile()
        ai.nuxie.sdk.logging.NuxieLog.configure(ai.nuxie.sdk.LogLevel.WARN)
        org.robolectric.shadows.ShadowLog.clear()
        try {
            File(directory, "purchase-evidence.json").writeText("""{"private-token":{}}""")
            File(directory, "purchase-bindings.json").writeText("[{}]")
            File(directory, "purchase-catalog.json").writeText("[{}]")
            val store = FilePurchaseEvidenceStore(directory)
            assertTrue(store.load().isEmpty())
            assertTrue(store.loadBindings().isEmpty())
            assertTrue(store.loadProductMappings().isEmpty())
            val warnings = org.robolectric.shadows.ShadowLog.getLogsForTag("NuxieBilling").map { it.msg }
            for (description in listOf("evidence", "bindings", "catalog")) {
                assertTrue("Invalid $description rows must not disappear silently", warnings.any {
                    it.contains("Invalid purchase $description row")
                })
            }
            assertFalse(warnings.any { it.contains("private-token") })
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun everyFileStoreMethodEntersTheInjectedIoDispatcher(): Unit = kotlinx.coroutines.runBlocking {
        val directory = Files.createTempDirectory("nuxie-evidence-io-boundary").toFile()
        val io = RecordingIoDispatcher()
        try {
            val store = FilePurchaseEvidenceStore(directory, io)
            val listenerThread = AtomicReference<Thread?>()
            store.setProductMappingsChangedListener { listenerThread.set(Thread.currentThread()) }
            val evidence = PurchaseEvidence(
                purchaseToken = "token-io",
                packageName = "com.example.app",
                storeProductIds = listOf("play-pro"),
                purchaseState = StoredPurchaseState.PURCHASED,
                buyerDistinctId = "customer-1",
                acknowledged = false,
                firstSeenMillis = 1L,
            )
            val binding = StoredPurchaseBinding(
                obfuscatedAccountId = "account-hash",
                distinctId = "customer-1",
                storeProductId = "play-pro",
                nuxieProductId = "pro",
                productType = "subs",
                consumable = false,
                nuxieManaged = true,
            )
            val mapping = StoredProductMapping(
                storeProductId = "play-pro",
                nuxieProductId = "pro",
                productType = "subs",
                consumable = false,
            )
            val stayedOnCaller = mutableListOf<String>()
            suspend fun entersIo(method: String, call: suspend () -> Unit) {
                val before = io.dispatches.get()
                call()
                if (io.dispatches.get() == before) stayedOnCaller += method
            }

            entersIo("upsert") { assertTrue(store.upsert(evidence)) }
            entersIo("load") { assertEquals(evidence, store.load().getValue("token-io")) }
            entersIo("upsertBinding") { assertTrue(store.upsertBinding(binding)) }
            entersIo("loadBindings") { assertEquals(listOf(binding), store.loadBindings()) }
            entersIo("upsertProductMapping") { assertTrue(store.upsertProductMapping(mapping)) }
            entersIo("loadProductMappings") { assertEquals(listOf(mapping), store.loadProductMappings()) }

            assertEquals(
                "Every file store read and write must enter the injected IO dispatcher",
                emptyList<String>(),
                stayedOnCaller,
            )
            assertTrue(
                "The mapping listener must run inside the store's IO boundary",
                listenerThread.get() === io.thread.get(),
            )
        } finally {
            io.close()
            directory.deleteRecursively()
        }
    }

    private class RecordingIoDispatcher : CoroutineDispatcher(), AutoCloseable {
        val dispatches = AtomicInteger()
        val thread = AtomicReference<Thread?>()
        private val executor = Executors.newSingleThreadExecutor { task ->
            Thread(task, "recording-evidence-io").apply {
                isDaemon = true
                thread.set(this)
            }
        }

        override fun dispatch(context: CoroutineContext, block: Runnable) {
            dispatches.incrementAndGet()
            executor.execute(block)
        }

        override fun close() = executor.shutdown()
    }

    @Test
    fun cancelledInstallerStillPinsFirstArrivalBeforeTheNextMapping() = kotlinx.coroutines.test.runTest {
        val store = InMemoryPurchaseEvidenceStore()
        val first = StoredProductMapping(
            storeProductId = "play-credits", nuxieProductId = "credits", productType = "inapp", consumable = false,
            featureAllowances = listOf(StoredFeatureAllowance("credits", "METERED", false, 1.0)),
        )
        val replacement = first.copy(featureAllowances = listOf(StoredFeatureAllowance("credits", "METERED", false, 99.0)))
        val started = kotlinx.coroutines.CompletableDeferred<Unit>()
        val release = kotlinx.coroutines.CompletableDeferred<Unit>()
        val seen = mutableListOf<Double?>()
        store.setProductMappingsChangedListener {
            if (seen.isEmpty()) {
                started.complete(Unit)
                release.await()
            }
            seen += store.loadProductMappings().single().featureAllowances.single().allowance
        }
        val installer = async { store.upsertProductMapping(first) }
        started.await()
        installer.cancel()
        val next = async { store.upsertProductMapping(replacement) }
        runCurrent()
        assertEquals(listOf(first), store.loadProductMappings())
        assertTrue("The replacement waits for the admitted first arrival", !next.isCompleted)
        release.complete(Unit)
        assertTrue(next.await())
        installer.join()
        assertEquals(listOf(1.0, 99.0), seen)
    }

    @Test
    fun fileStoreSurvivesAProcessStyleReconstruction(): Unit = kotlinx.coroutines.runBlocking {
        val directory = Files.createTempDirectory("nuxie-purchase-evidence").toFile()
        try {
            val evidence = PurchaseEvidence(
                purchaseToken = "token-1",
                packageName = "com.example.app",
                storeProductIds = listOf("pro"),
                purchaseState = StoredPurchaseState.PURCHASED,
                buyerDistinctId = "customer-1",
                ownerDistinctId = "customer-1",
                refused = true,
                pendingSend = PurchaseSend(PurchaseSyncReason.RESTORE, "restoring-customer", CustomerKind.IDENTIFIED),
                context = StoredPurchaseContext(
                    "primary",
                    "experience-1",
                    "v1",
                    BigDecimal("9.990000"),
                    "€9.99",
                    "checkout-journey",
                ),
                acknowledged = false,
                syncAttempts = 1,
                completionAttempts = 2,
                firstSeenMillis = 123L,
                signatureVerified = true,
                authorityScope = "authority-scope",
                backendSyncedAtMillis = 456L,
            )
            assertTrue(FilePurchaseEvidenceStore(directory).upsert(evidence))
            assertTrue(FilePurchaseEvidenceStore(directory).upsert(evidence.copy(acknowledged = true)))

            assertEquals(
                evidence.copy(acknowledged = true),
                FilePurchaseEvidenceStore(directory).load().getValue("token-1"),
            )
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun anEntryInAPreviousFormatIsIgnoredAndDroppedOnTheNextWrite(): Unit = kotlinx.coroutines.runBlocking {
        val directory = Files.createTempDirectory("nuxie-purchase-evidence").toFile()
        try {
            // The format before the outbox: the answer in `synced` and
            // `permanentlyRejected`, the requester in `syncAttributionDistinctId`.
            fun previous(token: String, synced: Boolean, rejected: Boolean, revoked: Boolean = false) =
                """"$token":{"purchaseToken":"$token","packageName":"com.example.app","storeProductIds":["pro"],""" +
                    """"purchaseState":"PURCHASED","syncAttributionDistinctId":"requester","ownerDistinctId":"guessed-owner",""" +
                    """"acknowledged":true,"consumed":false,"synced":$synced,"permanentlyRejected":$rejected,""" +
                    """"revoked":$revoked,"syncAttempts":3,"completionAttempts":0,"firstSeenMillis":1}"""
            // An owed send stored before admission ids.
            fun unrecorded(token: String, missing: String) =
                """"$token":{"purchaseToken":"$token","packageName":"com.example.app","storeProductIds":["pro"],""" +
                    """"purchaseState":"PURCHASED","refused":false,"acknowledged":true,"consumed":false,""" +
                    """"pendingSend":{""" + mapOf(
                        "reason" to "sync",
                        "distinctId" to "requester",
                        "customerKind" to "identified",
                        "admissionId" to "admission",
                    ).filterKeys { it != missing }.entries.joinToString(",") { """"${it.key}":"${it.value}"""" } +
                    """},"syncAttempts":3,"completionAttempts":0,"firstSeenMillis":1}"""
            val file = File(directory, "purchase-evidence.json")
            file.writeText(
                "{" + listOf(
                    previous("accepted", synced = true, rejected = false),
                    previous("refused", synced = false, rejected = true),
                    previous("unanswered", synced = false, rejected = false),
                    previous("refunded", synced = false, rejected = false, revoked = true),
                    unrecorded("no-admission", "admissionId"),
                ).joinToString(",") + "}",
            )
            val store = FilePurchaseEvidenceStore(directory)

            assertTrue("No record in the old format is read", store.load().isEmpty())

            assertTrue(
                store.upsert(
                    PurchaseEvidence(
                        purchaseToken = "current",
                        packageName = "com.example.app",
                        storeProductIds = listOf("pro"),
                        purchaseState = StoredPurchaseState.PURCHASED,
                        buyerDistinctId = "requester",
                        pendingSend = PurchaseSend.admit(PurchaseSyncReason.PURCHASE, "requester", CustomerKind.ANONYMOUS),
                        acknowledged = false,
                        firstSeenMillis = 2,
                    ),
                ),
            )
            assertEquals(setOf("current"), store.load().keys)
            assertEquals("The next write drops them", setOf("current"), Json.parseToJsonElement(file.readText()).jsonObject.keys)
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun aCurrentRowThatCannotBeReadClosesTheStoreAndLeavesItsFileUntouched(): Unit = kotlinx.coroutines.runBlocking {
        ai.nuxie.sdk.logging.NuxieLog.configure(ai.nuxie.sdk.LogLevel.WARN)
        // Current-format rows (they have `refused` and an admission id), each with one invalid field.
        val breakages = mapOf<String, (JsonObject) -> JsonObject>(
            // A field that is present but not of its type, never read as absent.
            "a retry due time that is not a number" to { row ->
                JsonObject(row + ("retryDueAtMillis" to JsonPrimitive("soon")))
            },
            "an acknowledged flag that is not a boolean" to { row ->
                JsonObject(row + ("acknowledged" to JsonPrimitive("yes")))
            },
            "a base plan that is not a string" to { row -> JsonObject(row + ("basePlanId" to JsonPrimitive(5))) },
            "a context that is not an object" to { row -> JsonObject(row + ("context" to JsonPrimitive("primary"))) },
            "a context price that is not a number" to { row ->
                JsonObject(row + ("context" to JsonObject(mapOf("price" to JsonPrimitive("cheap")))))
            },
            "an owed send without its reason" to { row ->
                JsonObject(row + ("pendingSend" to JsonObject(row.getValue("pendingSend").jsonObject - "reason")))
            },
            "an unknown customer kind" to { row ->
                JsonObject(row + ("pendingSend" to JsonObject(row.getValue("pendingSend").jsonObject + ("customerKind" to JsonPrimitive("robot")))))
            },
            "an unknown purchase state" to { row -> JsonObject(row + ("purchaseState" to JsonPrimitive("REFUNDED"))) },
            "no first-seen time" to { row -> JsonObject(row - "firstSeenMillis") },
        )
        for ((name, break_) in breakages) {
            val directory = Files.createTempDirectory("nuxie-purchase-evidence").toFile()
            org.robolectric.shadows.ShadowLog.clear()
            try {
                val writer = FilePurchaseEvidenceStore(directory)
                assertTrue(writer.upsert(owed("good-token")))
                assertTrue(writer.upsert(owed("broken-token")))
                val file = File(directory, "purchase-evidence.json")
                val rows = Json.parseToJsonElement(file.readText()).jsonObject
                file.writeText(JsonObject(rows + ("broken-token" to break_(rows.getValue("broken-token").jsonObject))).toString())
                val stored = file.readBytes()

                val store = FilePurchaseEvidenceStore(directory)
                assertFalse("$name: the store is not readable", store.isReadable())
                assertTrue("$name: nothing is read while the store is unreadable", store.load().isEmpty())
                assertFalse("$name: nothing is written while it is unreadable", store.upsert(owed("new-token")))
                assertTrue("$name: the file is left byte for byte as it was", stored.contentEquals(file.readBytes()))
                val warnings = org.robolectric.shadows.ShadowLog.getLogsForTag("NuxieBilling").map { it.msg }
                assertTrue("$name: the log names the row by its position, and the field", warnings.any {
                    it.contains("Could not read a purchase evidence row") && it.contains("row=1") && it.contains("field=")
                })
                assertFalse(warnings.any { it.contains("broken-token") || it.contains("good-token") })
            } finally {
                directory.deleteRecursively()
            }
        }
    }

    @Test
    fun aBindingOrCatalogRowWithAMalformedFieldClosesTheStoreAndLeavesItsFileUntouched(): Unit =
        kotlinx.coroutines.runBlocking {
            // The field's name is a sensitive log field: shown here with redaction off.
            ai.nuxie.sdk.logging.NuxieLog.configure(ai.nuxie.sdk.LogLevel.WARN, redactSensitiveData = false)
            val mapping = StoredProductMapping(
                storeProductId = "play-pro",
                nuxieProductId = "pro",
                productType = "inapp",
                consumable = false,
                licensingPublicKey = "public-key",
            )
            val binding = StoredPurchaseBinding(
                obfuscatedAccountId = "account-hash",
                distinctId = "customer-1",
                storeProductId = "play-pro",
                nuxieProductId = "pro",
                productType = "inapp",
                consumable = false,
                nuxieManaged = true,
            )
            // A licensing key that is not a string must never read as "no key": that
            // would drop the product's signature requirement.
            for ((fileName, field, value) in listOf(
                Triple("purchase-catalog.json", "licensingPublicKey", JsonPrimitive(42)),
                Triple("purchase-bindings.json", "awaitingCheckout", JsonPrimitive("yes")),
            )) {
                val directory = Files.createTempDirectory("nuxie-purchase-catalog").toFile()
                org.robolectric.shadows.ShadowLog.clear()
                try {
                    val writer = FilePurchaseEvidenceStore(directory)
                    assertTrue(writer.upsertProductMapping(mapping))
                    assertTrue(writer.upsertBinding(binding))
                    val file = File(directory, fileName)
                    val rows = Json.parseToJsonElement(file.readText()).jsonArray
                    file.writeText(kotlinx.serialization.json.JsonArray(listOf(JsonObject(rows.single().jsonObject + (field to value)))).toString())
                    val stored = file.readBytes()

                    val store = FilePurchaseEvidenceStore(directory)
                    assertFalse("$field: the store is not readable", store.isReadable())
                    if (fileName == "purchase-catalog.json") {
                        assertTrue(store.loadProductMappings().isEmpty())
                        assertFalse(store.upsertProductMapping(mapping.copy(nuxieProductId = "other")))
                    } else {
                        assertTrue(store.loadBindings().isEmpty())
                        assertFalse(store.upsertBinding(binding.copy(distinctId = "customer-2")))
                    }
                    assertTrue("$field: the file is left byte for byte as it was", stored.contentEquals(file.readBytes()))
                    val warnings = org.robolectric.shadows.ShadowLog.getLogsForTag("NuxieBilling").map { it.msg }
                    assertTrue("$field: the log names the row and the field", warnings.any {
                        it.contains("row=0") && it.contains("field=$field")
                    })
                } finally {
                    directory.deleteRecursively()
                }
            }
            ai.nuxie.sdk.logging.NuxieLog.configure(ai.nuxie.sdk.LogLevel.WARN)
        }

    @Test
    fun aCheckoutBindingIsEndedOnlyByItsOwnCheckoutId(): Unit = kotlinx.coroutines.runBlocking {
        val directory = Files.createTempDirectory("nuxie-checkout-bindings").toFile()
        try {
            fun binding(product: String, checkoutId: String) = StoredPurchaseBinding(
                obfuscatedAccountId = "account-hash",
                distinctId = "customer-1",
                storeProductId = product,
                nuxieProductId = product,
                productType = "inapp",
                consumable = false,
                nuxieManaged = true,
                checkoutId = checkoutId,
            )
            for (store in listOf(FilePurchaseEvidenceStore(directory), InMemoryPurchaseEvidenceStore())) {
                assertTrue(store.upsertBinding(binding("play-pro", "checkout-1")))
                assertTrue(store.upsertBinding(binding("play-plus", "checkout-2")))

                assertTrue(store.retireCheckoutBinding("checkout-1"))
                assertTrue("Nothing awaits an unknown checkout", store.retireCheckoutBinding("checkout-3"))

                val byCheckout = store.loadBindings().associate { it.checkoutId to it.awaitingCheckout }
                assertEquals(mapOf("checkout-1" to false, "checkout-2" to true), byCheckout)
            }
            assertEquals(
                "The id survives a reload",
                setOf("checkout-1", "checkout-2"),
                FilePurchaseEvidenceStore(directory).loadBindings().mapTo(mutableSetOf()) { it.checkoutId },
            )
        } finally {
            directory.deleteRecursively()
        }
    }

    private fun owed(token: String) = PurchaseEvidence(
        purchaseToken = token,
        packageName = "com.example.app",
        storeProductIds = listOf("pro"),
        purchaseState = StoredPurchaseState.PURCHASED,
        buyerDistinctId = "requester",
        pendingSend = PurchaseSend.admit(PurchaseSyncReason.PURCHASE, "requester", CustomerKind.ANONYMOUS),
        acknowledged = false,
        firstSeenMillis = 2,
    )

    @Test
    fun bothStoresReportACatalogChangeOnlyWhenAMappingChanges(): Unit = kotlinx.coroutines.runBlocking {
        val directory = Files.createTempDirectory("nuxie-purchase-evidence").toFile()
        try {
            for (store in listOf(FilePurchaseEvidenceStore(directory), InMemoryPurchaseEvidenceStore())) {
                val changes = AtomicInteger()
                store.setProductMappingsChangedListener { changes.incrementAndGet() }
                val mapping = StoredProductMapping(
                    storeProductId = "pro",
                    nuxieProductId = "pro-product",
                    productType = "inapp",
                    consumable = false,
                    featureAllowances = emptyList(),
                )
                assertTrue(store.upsertProductMapping(mapping))
                assertTrue("The same mapping again is still stored", store.upsertProductMapping(mapping))
                assertEquals("A new product is one change", 1, changes.get())
                assertTrue(store.upsertProductMapping(mapping.copy(consumable = true)))
                assertEquals("A changed mapping is one more", 2, changes.get())
            }
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun fileStoreDistinguishesAbsentEmptyAndPopulatedPinnedAllowances(): Unit = kotlinx.coroutines.runBlocking {
        val directory = Files.createTempDirectory("nuxie-pinned-purchase-allowances").toFile()
        try {
            val base = PurchaseEvidence(
                purchaseToken = "absent",
                packageName = "com.example.app",
                storeProductIds = listOf("play-product"),
                nuxieProductId = "nuxie-product",
                purchaseState = StoredPurchaseState.PURCHASED,
                buyerDistinctId = "customer-1",
                ownerDistinctId = "customer-1",
                acknowledged = false,
                firstSeenMillis = 123L,
            )
            val empty = base.copy(
                purchaseToken = "empty",
                pinnedFeatureAllowances = emptyList(),
            )
            val populated = base.copy(
                purchaseToken = "populated",
                pinnedFeatureAllowances = listOf(
                    StoredFeatureAllowance("credits", "METERED", false, allowance = 2.5),
                ),
            )
            val store = FilePurchaseEvidenceStore(directory)

            assertTrue(store.upsert(base))
            assertTrue(store.upsert(empty))
            assertTrue(store.upsert(populated))

            assertEquals(
                mapOf(
                    base.purchaseToken to base,
                    empty.purchaseToken to empty,
                    populated.purchaseToken to populated,
                ),
                FilePurchaseEvidenceStore(directory).load(),
            )
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun testStoreAuthorityIsSeparateWhileNativeDirectoryRemainsStable(): Unit = kotlinx.coroutines.runBlocking {
        val root = Files.createTempDirectory("nuxie-test-store-scope").toFile()
        try {
            val key = "pk_test_separation"
            val native = purchaseEvidenceDirectory(root, key, NuxieEnvironment.DEVELOPMENT)
            val simulated = purchaseEvidenceDirectory(root, key, NuxieEnvironment.DEVELOPMENT, testStore = true)
            val legacyDigest = java.security.MessageDigest.getInstance("SHA-256")
                .digest((key + "\u0000DEVELOPMENT").encodeToByteArray())
                .joinToString("") { "%02x".format(it) }
            assertEquals(legacyDigest, native.name)
            assertTrue(native != simulated)
            val marker = File(native, "feature-commands.json")
            marker.parentFile!!.mkdirs()
            marker.writeText("native-journal")
            assertFalse(File(simulated, marker.name).exists())
            simulated.mkdirs()
            File(simulated, marker.name).writeText("test-journal")
            assertEquals("native-journal", marker.readText())
            assertFalse(simulated.absolutePath.contains(key))
            assertTrue(simulated != purchaseEvidenceDirectory(root, "other-key", NuxieEnvironment.DEVELOPMENT, true))
            assertTrue(simulated != purchaseEvidenceDirectory(root, key, NuxieEnvironment.PRODUCTION, true))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun authorityScopedDirectoryDoesNotReplayEvidenceAcrossEnvironments(): Unit = kotlinx.coroutines.runBlocking {
        val filesDirectory = Files.createTempDirectory("nuxie-billing-authority").toFile()
        val apiKey = "pk_secret_authority"
        try {
            val development = purchaseEvidenceDirectory(
                filesDirectory,
                apiKey,
                NuxieEnvironment.DEVELOPMENT,
            )
            val production = purchaseEvidenceDirectory(
                filesDirectory,
                apiKey,
                NuxieEnvironment.PRODUCTION,
            )
            val evidence = PurchaseEvidence(
                purchaseToken = "token-1",
                packageName = "com.example.app",
                storeProductIds = listOf("pro"),
                purchaseState = StoredPurchaseState.PURCHASED,
                buyerDistinctId = "customer-1",
                ownerDistinctId = "customer-1",
                acknowledged = false,
                firstSeenMillis = 123L,
            )

            assertTrue(FilePurchaseEvidenceStore(development).upsert(evidence))

            assertTrue(FilePurchaseEvidenceStore(production).load().isEmpty())
            assertFalse(development.absolutePath.contains(apiKey))
            assertFalse(production.absolutePath.contains(apiKey))
            assertTrue(development != production)
        } finally {
            filesDirectory.deleteRecursively()
        }
    }

    @Test
    fun fileStoreRetainsCustomerScopedCatalogBindings(): Unit = kotlinx.coroutines.runBlocking {
        val directory = Files.createTempDirectory("nuxie-purchase-bindings").toFile()
        try {
            val binding = StoredPurchaseBinding(
                obfuscatedAccountId = "account-hash",
                distinctId = "customer-1",
                storeProductId = "play-pro",
                nuxieProductId = "pro",
                basePlanId = "annual",
                offerId = "launch",
                productType = "subs",
                consumable = false,
                context = StoredPurchaseContext(
                    "primary",
                    "experience-1",
                    "v1",
                    BigDecimal("9.990000"),
                    "€9.99",
                ),
                featureAllowances = listOf(StoredFeatureAllowance("pro", "BOOLEAN", false)),
                licensingPublicKey = "public-key",
                nuxieManaged = true,
            )

            assertTrue(FilePurchaseEvidenceStore(directory).upsertBinding(binding))

            assertEquals(binding, FilePurchaseEvidenceStore(directory).loadBindings().single())
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun fileStoreRetainsAppScopedProductMappings(): Unit = kotlinx.coroutines.runBlocking {
        val directory = Files.createTempDirectory("nuxie-product-mappings").toFile()
        try {
            val mapping = StoredProductMapping(
                storeProductId = "play-pro",
                nuxieProductId = "pro",
                basePlanId = "annual",
                offerId = "launch",
                productType = "subs",
                consumable = false,
                context = StoredPurchaseContext(
                    "primary",
                    "experience-1",
                    "v1",
                    BigDecimal("9.990000"),
                    "€9.99",
                ),
                featureAllowances = listOf(
                    StoredFeatureAllowance("exports", "METERED", false, allowance = 3.5),
                ),
                licensingPublicKey = "public-key",
            )

            assertTrue(FilePurchaseEvidenceStore(directory).upsertProductMapping(mapping))

            assertEquals(mapping, FilePurchaseEvidenceStore(directory).loadProductMappings().single())
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun productMappingReplacementWaitsForTheFirstArrivalListener(): Unit = kotlinx.coroutines.runBlocking {
        val directory = Files.createTempDirectory("nuxie-product-mapping-linearization").toFile()
        val releaseFirstListener = CountDownLatch(1)
        val firstListenerStarted = CountDownLatch(1)
        val secondWorkerStarted = CountDownLatch(1)
        val secondWorkerFinished = CountDownLatch(1)
        val listenerInvocations = AtomicInteger()
        val workerFailure = AtomicReference<Throwable>()
        var firstWorker: Thread? = null
        var secondWorker: Thread? = null
        try {
            val store = FilePurchaseEvidenceStore(directory)
            val first = StoredProductMapping(
                storeProductId = "play-credits",
                nuxieProductId = "credits",
                productType = "inapp",
                consumable = false,
                featureAllowances = listOf(
                    StoredFeatureAllowance("credits", "METERED", false, allowance = 1.0),
                ),
            )
            val replacement = first.copy(
                featureAllowances = listOf(
                    StoredFeatureAllowance("credits", "METERED", false, allowance = 99.0),
                ),
            )
            store.setProductMappingsChangedListener {
                if (listenerInvocations.getAndIncrement() == 0) {
                    firstListenerStarted.countDown()
                    check(releaseFirstListener.await(5, TimeUnit.SECONDS)) {
                        "Timed out waiting to release the first mapping listener"
                    }
                }
            }

            firstWorker = thread(name = "first-product-mapping") {
                runCatching { kotlinx.coroutines.runBlocking { check(store.upsertProductMapping(first)) } }
                    .onFailure { workerFailure.compareAndSet(null, it) }
            }
            assertTrue(firstListenerStarted.await(5, TimeUnit.SECONDS))

            secondWorker = thread(name = "replacement-product-mapping") {
                secondWorkerStarted.countDown()
                runCatching { kotlinx.coroutines.runBlocking { check(store.upsertProductMapping(replacement)) } }
                    .onFailure { workerFailure.compareAndSet(null, it) }
                secondWorkerFinished.countDown()
            }
            assertTrue(secondWorkerStarted.await(5, TimeUnit.SECONDS))

            assertFalse(
                "a replacement became visible before first-arrival pinning completed",
                secondWorkerFinished.await(250, TimeUnit.MILLISECONDS),
            )
            assertEquals(first, store.loadProductMappings().single())
        } finally {
            releaseFirstListener.countDown()
            firstWorker?.join(5_000)
            secondWorker?.join(5_000)
            directory.deleteRecursively()
        }
        workerFailure.get()?.let { throw AssertionError("mapping worker failed", it) }
    }

    @Test
    fun fileStoreRetainsBindingsAndMappingsForEveryFullProductIdentity(): Unit = kotlinx.coroutines.runBlocking {
        val directory = Files.createTempDirectory("nuxie-product-identity").toFile()
        try {
            val store = FilePurchaseEvidenceStore(directory)
            val annualBinding = StoredPurchaseBinding(
                obfuscatedAccountId = "account-hash",
                distinctId = "customer-1",
                storeProductId = "play-pro",
                nuxieProductId = "pro-annual",
                basePlanId = "annual",
                offerId = "launch",
                productType = "subs",
                consumable = false,
                featureAllowances = listOf(StoredFeatureAllowance("annual-feature", "BOOLEAN", false)),
                nuxieManaged = true,
            )
            val monthlyBinding = annualBinding.copy(
                nuxieProductId = "pro-monthly",
                basePlanId = "monthly",
                offerId = null,
                featureAllowances = listOf(StoredFeatureAllowance("monthly-feature", "BOOLEAN", false)),
            )
            val annualMapping = StoredProductMapping(
                storeProductId = annualBinding.storeProductId,
                nuxieProductId = annualBinding.nuxieProductId,
                basePlanId = annualBinding.basePlanId,
                offerId = annualBinding.offerId,
                productType = annualBinding.productType,
                consumable = false,
                featureAllowances = annualBinding.featureAllowances,
            )
            val monthlyMapping = annualMapping.copy(
                nuxieProductId = monthlyBinding.nuxieProductId,
                basePlanId = monthlyBinding.basePlanId,
                offerId = monthlyBinding.offerId,
                featureAllowances = monthlyBinding.featureAllowances,
            )

            assertTrue(store.upsertBinding(annualBinding))
            assertTrue(store.upsertBinding(monthlyBinding))
            assertTrue(store.upsertProductMapping(annualMapping))
            assertTrue(store.upsertProductMapping(monthlyMapping))

            assertEquals(
                setOf("pro-annual", "pro-monthly"),
                FilePurchaseEvidenceStore(directory).loadBindings().mapTo(mutableSetOf()) {
                    it.nuxieProductId
                },
            )
            assertEquals(
                setOf("pro-annual", "pro-monthly"),
                FilePurchaseEvidenceStore(directory).loadProductMappings().mapTo(mutableSetOf()) {
                    it.nuxieProductId
                },
            )
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun fileStoreIgnoresRetiredAllowanceKeys(): Unit = kotlinx.coroutines.runBlocking {
        // Pre-GA hard cut: the retired localFeatureGrants key is not migrated;
        // a mapping carrying only the old key decodes with no allowances.
        val directory = Files.createTempDirectory("nuxie-product-mapping-retired").toFile()
        try {
            directory.resolve("purchase-catalog.json").writeText(
                """[{"storeProductId":"play-pro","nuxieProductId":"pro","productType":"inapp","consumable":false,"localFeatureGrants":[{"featureId":"exports","type":"METERED","unlimited":false,"allowance":3.5}]}]""",
            )

            assertEquals(
                emptyList<StoredFeatureAllowance>(),
                FilePurchaseEvidenceStore(directory).loadProductMappings().single().featureAllowances,
            )
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun licensingKeyVerifierAcceptsOnlyMatchingPlayEvidence(): Unit = kotlinx.coroutines.runBlocking {
        val pair = KeyPairGenerator.getInstance("RSA").apply { initialize(1024) }.generateKeyPair()
        val originalJson = """{"purchaseToken":"token-1"}"""
        val signature = Signature.getInstance("SHA1withRSA").run {
            initSign(pair.private)
            update(originalJson.encodeToByteArray())
            Base64.encodeToString(sign(), Base64.NO_WRAP)
        }
        val publicKey = Base64.encodeToString(pair.public.encoded, Base64.NO_WRAP)

        assertTrue(PlayPurchaseSignatureVerifier.verify(publicKey, originalJson, signature))
        assertFalse(PlayPurchaseSignatureVerifier.verify(publicKey, "$originalJson ", signature))
    }
}
