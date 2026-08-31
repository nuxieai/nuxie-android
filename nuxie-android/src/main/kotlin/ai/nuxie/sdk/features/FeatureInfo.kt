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

    private val published = MutableStateFlow(
        PublishedSnapshot(
            features = emptyMap(),
            entities = emptyMap(),
            state = State.Unknown,
            featuresRevision = 0L,
        )
    )
    private val stagingLock = Any()
    private val emissionLock = Any()
    @Volatile
    private var currentGeneration = Generation()
    private val entityAccess: Map<String, Map<String, FeatureAccess>>
        get() = published.value.entities

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
    }

    /**
     * The one atomically published unit: values, entity scopes, and readiness
     * commit together (a scope change can never leave Ready paired with stale
     * values, and entity scopes cannot drift from the values they rode with).
     */
    private class PublishedSnapshot(
        val features: Map<String, FeatureAccess>,
        val entities: Map<String, Map<String, FeatureAccess>>,
        val state: State,
        val featuresRevision: Long,
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
            val oldFeatures = published.value.features
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

            // One CAS-committed container: values, entity scopes, and
            // readiness commit atomically, with the fence re-checked every
            // iteration and NO lock held across the store. StateFlow resumes
            // unconfined collectors inline from the store, and a collector
            // may reenter the facade; committing lock-free is what makes
            // that reentry safe (the historical AB-BA deadlock). A stale
            // writer either observes its dead fence and exits, or its
            // committed container is immediately replaced by the live
            // writer's own CAS retry.
            if (commitSnapshot(features, entities, state) == null) return

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

        private fun commitSnapshot(
            features: Map<String, FeatureAccess>,
            entities: Map<String, Map<String, FeatureAccess>>,
            state: State?,
        ): PublishedSnapshot? {
            while (true) {
                if (!isCurrent()) return null
                val current = published.value
                val resolvedState = state ?: current.state
                val featuresChanged = !current.features.hasSameFieldsAs(features)
                val stateChanged = current.state != resolvedState
                val entitiesChanged = current.entities != entities
                if (!featuresChanged && !stateChanged && !entitiesChanged) {
                    return current
                }
                val next = PublishedSnapshot(
                    features = features,
                    entities = entities,
                    state = resolvedState,
                    featuresRevision = current.featuresRevision +
                        (if (featuresChanged) 1L else 0L),
                )
                if (published.compareAndSet(current, next)) return next
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

    val state: StateFlow<State> = ProjectedStateFlow(
        source = published,
        project = PublishedSnapshot::state,
        distinctBy = { it.state },
    )
    val all: StateFlow<Map<String, FeatureAccess>> = ProjectedStateFlow(
        source = published,
        project = PublishedSnapshot::features,
        distinctBy = { it.featuresRevision },
    )

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
        val snapshot = published.value
        val entities = if (entityId == null) {
            snapshot.entities
        } else {
            snapshot.entities + (
                featureId to (snapshot.entities[featureId].orEmpty() + (entityId to access))
            )
        }
        // iOS has one reactive Feature map: an entity check publishes its
        // result there even though its reusable cache entry stays scoped.
        fence.publishSnapshot(snapshot.features + (featureId to access), entities, state = null)
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

/**
 * A read-only StateFlow projecting one field of the published snapshot,
 * emitting only when its projection's distinct key changes.
 */
@OptIn(ExperimentalForInheritanceCoroutinesApi::class)
private class ProjectedStateFlow<S, T>(
    private val source: StateFlow<S>,
    private val project: (S) -> T,
    private val distinctBy: (S) -> Any?,
) : StateFlow<T> {
    override val value: T get() = project(source.value)
    override val replayCache: List<T> get() = source.replayCache.map(project)
    override suspend fun collect(collector: FlowCollector<T>): Nothing {
        var lastKey: Any? = Unset
        source.collect { snapshot ->
            val key = distinctBy(snapshot)
            if (lastKey === Unset || lastKey != key) {
                lastKey = key
                collector.emit(project(snapshot))
            }
        }
    }

    private object Unset
}
