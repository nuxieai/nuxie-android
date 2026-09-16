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
    fun publishedMetricsReverseBindAfterOneStepAndChangeRenderedText() {
        val assets = InstrumentationRegistry.getInstrumentation().context.assets
        val directory = "runtime/font-metrics-binding"
        val bytes = assets.open("$directory/screen.riv").use { it.readBytes() }
        val contract = assets.open("$directory/expectations.json").bufferedReader().use {
            Json.parseToJsonElement(it.readText()).jsonObject
        }
        val cases = contract.getValue("cases").jsonArray
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
                            player.stepTyped(elapsedSeconds = 0.0)
                            val snapshot = checkNotNull(artboard.defaultViewModelSnapshot())
                            assertEquals(size, checkNotNull(snapshot.resolveGeometryNumber("observedFontSize")), 0f)
                            assertEquals(lineHeight, checkNotNull(snapshot.resolveGeometryNumber("observedLineHeight")), 0f)
                            assertEquals(18f, checkNotNull(snapshot.resolveGeometryNumber("fixedFontSize")), 0f)
                            assertEquals(24f, checkNotNull(snapshot.resolveGeometryNumber("fixedLineHeight")), 0f)
                            for (field in contract.getValue("geometry").jsonArray) {
                                val geometry = field.jsonObject
                                val path = geometry.getValue("path").jsonPrimitive.content
                                for (metric in listOf("x", "y", "width", "height")) {
                                    assertEquals("Artboard geometry $path/$metric", geometry.getValue(metric).jsonPrimitive.float,
                                        checkNotNull(snapshot.resolveGeometryNumber("$path/$metric")), 0.001f)
                                }
                            }
                            val frame = renderer.renderToCpuFrame(player, 0xff000000.toInt(), true)
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
    }
}
