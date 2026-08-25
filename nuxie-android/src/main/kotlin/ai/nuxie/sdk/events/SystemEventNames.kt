package ai.nuxie.sdk.events

/**
 * Canonical names for `$`-prefixed system events, mirroring the iOS
 * `SystemEventNames`. All `$`-event emissions must reference these constants —
 * no bare string literals at emitter sites. The full catalog (when each fires,
 * properties, delivery guarantees) lives in the iOS repo's `docs/sdk-events.md`.
 *
 * The `$experience_*` names are enumerated by
 * `fixtures/events/experience-events.json` and emitted by the presentation
 * service rather than the capture pipeline.
 */
internal object SystemEventNames {
    // Identity
    const val IDENTIFY = "\$identify"

    // App lifecycle
    const val APP_INSTALLED = "\$app_installed"
    const val APP_UPDATED = "\$app_updated"
    const val APP_OPENED = "\$app_opened"
    const val APP_BACKGROUNDED = "\$app_backgrounded"

    // Feature gating / metered usage (backend-ingested by name)
    const val FEATURE_USED = "\$feature_used"

    // Experience presentation
    const val EXPERIENCE_SHOWN = "\$experience_shown"
    const val EXPERIENCE_DISMISSED = "\$experience_dismissed"
    const val EXPERIENCE_PURCHASED = "\$experience_purchased"
    const val EXPERIENCE_TIMED_OUT = "\$experience_timed_out"
    const val EXPERIENCE_ERRORED = "\$experience_errored"

    // Screens
    const val SCREEN_SHOWN = "\$screen_shown"
    const val SCREEN_DISMISSED = "\$screen_dismissed"

    // Purchases / restores
    const val PURCHASE_COMPLETED = "\$purchase_completed"
    const val PURCHASE_FAILED = "\$purchase_failed"
    const val PURCHASE_CANCELLED = "\$purchase_cancelled"
    const val PURCHASE_PENDING = "\$purchase_pending"
    const val PURCHASE_SYNCED = "\$purchase_synced"
    const val RESTORE_COMPLETED = "\$restore_completed"
    const val RESTORE_FAILED = "\$restore_failed"
    const val RESTORE_NO_PURCHASES = "\$restore_no_purchases"
}
