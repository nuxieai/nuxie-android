package ai.nuxie.sdk.journey

import java.util.concurrent.atomic.AtomicReference

/** Physical visibility and authority readiness are separate admission conditions. */
internal class JourneyForegroundAdmission {
    private enum class Phase { BACKGROUND, REVALIDATING, ACTIVE }
    private data class State(val generation: Long, val phase: Phase, val completedRevalidations: Long = 0)
    private val state = AtomicReference(State(0, Phase.BACKGROUND))

    fun visibilityChanged(visible: Boolean) {
        transitionTo(if (visible) Phase.REVALIDATING else Phase.BACKGROUND)
    }

    fun revalidationToken(): Long? = state.get().takeIf { it.phase == Phase.REVALIDATING }?.generation
    fun isRevalidating(): Boolean = state.get().phase == Phase.REVALIDATING
    fun completedRevalidations(): Long = state.get().completedRevalidations
    fun get(): Boolean = state.get().phase == Phase.ACTIVE

    fun completeRevalidation(generation: Long): Boolean {
        val current = state.get()
        return current.generation == generation && current.phase == Phase.REVALIDATING &&
            state.compareAndSet(current, current.copy(
                phase = Phase.ACTIVE, completedRevalidations = current.completedRevalidations + 1,
            ))
    }

    private fun transitionTo(phase: Phase) {
        while (true) {
            val current = state.get()
            if (state.compareAndSet(current, current.copy(generation = current.generation + 1, phase = phase))) return
        }
    }

    /** For callers that have already completed foreground authority recovery. */
    fun activate() {
        transitionTo(Phase.ACTIVE)
    }
}
