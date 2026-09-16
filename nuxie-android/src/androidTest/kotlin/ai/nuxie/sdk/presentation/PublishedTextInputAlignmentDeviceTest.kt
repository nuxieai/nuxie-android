package ai.nuxie.sdk.presentation

import ai.nuxie.sdk.runtime.*
import android.content.Intent
import android.view.View
import android.widget.EditText
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** Uses published runtime geometry and the exact external font consumed by its renderer. */
class PublishedTextInputAlignmentDeviceTest {
    @Test fun publishedFontAndNativeEditorShareThePresentedFirstBaseline() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val assets = instrumentation.context.assets
        val directory = "runtime/font-metrics-binding"
        fun json(name: String) = assets.open("$directory/$name").bufferedReader().use {
            Json.parseToJsonElement(it.readText()).jsonObject
        }
        val contract = json("expectations.json")
        val expected = contract.getValue("geometry").jsonArray
        data class MetricsFrame(val snapshot: NuxieViewModelSnapshot, val geometry: NuxieTextGeometryCapture.Captured,
            val fontSize: Float, val lineHeight: Float)
        val metricFrames = mutableListOf<MetricsFrame>()
        val metadata = json("report.json").getValue("builtPackageMetadata").jsonObject.getValue("textInputs").jsonArray
        val bytes = assets.open("$directory/screen.riv").use { it.readBytes() }
        val fontBytes = assets.open("$directory/2898476918b21c3f9b5ba22e86853c6d63b544f92da277a92533011a28c93af5.otf")
            .use { it.readBytes() }
        val runtime = NuxieRuntime.shared
        assertTrue(runtime.isAvailable)
        val catalog = checkNotNull(runtime.inspectFileAssets(bytes))
        val font = catalog.single { it.kind == FileAssetKind.FONT }
        val renderer = checkNotNull(runtime.newAndroidVulkanRenderer(390, 844))
        val snapshot: NuxieViewModelSnapshot
        val capture: NuxieTextGeometryCapture.Captured
        val blankCapture: NuxieTextGeometryCapture.Captured
        val blankSnapshot: NuxieViewModelSnapshot
        try {
            val file = checkNotNull(runtime.importFile(renderer, bytes, catalog, mapOf(font.ordinal to fontBytes)))
            try {
                val artboard = checkNotNull(file.newArtboard("Paywall"))
                try {
                    artboard.bindDefaultViewModel("metrics")
                    val player = checkNotNull(artboard.newPlayer())
                    try {
                        capture = player.stepTyped(elapsedSeconds = 0.0,
                            textRunNames = expected.map { it.jsonObject.getValue("runName").jsonPrimitive.content })
                            .textGeometry as NuxieTextGeometryCapture.Captured
                        snapshot = checkNotNull(artboard.defaultViewModelSnapshot())
                        val frame = renderer.renderToCpuFrame(player, 0xff000000.toInt(), true)
                        assertEquals(390, frame.width)
                        assertEquals(844, frame.height)
                        for (item in contract.getValue("cases").jsonArray) {
                            val size = item.jsonObject.getValue("fontSize").jsonPrimitive.float
                            val height = item.jsonObject.getValue("lineHeight").jsonPrimitive.float
                            assertTrue(artboard.setDefaultViewModelValue("requestedFontSize", NuxieViewModelScalarValue.NumberValue(size.toDouble())))
                            assertTrue(artboard.setDefaultViewModelValue("requestedLineHeight", NuxieViewModelScalarValue.NumberValue(height.toDouble())))
                            val step = player.stepTyped(elapsedSeconds = 0.0, textRunNames = capture.fields.keys.toList())
                            val metricsSnapshot = checkNotNull(artboard.defaultViewModelSnapshot())
                            renderer.renderToCpuFrame(player, 0xff000000.toInt(), true)
                            metricFrames += MetricsFrame(metricsSnapshot, step.textGeometry as NuxieTextGeometryCapture.Captured, size, height)
                        }
                        expected.forEach { field ->
                            assertTrue(artboard.setTextRun(field.jsonObject.getValue("runName").jsonPrimitive.content, ""))
                        }
                        blankCapture = player.stepTyped(elapsedSeconds = 0.0, textRunNames = capture.fields.keys.toList())
                            .textGeometry as NuxieTextGeometryCapture.Captured
                        blankSnapshot = checkNotNull(artboard.defaultViewModelSnapshot())
                        blankCapture.fields.values.forEach { assertNull(it.firstBaseline) }
                        renderer.renderToCpuFrame(player, 0xff000000.toInt(), true)
                    } finally { player.close() }
                } finally { artboard.close() }
            } finally { file.close() }
        } finally { renderer.close() }
        val fontFile = File.createTempFile("nuxie-published-alignment-", ".otf", instrumentation.targetContext.cacheDir)
        fontFile.writeBytes(fontBytes)
        val activity = instrumentation.startActivitySync(Intent(instrumentation.targetContext,
            SurfaceCompatibilityHostActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        var overlay: ExperienceTextInputOverlay? = null
        try {
            instrumentation.runOnMainSync {
                val inputs = metadata.mapIndexed { index, item ->
                    val record = item.jsonObject
                    val style = record.getValue("style").jsonObject
                    fun string(key: String) = style.getValue(key).jsonPrimitive.content
                    val prefix = expected[index].jsonObject.getValue("path").jsonPrimitive.content
                    ExperienceTextInput(record.getValue("viewNodeId").jsonPrimitive.content,
                        record.getValue("riveTextRunName").jsonPrimitive.content,
                        record.getValue("value").jsonPrimitive.content, null, null, null, false, true, null,
                        listOf("x", "y", "width", "height", "rotation", "scaleX", "scaleY").associate { "${it}Path" to "$prefix/$it" },
                        ExperienceTextInput.Style(string("fontFamily"), string("fontWeight"), false,
                            style.getValue("fontSize").jsonPrimitive.float, style.getValue("lineHeight").jsonPrimitive.float,
                            0f, style.getValue("color").jsonPrimitive.long.toInt(), string("fontAssetRiveUniqueName"), "left"))
                }
                val current = ExperienceTextInputOverlay(activity, ExperienceArtboardSize(390f, 844f), inputs,
                    inputs.associate { it.style.fontAssetName to fontFile }, { _, _, _, done -> done(Result.success(Unit)) }, { throw it })
                overlay = current
                activity.setContentView(current)
                fun layout() {
                    current.measure(View.MeasureSpec.makeMeasureSpec(390, View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec(844, View.MeasureSpec.EXACTLY))
                    current.layout(0, 0, 390, 844)
                }
                layout()
                current.update(snapshot, capture)
                layout()
                inputs.forEach { input ->
                    val editor = current.findViewWithTag<EditText>("nuxie-text-input-${input.id}")
                    assertEquals(View.VISIBLE, editor.visibility)
                    val point = floatArrayOf(0f, editor.baseline.toFloat())
                    editor.matrix.mapPoints(point)
                    (editor.parent as View).matrix.mapPoints(point)
                    val geometry = capture.fields.getValue(input.runName)
                    val expectedY = geometry.contentTransform.ty + geometry.contentTransform.d * checkNotNull(geometry.firstBaseline)
                    assertEquals("${input.id} native baseline vs published text", expectedY, point[1], 0.5f)
                    val baselineBefore = editor.baseline
                    editor.setSelection(1, 4)
                    val connection = checkNotNull(editor.onCreateInputConnection(android.view.inputmethod.EditorInfo()))
                    assertTrue(connection.setComposingRegion(1, 4))
                    val cursorBefore = android.graphics.RectF()
                    val path = android.graphics.Path()
                    editor.layout.getCursorPath(1, path, editor.text)
                    path.computeBounds(cursorBefore, true)
                    current.update(blankSnapshot, blankCapture)
                    layout()
                    assertEquals("${input.id} blank runtime run retains native baseline", baselineBefore, editor.baseline)
                    assertEquals(1, editor.selectionStart)
                    assertEquals(4, editor.selectionEnd)
                    assertEquals(1, android.view.inputmethod.BaseInputConnection.getComposingSpanStart(editor.text))
                    assertEquals(4, android.view.inputmethod.BaseInputConnection.getComposingSpanEnd(editor.text))
                    val cursorAfter = android.graphics.RectF()
                    path.reset()
                    editor.layout.getCursorPath(1, path, editor.text)
                    path.computeBounds(cursorAfter, true)
                    assertEquals("${input.id} caret geometry stays stable", cursorBefore, cursorAfter)
                    current.update(snapshot, capture)
                    layout()
                }
                for (frame in metricFrames) {
                    current.update(frame.snapshot, frame.geometry)
                    layout()
                    inputs.forEachIndexed { index, input ->
                        val editor = current.findViewWithTag<EditText>("nuxie-text-input-${input.id}")
                        assertEquals(if (index == 0) frame.fontSize else 18f, editor.textSize, 0.001f)
                        val captured = frame.geometry.fields.getValue(input.runName)
                        val point = floatArrayOf(0f, editor.baseline.toFloat())
                        editor.matrix.mapPoints(point)
                        (editor.parent as View).matrix.mapPoints(point)
                        assertEquals("${input.id} baseline after effective metric change",
                            captured.contentTransform.ty + captured.contentTransform.d * checkNotNull(captured.firstBaseline), point[1], 0.5f)
                        assertEquals(1, editor.selectionStart)
                        assertEquals(4, editor.selectionEnd)
                        assertEquals(1, android.view.inputmethod.BaseInputConnection.getComposingSpanStart(editor.text))
                        assertEquals(4, android.view.inputmethod.BaseInputConnection.getComposingSpanEnd(editor.text))
                    }
                }
            }
        } finally {
            instrumentation.runOnMainSync { overlay?.close(); activity.finish() }
            fontFile.delete()
        }
    }
}
