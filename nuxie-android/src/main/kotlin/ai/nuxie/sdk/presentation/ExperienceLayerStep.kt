package ai.nuxie.sdk.presentation

import ai.nuxie.sdk.logging.NuxieLog

import ai.nuxie.sdk.experiences.ExperienceLayerHostContract.Style
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.withTimeoutOrNull

/** Ordering shared by the layer surface and deterministic tests. */
internal object ExperienceLayerStep {
    data class Exit(val event: String?, val watchdogMs: Long?)
    interface Operations {
        fun input(enabled: Boolean)
        suspend fun prepare()
        suspend fun register(screenId: String, event: String): Deferred<Unit>
        suspend fun insert(index: Int)
        suspend fun phases(values: List<Pair<String, ExperienceScreenLifecycle.Phase>>, transition: String)
        suspend fun present()
        suspend fun wait(completions: List<Deferred<Unit>>, milliseconds: Long) {
            withTimeoutOrNull(milliseconds) { completions.awaitAll() }
        }
        suspend fun remove()
        suspend fun hidden()
        suspend fun active()
    }

    suspend fun perform(source: String, target: String, style: Style, isBack: Boolean,
        plan: ExperienceScreenTransitionPlan.Custom?, exit: Exit, reducedMotion: Boolean, operations: Operations) {
        if (source == target) return
        operations.input(false)
        try {
            operations.prepare()
            val transition = plan?.id.orEmpty()
            val index = if (plan?.incomingOnTop ?: (style != Style.PUSH || !isBack)) 1 else 0
            var loggedPresentationTimeout = false
            suspend fun present(milliseconds: Long): Boolean {
                val presented = withTimeoutOrNull(milliseconds) { operations.present(); true } == true
                currentCoroutineContext().ensureActive()
                if (!presented && !loggedPresentationTimeout) {
                    loggedPresentationTimeout = true
                    NuxieLog.w("ExperienceLayerStep",
                        "Experience phase presentation watchdog expired", null, NuxieLog.sensitive("watchdogMs", milliseconds))
                }
                return presented
            }
            if (style != Style.NONE && plan != null && !reducedMotion) {
                val outgoing = operations.register(source, plan.outgoingCompletionEvent)
                val incoming = operations.register(target, plan.incomingCompletionEvent)
                operations.insert(index)
                operations.phases(listOf(source to ExperienceScreenLifecycle.Phase.EXITING,
                    target to ExperienceScreenLifecycle.Phase.ENTERING), transition)
                if (present(plan.watchdogMs)) operations.wait(listOf(outgoing, incoming), plan.watchdogMs)
                currentCoroutineContext().ensureActive()
                operations.remove()
            } else {
                val outgoing = if (reducedMotion) null else (plan?.outgoingCompletionEvent ?: exit.event)
                    ?.let { operations.register(source, it) }
                operations.phases(listOf(source to ExperienceScreenLifecycle.Phase.EXITING), transition)
                if (!reducedMotion) {
                    val duration = plan?.watchdogMs ?: exit.watchdogMs
                    if (present(duration ?: 250) && outgoing != null && duration != null) operations.wait(listOf(outgoing), duration)
                }
                currentCoroutineContext().ensureActive()
                operations.insert(index)
                operations.remove()
                val incoming = if (reducedMotion || plan == null) null else
                    operations.register(target, plan.incomingCompletionEvent)
                operations.phases(listOf(target to ExperienceScreenLifecycle.Phase.ENTERING), transition)
                if (!reducedMotion) {
                    if (present(plan?.watchdogMs ?: 250) && incoming != null && plan != null) operations.wait(listOf(incoming), plan.watchdogMs)
                }
            }
            currentCoroutineContext().ensureActive()
            operations.phases(listOf(source to ExperienceScreenLifecycle.Phase.HIDDEN,
                target to ExperienceScreenLifecycle.Phase.ACTIVE), "")
            operations.hidden()
            currentCoroutineContext().ensureActive()
            operations.active()
        } finally { operations.input(true) }
    }
}
