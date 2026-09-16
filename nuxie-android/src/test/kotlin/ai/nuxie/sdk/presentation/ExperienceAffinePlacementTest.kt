package ai.nuxie.sdk.presentation

import ai.nuxie.sdk.fixtures.FixtureRunner
import ai.nuxie.sdk.runtime.NuxieTextRunGeometry
import android.app.Activity
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [23, 35])
class ExperienceAffinePlacementTest {
    @Test fun `native view transforms preserve authored corners and inverse touch coordinates`() {
        val fixture = Json.parseToJsonElement(FixtureRunner.fixturesRoot()
            .resolve("journeys/planes/text-input-affine.json").readText()).jsonObject
        val width = fixture.getValue("width").jsonPrimitive.int
        val height = fixture.getValue("height").jsonPrimitive.int
        val controller = Robolectric.buildActivity(Activity::class.java).setup().visible()
        try {
            for (item in fixture.getValue("cases").jsonArray) {
                val case = item.jsonObject
                val name = case.getValue("name").jsonPrimitive.content
                val t = case.getValue("transform").jsonArray.map { it.jsonPrimitive.float }
                val placement = ExperienceAffinePlacement.resolve(
                    NuxieTextRunGeometry.Transform(t[0], t[1], t[2], t[3], t[4] + 100f, t[5] + 100f), width, height)
                if (case.getValue("corners") == JsonNull) {
                    assertNull(name, placement)
                    continue
                }
                val expected = case.getValue("corners").jsonArray.map { it.jsonPrimitive.float + 100f }
                val root = FrameLayout(controller.get())
                val container = FrameLayout(controller.get())
                var touched: Pair<Float, Float>? = null
                val child = object : View(controller.get()) {
                    override fun onTouchEvent(event: MotionEvent): Boolean {
                        touched = event.x to event.y
                        return true
                    }
                }
                root.addView(container, FrameLayout.LayoutParams(1, 1))
                container.addView(child, FrameLayout.LayoutParams(width, height))
                controller.get().setContentView(root)
                checkNotNull(placement).apply(container, child)
                val spec = View.MeasureSpec.makeMeasureSpec(400, View.MeasureSpec.EXACTLY)
                root.measure(spec, spec)
                root.layout(0, 0, 400, 400)
                val corners = floatArrayOf(0f, 0f, width.toFloat(), 0f,
                    width.toFloat(), height.toFloat(), 0f, height.toFloat())
                child.matrix.mapPoints(corners)
                // Every visible field point remains inside the intermediate parent's touch bounds.
                for (i in corners.indices step 2) {
                    assertTrue("$name parent x", corners[i] >= -0.001f && corners[i] <= container.width + 0.001f)
                    assertTrue("$name parent y", corners[i + 1] >= -0.001f && corners[i + 1] <= container.height + 0.001f)
                }
                container.matrix.mapPoints(corners)
                expected.forEachIndexed { index, coordinate -> assertEquals("$name corner $index", coordinate, corners[index], 0.001f) }
                // Independent fixture corners locate the point (3,4), rather than using the adapter's inverse.
                val x = expected[0] + 0.3f * (expected[2] - expected[0]) + 0.2f * (expected[6] - expected[0])
                val y = expected[1] + 0.3f * (expected[3] - expected[1]) + 0.2f * (expected[7] - expected[1])
                val event = MotionEvent.obtain(0, 0, MotionEvent.ACTION_DOWN, x, y, 0)
                try { assertTrue("$name touch delivery", root.dispatchTouchEvent(event)) } finally { event.recycle() }
                assertEquals("$name touch x", 3f, checkNotNull(touched).first, 0.001f)
                assertEquals("$name touch y", 4f, checkNotNull(touched).second, 0.001f)
            }
        } finally { controller.pause().stop().destroy() }
    }
}
