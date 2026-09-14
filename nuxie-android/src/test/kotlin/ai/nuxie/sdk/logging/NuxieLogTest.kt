package ai.nuxie.sdk.logging

import ai.nuxie.sdk.LogLevel
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadows.ShadowLog

@RunWith(RobolectricTestRunner::class)
class NuxieLogTest {
    @After fun restorePolicy() { NuxieLog.configure(LogLevel.WARN) }

    @Test fun `verbose uses the Android verbose priority and is suppressed at DEBUG`() {
        ShadowLog.clear()
        NuxieLog.configure(LogLevel.DEBUG)
        NuxieLog.v("PrivacyProbe", "Verbose diagnostic")
        assertTrue(ShadowLog.getLogsForTag("PrivacyProbe").isEmpty())
        NuxieLog.configure(LogLevel.VERBOSE)
        NuxieLog.v("PrivacyProbe", "Verbose diagnostic")
        assertEquals(android.util.Log.VERBOSE, ShadowLog.getLogsForTag("PrivacyProbe").single().type)
    }

    @Test fun `platform receives only filtered rendered text without raw throwable`() {
        ShadowLog.clear()
        NuxieLog.configure(LogLevel.ERROR)
        NuxieLog.w("PrivacyProbe", "Hidden warning", IllegalStateException("hidden-secret"))
        NuxieLog.e("PrivacyProbe", "Delivery failed", IllegalStateException("response-secret"),
            NuxieLog.sensitive("customer", "customer-secret"), NuxieLog.status("status", 503))
        val entry = ShadowLog.getLogsForTag("PrivacyProbe").single()
        assertEquals(android.util.Log.ERROR, entry.type)
        assertTrue(entry.msg.contains("Delivery failed"))
        assertTrue(entry.msg.contains("status=503"))
        assertFalse(entry.msg.contains("secret"))
        assertNull(entry.throwable)
    }

    @Test fun `shared correlation survives reconfiguration and NONE silences all entry points`() {
        ShadowLog.clear()
        NuxieLog.configure(LogLevel.DEBUG)
        NuxieLog.d("PrivacyProbe", "Event", null, NuxieLog.sensitive("customer", "stable"))
        val first = ShadowLog.getLogsForTag("PrivacyProbe").single().msg
        NuxieLog.configure(LogLevel.NONE)
        NuxieLog.v("PrivacyProbe", "No verbose")
        NuxieLog.d("PrivacyProbe", "No debug")
        NuxieLog.i("PrivacyProbe", "No info")
        NuxieLog.w("PrivacyProbe", "No warning")
        NuxieLog.e("PrivacyProbe", "No error")
        assertEquals(1, ShadowLog.getLogsForTag("PrivacyProbe").size)
        NuxieLog.configure(LogLevel.DEBUG)
        NuxieLog.d("PrivacyProbe", "Event", null, NuxieLog.sensitive("customer", "stable"))
        assertEquals(first, ShadowLog.getLogsForTag("PrivacyProbe").last().msg)
    }
}
