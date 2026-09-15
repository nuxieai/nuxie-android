package ai.nuxie.sdk.presentation

import ai.nuxie.sdk.runtime.NativeSemanticNode
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.RectF
import android.view.View
import kotlin.math.ceil
import kotlin.math.floor

/** Same centered-contain coordinates as native rendering, followed by Android view transforms. */
internal fun semanticBounds(host: View, size: ExperienceArtboardSize?, node: NativeSemanticNode): ExperienceAccessibilityProvider.Bounds? {
    val transform = ContainCenterTransform.create(size ?: return null, host.width, host.height) ?: return null
    val local = RectF(
        node.minX * transform.scale + transform.contentLeft,
        node.minY * transform.scale + transform.contentTop,
        node.maxX * transform.scale + transform.contentLeft,
        node.maxY * transform.scale + transform.contentTop,
    )
    if (!listOf(local.left, local.top, local.right, local.bottom).all(Float::isFinite) ||
        !local.intersect(0f, 0f, host.width.toFloat(), host.height.toFloat())) return null
    val chain = mutableListOf<View>()
    var current: View? = host
    while (current != null) { chain += current; current = current.parent as? View }
    val root = chain.last()
    val location = IntArray(2)
    root.getLocationOnScreen(location)
    val origin = floatArrayOf(0f, 0f)
    root.matrix.mapPoints(origin)
    val matrix = Matrix().apply {
        setTranslate(location[0] - origin[0], location[1] - origin[1])
        preConcat(root.matrix)
    }
    for (child in chain.asReversed().drop(1)) {
        val parent = child.parent as View
        matrix.preTranslate((child.left - parent.scrollX).toFloat(), (child.top - parent.scrollY).toFloat())
        matrix.preConcat(child.matrix)
    }
    val screen = RectF(local)
    matrix.mapRect(screen)
    val visible = Rect()
    if (!host.getGlobalVisibleRect(visible)) return null
    // getGlobalVisibleRect is in the root's coordinate space, unlike screen bounds.
    val rootScreen = IntArray(2)
    host.rootView.getLocationOnScreen(rootScreen)
    visible.offset(rootScreen[0], rootScreen[1])
    if (!screen.intersect(RectF(visible))) return null
    fun RectF.outward() = Rect(floor(left.toDouble()).toInt(), floor(top.toDouble()).toInt(),
        ceil(right.toDouble()).toInt(), ceil(bottom.toDouble()).toInt())
    return ExperienceAccessibilityProvider.Bounds(local.outward(), screen.outward())
}
