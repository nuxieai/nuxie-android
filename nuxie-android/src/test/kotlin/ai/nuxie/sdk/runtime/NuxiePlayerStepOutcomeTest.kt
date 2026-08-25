package ai.nuxie.sdk.runtime

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NuxiePlayerStepOutcomeTest {
    @Test
    fun `copies typed runtime events and view-model journal values`() {
        val native = NativePlayerStepOutcome(
            keepGoing = true,
            events = arrayOf(
                NativeRuntimeEvent(
                    localIndex = 7,
                    coreType = 99,
                    name = "checkout",
                    url = "nuxie://checkout",
                    target = "self",
                    delay = 0.25f,
                    properties = arrayOf(
                        NativeRuntimeEventProperty("amount", 0, 12.5f, false, byteArrayOf(), 0, 0),
                        NativeRuntimeEventProperty("valid", 1, 0f, true, byteArrayOf(), 0, 0),
                        NativeRuntimeEventProperty("raw", 2, 0f, false, byteArrayOf(0xff.toByte()), 0, 0),
                        NativeRuntimeEventProperty("tint", 3, 0f, false, byteArrayOf(), 0x80402010.toInt(), 0),
                        NativeRuntimeEventProperty("state", 4, 0f, false, byteArrayOf(), 0, 3),
                        NativeRuntimeEventProperty("action", 5, 0f, false, byteArrayOf(), 0, 0),
                    ),
                ),
            ),
            viewModelChanges = arrayOf(
                NativeViewModelChange(1, 23, 41, 2, 1, "done".encodeToByteArray(), 0f, 0, false, 0, longArrayOf()),
                NativeViewModelChange(0, 24, 41, 3, 8, byteArrayOf(), 0f, 0, false, 0, longArrayOf(50, 51)),
            ),
        )

        val outcome = native.toPlayerStepOutcome()

        assertTrue(outcome.keepGoing)
        val event = outcome.events.single()
        assertEquals(7, event.localIndex)
        assertEquals(NuxieRuntimeEventPropertyValue.Number(12.5f), event.properties[0].value)
        assertEquals(NuxieRuntimeEventPropertyValue.Bool(true), event.properties[1].value)
        assertArrayEquals(
            byteArrayOf(0xff.toByte()),
            (event.properties[2].value as NuxieRuntimeEventPropertyValue.Bytes).value,
        )
        assertEquals(NuxieRuntimeEventPropertyValue.Color(0x80402010.toInt()), event.properties[3].value)
        assertEquals(NuxieRuntimeEventPropertyValue.Enum(3uL), event.properties[4].value)
        assertEquals(NuxieRuntimeEventPropertyValue.Trigger, event.properties[5].value)

        assertEquals(NuxieViewModelChangeOrigin.RUNTIME, outcome.viewModelChanges[0].origin)
        assertEquals(23uL, outcome.viewModelChanges[0].correlationId)
        assertArrayEquals(
            "done".encodeToByteArray(),
            (outcome.viewModelChanges[0].value as NuxieViewModelValue.Bytes).value,
        )
        assertEquals(
            NuxieViewModelValue.List(listOf(50uL, 51uL)),
            outcome.viewModelChanges[1].value,
        )
    }
}
