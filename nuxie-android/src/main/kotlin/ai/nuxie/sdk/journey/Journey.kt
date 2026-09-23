package ai.nuxie.sdk.journey

/** Authored entry frequency retained by a Journey across profile refreshes. */
internal sealed class JourneyFrequency {
    data object OneTime : JourneyFrequency()
    data object EveryMatch : JourneyFrequency()
    data class OncePerWindow(val windowMillis: Long) : JourneyFrequency()
}
