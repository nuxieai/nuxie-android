package ai.nuxie.sdk.runtime

import android.graphics.Bitmap
import java.io.ByteArrayOutputStream
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

    private fun encodedBitmap(width: Int, height: Int): ByteArray {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        return ByteArrayOutputStream().use { output ->
            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
            bitmap.recycle()
            output.toByteArray()
        }
    }
}
