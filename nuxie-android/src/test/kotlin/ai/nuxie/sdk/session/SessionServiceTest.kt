package ai.nuxie.sdk.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class SessionServiceTest {
    private var now = 1_784_462_400_000L
    private val sessions = SessionService { now }

    @Test
    fun sessionIsCreatedOnFirstAccessAndStableWhileActive() {
        val first = sessions.getSessionId()
        assertNotNull(first)
        now += 10 * 60 * 1000
        assertEquals(first, sessions.getSessionId())
    }

    @Test
    fun readOnlyNeverCreatesASession() {
        assertNull(sessions.getSessionId(readOnly = true))
    }

    @Test
    fun thirtyMinutesOfInactivityRotatesTheSession() {
        val first = sessions.getSessionId()
        now += 31 * 60 * 1000
        assertNotEquals(first, sessions.getSessionId())
    }

    @Test
    fun touchKeepsTheSessionAliveAcrossIdleChecks() {
        val first = sessions.getSessionId()
        repeat(4) {
            now += 20 * 60 * 1000
            sessions.touchSession()
        }
        assertEquals(first, sessions.getSessionId())
    }

    @Test
    fun twentyFourHoursEndsTheSessionEvenWhenActive() {
        val first = sessions.getSessionId()
        repeat(49) {
            now += 30 * 60 * 1000  // touch every 30 min, never idle
            sessions.touchSession()
        }
        // > 24h since start: rotated despite continuous activity.
        assertNotEquals(first, sessions.getSessionId())
    }

    @Test
    fun backgroundTimeoutClearsInsteadOfRotating() {
        sessions.getSessionId()
        sessions.onAppDidEnterBackground()
        now += 31 * 60 * 1000
        sessions.touchSession()
        assertNull(sessions.getSessionId(readOnly = true))

        // Returning to foreground creates a fresh session.
        sessions.onAppBecameActive()
        assertNotNull(sessions.getSessionId(readOnly = true))
    }

    @Test
    fun resetSessionAlwaysRotates() {
        val first = sessions.getSessionId()
        sessions.resetSession()
        assertNotEquals(first, sessions.getSessionId())
    }
}
