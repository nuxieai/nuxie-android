package ai.nuxie.sdk.commerce

import ai.nuxie.sdk.events.SystemEventNames
import ai.nuxie.sdk.features.FeatureService
import ai.nuxie.sdk.features.FeatureType
import ai.nuxie.sdk.features.LocalPurchaseGrant
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
    fun sync(evidence: PurchaseEvidence): PurchaseSyncOutcome
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
) {
    private data class InFlightPurchase(
        val result: CompletableDeferred<PurchaseResult>,
        val owner: String,
        val obfuscatedAccountId: String,
        val priorTokens: Set<String>,
        val product: StoreProduct,
        val nuxieManaged: Boolean,
    )

    private val products = ConcurrentHashMap<String, StoreProduct>()
    private val inFlight = ConcurrentHashMap<String, InFlightPurchase>()
    private val processing = Mutex()
    private val retryJobs = ConcurrentHashMap<String, Job>()

    suspend fun purchase(
        activity: Activity,
        product: StoreProduct,
        replacement: SubscriptionReplacement?,
    ): PurchaseResult {
        val initiatingOwner = distinctId()
        settings.delegate?.let { delegate ->
            val outcome = delegate.purchase(product)
            if (outcome == PurchaseResult.Purchased && distinctId() == initiatingOwner) {
                emitPurchaseCompleted(product, transactionId = null)
            }
            return outcome
        }
        products[product.storeProductId] = product
        val owner = initiatingOwner
        val accountId = sha256(owner)
        if (!evidenceStore.upsertProductMapping(product.toMapping()) ||
            !evidenceStore.upsertBinding(product.toBinding(accountId, owner))
        ) {
            return PurchaseResult.Failed(IllegalStateException("Could not persist purchase catalog mapping."))
        }
        val active = when (val queried = billing.queryActive(product.productType)) {
            is ActivePurchasesResult.Failed -> return PurchaseResult.Failed(
                BillingUnavailableException(queried.responseCode, queried.debugMessage),
            )
            is ActivePurchasesResult.Success -> queried.purchases
        }
        if (product.productType == BillingClient.ProductType.SUBS && replacement == null && active.isNotEmpty()) {
            return PurchaseResult.Failed(SubscriptionReplacementRequiredException())
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
            return PurchaseResult.Failed(
                IllegalStateException("Another Play purchase is already in flight."),
            )
        }
        val launch = runCatching {
            billing.launch(
                activity,
                CheckoutRequest(product, accountId, replacement),
            )
        }.getOrElse {
            inFlight.remove(product.storeProductId, pending)
            return PurchaseResult.Failed(it)
        }
        if (launch.responseCode != BillingClient.BillingResponseCode.OK) {
            inFlight.remove(product.storeProductId, pending)
            return if (launch.responseCode == BillingClient.BillingResponseCode.USER_CANCELED) {
                PurchaseResult.Cancelled
            } else {
                PurchaseResult.Failed(
                    BillingUnavailableException(launch.responseCode, launch.debugMessage),
                )
            }
        }
        return result.await()
    }

    suspend fun restorePurchases(): RestoreResult {
        settings.delegate?.let { return it.restorePurchases() }
        val found = mutableListOf<PlayPurchase>()
        for (type in listOf(BillingClient.ProductType.SUBS, BillingClient.ProductType.INAPP)) {
            when (val result = billing.queryActive(type)) {
                is ActivePurchasesResult.Success -> found += result.purchases
                is ActivePurchasesResult.Failed -> return RestoreResult.Failed(
                    BillingUnavailableException(result.responseCode, result.debugMessage),
                )
            }
        }
        revokeMissingOptimistic(found.mapTo(mutableSetOf()) { it.purchaseToken })
        if (found.isEmpty()) return RestoreResult.NoPurchases
        processPurchases(found)
        return RestoreResult.Restored
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
            evidenceStore.load().values
                .filter {
                    it.distinctId.isNotBlank() && !it.permanentlyRejected &&
                        it.purchaseState == StoredPurchaseState.PURCHASED &&
                        (!it.synced || !it.syncedEventEmitted || it.needsManagedCompletion())
                }
                .forEach { evidence ->
                    if (!evidence.synced && evidence.distinctId == distinctId()) {
                        val noLongerActive = allQueriesSucceeded && evidence.purchaseToken !in activeTokens
                        if (noLongerActive) {
                            features.removeLocalPurchase(evidence.purchaseToken)
                        } else {
                            features.applyLocalPurchase(
                                evidence.localFeatureGrants.toFeatureGrants(),
                                evidence.purchaseToken,
                            )
                        }
                    }
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
        val owner = existing?.distinctId?.takeIf(String::isNotBlank)
            ?: exactBinding?.distinctId
            ?: currentOwner.takeIf { purchase.obfuscatedAccountId == sha256(it) }
            ?: matchingFlight?.owner?.takeIf {
                purchase.obfuscatedAccountId == matchingFlight.obfuscatedAccountId
            }
        val durableOwner = owner.orEmpty()
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
                ?: owner?.let(::sha256),
            distinctId = durableOwner,
            context = existing?.context ?: product?.toStoredContext()
                ?: catalogBinding?.context ?: catalogMapping?.context,
            acknowledged = purchase.acknowledged || existing?.acknowledged == true,
            consumed = existing?.consumed == true,
            synced = existing?.synced == true,
            permanentlyRejected = existing?.permanentlyRejected == true,
            syncAttempts = existing?.syncAttempts ?: 0,
            firstSeenMillis = existing?.firstSeenMillis ?: nowMillis(),
            consumable = existing?.consumable ?: product?.consumable
                ?: catalogBinding?.consumable ?: catalogMapping?.consumable ?: false,
            localFeatureGrants = existing?.localFeatureGrants
                ?: product?.localFeatureGrants?.toStoredGrants()
                ?: catalogBinding?.localFeatureGrants
                ?: catalogMapping?.localFeatureGrants.orEmpty(),
            catalogResolved = existing?.catalogResolved == true || product != null ||
                catalogBinding != null || catalogMapping != null,
            completionEmitted = existing?.completionEmitted == true,
            syncedEventEmitted = existing?.syncedEventEmitted == true,
            syncedCustomerId = existing?.syncedCustomerId,
            nuxieManaged = existing?.nuxieManaged?.takeIf { existing.distinctId.isNotBlank() }
                ?: matchingFlight?.nuxieManaged
                ?: exactBinding?.nuxieManaged
                ?: (settings.handlingMode == PurchaseHandlingMode.NUXIE_MANAGED),
        )
        // D2: evidence is durable before grants, facts, sync, acknowledge, or consume.
        if (!evidenceStore.upsert(evidence)) {
            complete(purchase, PurchaseResult.Failed(IllegalStateException("Could not persist purchase evidence.")))
            return
        }
        // D5: preserve unknown-owner evidence, but never grant or report it as
        // the currently active customer. A later matching binding can adopt it.
        if (owner == null) return

        if (purchase.state == StoredPurchaseState.PENDING) {
            complete(purchase, PurchaseResult.Pending)
            return
        }
        val licensingPublicKey = product?.licensingPublicKey ?: catalogBinding?.licensingPublicKey
            ?: catalogMapping?.licensingPublicKey
        if (licensingPublicKey != null && !PlayPurchaseSignatureVerifier.verify(
                licensingPublicKey,
                purchase.originalJson,
                purchase.signature,
            )
        ) {
            features.removeLocalPurchase(purchase.purchaseToken)
            evidenceStore.upsert(evidence.copy(permanentlyRejected = true))
            complete(purchase, PurchaseResult.Failed(SecurityException("Play purchase signature is invalid.")))
            return
        }

        var currentEvidence = evidence
        if (owner == distinctId()) {
            features.applyLocalPurchase(evidence.localFeatureGrants.toFeatureGrants(), purchase.purchaseToken)
            if (isCheckoutOutcome && !evidence.completionEmitted) {
                emitPurchaseCompleted(evidence)
                currentEvidence = evidence.copy(completionEmitted = true)
                evidenceStore.upsert(currentEvidence)
            }
        }
        complete(purchase, PurchaseResult.Purchased)
        if (!currentEvidence.permanentlyRejected) {
            if (currentEvidence.synced) completeManaged(currentEvidence) else syncEvidence(currentEvidence)
        }
    }

    private suspend fun syncEvidence(original: PurchaseEvidence) {
        val attempted = original.copy(syncAttempts = original.syncAttempts + 1)
        if (!evidenceStore.upsert(attempted)) return
        when (val outcome = runCatching { synchronizer.sync(attempted) }
            .getOrElse { PurchaseSyncOutcome.Rejected(permanent = false) }) {
            is PurchaseSyncOutcome.Rejected -> {
                if (outcome.permanent) {
                    evidenceStore.upsert(attempted.copy(permanentlyRejected = true))
                    if (attempted.distinctId == distinctId()) {
                        features.removeLocalPurchase(attempted.purchaseToken)
                    }
                } else {
                    scheduleRetry(attempted.purchaseToken, attempted.syncAttempts)
                }
            }
            is PurchaseSyncOutcome.Accepted -> {
                val accepted = attempted.copy(
                    synced = true,
                    syncedCustomerId = outcome.response.customerId,
                )
                if (!evidenceStore.upsert(accepted)) return
                if (accepted.distinctId == distinctId()) {
                    features.updateFromPurchase(
                        accepted.distinctId,
                        outcome.response.body,
                        accepted.purchaseToken,
                    )
                }
                completeManaged(emitPurchaseSyncedIfNeeded(accepted))
            }
        }
    }

    private fun emitPurchaseSyncedIfNeeded(evidence: PurchaseEvidence): PurchaseEvidence {
        if (evidence.syncedEventEmitted || evidence.distinctId != distinctId()) return evidence
        emit(
            SystemEventNames.PURCHASE_SYNCED,
            mapOf(
                "transaction_id" to evidence.purchaseToken,
                "original_transaction_id" to evidence.purchaseToken,
                "product_id" to evidence.storeProductIds.firstOrNull().orEmpty(),
                "customer_id" to evidence.syncedCustomerId.orEmpty(),
            ),
        )
        val emitted = evidence.copy(syncedEventEmitted = true)
        return if (evidenceStore.upsert(emitted)) emitted else evidence
    }

    private suspend fun completeManaged(evidence: PurchaseEvidence) {
        if (!evidence.needsManagedCompletion()) return
        val completion = if (evidence.consumable) {
            billing.consume(evidence.purchaseToken)
        } else {
            billing.acknowledge(evidence.purchaseToken)
        }
        if (completion.responseCode == BillingClient.BillingResponseCode.OK) {
            evidenceStore.upsert(
                if (evidence.consumable) evidence.copy(consumed = true)
                else evidence.copy(acknowledged = true),
            )
        } else {
            scheduleRetry(evidence.purchaseToken, evidence.syncAttempts)
        }
    }

    private fun PurchaseEvidence.needsManagedCompletion(): Boolean =
        nuxieManaged && catalogResolved && !acknowledged && !consumed

    private suspend fun revokeMissingOptimistic(activeTokens: Set<String>) {
        evidenceStore.load().values
            .filter {
                !it.synced && it.distinctId == distinctId() &&
                    it.purchaseState == StoredPurchaseState.PURCHASED &&
                    it.purchaseToken !in activeTokens
            }
            .forEach { features.removeLocalPurchase(it.purchaseToken) }
    }

    private fun scheduleRetry(token: String, attempt: Int) {
        if (retryJobs[token]?.isActive == true) return
        val delayMillis = (initialRetryDelayMillis * (1L shl attempt.coerceAtMost(16)))
            .coerceAtMost(maxRetryDelayMillis)
        retryJobs[token] = scope.launch {
            delay(delayMillis)
            retryJobs.remove(token)
            evidenceStore.load()[token]
                ?.takeIf {
                    !it.permanentlyRejected &&
                        (!it.synced || !it.syncedEventEmitted || it.needsManagedCompletion())
                }
                ?.let {
                    processing.withLock {
                        if (it.synced) {
                            completeManaged(emitPurchaseSyncedIfNeeded(it))
                        } else {
                            syncEvidence(it)
                        }
                    }
                }
        }
    }

    private fun emitPurchaseCompleted(evidence: PurchaseEvidence) {
        val properties = linkedMapOf<String, Any?>(
            "product_id" to (evidence.nuxieProductId ?: evidence.storeProductIds.firstOrNull().orEmpty()),
            "placement_id" to evidence.context?.placementId,
            "store_product_id" to evidence.storeProductIds.firstOrNull().orEmpty(),
            "experience_id" to evidence.context?.experienceId,
            "source" to "purchase",
            "test_store" to false,
            "transaction_id" to evidence.purchaseToken,
        ).filterValues { it != null }
        emit(SystemEventNames.PURCHASE_COMPLETED, properties)
    }

    private fun emitPurchaseCompleted(product: StoreProduct, transactionId: String?) {
        val properties = linkedMapOf<String, Any?>(
            "product_id" to product.productId,
            "placement_id" to product.placementId,
            "store_product_id" to product.storeProductId,
            "experience_id" to product.purchaseContext?.experienceId,
            "source" to "purchase",
            "test_store" to false,
            "transaction_id" to transactionId,
        ).filterValues { it != null }
        emit(SystemEventNames.PURCHASE_COMPLETED, properties)
    }

    private fun complete(purchase: PlayPurchase, result: PurchaseResult) {
        purchase.products.forEach { productId ->
            val pending = inFlight[productId] ?: return@forEach
            if (pending.matches(purchase) && inFlight.remove(productId, pending)) {
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
            inFlight.remove(keys.nextElement())?.result?.complete(result)
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
        localFeatureGrants = localFeatureGrants.toStoredGrants(),
        licensingPublicKey = licensingPublicKey,
        nuxieManaged = settings.handlingMode == PurchaseHandlingMode.NUXIE_MANAGED,
    )

    internal fun rememberProduct(product: StoreProduct): Boolean =
        evidenceStore.upsertProductMapping(product.toMapping())

    private fun StoreProduct.toMapping() = StoredProductMapping(
        storeProductId = storeProductId,
        nuxieProductId = productId,
        basePlanId = basePlanId,
        offerId = offerId,
        productType = productType,
        consumable = consumable,
        context = toStoredContext(),
        localFeatureGrants = localFeatureGrants.toStoredGrants(),
        licensingPublicKey = licensingPublicKey,
    )

    private fun StoreProduct.toStoredContext() = StoredPurchaseContext(
        placementId,
        purchaseContext?.experienceId,
        purchaseContext?.experienceVersion,
    )

    private fun List<LocalPurchaseGrant>.toStoredGrants() = map {
        StoredLocalPurchaseGrant(it.featureId, it.type.name, it.unlimited)
    }

    private fun List<StoredLocalPurchaseGrant>.toFeatureGrants(): List<LocalPurchaseGrant> = mapNotNull {
        val type = runCatching { FeatureType.valueOf(it.type) }.getOrNull() ?: return@mapNotNull null
        LocalPurchaseGrant(it.featureId, type, it.unlimited)
    }

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.encodeToByteArray())
        .joinToString("") { "%02x".format(it) }
}
