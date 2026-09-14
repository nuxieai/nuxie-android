package ai.nuxie.sdk.runtime

import ai.nuxie.sdk.LogLevel
import ai.nuxie.sdk.logging.NuxieLog
import android.os.Process
import android.system.Os
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test

class NativeLoggingDeviceTest {
    @Test fun nativeFailuresObeyPolicyAndLibraryLoadPreservesStderr() {
        val stderrBefore = Os.readlink("/proc/self/fd/2")
        NuxieLog.configure(LogLevel.NONE)
        assertTrue(NuxieRuntimeBridge.isAvailable)
        assertEquals(stderrBefore, Os.readlink("/proc/self/fd/2"))
        val renderer = NuxieRuntimeBridge.nativeRendererNewAndroidVulkan(16, 16)
        assertNotEquals(0L, renderer)
        val marker = "native-logging-${System.nanoTime()}"
        fun mark(phase: String) { android.util.Log.i("NuxieNativeProbe", "$marker-$phase") }
        fun failImport() { assertEquals(0L, NuxieRuntimeBridge.nativeFileNew(renderer, "invalid-riv".encodeToByteArray())) }
        try {
            mark("disabled")
            failImport()
            mark("redacted")
            NuxieLog.configure(LogLevel.WARN)
            failImport()
            mark("raw")
            NuxieLog.configure(LogLevel.WARN, redactSensitiveData = false)
            failImport()
            mark("end")
            val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
            val log = android.os.ParcelFileDescriptor.AutoCloseInputStream(
                automation.executeShellCommand("logcat -d --pid=${Process.myPid()} -v brief Nuxie:V NuxieNativeProbe:V *:S"),
            ).bufferedReader().use { it.readText() }
            fun section(start: String, end: String): String {
                assertTrue("Missing start marker $start", log.contains("$marker-$start"))
                assertTrue("Missing end marker $end", log.contains("$marker-$end"))
                return log.substringAfter("$marker-$start").substringBefore("$marker-$end")
            }
            assertFalse(section("disabled", "redacted").contains("Native runtime call failed"))
            val redacted = section("redacted", "raw")
            assertTrue(redacted.contains("Native runtime call failed operation=file_import_android_vulkan status="))
            assertTrue(redacted.contains("code=<redacted hmac-sha256:"))
            assertTrue(redacted.contains("details=<redacted hmac-sha256:"))
            val raw = section("raw", "end")
            assertTrue(raw.contains("Native runtime call failed operation=file_import_android_vulkan status="))
            assertFalse(raw.contains("<redacted"))
            assertTrue(raw.contains("code="))
        } finally {
            NuxieRuntimeBridge.nativeRendererFree(renderer)
            NuxieLog.configure(LogLevel.WARN)
        }
    }
}
