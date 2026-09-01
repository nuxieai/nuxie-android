package ai.nuxie.sdk.presentation

import ai.nuxie.sdk.billing.StoreProduct
import ai.nuxie.sdk.runtime.NuxieViewModelScalarValue
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.ProductDetails
import java.util.Locale
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class GooglePlayProductViewModelProjectionTest {
    @Test
    fun `exact live offer replaces signed previews before presentation`() {
        val projection = GooglePlayProductViewModelProjection.prepare(
            descriptor = descriptor(
                "price" to JsonPrimitive("$19.99"),
                "period" to JsonPrimitive("year"),
                "periodCount" to JsonPrimitive(1),
                "periodLabel" to JsonPrimitive("year"),
                "hasTrial" to JsonPrimitive(true),
                "trialLabel" to JsonPrimitive("7 days free"),
                "introOfferLabel" to JsonPrimitive("7-day free trial"),
                "renewalLabel" to JsonPrimitive("then $19.99/year"),
                "renewalPrice" to JsonPrimitive("$19.99"),
                "renewalPeriod" to JsonPrimitive("year"),
                "hasIntroductoryOffer" to JsonPrimitive(true),
                "hasFreeTrial" to JsonPrimitive(true),
                "introductoryPrice" to JsonPrimitive("$0.00"),
                "introductoryPeriod" to JsonPrimitive("week"),
                "introductoryPeriodCount" to JsonPrimitive(1),
                "introductoryCycles" to JsonPrimitive(1),
                "introductoryPaymentMode" to JsonPrimitive("free_trial"),
                "trialPeriodText" to JsonPrimitive("1 week"),
            ),
            products = listOf(subscription()),
            screenId = "paywall",
            locale = Locale.US,
        )

        val item = checkNotNull(projection).items.single()
        assertEquals("Live Pro", item.authoredInstanceName)
        assertEquals(0, item.listIndex)
        assertTrue(item.selected)
        assertEquals(string("€9.99"), item.values["price"])
        assertEquals(string("year"), item.values["period"])
        assertEquals(number(1), item.values["periodCount"])
        assertEquals(string("1 year"), item.values["periodLabel"])
        assertEquals(boolean(true), item.values["hasFreeTrial"])
        assertEquals(string("1 month"), item.values["trialLabel"])
        assertEquals(string("€9.99/1 year"), item.values["renewalLabel"])
        assertEquals(string("€0.00"), item.values["introductoryPrice"])
        assertEquals(string("month"), item.values["introductoryPeriod"])
        assertEquals(string("freeTrial"), item.values["introductoryPaymentMode"])
    }

    @Test
    fun `time ordered free trial and paid introduction project distinct truthful values`() {
        val projection = GooglePlayProductViewModelProjection.prepare(
            descriptor = descriptor(
                "price" to JsonPrimitive("$19.99"),
                "hasTrial" to JsonPrimitive(true),
                "trialLabel" to JsonPrimitive("7 days"),
                "introOfferLabel" to JsonPrimitive("$1.99 for 2 months"),
                "hasIntroductoryOffer" to JsonPrimitive(true),
                "hasFreeTrial" to JsonPrimitive(true),
                "introductoryPrice" to JsonPrimitive("$1.99"),
                "introductoryPeriod" to JsonPrimitive("month"),
                "introductoryPeriodCount" to JsonPrimitive(1),
                "introductoryCycles" to JsonPrimitive(2),
                "introductoryPaymentMode" to JsonPrimitive("payAsYouGo"),
                "trialPeriodText" to JsonPrimitive("1 week"),
            ),
            products = listOf(subscriptionWithTrialAndPaidIntroduction()),
            screenId = "paywall",
            locale = Locale.US,
        )

        val values = checkNotNull(projection).items.single().values
        assertEquals(string("€9.99"), values["price"])
        assertEquals(boolean(true), values["hasTrial"])
        assertEquals(string("1 week"), values["trialLabel"])
        assertEquals(string("1 week"), values["trialPeriodText"])
        assertEquals(string("€1.99"), values["introductoryPrice"])
        assertEquals(string("month"), values["introductoryPeriod"])
        assertEquals(number(2), values["introductoryCycles"])
        assertEquals(string("payAsYouGo"), values["introductoryPaymentMode"])
        assertEquals(string("€1.99/1 month for 2 months"), values["introOfferLabel"])
    }

    @Test
    fun `signed commerce claim fails closed without matching ProductDetails`() {
        val failure = assertThrows(IllegalStateException::class.java) {
            GooglePlayProductViewModelProjection.prepare(
                descriptor("price" to JsonPrimitive("$19.99")),
                products = emptyList(),
                screenId = "paywall",
            )
        }

        assertTrue(failure.message.orEmpty().contains("Placement 'primary'"))
    }

    @Test
    fun `cta without a signed commerce value claim does not require ProductDetails`() {
        assertNull(
            GooglePlayProductViewModelProjection.prepare(
                descriptor("name" to JsonPrimitive("Continue")),
                products = emptyList(),
                screenId = "paywall",
            ),
        )
    }

    @Test
    fun `one-time product uses the exact localized purchase option price`() {
        val projection = GooglePlayProductViewModelProjection.prepare(
            descriptor("price" to JsonPrimitive("$9.99")),
            products = listOf(oneTime()),
            screenId = "paywall",
            locale = Locale.JAPAN,
        )

        assertEquals(string("¥1,200"), checkNotNull(projection).items.single().values["price"])
    }

    private fun descriptor(vararg values: Pair<String, JsonPrimitive>): JsonObject {
        val productValues = listOf(
            value("placementId", JsonPrimitive("primary")),
            value("list_index", JsonPrimitive(0)),
            value("isSelected", JsonPrimitive(true)),
        ) + values.map { (path, value) -> value(path, value) }
        return JsonObject(
            mapOf(
                "journey" to JsonObject(
                    mapOf(
                        "screens" to JsonArray(
                            listOf(
                                JsonObject(
                                    mapOf(
                                        "id" to JsonPrimitive("paywall"),
                                        "defaultViewModelName" to JsonPrimitive("Runtime"),
                                    ),
                                ),
                            ),
                        ),
                        "viewModelValues" to JsonArray(productValues),
                    ),
                ),
            ),
        )
    }

    private fun value(path: String, value: JsonPrimitive): JsonObject = JsonObject(
        mapOf(
            "viewModelName" to JsonPrimitive("PaywallProduct"),
            "instanceId" to JsonPrimitive("product-1"),
            "instanceName" to JsonPrimitive("Live Pro"),
            "path" to JsonPrimitive(path),
            "value" to value,
        ),
    )

    private fun subscription(): StoreProduct = StoreProduct(
        productId = "nuxie-pro",
        storeProductId = "play-pro",
        basePlanId = "annual",
        offerId = "launch",
        placementId = "primary",
        rawProduct = productDetails(
            """{"productId":"play-pro","type":"subs","title":"Pro","name":"Pro","description":"Live description","subscriptionOfferDetails":[{"basePlanId":"annual","offerId":"launch","offerIdToken":"offer-token","pricingPhases":[{"billingPeriod":"P1M","priceCurrencyCode":"EUR","formattedPrice":"€0.00","priceAmountMicros":0,"recurrenceMode":2,"billingCycleCount":1},{"billingPeriod":"P1Y","priceCurrencyCode":"EUR","formattedPrice":"€9.99","priceAmountMicros":9990000,"recurrenceMode":1,"billingCycleCount":0}]}]}""",
        ),
        offerToken = "offer-token",
        isOfferPersonalized = false,
        productType = BillingClient.ProductType.SUBS,
    )

    private fun subscriptionWithTrialAndPaidIntroduction(): StoreProduct = StoreProduct(
        productId = "nuxie-pro",
        storeProductId = "play-pro",
        basePlanId = "annual",
        offerId = "launch",
        placementId = "primary",
        rawProduct = productDetails(
            """{"productId":"play-pro","type":"subs","title":"Pro","name":"Pro","description":"Live description","subscriptionOfferDetails":[{"basePlanId":"annual","offerId":"launch","offerIdToken":"multi-offer-token","pricingPhases":[{"billingPeriod":"P1W","priceCurrencyCode":"EUR","formattedPrice":"€0.00","priceAmountMicros":0,"recurrenceMode":2,"billingCycleCount":1},{"billingPeriod":"P1M","priceCurrencyCode":"EUR","formattedPrice":"€1.99","priceAmountMicros":1990000,"recurrenceMode":2,"billingCycleCount":2},{"billingPeriod":"P1Y","priceCurrencyCode":"EUR","formattedPrice":"€9.99","priceAmountMicros":9990000,"recurrenceMode":1,"billingCycleCount":0}]}]}""",
        ),
        offerToken = "multi-offer-token",
        isOfferPersonalized = false,
        productType = BillingClient.ProductType.SUBS,
    )

    private fun oneTime(): StoreProduct = StoreProduct(
        productId = "nuxie-lifetime",
        storeProductId = "play-lifetime",
        basePlanId = null,
        offerId = null,
        placementId = "primary",
        rawProduct = productDetails(
            """{"productId":"play-lifetime","type":"inapp","title":"Lifetime","name":"Lifetime","description":"Forever","oneTimePurchaseOfferDetails":{"formattedPrice":"¥1,200","priceAmountMicros":1200000000,"priceCurrencyCode":"JPY"}}""",
        ),
        offerToken = null,
        isOfferPersonalized = false,
        productType = BillingClient.ProductType.INAPP,
    )

    private fun productDetails(json: String): ProductDetails {
        val constructor = ProductDetails::class.java.getDeclaredConstructor(String::class.java)
        constructor.isAccessible = true
        return constructor.newInstance(json)
    }

    private fun string(value: String) = NuxieViewModelScalarValue.StringValue(value)
    private fun number(value: Int) = NuxieViewModelScalarValue.NumberValue(value.toDouble())
    private fun boolean(value: Boolean) = NuxieViewModelScalarValue.BooleanValue(value)
}
