package ai.nuxie.sdk.billing

import ai.nuxie.sdk.experiences.AuthenticatedJourneyRelease
import ai.nuxie.sdk.experiences.JourneyReleaseEnvelope
import ai.nuxie.sdk.experiences.JourneyReleaseIdentity
import ai.nuxie.sdk.fixtures.FixtureRunner
import android.util.Base64
import com.android.billingclient.api.BillingClient
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class JourneyBestOfferTest {
    @Test fun longestTrialWins() = runBlocking {
        assertChoice("long", listOf(offer("short", 0, "P1W"), offer("long", 0, "P1M"), offer("cheap", 1)))
    }

    @Test fun cheapestIntroWinsWithoutTrial() = runBlocking {
        assertChoice("cheap", listOf(offer("expensive", 5_000_000), offer("cheap", 1_000_000)))
    }

    @Test fun noEligibleOfferUsesPlainBase() = runBlocking {
        assertChoice(null, emptyList())
    }

    @Test fun ignoredTrialAndIntroAreNeverPickedAutomatically() = runBlocking {
        assertChoice("available", listOf(offer("ignored-trial", 0, "P1Y", ignored = true),
            offer("ignored-intro", 1, ignored = true), offer("available", 2_000_000)))
    }

    @Test fun pinnedOfferStaysExact() = runBlocking {
        assertChoice("pinned", listOf(offer("long", 0, "P1M"), offer("pinned", 5_000_000)), "pinned")
    }

    @Test fun oneTimePurchaseKeepsItsPurchaseOption() {
        val request = JourneyProductCatalog.parse(release(null, "nonConsumable")).requests.single()
        assertEquals(OfferSelection.None, request.offerSelection)
        assertEquals("standard", request.purchaseOptionId)
    }

    private suspend fun assertChoice(expected: String?, offers: List<PlaySubscriptionOffer>, pinned: String? = null) {
        // Exercise both spellings of an absent offer through the real Journey adapter.
        for (explicitNull in listOf(false, true)) {
            val requests = JourneyProductCatalog.parse(release(pinned, explicitNull = explicitNull)).requests
            val resolver = ProductResolver(ProductDetailsQuery {
                ProductDetailsQueryResult.Success(listOf(PlayProductDetails("play-pro", BillingClient.ProductType.SUBS,
                    null, listOf(offer(null)) + offers)))
            }, InMemoryPurchaseEvidenceStore())
            val chosen = resolver.resolve(requests).single()
            assertEquals(expected, chosen.offerId)
            assertEquals(if (expected == null) "base-token" else "$expected-token", chosen.offerToken)
            assertEquals("annual", chosen.basePlanId)
        }
    }

    private fun offer(id: String?, price: Long = 0, period: String = "P1M", ignored: Boolean = false) =
        PlaySubscriptionOffer("annual", id, if (id == null) "base-token" else "$id-token",
            if (ignored) listOf("nuxie-ignore-offer") else emptyList(),
            (if (id == null) emptyList() else listOf(PlayPricingPhase(price, period, 1, PlayRecurrenceMode.FINITE))) +
                PlayPricingPhase(9_990_000, "P1M", 0, PlayRecurrenceMode.INFINITE))

    private fun release(pinned: String?, type: String = "subscription", explicitNull: Boolean = false): AuthenticatedJourneyRelease {
        val root = FixtureRunner.fixturesRoot()
        val entry = Json.parseToJsonElement(root.resolve("journeys/rendered-text-input/release-entry.json").readText()).jsonObject
        val keys = Json.parseToJsonElement(root.resolve("journeys/planes/release.json").readText()).jsonObject
        val envelope = JourneyReleaseEnvelope.authenticate(entry.getValue("envelope").toString().encodeToByteArray(),
            mapOf("TEST_ONLY_DEV_KEYPAIR" to Base64.decode(keys.getValue("publicKeyBase64").jsonPrimitive.content, Base64.NO_WRAP)))
        val original = JourneyReleaseEnvelope.parseObject(envelope.descriptorBytes)
        val descriptor = JsonObject(original + mapOf(
            "products" to buildJsonArray { add(buildJsonObject {
                put("id", "pro"); put("type", type); put("entitlements", JsonArray(emptyList()))
                putJsonObject("store") {
                    put("platform", "google_play"); put("productId", "play-pro"); put("productType", type)
                    if (type == "subscription") put("basePlanId", "annual") else put("purchaseOptionId", "standard")
                }
            }) },
            "placements" to buildJsonArray { add(buildJsonObject {
                put("id", "primary"); put("productId", "pro")
                if (pinned != null) putJsonObject("googlePlay") { put("offerId", pinned) }
                else if (explicitNull) put("googlePlay", JsonNull)
            }) },
        ))
        // Catalog adapter test only; signature admission is covered by release tests.
        return AuthenticatedJourneyRelease(envelope,
            checkNotNull(JourneyReleaseIdentity.fromJson(original.getValue("identity").jsonObject)), descriptor, null)
    }
}
