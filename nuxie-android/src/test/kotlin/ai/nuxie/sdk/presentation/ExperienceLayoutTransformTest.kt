package ai.nuxie.sdk.presentation

import ai.nuxie.sdk.fixtures.FixtureRunner
import ai.nuxie.sdk.runtime.NativeSemanticNode
import android.app.Activity
import android.graphics.Rect
import android.view.View
import android.widget.FrameLayout
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import kotlin.math.ceil
import kotlin.math.floor

@RunWith(RobolectricTestRunner::class)
class ExperienceLayoutTransformTest {
    @Test fun `shared vectors map pixels to points and semantic rectangles to the view`() {
        val fixture = Json.parseToJsonElement(FixtureRunner.fixturesRoot()
            .resolve("journeys/planes/runtime-layout-transform.json").readText()).jsonObject
        val controller = Robolectric.buildActivity(Activity::class.java).setup().visible()
        val activity = controller.get()
        val previous = activity.resources.displayMetrics.density
        try {
            for (raw in fixture.getValue("cases").jsonArray) {
                val c = raw.jsonObject
                fun numbers(key: String) = c.getValue(key).jsonArray.map { it.jsonPrimitive.float }
                val view = numbers("view")
                val origin = numbers("origin")
                val pixel = numbers("pixelPoint")
                val expected = numbers("runtimePoint")
                val scale = c.getValue("scale").jsonPrimitive.float
                val bounds = ExperienceArtboardSize(view[0], view[1], origin[0], origin[1])
                val transform = checkNotNull(ExperienceLayoutTransform.create(bounds, view[0] * scale, view[1] * scale, scale))
                assertEquals(expected[0] to expected[1], transform.project(pixel[0], pixel[1]))
                activity.resources.displayMetrics.density = scale
                val parent = FrameLayout(activity)
                val host = View(activity)
                parent.addView(host)
                activity.setContentView(parent)
                val width = (view[0] * scale).toInt()
                val height = (view[1] * scale).toInt()
                parent.layout(0, 0, width, height)
                host.layout(0, 0, width, height)
                val rect = numbers("semanticRect")
                val node = NativeSemanticNode(1, -1, 0, 1, 0, 0, 0, 1,
                    rect[0], rect[1], rect[0] + rect[2], rect[1] + rect[3], "Continue", "", "")
                val actual = checkNotNull(semanticBounds(host, bounds, node)).inHost
                val expectedRect = numbers("viewRect")
                assertEquals(c.getValue("name").jsonPrimitive.content,
                    Rect(floor(expectedRect[0] * scale).toInt(), floor(expectedRect[1] * scale).toInt(),
                        ceil((expectedRect[0] + expectedRect[2]) * scale).toInt(),
                        ceil((expectedRect[1] + expectedRect[3]) * scale).toInt()), actual)
            }
        } finally {
            activity.resources.displayMetrics.density = previous
            controller.pause().stop().destroy()
        }
    }

    @Test fun `invalid view or density cannot project input`() {
        val bounds = ExperienceArtboardSize(393f, 852f)
        for (bad in listOf(0f, -1f, Float.NaN, Float.POSITIVE_INFINITY)) {
            assertNull(ExperienceLayoutTransform.create(bounds, bad, 852f, 3f))
            assertNull(ExperienceLayoutTransform.create(bounds, 393f, bad, 3f))
            assertNull(ExperienceLayoutTransform.create(bounds, 393f, 852f, bad))
        }
        val transform = checkNotNull(ExperienceLayoutTransform.create(bounds, 1179f, 2556f, 3f))
        assertNull(transform.project(Float.NaN, 300f))
        assertNull(transform.project(120f, Float.POSITIVE_INFINITY))
    }
}
