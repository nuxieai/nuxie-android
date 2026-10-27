package ai.nuxie.sdk.presentation

import ai.nuxie.sdk.experiences.ExperiencePageHostContract.Style
import ai.nuxie.sdk.logging.NuxieLog
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeoutOrNull

/** Page mutations in a batch run on one native lane, before another advance or draw. */
internal object ExperiencePageStep {
    data class Exit(val event: String?, val watchdogMs: Long?)
    interface Operations {
        fun input(enabled: Boolean)
        suspend fun prepare()
        suspend fun register(screenId: String, event: String): Deferred<Unit>
        suspend fun batch(present: Boolean = false, watchdogMs: Long = 250, writes: suspend () -> Unit) { writes() }
        suspend fun takeIncoming()
        /** Route (a): one ordinary zero-time step once the incoming row is placed and woken (R3c). */
        suspend fun settle()
        suspend fun phases(values: List<Pair<String, ExperienceScreenLifecycle.Phase>>, transition: String)
        suspend fun begin(transition: String?)
        suspend fun end(transition: String?)
        suspend fun retire()
        suspend fun present()
        suspend fun wait(completions: List<Deferred<Unit>>, milliseconds: Long) {
            withTimeoutOrNull(milliseconds) { completions.awaitAll() }
        }
        suspend fun hidden()
        suspend fun active()
        /** One ordinary step before the incoming row takes over, so step_end commands reach the row they leave (S8). */
        suspend fun drain() = Unit
    }

    suspend fun perform(source: String, target: String, style: Style, isBack: Boolean,
        plan: ExperienceScreenTransitionPlan.Custom?, exit: Exit, reducedMotion: Boolean, operations: Operations,
        coveredCut: Boolean = false) {
        if (source == target) return
        operations.input(false)
        try {
            operations.prepare()
            val transition = plan?.id
            val phasesTransition = transition.orEmpty()
            var loggedPresentationTimeout = false
            suspend fun awaitHalf(completions: List<Deferred<Unit>>, budget: Long) {
                val presented = withTimeoutOrNull(budget) { operations.present(); true } == true
                currentCoroutineContext().ensureActive()
                if (presented) operations.wait(completions, budget)
                else if (!loggedPresentationTimeout) {
                    loggedPresentationTimeout = true
                    NuxieLog.w("ExperiencePageStep", "Experience phase presentation watchdog expired", null,
                        NuxieLog.sensitive("watchdogMs", budget))
                }
                currentCoroutineContext().ensureActive()
            }
            val exiting = listOf(source to ExperienceScreenLifecycle.Phase.EXITING)
            val entering = listOf(target to ExperienceScreenLifecycle.Phase.ENTERING)
            val hidden = listOf(source to ExperienceScreenLifecycle.Phase.HIDDEN)
            val active = listOf(target to ExperienceScreenLifecycle.Phase.ACTIVE)
            suspend fun incoming() {
                operations.takeIncoming()
                operations.settle()
            }
            if (coveredCut) {
                // The lower page changes unseen: both rows stay hidden, and its entrance starts at the reveal.
                operations.batch {
                    operations.phases(listOf(source to ExperienceScreenLifecycle.Phase.HIDDEN,
                        target to ExperienceScreenLifecycle.Phase.HIDDEN), "")
                    incoming()
                    operations.begin(null)
                    operations.end(null)
                    operations.retire()
                }
            } else if (reducedMotion) {
                operations.batch {
                    incoming()
                    operations.phases(exiting, phasesTransition)
                    operations.phases(entering, phasesTransition)
                    operations.begin(transition)
                    operations.end(transition)
                    operations.retire()
                    operations.phases(hidden + active, "")
                }
            } else if (style != Style.NONE && plan != null) {
                val completions = mutableListOf<Deferred<Unit>>()
                operations.batch(present = true, watchdogMs = plan.watchdogMs) {
                    incoming()
                    plan.outgoingCompletionEvent.takeIf { it.isNotEmpty() }?.let { completions += operations.register(source, it) }
                    plan.incomingCompletionEvent.takeIf { it.isNotEmpty() }?.let { completions += operations.register(target, it) }
                    operations.phases(exiting + entering, phasesTransition)
                    operations.begin(transition)
                }
                awaitHalf(completions, plan.watchdogMs)
                operations.batch {
                    operations.end(transition)
                    operations.retire()
                    operations.phases(hidden + active, "")
                }
            } else {
                val outgoing = (plan?.outgoingCompletionEvent ?: exit.event)?.takeIf { it.isNotEmpty() }?.let { operations.register(source, it) }
                val leaveBudget = plan?.watchdogMs ?: exit.watchdogMs ?: 250
                operations.batch(present = outgoing != null, watchdogMs = leaveBudget) {
                    operations.phases(exiting, phasesTransition)
                }
                if (outgoing != null) awaitHalf(listOf(outgoing), leaveBudget)
                var incoming: Deferred<Unit>? = null
                operations.batch(present = plan != null, watchdogMs = plan?.watchdogMs ?: 250) {
                    incoming()
                    incoming = plan?.incomingCompletionEvent?.takeIf { it.isNotEmpty() }?.let { operations.register(target, it) }
                    operations.phases(entering, phasesTransition)
                    operations.begin(transition)
                    operations.retire()
                    operations.phases(hidden, "")
                }
                incoming?.let { awaitHalf(listOf(it), checkNotNull(plan).watchdogMs) }
                operations.batch {
                    operations.end(transition)
                    operations.phases(active, "")
                }
            }
            // step_end commands arrive on the next player step. Deliver that result
            // while the outgoing row still owns host commands, even after retirement.
            operations.drain()
            currentCoroutineContext().ensureActive()
            operations.hidden()
            currentCoroutineContext().ensureActive()
            operations.active()
        } finally { operations.input(true) }
    }
}
