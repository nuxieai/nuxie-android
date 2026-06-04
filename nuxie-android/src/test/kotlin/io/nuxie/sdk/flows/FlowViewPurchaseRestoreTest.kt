package io.nuxie.sdk.flows

import android.os.Looper
import androidx.activity.ComponentActivity
import io.nuxie.sdk.purchases.NuxiePurchaseDelegate
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
import java.nio.file.Files

@RunWith(RobolectricTestRunner::class)
class FlowViewPurchaseRestoreTest {
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
