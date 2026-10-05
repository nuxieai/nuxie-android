package ai.nuxie.sdk.presentation

import ai.nuxie.sdk.fixtures.FixtureRunner
import ai.nuxie.sdk.runtime.*
import android.app.Activity
import android.view.View
import android.widget.EditText
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ExperienceLayoutTypographyTest {
    @Test fun `shared typography stays point-sized at smaller view extents and fractional density`() {
        val fixture = Json.parseToJsonElement(FixtureRunner.fixturesRoot()
            .resolve("journeys/planes/text-input-typography.json").readText()).jsonObject
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        val activity = controller.get()
        val previous = activity.resources.displayMetrics.density
        try {
            for (density in listOf(1f, 2.625f, 3f)) {
                activity.resources.displayMetrics.density = density
                for (raw in fixture.getValue("cases").jsonArray) {
                    val c = raw.jsonObject
                    fun number(key: String) = c.getValue(key).jsonPrimitive.float
                    val geometryScale = number("geometryScale")
                    val input = ExperienceTextInput("answer", "run", "hello", "answer", null, null,
                        false, true, null, listOf("x", "y", "width", "height", "rotation", "scaleX", "scaleY")
                            .associate { "${it}Path" to it },
                        ExperienceTextInput.Style("sans-serif", "400", false, number("fontSize"), number("lineHeight"),
                            0f, 0xff000000.toInt(), "font", null))
                    val overlay = ExperienceTextInputOverlay(activity, ExperienceArtboardSize(400f, 400f),
                        listOf(input), emptyMap(), { _, _, _, done -> done(Result.success(Unit)) }, { throw it })
                    try {
                        activity.setContentView(overlay)
                        val size = (400 * number("viewSizeScale") * density).toInt()
                        overlay.layout(0, 0, size, size)
                        val transform = NuxieTextRunGeometry.Transform(geometryScale, 0f, 0f, geometryScale, 10f, 10f)
                        val bounds = NuxieTextRunGeometry.Bounds(0f, 0f, 240f, 180f)
                        val snapshot = NuxieViewModelSnapshot.fromNative(NativeViewModelSnapshot(1,
                            arrayOf(NativeViewModelSnapshotInstance(1, 0)),
                            mapOf("x" to 10f, "y" to 10f, "width" to 240f, "height" to 180f,
                                "rotation" to 0f, "scaleX" to geometryScale, "scaleY" to geometryScale).map { (name, value) ->
                                NativeViewModelSnapshotValue(1, 0, name, NuxieViewModelPropertyKind.NUMBER.nativeValue,
                                    byteArrayOf(), 0, value)
                            }.toTypedArray()))
                        overlay.update(snapshot, NuxieTextGeometryCapture.Captured(mapOf("run" to NuxieTextRunGeometry(
                            1uL, transform, transform, bounds, NuxieTextRunGeometry.Layout(transform, bounds), null))))
                        val editor = overlay.findViewWithTag<EditText>("nuxie-text-input-answer")
                        assertEquals(View.VISIBLE, editor.visibility)
                        assertEquals(c.getValue("name").jsonPrimitive.content,
                            number("expectedFontSize"), editor.textSize * geometryScale / density, 0.01f)
                        val expectedBaseline = c.getValue("expectedBaselineDistance")
                        if (expectedBaseline != JsonNull) {
                            assertEquals(expectedBaseline.jsonPrimitive.float,
                                (editor.paint.getFontMetricsInt(null) + editor.lineSpacingExtra) * geometryScale / density, 0.01f)
                        }
                    } finally { overlay.close() }
                }
            }
        } finally {
            activity.resources.displayMetrics.density = previous
            controller.pause().stop().destroy()
        }
    }
}
