package ai.nuxie.sdk

import ai.nuxie.sdk.fixtures.FixtureRunner
import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.math.BigDecimal
import org.junit.Assert.assertEquals
import org.junit.Test

class ForwardedActivityEncodingConformanceTest {
    @Test
    fun typedActivitiesBindEveryForwardingFixtureVector() {
        val suite = Json.parseToJsonElement(
            File(FixtureRunner.fixturesRoot(), "encodings/forwarded-activity.json").readText(),
        ).jsonObject
        assertEquals(NuxieActivityInfo.SCHEMA_VERSION, suite.getValue("version").jsonPrimitive.content.toInt())

        val activities = activities().associateBy { it.fixtureCase }
        val vectors = suite.getValue("vectors").jsonArray
        assertEquals(vectors.map { it.jsonObject.getValue("case").jsonPrimitive.content }.toSet(), activities.keys)

        vectors.forEach { element ->
            val vector = element.jsonObject
            val fixtureCase = vector.getValue("case").jsonPrimitive.content
            val activity = activities.getValue(fixtureCase).activity
            assertEquals(fixtureCase, vector.getValue("wireName").jsonPrimitive.content, activity.name)
            assertEquals(
                fixtureCase,
                vector.getValue("propertyKeys").jsonArray.map { it.jsonPrimitive.content }.toSet(),
                activity.properties.keys,
            )
        }
    }

    private fun activities(): List<FixtureActivity> {
        val ref = ExperienceRef("experience-1", "version-1", "journey-1")
        val purchase = PurchaseInfo(
            productId = "product-1",
            storeProductId = "store-product-1",
            placementId = "placement-1",
            experience = ref,
            price = BigDecimal("9.99"),
            displayPrice = "$9.99",
            transactionId = "transaction-1",
            isTestStore = false,
        )
        return listOf(
            fixture("experienceShown", NuxieActivity.ExperienceShown(ref)),
            fixture("experienceDismissed", NuxieActivity.ExperienceDismissed(ref, DismissReason.USER)),
            fixture("experienceErrored", NuxieActivity.ExperienceErrored(ref, "render failed")),
            fixture("journeyStarted", NuxieActivity.JourneyStarted(ref)),
            fixture("milestoneReached", NuxieActivity.MilestoneReached(ref, "milestone-1")),
            fixture("journeyConverted", NuxieActivity.JourneyConverted(ref, "journey-1")),
            fixture("journeyEnded", NuxieActivity.JourneyEnded(ref, JourneyExitReason.COMPLETED)),
            fixture("purchaseCompleted", NuxieActivity.PurchaseCompleted(purchase)),
            fixture(
                "purchaseFailed",
                NuxieActivity.PurchaseFailed(
                    PurchaseInfo(null, null, "placement-1", null, null, null, null, false),
                    "declined",
                ),
            ),
            fixture("purchaseCancelled", NuxieActivity.PurchaseCancelled(purchase)),
            fixture("purchasePending", NuxieActivity.PurchasePending(purchase)),
            fixture("restoreCompleted", NuxieActivity.RestoreCompleted),
            fixture("restoreFailed", NuxieActivity.RestoreFailed("restore failed")),
            fixture("restoreNoPurchases", NuxieActivity.RestoreNoPurchases),
            fixture("purchaseSynced", NuxieActivity.PurchaseSynced("transaction-1", "original-1", "product-1", ref)),
            fixture("featureUsed", NuxieActivity.FeatureUsed("feature-1", 2.0, "entity-1")),
            fixture("experimentExposure", NuxieActivity.ExperimentExposure(ref, "experiment-1", "variant-1", false)),
            fixture("experimentError", NuxieActivity.ExperimentError(ref, "experiment-1", "assignment failed")),
            fixture("productsUnavailable", NuxieActivity.ProductsUnavailable(ref, listOf("product-1", "product-2"))),
            fixture("screenShown", NuxieActivity.ScreenShown(ref, "screen-1")),
            fixture("screenDismissed", NuxieActivity.ScreenDismissed(ref, "screen-1")),
            fixture("experienceLoadFailed", NuxieActivity.ExperienceLoadFailed(ref, "load failed")),
            fixture("permissionResolved", NuxieActivity.PermissionResolved(ref, PermissionKind.NOTIFICATIONS, true)),
            fixture("appInstalled", NuxieActivity.AppInstalled),
            fixture("appUpdated", NuxieActivity.AppUpdated("1.0", "2.0")),
            fixture("appOpened", NuxieActivity.AppOpened),
            fixture("appBackgrounded", NuxieActivity.AppBackgrounded),
        )
    }

    private fun fixture(fixtureCase: String, activity: NuxieActivity) = FixtureActivity(fixtureCase, activity)

    private data class FixtureActivity(val fixtureCase: String, val activity: NuxieActivity)
}
