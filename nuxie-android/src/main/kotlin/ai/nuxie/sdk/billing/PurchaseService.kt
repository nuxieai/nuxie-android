package ai.nuxie.sdk.billing

import ai.nuxie.sdk.events.SystemEventNames
import ai.nuxie.sdk.events.TimeBasedEpochGenerator
import ai.nuxie.sdk.features.FeatureAccess
import ai.nuxie.sdk.features.FeatureAllowance
import ai.nuxie.sdk.features.FeatureInfo
import ai.nuxie.sdk.features.FeatureService
import ai.nuxie.sdk.features.FeatureUsageResult
import ai.nuxie.sdk.identity.CustomerKind
import ai.nuxie.sdk.network.NuxieApi
import android.app.Activity
import ai.nuxie.sdk.logging.NuxieLog as Log
import com.android.billingclient.api.BillingClient
import java.security.MessageDigest
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

private const val MAX_EXTERNAL_PURCHASE_CAPTURE_RETRIES = 3

internal class PurchaseSettings(
    delegate: NuxiePurchaseDelegate?,
    handlingMode: PurchaseHandlingMode,
) {
    @Volatile var delegate: NuxiePurchaseDelegate? = delegate
    @Volatile var handlingMode: PurchaseHandlingMode = handlingMode
}

internal sealed interface PurchaseSyncOutcome {
    data class Accepted(val response: NuxieApi.PurchaseResponse) : PurchaseSyncOutcome
    /**
     * No acceptance. [refused] is the server's answer that the purchase is not
     * the requester's (a 409 conflict or a 400 invalid purchase); anything else
     * (transport, 5xx, 401, 403, an unusable body) is no answer yet.
     */
    data class Rejected(val refused: Boolean = false) : PurchaseSyncOutcome
}

/**
 * One `/purchase` submission: the customer who asks for the purchase, whether
 * they signed in, and why the SDK sends it. The server may move a purchase
 * another customer owns only for [PurchaseSyncReason.PURCHASE] and
 * [PurchaseSyncReason.RESTORE].
 */
internal data class PurchaseSyncRequest(
    val reason: PurchaseSyncReason,
    val distinctId: String,
    val customerKind: CustomerKind,
)

internal fun interface PurchaseSynchronizer {
    suspend fun sync(evidence: PurchaseEvidence, request: PurchaseSyncRequest): PurchaseSyncOutcome
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
    override suspend fun sync(
        evidence: PurchaseEvidence,
        request: PurchaseSyncRequest,
    ): PurchaseSyncOutcome = try {
        val response = api.postPurchase(
            NuxieApi.PlayPurchaseReport(
                productId = evidence.storeProductIds.firstOrNull()?.takeIf(String::isNotBlank),
                purchaseToken = evidence.purchaseToken,
                basePlanId = evidence.basePlanId,
                purchaseOptionId = evidence.purchaseOptionId,
                offerId = evidence.offerId,
                productType = evidence.productType.toApiProductType(),
                obfuscatedAccountId = evidence.obfuscatedAccountId,
                distinctId = request.distinctId,
                reason = request.reason,
                customerKind = request.customerKind,
            ),
        )
        // A 2xx that is not a success is a body the SDK cannot use: no answer yet.
        if (response.success) PurchaseSyncOutcome.Accepted(response) else PurchaseSyncOutcome.Rejected()
    } catch (rejected: NuxieApi.PurchaseRejectedException) {
        PurchaseSyncOutcome.Rejected(refused = rejected.statusCode == 400 || rejected.statusCode == 409)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        Log.w("NuxieBilling", "Purchase evidence sync failed", error)
        PurchaseSyncOutcome.Rejected()
    }
}

internal class RestoreReconciliationException(message: String) : IllegalStateException(message)

/**
 * The delay before a kept outbox entry is sent again after [attempts] failed
 * sends: `initial × 2^(attempts − 1)`, capped (30 s and one hour by default).
 */
internal fun outboxRetryDelayMillis(attempts: Int, initialMillis: Long = 30_000, maximumMillis: Long = 3_600_000): Long =
    (initialMillis * (1L shl (attempts.coerceIn(1, 16) - 1))).coerceAtMost(maximumMillis)

/** Why a Restore that found purchases restored none of them. */
internal enum class RestoreClaimError(val description: String) {
    /** The server keeps the purchases with the account that owns them. */
    OWNED_BY_ANOTHER_ACCOUNT(
        "These purchases belong to another account, so they weren't restored. Sign in to that account to use them.",
    ),
    /** The server has not answered; the purchases are sent again later. */
    UNCONFIRMED("Your purchases couldn't be restored right now. Check your connection and try again."),
}

internal class RestoreClaimException(val error: RestoreClaimError) : IllegalStateException(error.description)

/** Checkout found the product already owned by this Google Play account; it was sent as the customer's purchase. */
internal class PurchaseAlreadyOwnedException : IllegalStateException("already_owned")

/** Owns checkout correlation, durable evidence, reconciliation, and managed completion. */
internal class PurchaseService(
    private val billing: PlayBillingGateway,
    private val evidenceStore: PurchaseEvidenceStore,
    private val synchronizer: PurchaseSynchronizer,
    private val features: FeatureService,
    private val distinctId: () -> String,
    /** Whether a customer signed in, declared on every `/purchase` submission. */
    private val customerKind: (String) -> CustomerKind,
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
    private val capturePurchaseEvent: suspend (
        name: String,
        properties: Map<String, Any?>,
        eventId: String,
        distinctId: String,
    ) -> Boolean = { name, properties, _, _ ->
        emit(name, properties)
        true
    },
    private val newExternalOperationId: () -> String = TimeBasedEpochGenerator.shared::next,
    private val verifyPurchaseSignature: (String, String, String) -> Boolean =
        PlayPurchaseSignatureVerifier::verify,
    private val logDroppedExternalPurchase: (String, Int, Throwable) -> Unit = { operationId, attempts, failure ->
        Log.w("NuxieBilling", "Dropping external purchase operation after bounded capture retries", failure,
            Log.sensitive("operation", operationId), Log.status("attempts", attempts))
    },
    /** Internal test observation only; null in production and never controls behavior. */
    private val purchaseCommitObserver: ((PurchaseCommitObservation) -> Unit)? = null,
    private val testStore: NuxieTestStore? = null,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    /** Ask for the current customer's fresh server profile after a `/purchase` answer for them. */
    private val refreshFeatureAuthority: () -> Unit = {},
    private val outboxRetryInitialDelayMillis: Long = 30_000,
    private val outboxRetryMaxDelayMillis: Long = 3_600_000,
) {
    private data class InFlightPurchase(
        val result: CompletableDeferred<PurchaseResult>,
        val owner: String,
        /** The buyer's kind when the checkout began, recorded with its owed send. */
        val ownerKind: CustomerKind,
        val obfuscatedAccountId: String,
        val priorTokens: Set<String>,
        val product: StoreProduct,
        val nuxieManaged: Boolean,
        val outcomeEventId: String?,
    )

    /** What the server answered one submission, as far as this purchase's local state goes. */
    private enum class SyncResult {
        /** The server attached the purchase to the requester and the SDK applied its answer. */
        ACCEPTED,
        /** The server refused the purchase for the requester (a conflict or an invalid purchase). */
        REFUSED,
        /** Not answered or not applied yet; a later attempt may still succeed. */
        UNAVAILABLE,
    }

    /** One send of an outbox entry and what the server answered it. */
    private data class SendAnswer(val send: PurchaseSend?, val result: SyncResult)

    private sealed interface UsageCoordination {
        data class AwaitSync(val answer: CompletableDeferred<SendAnswer>) : UsageCoordination
        data class AwaitClaim(val released: CompletableDeferred<Unit>) : UsageCoordination
        data object Claimed : UsageCoordination
    }


    private data class StagedAtomicUse(
        val evidence: PurchaseEvidence?,
        val access: FeatureAccess?,
        val publication: FeatureInfo.Mutation?,
    )

    private data class PendingPurchaseCompletion(
        val outcome: PurchaseOutcome,
        val checkout: InFlightPurchase,
    )

    private data class PendingDirectCheckoutCompletion(
        val checkout: InFlightPurchase,
        val outcome: PurchaseOutcome,
    )

    private data class PendingOutcomeEmission(
        val product: StoreProduct,
        val ownerDistinctId: String,
        val outcome: PurchaseOutcome,
        val eventId: String?,
    )

    /**
     * Raw BillingClient delivery before the SDK's verification policy runs.
     * With no configured licensing key, Play's PURCHASED delivery is the
     * local trust boundary. With a key, signature verification must succeed
     * before this observation can construct [PurchaseOutcome.Verified].
     */
    private data class PlayPurchaseObservation(
        val purchase: PlayPurchase,
        val source: PurchaseOutcomeSource,
    )

    private data class PendingPurchaseCommit(
        val identity: PurchaseCommitIdentity,
        val operation: CompletableDeferred<Unit>,
        val eventName: String,
        val eventId: String,
        val ownerDistinctId: String,
        val properties: Map<String, Any?>,
        val afterCapture: suspend (ProcessingEffects) -> Unit = {},
        val onFailure: () -> Unit = {},
        val bestEffort: Boolean = false,
        val captureAttempt: Int = 1,
    )

    private data class OwnedPurchaseCommit(
        val identity: PurchaseCommitIdentity,
        val completion: CompletableDeferred<Unit>,
    )

    private data class AllowanceResolutionIdentity(
        val purchaseToken: String,
        val obfuscatedAccountId: String?,
        val storeProductIds: Set<String>,
        val nuxieProductId: String?,
        val basePlanId: String?,
        val purchaseOptionId: String?,
        val offerId: String?,
        val productType: String?,
    )

    private data class PendingAllowancePin(
        val identity: AllowanceResolutionIdentity,
        val allowances: List<StoredFeatureAllowance>,
    )

    private class ProcessingEffects {
        val purchaseCommits = mutableListOf<PendingPurchaseCommit>()
        val ownedPurchaseCommits = mutableListOf<OwnedPurchaseCommit>()
        val purchaseCommitWaits = mutableListOf<CompletableDeferred<Unit>>()
        val publications = mutableListOf<FeatureInfo.Mutation>()
        val purchaseCompletions = mutableListOf<PendingPurchaseCompletion>()
        val directCheckoutCompletions = mutableListOf<PendingDirectCheckoutCompletion>()
        val outcomeEmissions = mutableListOf<PendingOutcomeEmission>()
    }

    /**
     * [processing] serializes local outcome decisions. Durable event capture and
     * FeatureInfo publication drain after the mutex is released. Commit owners
     * publish a decision-ready latch before either operation can call back into
     * the facade. The in-flight identity remains installed through capture and
     * publication, so duplicates join the decision and become no-ops instead of
     * forming a cycle with event or Feature callbacks.
     */
    private suspend fun <T> withProcessingDecision(
        decide: suspend (ProcessingEffects) -> T,
    ): T = withContext(NonCancellable) {
        val effects = ProcessingEffects()
        val outcome = try {
            Result.success(processing.withLock { decide(effects) })
        } catch (failure: Throwable) {
            Result.failure(failure)
        }

        // These latches mean "the serialized decision is complete", not "all
        // callback-bearing effects drained". Completing them here lets a
        // reentrant same-token observation return while the owner identity
        // stays installed through the work below.
        effects.ownedPurchaseCommits.forEach { it.completion.complete(Unit) }
        effects.purchaseCommitWaits.forEach { it.await() }

        // Publication order must equal FIFO reservation order: decision-time
        // publications and barriers reserved their lane slots before any
        // commit's afterCapture can reserve one, so the outer effects drain
        // FIRST. Draining a commit's post-capture publication ahead of an
        // earlier outer reservation deadlocks the lane (the later mutation
        // awaits the earlier one, which cannot publish until this drain
        // returns).
        val callbackFailure = drainCallbackEffects(effects)
        var purchaseCommitFailure: Throwable? = null
        effects.purchaseCommits.forEach { commit ->
            drainPurchaseCommit(commit)?.let { failure ->
                purchaseCommitFailure = aggregateFailure(purchaseCommitFailure, failure)
            }
        }
        val stagedOperations = effects.purchaseCommits.mapTo(mutableSetOf()) { it.operation }
        processing.withLock {
            // A decision can reserve an identity and then terminate before it
            // needs a durable capture (pending, rejected, or already emitted).
            // Keep that ready operation installed through its callbacks, then
            // release it without calling it a completed verified commit.
            effects.ownedPurchaseCommits
                .filter { it.completion !in stagedOperations }
                .forEach { owned ->
                    purchaseCommitOperations.remove(owned.identity, owned.completion)
                }
        }

        outcome.exceptionOrNull()?.let { decisionFailure ->
            purchaseCommitFailure?.takeIf { it !== decisionFailure }
                ?.let(decisionFailure::addSuppressed)
            callbackFailure?.takeIf { it !== decisionFailure }
                ?.let(decisionFailure::addSuppressed)
            throw decisionFailure
        }
        purchaseCommitFailure?.let { failure ->
            callbackFailure?.takeIf { it !== failure }?.let(failure::addSuppressed)
            throw failure
        }
        callbackFailure?.let { throw it }
        outcome.getOrThrow()
    }

    /** Drain only callback-bearing effects. No purchase decision lock is held. */
    private suspend fun drainCallbackEffects(effects: ProcessingEffects): Throwable? {
        var failure: Throwable? = null
        effects.publications.forEach { publication ->
            runCatching { features.publishStaged(publication) }
                .onFailure { publicationFailure ->
                    failure = aggregateFailure(failure, publicationFailure)
                }
        }
        effects.purchaseCompletions.forEach { completion ->
            runCatching {
                try {
                    complete(completion.checkout, completion.outcome)
                } finally {
                    releaseCheckoutCompletion(completion.checkout)
                }
            }
                .onFailure { completionFailure ->
                    failure = aggregateFailure(failure, completionFailure)
                }
        }
        effects.directCheckoutCompletions.forEach { completion ->
            runCatching {
                emitPurchaseOutcome(
                    completion.checkout.product,
                    completion.outcome,
                    completion.checkout.owner,
                    completion.checkout.outcomeEventId,
                )
                completion.checkout.result.complete(completion.outcome.toPurchaseResult())
            }.onFailure { completionFailure ->
                completion.checkout.result.completeExceptionally(completionFailure)
                failure = aggregateFailure(failure, completionFailure)
            }
        }
        effects.outcomeEmissions.forEach { emission ->
            runCatching {
                emitPurchaseOutcome(
                    emission.product,
                    emission.outcome,
                    emission.ownerDistinctId,
                    emission.eventId,
                )
            }.onFailure { completionFailure ->
                failure = aggregateFailure(failure, completionFailure)
            }
        }
        return failure
    }

    private fun aggregateFailure(current: Throwable?, next: Throwable): Throwable {
        if (current == null) return next
        if (current !== next) current.addSuppressed(next)
        return current
    }

    private fun ProcessingEffects.completeCheckoutAfterPublications(
        purchase: PlayPurchase,
        outcome: PurchaseOutcome,
        checkout: InFlightPurchase?,
    ) {
        if (checkout == null || !claimedCheckoutCompletions.add(checkout)) return
        stageReservedCheckoutCompletion(outcome, checkout)
    }

    private fun ProcessingEffects.stageReservedCheckoutCompletion(
        outcome: PurchaseOutcome,
        checkout: InFlightPurchase,
    ) {
        publications += features.stagePublicationBarrier()
        purchaseCompletions += PendingPurchaseCompletion(outcome, checkout)
    }

    private suspend fun capturePurchaseCommit(commit: PendingPurchaseCommit) {
        check(
            capturePurchaseEvent(
                commit.eventName,
                commit.properties,
                commit.eventId,
                commit.ownerDistinctId,
            ),
        ) { "Could not durably capture the purchase outcome event." }
        when (val identity = commit.identity) {
            is PurchaseCommitIdentity.Evidence -> check(markCompletionCaptured(identity.purchaseToken)) {
                "Could not persist the purchase completion commit."
            }
            is PurchaseCommitIdentity.External -> Unit
        }
    }

    /** Capture and publish one owned commit without holding a decision mutex. */
    private suspend fun drainPurchaseCommit(commit: PendingPurchaseCommit): Throwable? {
        val postCaptureEffects = ProcessingEffects()
        val failure = try {
            capturePurchaseCommit(commit)
            processing.withLock {
                if (purchaseCommitOperations[commit.identity] !== commit.operation) {
                    return@withLock
                }
                commit.afterCapture(postCaptureEffects)
            }
            drainCallbackEffects(postCaptureEffects)?.let { throw it }
            null
        } catch (cancelled: CancellationException) {
            commit.onFailure()
            processing.withLock {
                purchaseCommitOperations.remove(commit.identity, commit.operation)
            }
            throw cancelled
        } catch (failure: Throwable) {
            failure
        }

        if (failure == null) {
            processing.withLock {
                if (purchaseCommitOperations[commit.identity] === commit.operation) {
                    recordCompletedPurchaseCommit(commit.identity)
                    purchaseCommitOperations.remove(commit.identity, commit.operation)
                }
            }
            return null
        }

        commit.onFailure()
        if (commit.bestEffort && commit.identity is PurchaseCommitIdentity.External) {
            if (commit.captureAttempt <= MAX_EXTERNAL_PURCHASE_CAPTURE_RETRIES) {
                scheduleExternalPurchaseCommitRetry(commit)
            } else {
                processing.withLock {
                    purchaseCommitOperations.remove(commit.identity, commit.operation)
                }
                logDroppedExternalPurchase(commit.identity.operationId, commit.captureAttempt, failure)
            }
            return null
        }

        processing.withLock {
            purchaseCommitOperations.remove(commit.identity, commit.operation)
        }
        return failure
    }

    private fun scheduleExternalPurchaseCommitRetry(commit: PendingPurchaseCommit) {
        val identity = commit.identity as PurchaseCommitIdentity.External
        if (externalPurchaseCommitRetryJobs[identity]?.isActive == true) return
        val retry = scope.launch(start = CoroutineStart.LAZY) {
            delay(retryDelay(commit.captureAttempt))
            externalPurchaseCommitRetryJobs.remove(identity)
            val retained = processing.withLock {
                purchaseCommitOperations[identity] === commit.operation
            }
            if (retained) {
                drainPurchaseCommit(commit.copy(captureAttempt = commit.captureAttempt + 1))
            }
        }
        val existing = externalPurchaseCommitRetryJobs.putIfAbsent(identity, retry)
        if (existing == null) retry.start() else retry.cancel()
    }

    private suspend fun markCompletionCaptured(purchaseToken: String): Boolean =
        projectionRefresh.withLock {
            val current = evidenceStore.load()[purchaseToken] ?: return@withLock false
            val marked = current.completionEmitted ||
                evidenceStore.upsert(current.copy(completionEmitted = true))
            marked && clearOutcomeCorrelation(current.checkoutCompletionEventId)
        }

    private suspend fun clearOutcomeCorrelation(eventId: String?): Boolean {
        if (eventId == null) return true
        var cleared = true
        evidenceStore.loadBindings()
            .filter { it.outcomeEventId == eventId }
            .forEach { binding ->
                if (!evidenceStore.upsertBinding(binding.copy(outcomeEventId = null, context = binding.context?.copy(journeyId = null)))) {
                    cleared = false
                }
            }
        return cleared
    }

    private val products = ConcurrentHashMap<StoredProductIdentity, StoreProduct>()
    private val inFlight = ConcurrentHashMap<String, InFlightPurchase>()
    @Volatile private var checkoutIntakeClosed = false
    private val processing = Mutex()
    private val projectionRefresh = Mutex()
    private val sendRetryJobs = ConcurrentHashMap<String, Job>()
    private val completionRetryJobs = ConcurrentHashMap<String, Job>()
    private val externalPurchaseCommitRetryJobs =
        ConcurrentHashMap<PurchaseCommitIdentity.External, Job>()
    private val claimedCheckoutCompletions: MutableSet<InFlightPurchase> =
        Collections.newSetFromMap(ConcurrentHashMap())
    private val purchaseCommitOperations =
        ConcurrentHashMap<PurchaseCommitIdentity, CompletableDeferred<Unit>>()
    /** Process-lifetime terminal identities; guarded by [processing]. */
    private val completedPurchaseCommits = mutableMapOf<PurchaseCommitIdentity, Unit>()
    /**
     * What Play reported most recently: each purchase token with the product
     * type of the query that reported it, or null when only a purchase update
     * delivered it. In memory only, recomputed on every query, and empty after
     * launch until the first one, so stored entries unlock nothing early before
     * Play has reported them. Guarded by itself.
     */
    private val playReported = mutableMapOf<String, String?>()
    /**
     * Purchases the server answered in this process. An answer ends the early
     * unlock at once, even when saving it failed and the entry stays owed.
     */
    private val answeredTokens: MutableSet<String> = Collections.newSetFromMap(ConcurrentHashMap())
    private val usageCoordinationLock = Any()
    /** The one send in flight per purchase token; guarded by [usageCoordinationLock]. */
    private val sending = mutableMapOf<String, CompletableDeferred<SendAnswer>>()
    private val purchaseUsageClaims = mutableSetOf<String>()
    private val purchaseUsageWaiters = mutableMapOf<String, MutableList<CompletableDeferred<Unit>>>()
    private val managedCompletionClaims = mutableSetOf<String>()
    /** Guarded by [projectionRefresh]; failed pins retry their first resolved snapshot. */
    private val pendingAllowancePins = mutableMapOf<String, PendingAllowancePin>()
    private val initialProjectionRefresh: Job

    init {
        // Projection is a local derivation and must not wait for Play Billing
        // connectivity during a cold start.
        evidenceStore.setProductMappingsChangedListener {
            // Pin first-arrival allowances in the same linearization as
            // identity projection snapshots, but reserve no FeatureInfo FIFO
            // slot until an active SDK scope can also publish it.
            projectionRefresh.withLock { deriveOptimisticProjection() }
            scope.launch {
                refreshOptimisticProjection()
                // A changed catalog sends every kept entry again, as iOS does.
                resendKeptEntries()
            }
        }
        initialProjectionRefresh = scope.launch { refreshOptimisticProjection() }
    }

    /** Wait until cold-start evidence has established the initial optimistic projection. */
    internal suspend fun awaitInitialProjection() {
        initialProjectionRefresh.join()
    }

    suspend fun useFeatureWithPendingPurchase(
        distinctId: String,
        featureId: String,
        amount: Double,
        entityId: String?,
        metadata: Map<String, Any?>?,
    ): FeatureUsageResult? {
        if (testStore != null) return null
        val usageApi = api ?: return null
        if (!amount.isFinite() || amount <= 0.0) return null
        while (true) {
            if (this.distinctId() != distinctId) throw kotlinx.coroutines.CancellationException()
            val candidates = eligibleEvidence(distinctId, featureId)
            if (candidates.size != 1) return null
            val evidence = candidates.single()
            when (val coordination = coordinateUsage(evidence.purchaseToken)) {
                is UsageCoordination.AwaitSync -> {
                    val synced = coordination.answer.await().result == SyncResult.ACCEPTED
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
                    evidence.context?.experienceVersion?.let { put("experience_version_id", it) }
                    evidence.context?.journeyId?.let { put("journey_id", it) }
                }
                if (!capturePurchaseSynced(
                        SystemEventNames.PURCHASE_SYNCED,
                        properties,
                        purchaseSyncedEventId(distinctId, evidence.purchaseToken),
                        distinctId,
                    )
                ) {
                    throw IllegalStateException("Could not durably capture the purchase synchronization event.")
                }
                val stagedUse = projectionRefresh.withLock {
                    val current = evidenceStore.load()[evidence.purchaseToken]
                    // The server accepted the claimed spend: its answer stands even if
                    // Play's report changed meanwhile.
                    if (current == null ||
                        !current.isBuyersOwnUnsyncedPurchase(distinctId) ||
                        !current.matchesAtomicUsePayload(evidence)
                    ) {
                        throw IllegalStateException("Could not persist accepted purchase evidence.")
                    }
                    // The server's receipt attached the purchase to this customer.
                    val persistedAccepted = current.copy(
                        ownerDistinctId = distinctId,
                        refused = false,
                        pendingSend = null,
                        syncedCustomerId = distinctId,
                        syncedEventEmitted = true,
                        backendSyncedAtMillis = nowMillis(),
                    ).takeIf { evidenceStore.upsert(it) }
                        ?: throw IllegalStateException("Could not persist accepted purchase evidence.")
                    val currentDistinctId = this.distinctId()
                    if (currentDistinctId != distinctId) {
                        StagedAtomicUse(
                            evidence = null,
                            access = null,
                            publication = features.stageOptimisticPurchaseProjection(
                                currentDistinctId,
                                deriveOptimisticProjection(currentDistinctId),
                            ),
                        )
                    } else {
                        val staged = features.stageAuthoritativeUse(
                            result = response,
                            requestedFeatureId = featureId,
                            distinctId = distinctId,
                            entityId = entityId,
                            expectedScope = featureScope,
                            reconciledOptimisticProjection = deriveOptimisticProjection(),
                            reconcileOptimisticProjection = true,
                        )
                        StagedAtomicUse(persistedAccepted, staged.access, staged.publication)
                    }
                }
                features.publishStaged(stagedUse.publication)
                val accepted = stagedUse.evidence ?: throw kotlinx.coroutines.CancellationException()
                val access = checkNotNull(stagedUse.access)
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

    private suspend fun eligibleEvidence(distinctId: String, featureId: String): List<PurchaseEvidence> {
        val descriptors = evidenceStore.loadProductMappings()
        val bindings = evidenceStore.loadBindings()
        return evidenceStore.load().values.filter { evidence ->
            evidence.authorityScope == purchaseStorageScope &&
                evidence.isBuyersOwnUnsyncedPurchase(distinctId) &&
                unlocksEarly(evidence.purchaseToken) &&
                evidence.purchaseToken.isNotBlank() &&
                evidence.packageName.isNotBlank() &&
                evidence.storeProductIds.firstOrNull()?.isNotBlank() == true &&
                featureAllowancesForEvidence(evidence, descriptors, bindings).any { it.featureId == featureId }
        }
    }

    private fun PurchaseEvidence.matchesAtomicUsePayload(sent: PurchaseEvidence): Boolean =
        authorityScope == purchaseStorageScope &&
            purchaseToken == sent.purchaseToken &&
            packageName == sent.packageName &&
            storeProductIds.firstOrNull() == sent.storeProductIds.firstOrNull() &&
            basePlanId == sent.basePlanId &&
            offerId == sent.offerId &&
            obfuscatedAccountId == sent.obfuscatedAccountId

    private fun coordinateUsage(purchaseToken: String): UsageCoordination =
        synchronized(usageCoordinationLock) {
            sending[purchaseToken]?.let {
                return@synchronized UsageCoordination.AwaitSync(it)
            }
            if (purchaseToken in purchaseUsageClaims) {
                val waiter = CompletableDeferred<Unit>()
                purchaseUsageWaiters.getOrPut(purchaseToken, ::mutableListOf).add(waiter)
                UsageCoordination.AwaitClaim(waiter)
            } else {
                purchaseUsageClaims.add(purchaseToken)
                UsageCoordination.Claimed
            }
        }

    /** Run [write] holding [purchaseToken]'s usage claim, waiting for any spend that holds it. */
    private suspend fun <T> withUsageClaim(purchaseToken: String, write: suspend () -> T): T {
        while (true) {
            val held = synchronized(usageCoordinationLock) {
                if (purchaseToken in purchaseUsageClaims) {
                    CompletableDeferred<Unit>().also {
                        purchaseUsageWaiters.getOrPut(purchaseToken, ::mutableListOf).add(it)
                    }
                } else {
                    purchaseUsageClaims.add(purchaseToken)
                    null
                }
            } ?: break
            held.await()
        }
        try {
            return write()
        } finally {
            releasePurchaseUsageClaim(purchaseToken)
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

    private fun purchaseSyncedEventId(distinctId: String, purchaseToken: String): String = stableEventId(
        "purchase-synced:",
        listOf(
            purchaseStorageScope,
            SystemEventNames.PURCHASE_SYNCED,
            distinctId,
            purchaseToken,
        ),
    )

    private fun stableEventId(prefix: String, components: List<String>): String = prefix + sha256(
        components.joinToString("\u001f"),
    )

    /**
     * Stop new checkouts and release callers waiting for Play UI. A completion
     * already reserved by a purchase decision keeps its result through durable
     * capture and publication. The core must still drain those producer jobs.
     * Cancellation here is SDK lifetime cancellation, not a store outcome.
     */
    suspend fun stopCheckoutIntake() {
        processing.withLock {
            synchronized(inFlight) {
                checkoutIntakeClosed = true
                inFlight.values.toList().forEach(::cancelUnclaimedCheckoutIfClosed)
            }
        }
    }

    private fun ensureCheckoutIntakeOpen() {
        if (checkoutIntakeClosed) throw CancellationException("SDK checkout intake closed.")
    }

    private fun cancelUnclaimedCheckoutIfClosed(checkout: InFlightPurchase) {
        synchronized(inFlight) {
            if (checkoutIntakeClosed && checkout !in claimedCheckoutCompletions &&
                inFlight.remove(checkout.product.storeProductId, checkout)
            ) {
                checkout.result.cancel(CancellationException("SDK checkout intake closed."))
            }
        }
    }

    private fun releaseCheckoutCompletion(checkout: InFlightPurchase) {
        synchronized(inFlight) {
            claimedCheckoutCompletions.remove(checkout)
            cancelUnclaimedCheckoutIfClosed(checkout)
        }
    }

    suspend fun purchase(
        activity: Activity,
        product: StoreProduct,
        replacement: SubscriptionReplacement?,
        expectedOwnerDistinctId: String? = null,
        outcomeCorrelation: CommerceOutcomeCorrelation? = null,
    ): PurchaseResult = withContext(ioDispatcher) {
        purchaseOnIo(activity, product, replacement, expectedOwnerDistinctId, outcomeCorrelation)
    }

    private suspend fun purchaseOnIo(
        activity: Activity,
        product: StoreProduct,
        replacement: SubscriptionReplacement?,
        expectedOwnerDistinctId: String?,
        outcomeCorrelation: CommerceOutcomeCorrelation?,
    ): PurchaseResult {
        val product = product.forJourneyCheckout(outcomeCorrelation?.journeyId)
        ensureCheckoutIntakeOpen()
        val initiatingOwner = distinctId()
        val initiatingKind = customerKind(initiatingOwner)
        if (expectedOwnerDistinctId != null && initiatingOwner != expectedOwnerDistinctId) {
            return PurchaseResult.Failed(
                IllegalStateException("The customer changed before checkout could start."),
            )
        }
        testStore?.let { store ->
            val response = store.purchase(product, initiatingOwner, activity)
            response.access?.let { access ->
                // The server granted this purchase's Features: unlock them now,
                // before the purchase is announced, as a Play purchase sync does.
                features.updateFromPurchase(initiatingOwner, access, checkNotNull(response.transactionId))
            }
            val outcome = if (response.result == PurchaseResult.Purchased) {
                PurchaseOutcome.External(ExternalPurchaseDeclaration.Purchase(
                    operationId = checkNotNull(response.transactionId),
                    ownerDistinctId = initiatingOwner,
                    product = product,
                    outcomeEventId = outcomeCorrelation?.eventId,
                    testStore = true,
                    transactionId = response.transactionId,
                ))
            } else {
                response.result.toPurchaseOutcome(PurchaseOutcomeSource.CHECKOUT, testStore = true)
            }
            return commitStandaloneOutcome(product, initiatingOwner, outcome, outcomeCorrelation?.eventId)
        }
        settings.delegate?.let { delegate ->
            val result = delegate.purchase(product)
            val outcome = if (result == PurchaseResult.Purchased) {
                PurchaseOutcome.External(
                    ExternalPurchaseDeclaration.Purchase(
                        operationId = newExternalOperationId(),
                        ownerDistinctId = initiatingOwner,
                        product = product,
                        outcomeEventId = outcomeCorrelation?.eventId,
                    ),
                )
            } else {
                result.toPurchaseOutcome(PurchaseOutcomeSource.EXTERNAL_DELEGATE)
            }
            return commitStandaloneOutcome(
                product,
                initiatingOwner,
                outcome,
                outcomeCorrelation?.eventId,
            )
        }
        products[product.productIdentity()] = product.forJourneyCheckout(null)
        val owner = initiatingOwner
        val accountId = playAccountId(owner)
        if (!evidenceStore.upsertProductMapping(product.toMapping()) ||
            !evidenceStore.upsertBinding(
                product.toBinding(accountId, owner, outcomeCorrelation?.eventId),
            )
        ) {
            return failed(
                product,
                IllegalStateException("Could not persist purchase catalog mapping."),
                owner,
                PurchaseOutcomeSource.CHECKOUT,
                outcomeCorrelation?.eventId,
            )
        }
        val queried = try {
            queryPlay(product.productType)
        } catch (failure: Throwable) {
            ensureCheckoutIntakeOpen()
            throw failure
        }
        ensureCheckoutIntakeOpen()
        val active = when (queried) {
            is ActivePurchasesResult.Failed -> return failed(
                product,
                BillingUnavailableException(queried.responseCode, queried.debugMessage),
                owner,
                PurchaseOutcomeSource.CHECKOUT,
                outcomeCorrelation?.eventId,
            )
            is ActivePurchasesResult.Success -> queried.purchases
        }
        if (product.productType == BillingClient.ProductType.SUBS && replacement == null && active.isNotEmpty()) {
            return failed(
                product,
                SubscriptionReplacementRequiredException(),
                owner,
                PurchaseOutcomeSource.CHECKOUT,
                outcomeCorrelation?.eventId,
            )
        }
        val priorTokens = active.mapTo(mutableSetOf()) { it.purchaseToken }

        val result = CompletableDeferred<PurchaseResult>()
        val pending = InFlightPurchase(
            result,
            owner,
            initiatingKind,
            accountId,
            priorTokens,
            product,
            settings.handlingMode == PurchaseHandlingMode.NUXIE_MANAGED,
            outcomeCorrelation?.eventId,
        )
        val registered = synchronized(inFlight) {
            ensureCheckoutIntakeOpen()
            if (inFlight.isNotEmpty()) false else {
                inFlight[product.storeProductId] = pending
                true
            }
        }
        if (!registered) {
            return failed(
                product,
                IllegalStateException("Another Play purchase is already in flight."),
                owner,
                PurchaseOutcomeSource.CHECKOUT,
                outcomeCorrelation?.eventId,
            )
        }
        val launch = runCatching {
            ensureCheckoutIntakeOpen()
            billing.launch(
                activity,
                CheckoutRequest(product, accountId, replacement),
            )
        }.getOrElse {
            inFlight.remove(product.storeProductId, pending)
            ensureCheckoutIntakeOpen()
            if (it is CancellationException) throw it
            return failed(
                product,
                it,
                owner,
                PurchaseOutcomeSource.CHECKOUT,
                outcomeCorrelation?.eventId,
            )
        }
        if (launch.responseCode != BillingClient.BillingResponseCode.OK) {
            inFlight.remove(product.storeProductId, pending)
            val owned = if (launch.responseCode == BillingClient.BillingResponseCode.ITEM_ALREADY_OWNED) {
                // Play says this account already owns the product: find that purchase.
                (runCatching { queryPlay(product.productType) }.getOrNull() as? ActivePurchasesResult.Success)
                    ?.purchases?.ownedPurchaseOf(product)
            } else {
                null
            }
            return if (launch.responseCode == BillingClient.BillingResponseCode.USER_CANCELED) {
                commitStandaloneOutcome(
                    product,
                    owner,
                    PurchaseOutcome.Cancelled(PurchaseOutcomeSource.CHECKOUT),
                    outcomeCorrelation?.eventId,
                )
            } else if (owned != null) {
                alreadyOwned(product, owned, owner, initiatingKind, outcomeCorrelation?.eventId)
            } else {
                failed(
                    product,
                    BillingUnavailableException(launch.responseCode, launch.debugMessage),
                    owner,
                    PurchaseOutcomeSource.CHECKOUT,
                    outcomeCorrelation?.eventId,
                )
            }
        }
        return result.await()
    }

    /**
     * The one-time product this Play account already owns. A subscription of
     * the same product may be another base plan, so subscriptions keep the
     * replacement rule instead.
     */
    private fun List<PlayPurchase>.ownedPurchaseOf(product: StoreProduct): PlayPurchase? =
        takeIf { product.productType == BillingClient.ProductType.INAPP }
            ?.firstOrNull { product.storeProductId in it.products && it.state == StoredPurchaseState.PURCHASED }

    /**
     * The customer tried to buy a product this Play account already owns: that
     * purchase is sent as their purchase, so the server may move it to them,
     * and the checkout reports the product already owned.
     */
    private suspend fun alreadyOwned(
        product: StoreProduct,
        owned: PlayPurchase,
        owner: String,
        ownerKind: CustomerKind,
        outcomeEventId: String?,
    ): PurchaseResult {
        withProcessingDecision { effects ->
            commitPurchaseOutcome(
                classifyPurchaseObservation(owned, PurchaseOutcomeSource.STARTUP_RECOVERY),
                effects,
            )
        }
        if (distinctId() == owner && evidenceStore.load().containsKey(owned.purchaseToken)) {
            claim(owned.purchaseToken, PurchaseSend.admit(PurchaseSyncReason.PURCHASE, owner, ownerKind))
        }
        return failed(product, PurchaseAlreadyOwnedException(), owner, PurchaseOutcomeSource.CHECKOUT, outcomeEventId)
    }

    suspend fun restorePurchases(
        expectedOwnerDistinctId: String? = null,
        outcomeCorrelation: CommerceOutcomeCorrelation? = null,
    ): RestoreResult = withContext(ioDispatcher) {
        restorePurchasesOnIo(expectedOwnerDistinctId, outcomeCorrelation)
    }

    private suspend fun restorePurchasesOnIo(
        expectedOwnerDistinctId: String?,
        outcomeCorrelation: CommerceOutcomeCorrelation?,
    ): RestoreResult {
        val initiatingOwner = distinctId()
        val initiatingKind = customerKind(initiatingOwner)
        if (expectedOwnerDistinctId != null && initiatingOwner != expectedOwnerDistinctId) {
            return RestoreResult.Failed(
                IllegalStateException("The customer changed before restore could start."),
            )
        }
        testStore?.let { store ->
            val response = store.restorePurchases(initiatingOwner)
            emitRestoreOutcome(response.result, initiatingOwner, outcomeCorrelation?.eventId, testStore = true)
            return response.result
        }
        settings.delegate?.let { delegate ->
            val result = delegate.restorePurchases()
            if (result == RestoreResult.Restored) {
                withProcessingDecision { effects ->
                    commitPurchaseOutcome(
                        PurchaseOutcome.External(
                            ExternalPurchaseDeclaration.Restore(
                                newExternalOperationId(),
                                initiatingOwner,
                                outcomeCorrelation?.eventId,
                            ),
                        ),
                        effects,
                    )
                }
                return result
            }
            emitRestoreOutcome(result, initiatingOwner, outcomeCorrelation?.eventId)
            return result
        }
        val found = mutableListOf<PlayPurchase>()
        val pass = beginPlayPass()
        for (type in listOf(BillingClient.ProductType.SUBS, BillingClient.ProductType.INAPP)) {
            val query = try {
                queryPlay(type)
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                // Connection setup can fail before Play supplies a query response.
                return RestoreResult.Failed(error).also {
                    emitRestoreOutcome(it, initiatingOwner, outcomeCorrelation?.eventId)
                }
            }
            when (val result = query) {
                is ActivePurchasesResult.Success -> found += result.purchases
                is ActivePurchasesResult.Failed -> return RestoreResult.Failed(
                    BillingUnavailableException(result.responseCode, result.debugMessage),
                ).also {
                    emitRestoreOutcome(it, initiatingOwner, outcomeCorrelation?.eventId)
                }
            }
        }
        // Both types answered: this Restore's queries are a full pass.
        endPlayPass(pass)
        // Null means Play owns purchases this Restore must ask the server about.
        val settled: RestoreResult? = withProcessingDecision { effects ->
            when {
                found.isEmpty() -> RestoreResult.NoPurchases
                else -> {
                    found.distinctBy { it.purchaseToken }.forEach { purchase ->
                        commitPurchaseOutcome(
                            classifyPurchaseObservation(purchase, PurchaseOutcomeSource.STARTUP_RECOVERY),
                            effects,
                        )
                    }
                    null
                }
            }
        }
        // Restore queues a send for every purchase Play owns, whoever this device
        // last saw holding it, even after an earlier sync was refused or a
        // checkout receipt was accepted: only the server can move a purchase to
        // the restoring customer, and only its answer grants access.
        val retained = if (settled != null) emptyMap() else evidenceStore.load()
        val restorable = if (settled != null) emptyList() else {
            found.distinctBy { it.purchaseToken }.mapNotNull { purchase ->
                retained[purchase.purchaseToken]?.takeIf {
                    purchase.state == StoredPurchaseState.PURCHASED &&
                        it.purchaseState == StoredPurchaseState.PURCHASED &&
                        it.hasEligiblePurchaseSignature
                }
            }
        }
        // A purchase Play owns that could not be written down or verified is a
        // failure, never "no purchases".
        val unretained = settled == null && found.any {
            it.state == StoredPurchaseState.PURCHASED && it.purchaseToken !in retained
        }
        val unverified = settled == null && found.any {
            it.state == StoredPurchaseState.PURCHASED &&
                retained[it.purchaseToken]?.hasEligiblePurchaseSignature == false
        }
        val send = PurchaseSend.admit(PurchaseSyncReason.RESTORE, initiatingOwner, initiatingKind)
        val results = restorable.associate { evidence ->
            evidence.purchaseToken to claim(evidence.purchaseToken, send)
        }
        val outcome = when {
            settled != null -> settled
            unretained && results.values.none { it == SyncResult.ACCEPTED } -> RestoreResult.Failed(
                RestoreReconciliationException(
                    "A restored purchase could not be retained for reconciliation.",
                ),
            )
            unverified && results.values.none { it == SyncResult.ACCEPTED } -> RestoreResult.Failed(
                RestoreReconciliationException("A restored purchase could not be verified."),
            )
            restorable.isEmpty() -> RestoreResult.NoPurchases
            results.values.none { it == SyncResult.ACCEPTED } -> RestoreResult.Failed(
                RestoreClaimException(
                    if (results.values.any { it == SyncResult.REFUSED }) {
                        RestoreClaimError.OWNED_BY_ANOTHER_ACCOUNT
                    } else {
                        RestoreClaimError.UNCONFIRMED
                    },
                ),
            )
            expectedOwnerDistinctId != null -> strictRestoreOutcome(found, results)
            else -> RestoreResult.Restored
        }
        emitRestoreOutcome(outcome, initiatingOwner, outcomeCorrelation?.eventId)
        return outcome
    }

    /**
     * A signed Experience Restore route advances only when the server accepted
     * (200) every purchase Play returned for this Restore: a refusal, a missing
     * answer, or a purchase whose required signature never verified (never
     * sent, so never accepted) fails it. Each must also be durable, exactly
     * catalog-resolved, and completed with Play. The decision reads the
     * Restore's own answers, never the owner a purchase has after them. The
     * public host Restore reports Restored once the server accepted at least one.
     */
    private suspend fun strictRestoreOutcome(
        found: List<PlayPurchase>,
        results: Map<String, SyncResult>,
    ): RestoreResult {
        val evidenceByToken = evidenceStore.load()
        val taking = found.filter { it.state == StoredPurchaseState.PURCHASED }
            .map { it.purchaseToken }
            .distinct()
        val claimed = taking.mapNotNull(evidenceByToken::get)
        return when {
            found.any { it.purchaseToken !in evidenceByToken } -> RestoreResult.Failed(
                RestoreReconciliationException(
                    "A restored purchase could not be retained for reconciliation.",
                ),
            )
            taking.any { results[it] == SyncResult.REFUSED } ||
                claimed.any { !it.hasEligiblePurchaseSignature } -> RestoreResult.Failed(
                RestoreReconciliationException("A restored purchase could not be verified."),
            )
            taking.any { results[it] != SyncResult.ACCEPTED } -> RestoreResult.Failed(
                RestoreReconciliationException("Restore is waiting for purchase verification."),
            )
            claimed.any { !it.catalogResolved } -> RestoreResult.Failed(
                RestoreReconciliationException(
                    "A restored purchase did not match one exact signed Product.",
                ),
            )
            claimed.any { it.needsManagedCompletion() } -> RestoreResult.Failed(
                RestoreReconciliationException("Restore is waiting for Google Play completion."),
            )
            else -> RestoreResult.Restored
        }
    }

    suspend fun onPurchasesUpdated(update: PurchaseUpdate) {
        if (testStore != null) return
        if (update.billingResult.responseCode == BillingClient.BillingResponseCode.USER_CANCELED) {
            withProcessingDecision { effects ->
                commitPurchaseOutcome(
                    PurchaseOutcome.Cancelled(callbackOutcomeSource()),
                    effects,
                )
            }
            return
        }
        if (update.billingResult.responseCode != BillingClient.BillingResponseCode.OK) {
            withProcessingDecision { effects ->
                commitPurchaseOutcome(
                    PurchaseOutcome.Failed(
                        BillingUnavailableException(
                            update.billingResult.responseCode,
                            update.billingResult.debugMessage,
                        ),
                        callbackOutcomeSource(),
                    ),
                    effects,
                )
            }
            return
        }
        recordPlayUpdate(update.purchases.orEmpty()) {
            processPurchases(
                update.purchases.orEmpty(),
                PurchaseOutcomeSource.PURCHASES_UPDATED_STREAM,
            )
        }
    }

    /** Billing connect and app foreground share one recovery lane. */
    suspend fun recover() {
        if (testStore != null) return
        val active = refreshPlayReport()
        val purchaseFollowUps = withProcessingDecision { effects ->
            active.mapNotNull {
                commitPurchaseOutcome(
                    classifyPurchaseObservation(it, PurchaseOutcomeSource.STARTUP_RECOVERY),
                    effects,
                )
            }
        }
        purchaseFollowUps.forEach { finishPurchase(it) }
        // Each entry gets one attempt per recovery: those Play reported above were just sent.
        val sentThisPass = purchaseFollowUps.mapTo(mutableSetOf()) { it.purchaseToken }

        val recoveryFollowUps = withProcessingDecision { effects ->
            val bindings = evidenceStore.loadBindings()
            val mappings = evidenceStore.loadProductMappings()
            var normalizedAnyEvidence = false
            val evidenceRecords = evidenceStore.load().values.mapNotNull { storedEvidence ->
                val normalized = if (!storedEvidence.signatureVerificationRequired &&
                    hasConfiguredLicensingKey(storedEvidence, bindings, mappings)
                ) {
                    storedEvidence.copy(signatureVerificationRequired = true)
                } else {
                    storedEvidence
                }
                if (normalized != storedEvidence) {
                    normalizedAnyEvidence = true
                    upsertEvidence(normalized)
                } else {
                    normalized
                }
            }
            if (normalizedAnyEvidence) {
                // The projection published before normalization could demand
                // signature verification for legacy evidence; republish so an
                // unverified purchase cannot stay projected. A concurrent
                // refresh may already have reserved the equivalent mutation;
                // a barrier still orders recovery's return after it drains.
                val restaged = projectionRefresh.withLock { stageOptimisticProjectionLocked() }
                effects.publications.add(restaged ?: features.stagePublicationBarrier())
            }
            evidenceRecords
                .filter {
                    it.purchaseState == StoredPurchaseState.PURCHASED &&
                        !it.completionEmitted &&
                        !it.buyerDistinctId.isNullOrBlank() &&
                        !it.refused &&
                        (it.synced || it.pendingSend != null) &&
                        it.hasEligiblePurchaseSignature
                }
                .forEach { effects.stageRetainedPurchaseCompletion(it) }
            evidenceRecords
                .filter {
                    it.purchaseState == StoredPurchaseState.PURCHASED && (
                        it.pendingSend != null ||
                            (it.synced && (!it.syncedEventEmitted || it.needsManagedCompletion()))
                        )
                }
        }
        // Every owed send goes out again, with the reason, requester and kind it was admitted with.
        recoveryFollowUps.forEach { evidence ->
            if (evidence.pendingSend != null) {
                if (evidence.purchaseToken !in sentThisPass) sendOwed(evidence.purchaseToken)
            } else {
                completeManaged(emitPurchaseSyncedIfNeeded(evidence))
            }
        }
    }

    /** Recovery re-enters the same completion seam even after sync/Play completion is terminal. */
    private fun ProcessingEffects.stageRetainedPurchaseCompletion(evidence: PurchaseEvidence) {
        val identity = PurchaseCommitIdentity.Evidence(evidence.purchaseToken)
        val operation = beginPurchaseCommit(identity) ?: return
        purchaseCommits += purchaseCompletionCommit(
            identity = identity,
            operation = operation,
            ownerDistinctId = checkNotNull(evidence.buyerDistinctId),
            properties = purchaseCompletionProperties(
                evidence,
                PurchaseOutcomeSource.STARTUP_RECOVERY,
            ),
            eventId = evidence.checkoutCompletionEventId
                ?: purchaseCommitEventId(identity),
        )
    }

    private suspend fun processPurchases(
        purchases: List<PlayPurchase>,
        observedSource: PurchaseOutcomeSource,
    ) {
        val followUps = withProcessingDecision { effects ->
            purchases.mapNotNull { purchase ->
                commitPurchaseOutcome(classifyPurchaseObservation(purchase, observedSource), effects)
            }
        }
        followUps.forEach { finishPurchase(it) }
    }

    private fun callbackOutcomeSource(): PurchaseOutcomeSource =
        if (inFlight.isEmpty()) PurchaseOutcomeSource.PURCHASES_UPDATED_STREAM
        else PurchaseOutcomeSource.CHECKOUT

    /** Classify provenance while the serialized purchase decision is held. */
    private suspend fun classifyPurchaseObservation(
        purchase: PlayPurchase,
        observedSource: PurchaseOutcomeSource,
    ): PlayPurchaseObservation {
        val existing = evidenceStore.load()[purchase.purchaseToken]
        val matchingCheckout = purchase.products.firstNotNullOfOrNull(inFlight::get)
            ?.matches(purchase) == true
        val source = when {
            observedSource == PurchaseOutcomeSource.PURCHASES_UPDATED_STREAM &&
                existing?.purchaseState == StoredPurchaseState.PENDING &&
                purchase.state == StoredPurchaseState.PURCHASED ->
                PurchaseOutcomeSource.DEFERRED_UPDATE
            observedSource == PurchaseOutcomeSource.PURCHASES_UPDATED_STREAM && matchingCheckout ->
                PurchaseOutcomeSource.CHECKOUT
            else -> observedSource
        }
        return PlayPurchaseObservation(purchase, source)
    }

    private suspend fun commitPurchaseOutcome(
        observation: PlayPurchaseObservation,
        effects: ProcessingEffects,
    ): PurchaseEvidence? {
        val identity = PurchaseCommitIdentity.Evidence(observation.purchase.purchaseToken)
        if (completedPurchaseCommits.containsKey(identity)) return null
        purchaseCommitOperations[identity]?.let { ongoing ->
            effects.purchaseCommitWaits += ongoing
            return null
        }
        return commitStoreOutcome(observation.purchase, observation.source, effects)
    }

    /** The sole interpreter for conclusions from every purchase source. */
    private suspend fun commitPurchaseOutcome(
        outcome: PurchaseOutcome,
        effects: ProcessingEffects,
        directProduct: StoreProduct? = null,
        directOwner: String? = null,
        directOutcomeEventId: String? = null,
    ): PurchaseEvidence? = when (outcome) {
        is PurchaseOutcome.Verified -> error(
            "Verified outcomes are finalized only after a Play purchase observation is verified.",
        )
        is PurchaseOutcome.Pending -> {
            val storeEvidence = outcome.evidence
            if (storeEvidence == null) {
                checkNotNull(directProduct)
                recordTerminalPurchaseOutcome(outcome)
                effects.outcomeEmissions += PendingOutcomeEmission(
                    directProduct,
                    checkNotNull(directOwner),
                    outcome,
                    directOutcomeEventId,
                )
                null
            } else {
                val identity = PurchaseCommitIdentity.Evidence(storeEvidence.purchaseToken)
                if (completedPurchaseCommits.containsKey(identity)) {
                    null
                } else if (purchaseCommitOperations[identity] != null) {
                    val ongoing = purchaseCommitOperations.getValue(identity)
                    effects.purchaseCommitWaits += ongoing
                    null
                } else {
                    commitStoreOutcome(storeEvidence, outcome.source, effects)
                }
            }
        }
        is PurchaseOutcome.External -> {
            val identity = PurchaseCommitIdentity.External(outcome.declaration.operationId)
            effects.beginPurchaseCommit(identity)?.let { operation ->
                commitExternalDeclaration(outcome.declaration, identity, operation, effects)
            }
            null
        }
        is PurchaseOutcome.Cancelled,
        is PurchaseOutcome.Failed,
        -> {
            recordTerminalPurchaseOutcome(outcome)
            if (directProduct != null) {
                effects.outcomeEmissions += PendingOutcomeEmission(
                    directProduct,
                    checkNotNull(directOwner),
                    outcome,
                    directOutcomeEventId,
                )
            } else {
                effects.completeAllAfterPublications(outcome)
            }
            null
        }
    }

    private fun commitExternalDeclaration(
        declaration: ExternalPurchaseDeclaration,
        identity: PurchaseCommitIdentity.External,
        operation: CompletableDeferred<Unit>,
        effects: ProcessingEffects,
    ) {
        val commit = when (declaration) {
            is ExternalPurchaseDeclaration.Purchase -> purchaseCompletionCommit(
                identity = identity,
                operation = operation,
                ownerDistinctId = declaration.ownerDistinctId,
                properties = purchaseCompletionProperties(
                    declaration.product,
                    source = declaration.source,
                    testStore = declaration.testStore,
                    transactionId = declaration.transactionId,
                ),
                eventId = declaration.outcomeEventId
                    ?: purchaseCommitEventId(identity),
                bestEffort = true,
            )
            is ExternalPurchaseDeclaration.Restore -> PendingPurchaseCommit(
                identity = identity,
                operation = operation,
                eventName = SystemEventNames.RESTORE_COMPLETED,
                eventId = declaration.outcomeEventId
                    ?: purchaseCommitEventId(identity),
                ownerDistinctId = declaration.ownerDistinctId,
                properties = mapOf(
                    "source" to declaration.source.wireValue,
                    "test_store" to declaration.testStore,
                ),
                bestEffort = true,
            )
        }
        effects.purchaseCommits += commit
    }

    private fun ProcessingEffects.beginPurchaseCommit(
        identity: PurchaseCommitIdentity,
    ): CompletableDeferred<Unit>? {
        if (completedPurchaseCommits.containsKey(identity)) return null
        if (ownedPurchaseCommits.any { it.identity == identity }) return null
        val completion = CompletableDeferred<Unit>()
        val existing = purchaseCommitOperations.putIfAbsent(identity, completion)
        if (existing != null) {
            purchaseCommitWaits += existing
            return null
        }
        ownedPurchaseCommits += OwnedPurchaseCommit(identity, completion)
        return completion
    }

    /** Called only while [processing] is held. */
    private fun recordCompletedPurchaseCommit(identity: PurchaseCommitIdentity) {
        if (completedPurchaseCommits.put(identity, Unit) != null) return
        val observer = purchaseCommitObserver ?: return
        runCatching { observer(PurchaseCommitObservation.Committed(identity)) }
    }

    /** Called at the serialized interpreter boundary. */
    private fun recordTerminalPurchaseOutcome(outcome: PurchaseOutcome) {
        val observer = purchaseCommitObserver ?: return
        runCatching {
            observer(
                PurchaseCommitObservation.Terminal(
                    outcome = outcome,
                    terminal = true,
                ),
            )
        }
    }

    private suspend fun commitStandaloneOutcome(
        product: StoreProduct,
        ownerDistinctId: String,
        outcome: PurchaseOutcome,
        outcomeEventId: String? = null,
    ): PurchaseResult {
        withProcessingDecision { effects ->
            commitPurchaseOutcome(
                outcome,
                effects,
                product,
                ownerDistinctId,
                outcomeEventId,
            )
        }
        return outcome.toPurchaseResult()
    }

    /** Persist and stage local facts while [processing] is held; return any I/O follow-up. */
    private suspend fun commitStoreOutcome(
        purchase: PlayPurchase,
        source: PurchaseOutcomeSource,
        effects: ProcessingEffects,
    ): PurchaseEvidence? {
        val existing = evidenceStore.load()[purchase.purchaseToken]
        val bindings = evidenceStore.loadBindings()
        val mappings = evidenceStore.loadProductMappings()
        val matchingFlight = purchase.products.firstNotNullOfOrNull(inFlight::get)
        val isCheckoutOutcome = matchingFlight?.matches(purchase) == true
        val checkoutFlight = matchingFlight?.takeIf { isCheckoutOutcome }
        val existingProductIdentity = existing?.nuxieProductId?.let { nuxieProductId ->
            existing.storeProductIds.firstOrNull { it in purchase.products }?.let { storeProductId ->
                StoredProductIdentity(
                    storeProductId,
                    nuxieProductId,
                    existing.basePlanId,
                    existing.purchaseOptionId,
                    existing.offerId,
                    existing.productType,
                )
            }
        }
        val product = matchingFlight?.product?.takeIf { isCheckoutOutcome }
            ?: existingProductIdentity?.let { knownIdentity ->
                products.entries.filter { (identity) ->
                    identity.matchesKnownIdentity(knownIdentity)
                }.singleOrNull()?.value
            }
        val knownProductIdentity = existingProductIdentity ?: product?.productIdentity()
        val accountBindings = bindings.filter { binding ->
            binding.obfuscatedAccountId == purchase.obfuscatedAccountId &&
                binding.storeProductId in purchase.products
        }
        val exactBinding = selectFullProductMatch(accountBindings, knownProductIdentity) {
            it.productIdentity
        }
        val catalogBinding = exactBinding ?: selectFullProductMatch(
            bindings.filter { it.storeProductId in purchase.products },
            knownProductIdentity,
        ) { it.productIdentity }
        val catalogMapping = selectFullProductMatch(
            mappings.filter { it.storeProductId in purchase.products },
            knownProductIdentity,
        ) { it.productIdentity }
        val currentOwner = distinctId()
        // Checkout correlation only: which customer's checkout made this Play
        // purchase. Who owns it is only what the server last said.
        val buyerDistinctId = checkoutFlight?.owner
            ?: exactBinding?.distinctId
            ?: currentOwner.takeIf { purchase.obfuscatedAccountId == playAccountId(it) }
            ?: existing?.buyerDistinctId
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
        val pinnedFeatureAllowances = existing?.pinnedFeatureAllowances
            ?: matchingFlight?.product?.takeIf { isCheckoutOutcome }
                ?.featureAllowances?.toStoredAllowances()
            ?: exactBinding?.featureAllowances
            ?: catalogMapping?.featureAllowances
        // The send this observation owes, admitted in the purchase's first durable
        // write: a crash before the send still sends it with this reason, requester
        // and kind. The customer's own checkout is a purchase; anything else syncs.
        val admittedSend = when {
            purchase.state != StoredPurchaseState.PURCHASED -> null
            signatureVerificationRequired && !signatureVerified -> null
            existing?.synced == true || existing?.refused == true -> null
            checkoutFlight != null -> PurchaseSend.admit(
                PurchaseSyncReason.PURCHASE,
                checkoutFlight.owner,
                checkoutFlight.ownerKind,
            )
            else -> (buyerDistinctId ?: currentOwner).let { requester ->
                PurchaseSend.admit(PurchaseSyncReason.SYNC, requester, customerKind(requester))
            }
        }
        val evidenceCandidate = PurchaseEvidence(
            purchaseToken = purchase.purchaseToken,
            packageName = purchase.packageName,
            storeProductIds = purchase.products,
            nuxieProductId = existing?.nuxieProductId ?: product?.productId
                ?: catalogBinding?.nuxieProductId ?: catalogMapping?.nuxieProductId,
            basePlanId = existing?.basePlanId ?: product?.basePlanId
                ?: catalogBinding?.basePlanId ?: catalogMapping?.basePlanId,
            purchaseOptionId = existing?.purchaseOptionId ?: product?.purchaseOptionId
                ?: catalogBinding?.purchaseOptionId ?: catalogMapping?.purchaseOptionId,
            offerId = existing?.offerId ?: product?.offerId
                ?: catalogBinding?.offerId ?: catalogMapping?.offerId,
            productType = existing?.productType ?: product?.productType
                ?: catalogBinding?.productType ?: catalogMapping?.productType,
            purchaseState = purchase.state,
            // Only Play names the account; a missing one stays missing.
            obfuscatedAccountId = purchase.obfuscatedAccountId ?: existing?.obfuscatedAccountId,
            buyerDistinctId = buyerDistinctId,
            ownerDistinctId = existing?.ownerDistinctId,
            refused = existing?.refused == true,
            pendingSend = admittedSend,
            context = existing?.context ?: product?.toStoredContext()
                ?: catalogBinding?.context ?: catalogMapping?.context,
            pinnedFeatureAllowances = pinnedFeatureAllowances,
            acknowledged = purchase.acknowledged || existing?.acknowledged == true,
            consumed = existing?.consumed == true,
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
            nuxieManaged = existing?.nuxieManaged
                ?: matchingFlight?.nuxieManaged?.takeIf { isCheckoutOutcome }
                ?: (settings.handlingMode == PurchaseHandlingMode.NUXIE_MANAGED),
            signatureVerificationRequired = signatureVerificationRequired,
            signatureVerified = signatureVerified,
            authorityScope = purchaseStorageScope,
            backendSyncedAtMillis = existing?.backendSyncedAtMillis,
            checkoutCompletionEventId = existing?.checkoutCompletionEventId
                ?: checkoutFlight?.outcomeEventId
                ?: exactBinding?.outcomeEventId,
        )
        // D2: evidence is durable before projection, facts, sync, acknowledge, or consume.
        val evidence = upsertEvidence(evidenceCandidate)
        evidence?.let { typeReportedPurchase(it) }
        if (evidence == null) {
            val failedOutcome = PurchaseOutcome.Failed(
                IllegalStateException("Could not persist purchase evidence."),
                source,
            )
            recordTerminalPurchaseOutcome(failedOutcome)
            effects.completeCheckoutAfterPublications(
                purchase,
                failedOutcome,
                checkoutFlight,
            )
            return null
        }
        if (evidence.purchaseState == StoredPurchaseState.PENDING) {
            val pendingOutcome = PurchaseOutcome.Pending(source, purchase)
            recordTerminalPurchaseOutcome(pendingOutcome)
            effects.completeCheckoutAfterPublications(
                purchase,
                pendingOutcome,
                checkoutFlight,
            )
            return null
        }
        if (evidence.refused && evidence.pendingSend == null) {
            val failedOutcome = PurchaseOutcome.Failed(
                IllegalStateException("The purchase authority refused this purchase."),
                source,
            )
            recordTerminalPurchaseOutcome(failedOutcome)
            projectionRefresh.withLock { stageOptimisticProjectionLocked() }
                ?.let(effects.publications::add)
            effects.completeCheckoutAfterPublications(
                purchase,
                failedOutcome,
                checkoutFlight,
            )
            return null
        }
        if (!evidence.hasEligiblePurchaseSignature) {
            val failedOutcome = PurchaseOutcome.Failed(
                SecurityException("Play purchase signature is invalid."),
                source,
            )
            recordTerminalPurchaseOutcome(failedOutcome)
            projectionRefresh.withLock { stageOptimisticProjectionLocked() }
                ?.let(effects.publications::add)
            effects.completeCheckoutAfterPublications(
                purchase,
                failedOutcome,
                checkoutFlight,
            )
            return null
        }

        val identity = PurchaseCommitIdentity.Evidence(evidence.purchaseToken)
        val needsCompletionCapture = evidence.buyerDistinctId == distinctId() &&
            !evidence.completionEmitted
        val operation = if (needsCompletionCapture) effects.beginPurchaseCommit(identity) else null
        if (needsCompletionCapture && operation == null) return null
        val reservedCheckout = checkoutFlight?.takeIf {
            needsCompletionCapture && claimedCheckoutCompletions.add(it)
        }
        suspend fun finalizeVerifiedOutcome(target: ProcessingEffects) {
            projectionRefresh.withLock { stageOptimisticProjectionLocked() }
                ?.let(target.publications::add)
            val verifiedOutcome = PurchaseOutcome.Verified(purchase, source)
            if (reservedCheckout != null) {
                target.stageReservedCheckoutCompletion(verifiedOutcome, reservedCheckout)
            } else {
                target.completeCheckoutAfterPublications(
                    purchase,
                    verifiedOutcome,
                    checkoutFlight,
                )
            }
        }
        if (needsCompletionCapture) {
            effects.purchaseCommits += purchaseCompletionCommit(
                identity = identity,
                operation = checkNotNull(operation),
                ownerDistinctId = evidence.buyerDistinctId!!,
                properties = purchaseCompletionProperties(evidence, source),
                eventId = evidence.checkoutCompletionEventId
                    ?: purchaseCommitEventId(identity),
                afterCapture = ::finalizeVerifiedOutcome,
                onFailure = {
                    reservedCheckout?.let(::releaseCheckoutCompletion)
                },
            )
        } else {
            finalizeVerifiedOutcome(effects)
            if (evidence.completionEmitted) {
                recordCompletedPurchaseCommit(identity)
            }
        }
        return evidence
    }

    /** Backend synchronization and Play completion deliberately run outside [processing]. */
    private suspend fun finishPurchase(evidence: PurchaseEvidence) {
        val current = evidenceStore.load()[evidence.purchaseToken] ?: evidence
        when {
            current.pendingSend != null -> sendOwed(current.purchaseToken)
            current.synced -> completeManaged(current)
        }
    }

    /**
     * Ask the server for a purchase for a Restore or an already-owned checkout.
     * The claim is written into the outbox as the claimant's entry before it is
     * sent, so a claim that gets no answer is retried like any other entry,
     * also after a relaunch. One entry per purchase: the claim, the newer
     * intent, takes over whatever entry the purchase had.
     */
    private suspend fun claim(purchaseToken: String, requested: PurchaseSend): SyncResult {
        // The customer changed during the Restore: the claim is not made.
        if (requested.distinctId != distinctId()) return SyncResult.UNAVAILABLE
        // Each purchase's claim is its own admission.
        val claim = PurchaseSend.admit(requested.reason, requested.distinctId, requested.customerKind)
        // Writes to one purchase's entry are serialized: a spend holding this
        // purchase's usage claim finishes before the claim takes the entry over.
        val admitted = withUsageClaim(purchaseToken) {
            withContext(NonCancellable) {
                projectionRefresh.withLock {
                    val current = evidenceStore.load()[purchaseToken] ?: return@withLock false
                    evidenceStore.upsert(current.copy(pendingSend = claim, syncAttempts = 0)).also { saved ->
                        // A new entry: earlier answers no longer end its early unlock.
                        if (saved) answeredTokens -= purchaseToken
                    }
                }
            }
        }
        if (!admitted) return SyncResult.UNAVAILABLE
        // A claim of the buyer's own purchase replaces an entry that unlocked early.
        refreshOptimisticProjection()
        return sendOwed(purchaseToken, expected = claim)
    }

    /**
     * Send [purchaseToken]'s owed entry: the outbox drain. A caller waiting on
     * its own entry ([expected]) gets that entry's answer.
     */
    private suspend fun sendOwed(purchaseToken: String, expected: PurchaseSend? = null): SyncResult {
        while (true) {
            val (answer, owned) = sendInSlot(purchaseToken) { sendOnce(purchaseToken) }
                ?: return SyncResult.UNAVAILABLE
            if (expected != null && answer.send == expected) return answer.result
            // This caller had the slot and nothing was sent: there is nothing to wait for.
            if (owned && (expected == null || answer.send == null)) return answer.result
            // Another send went first. Send ours if it is still owed and that
            // send was not this same entry.
            val owed = evidenceStore.load()[purchaseToken]?.pendingSend ?: return SyncResult.UNAVAILABLE
            if (answer.send == owed) return answer.result
            if (expected != null && owed != expected) return SyncResult.UNAVAILABLE
        }
    }

    /**
     * Run [body] as the token's one send in flight, or wait for the send already
     * in flight; returns that answer and whether this caller sent it.
     */
    private suspend fun sendInSlot(
        purchaseToken: String,
        body: suspend () -> SendAnswer,
    ): Pair<SendAnswer, Boolean>? {
        val (operation, ownsOperation) = synchronized(usageCoordinationLock) {
            if (purchaseToken in purchaseUsageClaims) return null
            val existing = sending[purchaseToken]
            if (existing != null) {
                existing to false
            } else {
                CompletableDeferred<SendAnswer>().also { sending[purchaseToken] = it } to true
            }
        }
        if (ownsOperation) {
            // The host owns only its wait. SDK lifetime owns an admitted send
            // and applying the server's answer. Keep the caller's dispatcher;
            // replace only its cancellation owner.
            val job = scope.launch(currentCoroutineContext().minusKey(Job)) {
                try {
                    val answer = body()
                    if (answer.result != SyncResult.ACCEPTED) {
                        // A purchase the server accepted earlier still completes with Play.
                        evidenceStore.load()[purchaseToken]?.takeIf { it.synced }?.let { completeManaged(it) }
                    }
                    releaseSending(purchaseToken, operation)
                    operation.complete(answer)
                } catch (cancelled: CancellationException) {
                    operation.cancel(cancelled)
                    throw cancelled
                } catch (failure: Exception) {
                    Log.w("NuxieBilling", "Could not apply purchase synchronization.", failure)
                    releaseSending(purchaseToken, operation)
                    operation.complete(SendAnswer(null, SyncResult.UNAVAILABLE))
                }
            }
            job.invokeOnCompletion { failure ->
                // Also settle waiters if SDK shutdown prevented the job body
                // from starting, or if a fatal failure escaped it.
                if (failure != null) operation.completeExceptionally(failure)
                releaseSending(purchaseToken, operation)
            }
        }
        return operation.await() to ownsOperation
    }

    private fun releaseSending(purchaseToken: String, operation: CompletableDeferred<SendAnswer>) {
        synchronized(usageCoordinationLock) {
            if (sending[purchaseToken] === operation) sending.remove(purchaseToken)
        }
    }

    /** Send every entry kept after a failed send again now. */
    private suspend fun resendKeptEntries() {
        evidenceStore.load().values
            .filter { it.pendingSend != null && it.syncAttempts > 0 }
            .forEach { sendOwed(it.purchaseToken) }
    }

    /**
     * Send the token's owed entry once and apply the server's answer. A 200, a
     * 409 or a 400 answers the send; anything else (transport, 5xx, 401, 403)
     * keeps the entry for another attempt.
     */
    private suspend fun sendOnce(purchaseToken: String): SendAnswer {
        val evidence = evidenceStore.load()[purchaseToken] ?: return SendAnswer(null, SyncResult.UNAVAILABLE)
        // A purchase whose required Play signature never verified is never sent.
        if (!evidence.hasEligiblePurchaseSignature) return SendAnswer(null, SyncResult.UNAVAILABLE)
        val send = evidence.pendingSend ?: return SendAnswer(null, SyncResult.UNAVAILABLE)
        val outcome = try {
            synchronizer.sync(
                evidence.copy(pendingSend = send),
                PurchaseSyncRequest(send.reason, send.distinctId, send.customerKind),
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            Log.w("NuxieBilling", "Purchase synchronization failed.", failure)
            PurchaseSyncOutcome.Rejected()
        }
        return when (outcome) {
            is PurchaseSyncOutcome.Rejected -> if (outcome.refused) {
                answerRefusal(evidence, send)
                SendAnswer(send, SyncResult.REFUSED)
            } else {
                recordFailedSend(purchaseToken, send)
                SendAnswer(send, SyncResult.UNAVAILABLE)
            }
            is PurchaseSyncOutcome.Accepted -> {
                val result = answerAcceptance(evidence, send, outcome.response)
                // A 200 whose body the SDK cannot use keeps the entry, like any failed send.
                if (result != SyncResult.ACCEPTED) recordFailedSend(purchaseToken, send)
                SendAnswer(send, result)
            }
        }
    }

    /** A send without an answer keeps its entry, with one more attempt and a retry after the backoff. */
    private suspend fun recordFailedSend(purchaseToken: String, send: PurchaseSend) {
        val attempts = withContext(NonCancellable) {
            projectionRefresh.withLock {
                val current = evidenceStore.load()[purchaseToken] ?: return@withLock null
                if (current.pendingSend != send) return@withLock null
                val failed = current.copy(syncAttempts = current.syncAttempts + 1)
                if (evidenceStore.upsert(failed)) failed.syncAttempts else current.syncAttempts + 1
            }
        } ?: return
        scheduleSendRetry(purchaseToken, attempts)
    }

    /** The server attached the purchase to [send]'s requester: that is now its owner. */
    private suspend fun answerAcceptance(
        attempted: PurchaseEvidence,
        send: PurchaseSend,
        response: NuxieApi.PurchaseResponse,
    ): SyncResult {
        val catalog = response.catalogProduct
        if (catalog == null && !attempted.catalogResolved) return SyncResult.UNAVAILABLE
        if (catalog != null && catalog.storeProductId !in attempted.storeProductIds) {
            return SyncResult.UNAVAILABLE
        }
        val serverProductType = when (catalog?.storeProductType) {
            null -> attempted.productType
            "subscription" -> BillingClient.ProductType.SUBS
            "consumable", "nonConsumable" -> BillingClient.ProductType.INAPP
            else -> return SyncResult.UNAVAILABLE
        }
        val answer = withContext(NonCancellable) {
            projectionRefresh.withLock {
                val current = evidenceStore.load()[attempted.purchaseToken]
                // The entry was replaced by a newer admission: this answer only
                // asks for a fresh profile.
                if (current == null || current.pendingSend != send) return@withLock EntryAnswer.Stale
                answeredTokens += attempted.purchaseToken
                val accepted = current.copy(
                    nuxieProductId = catalog?.productId ?: current.nuxieProductId,
                    basePlanId = catalog?.basePlanId ?: current.basePlanId,
                    purchaseOptionId = catalog?.purchaseOptionId ?: current.purchaseOptionId,
                    offerId = catalog?.offerId ?: current.offerId,
                    productType = serverProductType,
                    consumable = catalog?.storeProductType == "consumable" ||
                        (catalog == null && current.consumable),
                    catalogResolved = current.catalogResolved || catalog != null,
                    ownerDistinctId = send.distinctId,
                    refused = false,
                    pendingSend = null,
                    syncAttempts = 0,
                    syncedCustomerId = response.customerId,
                    backendSyncedAtMillis = nowMillis(),
                )
                // Unsaved, the answer still ends the early unlock.
                if (!evidenceStore.upsert(accepted)) return@withLock EntryAnswer.Unsaved(stageOptimisticProjectionLocked())
                val currentDistinctId = distinctId()
                // The 200's Features bridge until the profile refresh lands; nobody
                // else keeps the early unlock this acceptance ends.
                val publication = if (send.distinctId == currentDistinctId) {
                    features.stagePurchaseAnswer(
                        currentDistinctId,
                        response.body,
                        deriveOptimisticProjection(currentDistinctId),
                    )
                } else {
                    stageOptimisticProjectionLocked()
                }
                EntryAnswer.Saved(accepted, publication)
            }
        }
        when (answer) {
            is EntryAnswer.Saved -> features.publishStaged(answer.publication)
            // The answer could not be saved: the entry stays owed and is sent
            // again, but the answer already ends the early unlock.
            is EntryAnswer.Unsaved -> features.publishStaged(answer.publication)
            EntryAnswer.Stale -> Unit
        }
        if (send.distinctId == distinctId()) refreshFeatureAuthority()
        return when (answer) {
            is EntryAnswer.Saved -> {
                completeManaged(emitPurchaseSyncedIfNeeded(answer.evidence))
                SyncResult.ACCEPTED
            }
            is EntryAnswer.Unsaved -> SyncResult.UNAVAILABLE
            // The server accepted this send; the newer entry is still owed.
            EntryAnswer.Stale -> SyncResult.ACCEPTED
        }
    }

    private sealed interface EntryAnswer {
        data class Saved(val evidence: PurchaseEvidence, val publication: FeatureInfo.Mutation?) : EntryAnswer
        /** The answer is for the current entry but could not be written. */
        data class Unsaved(val publication: FeatureInfo.Mutation?) : EntryAnswer
        /** A newer admission replaced the entry this send was for. */
        data object Stale : EntryAnswer
    }

    /**
     * The server refused [send] (a 409 conflict or a 400 invalid purchase): its
     * requester is not the owner, and an entry it answers is cleared. A purchase
     * left with no owner and no owed send is never sent again on its own, only
     * by a Restore.
     */
    private suspend fun answerRefusal(
        attempted: PurchaseEvidence,
        send: PurchaseSend,
    ) {
        val answer = withContext(NonCancellable) {
            projectionRefresh.withLock {
                val current = evidenceStore.load()[attempted.purchaseToken]
                // The entry was replaced by a newer admission: this answer only
                // asks for a fresh profile.
                if (current == null || current.pendingSend != send) return@withLock EntryAnswer.Stale
                answeredTokens += attempted.purchaseToken
                val owner = current.ownerDistinctId.takeUnless { it == send.distinctId }
                val answered = current.copy(
                    pendingSend = null,
                    syncAttempts = 0,
                    ownerDistinctId = owner,
                    refused = current.refused || owner == null,
                )
                val saved = evidenceStore.upsert(answered)
                // Access is whatever the profile requested below says; an earlier
                // 200's Features hold only until it lands.
                val publication = stageOptimisticProjectionLocked()
                if (saved) EntryAnswer.Saved(answered, publication) else EntryAnswer.Unsaved(publication)
            }
        }
        when (answer) {
            is EntryAnswer.Saved -> features.publishStaged(answer.publication)
            // A refusal that could not be written leaves the entry owed: it is
            // sent again, though the answer already ended its early unlock.
            is EntryAnswer.Unsaved -> {
                features.publishStaged(answer.publication)
                recordFailedSend(attempted.purchaseToken, send)
            }
            EntryAnswer.Stale -> Unit
        }
        if (send.distinctId == distinctId()) refreshFeatureAuthority()
    }

    private suspend fun emitPurchaseSyncedIfNeeded(evidence: PurchaseEvidence): PurchaseEvidence =
        projectionRefresh.withLock {
            val current = evidenceStore.load()[evidence.purchaseToken] ?: evidence
            if (current.syncedEventEmitted || current.ownerDistinctId != distinctId()) {
                return@withLock current
            }
            val properties = buildMap<String, Any?> {
                put("transaction_id", current.purchaseToken)
                put("original_transaction_id", current.purchaseToken)
                put("product_id", current.storeProductIds.firstOrNull().orEmpty())
                put("customer_id", current.syncedCustomerId.orEmpty())
                current.context?.experienceId?.let { put("experience_id", it) }
                current.context?.experienceVersion?.let { put("experience_version_id", it) }
                current.context?.journeyId?.let { put("journey_id", it) }
            }
            emit(SystemEventNames.PURCHASE_SYNCED, properties)
            current.copy(syncedEventEmitted = true).takeIf { evidenceStore.upsert(it) } ?: current
        }

    private suspend fun completeManaged(evidence: PurchaseEvidence) {
        if (completionRetryJobs[evidence.purchaseToken]?.isActive == true) return
        val ownsCompletion = synchronized(usageCoordinationLock) {
            managedCompletionClaims.add(evidence.purchaseToken)
        }
        if (!ownsCompletion) return
        try {
            val attempted = projectionRefresh.withLock {
                val current = evidenceStore.load()[evidence.purchaseToken] ?: evidence
                if (!current.synced || !current.needsManagedCompletion()) return@withLock null
                current.copy(completionAttempts = current.completionAttempts + 1)
                    .takeIf { evidenceStore.upsert(it) }
            } ?: return
            val completion = if (attempted.consumable) {
                billing.consume(evidence.purchaseToken)
            } else {
                billing.acknowledge(evidence.purchaseToken)
            }
            val completed = when {
                completion.responseCode == BillingClient.BillingResponseCode.OK -> true
                attempted.consumable &&
                    completion.responseCode == BillingClient.BillingResponseCode.ITEM_NOT_OWNED &&
                    attempted.completionAttempts > 1 -> reconcilePriorConsume(attempted.purchaseToken)
                else -> false
            }
            if (completed) {
                projectionRefresh.withLock {
                    val current = evidenceStore.load()[attempted.purchaseToken] ?: return@withLock
                    evidenceStore.upsert(
                        if (attempted.consumable) current.copy(consumed = true)
                        else current.copy(acknowledged = true),
                    )
                }
            } else {
                scheduleCompletionRetry(evidence.purchaseToken, attempted.completionAttempts)
            }
        } finally {
            synchronized(usageCoordinationLock) {
                managedCompletionClaims.remove(evidence.purchaseToken)
            }
        }
    }

    /**
     * A consume may commit in Play before the terminal local write commits.
     * Only a durable prior attempt plus a successful active-purchase query
     * proving the token absent can turn ITEM_NOT_OWNED into success.
     */
    private suspend fun reconcilePriorConsume(purchaseToken: String): Boolean =
        when (val active = queryPlay(BillingClient.ProductType.INAPP)) {
            is ActivePurchasesResult.Success -> active.purchases.none {
                it.purchaseToken == purchaseToken
            }
            is ActivePurchasesResult.Failed -> false
        }

    private fun PurchaseEvidence.needsManagedCompletion(): Boolean =
        nuxieManaged && catalogResolved && if (consumable) !consumed else !acknowledged

    private fun hasConfiguredLicensingKey(
        evidence: PurchaseEvidence,
        bindings: List<StoredPurchaseBinding>,
        mappings: List<StoredProductMapping>,
    ): Boolean = bindings.any {
        it.licensingPublicKey != null && evidence.matchesProductIdentity(it.productIdentity)
    } || mappings.any {
        it.licensingPublicKey != null && evidence.matchesProductIdentity(it.productIdentity)
    }

    private fun <T> selectFullProductMatch(
        candidates: List<T>,
        knownIdentity: StoredProductIdentity?,
        identity: (T) -> StoredProductIdentity,
    ): T? = if (knownIdentity == null) {
        candidates.singleOrNull()
    } else {
        candidates.filter { identity(it).matchesKnownIdentity(knownIdentity) }.singleOrNull()
    }

    private suspend fun upsertEvidence(candidate: PurchaseEvidence): PurchaseEvidence? =
        projectionRefresh.withLock { upsertEvidenceLocked(candidate) }

    /**
     * Merge an observation's monotonic Play facts; called only while
     * [projectionRefresh] is held. The server's word (owner, refusal, customer)
     * and an owed send come only from the server's answers and admissions, so
     * an observation read before an answer cannot undo it. An observation adds
     * a send only for a purchase that owes none and has no answer yet, and the
     * customer's own checkout upgrades only an owed entry's reason.
     */
    private suspend fun upsertEvidenceLocked(candidate: PurchaseEvidence): PurchaseEvidence? {
        val current = evidenceStore.load()[candidate.purchaseToken]
        val merged = if (current == null) candidate else candidate.copy(
            purchaseState = if (candidate.purchaseState == StoredPurchaseState.PURCHASED ||
                current.purchaseState == StoredPurchaseState.PURCHASED
            ) {
                StoredPurchaseState.PURCHASED
            } else {
                StoredPurchaseState.PENDING
            },
            buyerDistinctId = candidate.buyerDistinctId ?: current.buyerDistinctId,
            ownerDistinctId = current.ownerDistinctId,
            refused = current.refused,
            pendingSend = when {
                current.pendingSend == null ->
                    candidate.pendingSend.takeUnless { current.synced || current.refused }
                // An unsent entry keeps its requester and kind; the customer's
                // own checkout delivering it upgrades only its reason.
                candidate.pendingSend?.reason?.claimsOwnership == true -> candidate.pendingSend.reason
                    .strongest(current.pendingSend.reason)
                    .let { reason ->
                        // A changed reason is a new admission with a fresh id.
                        if (reason == current.pendingSend.reason) {
                            current.pendingSend
                        } else {
                            PurchaseSend.admit(reason, current.pendingSend.distinctId, current.pendingSend.customerKind)
                        }
                    }
                else -> current.pendingSend
            },
            acknowledged = candidate.acknowledged || current.acknowledged,
            consumed = candidate.consumed || current.consumed,
            syncAttempts = maxOf(candidate.syncAttempts, current.syncAttempts),
            completionAttempts = maxOf(candidate.completionAttempts, current.completionAttempts),
            firstSeenMillis = minOf(candidate.firstSeenMillis, current.firstSeenMillis),
            catalogResolved = candidate.catalogResolved || current.catalogResolved,
            completionEmitted = candidate.completionEmitted || current.completionEmitted,
            syncedEventEmitted = candidate.syncedEventEmitted || current.syncedEventEmitted,
            syncedCustomerId = current.syncedCustomerId,
            nuxieManaged = candidate.nuxieManaged || current.nuxieManaged,
            signatureVerificationRequired = candidate.signatureVerificationRequired ||
                current.signatureVerificationRequired,
            signatureVerified = candidate.signatureVerified || current.signatureVerified,
            backendSyncedAtMillis = current.backendSyncedAtMillis,
            checkoutCompletionEventId = current.checkoutCompletionEventId
                ?: candidate.checkoutCompletionEventId,
            pinnedFeatureAllowances = current.pinnedFeatureAllowances
                ?: candidate.pinnedFeatureAllowances,
        )
        if (!evidenceStore.upsert(merged)) return null
        return merged
    }

    /**
     * Send [token]'s entry again when it is due: the failure time plus the delay
     * for its attempt count. A later failure replaces an earlier timer, so each
     * entry keeps its own due time.
     */
    private fun scheduleSendRetry(token: String, attempts: Int) {
        sendRetryJobs.remove(token)?.cancel()
        sendRetryJobs[token] = scope.launch {
            delay(outboxRetryDelay(attempts))
            // ConcurrentMap's own remove(key, value): available on every supported API level.
            (sendRetryJobs as java.util.concurrent.ConcurrentMap<String, Job>).remove(token, coroutineContext[Job])
            val due = evidenceStore.load()[token]?.pendingSend ?: return@launch
            val held = synchronized(usageCoordinationLock) { token in purchaseUsageClaims }
            // A due entry a purchase-backed spend holds cannot be sent now: it
            // counts one more failed attempt, so its time moves on.
            if (held) recordFailedSend(token, due) else sendOwed(token)
        }
    }

    private fun scheduleCompletionRetry(token: String, attempt: Int) {
        if (completionRetryJobs[token]?.isActive == true) return
        completionRetryJobs[token] = scope.launch {
            delay(retryDelay(attempt))
            completionRetryJobs.remove(token)
            evidenceStore.load()[token]
                ?.takeIf { it.synced && it.needsManagedCompletion() }
                ?.let { completeManaged(emitPurchaseSyncedIfNeeded(it)) }
        }
    }

    private fun outboxRetryDelay(attempts: Int): Long =
        outboxRetryDelayMillis(attempts, outboxRetryInitialDelayMillis, outboxRetryMaxDelayMillis)

    private fun retryDelay(attempt: Int): Long =
        (initialRetryDelayMillis * (1L shl attempt.coerceAtMost(16)))
            .coerceAtMost(maxRetryDelayMillis)

    private fun purchaseCompletionProperties(
        evidence: PurchaseEvidence,
        source: PurchaseOutcomeSource,
    ): Map<String, Any?> =
        linkedMapOf<String, Any?>(
            "product_id" to (evidence.nuxieProductId ?: evidence.storeProductIds.firstOrNull().orEmpty()),
            "placement_id" to evidence.context?.placementId,
            "store_product_id" to evidence.storeProductIds.firstOrNull().orEmpty(),
            "experience_id" to evidence.context?.experienceId,
            "experience_version_id" to evidence.context?.experienceVersion,
            "journey_id" to evidence.context?.journeyId,
            "source" to source.wireValue,
            "test_store" to false,
            "transaction_id" to evidence.purchaseToken,
            "price" to evidence.context?.price?.toDouble(),
            "display_price" to evidence.context?.displayPrice,
        ).filterValues { it != null }

    private fun purchaseCompletionProperties(
        product: StoreProduct,
        source: PurchaseOutcomeSource,
        testStore: Boolean = false,
        transactionId: String? = null,
    ): Map<String, Any?> {
        val price = product.storePrice()
        return linkedMapOf<String, Any?>(
            "product_id" to product.productId,
            "placement_id" to product.placementId,
            "store_product_id" to product.storeProductId,
            "experience_id" to product.purchaseContext?.experienceId,
            "experience_version_id" to product.purchaseContext?.experienceVersion,
            "journey_id" to product.purchaseContext?.journeyId,
            "source" to source.wireValue,
            "test_store" to testStore,
            "transaction_id" to transactionId,
            "price" to price?.amount?.toDouble(),
            "display_price" to price?.display,
        ).filterValues { it != null }
    }

    /** The only construction site for a `$purchase_completed` capture. */
    private fun purchaseCompletionCommit(
        identity: PurchaseCommitIdentity,
        operation: CompletableDeferred<Unit>,
        ownerDistinctId: String,
        properties: Map<String, Any?>,
        eventId: String = purchaseCommitEventId(identity),
        afterCapture: suspend (ProcessingEffects) -> Unit = {},
        onFailure: () -> Unit = {},
        bestEffort: Boolean = false,
    ): PendingPurchaseCommit = PendingPurchaseCommit(
        identity = identity,
        operation = operation,
        eventName = SystemEventNames.PURCHASE_COMPLETED,
        eventId = eventId,
        ownerDistinctId = ownerDistinctId,
        properties = properties,
        afterCapture = afterCapture,
        onFailure = onFailure,
        bestEffort = bestEffort,
    )

    private fun purchaseCommitEventId(identity: PurchaseCommitIdentity): String = stableEventId(
        "purchase-completed:",
        when (identity) {
            is PurchaseCommitIdentity.Evidence -> listOf(
                purchaseStorageScope,
                "verified",
                identity.purchaseToken,
            )
            is PurchaseCommitIdentity.External -> listOf(
                purchaseStorageScope,
                "external",
                identity.operationId,
            )
        },
    )

    private suspend fun complete(checkout: InFlightPurchase, outcome: PurchaseOutcome) {
        if (!inFlight.remove(checkout.product.storeProductId, checkout)) return
        try {
            emitPurchaseOutcome(
                checkout.product,
                outcome,
                checkout.owner,
                checkout.outcomeEventId,
            )
            checkout.result.complete(outcome.toPurchaseResult())
        } catch (failure: Throwable) {
            checkout.result.completeExceptionally(failure)
            throw failure
        }
    }

    private fun InFlightPurchase.matches(purchase: PlayPurchase): Boolean =
        purchase.obfuscatedAccountId == obfuscatedAccountId &&
            purchase.purchaseToken !in priorTokens &&
            product.storeProductId in purchase.products

    private fun ProcessingEffects.completeAllAfterPublications(outcome: PurchaseOutcome) {
        val keys = inFlight.keys()
        while (keys.hasMoreElements()) {
            val key = keys.nextElement()
            val pending = inFlight[key] ?: continue
            // A successful callback already reserved this checkout's ordered drain.
            if (pending in claimedCheckoutCompletions) continue
            if (!inFlight.remove(key, pending)) continue
            directCheckoutCompletions += PendingDirectCheckoutCompletion(pending, outcome)
        }
    }

    private suspend fun failed(
        product: StoreProduct,
        cause: Throwable,
        initiatingOwner: String,
        source: PurchaseOutcomeSource,
        outcomeEventId: String? = null,
    ): PurchaseResult.Failed = commitStandaloneOutcome(
        product,
        initiatingOwner,
        PurchaseOutcome.Failed(cause, source),
        outcomeEventId,
    ) as PurchaseResult.Failed

    private suspend fun emitPurchaseOutcome(
        product: StoreProduct,
        outcome: PurchaseOutcome,
        initiatingOwner: String,
        outcomeEventId: String? = null,
    ) {
        if (outcomeEventId == null && distinctId() != initiatingOwner) return
        val price = product.storePrice()
        val properties = linkedMapOf<String, Any?>(
            "product_id" to product.productId,
            "store_product_id" to product.storeProductId,
            "placement_id" to product.placementId,
            "experience_id" to product.purchaseContext?.experienceId,
            "experience_version_id" to product.purchaseContext?.experienceVersion,
            "journey_id" to product.purchaseContext?.journeyId,
            "test_store" to outcome.testStore,
            "price" to price?.amount?.toDouble(),
            "display_price" to price?.display,
        ).filterValues { it != null }.toMutableMap()
        when (outcome) {
            is PurchaseOutcome.Verified,
            is PurchaseOutcome.External,
            -> return
            is PurchaseOutcome.Cancelled -> captureOrEmitPurchaseOutcome(
                SystemEventNames.PURCHASE_CANCELLED,
                properties,
                outcomeEventId,
                initiatingOwner,
            )
            is PurchaseOutcome.Pending -> {
                // Pending is telemetry, not the correlated terminal Journey outcome.
                // The uncorrelated emitter belongs to the current customer only.
                if (distinctId() == initiatingOwner) emit(SystemEventNames.PURCHASE_PENDING, properties)
            }
            is PurchaseOutcome.Failed -> {
                properties["error"] = outcome.reason.message ?: outcome.reason.javaClass.simpleName
                captureOrEmitPurchaseOutcome(
                    SystemEventNames.PURCHASE_FAILED,
                    properties,
                    outcomeEventId,
                    initiatingOwner,
                )
            }
        }
        if (outcome is PurchaseOutcome.Cancelled || outcome is PurchaseOutcome.Failed) {
            check(clearOutcomeCorrelation(outcomeEventId)) {
                "Could not clear the completed Journey purchase correlation."
            }
        }
    }

    private suspend fun captureOrEmitPurchaseOutcome(
        name: String,
        properties: Map<String, Any?>,
        outcomeEventId: String?,
        initiatingOwner: String,
    ) {
        if (outcomeEventId == null) {
            emit(name, properties)
        } else {
            check(capturePurchaseEvent(name, properties, outcomeEventId, initiatingOwner)) {
                "Could not durably capture the Journey purchase outcome event."
            }
        }
    }

    private fun PurchaseOutcome.toPurchaseResult(): PurchaseResult = when (this) {
        is PurchaseOutcome.Verified,
        is PurchaseOutcome.External,
        -> PurchaseResult.Purchased
        is PurchaseOutcome.Cancelled -> PurchaseResult.Cancelled
        is PurchaseOutcome.Pending -> PurchaseResult.Pending
        is PurchaseOutcome.Failed -> PurchaseResult.Failed(reason)
    }

    private fun PurchaseResult.toPurchaseOutcome(
        source: PurchaseOutcomeSource,
        testStore: Boolean = false,
    ): PurchaseOutcome = when (this) {
        PurchaseResult.Purchased -> error("A purchased delegate result requires an external declaration.")
        PurchaseResult.Cancelled -> PurchaseOutcome.Cancelled(source, testStore)
        PurchaseResult.Pending -> PurchaseOutcome.Pending(source, testStore = testStore)
        is PurchaseResult.Failed -> PurchaseOutcome.Failed(cause, source, testStore)
    }

    private suspend fun emitRestoreOutcome(
        result: RestoreResult,
        initiatingOwner: String,
        outcomeEventId: String? = null,
        testStore: Boolean = false,
    ) {
        if (outcomeEventId == null && distinctId() != initiatingOwner) return
        val (name, properties) = when (result) {
            RestoreResult.Restored -> SystemEventNames.RESTORE_COMPLETED to emptyMap()
            RestoreResult.NoPurchases -> SystemEventNames.RESTORE_NO_PURCHASES to emptyMap()
            is RestoreResult.Failed -> SystemEventNames.RESTORE_FAILED to mapOf(
                "error" to (result.cause.message ?: result.cause.javaClass.simpleName),
            )
        }
        val taggedProperties = if (testStore) properties + ("test_store" to true) else properties
        if (outcomeEventId == null) {
            emit(name, taggedProperties)
        } else {
            check(capturePurchaseEvent(name, taggedProperties, outcomeEventId, initiatingOwner)) {
                "Could not durably capture the Journey restore outcome event."
            }
        }
    }

    private fun StoreProduct.toBinding(
        accountId: String,
        owner: String,
        outcomeEventId: String? = null,
    ) = StoredPurchaseBinding(
        obfuscatedAccountId = accountId,
        distinctId = owner,
        storeProductId = storeProductId,
        nuxieProductId = productId,
        basePlanId = basePlanId,
        purchaseOptionId = purchaseOptionId,
        offerId = offerId,
        productType = productType,
        consumable = consumable,
        context = toStoredContext(),
        featureAllowances = featureAllowances.toStoredAllowances(),
        licensingPublicKey = licensingPublicKey,
        nuxieManaged = settings.handlingMode == PurchaseHandlingMode.NUXIE_MANAGED,
        outcomeEventId = outcomeEventId,
    )

    internal suspend fun rememberProduct(product: StoreProduct): Boolean =
        evidenceStore.upsertProductMapping(product.toMapping()).also { persisted ->
            if (persisted) scope.launch { refreshOptimisticProjection() }
        }

    private fun StoreProduct.toMapping() = StoredProductMapping(
        storeProductId = storeProductId,
        nuxieProductId = productId,
        basePlanId = basePlanId,
        purchaseOptionId = purchaseOptionId,
        offerId = offerId,
        productType = productType,
        consumable = consumable,
        context = toStoredContext().copy(journeyId = null),
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
            purchaseContext?.journeyId,
        )
    }

    private fun List<FeatureAllowance>.toStoredAllowances() = map {
        StoredFeatureAllowance(it.featureId, it.type.name, it.unlimited, it.allowance)
    }

    private fun StoreProduct.productIdentity() = StoredProductIdentity(
        storeProductId,
        productId,
        basePlanId,
        purchaseOptionId,
        offerId,
        productType,
    )

    /**
     * Ask Play for its active purchases of both types, recording what it
     * reports. Returns the purchases Play reported.
     */
    internal suspend fun refreshPlayReport(): List<PlayPurchase> {
        val pass = beginPlayPass()
        val active = mutableListOf<PlayPurchase>()
        var bothAnswered = true
        for (type in listOf(BillingClient.ProductType.SUBS, BillingClient.ProductType.INAPP)) {
            val result = runCatching { queryPlay(type) }.getOrNull()
            if (result is ActivePurchasesResult.Success) active += result.purchases else bothAnswered = false
        }
        if (bothAnswered) endPlayPass(pass)
        return active
    }

    /**
     * The one place Play's latest report changes. Applies [change], runs
     * [during], then, when the set of reported purchases changed, republishes
     * the early unlock, so no caller can change the report without publishing
     * it. If [during] fails, the purchases this change added leave the report
     * again, so a commit that failed exposes nothing.
     */
    private suspend fun <T> updatePlayReport(
        change: (MutableMap<String, String?>) -> Unit,
        during: suspend () -> T,
    ): T {
        val (removed, added) = synchronized(playReported) {
            val before = playReported.keys.toSet()
            change(playReported)
            (before - playReported.keys) to (playReported.keys - before)
        }
        var completed = false
        try {
            return during().also { completed = true }
        } finally {
            if (!completed) synchronized(playReported) { added.forEach(playReported::remove) }
            if (removed.isNotEmpty() || (completed && added.isNotEmpty())) {
                withContext(NonCancellable) { refreshOptimisticProjection() }
            }
        }
    }

    private suspend fun updatePlayReport(change: (MutableMap<String, String?>) -> Unit) =
        updatePlayReport(change) {}

    /**
     * Ask Play for its active purchases of [productType], recording what it
     * reports. A query speaks only for its own type: it replaces that type's
     * tokens and leaves the other type's, and those of unknown type, alone.
     */
    private suspend fun queryPlay(@BillingClient.ProductType productType: String): ActivePurchasesResult {
        val result = billing.queryActive(productType)
        if (result is ActivePurchasesResult.Success) {
            updatePlayReport { reported ->
                reported.entries.removeAll { (_, type) -> type == productType }
                result.purchases.forEach { reported[it.purchaseToken] = productType }
            }
        }
        return result
    }

    /** The tokens of unknown type before a pass that queries both types. */
    private fun beginPlayPass(): Set<String> =
        synchronized(playReported) { playReported.filterValues { it == null }.keys.toSet() }

    /**
     * Both types answered in one pass: a token of unknown type from before the
     * pass that neither query reported has left Play's report.
     */
    private suspend fun endPlayPass(unknownBefore: Set<String>) {
        updatePlayReport { reported ->
            unknownBefore.forEach { token -> if (reported[token] == null) reported.remove(token) }
        }
    }

    /**
     * Play delivered these purchases: it reports them until a query of their
     * type says otherwise, and [process] commits them. Each keeps the product
     * type its checkout or the catalog knows for it; [typeReportedPurchase]
     * fills in the rest from the evidence the update commits. The commit
     * publishes the early unlock in its own order, and the report's own
     * republish follows it.
     */
    private suspend fun recordPlayUpdate(purchases: List<PlayPurchase>, process: suspend () -> Unit) {
        val types = purchases.map { purchase ->
            purchase.purchaseToken to purchase.products.firstNotNullOfOrNull { storeProductId ->
                inFlight[storeProductId]?.product?.productType
                    ?: products.values.firstOrNull { it.storeProductId == storeProductId }?.productType
            }
        }
        updatePlayReport(
            { reported -> types.forEach { (token, type) -> if (reported[token] == null) reported[token] = type } },
            process,
        )
    }

    /** A purchase Play reported with no known type takes the type its committed evidence records. */
    private suspend fun typeReportedPurchase(evidence: PurchaseEvidence) {
        val type = evidence.productType ?: return
        updatePlayReport { reported ->
            if (evidence.purchaseToken in reported && reported[evidence.purchaseToken] == null) {
                reported[evidence.purchaseToken] = type
            }
        }
    }

    private fun reportedByPlay(purchaseToken: String): Boolean =
        synchronized(playReported) { purchaseToken in playReported }

    /**
     * Beyond the stored entry, the early unlock needs Play to report the
     * purchase in its latest query and no server answer for it in this process.
     */
    private fun unlocksEarly(purchaseToken: String): Boolean =
        reportedByPlay(purchaseToken) && purchaseToken !in answeredTokens

    private suspend fun refreshOptimisticProjection() {
        val publication = projectionRefresh.withLock { stageOptimisticProjectionLocked() }
        features.publishStaged(publication)
    }

    /** Called only while [projectionRefresh] is held. */
    private suspend fun stageOptimisticProjectionLocked(): FeatureInfo.Mutation? {
        val currentDistinctId = distinctId()
        return features.stageOptimisticPurchaseProjection(
            currentDistinctId,
            deriveOptimisticProjection(currentDistinctId),
        )
    }

    /**
     * Coordinate a destination projection snapshot with its synchronous Feature installation.
     * The caller may publish the staged Feature mutation after this returns.
     */
    internal suspend fun <T> withOptimisticProjectionSnapshot(
        distinctId: String,
        install: (Map<String, OptimisticFeatureOverlay>?) -> T,
    ): T = projectionRefresh.withLock {
        install(deriveOptimisticProjection(distinctId))
    }

    /** Resolve each retained token once; later catalog replacements cannot swap its allowances. */
    private suspend fun deriveOptimisticProjection(currentDistinctId: String = distinctId()):
        Map<String, OptimisticFeatureOverlay>? {
        if (testStore != null) return null
        val descriptors = evidenceStore.loadProductMappings()
        val bindings = evidenceStore.loadBindings()
        // Cross-commit visibility is deliberate: an evidence row is DURABLE
        // at persist time and the accepted optimistic-projection spec derives
        // the overlay from the durable evidence window with no dependency on
        // the purchase-completed carrier. The committer's evidence -> signal
        // -> projection order is per-commit discipline; another commit's
        // refresh surfacing this row before this commit's carrier lands is
        // spec-conformant, not a leak (adjudicated UNIV-2676 review r2).
        val retainedEvidence = evidenceStore.load().values
        val identitiesByToken = retainedEvidence.associate { retained ->
            retained.purchaseToken to retained.allowanceResolutionIdentity()
        }
        pendingAllowancePins.entries.removeAll { (token, pending) ->
            identitiesByToken[token] != pending.identity
        }
        val evidence = retainedEvidence.mapNotNull { retained ->
            if (retained.pinnedFeatureAllowances != null) {
                pendingAllowancePins.remove(retained.purchaseToken)
                return@mapNotNull retained
            }
            val identity = identitiesByToken.getValue(retained.purchaseToken)
            val pending = pendingAllowancePins[retained.purchaseToken]
                ?.takeIf { it.identity == identity }
            val resolved = pending?.allowances
                ?: resolvedFeatureAllowancesForEvidence(retained, descriptors, bindings)
                ?: return@mapNotNull retained
            if (pending == null) {
                pendingAllowancePins[retained.purchaseToken] = PendingAllowancePin(identity, resolved)
            }
            // A resolved allowance projection is safe only after its token-scoped
            // allowance snapshot is durable. Otherwise a later replacement or
            // relaunch could silently swap the retained purchase's allowances.
            upsertEvidenceLocked(retained.copy(pinnedFeatureAllowances = resolved))?.also {
                pendingAllowancePins.remove(retained.purchaseToken)
            }
        }
        return optimisticFeatureProjection(
            distinctId = currentDistinctId,
            authorityScope = purchaseStorageScope,
            evidence = evidence.filter { unlocksEarly(it.purchaseToken) },
            descriptors = descriptors,
            bindings = bindings,
        )
    }

    private fun PurchaseEvidence.allowanceResolutionIdentity() = AllowanceResolutionIdentity(
        purchaseToken = purchaseToken,
        obfuscatedAccountId = obfuscatedAccountId,
        storeProductIds = storeProductIds.toSet(),
        nuxieProductId = nuxieProductId,
        basePlanId = basePlanId,
        purchaseOptionId = purchaseOptionId,
        offerId = offerId,
        productType = productType,
    )

private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.encodeToByteArray())
        .joinToString("") { "%02x".format(it) }
}

private fun String?.toApiProductType(): String? = when (this) {
    BillingClient.ProductType.SUBS -> "subscription"
    BillingClient.ProductType.INAPP -> "one_time"
    else -> null
}
