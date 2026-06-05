package io.nuxie.sdk.purchases

import android.app.Activity
import com.android.billingclient.api.AcknowledgePurchaseParams
import com.android.billingclient.api.AcknowledgePurchaseResponseListener
import com.android.billingclient.api.AlternativeBillingOnlyAvailabilityListener
import com.android.billingclient.api.AlternativeBillingOnlyInformationDialogListener
import com.android.billingclient.api.AlternativeBillingOnlyReportingDetailsListener
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingConfigResponseListener
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.ConsumeParams
import com.android.billingclient.api.ConsumeResponseListener
import com.android.billingclient.api.ExternalOfferAvailabilityListener
import com.android.billingclient.api.ExternalOfferInformationDialogListener
import com.android.billingclient.api.ExternalOfferReportingDetailsListener
import com.android.billingclient.api.GetBillingConfigParams
import com.android.billingclient.api.InAppMessageParams
import com.android.billingclient.api.InAppMessageResponseListener
import com.android.billingclient.api.ProductDetailsResponseListener
import com.android.billingclient.api.PurchasesResponseListener
import com.android.billingclient.api.QueryProductDetailsParams
import com.android.billingclient.api.QueryPurchasesParams
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidPlayBillingClientTest {
  @Test
  fun endConnection_completesPendingSetupCallbacks() {
    val billingClient = FakeBillingClient()
    val client = AndroidPlayBillingClient(billingClient)
    val setupResults = mutableListOf<PlayBillingResult>()
    var disconnectCalls = 0

    client.startConnection(
      onSetupFinished = { setupResults += it },
      onDisconnected = { disconnectCalls += 1 },
    )

    assertEquals(1, billingClient.startConnectionCalls)
    assertTrue(setupResults.isEmpty())

    client.endConnection()

    assertEquals(1, billingClient.endConnectionCalls)
    assertEquals(0, disconnectCalls)
    assertEquals(1, setupResults.size)
    assertEquals(BillingClient.BillingResponseCode.SERVICE_DISCONNECTED, setupResults.single().responseCode)
    assertEquals("Billing connection closed", setupResults.single().debugMessage)

    billingClient.finishSetup()

    assertEquals(1, setupResults.size)
  }

  private class FakeBillingClient : BillingClient() {
    var startConnectionCalls = 0
    var endConnectionCalls = 0
    var stateListener: BillingClientStateListener? = null
    private var state: Int = ConnectionState.DISCONNECTED

    override fun startConnection(listener: BillingClientStateListener) {
      startConnectionCalls += 1
      state = ConnectionState.CONNECTING
      stateListener = listener
    }

    fun finishSetup() {
      state = ConnectionState.CONNECTED
      stateListener?.onBillingSetupFinished(
        BillingResult.newBuilder()
          .setResponseCode(BillingResponseCode.OK)
          .setDebugMessage("OK")
          .build()
      )
    }

    override fun endConnection() {
      endConnectionCalls += 1
      state = ConnectionState.CLOSED
    }

    override fun isReady(): Boolean = state == ConnectionState.CONNECTED

    override fun getConnectionState(): Int = state

    override fun isFeatureSupported(feature: String): BillingResult = unusedResult()

    override fun launchBillingFlow(activity: Activity, params: BillingFlowParams): BillingResult = unusedResult()

    override fun showAlternativeBillingOnlyInformationDialog(
      activity: Activity,
      listener: AlternativeBillingOnlyInformationDialogListener,
    ): BillingResult = unusedResult()

    override fun showExternalOfferInformationDialog(
      activity: Activity,
      listener: ExternalOfferInformationDialogListener,
    ): BillingResult = unusedResult()

    override fun showInAppMessages(
      activity: Activity,
      params: InAppMessageParams,
      listener: InAppMessageResponseListener,
    ): BillingResult = unusedResult()

    override fun acknowledgePurchase(
      params: AcknowledgePurchaseParams,
      listener: AcknowledgePurchaseResponseListener,
    ) = Unit

    override fun consumeAsync(params: ConsumeParams, listener: ConsumeResponseListener) = Unit

    override fun createAlternativeBillingOnlyReportingDetailsAsync(
      listener: AlternativeBillingOnlyReportingDetailsListener,
    ) = Unit

    override fun createExternalOfferReportingDetailsAsync(
      listener: ExternalOfferReportingDetailsListener,
    ) = Unit

    override fun getBillingConfigAsync(
      params: GetBillingConfigParams,
      listener: BillingConfigResponseListener,
    ) = Unit

    override fun isAlternativeBillingOnlyAvailableAsync(
      listener: AlternativeBillingOnlyAvailabilityListener,
    ) = Unit

    override fun isExternalOfferAvailableAsync(listener: ExternalOfferAvailabilityListener) = Unit

    override fun queryProductDetailsAsync(
      params: QueryProductDetailsParams,
      listener: ProductDetailsResponseListener,
    ) = Unit

    override fun queryPurchasesAsync(
      params: QueryPurchasesParams,
      listener: PurchasesResponseListener,
    ) = Unit

    private fun unusedResult(): BillingResult {
      return BillingResult.newBuilder()
        .setResponseCode(BillingResponseCode.FEATURE_NOT_SUPPORTED)
        .setDebugMessage("not used")
        .build()
    }
  }
}
