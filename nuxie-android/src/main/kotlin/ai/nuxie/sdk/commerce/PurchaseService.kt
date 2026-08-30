package ai.nuxie.sdk.commerce

import ai.nuxie.sdk.events.SystemEventNames
import ai.nuxie.sdk.features.FeatureService
import ai.nuxie.sdk.features.FeatureUsageResult
import ai.nuxie.sdk.features.FeatureAllowance
import ai.nuxie.sdk.network.NuxieApi
import android.app.Activity
import com.android.billingclient.api.BillingClient
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

internal class PurchaseSettings(
    delegate: NuxiePurchaseDelegate?,
    handlingMode: PurchaseHandlingMode,
) {
    @Volatile var delegate: NuxiePurchaseDelegate? = delegate
    @Volatile var handlingMode: PurchaseHandlingMode = handlingMode
}

internal sealed interface PurchaseSyncOutcome {
    data class Accepted(val response: NuxieApi.PurchaseResponse) : PurchaseSyncOutcome
    data class Rejected(val permanent: Boolean) : PurchaseSyncOutcome
}

internal fun interface PurchaseSynchronizer {
    suspend fun sync(evidence: PurchaseEvidence): PurchaseSyncOutcome
}

/**
 * Sends durable Play evidence through the production `/purchase` decoding
 * boundary. Retries preserve the purchase token and report identity; the
 * stable purchase-USE event-id half of the fixture lands with
 * [UNIV-2649](https://universe.basis.dev/issue/UNIV-2649).
 */
internal class NuxieApiPurchaseSynchronizer(
    private val api: NuxieApi,
) : PurchaseSynchronizer {
    override suspend fun sync(evidence: PurchaseEvidence): PurchaseSyncOutcome = try {
        val response = api.postPurchase(
            NuxieApi.PlayPurchaseReport(
                packageName = evidence.packageName.takeIf(String::isNotBlank),
                productId = evidence.storeProductIds.firstOrNull()?.takeIf(String::isNotBlank),
                purchaseToken = evidence.purchaseToken,
                basePlanId = evidence.basePlanId,
                offerId = evidence.offerId,
                obfuscatedAccountId = evidence.obfuscatedAccountId,
                distinctId = evidence.syncAttributionDistinctId,
            ),
        )
        if (response.success) PurchaseSyncOutcome.Accepted(response)
        else PurchaseSyncOutcome.Rejected(isPermanentPurchaseRejection(response.body))
    } catch (rejected: NuxieApi.PurchaseRejectedException) {
        PurchaseSyncOutcome.Rejected(rejected.permanent)
    } catch (_: Exception) {
        PurchaseSyncOutcome.Rejected(permanent = false)
    }
}

internal fun isPermanentPurchaseRejection(body: JsonObject): Boolean {
    if ((body["permanent"] as? JsonPrimitive)?.contentOrNull?.toBooleanStrictOrNull() == true) return true
    if ((body["retryable"] as? JsonPrimitive)?.contentOrNull?.toBooleanStrictOrNull() == false) return true
    val explicitReasons = setOf(
        "verification_failed",
        "verification_failure",
        "purchase_verification_failed",
        "invalid_purchase",
        "invalid_purchase_token",
        "invalid_receipt",
        "invalid_signature",
        "permanent",
    )
    // UNIV-2632 will finalize the exact permanent-reason vocabulary.
    return listOf("reason", "code", "error")
        .mapNotNull { key -> (body[key] as? JsonPrimitive)?.contentOrNull }
        .map { value -> value.trim().lowercase().replace('-', '_').replace(' ', '_') }
        .any { it in explicitReasons }
}

/** Owns checkout correlation, durable evidence, reconciliation, and managed completion. */
internal class PurchaseService(
    private val billing: PlayBillingGateway,
    private val evidenceStore: PurchaseEvidenceStore,
    private val synchronizer: PurchaseSynchronizer,
    private val features: FeatureService,
    private val distinctId: () -> String,
    private val emit: (String, Map<String, Any?>) -> Unit,
    private val settings: PurchaseSettings,
    private val scope: CoroutineScope,
    private val nowMillis: () -> Long = System::currentTimeMillis,
    private val initialRetryDelayMillis: Long = 1_000,
    private val maxRetryDelayMillis: Long = 60_000,
    private val api: NuxieApi? = null,
    private val purchaseStorageScope: String,
    private val capturePurchaseSynced: suspend (
        name: String,
        properties: Map<String, Any?>,
        eventId: String,
        distinctId: String,
    ) -> Boolean = { _, _, _, _ -> false },
    private val verifyPurchaseSignature: (String, String, String) -> Boolean =
        PlayPurchaseSignatureVerifier::verify,
) {
    private data class InFlightPurchase(
        val result: CompletableDeferred<PurchaseResult>,
        val owner: String,
        val obfuscatedAccountId: String,
        val priorTokens: Set<String>,
        val product: StoreProduct,
        val nuxieManaged: Boolean,
    )

    private sealed interface UsageCoordination {
        data class AwaitSync(val result: CompletableDeferred<Boolean>) : UsageCoordination
        data class AwaitClaim(val released: CompletableDeferred<Unit>) : UsageCoordination
        data object Claimed : UsageCoordination
    }

    private val products = ConcurrentHashMap<String, StoreProduct>()
    private val inFlight = ConcurrentHashMap<String, InFlightPurchase>()
    private val processing = Mutex()
    private val projectionRefresh = Mutex()
    private val syncRetryJobs = ConcurrentHashMap<String, Job>()
    private val completionRetryJobs = ConcurrentHashMap<String, Job>()
    private val usageCoordinationLock = Any()
    private val syncOperations = mutableMapOf<String, CompletableDeferred<Boolean>>()
    private val purchaseUsageClaims = mutableSetOf<String>()
    private val purchaseUsageWaiters = mutableMapOf<String, MutableList<CompletableDeferred<Unit>>>()

    init {
        // Projection is a local derivation and must not wait for Play Billing
        // connectivity during a cold start.
        evidenceStore.setProductMappingsChangedListener {
            scope.launch { refreshOptimisticProjection() }
        }
        scope.launch { refreshOptimisticProjection() }
    }

    suspend fun useFeatureWithPendingPurchase(
        distinctId: String,
        featureId: String,
        amount: Double,
        entityId: String?,
        metadata: Map<String, Any?>?,
    ): FeatureUsageResult? {
        val usageApi = api ?: return null
        if (!amount.isFinite() || amount <= 0.0) return null
        while (true) {
            if (this.distinctId() != distinctId) throw kotlinx.coroutines.CancellationException()
            val candidates = eligibleEvidence(distinctId, featureId)
            if (candidates.size != 1) return null
            val evidence = candidates.single()
            when (val coordination = coordinateUsage(evidence.purchaseToken)) {
                is UsageCoordination.AwaitSync -> {
                    val synced = coordination.result.await()
                    if (this.distinctId() != distinctId) throw kotlinx.coroutines.CancellationException()
                    if (synced) return null
                    continue
                }
                is UsageCoordination.AwaitClaim -> {
                    coordination.released.await()
                    continue
                }
                UsageCoordination.Claimed -> Unit
            }

            try {
                val claimedCandidates = eligibleEvidence(distinctId, featureId)
                if (claimedCandidates.size != 1 ||
                    claimedCandidates.single().purchaseToken != evidence.purchaseToken
                ) {
                    return null
                }
                val eventId = purchaseUsageEventId(evidence, featureId, amount, entityId)
                val featureScope = features.captureAuthoritativeUseScope(distinctId)
                val response = usageApi.useFeatureWithPurchase(
                    NuxieApi.PurchaseBackedFeatureUseReport(
                        customerId = distinctId,
                        featureId = featureId,
                        requiredBalance = amount,
                        eventData = NuxieApi.FeatureUseEventData(amount, metadata),
                        entityId = entityId,
                        purchase = NuxieApi.PlayPurchaseUseReport(
                            packageName = evidence.packageName,
                            productId = evidence.storeProductIds.first(),
                            purchaseToken = evidence.purchaseToken,
                            basePlanId = evidence.basePlanId,
                            offerId = evidence.offerId,
                            obfuscatedAccountId = evidence.obfuscatedAccountId,
                            eventId = eventId,
                        ),
                    ),
                )
                if (response.customerId != distinctId) {
                    throw IllegalStateException("Atomic Feature-use response customer did not match the purchase owner.")
                }
                val properties = buildMap<String, Any?> {
                    put("transaction_id", evidence.purchaseToken)
                    put("original_transaction_id", evidence.purchaseToken)
                    put("product_id", evidence.storeProductIds.first())
                    put("customer_id", distinctId)
                    evidence.context?.experienceId?.let { put("experience_id", it) }
                    evidence.context?.experienceVersion?.let { put("experience_version", it) }
                }
                if (!capturePurchaseSynced(
                        SystemEventNames.PURCHASE_SYNCED,
                        properties,
                        purchaseSyncedEventId(evidence),
                        distinctId,
                    )
                ) {
                    throw IllegalStateException("Could not durably capture the purchase synchronization event.")
                }
                val current = evidenceStore.load()[evidence.purchaseToken]
                if (current == null || !current.matchesAtomicUsePayload(evidence, distinctId)) {
                    throw IllegalStateException("Could not persist accepted purchase evidence.")
                }
                val accepted = current.copy(
                    synced = true,
                    syncedCustomerId = distinctId,
                    syncedEventEmitted = true,
                    backendSyncedAtMillis = nowMillis(),
                )
                val access = projectionRefresh.withLock {
                    if (!evidenceStore.upsert(accepted)) {
                        throw IllegalStateException("Could not persist accepted purchase evidence.")
                    }
                    val currentDistinctId = this.distinctId()
                    if (currentDistinctId != distinctId) {
                        features.applyOptimisticPurchaseProjection(
                            currentDistinctId,
                            deriveOptimisticProjection(currentDistinctId),
                        )
                        throw kotlinx.coroutines.CancellationException()
                    }
                    features.applyAuthoritativeUse(
                        response,
                        requestedFeatureId = featureId,
                        distinctId = distinctId,
                        entityId = entityId,
                        expectedScope = featureScope,
                        reconciledOptimisticProjection = deriveOptimisticProjection(),
                        reconcileOptimisticProjection = true,
                    )
                }
                releasePurchaseUsageClaim(evidence.purchaseToken)
                completeManaged(accepted)
                return FeatureUsageResult(
                    success = true,
                    featureId = featureId,
                    amountUsed = amount,
                    message = null,
                    usage = null,
                    authoritativeAccess = access,
                )
            } finally {
                releasePurchaseUsageClaim(evidence.purchaseToken)
            }
        }
    }

    private fun eligibleEvidence(distinctId: String, featureId: String): List<PurchaseEvidence> {
        val descriptors = evidenceStore.loadProductMappings()
        val bindings = evidenceStore.loadBindings()
        return evidenceStore.load().values.filter { evidence ->
            evidence.authorityScope == purchaseStorageScope &&
                evidence.ownerDistinctId == distinctId &&
                evidence.purchaseState == StoredPurchaseState.PURCHASED &&
                !evidence.revoked &&
                !evidence.permanentlyRejected &&
                !evidence.synced &&
                evidence.backendSyncedAtMillis == null &&
                evidence.purchaseToken.isNotBlank() &&
                evidence.packageName.isNotBlank() &&
                evidence.storeProductIds.firstOrNull()?.isNotBlank() == true &&
                evidence.signatureVerified &&
                featureAllowancesForEvidence(evidence, descriptors, bindings).any { it.featureId == featureId }
        }
    }

    private fun PurchaseEvidence.matchesAtomicUsePayload(
        sent: PurchaseEvidence,
        expectedOwner: String,
    ): Boolean =
        authorityScope == purchaseStorageScope &&
            ownerDistinctId == expectedOwner &&
            purchaseToken == sent.purchaseToken &&
            packageName == sent.packageName &&
            storeProductIds.firstOrNull() == sent.storeProductIds.firstOrNull() &&
            basePlanId == sent.basePlanId &&
            offerId == sent.offerId &&
            obfuscatedAccountId == sent.obfuscatedAccountId

    private fun coordinateUsage(purchaseToken: String): UsageCoordination =
        synchronized(usageCoordinationLock) {
            syncOperations[purchaseToken]?.let { return@synchronized UsageCoordination.AwaitSync(it) }
            if (purchaseToken in purchaseUsageClaims) {
                val waiter = CompletableDeferred<Unit>()
                purchaseUsageWaiters.getOrPut(purchaseToken, ::mutableListOf).add(waiter)
                UsageCoordination.AwaitClaim(waiter)
            } else {
                purchaseUsageClaims.add(purchaseToken)
                UsageCoordination.Claimed
            }
        }

    private fun releasePurchaseUsageClaim(purchaseToken: String) {
        val waiters = synchronized(usageCoordinationLock) {
            if (!purchaseUsageClaims.remove(purchaseToken)) return
            purchaseUsageWaiters.remove(purchaseToken).orEmpty()
        }
        waiters.forEach { it.complete(Unit) }
    }

    private fun purchaseUsageEventId(
        evidence: PurchaseEvidence,
        featureId: String,
        amount: Double,
        entityId: String?,
    ): String = stableEventId(
        "purchase-use:",
        listOf(
            purchaseStorageScope,
            evidence.purchaseToken,
            featureId,
            entityId.orEmpty(),
            java.lang.Double.doubleToRawLongBits(amount).toString(),
        ),
    )

    private fun purchaseSyncedEventId(evidence: PurchaseEvidence): String = stableEventId(
        "purchase-synced:",
        listOf(
            purchaseStorageScope,
            SystemEventNames.PURCHASE_SYNCED,
            evidence.ownerDistinctId.orEmpty(),
            evidence.purchaseToken,
        ),
    )

    private fun stableEventId(prefix: String, components: List<String>): String = prefix + sha256(
        components.joinToString("\u001f"),
    )

    suspend fun purchase(
        activity: Activity,
        product: StoreProduct,
        replacement: SubscriptionReplacement?,
    ): PurchaseResult {
        val initiatingOwner = distinctId()
        settings.delegate?.let { delegate ->
            val outcome = delegate.purchase(product)
            if (outcome == PurchaseResult.Purchased) {
                emitPurchaseCompleted(product, transactionId = null, initiatingOwner)
            } else {
                emitPurchaseOutcome(product, outcome, initiatingOwner)
            }
            return outcome
        }
        products[product.storeProductId] = product
        val owner = initiatingOwner
        val accountId = sha256(owner)
        if (!evidenceStore.upsertProductMapping(product.toMapping()) ||
            !evidenceStore.upsertBinding(product.toBinding(accountId, owner))
        ) {
            return failed(product, IllegalStateException("Could not persist purchase catalog mapping."), owner)
        }
        val active = when (val queried = billing.queryActive(product.productType)) {
            is ActivePurchasesResult.Failed -> return failed(
                product,
                BillingUnavailableException(queried.responseCode, queried.debugMessage),
                owner,
            )
            is ActivePurchasesResult.Success -> queried.purchases
        }
        if (product.productType == BillingClient.ProductType.SUBS && replacement == null && active.isNotEmpty()) {
            return failed(product, SubscriptionReplacementRequiredException(), owner)
        }
        val priorTokens = active.mapTo(mutableSetOf()) { it.purchaseToken }

        val result = CompletableDeferred<PurchaseResult>()
        val pending = InFlightPurchase(
            result,
            owner,
            accountId,
            priorTokens,
            product,
            settings.handlingMode == PurchaseHandlingMode.NUXIE_MANAGED,
        )
        val registered = synchronized(inFlight) {
            if (inFlight.isNotEmpty()) false else {
                inFlight[product.storeProductId] = pending
                true
            }
        }
        if (!registered) {
            return failed(product, IllegalStateException("Another Play purchase is already in flight."), owner)
        }
        val launch = runCatching {
            billing.launch(
                activity,
                CheckoutRequest(product, accountId, replacement),
            )
        }.getOrElse {
            inFlight.remove(product.storeProductId, pending)
            return failed(product, it, owner)
        }
        if (launch.responseCode != BillingClient.BillingResponseCode.OK) {
            inFlight.remove(product.storeProductId, pending)
            return if (launch.responseCode == BillingClient.BillingResponseCode.USER_CANCELED) {
                PurchaseResult.Cancelled.also { emitPurchaseOutcome(product, it, owner) }
            } else {
                failed(product, BillingUnavailableException(launch.responseCode, launch.debugMessage), owner)
            }
        }
        return result.await()
    }

    suspend fun restorePurchases(): RestoreResult {
        val initiatingOwner = distinctId()
        settings.delegate?.let {
            return it.restorePurchases().also { outcome -> emitRestoreOutcome(outcome, initiatingOwner) }
        }
        val found = mutableListOf<PlayPurchase>()
        for (type in listOf(BillingClient.ProductType.SUBS, BillingClient.ProductType.INAPP)) {
            when (val result = billing.queryActive(type)) {
                is ActivePurchasesResult.Success -> found += result.purchases
                is ActivePurchasesResult.Failed -> return RestoreResult.Failed(
                    BillingUnavailableException(result.responseCode, result.debugMessage),
                ).also { outcome -> emitRestoreOutcome(outcome, initiatingOwner) }
            }
        }
        revokeMissingOptimistic(found.mapTo(mutableSetOf()) { it.purchaseToken })
        if (found.isEmpty()) {
            return RestoreResult.NoPurchases.also { emitRestoreOutcome(it, initiatingOwner) }
        }
        processPurchases(found)
        return RestoreResult.Restored.also { emitRestoreOutcome(it, initiatingOwner) }
    }

    suspend fun onPurchasesUpdated(update: PurchaseUpdate) {
        if (update.billingResult.responseCode == BillingClient.BillingResponseCode.USER_CANCELED) {
            completeAll(PurchaseResult.Cancelled)
            return
        }
        if (update.billingResult.responseCode != BillingClient.BillingResponseCode.OK) {
            completeAll(
                PurchaseResult.Failed(
                    BillingUnavailableException(
                        update.billingResult.responseCode,
                        update.billingResult.debugMessage,
                    ),
                ),
            )
            return
        }
        processPurchases(update.purchases.orEmpty())
    }

    /** Billing connect and app foreground share one recovery lane. */
    suspend fun recover() {
        processing.withLock {
            refreshOptimisticProjection()
            val active = mutableListOf<PlayPurchase>()
            var allQueriesSucceeded = true
            for (type in listOf(BillingClient.ProductType.SUBS, BillingClient.ProductType.INAPP)) {
                val result = runCatching { billing.queryActive(type) }.getOrNull()
                if (result is ActivePurchasesResult.Success) {
                    active += result.purchases
                } else {
                    allQueriesSucceeded = false
                }
            }
            active.forEach { processPurchase(it) }
            val activeTokens = active.mapTo(mutableSetOf()) { it.purchaseToken }
            if (allQueriesSucceeded) revokeMissingOptimistic(activeTokens)
            val bindings = evidenceStore.loadBindings()
            val mappings = evidenceStore.loadProductMappings()
            val evidenceRecords = evidenceStore.load().values.mapNotNull { storedEvidence ->
                val normalized = if (!storedEvidence.signatureVerificationRequired &&
                    hasConfiguredLicensingKey(storedEvidence, bindings, mappings)
                ) {
                    storedEvidence.copy(signatureVerificationRequired = true)
                } else {
                    storedEvidence
                }
                if (normalized != storedEvidence && !evidenceStore.upsert(normalized)) null else normalized
            }
            evidenceRecords
                .filter {
                    it.permanentlyRejected &&
                        it.purchaseState == StoredPurchaseState.PURCHASED
                }
                .forEach { revokeEvidence(it) }
            evidenceRecords
                .filter {
                    !it.permanentlyRejected &&
                        !it.revoked &&
                        it.purchaseState == StoredPurchaseState.PURCHASED &&
                        (!it.synced || !it.syncedEventEmitted || it.needsManagedCompletion())
                }
                .forEach { evidence ->
                    if (evidence.synced) {
                        val current = emitPurchaseSyncedIfNeeded(evidence)
                        completeManaged(current)
                    } else {
                        syncEvidence(evidence)
                    }
                }
        }
    }

    private suspend fun processPurchases(purchases: List<PlayPurchase>) {
        processing.withLock { purchases.forEach { processPurchase(it) } }
    }

    private suspend fun processPurchase(purchase: PlayPurchase) {
        val existing = evidenceStore.load()[purchase.purchaseToken]
        val bindings = evidenceStore.loadBindings()
        val mappings = evidenceStore.loadProductMappings()
        val matchingFlight = purchase.products.firstNotNullOfOrNull(inFlight::get)
        val isCheckoutOutcome = matchingFlight?.matches(purchase) == true
        val product = purchase.products.firstNotNullOfOrNull(products::get) ?: matchingFlight?.product
        val exactBinding = bindings.firstOrNull { binding ->
            binding.obfuscatedAccountId == purchase.obfuscatedAccountId &&
                binding.storeProductId in purchase.products
        }
        val catalogBinding = exactBinding ?: bindings.firstOrNull { it.storeProductId in purchase.products }
        val catalogMapping = mappings.firstOrNull { it.storeProductId in purchase.products }
        val currentOwner = distinctId()
        val ownerDistinctId = exactBinding?.distinctId
            ?: currentOwner.takeIf { purchase.obfuscatedAccountId == sha256(it) }
            ?: matchingFlight?.owner?.takeIf {
                purchase.obfuscatedAccountId == matchingFlight.obfuscatedAccountId
            }
            ?: existing?.ownerDistinctId
        val syncAttributionDistinctId = existing?.syncAttributionDistinctId
            ?.takeIf(String::isNotBlank)
            ?: ownerDistinctId
            ?: currentOwner
        val licensingPublicKey = product?.licensingPublicKey ?: catalogBinding?.licensingPublicKey
            ?: catalogMapping?.licensingPublicKey
        val signatureVerificationRequired = existing?.signatureVerificationRequired == true ||
            licensingPublicKey != null
        val signatureVerified = existing?.signatureVerified == true ||
            (licensingPublicKey != null && verifyPurchaseSignature(
                licensingPublicKey,
                purchase.originalJson,
                purchase.signature,
            ))
        val evidence = PurchaseEvidence(
            purchaseToken = purchase.purchaseToken,
            packageName = purchase.packageName,
            storeProductIds = purchase.products,
            nuxieProductId = existing?.nuxieProductId ?: product?.productId
                ?: catalogBinding?.nuxieProductId ?: catalogMapping?.nuxieProductId,
            basePlanId = existing?.basePlanId ?: product?.basePlanId
                ?: catalogBinding?.basePlanId ?: catalogMapping?.basePlanId,
            offerId = existing?.offerId ?: product?.offerId
                ?: catalogBinding?.offerId ?: catalogMapping?.offerId,
            purchaseState = purchase.state,
            obfuscatedAccountId = purchase.obfuscatedAccountId ?: existing?.obfuscatedAccountId
                ?: sha256(ownerDistinctId ?: syncAttributionDistinctId),
            syncAttributionDistinctId = syncAttributionDistinctId,
            ownerDistinctId = ownerDistinctId,
            context = existing?.context ?: product?.toStoredContext()
                ?: catalogBinding?.context ?: catalogMapping?.context,
            acknowledged = purchase.acknowledged || existing?.acknowledged == true,
            consumed = existing?.consumed == true,
            synced = existing?.synced == true,
            permanentlyRejected = existing?.permanentlyRejected == true,
            syncAttempts = existing?.syncAttempts ?: 0,
            completionAttempts = existing?.completionAttempts ?: 0,
            firstSeenMillis = existing?.firstSeenMillis ?: nowMillis(),
            consumable = existing?.consumable ?: product?.consumable
                ?: catalogBinding?.consumable ?: catalogMapping?.consumable ?: false,
            catalogResolved = existing?.catalogResolved == true || product != null ||
                catalogBinding != null || catalogMapping != null,
            completionEmitted = existing?.completionEmitted == true,
            syncedEventEmitted = existing?.syncedEventEmitted == true,
            syncedCustomerId = existing?.syncedCustomerId,
            acceptedResponseBody = existing?.acceptedResponseBody,
            nuxieManaged = existing?.nuxieManaged
                ?: matchingFlight?.nuxieManaged?.takeIf { isCheckoutOutcome }
                ?: (settings.handlingMode == PurchaseHandlingMode.NUXIE_MANAGED),
            signatureVerificationRequired = signatureVerificationRequired,
            signatureVerified = signatureVerified,
            authorityScope = purchaseStorageScope,
            revoked = false,
            backendSyncedAtMillis = existing?.backendSyncedAtMillis,
        )
        // D2: evidence is durable before projection, facts, sync, acknowledge, or consume.
        if (!evidenceStore.upsert(evidence)) {
            complete(purchase, PurchaseResult.Failed(IllegalStateException("Could not persist purchase evidence.")))
            return
        }
        if (ownerDistinctId != null &&
            existing?.ownerDistinctId != ownerDistinctId &&
            (existing?.ownerDistinctId ?: existing?.syncAttributionDistinctId) == currentOwner
        ) {
            projectionRefresh.withLock {
                features.reassignPurchaseAuthority(
                    currentOwner,
                    evidence.purchaseToken,
                    deriveOptimisticProjection(currentOwner),
                )
            }
        }
        if (purchase.state == StoredPurchaseState.PENDING) {
            complete(purchase, PurchaseResult.Pending)
            return
        }
        if (evidence.permanentlyRejected) {
            revokeEvidence(evidence)
            return
        }
        if (evidence.signatureVerificationRequired && !evidence.signatureVerified) {
            revokeEvidence(evidence)
            val current = evidenceStore.load()[evidence.purchaseToken] ?: evidence
            evidenceStore.upsert(current.copy(permanentlyRejected = true, revoked = true))
            complete(purchase, PurchaseResult.Failed(SecurityException("Play purchase signature is invalid.")))
            return
        }

        refreshOptimisticProjection()
        var currentEvidence = evidence
        if (evidence.canProjectTo(distinctId())) {
            evidence.acceptedResponseBody?.let { body ->
                features.updateFromPurchase(evidence.ownerDistinctId!!, body, purchase.purchaseToken)
            }
            if (isCheckoutOutcome && !evidence.completionEmitted && emitPurchaseCompleted(evidence)) {
                currentEvidence = evidence.copy(completionEmitted = true)
                evidenceStore.upsert(currentEvidence)
            }
        }
        complete(purchase, PurchaseResult.Purchased)
        if (!currentEvidence.permanentlyRejected) {
            if (currentEvidence.synced) completeManaged(currentEvidence) else syncEvidence(currentEvidence)
        }
    }

    private suspend fun syncEvidence(original: PurchaseEvidence): Boolean {
        val operation: CompletableDeferred<Boolean>
        val ownsOperation: Boolean
        synchronized(usageCoordinationLock) {
            if (original.purchaseToken in purchaseUsageClaims) return false
            val existing = syncOperations[original.purchaseToken]
            if (existing != null) {
                operation = existing
                ownsOperation = false
            } else {
                operation = CompletableDeferred()
                syncOperations[original.purchaseToken] = operation
                ownsOperation = true
            }
        }
        if (!ownsOperation) return operation.await()
        val synced = try {
            performSyncEvidence(original)
        } catch (_: Exception) {
            false
        }
        operation.complete(synced)
        synchronized(usageCoordinationLock) {
            if (syncOperations[original.purchaseToken] === operation) {
                syncOperations.remove(original.purchaseToken)
            }
        }
        return synced
    }

    private suspend fun performSyncEvidence(original: PurchaseEvidence): Boolean {
        val attempted = original.copy(syncAttempts = original.syncAttempts + 1)
        if (!evidenceStore.upsert(attempted)) return false
        when (val outcome = runCatching { synchronizer.sync(attempted) }
            .getOrElse { PurchaseSyncOutcome.Rejected(permanent = false) }) {
            is PurchaseSyncOutcome.Rejected -> {
                if (outcome.permanent) {
                    evidenceStore.upsert(attempted.copy(permanentlyRejected = true, revoked = true))
                    revokeEvidence(attempted)
                } else {
                    scheduleSyncRetry(attempted.purchaseToken, attempted.syncAttempts)
                }
                return false
            }
            is PurchaseSyncOutcome.Accepted -> {
                val provenOwner = currentProvenOwner(attempted)
                val accepted = attempted.copy(
                    ownerDistinctId = provenOwner ?: attempted.ownerDistinctId,
                    synced = true,
                    syncedCustomerId = outcome.response.customerId,
                    acceptedResponseBody = outcome.response.body,
                    backendSyncedAtMillis = nowMillis(),
                )
                val projectionOwner = provenOwner ?: accepted.syncAttributionDistinctId
                val persisted = projectionRefresh.withLock {
                    if (!evidenceStore.upsert(accepted)) return@withLock false
                    if (projectionOwner == distinctId()) {
                        features.reconcilePurchase(
                            projectionOwner,
                            outcome.response.body,
                            accepted.purchaseToken,
                            deriveOptimisticProjection(),
                        )
                    }
                    true
                }
                if (!persisted) return false
                completeManaged(emitPurchaseSyncedIfNeeded(accepted))
                return true
            }
        }
    }

    private fun currentProvenOwner(evidence: PurchaseEvidence): String? {
        val boundOwner = evidence.obfuscatedAccountId?.let { accountId ->
            evidenceStore.loadBindings().firstOrNull { binding ->
                binding.obfuscatedAccountId == accountId &&
                    binding.storeProductId in evidence.storeProductIds
            }?.distinctId
        }
        return boundOwner ?: evidenceStore.load()[evidence.purchaseToken]?.ownerDistinctId
    }

    private fun emitPurchaseSyncedIfNeeded(evidence: PurchaseEvidence): PurchaseEvidence {
        if (evidence.syncedEventEmitted || evidence.syncAttributionDistinctId != distinctId()) return evidence
        val properties = buildMap<String, Any?> {
            put("transaction_id", evidence.purchaseToken)
            put("original_transaction_id", evidence.purchaseToken)
            put("product_id", evidence.storeProductIds.firstOrNull().orEmpty())
            put("customer_id", evidence.syncedCustomerId.orEmpty())
            evidence.context?.experienceId?.let { put("experience_id", it) }
            evidence.context?.experienceVersion?.let { put("experience_version", it) }
        }
        emit(SystemEventNames.PURCHASE_SYNCED, properties)
        val emitted = evidence.copy(syncedEventEmitted = true)
        return if (evidenceStore.upsert(emitted)) emitted else evidence
    }

    private suspend fun completeManaged(evidence: PurchaseEvidence) {
        if (!evidence.needsManagedCompletion()) return
        if (completionRetryJobs[evidence.purchaseToken]?.isActive == true) return
        val attempted = evidence.copy(completionAttempts = evidence.completionAttempts + 1)
        if (!evidenceStore.upsert(attempted)) return
        val completion = if (attempted.consumable) {
            billing.consume(evidence.purchaseToken)
        } else {
            billing.acknowledge(evidence.purchaseToken)
        }
        if (completion.responseCode == BillingClient.BillingResponseCode.OK) {
            evidenceStore.upsert(
                if (attempted.consumable) attempted.copy(consumed = true)
                else attempted.copy(acknowledged = true),
            )
        } else {
            scheduleCompletionRetry(evidence.purchaseToken, attempted.completionAttempts)
        }
    }

    private fun PurchaseEvidence.needsManagedCompletion(): Boolean =
        nuxieManaged && catalogResolved && !acknowledged && !consumed

    private fun PurchaseEvidence.canProjectTo(currentDistinctId: String): Boolean =
        ownerDistinctId == currentDistinctId &&
            signatureVerified

    private fun hasConfiguredLicensingKey(
        evidence: PurchaseEvidence,
        bindings: List<StoredPurchaseBinding>,
        mappings: List<StoredProductMapping>,
    ): Boolean = bindings.any {
        it.licensingPublicKey != null && it.storeProductId in evidence.storeProductIds
    } || mappings.any {
        it.licensingPublicKey != null && it.storeProductId in evidence.storeProductIds
    }

    private suspend fun revokeEvidence(evidence: PurchaseEvidence) {
        val current = evidenceStore.load()[evidence.purchaseToken] ?: evidence
        evidenceStore.upsert(current.copy(revoked = true))
        refreshOptimisticProjection()
    }

    private suspend fun revokeMissingOptimistic(activeTokens: Set<String>) {
        evidenceStore.load().values
            .filter {
                !it.synced && it.ownerDistinctId == distinctId() &&
                    it.purchaseState == StoredPurchaseState.PURCHASED &&
                    it.purchaseToken !in activeTokens
            }
            .forEach { evidenceStore.upsert(it.copy(revoked = true)) }
        refreshOptimisticProjection()
    }

    private fun scheduleSyncRetry(token: String, attempt: Int) {
        if (syncRetryJobs[token]?.isActive == true) return
        syncRetryJobs[token] = scope.launch {
            delay(retryDelay(attempt))
            syncRetryJobs.remove(token)
            evidenceStore.load()[token]
                ?.takeIf { !it.permanentlyRejected && !it.revoked && !it.synced }
                ?.let {
                    processing.withLock {
                        syncEvidence(it)
                    }
                }
        }
    }

    private fun scheduleCompletionRetry(token: String, attempt: Int) {
        if (completionRetryJobs[token]?.isActive == true) return
        completionRetryJobs[token] = scope.launch {
            delay(retryDelay(attempt))
            completionRetryJobs.remove(token)
            evidenceStore.load()[token]
                ?.takeIf { !it.permanentlyRejected && it.synced && it.needsManagedCompletion() }
                ?.let {
                    processing.withLock {
                        completeManaged(emitPurchaseSyncedIfNeeded(it))
                    }
                }
        }
    }

    private fun retryDelay(attempt: Int): Long =
        (initialRetryDelayMillis * (1L shl attempt.coerceAtMost(16)))
            .coerceAtMost(maxRetryDelayMillis)

    private fun emitPurchaseCompleted(evidence: PurchaseEvidence): Boolean {
        if (evidence.ownerDistinctId != distinctId()) return false
        val properties = linkedMapOf<String, Any?>(
            "product_id" to (evidence.nuxieProductId ?: evidence.storeProductIds.firstOrNull().orEmpty()),
            "placement_id" to evidence.context?.placementId,
            "store_product_id" to evidence.storeProductIds.firstOrNull().orEmpty(),
            "experience_id" to evidence.context?.experienceId,
            "source" to "purchase",
            "test_store" to false,
            "transaction_id" to evidence.purchaseToken,
            "price" to evidence.context?.price?.toDouble(),
            "display_price" to evidence.context?.displayPrice,
        ).filterValues { it != null }
        emit(SystemEventNames.PURCHASE_COMPLETED, properties)
        return true
    }

    private fun emitPurchaseCompleted(
        product: StoreProduct,
        transactionId: String?,
        initiatingOwner: String,
    ) {
        if (distinctId() != initiatingOwner) return
        val price = product.storePrice()
        val properties = linkedMapOf<String, Any?>(
            "product_id" to product.productId,
            "placement_id" to product.placementId,
            "store_product_id" to product.storeProductId,
            "experience_id" to product.purchaseContext?.experienceId,
            "source" to "purchase",
            "test_store" to false,
            "transaction_id" to transactionId,
            "price" to price?.amount?.toDouble(),
            "display_price" to price?.display,
        ).filterValues { it != null }
        emit(SystemEventNames.PURCHASE_COMPLETED, properties)
    }

    private fun complete(purchase: PlayPurchase, result: PurchaseResult) {
        purchase.products.forEach { productId ->
            val pending = inFlight[productId] ?: return@forEach
            if (pending.matches(purchase) && inFlight.remove(productId, pending)) {
                emitPurchaseOutcome(pending.product, result, pending.owner)
                pending.result.complete(result)
            }
        }
    }

    private fun InFlightPurchase.matches(purchase: PlayPurchase): Boolean =
        purchase.obfuscatedAccountId == obfuscatedAccountId &&
            purchase.purchaseToken !in priorTokens &&
            product.storeProductId in purchase.products

    private fun completeAll(result: PurchaseResult) {
        val keys = inFlight.keys()
        while (keys.hasMoreElements()) {
            inFlight.remove(keys.nextElement())?.let { pending ->
                emitPurchaseOutcome(pending.product, result, pending.owner)
                pending.result.complete(result)
            }
        }
    }

    private fun failed(
        product: StoreProduct,
        cause: Throwable,
        initiatingOwner: String,
    ): PurchaseResult.Failed =
        PurchaseResult.Failed(cause).also { emitPurchaseOutcome(product, it, initiatingOwner) }

    private fun emitPurchaseOutcome(
        product: StoreProduct,
        result: PurchaseResult,
        initiatingOwner: String,
    ) {
        if (distinctId() != initiatingOwner) return
        val price = product.storePrice()
        val properties = linkedMapOf<String, Any?>(
            "product_id" to product.productId,
            "store_product_id" to product.storeProductId,
            "placement_id" to product.placementId,
            "experience_id" to product.purchaseContext?.experienceId,
            "test_store" to false,
            "price" to price?.amount?.toDouble(),
            "display_price" to price?.display,
        ).filterValues { it != null }.toMutableMap()
        when (result) {
            PurchaseResult.Purchased -> return
            PurchaseResult.Cancelled -> emit(SystemEventNames.PURCHASE_CANCELLED, properties)
            PurchaseResult.Pending -> emit(SystemEventNames.PURCHASE_PENDING, properties)
            is PurchaseResult.Failed -> {
                properties["error"] = result.cause.message ?: result.cause.javaClass.simpleName
                emit(SystemEventNames.PURCHASE_FAILED, properties)
            }
        }
    }

    private fun emitRestoreOutcome(result: RestoreResult, initiatingOwner: String) {
        if (distinctId() != initiatingOwner) return
        when (result) {
            RestoreResult.Restored -> emit(SystemEventNames.RESTORE_COMPLETED, emptyMap())
            RestoreResult.NoPurchases -> emit(SystemEventNames.RESTORE_NO_PURCHASES, emptyMap())
            is RestoreResult.Failed -> emit(
                SystemEventNames.RESTORE_FAILED,
                mapOf("error" to (result.cause.message ?: result.cause.javaClass.simpleName)),
            )
        }
    }

    private fun StoreProduct.toBinding(accountId: String, owner: String) = StoredPurchaseBinding(
        obfuscatedAccountId = accountId,
        distinctId = owner,
        storeProductId = storeProductId,
        nuxieProductId = productId,
        basePlanId = basePlanId,
        offerId = offerId,
        productType = productType,
        consumable = consumable,
        context = toStoredContext(),
        featureAllowances = featureAllowances.toStoredAllowances(),
        licensingPublicKey = licensingPublicKey,
        nuxieManaged = settings.handlingMode == PurchaseHandlingMode.NUXIE_MANAGED,
    )

    internal fun rememberProduct(product: StoreProduct): Boolean =
        evidenceStore.upsertProductMapping(product.toMapping()).also { persisted ->
            if (persisted) scope.launch { refreshOptimisticProjection() }
        }

    private fun StoreProduct.toMapping() = StoredProductMapping(
        storeProductId = storeProductId,
        nuxieProductId = productId,
        basePlanId = basePlanId,
        offerId = offerId,
        productType = productType,
        consumable = consumable,
        context = toStoredContext(),
        featureAllowances = featureAllowances.toStoredAllowances(),
        licensingPublicKey = licensingPublicKey,
    )

    private fun StoreProduct.toStoredContext(): StoredPurchaseContext {
        val price = storePrice()
        return StoredPurchaseContext(
            placementId,
            purchaseContext?.experienceId,
            purchaseContext?.experienceVersion,
            price?.amount,
            price?.display,
        )
    }

    private fun List<FeatureAllowance>.toStoredAllowances() = map {
        StoredFeatureAllowance(it.featureId, it.type.name, it.unlimited, it.allowance)
    }

    private suspend fun refreshOptimisticProjection() {
        projectionRefresh.withLock {
            val currentDistinctId = distinctId()
            features.applyOptimisticPurchaseProjection(
                currentDistinctId,
                deriveOptimisticProjection(currentDistinctId),
            )
        }
    }

    private fun deriveOptimisticProjection(currentDistinctId: String = distinctId()) =
        optimisticFeatureProjection(
            distinctId = currentDistinctId,
            authorityScope = purchaseStorageScope,
            evidence = evidenceStore.load().values,
            descriptors = evidenceStore.loadProductMappings(),
            bindings = evidenceStore.loadBindings(),
        )

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.encodeToByteArray())
        .joinToString("") { "%02x".format(it) }
}
