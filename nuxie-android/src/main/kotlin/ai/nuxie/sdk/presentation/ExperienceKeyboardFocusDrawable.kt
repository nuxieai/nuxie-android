package ai.nuxie.sdk.presentation

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.Drawable

/** Two contrasting strokes remain discernible over arbitrary authored scene colors. */
internal class ExperienceKeyboardFocusDrawable(density: Float) : Drawable() {
    private val outerWidth = 4f * density
    private val innerWidth = 2f * density
    private var drawableAlpha = 255
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }

    override fun draw(canvas: Canvas) {
        if (bounds.isEmpty) return
        val save = canvas.save()
        canvas.clipRect(bounds)
        val rect = RectF(bounds)
        // Keep the entire indicator within the visible semantic bounds, including at host edges.
        val inset = minOf(outerWidth / 2f, rect.width() / 2f, rect.height() / 2f)
        rect.inset(inset, inset)
        paint.color = Color.BLACK
        paint.alpha = drawableAlpha
        paint.strokeWidth = outerWidth
        canvas.drawRect(rect, paint)
        paint.color = Color.WHITE
        paint.alpha = drawableAlpha
        paint.strokeWidth = innerWidth
        canvas.drawRect(rect, paint)
        canvas.restoreToCount(save)
    }

    override fun setAlpha(alpha: Int) { drawableAlpha = alpha.coerceIn(0, 255); invalidateSelf() }
    override fun setColorFilter(colorFilter: ColorFilter?) { paint.colorFilter = colorFilter; invalidateSelf() }
    @Deprecated("Android framework requires this override")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}
