package ai.nuxie.sdk.billing

import ai.nuxie.sdk.events.SystemEventNames
import ai.nuxie.sdk.events.TimeBasedEpochGenerator
import ai.nuxie.sdk.features.FeatureAccess
import ai.nuxie.sdk.features.FeatureAllowance
import ai.nuxie.sdk.features.FeatureInfo
import ai.nuxie.sdk.features.FeatureService
import ai.nuxie.sdk.features.FeatureUsageResult
import ai.nuxie.sdk.network.NuxieApi
import android.app.Activity
import android.util.Log
import com.android.billingclient.api.BillingClient
import java.security.MessageDigest
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

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
                productId = evidence.storeProductIds.firstOrNull()?.takeIf(String::isNotBlank),
                purchaseToken = evidence.purchaseToken,
                basePlanId = evidence.basePlanId,
                purchaseOptionId = evidence.purchaseOptionId,
                offerId = evidence.offerId,
                productType = evidence.productType.toApiProductType(),
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

internal class RestoreReconciliationException(message: String) : IllegalStateException(message)

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
    private val logWarning: (String, Throwable) -> Unit = { message, failure ->
        Log.w("NuxieBilling", message, failure)
    },
    /** Internal test observation only; null in production and never controls behavior. */
    private val purchaseCommitObserver: ((PurchaseCommitObservation) -> Unit)? = null,
) {
    private data class InFlightPurchase(
        val result: CompletableDeferred<PurchaseResult>,
        val owner: String,
        val obfuscatedAccountId: String,
        val priorTokens: Set<String>,
        val product: StoreProduct,
        val nuxieManaged: Boolean,
        val outcomeEventId: String?,
    )

    private sealed interface UsageCoordination {
        data class AwaitSync(val result: CompletableDeferred<Boolean>) : UsageCoordination
        data class AwaitClaim(val released: CompletableDeferred<Unit>) : UsageCoordination
        data object Claimed : UsageCoordination
    }

    private data class RestoreDecision(
        val outcome: RestoreResult,
        val followUps: List<PurchaseEvidence>,
    )

    private data class StagedRevocation(
        val persisted: Boolean,
        val publication: FeatureInfo.Mutation?,
    )

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
                    claimedCheckoutCompletions.remove(completion.checkout)
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
                logWarning(
                    "Dropping external purchase operation ${commit.identity.operationId} " +
                        "after ${commit.captureAttempt} capture attempts.",
                    failure,
                )
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

    private fun clearOutcomeCorrelation(eventId: String?): Boolean {
        if (eventId == null) return true
        var cleared = true
        evidenceStore.loadBindings()
            .filter { it.outcomeEventId == eventId }
            .forEach { binding ->
                if (!evidenceStore.upsertBinding(binding.copy(outcomeEventId = null))) {
                    cleared = false
                }
            }
        return cleared
    }

    private val products = ConcurrentHashMap<StoredProductIdentity, StoreProduct>()
    private val inFlight = ConcurrentHashMap<String, InFlightPurchase>()
    private val processing = Mutex()
    private val projectionRefresh = Mutex()
    private val syncRetryJobs = ConcurrentHashMap<String, Job>()
    private val completionRetryJobs = ConcurrentHashMap<String, Job>()
    private val externalPurchaseCommitRetryJobs =
        ConcurrentHashMap<PurchaseCommitIdentity.External, Job>()
    private val claimedCheckoutCompletions: MutableSet<InFlightPurchase> =
        Collections.newSetFromMap(ConcurrentHashMap())
    private val purchaseCommitOperations =
        ConcurrentHashMap<PurchaseCommitIdentity, CompletableDeferred<Unit>>()
    /** Process-lifetime terminal identities; guarded by [processing]. */
    private val completedPurchaseCommits = mutableMapOf<PurchaseCommitIdentity, Unit>()
    private val locallyRevokedTokens: MutableSet<String> =
        Collections.newSetFromMap(ConcurrentHashMap())
    private val usageCoordinationLock = Any()
    private val syncOperations = mutableMapOf<String, CompletableDeferred<Boolean>>()
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
            kotlinx.coroutines.runBlocking {
                projectionRefresh.withLock { deriveOptimisticProjection() }
            }
            scope.launch { refreshOptimisticProjection() }
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
                val stagedUse = projectionRefresh.withLock {
                    val current = evidenceStore.load()[evidence.purchaseToken]
                    if (current == null || current.revoked || current.permanentlyRejected ||
                        !current.matchesAtomicUsePayload(evidence, distinctId)
                    ) {
                        throw IllegalStateException("Could not persist accepted purchase evidence.")
                    }
                    val accepted = current.copy(
                        synced = true,
                        syncedCustomerId = distinctId,
                        syncedEventEmitted = true,
                        backendSyncedAtMillis = nowMillis(),
                    )
                    val persistedAccepted = upsertEvidenceLocked(
                        accepted,
                        rejectIfTerminal = true,
                    ) ?: throw IllegalStateException("Could not persist accepted purchase evidence.")
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
                evidence.purchaseToken !in locallyRevokedTokens &&
                evidence.backendSyncedAtMillis == null &&
                evidence.purchaseToken.isNotBlank() &&
                evidence.packageName.isNotBlank() &&
                evidence.storeProductIds.firstOrNull()?.isNotBlank() == true &&
                evidence.hasEligiblePurchaseSignature &&
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
        expectedOwnerDistinctId: String? = null,
        outcomeCorrelation: CommerceOutcomeCorrelation? = null,
    ): PurchaseResult {
        val initiatingOwner = distinctId()
        if (expectedOwnerDistinctId != null && initiatingOwner != expectedOwnerDistinctId) {
            return PurchaseResult.Failed(
                IllegalStateException("The customer changed before checkout could start."),
            )
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
        products[product.productIdentity()] = product
        val owner = initiatingOwner
        val accountId = sha256(owner)
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
        val active = when (val queried = billing.queryActive(product.productType)) {
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
            accountId,
            priorTokens,
            product,
            settings.handlingMode == PurchaseHandlingMode.NUXIE_MANAGED,
            outcomeCorrelation?.eventId,
        )
        val registered = synchronized(inFlight) {
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
            billing.launch(
                activity,
                CheckoutRequest(product, accountId, replacement),
            )
        }.getOrElse {
            inFlight.remove(product.storeProductId, pending)
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
            return if (launch.responseCode == BillingClient.BillingResponseCode.USER_CANCELED) {
                commitStandaloneOutcome(
                    product,
                    owner,
                    PurchaseOutcome.Cancelled(PurchaseOutcomeSource.CHECKOUT),
                    outcomeCorrelation?.eventId,
                )
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

    suspend fun restorePurchases(
        expectedOwnerDistinctId: String? = null,
        outcomeCorrelation: CommerceOutcomeCorrelation? = null,
    ): RestoreResult {
        val initiatingOwner = distinctId()
        if (expectedOwnerDistinctId != null && initiatingOwner != expectedOwnerDistinctId) {
            return RestoreResult.Failed(
                IllegalStateException("The customer changed before restore could start."),
            )
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
        val revocationSnapshot = withProcessingDecision { missingRevocationSnapshot() }
        val found = mutableListOf<PlayPurchase>()
        for (type in listOf(BillingClient.ProductType.SUBS, BillingClient.ProductType.INAPP)) {
            when (val result = billing.queryActive(type)) {
                is ActivePurchasesResult.Success -> found += result.purchases
                is ActivePurchasesResult.Failed -> return RestoreResult.Failed(
                    BillingUnavailableException(result.responseCode, result.debugMessage),
                ).also {
                    emitRestoreOutcome(it, initiatingOwner, outcomeCorrelation?.eventId)
                }
            }
        }
        val decision = withProcessingDecision { effects ->
            val revocation = stageMissingOptimisticRevocation(
                    activeTokens = found.mapTo(mutableSetOf()) { it.purchaseToken },
                    revocationSnapshot = revocationSnapshot,
                )
            revocation.publication?.let(effects.publications::add)
            if (!revocation.persisted) {
                RestoreDecision(
                    RestoreResult.Failed(
                        IllegalStateException("Could not durably revoke missing purchase evidence."),
                    ),
                    emptyList(),
                )
            } else if (found.isEmpty()) {
                RestoreDecision(RestoreResult.NoPurchases, emptyList())
            } else {
                RestoreDecision(
                    RestoreResult.Restored,
                    found.mapNotNull {
                        commitPurchaseOutcome(
                            classifyPurchaseObservation(it, PurchaseOutcomeSource.STARTUP_RECOVERY),
                            effects,
                        )
                    },
                )
            }
        }
        decision.followUps.forEach { finishPurchase(it) }
        val outcome = if (expectedOwnerDistinctId != null && decision.outcome == RestoreResult.Restored) {
            strictRestoreOutcome(found, initiatingOwner)
        } else {
            decision.outcome
        }
        emitRestoreOutcome(outcome, initiatingOwner, outcomeCorrelation?.eventId)
        return outcome
    }

    /**
     * Signed Experience restore routes may advance only after every owned Play
     * token is durable, server-verified, exactly catalog-resolved, and locally
     * completed. The public host restore API retains its legacy asynchronous
     * result; authenticated routes opt into this stricter decision by passing
     * their presentation owner.
     */
    private fun strictRestoreOutcome(
        found: List<PlayPurchase>,
        initiatingOwner: String,
    ): RestoreResult {
        val evidenceByToken = evidenceStore.load()
        if (found.any { it.purchaseToken !in evidenceByToken }) {
            return RestoreResult.Failed(
                RestoreReconciliationException(
                    "A restored purchase could not be retained for reconciliation.",
                ),
            )
        }
        val selected = found.mapNotNull { evidenceByToken[it.purchaseToken] }
            .filter { it.ownerDistinctId == initiatingOwner }
        return when {
            selected.isEmpty() -> RestoreResult.NoPurchases
            selected.any { !it.catalogResolved } -> RestoreResult.Failed(
                RestoreReconciliationException(
                    "A restored purchase did not match one exact signed Product.",
                ),
            )
            selected.any { it.permanentlyRejected || it.revoked } -> RestoreResult.Failed(
                RestoreReconciliationException("A restored purchase could not be verified."),
            )
            selected.any { !it.synced } -> RestoreResult.Failed(
                RestoreReconciliationException("Restore is waiting for purchase verification."),
            )
            selected.any { it.needsManagedCompletion() } -> RestoreResult.Failed(
                RestoreReconciliationException("Restore is waiting for Google Play completion."),
            )
            else -> RestoreResult.Restored
        }
    }

    suspend fun onPurchasesUpdated(update: PurchaseUpdate) {
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
        processPurchases(
            update.purchases.orEmpty(),
            PurchaseOutcomeSource.PURCHASES_UPDATED_STREAM,
        )
    }

    /** Billing connect and app foreground share one recovery lane. */
    suspend fun recover() {
        refreshOptimisticProjection()
        val revocationSnapshot = withProcessingDecision { missingRevocationSnapshot() }
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
        val purchaseFollowUps = withProcessingDecision { effects ->
            val prepared = active.mapNotNull {
                commitPurchaseOutcome(
                    classifyPurchaseObservation(it, PurchaseOutcomeSource.STARTUP_RECOVERY),
                    effects,
                )
            }
            val activeTokens = active.mapTo(mutableSetOf()) { it.purchaseToken }
            if (allQueriesSucceeded) {
                val revocation = stageMissingOptimisticRevocation(activeTokens, revocationSnapshot)
                revocation.publication?.let(effects.publications::add)
                if (!revocation.persisted) {
                    throw IllegalStateException("Could not durably revoke missing purchase evidence.")
                }
            }
            prepared
        }
        purchaseFollowUps.forEach { finishPurchase(it) }

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
                    it.permanentlyRejected &&
                        it.purchaseState == StoredPurchaseState.PURCHASED
                }
                .forEach {
                    val revocation = stageEvidenceRevocation(it)
                    revocation.publication?.let(effects.publications::add)
                    if (!revocation.persisted) {
                        throw IllegalStateException("Could not durably revoke rejected purchase evidence.")
                    }
                }
            evidenceRecords
                .filter {
                    !it.permanentlyRejected &&
                        !it.revoked &&
                        it.purchaseToken !in locallyRevokedTokens &&
                        it.purchaseState == StoredPurchaseState.PURCHASED &&
                        !it.completionEmitted &&
                        !it.ownerDistinctId.isNullOrBlank() &&
                        it.hasEligiblePurchaseSignature
                }
                .forEach { effects.stageRetainedPurchaseCompletion(it) }
            evidenceRecords
                .filter {
                    !it.permanentlyRejected &&
                        !it.revoked &&
                        it.purchaseToken !in locallyRevokedTokens &&
                        it.purchaseState == StoredPurchaseState.PURCHASED &&
                        (!it.synced || !it.syncedEventEmitted || it.needsManagedCompletion())
                }
        }
        recoveryFollowUps.forEach { evidence ->
            if (evidence.synced) {
                val current = emitPurchaseSyncedIfNeeded(evidence)
                completeManaged(current)
            } else {
                syncEvidence(evidence)
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
            ownerDistinctId = checkNotNull(evidence.ownerDistinctId),
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
                commitPurchaseOutcome(
                    classifyPurchaseObservation(purchase, observedSource),
                    effects,
                )
            }
        }
        followUps.forEach { finishPurchase(it) }
    }

    private fun callbackOutcomeSource(): PurchaseOutcomeSource =
        if (inFlight.isEmpty()) PurchaseOutcomeSource.PURCHASES_UPDATED_STREAM
        else PurchaseOutcomeSource.CHECKOUT

    /** Classify provenance while the serialized purchase decision is held. */
    private fun classifyPurchaseObservation(
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
                    source = PurchaseOutcomeSource.EXTERNAL_DELEGATE,
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
                    "source" to PurchaseOutcomeSource.EXTERNAL_DELEGATE.wireValue,
                    "test_store" to false,
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
        val pinnedFeatureAllowances = existing?.pinnedFeatureAllowances
            ?: matchingFlight?.product?.takeIf { isCheckoutOutcome }
                ?.featureAllowances?.toStoredAllowances()
            ?: exactBinding?.featureAllowances
            ?: catalogMapping?.featureAllowances
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
            obfuscatedAccountId = purchase.obfuscatedAccountId ?: existing?.obfuscatedAccountId
                ?: sha256(ownerDistinctId ?: syncAttributionDistinctId),
            syncAttributionDistinctId = syncAttributionDistinctId,
            ownerDistinctId = ownerDistinctId,
            context = existing?.context ?: product?.toStoredContext()
                ?: catalogBinding?.context ?: catalogMapping?.context,
            pinnedFeatureAllowances = pinnedFeatureAllowances,
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
            checkoutCompletionEventId = existing?.checkoutCompletionEventId
                ?: checkoutFlight?.outcomeEventId
                ?: exactBinding?.outcomeEventId,
        )
        // D2: evidence is durable before projection, facts, sync, acknowledge, or consume.
        val evidence = upsertEvidence(evidenceCandidate)
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
        suspend fun stageAuthorityReassignment(target: ProcessingEffects) {
            if (ownerDistinctId != null &&
                existing?.ownerDistinctId != ownerDistinctId &&
                (existing?.ownerDistinctId ?: existing?.syncAttributionDistinctId) == currentOwner
            ) {
                val publication = projectionRefresh.withLock {
                    features.stageReassignPurchaseAuthority(
                        currentOwner,
                        evidence.purchaseToken,
                        deriveOptimisticProjection(currentOwner),
                    )
                }
                publication?.let(target.publications::add)
            }
        }
        if (evidence.purchaseState == StoredPurchaseState.PENDING) {
            val pendingOutcome = PurchaseOutcome.Pending(source, purchase)
            recordTerminalPurchaseOutcome(pendingOutcome)
            stageAuthorityReassignment(effects)
            effects.completeCheckoutAfterPublications(
                purchase,
                pendingOutcome,
                checkoutFlight,
            )
            return null
        }
        if (evidence.permanentlyRejected) {
            val failedOutcome = PurchaseOutcome.Failed(
                IllegalStateException("Purchase evidence was permanently rejected."),
                source,
            )
            recordTerminalPurchaseOutcome(failedOutcome)
            stageAuthorityReassignment(effects)
            stageEvidenceRevocation(evidence).publication?.let(effects.publications::add)
            effects.completeCheckoutAfterPublications(
                purchase,
                failedOutcome,
                checkoutFlight,
            )
            return null
        }
        if (evidence.signatureVerificationRequired && !evidence.signatureVerified) {
            val failedOutcome = PurchaseOutcome.Failed(
                SecurityException("Play purchase signature is invalid."),
                source,
            )
            recordTerminalPurchaseOutcome(failedOutcome)
            stageAuthorityReassignment(effects)
            stageEvidenceRevocation(evidence.copy(permanentlyRejected = true)).publication
                ?.let(effects.publications::add)
            effects.completeCheckoutAfterPublications(
                purchase,
                PurchaseOutcome.Failed(
                    SecurityException("Play purchase signature is invalid."),
                    source,
                ),
                checkoutFlight,
            )
            return null
        }
        if (evidence.revoked) {
            stageAuthorityReassignment(effects)
            projectionRefresh.withLock { stageOptimisticProjectionLocked() }
                ?.let(effects.publications::add)
            effects.completeCheckoutAfterPublications(
                purchase,
                PurchaseOutcome.Verified(purchase, source),
                checkoutFlight,
            )
            return null
        }

        val identity = PurchaseCommitIdentity.Evidence(evidence.purchaseToken)
        val needsCompletionCapture = evidence.ownerDistinctId == distinctId() &&
            !evidence.completionEmitted
        val operation = if (needsCompletionCapture) effects.beginPurchaseCommit(identity) else null
        if (needsCompletionCapture && operation == null) return null
        val reservedCheckout = checkoutFlight?.takeIf {
            needsCompletionCapture && claimedCheckoutCompletions.add(it)
        }
        suspend fun finalizeVerifiedOutcome(target: ProcessingEffects) {
            stageAuthorityReassignment(target)
            val projectionPublication = projectionRefresh.withLock {
                val acceptedBody = evidence.acceptedResponseBody
                if (evidence.canProjectTo(distinctId()) && acceptedBody != null) {
                    features.stageReconcilePurchase(
                        evidence.ownerDistinctId!!,
                        acceptedBody,
                        purchase.purchaseToken,
                        deriveOptimisticProjection(),
                    )
                } else {
                    stageOptimisticProjectionLocked()
                }
            }
            projectionPublication?.let(target.publications::add)
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
                ownerDistinctId = evidence.ownerDistinctId!!,
                properties = purchaseCompletionProperties(evidence, source),
                eventId = evidence.checkoutCompletionEventId
                    ?: purchaseCommitEventId(identity),
                afterCapture = ::finalizeVerifiedOutcome,
                onFailure = {
                    reservedCheckout?.let(claimedCheckoutCompletions::remove)
                },
            )
        } else {
            finalizeVerifiedOutcome(effects)
            if (evidence.completionEmitted) {
                recordCompletedPurchaseCommit(identity)
            }
        }
        return evidence.takeUnless { it.permanentlyRejected }
    }

    /** Backend synchronization and Play completion deliberately run outside [processing]. */
    private suspend fun finishPurchase(evidence: PurchaseEvidence) {
        val current = evidenceStore.load()[evidence.purchaseToken] ?: evidence
        if (current.synced) completeManaged(current) else syncEvidence(current)
    }

    private suspend fun syncEvidence(original: PurchaseEvidence): Boolean {
        val current = evidenceStore.load()[original.purchaseToken] ?: original
        if (current.synced) return true
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
            performSyncEvidence(current)
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
        val attempted = projectionRefresh.withLock {
            val current = evidenceStore.load()[original.purchaseToken] ?: original
            upsertEvidenceLocked(
                current.copy(syncAttempts = current.syncAttempts + 1),
                rejectIfTerminal = true,
            )
        } ?: return false
        when (val outcome = runCatching { synchronizer.sync(attempted) }
            .getOrElse { PurchaseSyncOutcome.Rejected(permanent = false) }) {
            is PurchaseSyncOutcome.Rejected -> {
                if (outcome.permanent) {
                    revokeEvidence(attempted.copy(permanentlyRejected = true))
                } else {
                    scheduleSyncRetry(attempted.purchaseToken, attempted.syncAttempts)
                }
                return false
            }
            is PurchaseSyncOutcome.Accepted -> {
                val catalog = outcome.response.catalogProduct
                if (catalog == null && !attempted.catalogResolved) return false
                if (catalog != null && catalog.storeProductId !in attempted.storeProductIds) return false
                val serverProductType = when (catalog?.storeProductType) {
                    null -> attempted.productType
                    "subscription" -> BillingClient.ProductType.SUBS
                    "consumable", "nonConsumable" -> BillingClient.ProductType.INAPP
                    else -> return false
                }
                val stagedAcceptance = projectionRefresh.withLock {
                    val current = evidenceStore.load()[attempted.purchaseToken]
                        ?: return@withLock null
                    if (current.revoked || current.permanentlyRejected) return@withLock null
                    val provenOwner = currentProvenOwner(current)
                    val accepted = current.copy(
                        nuxieProductId = catalog?.productId ?: current.nuxieProductId,
                        basePlanId = catalog?.basePlanId ?: current.basePlanId,
                        purchaseOptionId = catalog?.purchaseOptionId ?: current.purchaseOptionId,
                        offerId = catalog?.offerId ?: current.offerId,
                        productType = serverProductType,
                        consumable = catalog?.storeProductType == "consumable" ||
                            (catalog == null && current.consumable),
                        catalogResolved = current.catalogResolved || catalog != null,
                        ownerDistinctId = provenOwner ?: current.ownerDistinctId,
                        synced = true,
                        syncedCustomerId = outcome.response.customerId,
                        acceptedResponseBody = outcome.response.body,
                        backendSyncedAtMillis = nowMillis(),
                    )
                    val persistedAccepted = upsertEvidenceLocked(
                        accepted,
                        rejectIfTerminal = true,
                    ) ?: return@withLock null
                    val projectionOwner = provenOwner ?: accepted.syncAttributionDistinctId
                    val publication = if (projectionOwner == distinctId()) {
                        features.stageReconcilePurchase(
                            projectionOwner,
                            outcome.response.body,
                            accepted.purchaseToken,
                            deriveOptimisticProjection(),
                        )
                    } else {
                        null
                    }
                    persistedAccepted to publication
                } ?: return false
                features.publishStaged(stagedAcceptance.second)
                val accepted = stagedAcceptance.first
                completeManaged(emitPurchaseSyncedIfNeeded(accepted))
                return true
            }
        }
    }

    private fun currentProvenOwner(evidence: PurchaseEvidence): String? {
        val boundOwner = evidence.obfuscatedAccountId?.let { accountId ->
            evidenceStore.loadBindings().firstOrNull { binding ->
                binding.obfuscatedAccountId == accountId &&
                    evidence.matchesProductIdentity(binding.productIdentity)
            }?.distinctId
        }
        return boundOwner ?: evidenceStore.load()[evidence.purchaseToken]?.ownerDistinctId
    }

    private suspend fun emitPurchaseSyncedIfNeeded(evidence: PurchaseEvidence): PurchaseEvidence =
        projectionRefresh.withLock {
            val current = evidenceStore.load()[evidence.purchaseToken] ?: evidence
            if (current.revoked || current.permanentlyRejected || current.syncedEventEmitted ||
                current.syncAttributionDistinctId != distinctId()
            ) {
                return@withLock current
            }
            val properties = buildMap<String, Any?> {
                put("transaction_id", current.purchaseToken)
                put("original_transaction_id", current.purchaseToken)
                put("product_id", current.storeProductIds.firstOrNull().orEmpty())
                put("customer_id", current.syncedCustomerId.orEmpty())
                current.context?.experienceId?.let { put("experience_id", it) }
                current.context?.experienceVersion?.let { put("experience_version", it) }
            }
            emit(SystemEventNames.PURCHASE_SYNCED, properties)
            upsertEvidenceLocked(
                current.copy(syncedEventEmitted = true),
                rejectIfTerminal = true,
            ) ?: current
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
                if (!current.needsManagedCompletion() || current.revoked || current.permanentlyRejected) {
                    return@withLock null
                }
                upsertEvidenceLocked(
                    current.copy(completionAttempts = current.completionAttempts + 1),
                    rejectIfTerminal = true,
                )
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
                    upsertEvidenceLocked(
                        if (attempted.consumable) current.copy(consumed = true)
                        else current.copy(acknowledged = true),
                        rejectIfTerminal = true,
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
        when (val active = billing.queryActive(BillingClient.ProductType.INAPP)) {
            is ActivePurchasesResult.Success -> active.purchases.none {
                it.purchaseToken == purchaseToken
            }
            is ActivePurchasesResult.Failed -> false
        }

    private fun PurchaseEvidence.needsManagedCompletion(): Boolean =
        nuxieManaged && catalogResolved && if (consumable) !consumed else !acknowledged

    private fun PurchaseEvidence.canProjectTo(currentDistinctId: String): Boolean =
        ownerDistinctId == currentDistinctId &&
            hasEligiblePurchaseSignature

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

    private suspend fun upsertEvidence(
        candidate: PurchaseEvidence,
        rejectIfTerminal: Boolean = false,
    ): PurchaseEvidence? = projectionRefresh.withLock {
        upsertEvidenceLocked(candidate, rejectIfTerminal)
    }

    /** Merge monotonic evidence facts; called only while [projectionRefresh] is held. */
    private fun upsertEvidenceLocked(
        candidate: PurchaseEvidence,
        rejectIfTerminal: Boolean = false,
    ): PurchaseEvidence? {
        val current = evidenceStore.load()[candidate.purchaseToken]
        if (rejectIfTerminal && (candidate.purchaseToken in locallyRevokedTokens ||
                current?.revoked == true || current?.permanentlyRejected == true)
        ) {
            return null
        }
        val merged = candidate.copy(
            purchaseState = if (candidate.purchaseState == StoredPurchaseState.PURCHASED ||
                current?.purchaseState == StoredPurchaseState.PURCHASED
            ) {
                StoredPurchaseState.PURCHASED
            } else {
                StoredPurchaseState.PENDING
            },
            acknowledged = candidate.acknowledged || current?.acknowledged == true,
            consumed = candidate.consumed || current?.consumed == true,
            synced = candidate.synced || current?.synced == true,
            revoked = candidate.revoked || current?.revoked == true ||
                candidate.purchaseToken in locallyRevokedTokens,
            permanentlyRejected = candidate.permanentlyRejected || current?.permanentlyRejected == true,
            syncAttempts = maxOf(candidate.syncAttempts, current?.syncAttempts ?: 0),
            completionAttempts = maxOf(candidate.completionAttempts, current?.completionAttempts ?: 0),
            firstSeenMillis = minOf(candidate.firstSeenMillis, current?.firstSeenMillis ?: Long.MAX_VALUE),
            catalogResolved = candidate.catalogResolved || current?.catalogResolved == true,
            completionEmitted = candidate.completionEmitted || current?.completionEmitted == true,
            syncedEventEmitted = candidate.syncedEventEmitted || current?.syncedEventEmitted == true,
            syncedCustomerId = candidate.syncedCustomerId ?: current?.syncedCustomerId,
            acceptedResponseBody = candidate.acceptedResponseBody ?: current?.acceptedResponseBody,
            nuxieManaged = candidate.nuxieManaged || current?.nuxieManaged == true,
            signatureVerificationRequired = candidate.signatureVerificationRequired ||
                current?.signatureVerificationRequired == true,
            signatureVerified = candidate.signatureVerified || current?.signatureVerified == true,
            backendSyncedAtMillis = candidate.backendSyncedAtMillis ?: current?.backendSyncedAtMillis,
            checkoutCompletionEventId = current?.checkoutCompletionEventId
                ?: candidate.checkoutCompletionEventId,
            pinnedFeatureAllowances = current?.pinnedFeatureAllowances
                ?: candidate.pinnedFeatureAllowances,
        )
        return merged.takeIf(evidenceStore::upsert)
    }

    private suspend fun stageEvidenceRevocation(evidence: PurchaseEvidence): StagedRevocation =
        withContext(NonCancellable) {
            projectionRefresh.withLock {
                locallyRevokedTokens += evidence.purchaseToken
                val current = evidenceStore.load()[evidence.purchaseToken] ?: evidence
                val persisted = evidenceStore.upsert(
                    current.copy(
                        revoked = true,
                        permanentlyRejected = current.permanentlyRejected || evidence.permanentlyRejected,
                    ),
                )
                StagedRevocation(persisted, stageOptimisticProjectionLocked())
            }
        }

    private suspend fun revokeEvidence(evidence: PurchaseEvidence): Boolean {
        val staged = stageEvidenceRevocation(evidence)
        features.publishStaged(staged.publication)
        return staged.persisted
    }

    private suspend fun missingRevocationSnapshot(): Map<String, PurchaseEvidence> =
        projectionRefresh.withLock {
            val currentDistinctId = distinctId()
            evidenceStore.load().values
                .filter { it.isMissingRevocationCandidate(currentDistinctId) }
                .associateBy { it.purchaseToken }
        }

    private fun PurchaseEvidence.isMissingRevocationCandidate(currentDistinctId: String): Boolean =
        !synced && !revoked && !permanentlyRejected &&
            ownerDistinctId == currentDistinctId &&
            purchaseState == StoredPurchaseState.PURCHASED &&
            (nuxieManaged || !consumable)

    private suspend fun stageMissingOptimisticRevocation(
        activeTokens: Set<String>,
        revocationSnapshot: Map<String, PurchaseEvidence>,
    ): StagedRevocation =
        withContext(NonCancellable) {
            projectionRefresh.withLock {
                val currentDistinctId = distinctId()
                val revalidated = evidenceStore.load().values
                    .filter { evidence ->
                        evidence.isMissingRevocationCandidate(currentDistinctId) &&
                            evidence.purchaseToken !in activeTokens &&
                            revocationSnapshot[evidence.purchaseToken] == evidence
                    }
                val missing = synchronized(usageCoordinationLock) {
                    val protectedTokens = purchaseUsageClaims + syncOperations.keys
                    revalidated.filter { it.purchaseToken !in protectedTokens }.also {
                        locallyRevokedTokens += it.map(PurchaseEvidence::purchaseToken)
                    }
                }
                val persisted = missing.map { evidenceStore.upsert(it.copy(revoked = true)) }.all { it }
                StagedRevocation(persisted, stageOptimisticProjectionLocked())
            }
        }

    private fun scheduleSyncRetry(token: String, attempt: Int) {
        if (syncRetryJobs[token]?.isActive == true) return
        syncRetryJobs[token] = scope.launch {
            delay(retryDelay(attempt))
            syncRetryJobs.remove(token)
            evidenceStore.load()[token]
                ?.takeIf {
                    token !in locallyRevokedTokens &&
                        !it.permanentlyRejected && !it.revoked && !it.synced
                }
                ?.let { syncEvidence(it) }
        }
    }

    private fun scheduleCompletionRetry(token: String, attempt: Int) {
        if (completionRetryJobs[token]?.isActive == true) return
        completionRetryJobs[token] = scope.launch {
            delay(retryDelay(attempt))
            completionRetryJobs.remove(token)
            evidenceStore.load()[token]
                ?.takeIf {
                    token !in locallyRevokedTokens &&
                        !it.permanentlyRejected && !it.revoked &&
                        it.synced && it.needsManagedCompletion()
                }
                ?.let { completeManaged(emitPurchaseSyncedIfNeeded(it)) }
        }
    }

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
            "source" to source.wireValue,
            "test_store" to false,
            "transaction_id" to evidence.purchaseToken,
            "price" to evidence.context?.price?.toDouble(),
            "display_price" to evidence.context?.displayPrice,
        ).filterValues { it != null }

    private fun purchaseCompletionProperties(
        product: StoreProduct,
        source: PurchaseOutcomeSource,
    ): Map<String, Any?> {
        val price = product.storePrice()
        return linkedMapOf<String, Any?>(
            "product_id" to product.productId,
            "placement_id" to product.placementId,
            "store_product_id" to product.storeProductId,
            "experience_id" to product.purchaseContext?.experienceId,
            "source" to source.wireValue,
            "test_store" to false,
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
        emitPurchaseOutcome(
            checkout.product,
            outcome,
            checkout.owner,
            checkout.outcomeEventId,
        )
        checkout.result.complete(outcome.toPurchaseResult())
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
            is PurchaseOutcome.Pending -> emit(SystemEventNames.PURCHASE_PENDING, properties)
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

    private fun PurchaseResult.toPurchaseOutcome(source: PurchaseOutcomeSource): PurchaseOutcome = when (this) {
        PurchaseResult.Purchased -> error("A purchased delegate result requires an external declaration.")
        PurchaseResult.Cancelled -> PurchaseOutcome.Cancelled(source)
        PurchaseResult.Pending -> PurchaseOutcome.Pending(source)
        is PurchaseResult.Failed -> PurchaseOutcome.Failed(cause, source)
    }

    private suspend fun emitRestoreOutcome(
        result: RestoreResult,
        initiatingOwner: String,
        outcomeEventId: String? = null,
    ) {
        if (distinctId() != initiatingOwner) return
        val (name, properties) = when (result) {
            RestoreResult.Restored -> SystemEventNames.RESTORE_COMPLETED to emptyMap()
            RestoreResult.NoPurchases -> SystemEventNames.RESTORE_NO_PURCHASES to emptyMap()
            is RestoreResult.Failed -> SystemEventNames.RESTORE_FAILED to mapOf(
                "error" to (result.cause.message ?: result.cause.javaClass.simpleName),
            )
        }
        if (outcomeEventId == null) {
            emit(name, properties)
        } else {
            check(capturePurchaseEvent(name, properties, outcomeEventId, initiatingOwner)) {
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

    internal fun rememberProduct(product: StoreProduct): Boolean =
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

    private fun StoreProduct.productIdentity() = StoredProductIdentity(
        storeProductId,
        productId,
        basePlanId,
        purchaseOptionId,
        offerId,
        productType,
    )

    private suspend fun refreshOptimisticProjection() {
        val publication = projectionRefresh.withLock { stageOptimisticProjectionLocked() }
        features.publishStaged(publication)
    }

    /** Called only while [projectionRefresh] is held. */
    private fun stageOptimisticProjectionLocked(): FeatureInfo.Mutation? {
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
    private fun deriveOptimisticProjection(currentDistinctId: String = distinctId()):
        Map<String, OptimisticFeatureOverlay>? {
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
            evidence = evidence.filter {
                it.purchaseToken !in locallyRevokedTokens
            },
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
