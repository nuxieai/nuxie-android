package ai.nuxie.sdk.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class NuxiePlayerInputTest {
    @Test
    fun `encodes bool number and trigger as exact ABI input records`() {
        val encoded = encodePlayerInputs(
            listOf(
                NuxiePlayerInput.Boolean("enabled", true),
                NuxiePlayerInput.Number("progress", 1.25),
                NuxiePlayerInput.Trigger("submit"),
            ),
        )

        assertEquals(
            listOf(
                NativePlayerInput(0, "enabled", true, 0f),
                NativePlayerInput(1, "progress", false, 1.25f),
                NativePlayerInput(2, "submit", false, 0f),
            ),
            encoded,
        )
    }

    @Test
    fun `rejects input records the ABI cannot represent`() {
        assertThrows(IllegalArgumentException::class.java) {
            encodePlayerInputs(listOf(NuxiePlayerInput.Trigger("")))
        }
        assertThrows(IllegalArgumentException::class.java) {
            encodePlayerInputs(listOf(NuxiePlayerInput.Number("progress", Double.NaN)))
        }
        assertThrows(IllegalArgumentException::class.java) {
            encodePlayerInputs(listOf(NuxiePlayerInput.Number("progress", Double.MAX_VALUE)))
        }
    }
}
