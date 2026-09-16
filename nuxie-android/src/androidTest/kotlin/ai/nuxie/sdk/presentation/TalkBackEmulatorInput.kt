package ai.nuxie.sdk.presentation

import android.annotation.TargetApi
import android.app.UiAutomation
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.Process
import android.os.SystemClock
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals

/** Hardware input for the opt-in TalkBack probe on a rooted 64-bit API 34+ emulator. */
@TargetApi(34)
internal class TalkBackEmulatorInput(private val automation: UiAutomation, private val device: String) {
    init {
        require(Build.VERSION.SDK_INT >= 34 && Process.is64Bit())
        require(Regex("/dev/input/event[0-9]+").matches(device))
        val event = device.substringAfterLast('/')
        val name = ParcelFileDescriptor.AutoCloseInputStream(
            automation.executeShellCommand("su 0 cat /sys/class/input/$event/device/name"),
        ).bufferedReader().use { it.readText().trim() }
        // This probe's coordinates use the primary emulator touchscreen's 0..32767 axes.
        require(name == "virtio_input_multi_touch_1") { "Select the primary virtio emulator touchscreen" }
    }

    fun swipeForward() = swipe(startX = 9000, deltaX = 1500)

    fun swipeBackward() = swipe(startX = 24000, deltaX = -1500)

    private fun swipe(startX: Int, deltaX: Int) {
        // Framework-injected events bypassed TalkBack in the Settings control probe.
        // Feed evdev instead, so the ordinary Android accessibility input filter sees the swipe.
        val pipes = automation.executeShellCommandRwe("su 0 tee $device")
        try {
            val expected = ByteArrayOutputStream()
            ParcelFileDescriptor.AutoCloseOutputStream(pipes[1]).use { output ->
                fun event(type: Int, code: Int, value: Int) {
                    val bytes = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN)
                        .putLong(0).putLong(0).putShort(type.toShort()).putShort(code.toShort()).putInt(value).array()
                    expected.write(bytes)
                    output.write(bytes)
                }
                fun absolute(code: Int, value: Int) = event(EV_ABS, code, value)
                fun sync() { event(EV_SYN, SYN_REPORT, 0); output.flush() }
                absolute(ABS_MT_SLOT, 0)
                absolute(ABS_MT_TRACKING_ID, 51)
                absolute(ABS_MT_POSITION_X, startX)
                absolute(ABS_MT_POSITION_Y, 16000)
                absolute(ABS_MT_PRESSURE, 800)
                sync()
                try {
                    for (step in 1..10) {
                        SystemClock.sleep(18)
                        absolute(ABS_MT_POSITION_X, startX + step * deltaX)
                        sync()
                    }
                } finally {
                    absolute(ABS_MT_TRACKING_ID, -1)
                    sync()
                }
            }
            val echoed = ParcelFileDescriptor.AutoCloseInputStream(pipes[0]).use { it.readBytes() }
            val errors = ParcelFileDescriptor.AutoCloseInputStream(pipes[2]).bufferedReader().use { it.readText() }
            assertEquals("Hardware input writer must report no error", "", errors)
            assertArrayEquals("Hardware input writer must consume every event", expected.toByteArray(), echoed)
        } finally {
            pipes.forEach { runCatching { it.close() } }
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
