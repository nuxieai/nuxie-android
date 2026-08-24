package ai.nuxie.sdk.session

import ai.nuxie.sdk.events.TimeBasedEpochGenerator

/**
 * Session management ported from the iOS `SessionService`: 30-minute idle
 * timeout, 24-hour maximum length, UUIDv7 session ids. Session boundaries
 * are derived server-side from `$session_id` stamps, so rotation
 * deliberately emits no client event.
 */
internal class SessionService(
    private val nowMillis: () -> Long = System::currentTimeMillis,
) {
    private val lock = Any()

    private var sessionId: String? = null
    private var sessionStartMillis: Long? = null
    private var sessionActivityMillis: Long? = null
    private var isAppInBackground = false

    /**
     * Current session id, creating one if needed. [readOnly] never creates
     * or touches.
     */
    fun getSessionId(atMillis: Long = nowMillis(), readOnly: Boolean = false): String? =
        synchronized(lock) {
            if (shouldStartNewSession(atMillis) && !readOnly) {
                createNewSession(atMillis)
            }
            if (sessionId != null && !readOnly) {
                sessionActivityMillis = atMillis
            }
            sessionId
        }

    fun startSession() {
        synchronized(lock) { createNewSession(nowMillis()) }
    }

    fun resetSession() {
        synchronized(lock) {
            clearSession()
            createNewSession(nowMillis())
        }
    }

    /** Called on each captured event. */
    fun touchSession() {
        synchronized(lock) {
            val now = nowMillis()
            if (shouldStartNewSession(now)) {
                if (isAppInBackground) clearSession() else createNewSession(now)
            } else if (sessionId != null) {
                sessionActivityMillis = now
            }
        }
    }

    fun onAppBecameActive() {
        synchronized(lock) {
            isAppInBackground = false
            val now = nowMillis()
            if (shouldStartNewSession(now)) createNewSession(now)
        }
    }

    fun onAppDidEnterBackground() {
        synchronized(lock) { isAppInBackground = true }
    }

    private fun shouldStartNewSession(atMillis: Long): Boolean {
        if (sessionId == null) return true
        // Maximum session duration takes precedence.
        sessionStartMillis?.let { start ->
            if (atMillis - start > SESSION_MAX_LENGTH_MILLIS) return true
        }
        sessionActivityMillis?.let { lastActivity ->
            if (atMillis - lastActivity > SESSION_ACTIVITY_MILLIS) return true
        }
        return false
    }

    private fun createNewSession(atMillis: Long) {
        sessionId = TimeBasedEpochGenerator.shared.next()
        sessionStartMillis = atMillis
        sessionActivityMillis = atMillis
    }

    private fun clearSession() {
        sessionId = null
        sessionStartMillis = null
        sessionActivityMillis = null
    }

    private companion object {
        /** 30 minutes of inactivity ends the session. */
        const val SESSION_ACTIVITY_MILLIS = 30L * 60L * 1000L

        /** 24-hour maximum session length. */
        const val SESSION_MAX_LENGTH_MILLIS = 24L * 60L * 60L * 1000L
    }
}
