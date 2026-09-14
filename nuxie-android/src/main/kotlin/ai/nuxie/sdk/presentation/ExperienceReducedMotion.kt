package ai.nuxie.sdk.presentation

import android.animation.ValueAnimator
import android.content.Context
import android.database.ContentObserver
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings

/** Main-thread subscription to Android's animation preference, owned by one presentation. */
internal class ExperienceReducedMotion(
    context: Context,
    private val publish: (Boolean) -> Unit,
) : AutoCloseable {
    private val resolver = context.contentResolver
    private var closed = false
    private var previous: Boolean? = null
    private val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) = refreshSetting()
    }
    private val platformSubscription: AutoCloseable?

    init {
        // Register before reading so a change during setup cannot be lost.
        platformSubscription = if (Build.VERSION.SDK_INT >= 33) {
            val listener = ValueAnimator.DurationScaleChangeListener { scale -> emit(scale == 0f) }
            if (ValueAnimator.registerDurationScaleChangeListener(listener)) {
                AutoCloseable { ValueAnimator.unregisterDurationScaleChangeListener(listener) }
            } else null
        } else null
        if (platformSubscription == null) {
            resolver.registerContentObserver(Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE), false, observer)
        }
        refresh()
    }

    /** Reconcile changes received while the Activity was stopped. */
    fun refresh() {
        if (closed) return
        if (Build.VERSION.SDK_INT >= 33 && platformSubscription != null) emit(ValueAnimator.getDurationScale() == 0f)
        else refreshSetting()
    }

    private fun refreshSetting() {
        if (!closed) emit(Settings.Global.getFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f)
    }

    private fun emit(value: Boolean) {
        if (!closed && previous != value) {
            previous = value
            publish(value)
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        if (platformSubscription == null) resolver.unregisterContentObserver(observer)
        platformSubscription?.close()
    }
}
