package ai.nuxie.sdk.journey

import ai.nuxie.sdk.features.FeatureAccess
import ai.nuxie.sdk.features.FeatureType
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import org.junit.Assert.assertEquals
import org.junit.Test

class JourneyOfferAccessTest {
    private val products = Json.parseToJsonElement("""[
        {"id":"basic","type":"subscription","entitlements":[{"featureId":"basic"}]},
        {"id":"pro","type":"subscription","entitlements":[{"featureId":"pro"}]},
        {"id":"credits","type":"consumable","entitlements":[{"featureId":"credits"}]}
    ]""") as JsonArray
    private val placements = Json.parseToJsonElement("""[
        {"id":"offer.basic","productId":"basic"},
        {"id":"offer.pro","productId":"pro"},
        {"id":"offer.credits","productId":"credits"}
    ]""") as JsonArray
    private fun access(allowed: Boolean) = FeatureAccess(allowed, false, null, FeatureType.BOOLEAN)

    @Test fun unresolvedAccessDoesNotPromptAPurchase() = runBlocking {
        assertEquals(JourneyOfferAccess.Decision.UNKNOWN,
            JourneyOfferAccess.evaluate(listOf("offer.pro"), products, placements) { null })
    }

    @Test fun existingAccessSuppressesTheSpecificOffer() = runBlocking {
        assertEquals(JourneyOfferAccess.Decision.ALREADY_ENTITLED,
            JourneyOfferAccess.evaluate(listOf("offer.pro"), products, placements) { access(true) })
    }

    @Test fun lowerTierOwnershipDoesNotSuppressAKnownUpgrade() = runBlocking {
        assertEquals(JourneyOfferAccess.Decision.ELIGIBLE,
            JourneyOfferAccess.evaluate(listOf("offer.basic", "offer.pro"), products, placements) { access(it == "basic") })
    }

    @Test fun lowerTierOwnershipDoesNotResolveAnUnknownUpgrade() = runBlocking {
        assertEquals(JourneyOfferAccess.Decision.UNKNOWN,
            JourneyOfferAccess.evaluate(listOf("offer.basic", "offer.pro"), products, placements) { if (it == "basic") access(true) else null })
    }

    @Test fun consumablesCanBePurchasedAgain() = runBlocking {
        assertEquals(JourneyOfferAccess.Decision.ELIGIBLE,
            JourneyOfferAccess.evaluate(listOf("offer.credits"), products, placements) { access(true) })
    }

    @Test fun anUnmappedPlacementIsUnknown() = runBlocking {
        assertEquals(JourneyOfferAccess.Decision.UNKNOWN,
            JourneyOfferAccess.evaluate(listOf("offer.missing"), products, placements) { access(false) })
    }
}
