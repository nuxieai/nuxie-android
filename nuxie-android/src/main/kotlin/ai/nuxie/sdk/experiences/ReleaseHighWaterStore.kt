package ai.nuxie.sdk.experiences

import android.content.Context

/**
 * Monotonic replay protection for release admission, ported from the iOS
 * `PersistentExperienceReleaseHighWaterStore`: per admission stream
 * (appId|environment|experienceId), the highest admitted releaseSequence is
 * the floor for future Active admissions.
 */
internal class ReleaseHighWaterStore(context: Context) {
    private val preferences = (context.applicationContext ?: context)
        .getSharedPreferences("nuxie_release_high_water", Context.MODE_PRIVATE)

    fun floor(streamKey: String): Long = synchronized(processLock) {
        preferences.getLong(streamKey, 0L)
    }

    /** Promote after successful admission; never lowers the floor. */
    fun promote(streamKey: String, releaseSequence: Long) {
        promoteBatch(mapOf(streamKey to releaseSequence))
    }

    /** Publish one verified profile's replay floors in a single preferences transaction. */
    fun promoteBatch(candidates: Map<String, Long>) = synchronized(processLock) {
        persistIncreasing(candidates)
    }

    /** Reject a stale prepared batch, then durably admit all increasing floors together. */
    fun admitBatch(candidates: Map<String, Long>) = synchronized(processLock) {
        if (candidates.any { (streamKey, sequence) ->
                sequence < 0L || sequence < preferences.getLong(streamKey, 0L)
            }
        ) {
            throw ReleaseAuthenticationException("replay rejected")
        }
        persistIncreasing(candidates)
    }

    private fun persistIncreasing(candidates: Map<String, Long>) {
        val promotions = candidates.filter { (streamKey, sequence) ->
            sequence > preferences.getLong(streamKey, 0L)
        }
        if (promotions.isEmpty()) return
        val editor = preferences.edit()
        for ((streamKey, sequence) in promotions) editor.putLong(streamKey, sequence)
        check(editor.commit()) { "Could not persist release replay floors" }
    }

    private companion object {
        /** SharedPreferences instances share this replay ledger within the app process. */
        val processLock = Any()
    }
}
