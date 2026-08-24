package ai.nuxie.sdk.features

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first

/**
 * Reactive snapshot of the current customer's Feature access. StateFlow
 * publication is thread-safe, so updates arrive from any thread; a
 * main-thread hop here would deadlock callers that hold the main thread
 * (identity transitions under runBlocking).
 */
class FeatureInfo {
    sealed interface State {
        /** No profile has hydrated access for the current customer yet. */
        object Unknown : State

        /** At least one profile hydration has completed for the current customer. */
        object Ready : State
    }

    private val mutableState = MutableStateFlow<State>(State.Unknown)
    private val mutableAll = MutableStateFlow<Map<String, FeatureAccess>>(emptyMap())
    @Volatile
    private var entityAccess: Map<String, Map<String, FeatureAccess>> = emptyMap()

    val state: StateFlow<State> = mutableState
    val all: StateFlow<Map<String, FeatureAccess>> = mutableAll

    /** Suspends until profile-backed Feature access is available. */
    suspend fun awaitReady() {
        state.filterIsInstance<State.Ready>().first()
    }

    /** Whether the current cached access allows [featureId]. */
    fun isAllowed(featureId: String): Boolean = all.value[featureId]?.allowed ?: false

    /** Returns the cached balance for [featureId], optionally scoped to [entityId]. */
    fun balance(featureId: String, entityId: String? = null): Double? =
        if (entityId == null) all.value[featureId]?.balance
        else entityAccess[featureId]?.get(entityId)?.balance

    internal fun update(
        features: Map<String, FeatureAccess>,
        entities: Map<String, Map<String, FeatureAccess>>,
        ready: Boolean = false,
    ) {
        entityAccess = entities
        mutableAll.value = features
        if (ready) mutableState.value = State.Ready
    }

    internal fun update(featureId: String, access: FeatureAccess, entityId: String?) {
        if (entityId == null) {
            mutableAll.value = mutableAll.value + (featureId to access)
        } else {
            entityAccess = entityAccess + (
                featureId to (entityAccess[featureId].orEmpty() + (entityId to access))
            )
        }
    }

    internal fun clear() {
        entityAccess = emptyMap()
        mutableAll.value = emptyMap()
    }

    internal fun reset() {
        clear()
        mutableState.value = State.Unknown
    }
}
