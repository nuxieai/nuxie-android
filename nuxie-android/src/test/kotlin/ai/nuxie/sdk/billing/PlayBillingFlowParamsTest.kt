package ai.nuxie.sdk.billing

import ai.nuxie.sdk.LogLevel
import ai.nuxie.sdk.experiences.AuthenticatedJourneyRelease
import ai.nuxie.sdk.experiences.JourneyReleaseEnvelope
import ai.nuxie.sdk.experiences.JourneyReleaseIdentity
import ai.nuxie.sdk.experiences.JourneySchemaValidator
import ai.nuxie.sdk.fixtures.FixtureRunner
import ai.nuxie.sdk.logging.NuxieLog
import ai.nuxie.sdk.presentation.GooglePlayProductViewModelProjection
import ai.nuxie.sdk.runtime.NuxieViewModelScalarValue
import android.app.Activity
import android.util.Base64
import com.android.billingclient.api.AcknowledgePurchaseParams
import com.android.billingclient.api.AcknowledgePurchaseResponseListener
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.ConsumeParams
import com.android.billingclient.api.ConsumeResponseListener
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.ProductDetailsResponseListener
import com.android.billingclient.api.PurchasesResponseListener
import com.android.billingclient.api.QueryProductDetailsParams
import com.android.billingclient.api.QueryProductDetailsResult
import com.android.billingclient.api.QueryPurchasesParams
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadows.ShadowLog

@RunWith(RobolectricTestRunner::class)
class PlayBillingFlowParamsTest {
    @Suppress("DEPRECATION")
    @Test
    fun googlePlayOfferFallbackFixturePinsDisplayAndCheckout() {
        val root = FixtureRunner.fixturesRoot()
        val fixture = Json.parseToJsonElement(root.resolve("billing/google-play-offer-fallback.json").readText()).jsonObject
        val entry = Json.parseToJsonElement(root.resolve("journeys/rendered-text-input/release-entry.json").readText()).jsonObject
        val keys = Json.parseToJsonElement(root.resolve("journeys/planes/release.json").readText()).jsonObject
        val envelope = JourneyReleaseEnvelope.authenticate(
            entry.getValue("envelope").toString().encodeToByteArray(),
            mapOf("TEST_ONLY_DEV_KEYPAIR" to Base64.decode(keys.getValue("publicKeyBase64").jsonPrimitive.content, Base64.NO_WRAP)),
        )
        val source = JourneyReleaseEnvelope.parseObject(envelope.descriptorBytes)
        val identity = checkNotNull(JourneyReleaseIdentity.fromJson(source.getValue("identity").jsonObject))
        NuxieLog.configure(LogLevel.WARN)
        FixtureRunner.run("billing/google-play-offer-fallback.json", "google-play-offer-fallback") { vector ->
            runTest {
                val body = vector.body
                val expected = body.getValue("expected").jsonObject
                val descriptor = JsonObject(source + mapOf(
                    "products" to JsonArray(listOf(fixture.getValue("releaseProducts").jsonObject
                        .getValue(body.getValue("releaseProduct").jsonPrimitive.content))),
                    "placements" to JsonArray(listOf(body.getValue("placement"))),
                ))
                JourneySchemaValidator.validate(descriptor)
                // Exercise the catalog adapter with a validated descriptor; signature admission has its own tests.
                val catalog = JourneyProductCatalog.parse(AuthenticatedJourneyRelease(envelope, identity, descriptor, null))
                val selection = body.getValue("placement").jsonObject["googlePlay"] as? JsonObject
                if (selection?.containsKey("noOffer") == true) {
                    assertEquals(OfferSelection.None, catalog.requests.single().offerSelection)
                }
                val nativeKey = body.getValue("productDetails").jsonPrimitive.contentOrNull
                val rawProduct = nativeKey?.let {
                    nativeProductDetails(fixture.getValue("playProducts").jsonObject.getValue(it).toString())
                }
                val adapter = CapturingAdapter().apply { products = listOfNotNull(rawProduct) }
                val connection = PlayBillingConnection(BillingClientAdapterFactory { adapter }, this)
                connection.connect()
                adapter.finishSetup()
                val store = InMemoryPurchaseEvidenceStore()
                val resolver = ProductResolver(connection, store)
                ShadowLog.clear()
                try {
                    val failureMessage = expected["failure"]?.jsonPrimitive?.content
                    if (failureMessage != null) {
                        try {
                            resolver.resolve(catalog.requests)
                            fail("Expected product resolution to fail")
                        } catch (failure: ProductResolutionException) {
                            assertTrue(failure.message.orEmpty().contains(failureMessage))
                        }
                        assertTrue(store.loadProductMappings().isEmpty())
                        assertNull(adapter.launchedParams)
                        return@runTest
                    }
                    val product = resolver.resolve(catalog.requests).single()
                    val target = expected.getValue("purchase").jsonObject
                    assertEquals(target.getValue("storeProductId").jsonPrimitive.content, product.storeProductId)
                    assertEquals(target.getValue("basePlanId").jsonPrimitive.contentOrNull, product.basePlanId)
                    assertEquals(target.getValue("purchaseOptionId").jsonPrimitive.contentOrNull, product.purchaseOptionId)
                    assertEquals(target.getValue("offerId").jsonPrimitive.contentOrNull, product.offerId)
                    assertEquals(target.getValue("offerToken").jsonPrimitive.content, product.offerToken)
                    assertEquals(product.offerId, store.loadProductMappings().single().offerId)

                    val display = expected.getValue("display").jsonObject
                    val values = checkNotNull(GooglePlayProductViewModelProjection.prepare(
                        projectionDescriptor(display), listOf(product), "paywall", Locale.US,
                    )).items.single().values
                    display.forEach { (key, value) ->
                        val primitive = value.jsonPrimitive
                        val scalar = when {
                            primitive.isString -> NuxieViewModelScalarValue.StringValue(primitive.content)
                            primitive.booleanOrNull != null -> NuxieViewModelScalarValue.BooleanValue(primitive.booleanOrNull!!)
                            else -> NuxieViewModelScalarValue.NumberValue(primitive.double)
                        }
                        assertEquals(key, scalar, values[key])
                    }
                    val fallbackLog = expected.getValue("fallbackLog").jsonPrimitive.contentOrNull
                    val warnings = ShadowLog.getLogsForTag("Nuxie").filter { it.type == android.util.Log.WARN }
                    if (fallbackLog == null) assertTrue(warnings.isEmpty()) else {
                        val warning = warnings.single().msg
                        assertTrue(warning.startsWith("$fallbackLog productId="))
                        assertTrue(warning.contains("offerId=<redacted hmac-sha256:"))
                        assertTrue(!warning.contains(product.offerToken!!))
                    }

                    connection.launch(Robolectric.buildActivity(Activity::class.java).get(),
                        CheckoutRequest(product.forJourneyCheckout("journey"), "account-hash", null))
                    val params = checkNotNull(adapter.launchedParams).zzk().single() as BillingFlowParams.ProductDetailsParams
                    assertSame(rawProduct, params.zza())
                    assertEquals(target.getValue("offerToken").jsonPrimitive.content, params.zzb())
                } finally {
                    connection.close()
                }
            }
        }
    }

    private fun projectionDescriptor(display: JsonObject): JsonObject {
        fun value(path: String, value: kotlinx.serialization.json.JsonElement) = JsonObject(mapOf(
            "viewModelName" to JsonPrimitive("PaywallProduct"), "instanceId" to JsonPrimitive("product-1"),
            "instanceName" to JsonPrimitive("Pro"), "path" to JsonPrimitive(path), "value" to value,
        ))
        val values = listOf(
            JsonObject(mapOf("viewModelName" to JsonPrimitive("Runtime"), "path" to JsonPrimitive("paywall/products"),
                "value" to JsonArray(listOf(JsonObject(mapOf("vmInstanceId" to JsonPrimitive("product-1"))))))),
            value("placementId", JsonPrimitive("primary")), value("list_index", JsonPrimitive(0)),
        ) + display.keys.map { value(it, JsonPrimitive("stale published terms")) }
        return JsonObject(mapOf(
            "leg" to JsonObject(mapOf("screens" to JsonArray(listOf(JsonObject(mapOf(
                "id" to JsonPrimitive("paywall"), "defaultViewModelName" to JsonPrimitive("Runtime"),
            )))))),
            "viewModelValues" to JsonArray(values),
        ))
    }

    @Suppress("DEPRECATION")
    @Test
    fun upgradeLaunchBuildsTheExactBillingFlowParams() = runTest {
        val adapter = CapturingAdapter()
        val connection = PlayBillingConnection(
            factory = BillingClientAdapterFactory { adapter },
            scope = this,
        )
        connection.connect()
        adapter.finishSetup()
        val rawProduct = productDetails()
        val product = StoreProduct(
            productId = "pro",
            storeProductId = "play-pro",
            basePlanId = "annual",
            offerId = "launch",
            placementId = "primary",
            rawProduct = rawProduct,
            offerToken = "offer-token",
            isOfferPersonalized = true,
            productType = BillingClient.ProductType.SUBS,
        )

        connection.launch(
            Robolectric.buildActivity(Activity::class.java).get(),
            CheckoutRequest(
                product,
                "account-hash",
                SubscriptionReplacement("old-token", ReplacementMode.DEFERRED),
            ),
        )

        val params = checkNotNull(adapter.launchedParams)
        val productParams = params.zzk().single() as BillingFlowParams.ProductDetailsParams
        assertSame(rawProduct, productParams.zza())
        assertEquals("offer-token", productParams.zzb())
        assertEquals("account-hash", params.zze())
        assertTrue(params.zzt())
        assertEquals("old-token", params.zzh())
        assertEquals(BillingFlowParams.SubscriptionUpdateParams.ReplacementMode.DEFERRED, params.zzb())
        connection.close()
    }

    private fun productDetails(): ProductDetails = nativeProductDetails(
        """{"productId":"play-pro","type":"subs","title":"Pro","name":"Pro","description":"Pro","packageName":"com.example.app"}""",
    )

    private fun nativeProductDetails(json: String): ProductDetails = ProductDetails::class.java
        .getDeclaredConstructor(String::class.java)
        .apply { isAccessible = true }
        .newInstance(json)

    private class CapturingAdapter : BillingClientAdapter {
        override var isReady = false
        private lateinit var listener: BillingClientStateListener
        var launchedParams: BillingFlowParams? = null
        var products: List<ProductDetails>? = null

        override fun startConnection(listener: BillingClientStateListener) {
            this.listener = listener
        }

        fun finishSetup() {
            isReady = true
            listener.onBillingSetupFinished(result(BillingClient.BillingResponseCode.OK))
        }

        override fun endConnection() = Unit
        override fun queryProductDetailsAsync(
            params: QueryProductDetailsParams,
            listener: ProductDetailsResponseListener,
        ) {
            products?.let { listener.onProductDetailsResponse(
                result(BillingClient.BillingResponseCode.OK), QueryProductDetailsResult.create(it, emptyList()),
            ) }
        }

        override fun launchBillingFlow(activity: Activity, params: BillingFlowParams): BillingResult {
            launchedParams = params
            return result(BillingClient.BillingResponseCode.OK)
        }

        override fun queryPurchasesAsync(
            params: QueryPurchasesParams,
            listener: PurchasesResponseListener,
        ) = Unit

        override fun acknowledgePurchase(
            params: AcknowledgePurchaseParams,
            listener: AcknowledgePurchaseResponseListener,
        ) = Unit

        override fun consumeAsync(params: ConsumeParams, listener: ConsumeResponseListener) = Unit

        private fun result(code: Int) = BillingResult.newBuilder()
            .setResponseCode(code)
            .setDebugMessage("test")
            .build()
    }
}
