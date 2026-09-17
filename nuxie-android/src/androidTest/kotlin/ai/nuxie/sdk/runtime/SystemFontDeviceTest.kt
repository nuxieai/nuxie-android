package ai.nuxie.sdk.runtime

import ai.nuxie.sdk.experiences.SystemFontProvider
import ai.nuxie.sdk.experiences.SystemFontRequirement
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

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
        } finally { renderer.close() }
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
