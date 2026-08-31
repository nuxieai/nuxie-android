package ai.nuxie.sdk

import java.math.BigDecimal

/** A durably captured Nuxie activity suitable for forwarding to an analytics tool. */
@ConsistentCopyVisibility
data class NuxieActivityInfo internal constructor(
    val id: String,
    val timestampMillis: Long,
    val receivedAtMillis: Long,
    val activity: NuxieActivity,
) {
    val name: String
        get() = activity.name

    val properties: Map<String, NuxieActivityValue>
        get() = activity.properties

    companion object {
        /** Contract version of the typed activity and its flat wire encoding. */
        const val SCHEMA_VERSION: Int = 1
    }
}

/** JSON-safe scalar used by the flat forwarding view. */
sealed interface NuxieActivityValue {
    data class String(val value: kotlin.String) : NuxieActivityValue
    data class Int(val value: Long) : NuxieActivityValue
    data class Double(val value: kotlin.Double) : NuxieActivityValue {
        override fun equals(other: Any?): Boolean =
            other is Double && value.hasSameDoubleValueAs(other.value)

        override fun hashCode(): kotlin.Int = value.doubleValueHashCode()
    }
    data class Bool(val value: Boolean) : NuxieActivityValue
}

/** Curated activities emitted by the Nuxie engine. */
sealed interface NuxieActivity {
    val name: String
        get() = wireName()

    val properties: Map<String, NuxieActivityValue>
        get() = wireProperties()

    data class ExperienceShown(val experience: ExperienceRef) : NuxieActivity
    data class ExperienceDismissed(val experience: ExperienceRef, val reason: DismissReason) : NuxieActivity
    data class ExperienceErrored(val experience: ExperienceRef, val message: String) : NuxieActivity

    data class JourneyStarted(val experience: ExperienceRef) : NuxieActivity
    /** This device started one leg of a pinned journey. */
    data class JourneyLegStarted(val experience: ExperienceRef, val legId: String, val generation: Long) : NuxieActivity
    /** Queued leg completion; the server may continue the journey chain. */
    data class JourneyLegCompleted(val experience: ExperienceRef, val legId: String, val generation: Long, val outcome: String) : NuxieActivity
    data class MilestoneReached(val experience: ExperienceRef, val milestoneId: String) : NuxieActivity
    data class JourneyConverted(val experience: ExperienceRef, val journeyId: String) : NuxieActivity
    data class JourneyEnded(val experience: ExperienceRef, val exitReason: JourneyExitReason) : NuxieActivity

    data class PurchaseCompleted(val info: PurchaseInfo) : NuxieActivity
    data class PurchaseFailed(val info: PurchaseInfo, val message: String) : NuxieActivity
    data class PurchaseCancelled(val info: PurchaseInfo) : NuxieActivity
    data class PurchasePending(val info: PurchaseInfo) : NuxieActivity
    data object RestoreCompleted : NuxieActivity
    data class RestoreFailed(val message: String) : NuxieActivity
    data object RestoreNoPurchases : NuxieActivity
    data class PurchaseSynced(
        val transactionId: String,
        val originalTransactionId: String?,
        val productId: String,
        val experience: ExperienceRef?,
    ) : NuxieActivity

    data class FeatureUsed(val featureId: String, val amount: Double, val entityId: String?) : NuxieActivity {
        override fun equals(other: Any?): Boolean =
            other is FeatureUsed &&
                featureId == other.featureId &&
                amount.hasSameDoubleValueAs(other.amount) &&
                entityId == other.entityId

        override fun hashCode(): Int {
            var result = featureId.hashCode()
            result = 31 * result + amount.doubleValueHashCode()
            result = 31 * result + (entityId?.hashCode() ?: 0)
            return result
        }
    }

    data class ExperimentExposure(
        val experience: ExperienceRef,
        val experimentKey: String,
        val variantKey: String,
        val isHoldout: Boolean,
    ) : NuxieActivity

    data class ExperimentError(
        val experience: ExperienceRef,
        val experimentKey: String,
        val message: String,
    ) : NuxieActivity

    data class ProductsUnavailable(val experience: ExperienceRef, val productIds: List<String>) : NuxieActivity
    data class ScreenShown(val experience: ExperienceRef, val screenId: String) : NuxieActivity
    data class ScreenDismissed(val experience: ExperienceRef, val screenId: String) : NuxieActivity
    data class ExperienceLoadFailed(val experience: ExperienceRef, val message: String) : NuxieActivity
    data class PermissionResolved(
        val experience: ExperienceRef?,
        val kind: PermissionKind,
        val granted: Boolean,
    ) : NuxieActivity

    data object AppInstalled : NuxieActivity
    data class AppUpdated(val fromVersion: String?, val toVersion: String) : NuxieActivity
    data object AppOpened : NuxieActivity
    data object AppBackgrounded : NuxieActivity
}

/** Why an Experience presentation ended. */
enum class DismissReason { USER, GOAL_MET, ERROR, HOST }

/** Commerce details captured at the presentation seam. */
class PurchaseInfo internal constructor(
    val productId: String?,
    val storeProductId: String?,
    val placementId: String?,
    val experience: ExperienceRef?,
    val price: BigDecimal?,
    val displayPrice: String?,
    val transactionId: String?,
    val isTestStore: Boolean,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is PurchaseInfo) return false

        return productId == other.productId &&
            storeProductId == other.storeProductId &&
            placementId == other.placementId &&
            experience == other.experience &&
            price.hasSameValueAs(other.price) &&
            displayPrice == other.displayPrice &&
            transactionId == other.transactionId &&
            isTestStore == other.isTestStore
    }

    override fun hashCode(): Int {
        var result = productId?.hashCode() ?: 0
        result = 31 * result + (storeProductId?.hashCode() ?: 0)
        result = 31 * result + (placementId?.hashCode() ?: 0)
        result = 31 * result + (experience?.hashCode() ?: 0)
        result = 31 * result + (price?.stripTrailingZeros()?.hashCode() ?: 0)
        result = 31 * result + (displayPrice?.hashCode() ?: 0)
        result = 31 * result + (transactionId?.hashCode() ?: 0)
        result = 31 * result + isTestStore.hashCode()
        return result
    }
}

private fun BigDecimal?.hasSameValueAs(other: BigDecimal?): Boolean {
    if (this == null || other == null) return this == null && other == null
    return compareTo(other) == 0
}

/** Permission family resolved by an Experience request. */
enum class PermissionKind { NOTIFICATIONS, TRACKING, OTHER }

private fun NuxieActivity.wireName(): String = when (this) {
        is NuxieActivity.ExperienceShown -> "experience_shown"
        is NuxieActivity.ExperienceDismissed -> "experience_dismissed"
        is NuxieActivity.ExperienceErrored -> "experience_errored"
        is NuxieActivity.JourneyStarted -> "journey_started"
        is NuxieActivity.JourneyLegStarted -> "journey_leg_started"
        is NuxieActivity.JourneyLegCompleted -> "journey_leg_completed"
        is NuxieActivity.MilestoneReached -> "milestone_reached"
        is NuxieActivity.JourneyConverted -> "journey_converted"
        is NuxieActivity.JourneyEnded -> "journey_ended"
        is NuxieActivity.PurchaseCompleted -> "purchase_completed"
        is NuxieActivity.PurchaseFailed -> "purchase_failed"
        is NuxieActivity.PurchaseCancelled -> "purchase_cancelled"
        is NuxieActivity.PurchasePending -> "purchase_pending"
        NuxieActivity.RestoreCompleted -> "restore_completed"
        is NuxieActivity.RestoreFailed -> "restore_failed"
        NuxieActivity.RestoreNoPurchases -> "restore_no_purchases"
        is NuxieActivity.PurchaseSynced -> "purchase_synced"
        is NuxieActivity.FeatureUsed -> "feature_used"
        is NuxieActivity.ExperimentExposure -> "experiment_exposure"
        is NuxieActivity.ExperimentError -> "experiment_error"
        is NuxieActivity.ProductsUnavailable -> "products_unavailable"
        is NuxieActivity.ScreenShown -> "screen_shown"
        is NuxieActivity.ScreenDismissed -> "screen_dismissed"
        is NuxieActivity.ExperienceLoadFailed -> "experience_load_failed"
        is NuxieActivity.PermissionResolved -> "permission_resolved"
        NuxieActivity.AppInstalled -> "app_installed"
        is NuxieActivity.AppUpdated -> "app_updated"
        NuxieActivity.AppOpened -> "app_opened"
        NuxieActivity.AppBackgrounded -> "app_backgrounded"
}

private fun NuxieActivity.wireProperties(): Map<String, NuxieActivityValue> = buildMap {
        when (val activity = this@wireProperties) {
            is NuxieActivity.ExperienceShown -> add(activity.experience)
            is NuxieActivity.ExperienceDismissed -> {
                add(activity.experience)
                put("reason", activity.reason.wireValue())
            }
            is NuxieActivity.ExperienceErrored -> {
                add(activity.experience)
                put("message", activity.message.activityValue())
            }
            is NuxieActivity.JourneyStarted -> add(activity.experience)
            is NuxieActivity.JourneyLegStarted -> {
                add(activity.experience)
                put("leg_id", activity.legId.activityValue())
                put("leg_generation", NuxieActivityValue.Int(activity.generation))
            }
            is NuxieActivity.JourneyLegCompleted -> {
                add(activity.experience)
                put("leg_id", activity.legId.activityValue())
                put("leg_generation", NuxieActivityValue.Int(activity.generation))
                put("outcome", activity.outcome.activityValue())
            }
            is NuxieActivity.MilestoneReached -> {
                add(activity.experience)
                put("milestone_id", activity.milestoneId.activityValue())
            }
            is NuxieActivity.JourneyConverted -> {
                add(activity.experience)
                put("journey_id", activity.journeyId.activityValue())
            }
            is NuxieActivity.JourneyEnded -> {
                add(activity.experience)
                put("exit_reason", activity.exitReason.wireValue().activityValue())
            }
            is NuxieActivity.PurchaseCompleted -> add(activity.info)
            is NuxieActivity.PurchaseFailed -> {
                add(activity.info)
                put("message", activity.message.activityValue())
            }
            is NuxieActivity.PurchaseCancelled -> add(activity.info)
            is NuxieActivity.PurchasePending -> add(activity.info)
            NuxieActivity.RestoreCompleted,
            NuxieActivity.RestoreNoPurchases,
            NuxieActivity.AppInstalled,
            NuxieActivity.AppOpened,
            NuxieActivity.AppBackgrounded,
            -> Unit
            is NuxieActivity.RestoreFailed -> put("message", activity.message.activityValue())
            is NuxieActivity.PurchaseSynced -> {
                put("transaction_id", activity.transactionId.activityValue())
                add("original_transaction_id", activity.originalTransactionId)
                put("product_id", activity.productId.activityValue())
                activity.experience?.let { ref ->
                    put("experience_id", ref.experienceId.activityValue())
                    add("journey_id", ref.journeyId)
                }
            }
            is NuxieActivity.FeatureUsed -> {
                put("feature_id", activity.featureId.activityValue())
                put("amount", NuxieActivityValue.Double(activity.amount))
                add("entity_id", activity.entityId)
            }
            is NuxieActivity.ExperimentExposure -> {
                add(activity.experience)
                put("experiment_key", activity.experimentKey.activityValue())
                put("variant_key", activity.variantKey.activityValue())
                put("is_holdout", NuxieActivityValue.Bool(activity.isHoldout))
            }
            is NuxieActivity.ExperimentError -> {
                add(activity.experience)
                put("experiment_key", activity.experimentKey.activityValue())
                put("message", activity.message.activityValue())
            }
            is NuxieActivity.ProductsUnavailable -> {
                add(activity.experience)
                put("product_ids", activity.productIds.joinToString(",").activityValue())
            }
            is NuxieActivity.ScreenShown -> {
                add(activity.experience)
                put("screen_id", activity.screenId.activityValue())
            }
            is NuxieActivity.ScreenDismissed -> {
                add(activity.experience)
                put("screen_id", activity.screenId.activityValue())
            }
            is NuxieActivity.ExperienceLoadFailed -> {
                add(activity.experience)
                put("message", activity.message.activityValue())
            }
            is NuxieActivity.PermissionResolved -> {
                activity.experience?.let(::add)
                put("kind", activity.kind.wireValue().activityValue())
                put("granted", NuxieActivityValue.Bool(activity.granted))
            }
            is NuxieActivity.AppUpdated -> {
                add("from_version", activity.fromVersion)
                put("to_version", activity.toVersion.activityValue())
            }
        }
}

private fun MutableMap<String, NuxieActivityValue>.add(ref: ExperienceRef) {
    put("experience_id", ref.experienceId.activityValue())
    add("experience_version", ref.experienceVersion)
    add("journey_id", ref.journeyId)
}

private fun MutableMap<String, NuxieActivityValue>.add(info: PurchaseInfo) {
    add("product_id", info.productId)
    add("store_product_id", info.storeProductId)
    add("placement_id", info.placementId)
    info.experience?.let { put("experience_id", it.experienceId.activityValue()) }
    info.price?.let { put("price", NuxieActivityValue.Double(it.toDouble())) }
    add("display_price", info.displayPrice)
    add("transaction_id", info.transactionId)
    put("test_store", NuxieActivityValue.Bool(info.isTestStore))
}

private fun MutableMap<String, NuxieActivityValue>.add(key: String, value: String?) {
    value?.let { put(key, it.activityValue()) }
}

private fun String.activityValue() = NuxieActivityValue.String(this)

private fun DismissReason.wireValue() = when (this) {
    DismissReason.USER -> "user"
    DismissReason.GOAL_MET -> "goal_met"
    DismissReason.ERROR -> "error"
    DismissReason.HOST -> "host"
}.activityValue()

private fun PermissionKind.wireValue() = when (this) {
    PermissionKind.NOTIFICATIONS -> "notifications"
    PermissionKind.TRACKING -> "tracking"
    PermissionKind.OTHER -> "other"
}

internal fun JourneyExitReason.wireValue() = when (this) {
    JourneyExitReason.COMPLETED -> "completed"
    JourneyExitReason.DISMISSED -> "dismissed"
    JourneyExitReason.GOAL_MET -> "goal_met"
    JourneyExitReason.TRIGGER_UNMATCHED -> "trigger_unmatched"
    JourneyExitReason.EXPIRED -> "expired"
    JourneyExitReason.CANCELLED -> "cancelled"
    JourneyExitReason.ERROR -> "error"
    JourneyExitReason.SUPERSEDED -> "superseded"
}
