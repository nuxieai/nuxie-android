package ai.nuxie.sdk.presentation

import ai.nuxie.sdk.runtime.NuxieTextRunGeometry
import android.view.View
import android.widget.FrameLayout
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * A full affine transform represented by two ordinary View transforms.
 * A = R(outer) diag(scaleX, scaleY) R(inner). The signed second scale retains
 * reflections. Unlike a draw-only matrix, these transforms also participate in
 * Android's input, caret and accessibility coordinate conversion on API 23+.
 */
internal class ExperienceAffinePlacement private constructor(
    val x: Float,
    val y: Float,
    val rotation: Float,
    val scaleX: Float,
    val scaleY: Float,
    val innerRotation: Float,
    val innerX: Float,
    val innerY: Float,
    val containerWidth: Int,
    val containerHeight: Int,
) {
    fun apply(container: FrameLayout, child: View) {
        container.clipChildren = false
        container.clipToPadding = false
        container.pivotX = 0f
        container.pivotY = 0f
        container.x = x
        container.y = y
        container.rotation = rotation
        container.scaleX = scaleX
        container.scaleY = scaleY
        val params = container.layoutParams
        if (params.width != containerWidth || params.height != containerHeight) {
            params.width = containerWidth
            params.height = containerHeight
            container.layoutParams = params
        }
        child.pivotX = 0f
        child.pivotY = 0f
        child.x = innerX
        child.y = innerY
        child.rotation = innerRotation
    }

    companion object {
        fun resolve(transform: NuxieTextRunGeometry.Transform, width: Int, height: Int): ExperienceAffinePlacement? {
            if (width <= 0 || height <= 0) return null
            val (af, bf, cf, df, tx, ty) = transform
            if (listOf(af, bf, cf, df, tx, ty).any { !it.isFinite() }) return null
            val a = af.toDouble(); val b = bf.toDouble()
            val c = cf.toDouble(); val d = df.toDouble()
            val determinant = a * d - b * c
            if (determinant == 0.0) return null
            // The principal eigenvector of A^T A gives the first right singular vector.
            val theta = 0.5 * atan2(2 * (a * c + b * d), a * a + b * b - c * c - d * d)
            val co = cos(theta); val si = sin(theta)
            val ux = a * co + c * si
            val uy = b * co + d * si
            val firstScale = hypot(ux, uy)
            val secondScale = determinant / firstScale
            val outerAngle = atan2(uy, ux)
            // Bound the inner rotated rectangle so the parent admits every child touch.
            val xs = doubleArrayOf(0.0, co * width, co * width + si * height, si * height)
            val ys = doubleArrayOf(0.0, -si * width, -si * width + co * height, co * height)
            val minX = xs.min(); val minY = ys.min()
            val extentX = ceil(xs.max() - minX)
            val extentY = ceil(ys.max() - minY)
            if (extentX > Int.MAX_VALUE || extentY > Int.MAX_VALUE) return null
            val outerCos = cos(outerAngle); val outerSin = sin(outerAngle)
            val x = tx + outerCos * firstScale * minX - outerSin * secondScale * minY
            val y = ty + outerSin * firstScale * minX + outerCos * secondScale * minY
            val values = listOf(x, y, firstScale, secondScale).map(Double::toFloat)
            if (values.any { !it.isFinite() } || values[2] == 0f || values[3] == 0f) return null
            return ExperienceAffinePlacement(values[0], values[1], Math.toDegrees(outerAngle).toFloat(),
                values[2], values[3], Math.toDegrees(-theta).toFloat(), (-minX).toFloat(), (-minY).toFloat(),
                extentX.toInt().coerceAtLeast(1), extentY.toInt().coerceAtLeast(1))
        }
    }
}
