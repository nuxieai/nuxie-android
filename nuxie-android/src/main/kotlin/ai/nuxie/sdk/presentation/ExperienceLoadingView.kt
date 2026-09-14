package ai.nuxie.sdk.presentation

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Shader
import android.view.View
import android.view.animation.LinearInterpolator
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin

/** A transparent loading overlay. The container alone owns the authenticated ground color. */
internal class ExperienceLoadingView(context: Context, backgroundColor: Int) : View(context), AutoCloseable {
    private val paint = Paint()
    private val transform = Matrix()
    private var gradient: LinearGradient? = null
    private var reducedMotion = true
    private var active = false
    private var closed = false
    private var preference: ExperienceReducedMotion? = null
    private val highlight = loadingHighlight(backgroundColor)
    private val animator = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 1350L
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener { invalidate() }
    }

    init {
        contentDescription = "Experience loading"
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
        isClickable = false
        isFocusable = false
    }

    fun setActive(value: Boolean) {
        active = value
        preference?.refresh()
        reconcile()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (!closed) preference = ExperienceReducedMotion(context) {
            reducedMotion = it
            reconcile()
        }
        reconcile()
    }

    override fun onDetachedFromWindow() {
        preference?.close()
        preference = null
        animator.cancel()
        super.onDetachedFromWindow()
    }

    private fun reconcile() {
        if (!closed && active && isAttachedToWindow && !reducedMotion) {
            if (!animator.isStarted) animator.start()
        } else animator.cancel()
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        val band = hypot(w.toFloat(), h.toFloat()) * 0.45f
        gradient = if (band > 0f) LinearGradient(
            -band / 2f * AXIS_X, -band / 2f * AXIS_Y,
            band / 2f * AXIS_X, band / 2f * AXIS_Y,
            IntArray(61) { index ->
                val ramp = 1f - kotlin.math.abs(index / 30f - 1f)
                val smooth = ramp * ramp * (3f - 2f * ramp)
                (highlight and 0x00ffffff) or ((Color.alpha(highlight) * smooth).roundToInt() shl 24)
            }, null, Shader.TileMode.CLAMP,
        ) else null
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (closed || !active || reducedMotion || !animator.isStarted) return
        val shader = gradient ?: return
        // Pixel-space projection keeps the angle constant for sheets and narrow drawers.
        // The band clears every corner before the short pause between sweeps.
        val radius = (width * AXIS_X + height * AXIS_Y) / 2f
        val band = hypot(width.toFloat(), height.toFloat()) * 0.45f
        val progress = ((animator.animatedValue as Float) * 1350f / 1200f).coerceAtMost(1f)
        val distance = (progress * 2f - 1f) * (radius + band / 2f)
        transform.setTranslate(width / 2f + distance * AXIS_X, height / 2f + distance * AXIS_Y)
        shader.setLocalMatrix(transform)
        paint.shader = shader
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
    }

    override fun close() {
        if (closed) return
        closed = true
        preference?.close()
        preference = null
        animator.cancel()
        animator.removeAllUpdateListeners()
        invalidate()
    }

    companion object {
        private val AXIS_X = cos(Math.toRadians(20.0)).toFloat()
        private val AXIS_Y = sin(Math.toRadians(20.0)).toFloat()
    }
}

/** Match the reference palette's measured contrast, with a deliberate unknown-background fallback. */
internal fun loadingHighlight(backgroundColor: Int): Int {
    fun linear(channel: Int): Double {
        val value = channel / 255.0
        return if (value <= 0.03928) value / 12.92 else ((value + 0.055) / 1.055).pow(2.4)
    }
    val luminance = 0.2126 * linear(Color.red(backgroundColor)) +
        0.7152 * linear(Color.green(backgroundColor)) + 0.0722 * linear(Color.blue(backgroundColor))
    val light = Color.alpha(backgroundColor) < 128 || 1.05 / (luminance + 0.05) >= (luminance + 0.05) / 0.05
    return if (light) 0x29ffffff else 0x17000000
}
