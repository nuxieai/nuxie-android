package ai.nuxie.sdk.presentation

import android.annotation.TargetApi
import android.app.UiAutomation
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.Process
import android.os.SystemClock
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals

/** Hardware input for the opt-in TalkBack probe on a rooted 64-bit API 34+ emulator. */
@TargetApi(34)
internal class TalkBackEmulatorInput(private val automation: UiAutomation, private val device: String) {
    init {
        require(Build.VERSION.SDK_INT >= 34 && Process.is64Bit())
        val displays = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext
            .getSystemService(android.hardware.display.DisplayManager::class.java)
        require(displays.getDisplay(android.view.Display.DEFAULT_DISPLAY)?.rotation == android.view.Surface.ROTATION_0) {
            "This hardware-input probe requires natural portrait orientation"
        }
        require(Regex("/dev/input/event[0-9]+").matches(device))
        val event = device.substringAfterLast('/')
        val name = ParcelFileDescriptor.AutoCloseInputStream(
            automation.executeShellCommand("su 0 cat /sys/class/input/$event/device/name"),
        ).bufferedReader().use { it.readText().trim() }
        // This probe's coordinates use the primary emulator touchscreen's 0..32767 axes.
        require(name == "virtio_input_multi_touch_1") { "Select the primary virtio emulator touchscreen" }
    }

    fun awaitAdjustableReadingControl() {
        // TalkBack changes its contextual reading control after publishing accessibility focus.
        // Observe that service-owned state before sending the slider gesture; do not mutate it.
        val deadline = SystemClock.uptimeMillis() + 5_000
        val expected = "<string name=\"pref_current_selector_setting_key\">ADJUSTABLE_WIDGET</string>"
        var lastMode: String? = null
        do {
            val preferences = ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand(
                "su 0 cat /data/user_de/0/com.google.android.marvin.talkback/shared_prefs/com.google.android.marvin.talkback_preferences.xml",
            )).bufferedReader().use { it.readText() }
            lastMode = Regex("<string name=\"pref_current_selector_setting_key\">([A-Z_]+)</string>").find(preferences)?.groupValues?.get(1)
            if (expected in preferences) return
            SystemClock.sleep(20)
        } while (SystemClock.uptimeMillis() < deadline)
        val focused = automation.rootInActiveWindow?.findFocus(android.view.accessibility.AccessibilityNodeInfo.FOCUS_ACCESSIBILITY)
        error("TalkBack must select its adjustable reading control; mode=$lastMode class=${focused?.className} enabled=${focused?.isEnabled} actions=${focused?.actionList?.map { it.id }}")
    }

    fun swipeForward() = swipe(startX = 9000, startY = 16000, deltaX = 1500, deltaY = 0)

    fun swipeBackward() = swipe(startX = 24000, startY = 16000, deltaX = -1500, deltaY = 0)

    fun swipeUp() = swipe(startX = 16000, startY = 24000, deltaX = 0, deltaY = -1500)

    fun swipeDown() = swipe(startX = 16000, startY = 9000, deltaX = 0, deltaY = 1500)

    private fun swipe(startX: Int, startY: Int, deltaX: Int, deltaY: Int) {
        automation.executeAndWaitForEvent(
            { sendSwipe(startX, startY, deltaX, deltaY) },
            { it.eventType == android.view.accessibility.AccessibilityEvent.TYPE_TOUCH_INTERACTION_END },
            5_000,
        ).recycle()
    }

    fun doubleTap(x: Int = 16000, y: Int = 16000) {
        automation.executeAndWaitForEvent(
            { writeGesture {
                contact(x, y) { SystemClock.sleep(50) }
                SystemClock.sleep(80)
                contact(x, y) { SystemClock.sleep(50) }
            } },
            { it.eventType == android.view.accessibility.AccessibilityEvent.TYPE_TOUCH_INTERACTION_END },
            5_000,
        ).recycle()
    }

    private fun sendSwipe(startX: Int, startY: Int, deltaX: Int, deltaY: Int) = writeGesture {
        contact(startX, startY) {
            for (step in 1..10) {
                SystemClock.sleep(18)
                move(startX + step * deltaX, startY + step * deltaY)
            }
        }
    }

    private fun writeGesture(gesture: TouchWriter.() -> Unit) {
        // Feed evdev so Android's accessibility input filter receives real hardware events.
        val pipes = automation.executeShellCommandRwe("su 0 tee $device")
        try {
            val expected = ByteArrayOutputStream()
            ParcelFileDescriptor.AutoCloseOutputStream(pipes[1]).use { output ->
                TouchWriter(output, expected).gesture()
            }
            val echoed = ParcelFileDescriptor.AutoCloseInputStream(pipes[0]).use { it.readBytes() }
            val errors = ParcelFileDescriptor.AutoCloseInputStream(pipes[2]).bufferedReader().use { it.readText() }
            assertEquals("Hardware input writer must report no error", "", errors)
            assertArrayEquals("Hardware input writer must consume every event", expected.toByteArray(), echoed)
        } finally {
            pipes.forEach { runCatching { it.close() } }
        }
    }

    private class TouchWriter(private val output: OutputStream, private val expected: ByteArrayOutputStream) {
        private var trackingId = 51

        fun contact(x: Int, y: Int, gesture: TouchWriter.() -> Unit) {
            try {
                event(EV_ABS, ABS_MT_SLOT, 0)
                event(EV_ABS, ABS_MT_TRACKING_ID, trackingId++)
                event(EV_ABS, ABS_MT_PRESSURE, 800)
                move(x, y)
                gesture()
            } finally {
                event(EV_ABS, ABS_MT_TRACKING_ID, -1)
                sync()
            }
        }

        fun move(x: Int, y: Int) {
            event(EV_ABS, ABS_MT_POSITION_X, x)
            event(EV_ABS, ABS_MT_POSITION_Y, y)
            sync()
        }

        private fun sync() { event(EV_SYN, SYN_REPORT, 0); output.flush() }

        private fun event(type: Int, code: Int, value: Int) {
            val bytes = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN)
                .putLong(0).putLong(0).putShort(type.toShort()).putShort(code.toShort()).putInt(value).array()
            expected.write(bytes)
            output.write(bytes)
        }
    }

    private companion object {
        const val EV_SYN = 0
        const val SYN_REPORT = 0
        const val EV_ABS = 3
        const val ABS_MT_SLOT = 47
        const val ABS_MT_POSITION_X = 53
        const val ABS_MT_POSITION_Y = 54
        const val ABS_MT_TRACKING_ID = 57
        const val ABS_MT_PRESSURE = 58
    }
}
