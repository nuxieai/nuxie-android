package ai.nuxie.sdk.experiences

import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.fonts.Font
import android.graphics.fonts.FontFamily
import java.nio.ByteBuffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SystemFontProviderTest {
    @Test fun `selected device faces produce independently decodable standalone fonts for all weights`() {
        for (weight in 100..900 step 100) {
            val candidate = SystemFontProvider.prepare(SystemFontRequirement("system-$weight", weight, "normal"))
            val buffer = ByteBuffer.allocateDirect(candidate.bytes.size).apply {
                put(candidate.bytes)
                flip()
            }
            val font = Font.Builder(buffer).setWeight(weight).build()
            val typeface = Typeface.CustomFallbackBuilder(FontFamily.Builder(font).build()).build()
            val paint = Paint().apply { this.typeface = typeface; textSize = 32f }
            assertTrue("Standalone face must contain Latin glyphs at weight $weight", paint.hasGlyph("a"))
            assertTrue(paint.measureText("ag") > 0f)
            assertEquals(0, font.ttcIndex)
        }
    }

    @Test fun `unsupported authored requests fail with a stable typed reason`() {
        for (request in listOf(
            SystemFontRequirement("invalid", 450, "normal"),
            SystemFontRequirement("invalid", 400, "italic"),
            SystemFontRequirement("invalid", 0, "normal"),
        )) {
            val error = assertThrows(SystemFontException::class.java) { SystemFontProvider.prepare(request) }
            assertEquals(SystemFontException.Reason.UNSUPPORTED_REQUEST, error.reason)
        }
    }

    @Test
    @Config(sdk = [23])
    @GraphicsMode(GraphicsMode.Mode.LEGACY)
    fun `unavailable platform extraction reports a typed failure without changing SDK minimum`() {
        val error = assertThrows(SystemFontException::class.java) {
            SystemFontProvider.prepare(SystemFontRequirement("system", 400, "normal"))
        }
        assertEquals(SystemFontException.Reason.FACE_UNAVAILABLE, error.reason)
    }
}
