package ai.nuxie.sdk.profile

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** The effective profile locale and the runtime decision that selected it. */
internal data class ProfileLocaleScope(
    val identifier: String,
    val revision: Long,
)

/**
 * Synchronized home for the locale used by profile requests.
 *
 * A null override follows the device locale. Reading a scope also notices a
 * device-locale change, so callers can reject work captured under the prior
 * effective locale even when the host did not call the explicit setter.
 */
internal class ProfileLocaleSettings(
    localeIdentifier: String?,
    private val deviceLocaleIdentifier: () -> String,
) {
    private val lock = Any()
    private val admission = Mutex()
    private var configuredIdentifier = localeIdentifier
    private var observedIdentifier = effectiveIdentifierLocked()
    private var revision = 0L

    fun captureScope(): ProfileLocaleScope = withAdmission { synchronized(lock) { currentScopeLocked() } }

    fun isCurrentScope(scope: ProfileLocaleScope): Boolean = synchronized(lock) {
        currentScopeLocked() == scope
    }

    fun <T> withCurrentScope(scope: ProfileLocaleScope, block: () -> T): T? =
        withAdmission {
            if (isCurrentScope(scope)) block() else null
        }

    fun setLocaleIdentifier(localeIdentifier: String?) = withAdmission {
        synchronized(lock) {
            configuredIdentifier = localeIdentifier
            currentScopeLocked()
        }
    }

    suspend fun <T> withCurrentScopeSuspending(scope: ProfileLocaleScope, block: suspend () -> T): T? =
        admission.withLock { if (isCurrentScope(scope)) block() else null }

    private fun <T> withAdmission(block: () -> T): T = runBlocking {
        admission.withLock { block() }
    }

    private fun currentScopeLocked(): ProfileLocaleScope {
        val effective = effectiveIdentifierLocked()
        if (effective != observedIdentifier) {
            observedIdentifier = effective
            revision += 1
        }
        return ProfileLocaleScope(observedIdentifier, revision)
    }

    private fun effectiveIdentifierLocked(): String =
        configuredIdentifier ?: deviceLocaleIdentifier()
}
