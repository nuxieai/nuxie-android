package ai.nuxie.sdk.runtime

import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class AndroidImageDecoderDeviceTest {
    @Test fun indexedPNGIsPremultipliedOnce() {
        // Exact published PNG: straight (0,255,0,180), 512 by 512.
        val decoded = decode("translucent-indexed-green.png", 512 * 512 * 4L)
        assertEquals(512, decoded.width)
        assertEquals(512, decoded.height)
        assertEquals(512 * 4, decoded.rowBytes)
        val offset = (256 * 512 + 256) * 4
        val pixel = decoded.pixels.copyOfRange(offset, offset + 4)
        assertArrayEquals(byteArrayOf(0, 180.toByte(), 0, 180.toByte()), pixel)
        println("Indexed PNG decoded RGBA: ${pixel.map { it.toInt() and 0xff }}")
    }

    @Test fun indexedAndTruecolorPNGsPreserveChannelOrder() {
        // Opaque (17,34,51,255), then straight (255,64,32,180).
        for (name in listOf("translucent-rgba-channels.png", "translucent-indexed-channels.png")) {
            val decoded = decode(name, 8)
            assertEquals(2, decoded.width)
            assertEquals(1, decoded.height)
            assertEquals(8, decoded.rowBytes)
            assertArrayEquals(
                byteArrayOf(17, 34, 51, 255.toByte(), 180.toByte(), 45, 23, 180.toByte()),
                decoded.pixels,
            )
        }
    }

    private fun decode(name: String, maximumDecodedBytes: Long): DecodedImage {
        val encoded = InstrumentationRegistry.getInstrumentation().context.assets
            .open(name).use { it.readBytes() }
        val decoded = AndroidImageDecoder.decode(encoded, 512, maximumDecodedBytes)
        assertNotNull(decoded)
        return requireNotNull(decoded)
    }
}
