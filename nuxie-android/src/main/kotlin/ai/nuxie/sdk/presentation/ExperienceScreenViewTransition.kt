package ai.nuxie.sdk.presentation

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.view.View
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred

/** Main-thread owner of temporary View transforms; screens retain their native resources. */
internal class ExperienceScreenViewTransition(
    private val source: View,
    private val target: View,
    private val kind: ExperienceScreenTransitionPlan.Kind,
) {
    private val completion = CompletableDeferred<Unit>()
    private var animator: ValueAnimator? = null

    suspend fun play(visible: Boolean = true) {
        check(animator == null && !completion.isCompleted)
        target.bringToFront()
        applyProgress(0f)
        val animation = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 300
            addUpdateListener { applyProgress(it.animatedValue as Float) }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) { completion.complete(Unit) }
                override fun onAnimationCancel(animation: Animator) {
                    completion.completeExceptionally(CancellationException("Screen transition cancelled"))
                }
            })
        }
        animator = animation
        try {
            animation.start()
            if (!visible) animation.pause()
            completion.await()
        } finally {
            if (animation.isStarted) animation.cancel()
        }
    }

    fun setVisible(visible: Boolean) {
        animator?.let { if (visible) it.resume() else it.pause() }
    }

    fun cancel() {
        completion.completeExceptionally(CancellationException("Screen transition owner closed"))
        animator?.cancel()
    }

    fun restoreSource() {
        cancel()
        source.alpha = 1f
        source.translationX = 0f
        source.translationY = 0f
        target.alpha = 0f
        target.translationX = 0f
        target.translationY = 0f
    }

    internal fun applyProgress(progress: Float) {
        val fraction = progress.coerceIn(0f, 1f)
        when (kind) {
            ExperienceScreenTransitionPlan.Kind.FADE -> {
                source.alpha = 1f - fraction
                target.alpha = fraction
            }
            ExperienceScreenTransitionPlan.Kind.PUSH -> {
                target.alpha = 1f
                val direction = if (target.layoutDirection == View.LAYOUT_DIRECTION_RTL) -1 else 1
                source.translationX = -direction * source.width * fraction
                target.translationX = direction * target.width * (1f - fraction)
            }
            ExperienceScreenTransitionPlan.Kind.MODAL -> {
                target.alpha = 1f
                target.translationY = target.height * (1f - fraction)
            }
            else -> error("View animation requires a standard animated transition")
        }
    }
}
