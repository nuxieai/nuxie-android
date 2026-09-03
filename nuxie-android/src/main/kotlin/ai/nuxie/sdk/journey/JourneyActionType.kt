package ai.nuxie.sdk.journey

import ai.nuxie.sdk.events.SystemEventNames
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** The closed operation vocabulary admitted from an authenticated Journey. */
internal enum class JourneyActionType(val wireValue: String) {
    CONDITION("condition"),
    EXPERIMENT("experiment"),
    TIME_WINDOW("time_window"),
    DELAY("delay"),
    WAIT_UNTIL("wait_until"),
    NAVIGATE("navigate"),
    BACK("back"),
    PURCHASE("purchase"),
    RESTORE("restore"),
    REQUEST_NOTIFICATIONS("request_notifications"),
    REQUEST_PERMISSION("request_permission"),
    REQUEST_TRACKING("request_tracking"),
    OPEN_LINK("open_link"),
    DISMISS("dismiss"),
    SEND_EVENT("send_event"),
    UPDATE_CUSTOMER("update_customer"),
    MILESTONE("milestone"),
    SUBMIT_RESPONSE("submit_response"),
    APP_ACTION("app_action"),
    EXIT("exit"),
    CONNECTOR_ACTION("connector_action"),
    GRANT_ENTITLEMENT("grant_entitlement"),
    DEVICE_AVAILABLE("device_available"),
    ;

    val isPresentationOwned: Boolean
        get() = this in PRESENTATION_OWNED

    val isCommerce: Boolean
        get() = this == PURCHASE || this == RESTORE

    companion object {
        private val BY_WIRE_VALUE = entries.associateBy(JourneyActionType::wireValue)
        private val PRESENTATION_OWNED = setOf(
            NAVIGATE,
            BACK,
            PURCHASE,
            RESTORE,
            REQUEST_NOTIFICATIONS,
            REQUEST_PERMISSION,
            REQUEST_TRACKING,
            OPEN_LINK,
            DISMISS,
        )

        fun from(action: JsonObject): JourneyActionType? =
            (action["type"] as? JsonPrimitive)
                ?.takeIf(JsonPrimitive::isString)
                ?.content
                ?.let(BY_WIRE_VALUE::get)

        fun presentationOutcomeRoute(eventName: String): Pair<JourneyActionType, String>? =
            when (eventName) {
                SystemEventNames.PURCHASE_COMPLETED -> PURCHASE to "completed"
                SystemEventNames.PURCHASE_FAILED -> PURCHASE to "failed"
                SystemEventNames.PURCHASE_CANCELLED -> PURCHASE to "cancelled"
                SystemEventNames.RESTORE_COMPLETED -> RESTORE to "restored"
                SystemEventNames.RESTORE_FAILED -> RESTORE to "failed"
                SystemEventNames.RESTORE_NO_PURCHASES -> RESTORE to "noPurchases"
                else -> null
            }
    }
}
