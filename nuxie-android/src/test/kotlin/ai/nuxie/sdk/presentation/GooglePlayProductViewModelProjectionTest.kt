package ai.nuxie.sdk.presentation

import ai.nuxie.sdk.billing.StoreProduct
import ai.nuxie.sdk.billing.CatalogProductRequest
import ai.nuxie.sdk.billing.ProductResolver
import ai.nuxie.sdk.billing.ProductDetailsQuery
import ai.nuxie.sdk.billing.InMemoryPurchaseEvidenceStore
import kotlinx.coroutines.runBlocking
import java.io.File
import ai.nuxie.sdk.fixtures.FixtureRunner
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.double
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
    fun `Test Store projection matches shared reference vectors`() = runBlocking {
        val fixture = Json.parseToJsonElement(
            File(FixtureRunner.fixturesRoot(), "purchases/test-store-preview.json").readText(),
        ).jsonObject
        for (element in fixture.getValue("cases").jsonArray) {
            val scenario = element.jsonObject
            val expected = scenario.getValue("expected").jsonObject
            val products = ProductResolver(
                ProductDetailsQuery { error("Test Store queried Play") }, InMemoryPurchaseEvidenceStore(), true,
            ).resolve(listOf(CatalogProductRequest(
                productId = "pro", storeProductId = "play-pro", productType = BillingClient.ProductType.SUBS,
                basePlanId = "annual", placementId = "primary", preview = scenario.getValue("preview").jsonObject,
            )))
            val result = GooglePlayProductViewModelProjection.prepare(
                descriptor(*expected.keys.map { it to JsonPrimitive("stale") }.toTypedArray()),
                products, "paywall",
            )!!.items.single().values
            for ((key, raw) in expected) {
                val value = raw.jsonPrimitive
                val scalar = when {
                    value.isString -> string(value.content)
                    value.booleanOrNull != null -> boolean(value.booleanOrNull!!)
                    else -> NuxieViewModelScalarValue.NumberValue(value.double)
                }
                assertEquals("${scenario.getValue("name")}: $key", scalar, result[key])
            }
        }
    }

    @Test
    fun `Test Store projects signed previews with explicit test labels and no Play details`() = runBlocking {
        for (hasTrial in listOf(true, false)) {
            val preview = JsonObject(mapOf(
                "name" to JsonPrimitive("Preview Pro"), "description" to JsonPrimitive("Preview description"),
                "price" to JsonPrimitive("$19.99"), "period" to JsonPrimitive("year"),
                "periodCount" to JsonPrimitive(1), "periodLabel" to JsonPrimitive("year"),
                "hasTrial" to JsonPrimitive(hasTrial), "trialLabel" to JsonPrimitive("7-day free trial"),
                "introOfferLabel" to JsonPrimitive(""), "renewalLabel" to JsonPrimitive("then $19.99/year"),
            ))
            val products = ProductResolver(
                ProductDetailsQuery { error("Test Store queried Play") },
                InMemoryPurchaseEvidenceStore(), testStore = true,
            ).resolve(listOf(CatalogProductRequest(
                productId = "pro", storeProductId = "play-pro", productType = BillingClient.ProductType.SUBS,
                basePlanId = "annual", placementId = "primary", preview = preview,
            )))
            val projected = GooglePlayProductViewModelProjection.prepare(
                descriptor = descriptor(
                    *preview.map { it.key to (it.value as JsonPrimitive) }.toTypedArray(),
                    "hasFreeTrial" to JsonPrimitive(true), "hasIntroductoryOffer" to JsonPrimitive(true),
                    "introductoryPrice" to JsonPrimitive("old"), "introductoryPeriod" to JsonPrimitive("old"),
                    "introductoryPeriodCount" to JsonPrimitive(99), "introductoryCycles" to JsonPrimitive(99),
                    "introductoryPaymentMode" to JsonPrimitive("old"), "trialPeriodText" to JsonPrimitive("old"),
                    "renewalPrice" to JsonPrimitive("old"), "renewalPeriod" to JsonPrimitive("old"),
                ), products = products, screenId = "paywall",
            )!!.items.single().values
            assertEquals(string("TEST · Preview Pro"), projected["name"])
            assertEquals(string("TEST STORE — no charge. Preview description"), projected["description"])
            assertEquals(string("TEST · $19.99"), projected["price"])
            assertEquals(boolean(hasTrial), projected["hasFreeTrial"])
            assertEquals(boolean(hasTrial), projected["hasIntroductoryOffer"])
            assertEquals(string(if (hasTrial) "TEST · FREE" else ""), projected["introductoryPrice"])
            assertEquals(string(if (hasTrial) "day" else ""), projected["introductoryPeriod"])
            assertEquals(number(if (hasTrial) 7 else 0), projected["introductoryPeriodCount"])
            assertEquals(string(if (hasTrial) "7-day free trial" else ""), projected["introOfferLabel"])
            assertEquals(string(if (hasTrial) "7-day free trial" else ""), projected["trialPeriodText"])
            assertEquals(string("then $19.99/year"), projected["renewalPrice"])
            assertEquals(string(""), projected["renewalPeriod"])
            assertNull(products.single().rawProduct)
        }
    }

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
        assertEquals(string("primary"), item.values["placementId"])
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
                "leg" to JsonObject(
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
                    ),
                ),
                "viewModelValues" to JsonArray(productValues),
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
