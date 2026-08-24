package ai.nuxie.sdk.experiences

import android.content.Context

/**
 * Monotonic replay protection for release admission, ported from the iOS
 * `PersistentExperienceReleaseHighWaterStore`: per admission stream
 * (appId|environment|experienceId), the highest admitted publishedAtSeq is
 * the floor for future Active admissions.
 */
internal class ReleaseHighWaterStore(context: Context) {
    private val preferences = (context.applicationContext ?: context)
        .getSharedPreferences("nuxie_release_high_water", Context.MODE_PRIVATE)

    fun floor(streamKey: String): Long = preferences.getLong(streamKey, 0L)

    /** Promote after successful admission; never lowers the floor. */
    fun promote(streamKey: String, publishedAtSeq: Long) {
        if (publishedAtSeq > floor(streamKey)) {
            preferences.edit().putLong(streamKey, publishedAtSeq).apply()
        }
    }
}
