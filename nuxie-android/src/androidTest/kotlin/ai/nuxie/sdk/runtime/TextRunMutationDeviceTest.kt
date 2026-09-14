package ai.nuxie.sdk.runtime

import android.util.Base64
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class TextRunMutationDeviceTest {
    @Test
    fun textRunWritesPreserveUTF8AndPropagateNativeFailures() {
        // Same native fixture as iOS ecab11b2:
        // Tests/NuxieUnitTests/Fixtures/text_run_apple_seam.riv.base64.
        val encoded = InstrumentationRegistry.getInstrumentation().context.assets
            .open("text_run_apple_seam.riv.base64").use { it.readBytes() }
        val runtime = NuxieRuntime.shared
        assertTrue("Native runtime must load", runtime.isAvailable)
        val renderer = checkNotNull(runtime.newAndroidVulkanRenderer(16, 16))
        try {
            val file = checkNotNull(runtime.importFile(renderer, Base64.decode(encoded, Base64.DEFAULT)))
            try {
                val artboard = checkNotNull(file.newArtboard("Root"))
                try {
                    val text = "😀\u0000漢e\u0301"
                    assertTrue(artboard.setTextRun("headline", text))
                    assertFalse(artboard.setTextRun("headline", text))
                    assertTrue(artboard.setTextRun("headline", ""))
                    assertFalse(artboard.setTextRun("headline", ""))
                    assertThrows(NuxieRuntimeCallException::class.java) { artboard.setTextRun("missing", "invalid") }
                    assertFalse(artboard.setTextRun("headline", ""))
                } finally { artboard.close() }
            } finally { file.close() }
        } finally { renderer.close() }
    }
}
