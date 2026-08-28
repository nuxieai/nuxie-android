package ai.nuxie.sdk.events

import ai.nuxie.sdk.DismissReason
import ai.nuxie.sdk.ExperienceRef
import ai.nuxie.sdk.JourneyExitReason
import ai.nuxie.sdk.NuxieActivity
import ai.nuxie.sdk.PermissionKind
import ai.nuxie.sdk.PurchaseInfo
import ai.nuxie.sdk.journey.JourneyEventNames
import android.util.Log
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull

/** Converts the curated internal event catalog into the stable typed forwarding contract. */
internal object ActivityCuration {
    val curatedNames: Set<String> = setOf(
        SystemEventNames.APP_BACKGROUNDED,
        SystemEventNames.APP_INSTALLED,
        SystemEventNames.APP_OPENED,
        SystemEventNames.APP_UPDATED,
        JourneyEventNames.EXPERIENCE_ARTIFACT_LOAD_FAILED,
        SystemEventNames.EXPERIENCE_DISMISSED,
        SystemEventNames.EXPERIENCE_ERRORED,
        SystemEventNames.EXPERIENCE_SHOWN,
        JourneyEventNames.EXPERIMENT_EXPOSURE,
        JourneyEventNames.EXPERIMENT_EXPOSURE_ERROR,
        SystemEventNames.FEATURE_USED,
        JourneyEventNames.CONVERTED,
        JourneyEventNames.ENROLLED,
        JourneyEventNames.EXITED,
        JourneyEventNames.MILESTONE,
        JourneyEventNames.SUPERSEDED,
        SystemEventNames.NOTIFICATIONS_DENIED,
        SystemEventNames.NOTIFICATIONS_ENABLED,
        SystemEventNames.PERMISSION_DENIED,
        SystemEventNames.PERMISSION_GRANTED,
        SystemEventNames.PRODUCTS_UNAVAILABLE,
        SystemEventNames.PURCHASE_CANCELLED,
        SystemEventNames.PURCHASE_COMPLETED,
        SystemEventNames.PURCHASE_FAILED,
        SystemEventNames.PURCHASE_PENDING,
        SystemEventNames.PURCHASE_SYNCED,
        SystemEventNames.RESTORE_COMPLETED,
        SystemEventNames.RESTORE_FAILED,
        SystemEventNames.RESTORE_NO_PURCHASES,
        SystemEventNames.SCREEN_DISMISSED,
        SystemEventNames.SCREEN_SHOWN,
        SystemEventNames.TRACKING_AUTHORIZED,
        SystemEventNames.TRACKING_DENIED,
    )

    fun activity(internalName: String, properties: JsonObject): NuxieActivity? = when (internalName) {
        SystemEventNames.EXPERIENCE_SHOWN -> experienceRef(properties)
            ?.let(NuxieActivity::ExperienceShown) ?: missing(internalName)
        SystemEventNames.EXPERIENCE_DISMISSED -> {
            val ref = experienceRef(properties)
            val reason = dismissReason(properties.string("reason"))
            if (ref == null || reason == null) missing(internalName)
            else NuxieActivity.ExperienceDismissed(ref, reason)
        }
        SystemEventNames.EXPERIENCE_ERRORED -> experienceRef(properties)?.let {
            NuxieActivity.ExperienceErrored(it, properties.string("error_message").orEmpty())
        } ?: missing(internalName)
        JourneyEventNames.ENROLLED -> experienceRef(properties)
            ?.let(NuxieActivity::JourneyStarted) ?: missing(internalName)
        JourneyEventNames.MILESTONE -> {
            val ref = experienceRef(properties)
            val milestoneId = properties.string("milestone_id")
            if (ref == null || milestoneId == null) missing(internalName)
            else NuxieActivity.MilestoneReached(ref, milestoneId)
        }
        JourneyEventNames.CONVERTED -> {
            val ref = experienceRef(properties, requireVersion = true)
            val journeyId = properties.string("journey_id")
            if (ref == null || journeyId == null) missing(internalName)
            else NuxieActivity.JourneyConverted(ref, journeyId)
        }
        JourneyEventNames.EXITED -> {
            val ref = experienceRef(properties)
            val rawReason = properties.string("reason")
            val reason = when {
                rawReason == "cancelled" && properties.string("dismissed_by") == "user" -> JourneyExitReason.DISMISSED
                rawReason != null -> journeyExitReason(rawReason)
                else -> null
            }
            if (ref == null || reason == null) missing(internalName)
            else NuxieActivity.JourneyEnded(ref, reason)
        }
        JourneyEventNames.SUPERSEDED -> experienceRef(properties)?.let {
            NuxieActivity.JourneyEnded(it, JourneyExitReason.SUPERSEDED)
        } ?: missing(internalName)
        SystemEventNames.PURCHASE_COMPLETED -> purchaseInfo(properties)
            ?.let(NuxieActivity::PurchaseCompleted) ?: missing(internalName)
        SystemEventNames.PURCHASE_FAILED -> {
            val info = purchaseInfo(properties, requiresProductIdentifiers = false)
            val message = properties.string("error") ?: properties.string("reason")
            if (info == null || message == null) missing(internalName)
            else NuxieActivity.PurchaseFailed(info, message)
        }
        SystemEventNames.PURCHASE_CANCELLED -> purchaseInfo(properties)
            ?.let(NuxieActivity::PurchaseCancelled) ?: missing(internalName)
        SystemEventNames.PURCHASE_PENDING -> purchaseInfo(properties)
            ?.let(NuxieActivity::PurchasePending) ?: missing(internalName)
        SystemEventNames.RESTORE_COMPLETED -> NuxieActivity.RestoreCompleted
        SystemEventNames.RESTORE_FAILED -> properties.string("error")
            ?.let(NuxieActivity::RestoreFailed) ?: missing(internalName)
        SystemEventNames.RESTORE_NO_PURCHASES -> NuxieActivity.RestoreNoPurchases
        SystemEventNames.PURCHASE_SYNCED -> {
            val transactionId = properties.string("transaction_id")
            val productId = properties.string("product_id")
            if (transactionId == null || productId == null) missing(internalName)
            else NuxieActivity.PurchaseSynced(
                transactionId,
                properties.nonemptyString("original_transaction_id"),
                productId,
                experienceRef(properties),
            )
        }
        SystemEventNames.FEATURE_USED -> {
            val featureId = properties.string("feature_id") ?: properties.string("feature_extId")
            val amount = properties.double("amount")
            if (featureId == null || amount == null) missing(internalName)
            else NuxieActivity.FeatureUsed(featureId, amount, properties.nonemptyString("entity_id"))
        }
        JourneyEventNames.EXPERIMENT_EXPOSURE -> {
            val ref = experienceRef(properties)
            val experimentKey = properties.string("experiment_key")
            val variantKey = properties.string("variant_key")
            val isHoldout = properties.bool("is_holdout")
            if (ref == null || experimentKey == null || variantKey == null || isHoldout == null) {
                missing(internalName)
            } else NuxieActivity.ExperimentExposure(ref, experimentKey, variantKey, isHoldout)
        }
        JourneyEventNames.EXPERIMENT_EXPOSURE_ERROR -> {
            val ref = experienceRef(properties)
            val experimentKey = properties.string("experiment_key")
            if (ref == null || experimentKey == null) missing(internalName)
            else NuxieActivity.ExperimentError(ref, experimentKey, properties.string("reason").orEmpty())
        }
        SystemEventNames.PRODUCTS_UNAVAILABLE -> {
            val ref = experienceRef(properties)
            val productIds = properties.strings("product_ids")
            if (ref == null || productIds == null) missing(internalName)
            else NuxieActivity.ProductsUnavailable(ref, productIds)
        }
        SystemEventNames.SCREEN_SHOWN -> screenActivity(properties, internalName, shown = true)
        SystemEventNames.SCREEN_DISMISSED -> screenActivity(properties, internalName, shown = false)
        JourneyEventNames.EXPERIENCE_ARTIFACT_LOAD_FAILED -> experienceRef(properties)?.let {
            NuxieActivity.ExperienceLoadFailed(it, properties.string("error_message").orEmpty())
        } ?: missing(internalName)
        SystemEventNames.NOTIFICATIONS_ENABLED -> permission(properties, PermissionKind.NOTIFICATIONS, true)
        SystemEventNames.NOTIFICATIONS_DENIED -> permission(properties, PermissionKind.NOTIFICATIONS, false)
        SystemEventNames.TRACKING_AUTHORIZED -> permission(properties, PermissionKind.TRACKING, true)
        SystemEventNames.TRACKING_DENIED -> permission(properties, PermissionKind.TRACKING, false)
        SystemEventNames.PERMISSION_GRANTED -> permission(properties, PermissionKind.OTHER, true)
        SystemEventNames.PERMISSION_DENIED -> permission(properties, PermissionKind.OTHER, false)
        SystemEventNames.APP_INSTALLED -> NuxieActivity.AppInstalled
        SystemEventNames.APP_UPDATED -> properties.string("app_version")?.let {
            NuxieActivity.AppUpdated(properties.nonemptyString("previous_version"), it)
        } ?: missing(internalName)
        SystemEventNames.APP_OPENED -> NuxieActivity.AppOpened
        SystemEventNames.APP_BACKGROUNDED -> NuxieActivity.AppBackgrounded
        else -> null
    }

    private fun screenActivity(properties: JsonObject, internalName: String, shown: Boolean): NuxieActivity? {
        val ref = experienceRef(properties)
        val screenId = properties.string("screen_id")
        if (ref == null || screenId == null) return missing(internalName)
        return if (shown) NuxieActivity.ScreenShown(ref, screenId)
        else NuxieActivity.ScreenDismissed(ref, screenId)
    }

    private fun permission(properties: JsonObject, kind: PermissionKind, granted: Boolean) =
        NuxieActivity.PermissionResolved(experienceRef(properties), kind, granted)

    private fun experienceRef(properties: JsonObject, requireVersion: Boolean = false): ExperienceRef? {
        val experienceId = properties.string("experience_id") ?: return null
        val version = properties.nonemptyString("experience_version")
            ?: properties.nonemptyString("experience_version_id")
        if (requireVersion && version == null) return null
        return ExperienceRef(experienceId, version, properties.nonemptyString("journey_id"))
    }

    private fun purchaseInfo(properties: JsonObject, requiresProductIdentifiers: Boolean = true): PurchaseInfo? {
        val productId = properties.string("product_id")
        val storeProductId = properties.string("store_product_id")
        if (requiresProductIdentifiers && (productId == null || storeProductId == null)) return null
        return PurchaseInfo(
            productId,
            storeProductId,
            properties.nonemptyString("placement_id"),
            experienceRef(properties),
            properties.decimal("price"),
            properties.nonemptyString("display_price"),
            properties.nonemptyString("transaction_id"),
            properties.bool("test_store") ?: false,
        )
    }

    private fun dismissReason(value: String?): DismissReason? = when (value) {
        "user", "user_dismissed" -> DismissReason.USER
        "goal_met" -> DismissReason.GOAL_MET
        "error" -> DismissReason.ERROR
        "host", "host_dismissed" -> DismissReason.HOST
        else -> null
    }

    private fun journeyExitReason(value: String): JourneyExitReason? = when (value) {
        "completed" -> JourneyExitReason.COMPLETED
        "dismissed" -> JourneyExitReason.DISMISSED
        "goal_met", "converted_exit" -> JourneyExitReason.GOAL_MET
        "trigger_unmatched", "stopped_matching" -> JourneyExitReason.TRIGGER_UNMATCHED
        "expired", "time_limit" -> JourneyExitReason.EXPIRED
        "cancelled" -> JourneyExitReason.CANCELLED
        "error" -> JourneyExitReason.ERROR
        "superseded" -> JourneyExitReason.SUPERSEDED
        else -> null
    }

    private fun JsonObject.string(key: String): String? =
        (get(key) as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.content

    private fun JsonObject.nonemptyString(key: String): String? = string(key)?.takeIf(String::isNotEmpty)
    private fun JsonObject.double(key: String): Double? = (get(key) as? JsonPrimitive)
        ?.takeUnless(JsonPrimitive::isString)
        ?.doubleOrNull
    private fun JsonObject.decimal(key: String) = (get(key) as? JsonPrimitive)
        ?.takeUnless(JsonPrimitive::isString)
        ?.content
        ?.toBigDecimalOrNull()

    private fun JsonObject.bool(key: String): Boolean? {
        val primitive = (get(key) as? JsonPrimitive)?.takeUnless(JsonPrimitive::isString) ?: return null
        return primitive.booleanOrNull ?: primitive.doubleOrNull?.let { it != 0.0 }
    }

    private fun JsonObject.strings(key: String): List<String>? {
        val array = get(key) as? JsonArray ?: return null
        val values = array.mapNotNull { (it as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.content }
        return values.takeIf { it.size == array.size }
    }

    private fun missing(internalName: String): NuxieActivity? {
        Log.w("Nuxie", "Suppressing malformed forwarded activity '$internalName'")
        return null
    }
}
