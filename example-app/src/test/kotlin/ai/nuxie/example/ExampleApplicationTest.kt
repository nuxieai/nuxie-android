package ai.nuxie.example

import ai.nuxie.sdk.NuxieConfiguration
import ai.nuxie.sdk.billing.NuxiePurchaseDelegate
import ai.nuxie.sdk.billing.PurchaseResult
import ai.nuxie.sdk.billing.RestoreResult
import ai.nuxie.sdk.billing.StoreProduct
import kotlinx.coroutines.runBlocking
import android.app.Activity
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ExampleApplicationTest {
  @Test fun providerConfigurationUsesOneApplicationOwner() {
    val app = RuntimeEnvironment.getApplication() as ExampleApplication
    val delegate = object : NuxiePurchaseDelegate {
      override suspend fun purchase(product: StoreProduct) = PurchaseResult.Cancelled
      override suspend fun restorePurchases() = RestoreResult.NoPurchases
    }
    val first = NuxieConfiguration("pk_test_owner").apply { purchaseDelegate = delegate }
    app.ownProviderOperations(first, "a".repeat(64))
    assertSame(app.providerOperations, first.purchaseDelegate)
    val retry = NuxieConfiguration("pk_test_owner").apply { purchaseDelegate = delegate }
    app.ownProviderOperations(retry, "a".repeat(64))
    assertSame(first.purchaseDelegate, retry.purchaseDelegate)
    assertThrows(IllegalStateException::class.java) { app.ownProviderOperations(retry, "b".repeat(64)) }
    runBlocking { requireNotNull(app.providerOperations).closeAndAwait() }
  }

  @Test fun outgoingActivityCannotClearTheIncomingCheckoutHost() {
    val app = RuntimeEnvironment.getApplication() as ExampleApplication
    val first = Robolectric.buildActivity(Activity::class.java).get()
    val second = Robolectric.buildActivity(Activity::class.java).get()
    app.onActivityResumed(first)
    app.onActivityResumed(second)
    app.onActivityPaused(first)
    app.onActivityDestroyed(first)
    assertSame(second, app.currentActivity)
    app.onActivityPaused(second)
    assertNull(app.currentActivity)
  }

  @Test fun destroyedActivityIsNotReturnedForCheckout() {
    val app = RuntimeEnvironment.getApplication() as ExampleApplication
    val activity = Robolectric.buildActivity(Activity::class.java).get()
    app.onActivityResumed(activity)
    assertSame(activity, app.currentActivity)
    app.onActivityDestroyed(activity)
    assertNull(app.currentActivity)
  }
}
