package ai.nuxie.sdk.runtime

import ai.nuxie.sdk.LogLevel
import ai.nuxie.sdk.NuxieEnvironment
import ai.nuxie.sdk.billing.JourneyCommercePreparing
import ai.nuxie.sdk.billing.JourneyCommerceSession
import ai.nuxie.sdk.billing.NuxiePurchaseDelegate
import ai.nuxie.sdk.billing.PurchaseResult
import ai.nuxie.sdk.billing.RestoreResult
import ai.nuxie.sdk.billing.StoreProduct
import ai.nuxie.sdk.core.NuxieCore
import ai.nuxie.sdk.experiences.AcquiredJourneyRelease
import ai.nuxie.sdk.experiences.AuthenticatedJourneyRelease
import ai.nuxie.sdk.experiences.JourneyReleaseEnvelope
import ai.nuxie.sdk.experiences.JourneyReleaseIdentity
import ai.nuxie.sdk.journey.JourneyExecutionFence
import ai.nuxie.sdk.network.HttpTransport
import ai.nuxie.sdk.presentation.CloseReason
import ai.nuxie.sdk.presentation.ExperiencePresentationService
import ai.nuxie.sdk.presentation.GooglePlayProductViewModelProjection
import ai.nuxie.sdk.presentation.JourneyPresentationActionResult
import ai.nuxie.sdk.presentation.JourneyPresentationFences
import ai.nuxie.sdk.presentation.JourneyPresentationOwner
import ai.nuxie.sdk.presentation.PresentationRegistry
import ai.nuxie.sdk.presentation.PresentationScreenHandle
import android.app.Activity
import android.util.Base64
import androidx.test.platform.app.InstrumentationRegistry
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.ProductDetails
import java.io.Closeable
import java.io.File
import java.util.UUID
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PlacementPurchaseDeviceTest {
    @Test
    fun nativePlanThenBuyDispatchesEachPlacementOfOneProduct() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        assertTrue("Pinned native runtime must load", NuxieRuntime.shared.isAvailable)
        val context = instrumentation.targetContext
        val writer = InstrumentationRegistry.getArguments().getString("nuxiePaywallWriter") ?: "placement"
        println("Native paywall writer: $writer")
        val assets = instrumentation.context.assets
        val fixturePath = "runtime/paywall-two-placement-$writer"
        val directory = File(context.cacheDir, "placement-${UUID.randomUUID()}").apply { mkdirs() }
        val scene = File(directory, "screen.riv").apply { writeBytes(assets.open("$fixturePath/screen.riv").use { it.readBytes() }) }
        val rows = Json.parseToJsonElement(assets.open("$fixturePath/view-model-values.json").bufferedReader().use { it.readText() }).jsonArray
        val purchased = LinkedBlockingQueue<StoreProduct>()
        val core = NuxieCore(context, "pk_test_placement", NuxieEnvironment.DEVELOPMENT, LogLevel.NONE, null,
            purchaseDelegate = object : NuxiePurchaseDelegate {
                override suspend fun purchase(product: StoreProduct): PurchaseResult {
                    purchased.add(product)
                    return PurchaseResult.Cancelled
                }
                override suspend fun restorePurchases() = RestoreResult.NoPurchases
            }, overrides = NuxieCore.Overrides(registerLifecycle = false, requestInitialProfileRefresh = false,
                eventDatabaseFile = File(directory, "events.db"), profileCacheDirectory = File(directory, "profiles"),
                transport = HttpTransport { HttpTransport.Response(503, "{}".encodeToByteArray()) }))
        core.identity.setDistinctId("customer-1")
        core.purchases.awaitInitialProjection()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val products = listOf("monthly", "annual").map { plan ->
            val constructor = ProductDetails::class.java.getDeclaredConstructor(String::class.java).apply { isAccessible = true }
            val details = constructor.newInstance("""{"productId":"play-pro","type":"subs","title":"Pro","name":"Pro","description":"Pro","subscriptionOfferDetails":[{"basePlanId":"$plan","offerIdToken":"token-$plan","pricingPhases":[{"billingPeriod":"${if (plan == "annual") "P1Y" else "P1M"}","priceCurrencyCode":"USD","formattedPrice":"$9.99","priceAmountMicros":9990000,"recurrenceMode":1,"billingCycleCount":0}]}]}""")
            StoreProduct("product-pro", "play-pro", plan, offerId = null, placementId = "placement-$plan",
                rawProduct = details, offerToken = "token-$plan", isOfferPersonalized = false, productType = BillingClient.ProductType.SUBS)
        }
        val fixture = Json.parseToJsonElement(assets.open("journeys/planes/release.json").bufferedReader().use { it.readText() }).jsonObject
        val entry = fixture.getValue("renderedEntry").jsonObject
        val envelope = JourneyReleaseEnvelope.authenticate(entry.getValue("envelope").toString().encodeToByteArray(),
            mapOf("TEST_ONLY_DEV_KEYPAIR" to Base64.decode(fixture.getValue("publicKeyBase64").jsonPrimitive.content, Base64.NO_WRAP)))
        val original = Json.parseToJsonElement(Base64.decode(entry.getValue("envelope").jsonObject.getValue("descriptorBytesBase64").jsonPrimitive.content, Base64.NO_WRAP).decodeToString()).jsonObject
        val leg = original.getValue("leg").jsonObject
        val descriptor = JsonObject(original + mapOf(
            "leg" to JsonObject(leg + ("screens" to buildJsonArray { add(buildJsonObject {
                put("id", "screen_welcome"); put("defaultViewModelName", "Runtime"); put("defaultInstanceId", "root-sdk-id"); put("responseCaptures", JsonArray(emptyList()))
            }) })),
            "viewModelValues" to JsonArray(rows + listOf("monthly", "annual").mapIndexed { index, plan -> buildJsonObject {
                put("viewModelName", "PaywallProduct"); put("instanceId", plan); put("instanceName", plan.replaceFirstChar(Char::uppercase)); put("path", "list_index"); put("value", index)
            } }),
        ))
        val identity = checkNotNull(JourneyReleaseIdentity.fromJson(entry.getValue("locator").jsonObject, additionalKeys = setOf("legId")))
        val release = AuthenticatedJourneyRelease(envelope, identity, descriptor, null)
        val launched = CompletableDeferred<String>()
        val presentations = ExperiencePresentationService(emit = { _, _, _ -> }, scope = scope,
            runtimeAvailable = { NuxieRuntime.shared.isAvailable }, currentDistinctId = { "customer-1" }, isAppForeground = { true },
            launch = { launched.complete(it) }, commerce = JourneyCommercePreparing { JourneyCommerceSession(products, core.purchases) })
        val fence = JourneyExecutionFence()
        val fences = JourneyPresentationFences(core.identity, core.identity.captureScope(), fence, fence.token())
        val owner = JourneyPresentationOwner("journey-1", "customer-1")
        val renderer = checkNotNull(NuxieRuntime.shared.newAndroidVulkanRenderer(320, 150))
        var id: String? = null
        var host: PresentationScreenHandle? = null
        try {
            val file = checkNotNull(NuxieRuntime.shared.importFile(renderer, scene.readBytes()))
            try {
                val artboard = checkNotNull(file.newArtboard("Paywall"))
                try {
                    val projection = checkNotNull(GooglePlayProductViewModelProjection.prepare(descriptor, products, "screen_welcome"))
                    val state = NuxieRuntime.shared.bindViewModelList(file, artboard, projection)
                    try {
                        val ids = state.nativeSnapshot().values.single { it.name == "products" }.listItemIds
                        for ((index, plan) in listOf("monthly", "annual").withIndex()) {
                            val child = state.acquireListItem("paywall/products", index, ids[index])
                            try { assertTrue(state.linkViewModel(plan, child)) } finally { child.close() }
                        }
                        val player = file.newExperiencePlayer(artboard, "Paywall")
                        try {
                            repeat(20) { player.stepTyped(elapsedSeconds = 0.016) }
                            val pending = async { presentations.presentJourney(fences, release, "screen_welcome", owner.journeyId, owner.distinctId,
                                presentations.reserveJourney(owner.distinctId), acquire = { AcquiredJourneyRelease(identity, mapOf("renders/main.riv" to scene), scene, protection = Closeable {}) }, onOutcome = {}) }
                            id = withTimeout(10_000) { launched.await() }
                            PresentationRegistry.reportFirstFrame(checkNotNull(id))
                            pending.await()
                            var activity: Activity? = null
                            instrumentation.runOnMainSync { activity = Activity() }
                            host = object : PresentationScreenHandle {
                                override fun requestCloseFromService(reason: CloseReason) = true
                                override fun screenCloseReason(): CloseReason? = null
                                override fun finishAfterServiceClose() = Unit
                                override fun purchaseActivity() = activity
                            }
                            assertTrue(PresentationRegistry.attach(checkNotNull(id), checkNotNull(host)))
                            fun tap(x: Float, y: Float): List<NuxiePlayerStepOutcome> = listOf(
                                player.stepTyped(elapsedSeconds = 0.0, pointers = listOf(NuxiePlayerPointerEvent(NuxiePlayerPointerKind.DOWN, x, y, 1, 0f))),
                                player.stepTyped(elapsedSeconds = 0.0, pointers = listOf(NuxiePlayerPointerEvent(NuxiePlayerPointerKind.UP, x, y, 1, 0.1f))),
                                player.stepTyped(elapsedSeconds = 0.016))
                            for ((index, plan) in listOf("annual", "monthly").withIndex()) {
                                val selected = tap(if (plan == "annual") 240f else 80f, 40f)
                                val frame = state.captureSnapshot(selected.flatMap { it.viewModelChanges }).values
                                PresentationRegistry.reportRuntimeStep(checkNotNull(id), NuxiePlayerStepOutcome(true, emptyList(), emptyList(), emptyList(), emptyList()), (index + 1).toULong(), frame)
                                val buy = tap(160f, 120f)
                                assertTrue("Buy must come from a native pointer", buy.any { it.hostCommands.isNotEmpty() || it.events.isNotEmpty() })
                                val action = checkNotNull(presentations.resolveJourneyAction(owner, Json.parseToJsonElement("""{"type":"purchase","placementId":{"ref":{"kind":"path","path":"paywall.selectedProduct.placementId","isRelative":false}}}""").jsonObject, null))
                                assertEquals("placement-$plan", action.getValue("placementId").jsonPrimitive.content)
                                assertEquals(JourneyPresentationActionResult.AwaitingOutcome, presentations.dispatchJourneyAction(owner, action, "buy-$index"))
                                val product = checkNotNull(purchased.poll(10, TimeUnit.SECONDS))
                                assertEquals("product-pro", product.productId)
                                assertEquals("placement-$plan", product.placementId)
                                assertEquals(plan, product.basePlanId)
                            }
                        } finally { player.close() }
                    } finally { state.close() }
                } finally { artboard.close() }
            } finally { file.close() }
        } finally {
            if (id != null && host != null) PresentationRegistry.detach(checkNotNull(id), checkNotNull(host))
            presentations.shutdownOwnedBy("customer-1")
            renderer.close(); scope.cancel(); core.stopAndAwait(); directory.deleteRecursively()
        }
    }
}
