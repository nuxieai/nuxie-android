package ai.nuxie.sdk.events

import ai.nuxie.sdk.DismissReason
import ai.nuxie.sdk.JourneyExitReason
import ai.nuxie.sdk.NuxieActivity
import ai.nuxie.sdk.NuxieActivityValue
import ai.nuxie.sdk.journey.JourneyEventNames
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
}
