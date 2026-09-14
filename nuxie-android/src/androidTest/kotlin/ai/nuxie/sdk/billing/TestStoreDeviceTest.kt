package ai.nuxie.sdk.billing

import ai.nuxie.sdk.Nuxie
import ai.nuxie.sdk.NuxieConfiguration
import ai.nuxie.sdk.NuxieEnvironment
import ai.nuxie.sdk.NuxieEvent
import ai.nuxie.sdk.core.NuxieCore
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
