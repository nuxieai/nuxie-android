package ai.nuxie.sdk.billing

import ai.nuxie.sdk.LogLevel
import ai.nuxie.sdk.NuxieEnvironment
import ai.nuxie.sdk.core.NuxieCore
import ai.nuxie.sdk.events.SystemEventNames
import ai.nuxie.sdk.features.FeatureAllowance
import ai.nuxie.sdk.features.FeatureType
import ai.nuxie.sdk.network.HttpTransport
import android.app.Activity
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.ProductDetails
import java.io.File
import java.math.BigDecimal
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
@OptIn(ExperimentalCoroutinesApi::class)
class PurchaseOutcomeCommitFixtureTest {
    @Test
    fun matchesTheSharedPurchaseOutcomeCommitFixture() {
        val root = Json.parseToJsonElement(
            File(
                ai.nuxie.sdk.fixtures.FixtureRunner.fixturesRoot(),
                "purchases/outcome-commit.json",
            ).readText(),
        ).jsonObject
        assertEquals("purchases/outcome-commit", root.requiredString("suite"))
        assertEquals(1, root.requiredInt("version"))
        assertEquals(
            listOf(
                "checkout",
                "transaction_stream",
                "startup_recovery",
                "deferred_update",
                "external_delegate",
            ),
            root.getValue("sources").jsonArray.map { it.jsonPrimitive.content },
        )
        val contract = Contract(root)

        root.getValue("cases").jsonArray.forEachIndexed { vectorIndex, element ->
            val vector = element.jsonObject
            val name = vector.requiredString("name")
            try {
                runTest { runVector(contract, vector, vectorIndex) }
            } catch (failure: Throwable) {
                throw AssertionError("Fixture vector 'purchases/outcome-commit/$name' failed", failure)
            }
        }
    }

    private suspend fun TestScope.runVector(
        contract: Contract,
        vector: JsonObject,
        vectorIndex: Int,
    ) {
        val actions = vector.getValue("actions").jsonArray.map { it.jsonObject }
        val expected = vector.getValue("expect").jsonObject
        val pendingEvidenceName = actions.firstNotNullOfOrNull { action ->
            action.optionalString("evidence")
        }
        val harness = Harness(
            scope = this,
            vectorIndex = vectorIndex,
            trackedFeatureIds = contract.products.values
                .flatMap(FixtureProduct::featureIds)
                .toSet(),
        )
        try {
            runCurrent()
            actions.forEach { action ->
                executeAction(
                    harness = harness,
                    contract = contract,
                    action = action,
                    pendingEvidenceName = pendingEvidenceName,
                    expected = expected,
                )
                harness.observeOverlay()
            }
            runCurrent()
            assertVector(contract, vector, harness)
        } finally {
            harness.close()
        }
    }

    private suspend fun TestScope.executeAction(
        harness: Harness,
        contract: Contract,
        action: JsonObject,
        pendingEvidenceName: String?,
        expected: JsonObject,
    ) {
        when (val entry = action.requiredString("entry")) {
            "checkout" -> executeCheckout(harness, contract, action, pendingEvidenceName)
            "transaction_stream", "deferred_update" -> {
                val evidenceName = action.requiredString("evidence")
                val purchase = checkNotNull(harness.purchasesByEvidence[evidenceName]) {
                    "No earlier purchase observation for evidence '$evidenceName'."
                }.copy(state = StoredPurchaseState.PURCHASED)
                // deferred_update is fixture provenance, not an injectable Android entry point.
                // The production classifier derives it from pending ownership plus this stream.
                harness.service.onPurchasesUpdated(okUpdate(purchase))
                harness.purchasesByEvidence[evidenceName] = purchase
            }
            "startup_recovery" -> {
                val evidenceName = action.requiredString("evidence")
                val purchase = checkNotNull(harness.purchasesByEvidence[evidenceName]) {
                    "No earlier purchase observation for evidence '$evidenceName'."
                }.copy(state = StoredPurchaseState.PURCHASED)
                harness.billing.active.clear()
                harness.billing.active[BillingClient.ProductType.INAPP] = listOf(purchase)
                val queryCountBeforeRecovery = harness.billing.queries.size
                harness.logicalEntitlementScans += 1
                harness.service.recover()
                assertEquals(
                    "One logical Play entitlement scan must query both product types",
                    listOf(BillingClient.ProductType.SUBS, BillingClient.ProductType.INAPP),
                    harness.billing.queries.drop(queryCountBeforeRecovery),
                )
            }
            "external_delegate" -> executeExternalDeclaration(harness, contract, action)
            "retry_retained_outcomes" -> {
                assertEquals("retry", action.requiredString("outcome"))
                val retainedOperationId = checkNotNull(harness.externalOperationIds.lastOrNull()) {
                    "A retained external retry requires an earlier external operation."
                }
                val retainedEventId = checkNotNull(harness.captureAttempts.lastOrNull()?.eventId) {
                    "A retained external retry requires an earlier carrier attempt."
                }
                val operationCountBefore = harness.externalOperationIds.size
                val attemptsBefore = harness.captureAttempts.size
                val captureSucceeds = action.optionalBoolean("carrierCaptureSucceeds") ?: true
                harness.captureShouldSucceed = captureSucceeds
                advanceTimeBy(harness.nextExternalRetryDelayMillis)
                runCurrent()
                harness.nextExternalRetryDelayMillis *= 2
                val delta = harness.captureAttempts.size - attemptsBefore
                action.optionalInt("expectedCarrierCaptureAttemptDelta")?.let { expectedDelta ->
                    assertEquals(
                        "A retained operation past its retry cap must stop attempting",
                        expectedDelta,
                        delta,
                    )
                }
                if (delta > 0) {
                    assertEquals(
                        "Every retry must retain the declaration's carrier identity",
                        listOf(retainedEventId),
                        harness.captureAttempts.drop(attemptsBefore)
                            .map(CaptureAttempt::eventId).distinct(),
                    )
                }
                assertEquals(
                    "A retained retry must not mint another external operation",
                    operationCountBefore,
                    harness.externalOperationIds.size,
                )
                if (captureSucceeds && delta > 0) {
                    assertTrue(
                        "The successful retained capture must commit the original external operation",
                        harness.hasCommittedExternalOperation(retainedOperationId),
                    )
                    val attemptCountAfterSuccess = harness.captureAttempts.size
                    advanceTimeBy(1_000)
                    runCurrent()
                    assertEquals(
                        "A successful retained operation must cancel further carrier retries",
                        attemptCountAfterSuccess,
                        harness.captureAttempts.size,
                    )
                } else if (!captureSucceeds) {
                    assertTrue(
                        "The retained operation must not commit while its carrier keeps failing",
                        !harness.hasCommittedExternalOperation(retainedOperationId),
                    )
                }
            }
            else -> error("Unsupported purchase outcome fixture entry '$entry'.")
        }
    }

    private suspend fun TestScope.executeCheckout(
        harness: Harness,
        contract: Contract,
        action: JsonObject,
        pendingEvidenceName: String?,
    ) {
        val productName = action.requiredString("product")
        val product = contract.products.getValue(productName)
        val checkout = async {
            harness.service.purchase(activity(), product.toStoreProduct(), replacement = null)
        }
        runCurrent()
        val launch = checkNotNull(harness.billing.launches.lastOrNull()) {
            "Native checkout did not reach Play launch."
        }
        val fixtureOutcome = action.requiredString("outcome")
        when (fixtureOutcome) {
            "verified", "pending" -> {
                val evidenceName = action.optionalString("evidence") ?: pendingEvidenceName
                val purchase = if (evidenceName == null) {
                    standalonePendingPurchase(product, harness.vectorIndex)
                } else {
                    contract.evidence.getValue(evidenceName).toPlayPurchase(contract)
                }.copy(
                    state = if (fixtureOutcome == "pending") {
                        StoredPurchaseState.PENDING
                    } else {
                        StoredPurchaseState.PURCHASED
                    },
                    obfuscatedAccountId = launch.obfuscatedAccountId,
                )
                harness.service.onPurchasesUpdated(okUpdate(purchase))
                if (evidenceName == null) {
                    check(purchase.state == StoredPurchaseState.PENDING)
                } else {
                    harness.purchasesByEvidence[evidenceName] = purchase
                }
            }
            "cancelled" -> harness.service.onPurchasesUpdated(
                PurchaseUpdate(
                    billingResult(
                        BillingClient.BillingResponseCode.USER_CANCELED,
                        "cancelled",
                    ),
                    purchases = null,
                ),
            )
            "failed" -> harness.service.onPurchasesUpdated(
                PurchaseUpdate(
                    billingResult(
                        BillingClient.BillingResponseCode.ERROR,
                        action.requiredString("reason"),
                    ),
                    purchases = null,
                ),
            )
            else -> error("Unsupported checkout outcome '$fixtureOutcome'.")
        }

        val result = checkout.await()
        harness.checkoutResults += result
        when (fixtureOutcome) {
            "verified" -> assertEquals(PurchaseResult.Purchased, result)
            "cancelled" -> assertEquals(PurchaseResult.Cancelled, result)
            "pending" -> assertEquals(PurchaseResult.Pending, result)
            "failed" -> assertTrue(result is PurchaseResult.Failed)
        }
    }

    private suspend fun TestScope.executeExternalDeclaration(
        harness: Harness,
        contract: Contract,
        action: JsonObject,
    ) {
        assertEquals("external", action.requiredString("outcome"))
        harness.settings.delegate = harness.delegate
        harness.captureShouldSucceed = action.optionalBoolean("carrierCaptureSucceeds") ?: true
        when (val operation = action.requiredString("operation")) {
            "purchase" -> {
                harness.delegate.purchaseResult = PurchaseResult.Purchased
                val product = contract.products.getValue(action.requiredString("product"))
                val gate = harness.delegate.gateNextPurchase()
                val initiatingIdentity = harness.core.identity.distinctId()
                val purchase = async {
                    harness.service.purchase(
                        activity(),
                        product.toStoreProduct(),
                        replacement = null,
                    )
                }
                runCurrent()
                gate.started.await()
                harness.core.identity.setDistinctId(
                    "post_delegate_${harness.vectorIndex}_${harness.delegate.purchasedProducts.size}",
                )
                gate.release.complete(Unit)
                val result = try {
                    purchase.await()
                } finally {
                    harness.core.identity.setDistinctId(initiatingIdentity)
                }
                harness.checkoutResults += result
                assertEquals(PurchaseResult.Purchased, result)
            }
            "restore" -> {
                harness.delegate.restoreResult = RestoreResult.Restored
                val result = harness.service.restorePurchases()
                harness.restoreResults += result
                assertEquals(RestoreResult.Restored, result)
            }
            else -> error("Unsupported external purchase operation '$operation'.")
        }
    }

    private fun assertVector(
        contract: Contract,
        vector: JsonObject,
        harness: Harness,
    ) {
        val name = vector.requiredString("name")
        val expected = vector.getValue("expect").jsonObject
        val successfulCaptures = harness.successfulCaptures.values.toList()
        val completions = successfulCaptures.filter {
            it.name == SystemEventNames.PURCHASE_COMPLETED
        }
        val restores = successfulCaptures.filter {
            it.name == SystemEventNames.RESTORE_COMPLETED
        }
        val failures = harness.emittedEvents.filter {
            it.name == SystemEventNames.PURCHASE_FAILED
        }
        val committedIdentities = harness.purchaseCommitObservations
            .filterIsInstance<PurchaseCommitObservation.Committed>()
            .map(PurchaseCommitObservation.Committed::identity)
        val terminalOutcomes = harness.purchaseCommitObservations
            .filterIsInstance<PurchaseCommitObservation.Terminal>()
            .map { it.toFixtureTerminalOutcome() }

        assertEquals(name, expected.requiredInt("successfulCommits"), committedIdentities.size)
        assertEquals(
            "$name: a completed committer identity must be observed once",
            committedIdentities.size,
            committedIdentities.distinct().size,
        )

        // Play persists pending ownership in PurchaseEvidenceStore. The shared fixture gives
        // pending ownership its own bucket, so this portable evidence count tracks identities
        // that ever reached PURCHASED while pendingRecords checks the retained pending rows.
        assertEquals(
            name,
            expected.requiredInt("uniqueEvidenceRows"),
            harness.store.everPurchasedTokens.size,
        )
        assertEquals(
            name,
            expected.requiredInt("pendingRecords"),
            harness.store.load().values.count {
                it.purchaseState == StoredPurchaseState.PENDING && !it.revoked
            },
        )
        assertEquals(
            name,
            expected.requiredInt("uniqueEvidenceRows") + expected.requiredInt("pendingRecords"),
            harness.store.load().size,
        )
        assertEquals(
            name,
            expected.requiredInt("journeyAdvancements"),
            completions.size + restores.size,
        )
        assertEquals(name, expected.requiredInt("purchaseCompletedEvents"), completions.size)
        assertEquals(name, expected.requiredInt("restoreCompletedEvents"), restores.size)
        assertEquals(name, expected.requiredInt("purchaseFailedEvents"), failures.size)
        restores.forEach { restore ->
            assertEquals(name, PurchaseOutcomeSource.EXTERNAL_DELEGATE.wireValue, restore.properties["source"])
            assertEquals(name, false, restore.properties["test_store"])
        }

        // Android starts initial sync inline. The injected synchronizer is the follow-up
        // scheduling seam; the recording transport separately proves the actual /purchase call.
        assertEquals(
            name,
            expected.requiredInt("scheduledSyncTasks"),
            harness.syncStarts.size,
        )
        assertEquals(
            name,
            expected.requiredInt("serverSyncRequests"),
            harness.transport.purchaseRequests.size,
        )
        assertEquals(name, expected.requiredBoolean("overlayEverPresent"), harness.overlayEverPresent)

        // One portable entitlement scan is recover(); Play implements it as SUBS + INAPP.
        // Checkout's product-specific preflight query is deliberately not such a scan.
        assertEquals(
            name,
            expected.requiredInt("storeEntitlementQueries"),
            harness.logicalEntitlementScans,
        )

        assertEquals(name, expected.stringArray("checkoutErrors"), harness.checkoutErrors())
        assertEquals(
            name,
            expected.getValue("committerTerminalOutcomes").jsonArray.map {
                val terminal = it.jsonObject
                TerminalOutcome(
                    kind = terminal.requiredString("kind"),
                    source = terminal.requiredString("source"),
                    reason = terminal.optionalString("reason"),
                    terminal = terminal.requiredBoolean("terminal"),
                )
            },
            terminalOutcomes,
        )
        assertTerminalEvents(name, harness, terminalOutcomes)

        assertEquals(
            name,
            expected.stringArray("completionSources"),
            completions.map { it.properties.getValue("source") as String },
        )
        assertCompletionEvidenceIdentity(
            name = name,
            expected = expected.requiredBoolean("completionCarriesEvidenceIdentity"),
            completions = completions,
            contract = contract,
            harness = harness,
        )
        assertEquals(
            name,
            expected.requiredBoolean("completionCarriesProductMapping"),
            completions.isNotEmpty() && completions.all { it.carriesProductMapping(contract) },
        )
        assertProductContexts(name, contract, harness)
        assertFixturePayloadMapping(name, contract, vector, harness)
        assertPurchaseRequests(name, contract, harness)

        expected.optionalBoolean("completionEventIdsDistinct")?.let { distinct ->
            assertEquals(
                name,
                distinct,
                completions.map(CapturedEvent::eventId).distinct().size == completions.size,
            )
        }
        expected.optionalInt("minimumCarrierCaptureAttempts")?.let { minimum ->
            assertTrue(name, harness.captureAttempts.size >= minimum)
            val maximum = expected.optionalInt("maximumCarrierCaptureAttempts")
                ?: MAX_EXTERNAL_CAPTURE_ATTEMPTS
            assertTrue(name, minimum <= maximum)
            assertTrue(name, harness.captureAttempts.size <= maximum)
        }
        expected.optionalInt("maximumCarrierCaptureAttempts")?.let { maximum ->
            assertTrue(name, harness.captureAttempts.size <= maximum)
        }
        expected.optionalInt("successfulCarrierCaptures")?.let { count ->
            assertEquals(name, count, harness.successfulCaptureCallbacks.size)
        }
        expected.optionalInt("carrierCaptureOperationIdentityCount")?.let { count ->
            assertEquals(name, count, harness.captureAttempts.map(CaptureAttempt::eventId).distinct().size)
        }
        expected.optionalInt("retainedCarrierRetryRoundLimit")?.let { limit ->
            // Production makes one immediate attempt plus at most `limit`
            // retained retry rounds for one external operation.
            assertTrue(name, harness.captureAttempts.size <= 1 + limit)
            if (expected.optionalBoolean("carrierCaptureAttemptsStopAtRetryLimit") == true) {
                assertEquals(name, 1 + limit, harness.captureAttempts.size)
            }
        }

        val expectedFinalizations = expected.optionalInt("storeFinalizationCalls")
            ?: harness.store.everPurchasedTokens.size
        assertEquals(
            name,
            expectedFinalizations,
            harness.billing.acknowledgedTokens.size + harness.billing.consumedTokens.size,
        )
        assertEquals(name, harness.store.everPurchasedTokens, harness.billing.acknowledgedTokens.toSet())
        assertTrue(name, harness.billing.consumedTokens.isEmpty())

        val fixtureActions = vector.getValue("actions").jsonArray.map { it.jsonObject }
        val expectedRawQueries = fixtureActions.flatMap { action ->
            when (action.requiredString("entry")) {
                "checkout" -> listOf(BillingClient.ProductType.INAPP)
                "startup_recovery" -> listOf(
                    BillingClient.ProductType.SUBS,
                    BillingClient.ProductType.INAPP,
                )
                else -> emptyList()
            }
        }
        assertEquals(name, expectedRawQueries, harness.billing.queries)
        assertEquals(
            name,
            fixtureActions.count { it.requiredString("entry") == "checkout" },
            harness.billing.launches.size,
        )

        val onlyExternalEntries = fixtureActions.all { action ->
            action.requiredString("entry") in setOf(
                "external_delegate",
                "retry_retained_outcomes",
            )
        }
        if (onlyExternalEntries) {
            assertTrue(name, harness.billing.launches.isEmpty())
            assertTrue(name, harness.billing.queries.isEmpty())
            assertTrue(name, harness.store.load().isEmpty())
            assertTrue(name, harness.store.everStoredTokens.isEmpty())
            assertTrue(name, harness.syncStarts.isEmpty())
            assertTrue(name, harness.trackedFeatureIds.none(harness.core.featureInfo::isAllowed))
        }

        val externalActions = fixtureActions
            .filter { it.requiredString("entry") == "external_delegate" }
        val externalPurchaseActions = externalActions.count {
            it.requiredString("operation") == "purchase"
        }
        val externalRestoreActions = externalActions.count {
            it.requiredString("operation") == "restore"
        }
        assertEquals(name, externalActions.size, harness.externalOperationIds.size)
        assertEquals(name, harness.externalOperationIds.size, harness.externalOperationIds.distinct().size)
        assertEquals(name, externalPurchaseActions, harness.delegate.purchasedProducts.size)
        assertEquals(name, externalRestoreActions, harness.delegate.restoreCalls)
        assertEquals(name, externalRestoreActions, harness.restoreResults.size)

        successfulCaptures.forEach { capture ->
            assertEquals(name, harness.ownerDistinctId, capture.distinctId)
        }
    }

    private fun assertCompletionEvidenceIdentity(
        name: String,
        expected: Boolean,
        completions: List<CapturedEvent>,
        contract: Contract,
        harness: Harness,
    ) {
        val carriesEvidence = completions.isNotEmpty() && completions.all {
            (it.properties["transaction_id"] as? String)?.isNotBlank() == true
        }
        assertEquals(name, expected, carriesEvidence)
        if (!expected && completions.isNotEmpty()) {
            assertTrue(name, completions.all { "transaction_id" !in it.properties })
            return
        }
        if (!expected) return

        val completionTokens = completions.map { it.properties.getValue("transaction_id") as String }
        assertTrue(name, completionTokens.all { it in harness.store.everPurchasedTokens })
        val syncedEvents = harness.emittedEvents.filter {
            it.name == SystemEventNames.PURCHASE_SYNCED
        }
        assertEquals(name, completionTokens.toSet(), syncedEvents.map {
            it.properties.getValue("transaction_id") as String
        }.toSet())
        syncedEvents.forEach { event ->
            // Play has no separate lineage identity; the shared fixture explicitly maps it
            // to the purchase token for original_transaction_id.
            val token = event.properties.getValue("transaction_id") as String
            val fixtureEvidence = contract.evidence.values.single { it.identity == token }
            assertTrue(name, fixtureEvidence.originalIdentity.isNotBlank())
            assertEquals(
                name,
                fixtureEvidence.identity,
                event.properties["original_transaction_id"],
            )
        }
    }

    private fun assertProductContexts(
        name: String,
        contract: Contract,
        harness: Harness,
    ) {
        harness.delegate.purchasedProducts.forEach { actual ->
            val expected = contract.products.values.single { it.productId == actual.productId }
            assertEquals(name, expected.storeProductId, actual.storeProductId)
            assertEquals(name, expected.placementId, actual.placementId)
            assertEquals(name, expected.experienceId, actual.purchaseContext?.experienceId)
            assertEquals(name, expected.experienceVersion, actual.purchaseContext?.experienceVersion)
        }
        harness.store.load().values
            .filter { it.purchaseState == StoredPurchaseState.PURCHASED }
            .forEach { evidence ->
                val expected = contract.products.values.single {
                    it.productId == evidence.nuxieProductId
                }
                assertEquals(name, expected.placementId, evidence.context?.placementId)
                assertEquals(name, expected.experienceId, evidence.context?.experienceId)
                assertEquals(name, expected.experienceVersion, evidence.context?.experienceVersion)
            }
    }

    private fun assertFixturePayloadMapping(
        name: String,
        contract: Contract,
        vector: JsonObject,
        harness: Harness,
    ) {
        val evidenceNames = vector.getValue("actions").jsonArray.mapNotNull {
            it.jsonObject.optionalString("evidence")
        }.distinct()
        evidenceNames.forEach { evidenceName ->
            val evidence = contract.evidence.getValue(evidenceName)
            val payload = evidence.playPayload()
            assertTrue(
                name,
                harness.signatureInputs.any {
                    it.publicKey == FIXTURE_LICENSING_KEY &&
                        it.originalJson == payload.originalJson &&
                        it.signature == payload.signature
                },
            )
        }
    }

    private fun assertPurchaseRequests(
        name: String,
        contract: Contract,
        harness: Harness,
    ) {
        harness.transport.purchaseRequests.forEach { request ->
            val body = Json.parseToJsonElement(request.body.decodeToString()).jsonObject
            val token = body.requiredString("purchase_token")
            assertTrue(name, token in harness.store.everPurchasedTokens)
            assertEquals(name, harness.ownerDistinctId, body.requiredString("distinct_id"))
            val stored = harness.store.load().getValue(token)
            val product = contract.products.values.single { it.productId == stored.nuxieProductId }
            assertEquals(name, product.storeProductId, body.requiredString("product_id"))
        }
    }

    private fun assertTerminalEvents(
        name: String,
        harness: Harness,
        terminalOutcomes: List<TerminalOutcome>,
    ) {
        val expectedNames = terminalOutcomes.map { terminal ->
            when (terminal.kind) {
                "cancelled" -> SystemEventNames.PURCHASE_CANCELLED
                "pending" -> SystemEventNames.PURCHASE_PENDING
                "failed" -> SystemEventNames.PURCHASE_FAILED
                else -> error("Unsupported terminal fixture kind '${terminal.kind}'.")
            }
        }
        val actualNames = harness.emittedEvents.map(CapturedEvent::name).filter {
            it in setOf(
                SystemEventNames.PURCHASE_CANCELLED,
                SystemEventNames.PURCHASE_PENDING,
                SystemEventNames.PURCHASE_FAILED,
            )
        }
        assertEquals(name, expectedNames, actualNames)
    }

    private data class FixtureProduct(
        val productId: String,
        val storeProductId: String,
        val placementId: String,
        val experienceId: String,
        val experienceVersion: String,
        val displayPrice: String,
        val price: Double,
        val featureIds: List<String>,
    ) {
        fun toStoreProduct(): StoreProduct = StoreProduct(
            productId = productId,
            storeProductId = storeProductId,
            basePlanId = null,
            offerId = null,
            placementId = placementId,
            rawProduct = productDetails(),
            offerToken = null,
            isOfferPersonalized = false,
            productType = BillingClient.ProductType.INAPP,
            consumable = false,
            featureAllowances = featureIds.map {
                FeatureAllowance(it, FeatureType.BOOLEAN)
            },
            licensingPublicKey = FIXTURE_LICENSING_KEY,
            purchaseContext = PurchaseContext(experienceId, experienceVersion),
        )

        private fun productDetails(): ProductDetails {
            val priceMicros = BigDecimal.valueOf(price).movePointRight(6).longValueExact()
            val encoded = JsonObject(
                mapOf(
                    "productId" to JsonPrimitive(storeProductId),
                    "type" to JsonPrimitive("inapp"),
                    "title" to JsonPrimitive(productId),
                    "name" to JsonPrimitive(productId),
                    "description" to JsonPrimitive(productId),
                    "oneTimePurchaseOfferDetails" to JsonObject(
                        mapOf(
                            "formattedPrice" to JsonPrimitive(displayPrice),
                            "priceAmountMicros" to JsonPrimitive(priceMicros),
                            "priceCurrencyCode" to JsonPrimitive("USD"),
                        ),
                    ),
                ),
            ).toString()
            val constructor = ProductDetails::class.java.getDeclaredConstructor(String::class.java)
            constructor.isAccessible = true
            return constructor.newInstance(encoded)
        }
    }

    private data class FixtureEvidence(
        val identity: String,
        val originalIdentity: String,
        val signedPayload: String,
        val productName: String,
    ) {
        fun toPlayPurchase(contract: Contract): PlayPurchase {
            val product = contract.products.getValue(productName)
            val payload = playPayload()
            return PlayPurchase(
                purchaseToken = identity,
                packageName = FIXTURE_PACKAGE_NAME,
                products = listOf(product.storeProductId),
                state = StoredPurchaseState.PURCHASED,
                acknowledged = false,
                obfuscatedAccountId = null,
                originalJson = payload.originalJson,
                signature = payload.signature,
            )
        }

        fun playPayload(): PlayPayload = PlayPayload(
            originalJson = JsonObject(
                mapOf("signedPayload" to JsonPrimitive(signedPayload)),
            ).toString(),
            signature = "fixture-signature:$signedPayload",
        )
    }

    private data class PlayPayload(
        val originalJson: String,
        val signature: String,
    )

    private inner class Contract(root: JsonObject) {
        val products: Map<String, FixtureProduct> = root.getValue("products").jsonObject
            .mapValues { (_, element) ->
                val product = element.jsonObject
                FixtureProduct(
                    productId = product.requiredString("productId"),
                    storeProductId = product.requiredString("storeProductId"),
                    placementId = product.requiredString("placementId"),
                    experienceId = product.requiredString("experienceId"),
                    experienceVersion = product.requiredString("experienceVersion"),
                    displayPrice = product.requiredString("displayPrice"),
                    price = product.requiredDouble("price"),
                    featureIds = product.getValue("localEntitlementGrants").jsonArray.map {
                        it.jsonObject.requiredString("featureId")
                    },
                )
            }
        val evidence: Map<String, FixtureEvidence> = root.getValue("evidence").jsonObject
            .mapValues { (_, element) ->
                val evidence = element.jsonObject
                FixtureEvidence(
                    identity = evidence.requiredString("identity"),
                    originalIdentity = evidence.requiredString("originalIdentity"),
                    signedPayload = evidence.requiredString("signedPayload"),
                    productName = evidence.requiredString("product"),
                )
            }
    }

    private data class CapturedEvent(
        val name: String,
        val properties: Map<String, Any?>,
        val eventId: String?,
        val distinctId: String?,
    ) {
        fun carriesProductMapping(contract: Contract): Boolean {
            val productId = properties["product_id"] as? String ?: return false
            val product = contract.products.values.singleOrNull { it.productId == productId }
                ?: return false
            val actualPrice = properties["price"] as? Double ?: return false
            return properties["store_product_id"] == product.storeProductId &&
                properties["placement_id"] == product.placementId &&
                properties["experience_id"] == product.experienceId &&
                properties["display_price"] == product.displayPrice &&
                actualPrice == product.price
        }
    }

    private data class CaptureAttempt(
        val name: String,
        val eventId: String,
        val distinctId: String,
    )

    private data class SignatureInput(
        val publicKey: String,
        val originalJson: String,
        val signature: String,
    )

    private data class TerminalOutcome(
        val kind: String,
        val source: String,
        val reason: String?,
        val terminal: Boolean,
    )

    private class Harness(
        scope: TestScope,
        val vectorIndex: Int,
        val trackedFeatureIds: Set<String>,
    ) {
        val ownerDistinctId = "purchase_outcome_owner_$vectorIndex"
        val transport = RecordingPurchaseTransport(ownerDistinctId)
        val core = NuxieCore(
            context = RuntimeEnvironment.getApplication(),
            apiKey = "pk_test_purchase_outcome_$vectorIndex",
            environment = NuxieEnvironment.DEVELOPMENT,
            logLevel = LogLevel.NONE,
            beforeSend = null,
            overrides = NuxieCore.Overrides(
                transport = transport,
                nowMillis = { FIXED_NOW_MILLIS + vectorIndex },
                registerLifecycle = false,
            ),
        ).also { it.identity.setDistinctId(ownerDistinctId) }
        val store = RecordingEvidenceStore()
        val billing = FakeBilling()
        val settings = PurchaseSettings(delegate = null, handlingMode = PurchaseHandlingMode.NUXIE_MANAGED)
        val delegate = RecordingDelegate()
        val emittedEvents = mutableListOf<CapturedEvent>()
        val successfulCaptures = linkedMapOf<String, CapturedEvent>()
        val successfulCaptureCallbacks = mutableListOf<CaptureAttempt>()
        val captureAttempts = mutableListOf<CaptureAttempt>()
        val signatureInputs = mutableListOf<SignatureInput>()
        val syncStarts = mutableListOf<PurchaseEvidence>()
        val externalOperationIds = mutableListOf<String>()
        val purchasesByEvidence = mutableMapOf<String, PlayPurchase>()
        val checkoutResults = mutableListOf<PurchaseResult>()
        val restoreResults = mutableListOf<RestoreResult>()
        val purchaseCommitObservations = mutableListOf<PurchaseCommitObservation>()
        var captureShouldSucceed = true
        var overlayEverPresent = false
        var logicalEntitlementScans = 0
        var nextExternalRetryDelayMillis = INITIAL_EXTERNAL_RETRY_MILLIS * 2
        private val apiSynchronizer = NuxieApiPurchaseSynchronizer(core.api)
        private var externalOperationSequence = 0

        val service = PurchaseService(
            billing = billing,
            evidenceStore = store,
            synchronizer = PurchaseSynchronizer { evidence ->
                syncStarts += evidence
                observeOverlay()
                apiSynchronizer.sync(evidence)
            },
            features = core.features,
            distinctId = core.identity::distinctId,
            emit = { name, properties ->
                emittedEvents += CapturedEvent(
                    name = name,
                    properties = properties.toMap(),
                    eventId = null,
                    distinctId = null,
                )
            },
            settings = settings,
            scope = scope.backgroundScope,
            nowMillis = { FIXED_NOW_MILLIS + vectorIndex },
            initialRetryDelayMillis = INITIAL_EXTERNAL_RETRY_MILLIS,
            maxRetryDelayMillis = 1_000,
            api = core.api,
            purchaseStorageScope = "purchase-outcome-scope-$vectorIndex",
            capturePurchaseEvent = { name, properties, eventId, distinctId ->
                val attempt = CaptureAttempt(name, eventId, distinctId)
                captureAttempts += attempt
                if (captureShouldSucceed) {
                    successfulCaptureCallbacks += attempt
                    if (eventId !in successfulCaptures) {
                        successfulCaptures[eventId] = CapturedEvent(
                            name = name,
                            properties = properties.toMap(),
                            eventId = eventId,
                            distinctId = distinctId,
                        )
                    }
                    true
                } else {
                    false
                }
            },
            newExternalOperationId = {
                externalOperationSequence += 1
                "fixture-external-$vectorIndex-$externalOperationSequence".also {
                    externalOperationIds += it
                }
            },
            verifyPurchaseSignature = { publicKey, originalJson, signature ->
                signatureInputs += SignatureInput(publicKey, originalJson, signature)
                true
            },
            logWarning = { _, _ -> },
            purchaseCommitObserver = purchaseCommitObservations::add,
        )

        init {
            core.featureInfo.onFeatureChange = { _, _, _, _ -> observeOverlay() }
        }

        fun observeOverlay() {
            overlayEverPresent = overlayEverPresent ||
                trackedFeatureIds.any(core.featureInfo::isAllowed)
        }

        fun checkoutErrors(): List<String> = checkoutResults.mapNotNull { result ->
            when (result) {
                PurchaseResult.Purchased -> null
                PurchaseResult.Cancelled -> "cancelled"
                PurchaseResult.Pending -> "pending"
                is PurchaseResult.Failed -> "failed"
            }
        }

        fun hasCommittedExternalOperation(operationId: String): Boolean =
            purchaseCommitObservations.any { observation ->
                observation is PurchaseCommitObservation.Committed &&
                    observation.identity == PurchaseCommitIdentity.External(operationId)
            }

        fun close() = core.stop()
    }

    private class RecordingPurchaseTransport(
        private val customerId: String,
    ) : HttpTransport {
        val requests = mutableListOf<HttpTransport.Request>()
        val purchaseRequests: List<HttpTransport.Request>
            get() = requests.filter { it.url.path == "/purchase" }

        override fun execute(request: HttpTransport.Request): HttpTransport.Response {
            requests += request
            val body = when (request.url.path) {
                "/purchase" -> JsonObject(
                    mapOf(
                        "success" to JsonPrimitive(true),
                        "customer_id" to JsonPrimitive(customerId),
                        "features" to JsonArray(
                            listOf(
                                JsonObject(
                                    mapOf(
                                        "id" to JsonPrimitive("premium"),
                                        "type" to JsonPrimitive("boolean"),
                                        "allowed" to JsonPrimitive(true),
                                        "unlimited" to JsonPrimitive(false),
                                    ),
                                ),
                            ),
                        ),
                        "catalog_product" to JsonObject(
                            mapOf(
                                "id" to JsonPrimitive("fixture-product"),
                                "store_product_id" to JsonPrimitive("fixture-product"),
                                "base_plan_id" to JsonNull,
                                "purchase_option_id" to JsonNull,
                                "offer_id" to JsonNull,
                                "store_product_type" to JsonPrimitive("nonConsumable"),
                            ),
                        ),
                    ),
                ).toString()
                "/profile" -> """{"segments":[]}"""
                else -> "{}"
            }
            return HttpTransport.Response(200, body.encodeToByteArray())
        }
    }

    private class RecordingEvidenceStore : PurchaseEvidenceStore {
        private val entries = linkedMapOf<String, PurchaseEvidence>()
        private val bindings =
            linkedMapOf<Pair<String, StoredProductIdentity>, StoredPurchaseBinding>()
        private val mappings = linkedMapOf<StoredProductIdentity, StoredProductMapping>()
        val everStoredTokens = linkedSetOf<String>()
        val everPurchasedTokens = linkedSetOf<String>()

        override fun load(): Map<String, PurchaseEvidence> = entries.toMap()

        override fun upsert(evidence: PurchaseEvidence): Boolean {
            entries[evidence.purchaseToken] = evidence
            everStoredTokens += evidence.purchaseToken
            if (evidence.purchaseState == StoredPurchaseState.PURCHASED) {
                everPurchasedTokens += evidence.purchaseToken
            }
            return true
        }

        override fun loadBindings(): List<StoredPurchaseBinding> = bindings.values.toList()

        override fun upsertBinding(binding: StoredPurchaseBinding): Boolean {
            bindings[binding.obfuscatedAccountId to binding.productIdentity] = binding
            return true
        }

        override fun loadProductMappings(): List<StoredProductMapping> = mappings.values.toList()

        override fun upsertProductMapping(mapping: StoredProductMapping): Boolean {
            mappings[mapping.productIdentity] = mapping
            return true
        }
    }

    private class FakeBilling : PlayBillingGateway {
        val active = mutableMapOf<String, List<PlayPurchase>>()
        val queries = mutableListOf<String>()
        val launches = mutableListOf<CheckoutRequest>()
        val acknowledgedTokens = mutableListOf<String>()
        val consumedTokens = mutableListOf<String>()

        override suspend fun launch(activity: Activity, request: CheckoutRequest): BillingResult {
            launches += request
            return result(BillingClient.BillingResponseCode.OK, "launched")
        }

        override suspend fun queryActive(productType: String): ActivePurchasesResult {
            queries += productType
            return ActivePurchasesResult.Success(active[productType].orEmpty())
        }

        override suspend fun acknowledge(purchaseToken: String): BillingResult {
            acknowledgedTokens += purchaseToken
            return result(BillingClient.BillingResponseCode.OK, "acknowledged")
        }

        override suspend fun consume(purchaseToken: String): BillingResult {
            consumedTokens += purchaseToken
            return result(BillingClient.BillingResponseCode.OK, "consumed")
        }

        private fun result(code: Int, message: String): BillingResult =
            BillingResult.newBuilder()
                .setResponseCode(code)
                .setDebugMessage(message)
                .build()
    }

    private class RecordingDelegate : NuxiePurchaseDelegate {
        class Gate {
            val started = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
        }

        var purchaseResult: PurchaseResult = PurchaseResult.Cancelled
        var restoreResult: RestoreResult = RestoreResult.NoPurchases
        val purchasedProducts = mutableListOf<StoreProduct>()
        var restoreCalls = 0
        private var nextPurchaseGate: Gate? = null

        fun gateNextPurchase(): Gate = Gate().also { gate ->
            check(nextPurchaseGate == null)
            nextPurchaseGate = gate
        }

        override suspend fun purchase(product: StoreProduct): PurchaseResult {
            purchasedProducts += product
            nextPurchaseGate?.also { gate ->
                nextPurchaseGate = null
                gate.started.complete(Unit)
                gate.release.await()
            }
            return purchaseResult
        }

        override suspend fun restorePurchases(): RestoreResult {
            restoreCalls += 1
            return restoreResult
        }
    }

    private fun FixtureProduct.toStandalonePendingPurchase(vectorIndex: Int): PlayPurchase = PlayPurchase(
        purchaseToken = "fixture-pending-$vectorIndex",
        packageName = FIXTURE_PACKAGE_NAME,
        products = listOf(storeProductId),
        state = StoredPurchaseState.PENDING,
        acknowledged = false,
        obfuscatedAccountId = null,
        originalJson = JsonObject(
            mapOf("pending" to JsonPrimitive("fixture-pending-$vectorIndex")),
        ).toString(),
        signature = "fixture-pending-signature-$vectorIndex",
    )

    private fun standalonePendingPurchase(
        product: FixtureProduct,
        vectorIndex: Int,
    ): PlayPurchase = product.toStandalonePendingPurchase(vectorIndex)

    private fun PurchaseCommitObservation.Terminal.toFixtureTerminalOutcome(): TerminalOutcome =
        when (val committedOutcome = outcome) {
            is PurchaseOutcome.Cancelled -> TerminalOutcome(
                kind = "cancelled",
                source = committedOutcome.source.wireValue,
                reason = null,
                terminal = terminal,
            )
            is PurchaseOutcome.Pending -> TerminalOutcome(
                kind = "pending",
                source = committedOutcome.source.wireValue,
                reason = null,
                terminal = terminal,
            )
            is PurchaseOutcome.Failed -> TerminalOutcome(
                kind = "failed",
                source = committedOutcome.source.wireValue,
                reason = (committedOutcome.reason as? BillingUnavailableException)?.debugMessage
                    ?: committedOutcome.reason.message,
                terminal = terminal,
            )
            is PurchaseOutcome.External,
            is PurchaseOutcome.Verified,
            -> error("A successful purchase outcome cannot be a terminal fixture observation.")
        }

    private fun okUpdate(vararg purchases: PlayPurchase): PurchaseUpdate = PurchaseUpdate(
        billingResult(BillingClient.BillingResponseCode.OK, "updated"),
        purchases.toList(),
    )

    private fun billingResult(code: Int, message: String): BillingResult =
        BillingResult.newBuilder()
            .setResponseCode(code)
            .setDebugMessage(message)
            .build()

    private fun activity(): Activity = Robolectric.buildActivity(Activity::class.java).get()

    private fun JsonObject.requiredString(key: String): String =
        getValue(key).jsonPrimitive.content

    private fun JsonObject.optionalString(key: String): String? =
        this[key]?.jsonPrimitive?.content

    private fun JsonObject.requiredInt(key: String): Int =
        getValue(key).jsonPrimitive.int

    private fun JsonObject.optionalInt(key: String): Int? =
        this[key]?.jsonPrimitive?.int

    private fun JsonObject.requiredDouble(key: String): Double =
        getValue(key).jsonPrimitive.double

    private fun JsonObject.requiredBoolean(key: String): Boolean =
        getValue(key).jsonPrimitive.boolean

    private fun JsonObject.optionalBoolean(key: String): Boolean? =
        this[key]?.jsonPrimitive?.boolean

    private fun JsonObject.stringArray(key: String): List<String> =
        getValue(key).jsonArray.map { it.jsonPrimitive.content }

    private companion object {
        const val FIXTURE_PACKAGE_NAME = "com.example.purchaseoutcome"
        const val FIXTURE_LICENSING_KEY = "fixture-public-key"
        const val FIXED_NOW_MILLIS = 1_788_192_000_000L
        const val INITIAL_EXTERNAL_RETRY_MILLIS = 1L
        const val MAX_EXTERNAL_CAPTURE_ATTEMPTS = 4
    }
}
