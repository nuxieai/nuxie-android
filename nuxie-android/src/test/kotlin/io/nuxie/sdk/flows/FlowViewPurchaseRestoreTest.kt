package io.nuxie.sdk.flows

import android.os.Looper
import androidx.activity.ComponentActivity
import io.nuxie.sdk.purchases.NuxiePurchaseDelegate
import io.nuxie.sdk.purchases.OfferAwareNuxiePurchaseDelegate
import io.nuxie.sdk.purchases.PlayStorePurchase
import io.nuxie.sdk.purchases.PurchaseOffer
import io.nuxie.sdk.purchases.PurchaseResult
import io.nuxie.sdk.purchases.RestoreResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.io.File
import java.lang.reflect.Proxy
import java.nio.file.Files

@RunWith(RobolectricTestRunner::class)
class FlowViewPurchaseRestoreTest {
  @Test
  fun purchaseOutcomeCompat_passes_the_selected_offer_to_the_delegate() = runBlocking {
    var receivedOffer: PurchaseOffer? = null
    val delegate = object : OfferAwareNuxiePurchaseDelegate {
      override suspend fun purchase(productId: String): PurchaseResult = PurchaseResult.Success
      override suspend fun purchaseOutcome(productId: String, offer: PurchaseOffer) =
        io.nuxie.sdk.purchases.PurchaseOutcome(
          result = PurchaseResult.Success.also { receivedOffer = offer },
          productId = productId,
        )
      override suspend fun restore(): RestoreResult = RestoreResult.NoPurchases
    }
    val offer = PurchaseOffer(
      id = "exit-discount",
      type = "developerDetermined",
      price = "\$1.99",
      periodCount = 3,
      offerToken = "eligible-token",
    )

    val outcome = delegate.purchaseOutcomeCompat("pro_monthly", offer)

    assertEquals(offer, receivedOffer)
    assertEquals(PurchaseResult.Success, outcome.result)
  }

  @Test
  fun purchaseOutcomeCompat_preservesLegacyOutcomeReceiptEvidence() = runBlocking {
    val purchase = PlayStorePurchase(
      purchaseToken = "receipt-token",
      productIds = listOf("pro_monthly"),
    )
    val delegate = object : NuxiePurchaseDelegate {
      override suspend fun purchase(productId: String): PurchaseResult = PurchaseResult.Success
      override suspend fun purchaseOutcome(productId: String) =
        io.nuxie.sdk.purchases.PurchaseOutcome(
          result = PurchaseResult.Success,
          productId = productId,
          playStorePurchase = purchase,
        )
      override suspend fun restore(): RestoreResult = RestoreResult.NoPurchases
    }

    val outcome = delegate.purchaseOutcomeCompat("pro_monthly")

    assertEquals(purchase, outcome.playStorePurchase)
  }

  @Test
  fun purchaseOutcomeCompat_fallsBackToPurchaseForLegacyDelegates() = runBlocking {
    var purchaseCalls = 0
    var purchasedProductId: String? = null
    val delegate = Proxy.newProxyInstance(
      NuxiePurchaseDelegate::class.java.classLoader,
      arrayOf(NuxiePurchaseDelegate::class.java),
    ) { _, method, args ->
      when (method.name) {
        "purchaseOutcome" -> throw AbstractMethodError("purchaseOutcome")
        "purchase" -> {
          purchaseCalls += 1
          purchasedProductId = args?.firstOrNull() as? String
          PurchaseResult.Success
        }
        "restore" -> RestoreResult.NoPurchases
        "toString" -> "LegacyPurchaseDelegate"
        "hashCode" -> 1
        "equals" -> false
        else -> throw UnsupportedOperationException(method.name)
      }
    } as NuxiePurchaseDelegate

    val outcome = delegate.purchaseOutcomeCompat("pro_monthly")

    assertEquals(1, purchaseCalls)
    assertEquals("pro_monthly", purchasedProductId)
    assertEquals(PurchaseResult.Success, outcome.result)
    assertEquals("pro_monthly", outcome.productId)
  }

  @Test
  fun withKnownProductId_backfillsEmptyPlayStorePurchaseProductIds() {
    val purchase = PlayStorePurchase(
      purchaseToken = "token_flow_purchase",
      productIds = emptyList(),
    )

    val backfilled = purchase.withKnownProductId(" pro_monthly ")

    assertEquals(listOf("pro_monthly"), backfilled.productIds)
    assertEquals("pro_monthly", backfilled.productId)
  }

  @Test
  fun withKnownProductId_preservesDelegateProvidedProductIds() {
    val purchase = PlayStorePurchase(
      purchaseToken = "token_existing_product",
      productIds = listOf("coins_100"),
    )

    val backfilled = purchase.withKnownProductId("pro_monthly")

    assertEquals(listOf("coins_100"), backfilled.productIds)
    assertEquals("coins_100", backfilled.productId)
  }

  @Test
  fun performRestore_refreshesPlayStorePurchasesAfterSuccessfulRestore() = runBlocking {
    val harness = newHarness(RestoreResult.Success(restoredCount = 2))
    try {
      harness.flowView.performRestore()
      harness.waitForRestore()

      assertEquals(1, harness.delegate.restoreCalls)
      assertEquals(1, harness.refreshCalls)
    } finally {
      harness.close()
    }
  }

  @Test
  fun performRestore_refreshesPlayStorePurchasesAfterNoPurchasesRestore() = runBlocking {
    val harness = newHarness(RestoreResult.NoPurchases)
    try {
      harness.flowView.performRestore()
      harness.waitForRestore()

      assertEquals(1, harness.delegate.restoreCalls)
      assertEquals(1, harness.refreshCalls)
    } finally {
      harness.close()
    }
  }

  @Test
  fun performRestore_doesNotRefreshPlayStorePurchasesAfterFailedRestore() = runBlocking {
    val harness = newHarness(RestoreResult.Failed("restore_failed"))
    try {
      harness.flowView.performRestore()
      harness.waitForRestore()

      assertEquals(1, harness.delegate.restoreCalls)
      assertEquals(0, harness.refreshCalls)
    } finally {
      harness.close()
    }
  }

  private suspend fun RestoreHarness.waitForRestore() {
    repeat(20) {
      shadowOf(Looper.getMainLooper()).idle()
      if (delegate.restoreCalls > 0) return
      delay(25)
    }
    shadowOf(Looper.getMainLooper()).idle()
  }

  private fun newHarness(restoreResult: RestoreResult): RestoreHarness {
    val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
    val root = Files.createTempDirectory("nuxie-flow-restore").toFile()
    val bundleRoot = File(root, "bundles")
    val fontRoot = File(root, "fonts")
    val manifest = BuildManifest(
      totalFiles = 1,
      totalSize = 0,
      contentHash = "sha256:restorehash",
      files = listOf(
        BuildManifestFile(
          path = "index.html",
          size = 0,
          contentType = "text/html",
        )
      ),
    )
    val cachedBundleDir = File(bundleRoot, "flow_restore_flow_restorehash")
    cachedBundleDir.mkdirs()
    File(cachedBundleDir, "index.html").writeText("<!doctype html><html><body></body></html>")

    val flow = Flow(
      RemoteFlow(
        id = "restore_flow",
        bundle = FlowBundleRef(
          url = "https://cdn.example.com/flows/restore/",
          manifest = manifest,
        ),
        screens = emptyList(),
        interactions = emptyMap(),
        viewModels = emptyList(),
      )
    )
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val delegate = FakePurchaseDelegate(restoreResult)
    val flowView = FlowView(activity)
    val harness = RestoreHarness(
      flowView = flowView,
      scope = scope,
      root = root,
      delegate = delegate,
    )
    flowView.playStorePurchaseRefresh = {
      harness.refreshCalls += 1
    }
    flowView.load(
      flow = flow,
      bundleStore = FlowBundleStore(bundleRoot),
      fontStore = FontStore(fontRoot),
      purchaseDelegate = delegate,
      scope = scope,
    )
    shadowOf(Looper.getMainLooper()).idle()
    return harness
  }

  private class RestoreHarness(
    val flowView: FlowView,
    val scope: CoroutineScope,
    val root: File,
    val delegate: FakePurchaseDelegate,
  ) {
    var refreshCalls: Int = 0

    fun close() {
      scope.cancel()
      root.deleteRecursively()
    }
  }

  private class FakePurchaseDelegate(
    private val restoreResult: RestoreResult,
  ) : NuxiePurchaseDelegate {
    var restoreCalls: Int = 0

    override suspend fun purchase(productId: String): PurchaseResult {
      return PurchaseResult.Cancelled
    }

    override suspend fun restore(): RestoreResult {
      restoreCalls += 1
      return restoreResult
    }
  }
}
