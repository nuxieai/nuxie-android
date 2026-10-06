package ai.nuxie.sdk.runtime

import org.junit.Assert.*
import org.junit.Test

class NuxieFocusInputsTest {
    @Test fun fiveKindsPreserveUtf8AndKeyFlags() {
        val inputs = encodeFocusInputs(listOf(NuxieFocusInput.Next, NuxieFocusInput.Previous,
            NuxieFocusInput.Clear, NuxieFocusInput.Key(65, 9, false, true),
            NuxieFocusInput.Text("Aé😀")))
        assertEquals(listOf(0, 1, 2, 3, 4), inputs.map { it.kind })
        assertEquals(65, inputs[3].code)
        assertEquals(9, inputs[3].modifiers)
        assertFalse(inputs[3].pressed)
        assertTrue(inputs[3].repeated)
        assertArrayEquals(byteArrayOf(65, -61, -87, -16, -97, -104, -128), inputs[4].text)
    }

    @Test fun limitsAcceptTheirBoundariesAndRejectOversizedInputs() {
        assertEquals(4_096, encodeFocusInputs(List(4_096) { NuxieFocusInput.Clear }).size)
        assertEquals(4, encodeFocusInputs(List(4) { NuxieFocusInput.Text("a".repeat(1_048_576)) }).size)
        listOf(
            List(4_097) { NuxieFocusInput.Clear },
            listOf(NuxieFocusInput.Text("a".repeat(1_048_577))),
            List(5) { NuxieFocusInput.Text("a".repeat(1_048_576)) },
            listOf(NuxieFocusInput.Key(65, 16, true, false)),
            listOf(NuxieFocusInput.Key(-1, 0, true, false)),
            listOf(NuxieFocusInput.Key(65_536, 0, true, false)),
        ).forEach { inputs ->
            assertThrows(IllegalArgumentException::class.java) { encodeFocusInputs(inputs) }
        }
    }
}
