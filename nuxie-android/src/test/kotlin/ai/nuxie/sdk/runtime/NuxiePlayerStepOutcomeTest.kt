package ai.nuxie.sdk.runtime

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
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

    @Test
    fun `number and view-model journal kinds use their selected fields`() {
        val outcome = outcome(
            change(kind = 2, numberValue = 1.25f, integerValue = 91),
            change(kind = 9, integerValue = 92, referencedInstanceId = 73),
        )

        assertEquals(NuxieViewModelValue.Number(1.25f), outcome.viewModelChanges[0].value)
        assertEquals(
            NuxieViewModelValue.ReferencedInstance(73uL),
            outcome.viewModelChanges[1].value,
        )
    }

    @Test
    fun `boolean unsupported and integer-backed journal kinds decode exactly`() {
        val outcome = outcome(
            change(kind = 3, boolValue = true),
            change(kind = 0, integerValue = 99),
            change(kind = 4, integerValue = 0x80402010),
            change(kind = 5, integerValue = 6),
            change(kind = 7, integerValue = 2),
        )

        assertEquals(NuxieViewModelValue.Bool(true), outcome.viewModelChanges[0].value)
        assertEquals(NuxieViewModelValue.Unsupported, outcome.viewModelChanges[1].value)
        assertEquals(NuxieViewModelValue.Integer(0x80402010uL), outcome.viewModelChanges[2].value)
        assertEquals(NuxieViewModelValue.Integer(6uL), outcome.viewModelChanges[3].value)
        assertEquals(NuxieViewModelValue.Integer(2uL), outcome.viewModelChanges[4].value)
    }

    @Test
    fun `malformed journal kind origin and property index are rejected`() {
        assertThrows(IllegalStateException::class.java) {
            outcome(change(kind = 99))
        }
        assertThrows(IllegalStateException::class.java) {
            outcome(change(kind = 2, origin = 99))
        }
        assertThrows(IllegalStateException::class.java) {
            outcome(change(kind = 2, propertyIndex = -1))
        }
    }

    private fun outcome(vararg changes: NativeViewModelChange): NuxiePlayerStepOutcome =
        NativePlayerStepOutcome(
            keepGoing = false,
            events = emptyArray(),
            viewModelChanges = arrayOf(*changes),
        ).toPlayerStepOutcome()

    private fun change(
        kind: Int,
        origin: Int = 0,
        propertyIndex: Long = 0,
        numberValue: Float = 0f,
        integerValue: Long = 0,
        boolValue: Boolean = false,
        referencedInstanceId: Long = 0,
    ) = NativeViewModelChange(
        origin = origin,
        correlationId = 0,
        ownerInstanceId = 0,
        propertyIndex = propertyIndex,
        kind = kind,
        bytesValue = byteArrayOf(),
        numberValue = numberValue,
        integerValue = integerValue,
        boolValue = boolValue,
        referencedInstanceId = referencedInstanceId,
        listItems = longArrayOf(),
    )
}
