package ai.nuxie.sdk.runtime

import android.graphics.Bitmap
import android.graphics.Color
import java.io.ByteArrayOutputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class AndroidImageDecoderTest {
    @Test
    fun `decodes within request ceilings to tightly packed rgba`() {
        val decoded = AndroidImageDecoder.decode(
            encoded = encodedBitmap(width = 2, height = 1),
            maximumDimension = 2,
            maximumDecodedBytes = 8,
        )

        assertNotNull(decoded)
        assertEquals(2, decoded?.width)
        assertEquals(1, decoded?.height)
        assertEquals(8, decoded?.rowBytes)
        assertEquals(8, decoded?.pixels?.size)
    }

    @Test
    fun `decodes pixels in premultiplied rgba channel order`() {
        // Distinct nonmaximal channels: a G/B swap or ABGR ordering cannot
        // pass. Alpha stays opaque here so the bitmap round-trip is
        // bit-exact; premultiplication rounding is proven directly below.
        val decoded = AndroidImageDecoder.decode(
            encoded = encodedBitmap(
                width = 2,
                height = 1,
                colors = intArrayOf(
                    Color.argb(0xff, 0x11, 0x22, 0x33),
                    Color.argb(0x80, 0xff, 0x00, 0x00),
                ),
            ),
            maximumDimension = 2,
            maximumDecodedBytes = 8,
        )

        assertNotNull(decoded)
        assertArrayEquals(
            byteArrayOf(
                0x11, 0x22, 0x33, 0xff.toByte(),
                0x80.toByte(), 0x00, 0x00, 0x80.toByte(),
            ),
            decoded?.pixels,
        )
    }

    @Test
    fun `premultiplication rounds half up instead of truncating`() {
        assertEquals(0x41, AndroidImageDecoder.premultiply(0x81, 0x80))
        assertEquals(0x01, AndroidImageDecoder.premultiply(0x01, 0x80))
        assertEquals(0x7f, AndroidImageDecoder.premultiply(0xfe, 0x80))
        assertEquals(0x00, AndroidImageDecoder.premultiply(0x00, 0xff))
        assertEquals(0xff, AndroidImageDecoder.premultiply(0xff, 0xff))
    }

    @Test
    fun `rejects an image over the request dimension ceiling`() {
        val encoded = encodedBitmap(width = 2, height = 1)

        assertNull(
            AndroidImageDecoder.decode(
                encoded = encoded,
                maximumDimension = 1,
                maximumDecodedBytes = 8,
            ),
        )
    }

    @Test
    fun `rejects an image over the request decoded byte ceiling`() {
        val encoded = encodedBitmap(width = 2, height = 1)

        assertNull(
            AndroidImageDecoder.decode(
                encoded = encoded,
                maximumDimension = 2,
                maximumDecodedBytes = 7,
            ),
        )
    }

    private fun encodedBitmap(
        width: Int,
        height: Int,
        colors: IntArray = IntArray(width * height),
    ): ByteArray {
        val bitmap = Bitmap.createBitmap(colors, width, height, Bitmap.Config.ARGB_8888)
        return ByteArrayOutputStream().use { output ->
            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
            bitmap.recycle()
            output.toByteArray()
        }
    }
}
