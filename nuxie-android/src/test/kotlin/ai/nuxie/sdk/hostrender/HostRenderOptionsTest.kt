package ai.nuxie.sdk.hostrender

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class HostRenderOptionsTest {
    @Test
    fun `required paths use deterministic render defaults`() {
        val options = HostRenderOptions.parse(
            arrayOf("--input", "release", "--output", "frames"),
        )

        assertEquals(File("release"), options.inputDirectory)
        assertEquals(File("frames"), options.outputDirectory)
        assertEquals(1, options.frameCount)
        assertEquals(16L, options.stepMillis)
        assertNull(options.size)
    }

    @Test
    fun `explicit fixed-step render settings are parsed`() {
        val options = HostRenderOptions.parse(
            arrayOf(
                "--input", "release",
                "--output", "frames",
                "--frames", "3",
                "--size", "390x844",
                "--step-ms", "20",
            ),
        )

        assertEquals(3, options.frameCount)
        assertEquals(HostRenderSize(390, 844), options.size)
        assertEquals(20L, options.stepMillis)
    }

    @Test
    fun `invalid or incomplete options fail before rendering`() {
        listOf(
            arrayOf("--input", "release"),
            arrayOf("--input", "release", "--output", "frames", "--frames", "0"),
            arrayOf("--input", "release", "--output", "frames", "--frames", "many"),
            arrayOf("--input", "release", "--output", "frames", "--size", "390"),
            arrayOf("--input", "release", "--output", "frames", "--step-ms", "-1"),
            arrayOf("--input", "release", "--output", "frames", "--step-ms", "soon"),
        ).forEach { args ->
            assertThrows(IllegalArgumentException::class.java) {
                HostRenderOptions.parse(args)
            }
        }
    }
}
