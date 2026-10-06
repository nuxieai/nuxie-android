package ai.nuxie.sdk.presentation

import ai.nuxie.sdk.fixtures.FixtureRunner
import ai.nuxie.sdk.runtime.NuxieFocusInput
import android.view.KeyEvent
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ExperienceHardwareKeyTest {
    @Test fun `platform table matches every shared Android key`() {
        val vector = Json.parseToJsonElement(FixtureRunner.fixturesRoot()
            .resolve("input/hardware-keys.json").readText()).jsonObject
        val keys = vector.getValue("keys").jsonArray
        var checked = 0
        keys.forEach { row ->
            val entry = row.jsonObject
            val code = entry.getValue("androidKeyCode").jsonPrimitive.intOrNull
            if (code != null) {
                assertEquals(entry.getValue("key").jsonPrimitive.content,
                    entry.getValue("rive").jsonPrimitive.int, ExperienceHardwareKey.code(code))
                checked++
            }
        }
        assertEquals(117, checked)
        assertNull(ExperienceHardwareKey.code(KeyEvent.KEYCODE_UNKNOWN))
        assertNull(ExperienceHardwareKey.code(Int.MAX_VALUE))
        val flags = vector.getValue("modifiers").jsonObject
        listOf(KeyEvent.META_SHIFT_ON to "shift", KeyEvent.META_CTRL_ON to "control",
            KeyEvent.META_ALT_ON to "alt", KeyEvent.META_META_ON to "meta").forEach { (flag, name) ->
            assertEquals(flags.getValue(name).jsonPrimitive.int,
                ExperienceHardwareKey.modifiers(event(KeyEvent.KEYCODE_A, flags = flag)))
        }
        assertEquals(15, ExperienceHardwareKey.modifiers(event(KeyEvent.KEYCODE_A,
            flags = KeyEvent.META_SHIFT_ON or KeyEvent.META_CTRL_ON or KeyEvent.META_ALT_ON or KeyEvent.META_META_ON)))
        assertEquals(0, ExperienceHardwareKey.modifiers(event(KeyEvent.KEYCODE_A,
            flags = KeyEvent.META_CAPS_LOCK_ON or KeyEvent.META_NUM_LOCK_ON)))
    }

    @Test fun `tab directions key flags repeat and unsupported actions are explicit`() {
        assertEquals(NuxieFocusInput.Next, ExperienceHardwareKey.input(event(KeyEvent.KEYCODE_TAB)))
        assertEquals(NuxieFocusInput.Previous,
            ExperienceHardwareKey.input(event(KeyEvent.KEYCODE_TAB, flags = KeyEvent.META_SHIFT_ON)))
        assertNull(ExperienceHardwareKey.input(event(KeyEvent.KEYCODE_TAB, action = KeyEvent.ACTION_UP)))
        assertEquals(NuxieFocusInput.Key(65, 9, true, true), ExperienceHardwareKey.input(
            event(KeyEvent.KEYCODE_A, repeat = 2, flags = KeyEvent.META_SHIFT_ON or KeyEvent.META_META_ON)))
        assertEquals(NuxieFocusInput.Key(65, 0, false, false), ExperienceHardwareKey.input(
            event(KeyEvent.KEYCODE_A, action = KeyEvent.ACTION_UP, repeat = 2)))
        assertEquals(NuxieFocusInput.Key(340, 1, true, false), ExperienceHardwareKey.input(
            event(KeyEvent.KEYCODE_SHIFT_LEFT, flags = KeyEvent.META_SHIFT_ON)))
        assertNull(ExperienceHardwareKey.input(event(KeyEvent.KEYCODE_A, action = KeyEvent.ACTION_MULTIPLE)))
        assertNull(ExperienceHardwareKey.input(event(KeyEvent.KEYCODE_UNKNOWN)))
    }

    private fun event(code: Int, action: Int = KeyEvent.ACTION_DOWN, repeat: Int = 0, flags: Int = 0) =
        KeyEvent(1, 2, action, code, repeat, flags)
}
