package ai.nuxie.sdk.billing

import ai.nuxie.sdk.events.SystemEventNames
import ai.nuxie.sdk.events.TimeBasedEpochGenerator
import ai.nuxie.sdk.features.FeatureAccess
import ai.nuxie.sdk.features.FeatureAllowance
import ai.nuxie.sdk.features.FeatureInfo
import ai.nuxie.sdk.features.FeatureService
import ai.nuxie.sdk.features.FeatureUsageResult
import ai.nuxie.sdk.identity.IdentityProvider
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
    private val identity: IdentityProvider? = null,
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
        /** This checkout's own id, made when it began; its binding carries it. */
        val checkoutId: String,
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
        val entry: EntryAnswer,
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

    private class ProcessingEffects {
        val purchaseCommits = mutableListOf<PendingPurchaseCommit>()
        val ownedPurchaseCommits = mutableListOf<OwnedPurchaseCommit>()
        val purchaseCommitWaits = mutableListOf<CompletableDeferred<Unit>>()
        val publications = mutableListOf<FeatureInfo.Mutation>()
        val purchaseCompletions = mutableListOf<PendingPurchaseCompletion>()
        val directCheckoutCompletions = mutableListOf<PendingDirectCheckoutCompletion>()
        val outcomeEmissions = mutableListOf<PendingOutcomeEmission>()
        /** While the outbox is off: what this decision's deliveries would have saved, by token. */
        val unsavedEntries = linkedMapOf<String, PurchaseEvidence>()
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

    /**
     * Record that the purchase's completion is captured. While the outbox is
     * off there is nothing saved to mark: the entry and its checkout context
     * live only in this session, so the next launch's recovery has no context
     * to capture the completion again.
     */
    private suspend fun markCompletionCaptured(purchaseToken: String): Boolean =
        projectionRefresh.withLock {
            if (outboxOff) return@withLock true
            val current = readEntries()[purchaseToken] ?: return@withLock false
            val marked = current.completionEmitted ||
                saveEntry(current.copy(completionEmitted = true)) || outboxOff
            // The checkout's completion is delivered: the binding this purchase matched
            // stands for no other purchase. It is retired by name, never another
            // checkout's binding for the same customer and product.
            current.checkoutId?.let { retireBinding(it) }
            marked && clearOutcomeCorrelation(current.checkoutCompletionEventId)
        }

    private suspend fun clearOutcomeCorrelation(eventId: String?): Boolean {
        if (eventId == null || outboxOff) return true
        var cleared = true
        readBindings()
            .filter { it.outcomeEventId == eventId }
            .forEach { binding ->
                if (!saveBinding(binding.copy(outcomeEventId = null, context = binding.context?.copy(journeyId = null)))) {
                    cleared = outboxOff
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
    /**
     * External declarations commit once per callback operation; guarded by
     * [processing]. A Play purchase has no such state beside its entry: the
     * entry's own flags decide what a later delivery still does.
     */
    private val completedExternalCommits = mutableSetOf<PurchaseCommitIdentity.External>()
    /** For the commit observer only: each purchase's completed commit is reported once. Guarded by [processing]. */
    private val reportedEvidenceCommits = mutableSetOf<PurchaseCommitIdentity.Evidence>()
    /**
     * Set for the rest of the session once the outbox cannot save: an evidence
     * write failed (a full disk, say) or the store cannot be read. While it is
     * off nothing is saved and nothing is held in memory instead: no entry
     * unlocks early (access comes from the profile alone), no timer sends or
     * completes, and each purchase Play delivers is sent once, its answer only
     * refreshing the profile. Such a purchase is never acknowledged or
     * consumed, so the next launch's recovery finds it, saves its entry, sends
     * it and completes it.
     */
    @Volatile private var outboxOff = false

    /**
     * The outbox could not save: it is off for the rest of the session. Its
     * timers stop (except the caller's own, which ends by itself), and the
     * early unlock is withdrawn.
     */
    private suspend fun switchOutboxOff() {
        if (outboxOff) return
        outboxOff = true
        Log.w("NuxieBilling", "The purchase outbox cannot save; it is off until the next launch.")
        val caller = currentCoroutineContext()[Job]
        for (jobs in listOf(sendRetryJobs, completionRetryJobs)) {
            jobs.values.filter { it !== caller }.forEach { it.cancel() }
            jobs.clear()
        }
        scope.launch { refreshOptimisticProjection() }
    }

    /**
     * Every store read goes through these. A read that fails is never taken for
     * an empty store: after launch it turns the outbox off for the session, and
     * the read gives nothing to act on. Each waits for the launch's store open
     * first, so a corrupt file is set aside before it is read. Before the core
     * starts, the launch open decides.
     */
    private suspend fun readEntries(): Map<String, PurchaseEvidence> =
        readStore(emptyMap()) { evidenceStore.loadOrNull() }

    private suspend fun readBindings(): List<StoredPurchaseBinding> =
        readStore(emptyList()) { evidenceStore.loadBindingsOrNull() }

    private suspend fun readMappings(): List<StoredProductMapping> =
        readStore(emptyList()) { evidenceStore.loadProductMappingsOrNull() }

    private suspend fun <T> readStore(nothing: T, read: suspend () -> T?): T {
        val open = launchOpen
        open?.join()
        return read() ?: nothing.also { if (open != null) switchOutboxOff() }
    }

    /**
     * Every evidence write goes through these: one that fails turns the outbox
     * off. Each waits for the launch's store open first.
     */
    private suspend fun saveEntry(evidence: PurchaseEvidence): Boolean {
        launchOpen?.join()
        return !outboxOff && (evidenceStore.upsert(evidence) || false.also { switchOutboxOff() })
    }

    private suspend fun saveBinding(binding: StoredPurchaseBinding): Boolean {
        launchOpen?.join()
        return !outboxOff && (evidenceStore.upsertBinding(binding) || false.also { switchOutboxOff() })
    }

    /** End checkout [checkoutId]'s binding by name; a failed write turns the outbox off. */
    private suspend fun retireBinding(checkoutId: String): Boolean {
        launchOpen?.join()
        return !outboxOff && (evidenceStore.retireCheckoutBinding(checkoutId) || false.also { switchOutboxOff() })
    }

    private suspend fun saveMapping(mapping: StoredProductMapping, catalogChange: Boolean = true): Boolean {
        launchOpen?.join()
        return !outboxOff &&
            (evidenceStore.upsertProductMapping(mapping, catalogChange) || false.also { switchOutboxOff() })
    }
    private val usageCoordinationLock = Any()
    /** The one send in flight per purchase token; guarded by [usageCoordinationLock]. */
    private val sending = mutableMapOf<String, CompletableDeferred<SendAnswer>>()
    private val purchaseUsageClaims = mutableSetOf<String>()
    private val purchaseUsageWaiters = mutableMapOf<String, MutableList<CompletableDeferred<Unit>>>()
    private val managedCompletionClaims = mutableSetOf<String>()
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
                // A changed catalog runs the Play recovery query and sends every
                // unanswered entry, as launch, identify and reset do; before the
                // core has started, its own launch recovery does that.
                if (started) recover()
            }
        }
        initialProjectionRefresh = scope.launch { refreshOptimisticProjection() }
    }

    @Volatile private var started = false

    /**
     * The core's post-construction start: only now does outbox work begin
     * (stored retries, recovery, sends), so an answer never reaches a part of
     * the core that is not built yet.
     */
    fun start() {
        if (started) return
        started = true
        // A corrupt store file is moved aside and an empty one takes its place. A
        // file that cannot be read right now cannot save either: the outbox is off.
        // It starts at once, so a store that answers without waiting is open before
        // start() returns; a file store goes on on its IO dispatcher.
        val open = scope.launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
            if (!evidenceStore.openAtLaunch()) switchOutboxOff()
        }
        launchOpen = open
        scope.launch {
            open.join()
            scheduleStoredRetries()
        }
    }

    /** The launch's store open: no write goes before it, so none lands on a file it moves aside. */
    @Volatile private var launchOpen: Job? = null

    /**
     * At launch, every unanswered entry gets its timer from its saved due time.
     * An overdue one, or one with no saved due time (saved but never sent, say,
     * when the process died before its first send), is due now. The outbox
     * sends without Play, so this holds even when Billing cannot connect.
     */
    private suspend fun scheduleStoredRetries() {
        // The Test Store never sends native evidence, and an outbox that is off sets no timer.
        if (testStore != null || outboxOff) return
        readEntries().values.forEach { entry ->
            val send = entry.pendingSend ?: return@forEach
            scheduleSendRetry(entry.purchaseToken, send, entry.retryDueAtMillis ?: nowMillis())
        }
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
                // The command is built from the entry as it is now that it holds the
                // purchase, never from the read before it waited for the purchase.
                val candidate = claimedCandidates.single()
                val eventId = purchaseUsageEventId(candidate, featureId, amount, entityId)
                val claimed = admitFeatureUse(candidate, distinctId, eventId) ?: return null
                val featureScope = features.captureAuthoritativeUseScope(distinctId)
                val response = usageApi.useFeatureWithPurchase(
                    NuxieApi.PurchaseBackedFeatureUseReport(
                        customerId = distinctId,
                        featureId = featureId,
                        requiredBalance = amount,
                        eventData = NuxieApi.FeatureUseEventData(amount, metadata),
                        entityId = entityId,
                        customerKind = claimed.featureUseCustomerKinds?.get(eventId),
                        purchase = NuxieApi.PlayPurchaseUseReport(
                            packageName = claimed.packageName,
                            productId = claimed.storeProductIds.first(),
                            purchaseToken = claimed.purchaseToken,
                            basePlanId = claimed.basePlanId,
                            offerId = claimed.offerId,
                            obfuscatedAccountId = claimed.obfuscatedAccountId,
                            eventId = eventId,
                        ),
                    ),
                )
                if (response.customerId != distinctId) {
                    throw IllegalStateException("Atomic Feature-use response customer did not match the purchase owner.")
                }
                val send = checkNotNull(claimed.pendingSend)
                // The command's answer passes the same admission check as every
                // answer: only one for the admission (and payload) its entry still
                // holds answers the entry, and only once that answer is saved is it
                // captured as purchase synced. The server consumed the Feature, so
                // its result reaches the caller either way; a superseded answer only
                // refreshes the profile.
                val stagedUse = projectionRefresh.withLock {
                    val entry = answerEntryLocked(
                        claimed.purchaseToken,
                        send,
                        PurchaseAnswer.AcceptedFeatureUse(distinctId),
                    ) { it.matchesAtomicUsePayload(claimed) }
                    val currentDistinctId = this.distinctId()
                    when {
                        currentDistinctId != distinctId -> StagedAtomicUse(
                            entry = entry,
                            access = null,
                            publication = features.stageOptimisticPurchaseProjection(
                                currentDistinctId,
                                deriveOptimisticProjection(currentDistinctId),
                            ),
                        )
                        // Only an answer that answered its entry changes Feature authority.
                        // A superseded one (a newer admission holds the entry) or one the
                        // outbox could not save changes no authority, cache, entry or
                        // hold: it only asks for the profile again. The server consumed
                        // the Feature, so the caller still gets the result.
                        entry !is EntryAnswer.Answered -> StagedAtomicUse(
                            entry = entry,
                            access = features.authoritativeUseResultAccess(
                                result = response,
                                requestedFeatureId = featureId,
                                distinctId = distinctId,
                                expectedScope = featureScope,
                            ),
                            publication = null,
                        )
                        else -> {
                            val staged = features.stageAuthoritativeUse(
                                result = response,
                                requestedFeatureId = featureId,
                                distinctId = distinctId,
                                entityId = entityId,
                                expectedScope = featureScope,
                                reconciledOptimisticProjection = deriveOptimisticProjection(),
                                reconcileOptimisticProjection = true,
                            )
                            StagedAtomicUse(entry, staged.access, staged.publication)
                        }
                    }
                }
                features.publishStaged(stagedUse.publication)
                // The customer changed during the command: its result is not theirs.
                val access = stagedUse.access ?: throw kotlinx.coroutines.CancellationException()
                // Saved: the answer is reported as purchase synced, still holding the
                // purchase so no other pass reports it too.
                (stagedUse.entry as? EntryAnswer.Answered)?.let {
                    recordAnsweredAdmission(send.admissionId, SyncResult.ACCEPTED)
                    reportCommandPurchaseSynced(it.evidence, send)
                }
                releasePurchaseUsageClaim(evidence.purchaseToken)
                // Answered: the purchase completes with Play. Not saved, or a newer
                // admission: the profile decides, and the entry is sent again.
                (stagedUse.entry as? EntryAnswer.Answered)?.let { completeManaged(it.evidence) }
                // As after every answer, the requester's profile is fetched again: it
                // holds the product's other Features once the early unlock ends.
                refreshFeatureAuthority()
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

    /** Freeze the consume's kind before sending, without readmitting `/purchase`. */
    private suspend fun admitFeatureUse(
        evidence: PurchaseEvidence,
        distinctId: String,
        operationId: String,
    ): PurchaseEvidence? {
        var admitted: PurchaseEvidence? = null
        suspend fun admit() {
            projectionRefresh.withLock {
                if (outboxOff) return@withLock
                val current = readEntries()[evidence.purchaseToken] ?: return@withLock
                if (current.pendingSend?.admissionId != evidence.pendingSend?.admissionId ||
                    !current.matchesAtomicUsePayload(evidence)
                ) return@withLock
                val kinds = current.featureUseCustomerKinds
                // A missing map is legacy evidence: the reconstructed operation
                // may already have been sent undeclared. Never backfill its kind.
                if (kinds == null || kinds.containsKey(operationId)) {
                    admitted = current
                } else {
                    val updated = current.copy(featureUseCustomerKinds =
                        kinds + (operationId to customerKind(distinctId)))
                    // A failed durable admission turns the outbox off. No evidence
                    // was sent, so the caller can use the ordinary command path.
                    if (saveEntry(updated)) admitted = updated
                }
            }
        }
        val provider = identity
        if (provider != null) {
            val identityScope = provider.captureScope()
            if (identityScope.distinctId != distinctId ||
                provider.withCurrentScopeSuspending(identityScope) { admit(); true } != true
            ) throw CancellationException()
        } else {
            if (this.distinctId() != distinctId) throw CancellationException()
            admit()
        }
        return admitted
    }

    /**
     * A purchase-backed Feature command's saved answer is reported as purchase
     * synced, under the purchase's stable sync event id. A capture that fails
     * leaves the entry unreported, and recovery reports it later.
     */
    private suspend fun reportCommandPurchaseSynced(saved: PurchaseEvidence, send: PurchaseSend) {
        val customer = saved.syncedCustomerId ?: return
        val properties = buildMap<String, Any?> {
            put("transaction_id", saved.purchaseToken)
            put("original_transaction_id", saved.purchaseToken)
            put("product_id", saved.storeProductIds.first())
            put("customer_id", customer)
            saved.context?.experienceId?.let { put("experience_id", it) }
            saved.context?.experienceVersion?.let { put("experience_version_id", it) }
            saved.context?.journeyId?.let { put("journey_id", it) }
        }
        val captured = try {
            capturePurchaseSynced(
                SystemEventNames.PURCHASE_SYNCED,
                properties,
                purchaseSyncedEventId(customer, saved.purchaseToken, send.admissionId),
                customer,
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            Log.w("NuxieBilling", "Could not capture the purchase synchronization event.", failure)
            false
        }
        if (!captured) return
        projectionRefresh.withLock {
            val current = readEntries()[saved.purchaseToken] ?: return@withLock
            if (current.pendingSend == null && current.syncedCustomerId == customer && !current.syncedEventEmitted) {
                saveEntry(current.copy(syncedEventEmitted = true))
            }
        }
    }

    private suspend fun eligibleEvidence(distinctId: String, featureId: String): List<PurchaseEvidence> {
        val descriptors = readMappings()
        val bindings = readBindings()
        // While the outbox is off no entry unlocks early: the profile alone decides.
        if (outboxOff) return emptyList()
        return readEntries().values.filter { evidence ->
            evidence.authorityScope == purchaseStorageScope &&
                evidence.isBuyersOwnUnsyncedPurchase(distinctId, bindings, descriptors) &&
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

    /** One `$purchase_synced` per purchase, customer and accepted admission. */
    private fun purchaseSyncedEventId(distinctId: String, purchaseToken: String, admissionId: String): String =
        stableEventId(
            "purchase-synced:",
            listOf(
                purchaseStorageScope,
                SystemEventNames.PURCHASE_SYNCED,
                distinctId,
                purchaseToken,
                admissionId,
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
                // before the purchase is announced, as a Play purchase's 200 does.
                // They hold until a profile requested after this answer lands, and
                // the customer's profile is fetched again, as after every answer.
                // A Test Store purchase never has an outbox entry.
                if (initiatingOwner == distinctId()) {
                    features.publishStaged(
                        projectionRefresh.withLock {
                            features.stagePurchaseAnswer(initiatingOwner, access, deriveOptimisticProjection(initiatingOwner))
                        },
                    )
                    refreshFeatureAuthority()
                }
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
        // The checkout's own product is no catalog change: it starts no recovery. A
        // mapping that cannot be saved turns the outbox off, and the checkout goes on
        // with the product it holds.
        saveMapping(product.toMapping(), catalogChange = false)
        // This checkout's own id: its binding is retired only by name.
        val checkoutId = java.util.UUID.randomUUID().toString()
        val outcome: PurchaseResult = run checkout@{
            val queried = try {
                billing.queryActive(product.productType)
            } catch (failure: Throwable) {
                ensureCheckoutIntakeOpen()
                throw failure
            }
            ensureCheckoutIntakeOpen()
            val active = when (queried) {
                is ActivePurchasesResult.Failed -> return@checkout failed(
                    product,
                    BillingUnavailableException(queried.responseCode, queried.debugMessage),
                    owner,
                    PurchaseOutcomeSource.CHECKOUT,
                    outcomeCorrelation?.eventId,
                )
                is ActivePurchasesResult.Success -> queried.purchases
            }
            if (product.productType == BillingClient.ProductType.SUBS && replacement == null && active.isNotEmpty()) {
                return@checkout failed(
                    product,
                    SubscriptionReplacementRequiredException(),
                    owner,
                    PurchaseOutcomeSource.CHECKOUT,
                    outcomeCorrelation?.eventId,
                )
            }
            val priorTokens = active.mapTo(mutableSetOf()) { it.purchaseToken }
            // The checkout's binding names what Play already owned when it began, so
            // none of that is ever taken for the checkout's own purchase. Without it
            // (the outbox is off) the checkout in flight is the only context.
            saveBinding(product.toBinding(accountId, owner, outcomeCorrelation?.eventId, priorTokens, checkoutId))

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
                checkoutId,
            )
            val registered = synchronized(inFlight) {
                ensureCheckoutIntakeOpen()
                if (inFlight.isNotEmpty()) false else {
                    inFlight[product.storeProductId] = pending
                    true
                }
            }
            if (!registered) {
                return@checkout failed(
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
                return@checkout failed(
                    product,
                    it,
                    owner,
                    PurchaseOutcomeSource.CHECKOUT,
                    outcomeCorrelation?.eventId,
                )
            }
            if (launch.responseCode != BillingClient.BillingResponseCode.OK) {
                inFlight.remove(product.storeProductId, pending)
                val reconciled = if (launch.responseCode == BillingClient.BillingResponseCode.ITEM_ALREADY_OWNED) {
                    reconcileAlreadyOwned(product, owner, initiatingKind, outcomeCorrelation?.eventId, checkoutId)
                } else {
                    null
                }
                return@checkout if (launch.responseCode == BillingClient.BillingResponseCode.USER_CANCELED) {
                    commitStandaloneOutcome(
                        product,
                        owner,
                        PurchaseOutcome.Cancelled(PurchaseOutcomeSource.CHECKOUT),
                        outcomeCorrelation?.eventId,
                    )
                } else if (reconciled != null) {
                    reconciled
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
            result.await()
        }
        // The checkout has its outcome, so its binding no longer stands for a
        // purchase to come; a purchase still pending approval keeps it.
        if (outcome !is PurchaseResult.Pending) endCheckoutBinding(checkoutId)
        return outcome
    }

    /**
     * Checkout [checkoutId] ended: its binding keeps only catalog facts. Only
     * that checkout's binding is ended, never another checkout's for the same
     * customer and product, and the check and the write are one step.
     */
    private suspend fun endCheckoutBinding(checkoutId: String) {
        retireBinding(checkoutId)
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
    /**
     * Play answered a checkout of [product] with ITEM_ALREADY_OWNED, from the
     * launch result or a purchase update. Ask Play which purchase owns it and
     * send that as the customer's purchase. Null when Play reports none.
     */
    private suspend fun reconcileAlreadyOwned(
        product: StoreProduct,
        owner: String,
        ownerKind: CustomerKind,
        outcomeEventId: String?,
        checkoutId: String,
    ): PurchaseResult? {
        val owned = (runCatching { billing.queryActive(product.productType) }.getOrNull() as? ActivePurchasesResult.Success)
            ?.purchases?.ownedPurchaseOf(product)
            ?: return null
        return alreadyOwned(product, owned, owner, ownerKind, outcomeEventId, checkoutId)
    }

    private suspend fun alreadyOwned(
        product: StoreProduct,
        owned: PlayPurchase,
        owner: String,
        ownerKind: CustomerKind,
        outcomeEventId: String?,
        checkoutId: String,
    ): PurchaseResult {
        // As on iOS: the failed checkout's binding goes first, so reconciling the
        // owned purchase carries no checkout context and captures no completion; the
        // checkout's only outcome is the already-owned failure below.
        endCheckoutBinding(checkoutId)
        clearOutcomeCorrelation(outcomeEventId)
        val unsaved = withProcessingDecision { effects ->
            commitPurchaseOutcome(
                classifyPurchaseObservation(owned, PurchaseOutcomeSource.STARTUP_RECOVERY),
                effects,
                admitsSend = false,
                correlatesCheckout = false,
            )
            effects.unsavedEntries[owned.purchaseToken]
        }
        val entry = unsaved ?: readEntries()[owned.purchaseToken]
        if (distinctId() == owner && entry != null) {
            claim(entry, PurchaseSend.admit(PurchaseSyncReason.PURCHASE, owner, ownerKind))
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
        for (type in listOf(BillingClient.ProductType.SUBS, BillingClient.ProductType.INAPP)) {
            val query = try {
                billing.queryActive(type)
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
        // Null means Play owns purchases this Restore must ask the server about.
        val (settled, unsaved) = withProcessingDecision { effects ->
            when {
                found.isEmpty() -> RestoreResult.NoPurchases
                else -> {
                    // Everything this Restore finds is admitted as its claim below.
                    found.distinctBy { it.purchaseToken }.forEach { purchase ->
                        commitPurchaseOutcome(
                            classifyPurchaseObservation(purchase, PurchaseOutcomeSource.STARTUP_RECOVERY),
                            effects,
                            admitsSend = false,
                        )
                    }
                    null
                }
            } to effects.unsavedEntries.toMap()
        }
        // Restore queues a send for every purchase Play owns, whoever this device
        // last saw holding it, even after an earlier sync was refused or a
        // checkout receipt was accepted: only the server can move a purchase to
        // the restoring customer, and only its answer grants access.
        // While the outbox is off, what this Restore found lives only in its decision.
        val retained = if (settled != null) emptyMap() else readEntries() + unsaved
        val bindings = readBindings()
        val mappings = readMappings()
        val restorable = if (settled != null) emptyList() else {
            found.distinctBy { it.purchaseToken }.mapNotNull { purchase ->
                retained[purchase.purchaseToken]?.takeIf {
                    purchase.state == StoredPurchaseState.PURCHASED &&
                        it.purchaseState == StoredPurchaseState.PURCHASED &&
                        it.hasEligiblePurchaseSignature(bindings, mappings)
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
                retained[it.purchaseToken]?.hasEligiblePurchaseSignature(bindings, mappings) == false
        }
        val send = PurchaseSend.admit(PurchaseSyncReason.RESTORE, initiatingOwner, initiatingKind)
        // A dropped claim (its customer is no longer current, or its entry was
        // answered or admitted again before it went out) is not counted.
        val claimed = restorable.associate { evidence -> evidence.purchaseToken to claim(evidence, send) }
        val results = claimed.mapNotNull { (token, result) -> result?.let { token to it } }.toMap()
        val dropped = claimed.filterValues { it == null }.keys
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
            results.isEmpty() -> RestoreResult.NoPurchases
            // Owned by another account only when every claim was refused; any claim
            // still unanswered leaves the Restore unconfirmed, whatever the others said.
            results.values.none { it == SyncResult.ACCEPTED } -> RestoreResult.Failed(
                RestoreClaimException(
                    if (results.values.all { it == SyncResult.REFUSED }) {
                        RestoreClaimError.OWNED_BY_ANOTHER_ACCOUNT
                    } else {
                        RestoreClaimError.UNCONFIRMED
                    },
                ),
            )
            expectedOwnerDistinctId != null -> strictRestoreOutcome(found.filter { it.purchaseToken !in dropped }, results)
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
        val evidenceByToken = readEntries()
        val bindings = readBindings()
        val mappings = readMappings()
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
                claimed.any { !it.hasEligiblePurchaseSignature(bindings, mappings) } -> RestoreResult.Failed(
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
            claimed.any { it.needsManagedCompletion } -> RestoreResult.Failed(
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
        if (update.billingResult.responseCode == BillingClient.BillingResponseCode.ITEM_ALREADY_OWNED) {
            // The same already-owned reconciliation as a launch result gives.
            val checkout = inFlight.values.firstOrNull()
            if (checkout != null && inFlight.remove(checkout.product.storeProductId, checkout)) {
                val result = try {
                    reconcileAlreadyOwned(
                        checkout.product,
                        checkout.owner,
                        checkout.ownerKind,
                        checkout.outcomeEventId,
                        checkout.checkoutId,
                    )
                        ?: failed(
                            checkout.product,
                            BillingUnavailableException(
                                update.billingResult.responseCode,
                                update.billingResult.debugMessage,
                            ),
                            checkout.owner,
                            PurchaseOutcomeSource.CHECKOUT,
                            checkout.outcomeEventId,
                        )
                } catch (failure: Throwable) {
                    checkout.result.completeExceptionally(failure)
                    throw failure
                }
                checkout.result.complete(result)
                return
            }
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
        if (testStore != null) return
        val active = queryActivePurchases()
        val purchaseFollowUps = withProcessingDecision { effects ->
            active.mapNotNull {
                commitPurchaseOutcome(
                    classifyPurchaseObservation(it, PurchaseOutcomeSource.STARTUP_RECOVERY),
                    effects,
                )
            }
        }
        purchaseFollowUps.forEach { finishPurchase(it) }
        // While the outbox is off only what Play delivered is sent; stored entries
        // are sent and completed by the next launch's recovery.
        if (outboxOff) return
        // Each entry gets one attempt per recovery: those Play returned above were just sent.
        val sentThisPass = purchaseFollowUps.mapTo(mutableSetOf()) { it.purchaseToken }

        val recoveryFollowUps = withProcessingDecision { effects ->
            val bindings = readBindings()
            val mappings = readMappings()
            val evidenceRecords = readEntries().values
            // A checkout's completion is captured only while its buyer is the current
            // customer: the event log stamps the current identity on every event and
            // routes it to the current customer's Journeys. Until then it waits on the
            // buyer's saved entry.
            val currentCustomer = distinctId()
            evidenceRecords
                .filter {
                    it.purchaseState == StoredPurchaseState.PURCHASED &&
                        !it.completionEmitted &&
                        !it.buyerDistinctId.isNullOrBlank() &&
                        it.buyerDistinctId == currentCustomer &&
                        !it.refused &&
                        (it.synced || it.pendingSend != null) &&
                        it.hasEligiblePurchaseSignature(bindings, mappings)
                }
                .forEach { effects.stageRetainedPurchaseCompletion(it) }
            evidenceRecords
                .filter {
                    it.purchaseState == StoredPurchaseState.PURCHASED && (
                        it.pendingSend != null ||
                            (it.synced && (!it.syncedEventEmitted || it.needsManagedCompletion))
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
        val existing = readEntries()[purchase.purchaseToken]
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
        admitsSend: Boolean = true,
        correlatesCheckout: Boolean = true,
    ): PurchaseEvidence? {
        val identity = PurchaseCommitIdentity.Evidence(observation.purchase.purchaseToken)
        purchaseCommitOperations[identity]?.let { ongoing ->
            effects.purchaseCommitWaits += ongoing
            return null
        }
        return commitStoreOutcome(observation.purchase, observation.source, effects, admitsSend, correlatesCheckout)
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
                if (purchaseCommitOperations[identity] != null) {
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
        if (identity in completedExternalCommits) return null
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
        val first = when (identity) {
            is PurchaseCommitIdentity.External -> completedExternalCommits.add(identity)
            is PurchaseCommitIdentity.Evidence -> reportedEvidenceCommits.add(identity)
        }
        if (!first) return
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
    /**
     * [admitsSend] is false for a purchase a claim (Restore or already owned)
     * observes: the claim is admitted as its entry, so the observation writes
     * no send of its own and nothing it finds unlocks early first.
     * [correlatesCheckout] is false for the purchase an already-owned checkout
     * reconciles: it is no checkout's outcome, so no checkout binding, outcome
     * event or completion is attached to it.
     */
    private suspend fun commitStoreOutcome(
        purchase: PlayPurchase,
        source: PurchaseOutcomeSource,
        effects: ProcessingEffects,
        admitsSend: Boolean = true,
        correlatesCheckout: Boolean = true,
    ): PurchaseEvidence? {
        val matchingFlight = purchase.products.firstNotNullOfOrNull(inFlight::get)
        // A purchase already tied to one checkout is never another checkout's result:
        // an earlier checkout's late approval never takes a newer checkout in flight.
        // An entry's checkout, once set, never changes.
        val tiedCheckout = readEntries()[purchase.purchaseToken]?.checkoutId
        val isCheckoutOutcome = correlatesCheckout && matchingFlight?.matches(purchase) == true &&
            (tiedCheckout == null || tiedCheckout == matchingFlight.checkoutId)
        val checkoutFlight = matchingFlight?.takeIf { isCheckoutOutcome }
        // D2: evidence is durable before projection, facts, sync, acknowledge, or consume.
        // The observation is built over the entry as it stands and written in the
        // same step, so it is never based on an admission the entry no longer holds.
        val committed = projectionRefresh.withLock {
            // Another customer's settled record is only the record of their purchase:
            // this customer's checkout starts the purchase's entry afresh.
            val existing = readEntries()[purchase.purchaseToken]?.takeUnless { it.givesWayTo(checkoutFlight?.owner) }
            val bindings = readBindings()
            val mappings = readMappings()
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
            // Only a binding whose checkout still awaits its outcome correlates a
            // delivery with that checkout; any other binding gives catalog facts only.
            val checkoutBindings = if (correlatesCheckout) {
                bindings.filter { it.awaitingCheckout && it.checkoutId != null && purchase.purchaseToken !in it.priorTokens }
            } else {
                emptyList()
            }
            val accountBindings = checkoutBindings.filter { binding ->
                binding.obfuscatedAccountId == purchase.obfuscatedAccountId &&
                    binding.storeProductId in purchase.products &&
                    // Likewise only its own checkout's binding matches a tied purchase.
                    (existing?.checkoutId == null || binding.checkoutId == existing.checkoutId)
            }
            val exactBinding = selectFullProductMatch(accountBindings, knownProductIdentity) {
                it.productIdentity
            }
            val catalogBinding = exactBinding ?: selectFullProductMatch(
                (if (correlatesCheckout) bindings else emptyList()).filter { it.storeProductId in purchase.products },
                knownProductIdentity,
            ) { it.productIdentity }
            val catalogMapping = selectFullProductMatch(
                mappings.filter { it.storeProductId in purchase.products },
                knownProductIdentity,
            ) { it.productIdentity }
            val currentOwner = distinctId()
            // Checkout correlation only: which customer's checkout made this Play
            // purchase, from the checkout itself or its binding. A purchase a scan,
            // a Restore or an already-owned checkout finds carries no checkout
            // context, so it never gets a checkout completion. Who owns it is only
            // what the server last said.
            val buyerDistinctId = checkoutFlight?.owner
                ?: exactBinding?.distinctId
                ?: existing?.buyerDistinctId
            // What the current catalog asks for, never a flag stored on the entry: any
            // licensing key for this store product requires Play's signature.
            val licensingPublicKey = product?.licensingPublicKey ?: catalogBinding?.licensingPublicKey
                ?: catalogMapping?.licensingPublicKey
                ?: bindings.firstOrNull { it.storeProductId in purchase.products && it.licensingPublicKey != null }
                    ?.licensingPublicKey
                ?: mappings.firstOrNull { it.storeProductId in purchase.products && it.licensingPublicKey != null }
                    ?.licensingPublicKey
            val signatureVerificationRequired = licensingPublicKey != null
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
                !admitsSend -> null
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
                featureUseCustomerKinds = if (existing == null) emptyMap() else existing.featureUseCustomerKinds,
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
                signatureVerified = signatureVerified,
                authorityScope = purchaseStorageScope,
                backendSyncedAtMillis = existing?.backendSyncedAtMillis,
                checkoutCompletionEventId = existing?.checkoutCompletionEventId
                    ?: checkoutFlight?.outcomeEventId
                    ?: exactBinding?.outcomeEventId,
                checkoutId = existing?.checkoutId ?: checkoutFlight?.checkoutId ?: exactBinding?.checkoutId,
            )
            val stored = upsertEvidenceLocked(evidenceCandidate, givesWayTo = checkoutFlight?.owner)
            // A completion whose buyer is not the current customer waits on the buyer's
            // saved entry, which now holds it, so the checkout binding is retired. While
            // the outbox is off nothing is saved, and the binding keeps it until the
            // completion is captured.
            val matchedCheckout = exactBinding?.checkoutId
            if (!outboxOff && matchedCheckout != null && checkoutFlight == null &&
                stored.checkoutId == matchedCheckout && stored.buyerDistinctId != currentOwner &&
                stored.purchaseState == StoredPurchaseState.PURCHASED && !stored.completionEmitted
            ) {
                retireBinding(matchedCheckout)
            }
            stored to (stored.signatureVerified || !signatureVerificationRequired)
        }
        val evidence = committed.first
        // While the outbox is off the entry is this delivery's alone: the decision
        // goes on with it, and a Restore or already-owned claim sends it.
        if (outboxOff) effects.unsavedEntries[evidence.purchaseToken] = evidence
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
        if (!committed.second) {
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
        val needsCompletionCapture = correlatesCheckout && evidence.buyerDistinctId == distinctId() &&
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
        // While the outbox is off the delivered purchase is sent once and never
        // completed with Play: the next launch's recovery does that.
        if (outboxOff) {
            evidence.pendingSend?.let { sendUnsaved(evidence, it) }
            return
        }
        val current = readEntries()[evidence.purchaseToken] ?: evidence
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
    /** A claim's answer, or null when it was dropped (an obsolete claim never reaches the server). */
    private suspend fun claim(evidence: PurchaseEvidence, requested: PurchaseSend): SyncResult? {
        val purchaseToken = evidence.purchaseToken
        // The customer changed during the Restore: the claim is not made.
        if (requested.distinctId != distinctId()) return null
        // Each purchase's claim is its own admission.
        val claim = PurchaseSend.admit(requested.reason, requested.distinctId, requested.customerKind)
        // While the outbox is off the claim is sent once, unsaved.
        if (outboxOff) return sendUnsaved(evidence, claim)
        // Writes to one purchase's entry are serialized: a spend holding this
        // purchase's usage claim finishes before the claim takes the entry over.
        val admitted = withUsageClaim(purchaseToken) {
            withContext(NonCancellable) {
                projectionRefresh.withLock {
                    val current = readEntries()[purchaseToken] ?: return@withLock false
                    // The claim is built from the entry as it is now, in the purchase's
                    // serialized step, never from what was true before it waited: a claim
                    // whose customer is no longer the current one is dropped, so it can
                    // never move the purchase back after another customer's Restore.
                    if (requested.distinctId != distinctId()) return@withLock null
                    saveEntry(current.copy(
                        pendingSend = claim,
                        featureUseCustomerKinds = if (
                            (current.pendingSend?.distinctId ?: current.ownerDistinctId ?: current.buyerDistinctId) == claim.distinctId
                        ) current.featureUseCustomerKinds else emptyMap(),
                        syncAttempts = 0,
                        retryDueAtMillis = null,
                    ))
                }
            }
        } ?: return null
        if (!admitted) return if (outboxOff) sendUnsaved(evidence, claim) else SyncResult.UNAVAILABLE
        // A claim of the buyer's own purchase replaces an entry that unlocked early.
        refreshOptimisticProjection()
        return sendOwed(purchaseToken, expected = claim)
    }

    /**
     * Send [purchaseToken]'s owed entry: the outbox drain. A caller waiting on
     * its own entry ([expected]) gets that entry's answer.
     */
    private suspend fun sendOwed(purchaseToken: String, expected: PurchaseSend? = null): SyncResult? {
        while (true) {
            val (answer, owned) = sendInSlot(purchaseToken) { sendOnce(purchaseToken) }
                ?: return heldBack(purchaseToken)
            if (expected != null && answer.send == expected) return answer.result
            // This caller had the slot and nothing was sent: there is nothing to wait for.
            if (owned && (expected == null || answer.send == null)) return answer.result
            // Another send went first. Send ours if it is still owed and that
            // send was not this same entry.
            val owed = readEntries()[purchaseToken]?.pendingSend
            if (expected != null) {
                // The entry was answered or admitted again meanwhile: the expected send
                // is dropped, and its caller gets the answer its admission already got
                // this session, or nothing.
                if (owed != expected) return answeredAdmission(expected.admissionId)
                continue
            }
            if (owed == null) return SyncResult.UNAVAILABLE
            if (answer.send == owed) return answer.result
        }
    }

    /** Answers saved this session, by the admission they answered; the oldest go first. */
    private val answeredAdmissions = object : LinkedHashMap<String, SyncResult>() {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, SyncResult>?): Boolean = size > 64
    }

    private fun recordAnsweredAdmission(admissionId: String, result: SyncResult) {
        synchronized(answeredAdmissions) { answeredAdmissions[admissionId] = result }
    }

    private fun answeredAdmission(admissionId: String): SyncResult? =
        synchronized(answeredAdmissions) { answeredAdmissions[admissionId] }

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
                        readEntries()[purchaseToken]?.takeIf { it.synced }?.let { completeManaged(it) }
                    }
                    releaseSending(purchaseToken, operation)
                    operation.complete(answer)
                } catch (cancelled: CancellationException) {
                    operation.cancel(cancelled)
                    throw cancelled
                } catch (failure: Exception) {
                    Log.w("NuxieBilling", "Could not apply purchase synchronization.", failure)
                    // An entry still owed after a failure the send path did not handle
                    // counts a failed attempt and keeps its timer, like any unsent entry.
                    runCatching {
                        readEntries()[purchaseToken]?.pendingSend?.let { recordFailedSend(purchaseToken, it) }
                    }
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

    /**
     * A purchase-backed Feature command holds the purchase, so its entry cannot
     * be sent now: that counts one more failed attempt and schedules the entry's
     * retry by its own due time, in case the command's answer does not settle it.
     */
    private suspend fun heldBack(purchaseToken: String): SyncResult {
        readEntries()[purchaseToken]?.pendingSend?.let { recordFailedSend(purchaseToken, it) }
        return SyncResult.UNAVAILABLE
    }

    /**
     * Send the token's owed entry once and apply the server's answer. A 200, a
     * 409 or a 400 answers the send; anything else (transport, 5xx, 401, 403,
     * a body the SDK cannot use) keeps the entry for another attempt.
     */
    private suspend fun sendOnce(purchaseToken: String): SendAnswer {
        val evidence = readEntries()[purchaseToken] ?: return SendAnswer(null, SyncResult.UNAVAILABLE)
        val send = evidence.pendingSend ?: return SendAnswer(null, SyncResult.UNAVAILABLE)
        // The outbox turned off after this entry was saved (a later write failed):
        // the delivery or claim that admitted it is still sent once, unsaved. Timers
        // and the stored-entry pass never get here while it is off.
        if (outboxOff) return SendAnswer(null, sendUnsaved(evidence, send))
        // A purchase whose Play signature the current catalog requires and that
        // never verified is never sent. Like any owed entry that cannot be sent,
        // it counts a failed attempt and waits out its backoff, so its timer
        // goes on (a later recovery may verify it).
        if (!signatureEligible(evidence)) {
            recordFailedSend(purchaseToken, send)
            return SendAnswer(null, SyncResult.UNAVAILABLE)
        }
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
        val answer = when (outcome) {
            is PurchaseSyncOutcome.Rejected -> if (outcome.refused) PurchaseAnswer.Refused else PurchaseAnswer.Failed
            is PurchaseSyncOutcome.Accepted -> acceptedAnswer(evidence, outcome.response)
        }
        return SendAnswer(send, applyPurchaseAnswer(purchaseToken, send, answer))
    }

    /**
     * While the outbox is off: send [evidence], which could not be saved, once
     * for [send]. Nothing is written, held or retried. Any answer only refreshes
     * the requester's profile; the purchase is not completed with Play, so the
     * next launch's recovery saves its entry and sends it again.
     */
    private suspend fun sendUnsaved(evidence: PurchaseEvidence, send: PurchaseSend): SyncResult {
        // Never sent without the signature the current catalog requires.
        if (!signatureEligible(evidence)) return SyncResult.UNAVAILABLE
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
        val answer = when (outcome) {
            is PurchaseSyncOutcome.Rejected -> if (outcome.refused) PurchaseAnswer.Refused else PurchaseAnswer.Failed
            is PurchaseSyncOutcome.Accepted -> acceptedAnswer(evidence, outcome.response)
        }
        if (answer != PurchaseAnswer.Failed && send.distinctId == distinctId()) refreshFeatureAuthority()
        return answer.result
    }

    private val PurchaseAnswer.result: SyncResult
        get() = when (this) {
            is PurchaseAnswer.Accepted, is PurchaseAnswer.AcceptedFeatureUse -> SyncResult.ACCEPTED
            PurchaseAnswer.Refused -> SyncResult.REFUSED
            PurchaseAnswer.Failed -> SyncResult.UNAVAILABLE
        }

    /** What the server answered one send of a purchase's entry. */
    private sealed interface PurchaseAnswer {
        /** A 200 to `/purchase`, with the product type its catalog facts name. */
        data class Accepted(val response: NuxieApi.PurchaseResponse, val productType: String?) : PurchaseAnswer
        /** A 200 to a purchase-backed Feature command: the server consumed the Feature. */
        data class AcceptedFeatureUse(val customerId: String) : PurchaseAnswer
        /** A 409 conflict or a 400 invalid purchase: the requester is not the owner. */
        data object Refused : PurchaseAnswer
        /** No answer the SDK can act on. */
        data object Failed : PurchaseAnswer
    }

    /** A 200 the SDK can use, or [PurchaseAnswer.Failed] when its body does not fit the purchase. */
    private fun acceptedAnswer(attempted: PurchaseEvidence, response: NuxieApi.PurchaseResponse): PurchaseAnswer {
        val catalog = response.catalogProduct
        if (catalog == null && !attempted.catalogResolved) return PurchaseAnswer.Failed
        if (catalog != null && catalog.storeProductId !in attempted.storeProductIds) return PurchaseAnswer.Failed
        val productType = when (catalog?.storeProductType) {
            null -> attempted.productType
            "subscription" -> BillingClient.ProductType.SUBS
            "consumable", "nonConsumable" -> BillingClient.ProductType.INAPP
            else -> return PurchaseAnswer.Failed
        }
        return PurchaseAnswer.Accepted(response, productType)
    }

    /** What an answer did to its entry. */
    private sealed interface EntryAnswer {
        /** The answer is saved: the entry is answered and stays as the purchase's record. */
        data class Answered(val evidence: PurchaseEvidence) : EntryAnswer
        /** No answer: the entry stays owed with one more failed attempt, sent again at [dueAtMillis]. */
        data class Kept(val dueAtMillis: Long) : EntryAnswer
        /** A newer admission holds the entry: the answer changes nothing. */
        data object Superseded : EntryAnswer
        /**
         * The outbox is off (this write failed, or an earlier one did): nothing
         * changes on disk or in memory, and the answer only refreshes the profile.
         */
        data object Unsaved : EntryAnswer
    }

    /**
     * Every server answer lands here: a recovery or update's send, the retry
     * timer, a Restore or already-owned claim, and a purchase-backed Feature
     * command. The admission check comes first: only an answer to the
     * admission the entry still holds (and, for a Feature command, the payload
     * it used, [answersPayload]) may change it. A matching answer answers the
     * entry, which stays as the purchase's record; its flags, not any state
     * kept beside it, stop a later delivery from sending again. A matching
     * failure counts one more attempt. Nothing is written while the outbox is
     * off, and a write that fails turns it off: the answer is then [EntryAnswer.Unsaved].
     * Called only while [projectionRefresh] is held.
     */
    private suspend fun answerEntryLocked(
        purchaseToken: String,
        send: PurchaseSend,
        answer: PurchaseAnswer,
        answersPayload: (PurchaseEvidence) -> Boolean = { true },
    ): EntryAnswer {
        if (outboxOff) return EntryAnswer.Unsaved
        val current = readEntries()[purchaseToken]
        if (current == null || current.pendingSend != send || !answersPayload(current)) return EntryAnswer.Superseded
        val answered = when (answer) {
            is PurchaseAnswer.Accepted -> {
                val catalog = answer.response.catalogProduct
                current.copy(
                    nuxieProductId = catalog?.productId ?: current.nuxieProductId,
                    basePlanId = catalog?.basePlanId ?: current.basePlanId,
                    purchaseOptionId = catalog?.purchaseOptionId ?: current.purchaseOptionId,
                    offerId = catalog?.offerId ?: current.offerId,
                    productType = answer.productType,
                    consumable = catalog?.storeProductType == "consumable" ||
                        (catalog == null && current.consumable),
                    catalogResolved = current.catalogResolved || catalog != null,
                    // The server attached the purchase to the requester: that is now its owner.
                    ownerDistinctId = send.distinctId,
                    refused = false,
                    // Each accepted admission is reported once as purchase_synced,
                    // for the customer it was accepted for (telemetry only).
                    syncedEventEmitted = false,
                    syncedCustomerId = answer.response.customerId,
                    backendSyncedAtMillis = nowMillis(),
                )
            }
            is PurchaseAnswer.AcceptedFeatureUse -> current.copy(
                // The server's receipt attached the purchase to this customer. The
                // command reports it as purchase synced once this answer is saved.
                ownerDistinctId = answer.customerId,
                refused = false,
                syncedCustomerId = answer.customerId,
                syncedEventEmitted = false,
                backendSyncedAtMillis = nowMillis(),
            )
            PurchaseAnswer.Refused -> {
                // The requester is not the owner. A purchase left with no owner is
                // never sent again on its own, only by a Restore.
                val owner = current.ownerDistinctId.takeUnless { it == send.distinctId }
                current.copy(ownerDistinctId = owner, refused = current.refused || owner == null)
            }
            PurchaseAnswer.Failed -> null
        }?.copy(pendingSend = null, syncAttempts = 0, retryDueAtMillis = null)
        if (answered != null) {
            return if (saveEntry(answered)) EntryAnswer.Answered(answered) else EntryAnswer.Unsaved
        }
        // No answer: on disk only the attempt count changes, so the entry stays owed
        // and is sent again after its backoff. The due time is saved with the
        // attempt, so it survives a restart.
        val attempts = current.syncAttempts + 1
        val dueAt = nowMillis() + outboxRetryDelay(attempts)
        return if (saveEntry(current.copy(syncAttempts = attempts, retryDueAtMillis = dueAt))) {
            EntryAnswer.Kept(dueAt)
        } else {
            EntryAnswer.Unsaved
        }
    }

    /**
     * Apply the server's answer to a send of [purchaseToken]'s entry. After any
     * answer, accepted or refused, superseded or not, the requester's profile
     * is fetched again when they are the current customer: access comes from
     * it. A matching 200's Features hold until that profile lands.
     */
    private suspend fun applyPurchaseAnswer(
        purchaseToken: String,
        send: PurchaseSend,
        answer: PurchaseAnswer,
    ): SyncResult {
        val (entry, publication) = withContext(NonCancellable) {
            projectionRefresh.withLock {
                val entry = answerEntryLocked(purchaseToken, send, answer)
                val currentDistinctId = distinctId()
                entry to when {
                    // The outbox is off: no entry unlocks early, and the profile decides.
                    entry is EntryAnswer.Unsaved -> stageOptimisticProjectionLocked()
                    entry !is EntryAnswer.Answered -> null
                    // The 200's Features bridge until the profile refresh lands; nobody
                    // else keeps the early unlock this acceptance ends.
                    answer is PurchaseAnswer.Accepted && send.distinctId == currentDistinctId ->
                        features.stagePurchaseAnswer(
                            currentDistinctId,
                            answer.response.body,
                            deriveOptimisticProjection(currentDistinctId),
                        )
                    // Access is whatever the profile requested below says; an earlier
                    // 200's Features hold only until it lands.
                    else -> stageOptimisticProjectionLocked()
                }
            }
        }
        features.publishStaged(publication)
        if (entry is EntryAnswer.Kept) scheduleSendRetry(purchaseToken, send, entry.dueAtMillis)
        if (answer != PurchaseAnswer.Failed && send.distinctId == distinctId()) refreshFeatureAuthority()
        if (entry is EntryAnswer.Answered) recordAnsweredAdmission(send.admissionId, answer.result)
        return when (entry) {
            is EntryAnswer.Answered -> when (answer) {
                is PurchaseAnswer.Accepted, is PurchaseAnswer.AcceptedFeatureUse -> {
                    completeManaged(emitPurchaseSyncedIfNeeded(entry.evidence))
                    SyncResult.ACCEPTED
                }
                PurchaseAnswer.Refused -> SyncResult.REFUSED
                PurchaseAnswer.Failed -> SyncResult.UNAVAILABLE
            }
            is EntryAnswer.Kept -> SyncResult.UNAVAILABLE
            // A newer admission holds the entry; it is still owed and sent itself.
            // Or the outbox is off, and the answer only refreshed the profile.
            EntryAnswer.Superseded, EntryAnswer.Unsaved -> answer.result
        }
    }

    /** A send without an answer keeps its entry, with one more attempt and a retry after the backoff. */
    private suspend fun recordFailedSend(purchaseToken: String, send: PurchaseSend) {
        applyPurchaseAnswer(purchaseToken, send, PurchaseAnswer.Failed)
    }

    private suspend fun emitPurchaseSyncedIfNeeded(evidence: PurchaseEvidence): PurchaseEvidence =
        projectionRefresh.withLock {
            val current = readEntries()[evidence.purchaseToken] ?: evidence
            // While the outbox is off it is reported by the next launch, from a saved entry.
            // A purchase-backed Feature command holding the purchase reports its own answer.
            if (outboxOff || current.syncedEventEmitted || current.ownerDistinctId != distinctId() ||
                synchronized(usageCoordinationLock) { current.purchaseToken in purchaseUsageClaims }
            ) {
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
            current.copy(syncedEventEmitted = true).takeIf { saveEntry(it) } ?: current
        }

    private suspend fun completeManaged(evidence: PurchaseEvidence) {
        // While the outbox is off nothing is acknowledged or consumed: Play keeps the
        // purchase for the next launch's recovery.
        if (outboxOff) return
        if (completionRetryJobs[evidence.purchaseToken]?.isActive == true) return
        val ownsCompletion = synchronized(usageCoordinationLock) {
            managedCompletionClaims.add(evidence.purchaseToken)
        }
        if (!ownsCompletion) return
        try {
            val attempted = projectionRefresh.withLock {
                val current = readEntries()[evidence.purchaseToken] ?: evidence
                if (!current.synced || !current.needsManagedCompletion) return@withLock null
                // Nothing is acknowledged or consumed without the signature the catalog requires.
                if (!signatureEligible(current)) return@withLock null
                current.copy(completionAttempts = current.completionAttempts + 1)
                    .takeIf { saveEntry(it) }
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
                    val current = readEntries()[attempted.purchaseToken] ?: return@withLock
                    saveEntry(
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
        when (val active = billing.queryActive(BillingClient.ProductType.INAPP)) {
            is ActivePurchasesResult.Success -> active.purchases.none {
                it.purchaseToken == purchaseToken
            }
            is ActivePurchasesResult.Failed -> false
        }


    /**
     * Whether [evidence]'s Play signature satisfies what the current catalog asks
     * for. A catalog or binding read that fails turns the outbox off and fails
     * closed: the purchase is neither sent as verified nor completed.
     */
    private suspend fun signatureEligible(evidence: PurchaseEvidence): Boolean {
        val open = launchOpen
        open?.join()
        val bindings = evidenceStore.loadBindingsOrNull()
        val mappings = evidenceStore.loadProductMappingsOrNull()
        if (bindings == null || mappings == null) {
            if (open != null) switchOutboxOff()
            return false
        }
        return evidence.hasEligiblePurchaseSignature(bindings, mappings)
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

    /**
     * Merge an observation's monotonic Play facts; called only while
     * [projectionRefresh] is held. The server's word (owner, refusal, customer)
     * and an owed send come only from the server's answers and admissions, so
     * an observation read before an answer cannot undo it. An observation adds
     * a send only for a purchase that owes none and has no answer yet, and the
     * customer's own checkout upgrades only an owed entry's reason. A settled
     * record of another customer than [givesWayTo] is replaced, not merged.
     */
    private suspend fun upsertEvidenceLocked(candidate: PurchaseEvidence, givesWayTo: String? = null): PurchaseEvidence {
        val current = readEntries()[candidate.purchaseToken]?.takeUnless { it.givesWayTo(givesWayTo) }
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
            featureUseCustomerKinds = current.featureUseCustomerKinds,
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
            retryDueAtMillis = current.retryDueAtMillis,
            completionAttempts = maxOf(candidate.completionAttempts, current.completionAttempts),
            firstSeenMillis = minOf(candidate.firstSeenMillis, current.firstSeenMillis),
            catalogResolved = candidate.catalogResolved || current.catalogResolved,
            completionEmitted = candidate.completionEmitted || current.completionEmitted,
            syncedEventEmitted = candidate.syncedEventEmitted || current.syncedEventEmitted,
            syncedCustomerId = current.syncedCustomerId,
            nuxieManaged = candidate.nuxieManaged || current.nuxieManaged,
            signatureVerified = candidate.signatureVerified || current.signatureVerified,
            backendSyncedAtMillis = current.backendSyncedAtMillis,
            checkoutCompletionEventId = current.checkoutCompletionEventId
                ?: candidate.checkoutCompletionEventId,
            checkoutId = current.checkoutId ?: candidate.checkoutId,
            pinnedFeatureAllowances = current.pinnedFeatureAllowances
                ?: candidate.pinnedFeatureAllowances,
        ).let { kept ->
            when {
                // A new admission starts its attempt count and due time again.
                kept.pendingSend != current.pendingSend -> kept.copy(syncAttempts = 0, retryDueAtMillis = null)
                // A write that changes what the owed send carries (the catalog filling
                // in a product type, base plan or offer, say) is an admission too, so
                // an answer to the earlier payload cannot settle the changed entry.
                kept.pendingSend != null && kept.sendPayload != current.sendPayload -> kept.copy(
                    pendingSend = kept.pendingSend.readmitted(),
                    syncAttempts = 0,
                    retryDueAtMillis = null,
                )
                else -> kept
            }
        }
        // A later delivery that changes nothing writes nothing: the entry already
        // records it. One that cannot be saved turns the outbox off, and the merged
        // entry is this delivery's alone.
        if (merged != current) saveEntry(merged)
        return merged
    }

    /**
     * Wake [token]'s entry at [dueAtMillis] to send [send] again: its failure
     * time plus the delay for its attempt count. A timer is only a wake-up; the
     * stored entry decides. It is set only while the entry still owes [send],
     * and never earlier than the entry's stored due time, so a caller holding
     * an older read of the entry cannot bring a retry forward. Reading the
     * entry and replacing the timer happen as one step, so the last timer set
     * is for the latest stored due time. An overdue one is due now.
     */
    private suspend fun scheduleSendRetry(token: String, send: PurchaseSend, dueAtMillis: Long) {
        retryScheduling.withLock {
            val stored = readEntries()[token]
            // An outbox that is off sets no timer.
            if (outboxOff || stored?.pendingSend != send) return@withLock
            val dueAt = maxOf(dueAtMillis, stored.retryDueAtMillis ?: dueAtMillis)
            sendRetryJobs.remove(token)?.cancel()
            sendRetryJobs[token] = scope.launch {
                delay((dueAt - nowMillis()).coerceAtLeast(0))
                // ConcurrentMap's own remove(key, value): available on every supported API level.
                (sendRetryJobs as java.util.concurrent.ConcurrentMap<String, Job>).remove(token, coroutineContext[Job])
                wakeSendRetry(token, send)
            }
        }
    }

    /** Serializes reading an entry's due time with replacing its timer. */
    private val retryScheduling = Mutex()

    /**
     * A retry timer fired. The entry decides: answered, or held by a newer
     * admission, it does nothing; still owed but not yet due, the timer is set
     * again for its stored due time; owed and due, it is sent.
     */
    private suspend fun wakeSendRetry(token: String, send: PurchaseSend) {
        if (outboxOff) return
        val stored = readEntries()[token] ?: return
        if (stored.pendingSend != send) return
        val dueAt = stored.retryDueAtMillis
        if (dueAt != null && dueAt > nowMillis()) {
            scheduleSendRetry(token, send, dueAt)
            return
        }
        // A held-back send counts a failed attempt in sendOwed itself.
        sendOwed(token)
    }

    private fun scheduleCompletionRetry(token: String, attempt: Int) {
        if (outboxOff || completionRetryJobs[token]?.isActive == true) return
        completionRetryJobs[token] = scope.launch {
            delay(retryDelay(attempt))
            if (outboxOff) return@launch
            completionRetryJobs.remove(token)
            readEntries()[token]
                ?.takeIf { it.synced && it.needsManagedCompletion }
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
                    // An already-owned checkout reports what iOS reports for it.
                    if (outcome.reason is PurchaseAlreadyOwnedException) {
                        linkedMapOf<String, Any?>(
                            "product_id" to product.productId,
                            "placement_id" to product.placementId,
                            "store_product_id" to product.storeProductId,
                            "reason" to "already_owned",
                            "test_store" to outcome.testStore,
                        ).filterValues { it != null }
                    } else {
                        properties
                    },
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
        priorTokens: Set<String> = emptySet(),
        checkoutId: String? = null,
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
        priorTokens = priorTokens,
        checkoutId = checkoutId,
    )

    internal suspend fun rememberProduct(product: StoreProduct): Boolean =
        saveMapping(product.toMapping()).also { persisted ->
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
     * Ask Play for its active purchases of both types. Returns what the
     * queries that answered reported; a failed query reports nothing.
     */
    private suspend fun queryActivePurchases(): List<PlayPurchase> =
        listOf(BillingClient.ProductType.SUBS, BillingClient.ProductType.INAPP).flatMap { type ->
            (runCatching { billing.queryActive(type) }.getOrNull() as? ActivePurchasesResult.Success)
                ?.purchases.orEmpty()
        }

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
        val descriptors = readMappings()
        val bindings = readBindings()
        // Cross-commit visibility is deliberate: an evidence row is DURABLE
        // at persist time and the accepted optimistic-projection spec derives
        // the overlay from the durable evidence window with no dependency on
        // the purchase-completed carrier. The committer's evidence -> signal
        // -> projection order is per-commit discipline; another commit's
        // refresh surfacing this row before this commit's carrier lands is
        // spec-conformant, not a leak (adjudicated UNIV-2676 review r2).
        val retainedEvidence = if (outboxOff) emptyList() else readEntries().values
        val evidence = retainedEvidence.map { retained ->
            if (retained.pinnedFeatureAllowances != null) return@map retained
            val resolved = resolvedFeatureAllowancesForEvidence(retained, descriptors, bindings)
                ?: return@map retained
            // A resolved allowance projection is safe only after its token-scoped
            // allowance snapshot is durable. Otherwise a later replacement or
            // relaunch could silently swap the retained purchase's allowances. A
            // snapshot that cannot be saved turns the outbox off.
            upsertEvidenceLocked(retained.copy(pinnedFeatureAllowances = resolved))
        }
        return optimisticFeatureProjection(
            distinctId = currentDistinctId,
            authorityScope = purchaseStorageScope,
            // The entry alone decides the early unlock (see [isBuyersOwnUnsyncedPurchase]),
            // and none does while the outbox is off: access comes from the profile alone.
            evidence = if (outboxOff) emptyList() else evidence,
            descriptors = descriptors,
            bindings = bindings,
        )
    }


private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.encodeToByteArray())
        .joinToString("") { "%02x".format(it) }
}

private fun String?.toApiProductType(): String? = when (this) {
    BillingClient.ProductType.SUBS -> "subscription"
    BillingClient.ProductType.INAPP -> "one_time"
    else -> null
}
