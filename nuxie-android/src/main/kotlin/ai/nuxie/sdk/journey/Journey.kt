package ai.nuxie.sdk.journey

/** Authored reentry policy retained by a Journey across profile refreshes. */
internal sealed class JourneyReentry {
    data object OneTime : JourneyReentry()
    data object EveryTime : JourneyReentry()
    data class OncePerWindow(val windowMillis: Long) : JourneyReentry()
}
