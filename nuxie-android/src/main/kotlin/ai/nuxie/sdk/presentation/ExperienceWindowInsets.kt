package ai.nuxie.sdk.presentation

import android.app.Activity
import android.os.Build
import android.view.View
import android.view.ViewTreeObserver
import android.view.WindowInsets
import kotlin.math.max
import kotlin.math.min

/** Activity-owned observation of system bars/cutouts relative to the actual rendering view. */
internal class ExperienceWindowInsets(
    private val activity: Activity,
    private val view: View,
    private val artboardSize: ExperienceArtboardSize?,
    private val publish: (ExperienceSafeAreaInsets) -> Unit,
) : AutoCloseable {
    /** Native shell controls consume local pixel insets without artboard projection. */
    constructor(activity: Activity, view: View, publish: (ExperienceSafeAreaInsets) -> Unit) :
        this(activity, view, null, publish)
    private var closed = false
    private var previous: ExperienceSafeAreaInsets? = null
    private val update = Runnable { update() }
    private val layout = ViewTreeObserver.OnGlobalLayoutListener { scheduleUpdate() }

    init {
        activity.window.decorView.viewTreeObserver.addOnGlobalLayoutListener(layout)
        view.setOnApplyWindowInsetsListener { _, insets ->
            // rootWindowInsets is current after dispatch has completed. Preserve
            // ordinary framework dispatch and avoid replacing parent padding.
            scheduleUpdate()
            view.onApplyWindowInsets(insets)
        }
        view.requestApplyInsets()
        scheduleUpdate()
    }

    private fun scheduleUpdate() {
        if (closed) return
        view.removeCallbacks(update)
        view.post(update)
    }

    private fun update() {
        if (closed || !view.isAttachedToWindow || view.width <= 0 || view.height <= 0) return
        val position = IntArray(2)
        val windowInsets: WindowInsets
        val windowWidth: Int
        val windowHeight: Int
        if (Build.VERSION.SDK_INT >= 30) {
            val metrics = activity.windowManager.currentWindowMetrics
            windowInsets = metrics.windowInsets
            windowWidth = metrics.bounds.width()
            windowHeight = metrics.bounds.height()
            view.getLocationOnScreen(position)
            position[0] -= metrics.bounds.left
            position[1] -= metrics.bounds.top
        } else {
            val root = activity.window.decorView
            windowInsets = root.rootWindowInsets ?: return
            windowWidth = root.width
            windowHeight = root.height
            view.getLocationInWindow(position)
            val rootPosition = IntArray(2)
            root.getLocationInWindow(rootPosition)
            position[0] -= rootPosition[0]
            position[1] -= rootPosition[1]
        }
        val local = relativeInsets(systemInsets(windowInsets), windowWidth, windowHeight,
            position[0], position[1], view.width, view.height)
        val projected = artboardSize?.let {
            ExperienceSafeAreaInsetMapper.artboardInsets(local,
                view.width.toDouble(), view.height.toDouble(), it.width.toDouble(), it.height.toDouble())
        } ?: local
        if (projected != previous) {
            previous = projected
            publish(projected)
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        view.removeCallbacks(update)
        view.setOnApplyWindowInsetsListener(null)
        val observer = activity.window.decorView.viewTreeObserver
        if (observer.isAlive) observer.removeOnGlobalLayoutListener(layout)
    }

    companion object {
        @Suppress("DEPRECATION")
        internal fun systemInsets(insets: WindowInsets): ExperienceSafeAreaInsets {
            if (Build.VERSION.SDK_INT >= 30) {
                val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                return ExperienceSafeAreaInsets(bars.top.toDouble(), bars.bottom.toDouble(),
                    bars.left.toDouble(), bars.right.toDouble())
            }
            // Legacy system-window bottom includes the IME. Stable bounds cap it
            // at system-bar space; hidden bars still produce a zero current inset.
            val cutout = if (Build.VERSION.SDK_INT >= 28) insets.displayCutout?.let {
                ExperienceSafeAreaInsets(it.safeInsetTop.toDouble(), it.safeInsetBottom.toDouble(),
                    it.safeInsetLeft.toDouble(), it.safeInsetRight.toDouble())
            } ?: ExperienceSafeAreaInsets.ZERO else ExperienceSafeAreaInsets.ZERO
            return ExperienceSafeAreaInsets(
                max(min(insets.systemWindowInsetTop, insets.stableInsetTop).toDouble(), cutout.top),
                max(min(insets.systemWindowInsetBottom, insets.stableInsetBottom).toDouble(), cutout.bottom),
                max(min(insets.systemWindowInsetLeft, insets.stableInsetLeft).toDouble(), cutout.left),
                max(min(insets.systemWindowInsetRight, insets.stableInsetRight).toDouble(), cutout.right),
            )
        }

        internal fun relativeInsets(
            insets: ExperienceSafeAreaInsets,
            windowWidth: Int,
            windowHeight: Int,
            left: Int,
            top: Int,
            width: Int,
            height: Int,
        ) = ExperienceSafeAreaInsets(
            (insets.top - top).coerceIn(0.0, height.coerceAtLeast(0).toDouble()),
            (insets.bottom - (windowHeight.toDouble() - top - height)).coerceIn(0.0, height.coerceAtLeast(0).toDouble()),
            (insets.left - left).coerceIn(0.0, width.coerceAtLeast(0).toDouble()),
            (insets.right - (windowWidth.toDouble() - left - width)).coerceIn(0.0, width.coerceAtLeast(0).toDouble()),
        )
    }
}
