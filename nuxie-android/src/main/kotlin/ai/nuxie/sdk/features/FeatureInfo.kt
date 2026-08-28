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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Reactive snapshot of the current customer's Feature access. StateFlow
 * publication is thread-safe, so updates arrive from any thread; a
 * main-thread hop here would deadlock callers that hold the main thread
 * (identity transitions under runBlocking).
 */
class FeatureInfo {
    internal sealed interface State {
        /** No profile has hydrated access for the current customer yet. */
        object Unknown : State

        /** At least one profile hydration has completed for the current customer. */
        object Ready : State
    }

    private val mutableState = MutableStateFlow<State>(State.Unknown)
    private val mutableAll = MutableFeatureAccessFlow()
    private val mutationLock = Mutex()
    private val stagingLock = Any()
    private var mutationTail = CompletableDeferred<Unit>().also { it.complete(Unit) }
    @Volatile
    private var entityAccess: Map<String, Map<String, FeatureAccess>> = emptyMap()

    internal var onFeatureChange: suspend (
        featureId: String,
        oldAccess: FeatureAccess?,
        newAccess: FeatureAccess,
    ) -> Unit = { _, _, _ -> }

    /** A FIFO position reserved at the engine mutation commit point. */
    internal class Mutation internal constructor(
        internal val previous: Deferred<Unit>,
        internal val completed: CompletableDeferred<Unit>,
        internal val apply: suspend () -> Unit,
    )

    internal val state: StateFlow<State> = mutableState
    val all: StateFlow<Map<String, FeatureAccess>> = mutableAll

    /** Suspends until profile-backed Feature access is available. */
    internal suspend fun awaitReady() {
        state.filterIsInstance<State.Ready>().first()
    }

    /** Whether the current cached access allows [featureId]. */
    fun isAllowed(featureId: String): Boolean = all.value[featureId]?.allowed ?: false

    /** Returns the globally scoped cached balance for [featureId]. */
    fun balance(featureId: String): Double? = all.value[featureId]?.balance

    internal suspend fun update(
        features: Map<String, FeatureAccess>,
        entities: Map<String, Map<String, FeatureAccess>>,
        ready: Boolean = false,
    ) = publish(stageUpdate(features, entities, ready))

    internal fun stageUpdate(
        features: Map<String, FeatureAccess>,
        entities: Map<String, Map<String, FeatureAccess>>,
        ready: Boolean = false,
    ): Mutation = stage {
            val oldFeatures = mutableAll.value
            features.forEach { (featureId, newAccess) ->
                val oldAccess = oldFeatures[featureId]
                if (!oldAccess.hasSameFieldsAs(newAccess)) {
                    runCatching { onFeatureChange(featureId, oldAccess, newAccess) }
                }
            }
            entityAccess = entities
            mutableAll.publish(features)
            if (ready) mutableState.value = State.Ready
    }

    internal suspend fun update(featureId: String, access: FeatureAccess, entityId: String?) =
        publish(stageUpdate(featureId, access, entityId))

    internal fun stageUpdate(featureId: String, access: FeatureAccess, entityId: String?): Mutation = stage {
            val oldAccess = mutableAll.value[featureId]
            if (!oldAccess.hasSameFieldsAs(access)) {
                runCatching { onFeatureChange(featureId, oldAccess, access) }
            }
            // iOS has one reactive Feature map: an entity check publishes its
            // result there even though its reusable cache entry stays scoped.
            mutableAll.publish(mutableAll.value + (featureId to access))
            if (entityId != null) {
                entityAccess = entityAccess + (
                    featureId to (entityAccess[featureId].orEmpty() + (entityId to access))
                )
            }
    }

    internal suspend fun setBalance(featureId: String, balance: Double, entityId: String?) =
        publish(stage {
            val current = mutableAll.value[featureId] ?: return@stage
            val updated = current.copy(
                allowed = current.unlimited || balance >= 1.0,
                balance = balance,
            )
            if (!current.hasSameFieldsAs(updated)) {
                runCatching { onFeatureChange(featureId, current, updated) }
            }
            // Match the same iOS reactive publication after entity-scoped use.
            mutableAll.publish(mutableAll.value + (featureId to updated))
            if (entityId != null) {
                entityAccess = entityAccess + (
                    featureId to (entityAccess[featureId].orEmpty() + (entityId to updated))
                )
            }
        })

    internal suspend fun clear() = publish(stageClear())

    internal fun stageClear(): Mutation = stage {
            entityAccess = emptyMap()
            mutableAll.publish(emptyMap())
    }

    internal suspend fun reset() = publish(stageReset())

    internal fun stageReset(): Mutation = stage {
            entityAccess = emptyMap()
            mutableAll.publish(emptyMap())
            mutableState.value = State.Unknown
    }

    internal suspend fun publish(mutation: Mutation) {
        // Once engine state commits, cancellation cannot abandon its matching
        // listener/StateFlow publication or strand later FIFO reservations.
        withContext(NonCancellable) {
            mutation.previous.await()
            try {
                mutationLock.withLock { mutation.apply() }
            } finally {
                mutation.completed.complete(Unit)
            }
        }
    }

    private fun stage(apply: suspend () -> Unit): Mutation = synchronized(stagingLock) {
        val completed = CompletableDeferred<Unit>()
        Mutation(mutationTail, completed, apply).also { mutationTail = completed }
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

    fun publish(features: Map<String, FeatureAccess>) {
        val current = published.value
        if (current.features.hasSameFieldsAs(features)) return
        published.value = PublishedFeatures(current.revision + 1, features)
    }
}
