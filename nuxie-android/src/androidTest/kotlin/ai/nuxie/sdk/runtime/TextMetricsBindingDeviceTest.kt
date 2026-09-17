package ai.nuxie.sdk.runtime

import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.float
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TextMetricsBindingDeviceTest {
    @Test
    fun publishedFontScalePolicyAfterOneStep() {
        val assets = InstrumentationRegistry.getInstrumentation().context.assets
        val directory = "runtime/font-scale-policy"
        fun document(name: String) = assets.open("$directory/$name").bufferedReader().use {
            Json.parseToJsonElement(it.readText()).jsonObject
        }
        val contract = document("cases.json")
        val cases = contract.getValue("cases").jsonArray
        val fontHash = document("provenance.json").getValue("fontSha256").jsonPrimitive.content
        val bytes = assets.open("$directory/screen.riv").use { it.readBytes() }
        val fontBytes = assets.open("$directory/$fontHash.otf").use { it.readBytes() }
        val runtime = NuxieRuntime.shared
        assertTrue("Pinned public runtime must load", runtime.isAvailable)
        val expectedAssets = checkNotNull(runtime.inspectFileAssets(bytes))
        val font = expectedAssets.single { it.kind == FileAssetKind.FONT }
        val renderer = checkNotNull(runtime.newAndroidVulkanRenderer(390, 844))
        try {
            val file = checkNotNull(runtime.importFile(renderer, bytes, expectedAssets, mapOf(font.ordinal to fontBytes)))
            try {
                val artboard = checkNotNull(file.newArtboard("Paywall"))
                try {
                    artboard.bindDefaultViewModel("metrics")
                    val player = checkNotNull(artboard.newPlayer())
                    try {
                        player.enableSemantics()
                        var baseline: ByteArray? = null
                        val runNames = listOf("bound Run", "fixed Run", "natural Run")
                        cases.forEachIndexed { index, item ->
                            val values = item.jsonObject
                            val scale = values.getValue("scale").jsonPrimitive.float
                            assertTrue(artboard.setDefaultViewModelValue(
                                contract.getValue("fontScalePath").jsonPrimitive.content,
                                NuxieViewModelScalarValue.NumberValue(scale.toDouble()),
                            ))
                            val step = player.stepTyped(elapsedSeconds = 0.0, textRunNames = runNames)
                            val captured = step.textGeometry as NuxieTextGeometryCapture.Captured
                            assertEquals(runNames.toSet(), captured.fields.keys)
                            assertEquals(1, captured.fields.values.map { it.renderRevision }.toSet().size)
                            val snapshot = checkNotNull(artboard.defaultViewModelSnapshot())
                            values.getValue("expected").jsonObject.forEach { (name, expected) ->
                                assertEquals("$name at scale $scale", expected.jsonPrimitive.float,
                                    checkNotNull(snapshot.resolveGeometryNumber(name)), 0.0001f)
                            }
                            runNames.forEach { name ->
                                val size = if (name == "fixed Run") 18f else 18f * scale
                                assertEquals(1929f / 2048f * size,
                                    checkNotNull(captured.fields.getValue(name).firstBaseline), 0.001f)
                            }
                            val frame = renderer.renderToCpuFrame(player, 0xff000000.toInt(), true)
                            val semantics = player.captureSemantics()
                            try {
                                captured.fields.values.forEach {
                                    assertEquals(semantics.tree.renderRevision.toULong(), it.renderRevision)
                                }
                            } finally { semantics.close() }
                            val first = baseline
                            if (first == null) {
                                baseline = frame.rgba
                            } else {
                                val start = 264 * 390 * 4
                                val end = 484 * 390 * 4
                                assertArrayEquals("Fixed field pixels stay unchanged", first.copyOfRange(start, end),
                                    frame.rgba.copyOfRange(start, end))
                                if (index == cases.lastIndex) {
                                    assertArrayEquals("Scale reset restores the frame", first, frame.rgba)
                                } else {
                                    assertFalse("System scaling changes pixels", first.contentEquals(frame.rgba))
                                }
                            }
                        }
                    } finally { player.close() }
                } finally { artboard.close() }
            } finally { file.close() }
        } finally { renderer.close() }
    }

    @Test
    fun publishedMetricsReverseBindAfterOneStepAndChangeRenderedText() {
        val assets = InstrumentationRegistry.getInstrumentation().context.assets
        val directory = "runtime/font-metrics-binding"
        val bytes = assets.open("$directory/screen.riv").use { it.readBytes() }
        val contract = assets.open("$directory/expectations.json").bufferedReader().use {
            Json.parseToJsonElement(it.readText()).jsonObject
        }
        val cases = contract.getValue("cases").jsonArray
        val expectedGeometry = contract.getValue("geometry").jsonArray.map { it.jsonObject }
        val runNames = expectedGeometry.map { it.getValue("runName").jsonPrimitive.content }
        var retainedGeometry: NuxieTextRunGeometry? = null
        val runtime = NuxieRuntime.shared
        assertTrue("Pinned native runtime must load", runtime.isAvailable)
        val expectedAssets = checkNotNull(runtime.inspectFileAssets(bytes))
        val font = expectedAssets.single { it.kind == FileAssetKind.FONT }
        val fontBytes = assets.open(
            "$directory/2898476918b21c3f9b5ba22e86853c6d63b544f92da277a92533011a28c93af5.otf",
        ).use { it.readBytes() }
        val renderer = checkNotNull(runtime.newAndroidVulkanRenderer(390, 844))
        try {
            val file = checkNotNull(runtime.importFile(renderer, bytes, expectedAssets, mapOf(font.ordinal to fontBytes)))
            try {
                val artboard = checkNotNull(file.newArtboard("Paywall"))
                try {
                    artboard.bindDefaultViewModel("metrics")
                    val player = checkNotNull(artboard.newPlayer())
                    try {
                        player.enableSemantics()
                        var baseline: ByteArray? = null
                        cases.forEachIndexed { index, item ->
                            val values = item.jsonObject
                            val size = values.getValue("fontSize").jsonPrimitive.float
                            val lineHeight = values.getValue("lineHeight").jsonPrimitive.float
                            assertTrue(artboard.setDefaultViewModelValue(
                                "requestedFontSize", NuxieViewModelScalarValue.NumberValue(size.toDouble()),
                            ))
                            assertTrue(artboard.setDefaultViewModelValue(
                                "requestedLineHeight", NuxieViewModelScalarValue.NumberValue(lineHeight.toDouble()),
                            ))
                            val step = player.stepTyped(elapsedSeconds = 0.0, textRunNames = runNames)
                            val captured = step.textGeometry as NuxieTextGeometryCapture.Captured
                            assertEquals(runNames.toSet(), captured.fields.keys)
                            val snapshot = checkNotNull(artboard.defaultViewModelSnapshot())
                            assertEquals(size, checkNotNull(snapshot.resolveGeometryNumber("observedFontSize")), 0f)
                            assertEquals(lineHeight, checkNotNull(snapshot.resolveGeometryNumber("observedLineHeight")), 0f)
                            assertEquals(18f, checkNotNull(snapshot.resolveGeometryNumber("fixedFontSize")), 0f)
                            assertEquals(24f, checkNotNull(snapshot.resolveGeometryNumber("fixedLineHeight")), 0f)
                            for ((fieldIndex, expected) in expectedGeometry.withIndex()) {
                                val geometry = captured.fields.getValue(runNames[fieldIndex])
                                assertTrue(geometry.renderRevision > 0uL)
                                val layout = checkNotNull(geometry.layout)
                                val x = expected.getValue("x").jsonPrimitive.float
                                val y = expected.getValue("y").jsonPrimitive.float
                                assertEquals(NuxieTextRunGeometry.Transform(1f, 0f, 0f, 1f, x, y), layout.transform)
                                assertEquals(NuxieTextRunGeometry.Bounds(0f, 0f,
                                    expected.getValue("width").jsonPrimitive.float,
                                    expected.getValue("height").jsonPrimitive.float), layout.bounds)
                                assertEquals(x, geometry.worldTransform.tx, 0.001f)
                                assertEquals(y + 1.0458984f, geometry.worldTransform.ty, 0.001f)
                                val effectiveSize = if (fieldIndex == 0) size else 18f
                                val prefix = expected.getValue("path").jsonPrimitive.content
                                assertEquals(effectiveSize, checkNotNull(snapshot.resolveGeometryNumber("$prefix/fontSize")), 0f)
                                assertEquals(if (fieldIndex == 0) lineHeight else 24f,
                                    checkNotNull(snapshot.resolveGeometryNumber("$prefix/lineHeight")), 0f)
                                assertEquals(1929f / 2048f * effectiveSize, checkNotNull(geometry.firstBaseline), 0.001f)
                                if (index == 0 && fieldIndex == 0) retainedGeometry = geometry
                            }
                            val frame = renderer.renderToCpuFrame(player, 0xff000000.toInt(), true)
                            val semantics = player.captureSemantics()
                            try {
                                captured.fields.values.forEach {
                                    assertEquals(semantics.tree.renderRevision.toULong(), it.renderRevision)
                                }
                            } finally { semantics.close() }
                            assertEquals(390, frame.width)
                            assertEquals(844, frame.height)
                            val first = baseline
                            if (first == null) {
                                baseline = frame.rgba
                            } else {
                                val fixedOffset = 264 * 390 * 4
                                assertArrayEquals("Fixed text must not scale", first.copyOfRange(fixedOffset, first.size),
                                    frame.rgba.copyOfRange(fixedOffset, frame.rgba.size))
                                if (index == cases.lastIndex) {
                                    assertArrayEquals("Restoring metrics restores the frame", first, frame.rgba)
                                } else {
                                    assertFalse("Bound metrics must change actual pixels", first.contentEquals(frame.rgba))
                                }
                            }
                        }
                    } finally { player.close() }
                } finally { artboard.close() }
            } finally { file.close() }
        } finally { renderer.close() }
        val retained = checkNotNull(retainedGeometry)
        assertEquals(24f, checkNotNull(retained.layout).transform.tx, 0f)
        assertEquals(1929f / 2048f * 18f, checkNotNull(retained.firstBaseline), 0.001f)
    }
}
