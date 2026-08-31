package ai.nuxie.sdk.features

import ai.nuxie.sdk.hasSameDoubleValueAs
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.ExperimentalForInheritanceCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/**
 * Reactive snapshot of the current customer's Feature access. StateFlow
 * publication is thread-safe, so updates arrive from any thread; a
 * main-thread hop here would deadlock callers that hold the main thread.
 */
class FeatureInfo {
    /** Whether server-backed Feature access is available and fully reconciled. */
    sealed interface State {
        /** No profile has hydrated access for the current customer yet. */
        object Unknown : State

        /** Visible access currently includes an optimistic verified-purchase overlay. */
        object Reconciling : State

        /** A profile is admitted and no optimistic purchase overlay is active. */
        object Ready : State
    }

    private val mutableState = MutableStateFlow<State>(State.Unknown)
    private val mutableAll = MutableFeatureAccessFlow()
    private val stagingLock = Any()
    private val emissionLock = Any()
    @Volatile
    private var currentGeneration = Generation()
    @Volatile
    private var entityAccess: Map<String, Map<String, FeatureAccess>> = emptyMap()

    @Volatile
    internal var onFeatureChange: suspend (
        featureId: String,
        oldAccess: FeatureAccess?,
        newAccess: FeatureAccess,
        isCurrent: () -> Boolean,
    ) -> Unit = { _, _, _, _ -> }

    /** One FIFO lane per customer-visible publication generation. */
    private class Generation {
        var tail = CompletableDeferred<Unit>().also { it.complete(Unit) }

        /** Last snapshot fully published for this generation (repair source). */
        @Volatile
        var latestSnapshot: PublishedSnapshot? = null
    }

    private class PublishedSnapshot(
        val features: Map<String, FeatureAccess>,
        val entities: Map<String, Map<String, FeatureAccess>>,
        val state: State,
    )

    /** Fence shared by readiness, values, and every last-mile callback. */
    private inner class PublicationFence(
        private val generation: Generation,
        private val additionalCheck: () -> Boolean,
    ) {
        fun isCurrent(): Boolean =
            currentGeneration === generation && additionalCheck()

        suspend fun publishSnapshot(
            features: Map<String, FeatureAccess>,
            entities: Map<String, Map<String, FeatureAccess>>,
            state: State?,
        ) {
            if (!isCurrent()) return
            val oldFeatures = synchronized(emissionLock) {
                if (!isCurrent()) return
                mutableAll.value
            }
            val observer = onFeatureChange
            val callbacks = (oldFeatures.keys + features.keys).toSortedSet().mapNotNull { featureId ->
                val oldAccess = oldFeatures[featureId]
                val newAccess = features[featureId] ?: FEATURE_NOT_FOUND
                if (oldAccess.hasSameFieldsAs(newAccess)) {
                    null
                } else {
                    FeatureChange(featureId, oldAccess, newAccess)
                }
            }

            // Readiness and values are one staged publication. A scope change
            // may supersede the whole snapshot, but cannot commit only one
            // half and leave Ready paired with stale or empty Feature values.
            var lastStored: PublishedFeatures? = null
            if (!emitIfCurrent(this) {
                    if (state != null) mutableState.value = state
                    entityAccess = entities
                    lastStored = mutableAll.publish(features)
                }
            ) {
                // An unconfined collector may have reentered mid-emission and
                // published a replacement generation inline; our stale sets
                // then overwrote it. Restore the replacement's snapshot.
                repairClobberedReplacement()
                return
            }
            val recorded = PublishedSnapshot(
                features,
                entities,
                state ?: mutableState.value,
            )
            var stored = lastStored
            while (true) {
                synchronized(emissionLock) {
                    if (!isCurrent()) {
                        repairClobberedReplacement()
                        return
                    }
                    generation.latestSnapshot = recorded
                }
                // A stale repairer may have overwritten the flows between the
                // emission and the record; re-set until the flow holds this
                // publication's exact container (identity, not field
                // equality: NaN balances never field-compare equal, and a
                // deduped publish returns the surviving equal container).
                val settled = mutableAll.currentPublished === stored &&
                    mutableState.value == recorded.state
                if (settled) break
                mutableState.value = recorded.state
                entityAccess = recorded.entities
                stored = mutableAll.publish(recorded.features)
            }

            callbacks.forEach { callback ->
                if (!isCurrent()) return
                runCatching {
                    observer(
                        callback.featureId,
                        callback.oldAccess,
                        callback.newAccess,
                        ::isCurrent,
                    )
                }
                if (!isCurrent()) return
            }
        }
    }

    private data class FeatureChange(
        val featureId: String,
        val oldAccess: FeatureAccess?,
        val newAccess: FeatureAccess,
    )

    /** A FIFO position reserved at the engine mutation commit point. */
    internal class Mutation internal constructor(
        internal val previous: Deferred<Unit>,
        internal val completed: CompletableDeferred<Unit>,
        internal val apply: suspend () -> Unit,
    )

    val state: StateFlow<State> = mutableState
    val all: StateFlow<Map<String, FeatureAccess>> = mutableAll

    /** Suspends until profile-backed Feature access is available. */
    suspend fun awaitReady() {
        state.filterIsInstance<State.Ready>().first()
    }

    /** Whether the current cached access allows [featureId]. */
    fun isAllowed(featureId: String): Boolean = all.value[featureId]?.allowed ?: false

    /** Returns the globally scoped cached balance for [featureId]. */
    fun balance(featureId: String): Double? = all.value[featureId]?.balance

    internal suspend fun update(
        features: Map<String, FeatureAccess>,
        entities: Map<String, Map<String, FeatureAccess>>,
        state: State? = null,
    ) = publish(stageUpdate(features, entities, state))

    internal fun stageUpdate(
        features: Map<String, FeatureAccess>,
        entities: Map<String, Map<String, FeatureAccess>>,
        state: State? = null,
        isCurrent: () -> Boolean = { true },
    ): Mutation = stage(isCurrent) { fence ->
        fence.publishSnapshot(features, entities, state)
    }

    /**
     * Invalidate every older customer publication and stage the complete new
     * customer view. Staging emits nothing; callers publish only after all
     * identity-decision locks have been released.
     */
    internal fun stageIdentityChange(
        features: Map<String, FeatureAccess>,
        entities: Map<String, Map<String, FeatureAccess>>,
        state: State,
    ): Mutation = synchronized(emissionLock) {
        synchronized(stagingLock) {
            val generation = Generation()
            currentGeneration = generation
            stageLocked(generation, isCurrent = { true }) { fence ->
                fence.publishSnapshot(features, entities, state)
            }
        }
    }

    internal suspend fun update(featureId: String, access: FeatureAccess, entityId: String?) =
        publish(stageUpdate(featureId, access, entityId))

    internal fun stageUpdate(
        featureId: String,
        access: FeatureAccess,
        entityId: String?,
    ): Mutation = stage { fence ->
        val entities = if (entityId == null) {
            entityAccess
        } else {
            entityAccess + (
                featureId to (entityAccess[featureId].orEmpty() + (entityId to access))
            )
        }
        // iOS has one reactive Feature map: an entity check publishes its
        // result there even though its reusable cache entry stays scoped.
        fence.publishSnapshot(mutableAll.value + (featureId to access), entities, state = null)
    }

    internal suspend fun clear() = publish(stageClear())

    internal fun stageClear(): Mutation = stage { fence ->
        fence.publishSnapshot(emptyMap(), emptyMap(), state = null)
    }

    internal suspend fun reset() = publish(
        stageIdentityChange(emptyMap(), emptyMap(), State.Unknown),
    )

    internal fun stageReset(): Mutation = stage { fence ->
        fence.publishSnapshot(emptyMap(), emptyMap(), State.Unknown)
    }

    /** Reserve a FIFO position without changing the visible Feature snapshot. */
    internal fun stageBarrier(): Mutation = stage { _ -> }

    internal suspend fun publish(mutation: Mutation) {
        // Once engine state commits, cancellation cannot abandon its matching
        // listener/StateFlow publication or strand later FIFO reservations.
        withContext(NonCancellable) {
            mutation.previous.await()
            try {
                mutation.apply()
            } finally {
                mutation.completed.complete(Unit)
            }
        }
    }

    private fun stage(
        isCurrent: () -> Boolean = { true },
        apply: suspend (PublicationFence) -> Unit,
    ): Mutation = synchronized(stagingLock) {
        stageLocked(currentGeneration, isCurrent, apply)
    }

    private fun stageLocked(
        generation: Generation,
        isCurrent: () -> Boolean,
        apply: suspend (PublicationFence) -> Unit,
    ): Mutation {
        val completed = CompletableDeferred<Unit>()
        val previous = generation.tail
        generation.tail = completed
        val fence = PublicationFence(generation, isCurrent)
        return Mutation(previous, completed) { apply(fence) }
    }

    private fun repairClobberedReplacement() {
        // Loop: another swap, or a newer same-generation publication, can
        // land during the repair itself. Exit only when the snapshot the
        // repair restored is still the generation's recorded latest.
        while (true) {
            val (generation, snapshot) = synchronized(emissionLock) {
                currentGeneration to currentGeneration.latestSnapshot
            }
            // No completed publication for the replacement yet: its recorder
            // verifies the flows after recording and re-sets if this repair
            // (or any stale writer) clobbered its emission.
            if (snapshot == null) return
            mutableState.value = snapshot.state
            entityAccess = snapshot.entities
            mutableAll.publish(snapshot.features)
            synchronized(emissionLock) {
                if (currentGeneration === generation &&
                    generation.latestSnapshot === snapshot
                ) {
                    return
                }
            }
        }
    }

    private inline fun emitIfCurrent(
        fence: PublicationFence,
        emit: () -> Unit,
    ): Boolean {
        // Decide under the lock, emit after releasing it: StateFlow.setValue
        // resumes unconfined collectors inline, and a collector may reenter
        // the facade (identify/reset), which takes the facade monitor while
        // the facade's own identity path wants this lock (AB-BA deadlock).
        // The FIFO publication lane serializes publishers, so values cannot
        // interleave; a generation swap that lands mid-emission is repaired
        // by its own queued publication, and the post-emission check makes
        // the caller abandon its remaining callbacks.
        synchronized(emissionLock) {
            if (!fence.isCurrent()) return false
        }
        emit()
        return synchronized(emissionLock) { fence.isCurrent() }
    }

    private companion object {
        val FEATURE_NOT_FOUND = FeatureAccess(
            allowed = false,
            unlimited = false,
            balance = null,
            type = FeatureType.BOOLEAN,
        )
    }
}

private fun FeatureAccess?.hasSameFieldsAs(other: FeatureAccess): Boolean =
    this != null &&
        allowed == other.allowed &&
        unlimited == other.unlimited &&
        balance.hasSameDoubleValueAs(other.balance) &&
        type == other.type

private fun Map<String, FeatureAccess>.hasSameFieldsAs(
    other: Map<String, FeatureAccess>,
): Boolean = size == other.size && all { (featureId, access) ->
    other[featureId]?.let(access::hasSameFieldsAs) == true
}

private data class PublishedFeatures(
    val revision: Long,
    val features: Map<String, FeatureAccess>,
)

/**
 * Publishes Feature maps using Swift field equality instead of Kotlin data-class equality.
 * The revision makes every comparator-approved transition visible to MutableStateFlow,
 * including NaN-to-NaN changes.
 */
@OptIn(ExperimentalForInheritanceCoroutinesApi::class)
private class MutableFeatureAccessFlow : StateFlow<Map<String, FeatureAccess>> {
    private val published = MutableStateFlow(PublishedFeatures(0L, emptyMap()))

    override val value: Map<String, FeatureAccess>
        get() = published.value.features

    override val replayCache: List<Map<String, FeatureAccess>>
        get() = published.replayCache.map(PublishedFeatures::features)

    override suspend fun collect(collector: FlowCollector<Map<String, FeatureAccess>>): Nothing =
        published.collect { collector.emit(it.features) }

    @Suppress("MemberVisibilityCanBePrivate")
    fun publish(features: Map<String, FeatureAccess>): PublishedFeatures {
        val current = published.value
        if (current.features.hasSameFieldsAs(features)) return current
        val next = PublishedFeatures(current.revision + 1, features)
        published.value = next
        return next
    }

    /** The exact published container, for identity-based clobber detection. */
    val currentPublished: PublishedFeatures
        get() = published.value
}
