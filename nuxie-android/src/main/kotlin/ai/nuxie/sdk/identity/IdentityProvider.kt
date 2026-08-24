package ai.nuxie.sdk.identity

import ai.nuxie.sdk.events.TimeBasedEpochGenerator
import android.content.Context

/**
 * Identity seam consumed by the event pipeline. The full IdentityService
 * (identify/reset, FIFO user transitions, event migration) replaces
 * [AnonymousIdentityStub] in the identity PR; the interface is the stable
 * boundary.
 */
internal interface IdentityProvider {
    /** The effective id events are attributed to. Anonymous until identify. */
    fun distinctId(): String

    /** The device-scoped anonymous id, minted once and persisted. */
    fun anonymousId(): String

    /** The host-supplied user id when identified, else null. */
    fun rawDistinctId(): String?

    val isIdentified: Boolean
}

/**
 * Pre-identity stub: a persisted anonymous UUIDv7. No identify support —
 * see the identity PR (UNIV-1181 milestone 4 in the spec's ordering).
 */
internal class AnonymousIdentityStub(context: Context) : IdentityProvider {
    private val preferences =
        (context.applicationContext ?: context)
            .getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    private val anonymousId: String by lazy {
        preferences.getString(ANONYMOUS_ID_KEY, null) ?: TimeBasedEpochGenerator.shared.next().also {
            preferences.edit().putString(ANONYMOUS_ID_KEY, it).apply()
        }
    }

    override fun distinctId(): String = anonymousId

    override fun anonymousId(): String = anonymousId

    override fun rawDistinctId(): String? = null

    override val isIdentified: Boolean = false

    private companion object {
        const val PREFERENCES_NAME = "nuxie_identity"
        const val ANONYMOUS_ID_KEY = "anonymous_id"
    }
}
