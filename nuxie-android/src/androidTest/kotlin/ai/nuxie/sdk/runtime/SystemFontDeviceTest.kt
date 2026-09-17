package ai.nuxie.sdk.runtime

import ai.nuxie.sdk.experiences.SystemFontProvider
import ai.nuxie.sdk.experiences.SystemFontRequirement
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer

@SdkSuppress(minSdkVersion = 31)
class SystemFontDeviceTest {
    private fun asset(name: String): ByteArray = InstrumentationRegistry.getInstrumentation()
        .context.assets.open("runtime/system-font-axes/$name").use { it.readBytes() }

    @Test fun authoredRegularAndBoldRenderDistinctDeviceGlyphs() {
        val runtime = NuxieRuntime.shared
        assertTrue(runtime.isAvailable)
        val renderer = checkNotNull(runtime.newAndroidVulkanRenderer(320, 640))
        fun render(name: String, weight: Int): ByteArray {
            val bytes = asset("$name.nux")
            val catalog = checkNotNull(runtime.inspectFileAssets(bytes))
            val font = catalog.single { it.kind == FileAssetKind.FONT }
            val device = SystemFontProvider.prepare(SystemFontRequirement("system", weight, "normal"))
            val file = checkNotNull(runtime.importFile(renderer, bytes, catalog, mapOf(font.ordinal to device.bytes)))
            try {
                val artboard = checkNotNull(file.newArtboard("One"))
                try {
                    val player = checkNotNull(artboard.newPlayer())
                    try {
                        player.step(0.0)
                        return renderer.renderToCpuFrame(player, 0xff111111.toInt(), false).rgba
                    } finally { player.close() }
                } finally { artboard.close() }
            } finally { file.close() }
        }
        fun ink(pixels: ByteArray) = (2 until pixels.size step 4).sumOf {
            ((pixels[it].toInt() and 255) - 0x11).coerceAtLeast(0)
        }
        try {
            val regular = render("regular", 400)
            val bold = render("bold", 700)
            assertTrue("Regular scene contains visible glyphs", ink(regular) > 10_000)
            assertTrue("Authored bold increases glyph coverage", ink(bold) > ink(regular))
            assertFalse("Authored weight changes rendered pixels", regular.contentEquals(bold))
            assertArrayEquals("Reserialized production scene preserves rendering", regular, render("optical-baseline", 400))
            val font = SystemFontProvider.prepare(SystemFontRequirement("system", 400, "normal"))
            val optical = render("optical-control", 400)
            val axes = variationAxes(font.bytes)
            if ("opsz" in axes) {
                assertFalse("Supported authored optical size changes glyphs", regular.contentEquals(optical))
            } else {
                assertArrayEquals("Unsupported optical axis preserves the device face", regular, optical)
            }
            android.util.Log.i("SystemFontDeviceTest", "Selected font variation axes: $axes")
        } finally { renderer.close() }
    }

    /** Read the standard SFNT fvar directory independently of the provider's extraction. */
    private fun variationAxes(bytes: ByteArray): Set<String> {
        val data = ByteBuffer.wrap(bytes)
        val count = data.getShort(4).toInt() and 0xffff
        val record = (0 until count).map { 12 + it * 16 }
            .firstOrNull { String(bytes, it, 4, Charsets.US_ASCII) == "fvar" } ?: return emptySet()
        val offset = data.getInt(record + 8)
        val axesOffset = data.getShort(offset + 4).toInt() and 0xffff
        val axesCount = data.getShort(offset + 8).toInt() and 0xffff
        val axesSize = data.getShort(offset + 10).toInt() and 0xffff
        return (0 until axesCount).map {
            String(bytes, offset + axesOffset + it * axesSize, 4, Charsets.US_ASCII)
        }.toSet()
    }

    @Test fun mixedDeviceAndDownloadedFontsRenderAndRequiredFontsRejectInvalidBytes() {
        val runtime = NuxieRuntime.shared
        assertTrue(runtime.isAvailable)
        val bytes = asset("mixed.nux")
        val catalog = checkNotNull(runtime.inspectFileAssets(bytes))
        assertEquals(2, catalog.size)
        val scenes = JSONObject(String(asset("provenance.json"))).getJSONArray("scenes")
        val mixed = (0 until scenes.length()).map(scenes::getJSONObject).single { it.getString("name") == "mixed" }
        val fonts = mixed.getJSONArray("fonts")
        val systemID = (0 until fonts.length()).map(fonts::getJSONObject)
            .single { it.getString("location") == "system" }.getLong("riveAssetId")
        val device = SystemFontProvider.prepare(SystemFontRequirement("system", 400, "normal"))
        val cdn = asset("mixed-cdn.ttf")
        val valid = catalog.associate { it.ordinal to if (it.authoredId == systemID) device.bytes else cdn }
        val renderer = checkNotNull(runtime.newAndroidVulkanRenderer(320, 640))
        try {
            val file = checkNotNull(runtime.importFile(renderer, bytes, catalog, valid))
            try {
                val artboard = checkNotNull(file.newArtboard("One"))
                try {
                    val player = checkNotNull(artboard.newPlayer())
                    try {
                        player.step(0.0)
                        val frame = renderer.renderToCpuFrame(player, 0xff111111.toInt(), false)
                        for (rows in listOf(16 until 112, 144 until 240)) {
                            val ink = rows.sumOf { y -> (0 until 320).sumOf { x ->
                                ((frame.rgba[(y * 320 + x) * 4 + 2].toInt() and 255) - 0x11).coerceAtLeast(0)
                            } }
                            assertTrue("System and CDN rows must both contain visible text", ink > 10_000)
                        }
                    } finally { player.close() }
                } finally { artboard.close() }
            } finally { file.close() }
            for (font in catalog) {
                for (malformed in listOf(false, true)) {
                    val invalid = valid.toMutableMap()
                    if (malformed) invalid[font.ordinal] = "not a font".toByteArray()
                    else invalid.remove(font.ordinal)
                    val imported = runtime.importFile(renderer, bytes, catalog, invalid)
                    imported?.close()
                    assertNull("Required font ${font.name}, malformed=$malformed", imported)
                }
            }
        } finally { renderer.close() }
    }
}
