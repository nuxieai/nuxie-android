package ai.nuxie.sdk.billing

import ai.nuxie.sdk.Nuxie
import ai.nuxie.sdk.NuxieConfiguration
import ai.nuxie.sdk.NuxieEnvironment
import ai.nuxie.sdk.NuxieEvent
import ai.nuxie.sdk.identity.IdentityService
import ai.nuxie.sdk.journey.JourneyRunJournal
import ai.nuxie.sdk.journey.JourneyStorageScope
import ai.nuxie.sdk.network.ProfileDeliveryAuthority
import kotlinx.serialization.json.*
import ai.nuxie.sdk.core.NuxieCore
import ai.nuxie.sdk.events.SQLiteEventStore
import ai.nuxie.sdk.events.SystemEventNames
import ai.nuxie.sdk.network.HttpTransport
import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.TextView
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test

class TestStoreHostActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(TextView(this).apply { text = "Test Store qualification host" })
    }
}

/** Public facade + real main looper/dialog; only HTTP and forbidden Play creation are replaced. */
class TestStoreDeviceTest {
    @Test
    fun publicCheckoutAndRestoreUseNativeTestChoicesWithoutPlay() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val monitor = Instrumentation.ActivityMonitor(TestStoreHostActivity::class.java.name, null, false)
        instrumentation.addMonitor(monitor)
        val events = CopyOnWriteArrayList<NuxieEvent>()
        val billingConstructions = AtomicInteger()
        var activity: Activity? = null
        try {
            instrumentation.context.startActivity(Intent(instrumentation.context, TestStoreHostActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            activity = checkNotNull(monitor.waitForActivityWithTimeout(10_000))
            instrumentation.waitForIdleSync()
            Nuxie.overridesForTesting = NuxieCore.Overrides(
                transport = object : HttpTransport {
                    override fun execute(request: HttpTransport.Request) = HttpTransport.Response(503, ByteArray(0))
                },
                requestInitialProfileRefresh = false,
                billingClientFactory = BillingClientAdapterFactory {
                    billingConstructions.incrementAndGet()
                    error("Test Store must not construct Play Billing")
                },
            )
            val owner = activity
            instrumentation.runOnMainSync {
                Nuxie.setup(owner, NuxieConfiguration("pk_test_device_store_${System.nanoTime()}").apply {
                    environment = NuxieEnvironment.DEVELOPMENT
                    testStoreEnabled = true
                    beforeSend = { events += it; it }
                })
            }
            val product = StoreProduct(
                productId = "qualification-pro", storeProductId = "play-pro", basePlanId = null,
                offerId = null, placementId = "primary", rawProduct = null, offerToken = null,
                isOfferPersonalized = false, productType = "inapp",
            )
            for (choice in listOf("Purchased", "Pending", "Cancelled", "Failed")) {
                val result = async(Dispatchers.Default) { Nuxie.purchase(owner, product) }
                val button = awaitNode(choice, clickable = true)
                assertNotNull(awaitNode("no charge, no Google Play transaction", clickable = false))
                if (choice == "Purchased") {
                    // Accessibility nodes precede window animation/composition. Capture only
                    // after the real UI has gone idle, while the dialog remains open.
                    instrumentation.waitForIdleSync()
                    instrumentation.uiAutomation.waitForIdle(500, 5_000)
                    val screenshot = instrumentation.uiAutomation.takeScreenshot()
                    assertNotNull(screenshot)
                    File(instrumentation.targetContext.cacheDir, "test-store-dialog.png").outputStream().use {
                        check(screenshot.compress(Bitmap.CompressFormat.PNG, 100, it))
                    }
                    screenshot.recycle()
                }
                assertTrue(button.performAction(AccessibilityNodeInfo.ACTION_CLICK))
                val outcome = withTimeout(10_000) { result.await() }
                when (choice) {
                    "Purchased" -> assertEquals(PurchaseResult.Purchased, outcome)
                    "Pending" -> assertEquals(PurchaseResult.Pending, outcome)
                    "Cancelled" -> assertEquals(PurchaseResult.Cancelled, outcome)
                    else -> assertTrue(outcome is PurchaseResult.Failed)
                }
            }
            for (choice in listOf("Restored", "No Purchases", "Failed")) {
                val result = async(Dispatchers.Default) { Nuxie.restorePurchases() }
                val button = awaitNode(choice, clickable = true)
                assertTrue(button.performAction(AccessibilityNodeInfo.ACTION_CLICK))
                val outcome = withTimeout(10_000) { result.await() }
                when (choice) {
                    "Restored" -> assertEquals(RestoreResult.Restored, outcome)
                    "No Purchases" -> assertEquals(RestoreResult.NoPurchases, outcome)
                    else -> assertTrue(outcome is RestoreResult.Failed)
                }
            }
            val destroyed = async(Dispatchers.Default) { Nuxie.purchase(owner, product) }
            awaitNode("Purchased", clickable = true)
            instrumentation.runOnMainSync { owner.finish() }
            assertEquals(PurchaseResult.Cancelled, withTimeout(10_000) { destroyed.await() })
            val completion = events.single { it.name == SystemEventNames.PURCHASE_COMPLETED }
            assertEquals(true, completion.properties["test_store"])
            assertEquals("checkout", completion.properties["source"])
            assertTrue((completion.properties["transaction_id"] as String).startsWith("nuxie-test-"))
            assertEquals(0, billingConstructions.get())
        } finally {
            Nuxie.shutdownAndAwait()
            Nuxie.overridesForTesting = null
            activity?.let { owner -> instrumentation.runOnMainSync { owner.finish() } }
            instrumentation.removeMonitor(monitor)
        }
    }

    @Test
    fun signedJourneyCheckoutAndRestoreCompleteThroughNativeChoices() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val fixture = instrumentation.context.assets.open("journeys/planes/test-store-orchestration.json")
            .use { Json.parseToJsonElement(it.readBytes().decodeToString()).jsonObject }
        val monitor = Instrumentation.ActivityMonitor(TestStoreHostActivity::class.java.name, null, false)
        instrumentation.addMonitor(monitor)
        var activity: Activity? = null
        val cases = listOf(
            Triple("purchase", "Purchased", SystemEventNames.PURCHASE_COMPLETED),
            Triple("purchase", "Pending", SystemEventNames.PURCHASE_PENDING),
            Triple("purchase", "Cancelled", SystemEventNames.PURCHASE_CANCELLED),
            Triple("purchase", "Failed", SystemEventNames.PURCHASE_FAILED),
            Triple("restore", "Restored", SystemEventNames.RESTORE_COMPLETED),
            Triple("restore", "No Purchases", SystemEventNames.RESTORE_NO_PURCHASES),
            Triple("restore", "Failed", SystemEventNames.RESTORE_FAILED),
        )
        try {
            instrumentation.context.startActivity(Intent(instrumentation.context, TestStoreHostActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            activity = checkNotNull(monitor.waitForActivityWithTimeout(10_000))
            val host = activity
            for ((action, choice, expectedEvent) in cases) {
                val entry = fixture.getValue("entries").jsonObject.getValue(action).jsonObject
                val locator = entry.getValue("locator").jsonObject
                val descriptor = Json.parseToJsonElement(android.util.Base64.decode(
                    entry.getValue("envelope").jsonObject.getValue("descriptorBytesBase64").jsonPrimitive.content,
                    android.util.Base64.NO_WRAP).decodeToString()).jsonObject
                val render = descriptor.getValue("render").jsonObject
                val artifacts = (render.getValue("assets").jsonArray.toList() + render.getValue("nux"))
                    .associate { value -> value.jsonObject.let { it.getValue("key").jsonPrimitive.content to it } }
                val appId = locator.getValue("appId").jsonPrimitive.content
                val environment = locator.getValue("environment").jsonPrimitive.content
                val profile = orchestrationProfile(entry).toString().encodeToByteArray()
                val owner = "test-store-journey-${System.nanoTime()}"
                val identity = IdentityService(context).apply { setDistinctId(owner) }
                val eventStore = SQLiteEventStore(context, databaseFile = File(context.cacheDir, "$owner.db"))
                val events = CopyOnWriteArrayList<NuxieEvent>()
                val billingConstructions = AtomicInteger()
                val journal = JourneyRunJournal(File(context.filesDir, "nuxie"), owner,
                    JourneyStorageScope(ProfileDeliveryAuthority(appId, environment)))
                Nuxie.overridesForTesting = NuxieCore.Overrides(
                    identity = identity,
                    store = eventStore,
                    transport = object : HttpTransport {
                        override fun execute(request: HttpTransport.Request): HttpTransport.Response {
                            if (request.url.path == "/profile") return HttpTransport.Response(200, profile,
                                mapOf("ETag" to "\"test-store-journey\"", "Nuxie-App-Id" to appId,
                                    "Nuxie-App-Environment" to environment))
                            if (request.url.host == "test-store-fixture.example") {
                                val root = fixture.getValue("artifactFixture").jsonPrimitive.content
                                val bytes = instrumentation.context.assets.open("$root${request.url.path}").use { it.readBytes() }
                                val artifact = artifacts.getValue(request.url.path.removePrefix("/"))
                                return HttpTransport.Response(200, bytes, mapOf("Content-Type" to artifact.getValue("contentType").jsonPrimitive.content))
                            }
                            return HttpTransport.Response(503, ByteArray(0))
                        }
                    },
                    billingClientFactory = BillingClientAdapterFactory {
                        billingConstructions.incrementAndGet()
                        error("Journey Test Store must not construct Play Billing")
                    },
                )
                try {
                    instrumentation.runOnMainSync {
                        Nuxie.setup(host, NuxieConfiguration("pk_test_$owner").apply {
                            this.environment = NuxieEnvironment.DEVELOPMENT
                            testStoreEnabled = true
                            beforeSend = { events += it; it }
                        })
                    }
                    val button = awaitNode(choice, clickable = true)
                    val waitingRun = journal.runs().single()
                    assertEquals(action, waitingRun.stepId)
                    val effectId = waitingRun.effectReceipts.getValue(action)
                    assertTrue(eventStore.hasEvent(SystemEventNames.SCREEN_SHOWN, owner))
                    if (action == "purchase") assertNotNull(awaitNode("Monthly", clickable = false))
                    assertTrue(button.performAction(AccessibilityNodeInfo.ACTION_CLICK))
                    withTimeout(15_000) {
                        while (!eventStore.hasEvent(expectedEvent, owner)) kotlinx.coroutines.delay(25)
                    }
                    val outcome = events.single { it.name == expectedEvent }
                    withTimeout(15_000) {
                        while (eventStore.isLocalRoutePending(outcome.id)) kotlinx.coroutines.delay(25)
                    }
                    assertEquals(true, outcome.properties["test_store"])
                    assertEquals(owner, outcome.distinctId)
                    if (choice == "Pending") {
                        assertNotEquals(effectId, outcome.id)
                        assertEquals(action, journal.runs().single().stepId)
                        assertEquals(effectId, journal.runs().single().effectReceipts.getValue(action))
                        assertNull(journal.checkmark(locator.getValue("experienceId").jsonPrimitive.content))
                        assertFalse(eventStore.hasEvent("$" + "journey_leg_completed", owner))
                    } else {
                        assertEquals(effectId, outcome.id)
                        withTimeout(15_000) {
                            while (journal.checkmark(locator.getValue("experienceId").jsonPrimitive.content) == null
                                || journal.runs().isNotEmpty()
                                || !eventStore.hasEvent("$" + "journey_leg_completed", owner)) kotlinx.coroutines.delay(25)
                        }
                        assertTrue(journal.runs().isEmpty())
                    }
                    assertEquals(0, billingConstructions.get())
                } finally {
                    Nuxie.shutdownAndAwait()
                    Nuxie.overridesForTesting = null
                }
            }
        } finally {
            Nuxie.shutdownAndAwait()
            Nuxie.overridesForTesting = null
            activity?.let { host -> instrumentation.runOnMainSync { host.finish() } }
            instrumentation.removeMonitor(monitor)
        }
    }

    private fun orchestrationProfile(entry: JsonObject): JsonObject {
        val locator = entry.getValue("locator").jsonObject
        val envelope = entry.getValue("envelope").jsonObject
        fun obj(vararg fields: Pair<String, JsonElement>) = JsonObject(mapOf(*fields))
        val empty = JsonObject(emptyMap())
        return obj(
            "schemaVersion" to JsonPrimitive("nuxie.journey-plane-profile.v1"), "status" to JsonPrimitive("ok"),
            "delivery" to obj("renderBaseUrl" to JsonPrimitive("https://test-store-fixture.example/"),
                "assetBaseUrl" to JsonPrimitive("https://test-store-fixture.example/")),
            "features" to JsonArray(emptyList()), "facts" to obj("properties" to empty, "memberships" to empty, "assignments" to empty),
            "releases" to JsonArray(listOf(entry)),
            "armedLegs" to JsonArray(listOf(obj(
                "reference" to obj("experienceId" to locator.getValue("experienceId"), "versionId" to locator.getValue("experienceVersionId"),
                    "legId" to locator.getValue("legId"), "descriptorSha256" to envelope.getValue("descriptorSha256")),
                "binding" to obj("type" to JsonPrimitive("new")),
                "entryCondition" to obj("type" to JsonPrimitive("app_foregrounded")),
                "context" to obj("event" to empty, "responses" to empty),
            ))),
        )
    }

    private fun awaitNode(text: String, clickable: Boolean): AccessibilityNodeInfo {
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        val deadline = SystemClock.uptimeMillis() + 10_000
        while (SystemClock.uptimeMillis() < deadline) {
            val found = automation.rootInActiveWindow?.findAccessibilityNodeInfosByText(text)
                ?.firstOrNull { it.isVisibleToUser && (!clickable || it.isClickable) }
            if (found != null) return found
            SystemClock.sleep(50)
        }
        throw AssertionError("Missing native Test Store control: $text")
    }
}
