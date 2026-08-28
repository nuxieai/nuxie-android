package ai.nuxie.sdk.events

import ai.nuxie.sdk.DismissReason
import ai.nuxie.sdk.JourneyExitReason
import ai.nuxie.sdk.NuxieActivity
import ai.nuxie.sdk.NuxieActivityValue
import ai.nuxie.sdk.journey.JourneyEventNames
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.math.BigDecimal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ActivityCurationTest {
    @Test
    fun curationBuildsTypedActivitiesFromCommittedWireProperties() {
        val ref = mapOf(
            "experience_id" to JsonPrimitive("experience-1"),
            "experience_version_id" to JsonPrimitive("version-1"),
            "journey_id" to JsonPrimitive("journey-1"),
        )

        val dismissed = ActivityCuration.activity(
            SystemEventNames.EXPERIENCE_DISMISSED,
            JsonObject(ref + ("reason" to JsonPrimitive("user_dismissed"))),
        ) as NuxieActivity.ExperienceDismissed
        assertEquals(DismissReason.USER, dismissed.reason)
        assertEquals("version-1", dismissed.experience.experienceVersion)

        val ended = ActivityCuration.activity(
            JourneyEventNames.EXITED,
            JsonObject(ref + mapOf("reason" to JsonPrimitive("cancelled"), "dismissed_by" to JsonPrimitive("user"))),
        ) as NuxieActivity.JourneyEnded
        assertEquals(JourneyExitReason.DISMISSED, ended.exitReason)

        val products = ActivityCuration.activity(
            SystemEventNames.PRODUCTS_UNAVAILABLE,
            JsonObject(ref + ("product_ids" to JsonArray(listOf(JsonPrimitive("one"), JsonPrimitive("two"))))),
        ) as NuxieActivity.ProductsUnavailable
        assertEquals(NuxieActivityValue.String("one,two"), products.properties.getValue("product_ids"))
    }

    @Test
    fun malformedCuratedActivityIsSuppressed() {
        assertNull(ActivityCuration.activity(SystemEventNames.PURCHASE_COMPLETED, JsonObject(emptyMap())))
    }

    @Test
    fun stringifiedDoubleIsSuppressedAsMalformed() {
        assertNull(
            ActivityCuration.activity(
                SystemEventNames.FEATURE_USED,
                JsonObject(
                    mapOf(
                        "feature_id" to JsonPrimitive("feature-1"),
                        "amount" to JsonPrimitive("1.5"),
                    ),
                ),
            ),
        )
    }

    @Test
    fun stringifiedBooleanIsSuppressedAsMalformed() {
        assertNull(
            ActivityCuration.activity(
                JourneyEventNames.EXPERIMENT_EXPOSURE,
                experimentExposureProperties(JsonPrimitive("true")),
            ),
        )
    }

    @Test
    fun numericBooleanIsAccepted() {
        val truthy = ActivityCuration.activity(
            JourneyEventNames.EXPERIMENT_EXPOSURE,
            experimentExposureProperties(JsonPrimitive(1)),
        ) as NuxieActivity.ExperimentExposure
        val falsey = ActivityCuration.activity(
            JourneyEventNames.EXPERIMENT_EXPOSURE,
            experimentExposureProperties(JsonPrimitive(0)),
        ) as NuxieActivity.ExperimentExposure

        assertTrue(truthy.isHoldout)
        assertFalse(falsey.isHoldout)
    }

    @Test
    fun booleanDoubleIsAccepted() {
        val truthy = ActivityCuration.activity(
            SystemEventNames.FEATURE_USED,
            featureUsedProperties(JsonPrimitive(true)),
        ) as NuxieActivity.FeatureUsed
        val falsey = ActivityCuration.activity(
            SystemEventNames.FEATURE_USED,
            featureUsedProperties(JsonPrimitive(false)),
        ) as NuxieActivity.FeatureUsed

        assertEquals(1.0, truthy.amount, 0.0)
        assertEquals(0.0, falsey.amount, 0.0)
    }

    @Test
    fun booleanDecimalIsAccepted() {
        val truthy = ActivityCuration.activity(
            SystemEventNames.PURCHASE_COMPLETED,
            purchaseProperties(JsonPrimitive(true)),
        ) as NuxieActivity.PurchaseCompleted
        val falsey = ActivityCuration.activity(
            SystemEventNames.PURCHASE_COMPLETED,
            purchaseProperties(JsonPrimitive(false)),
        ) as NuxieActivity.PurchaseCompleted

        assertEquals(BigDecimal.ONE, truthy.info.price)
        assertEquals(BigDecimal.ZERO, falsey.info.price)
    }

    private fun featureUsedProperties(amount: JsonPrimitive) = JsonObject(
        mapOf(
            "feature_id" to JsonPrimitive("feature-1"),
            "amount" to amount,
        ),
    )

    private fun purchaseProperties(price: JsonPrimitive) = JsonObject(
        mapOf(
            "product_id" to JsonPrimitive("product-1"),
            "store_product_id" to JsonPrimitive("store-product-1"),
            "price" to price,
        ),
    )

    private fun experimentExposureProperties(isHoldout: JsonPrimitive) = JsonObject(
        mapOf(
            "experience_id" to JsonPrimitive("experience-1"),
            "experiment_key" to JsonPrimitive("experiment-1"),
            "variant_key" to JsonPrimitive("variant-1"),
            "is_holdout" to isHoldout,
        ),
    )
}
