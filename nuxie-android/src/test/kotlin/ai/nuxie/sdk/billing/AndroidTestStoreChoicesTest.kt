package ai.nuxie.sdk.billing

import android.app.Activity
import android.os.Looper
import android.widget.Button
import android.widget.TextView
import android.view.View
import android.view.ViewGroup
import kotlinx.coroutines.async
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.test.resetMain
import org.junit.Before
import org.junit.After
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowAlertDialog

@RunWith(RobolectricTestRunner::class)
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class AndroidTestStoreChoicesTest {
    @Before
    fun installMainDispatcher() { Dispatchers.setMain(UnconfinedTestDispatcher()) }

    @After
    fun resetMainDispatcher() { Dispatchers.resetMain() }

    private fun visibleText(view: View): String = when (view) {
        is TextView -> view.text.toString()
        is ViewGroup -> (0 until view.childCount).joinToString("\n") { visibleText(view.getChildAt(it)) }
        else -> ""
    }

    private fun product() = StoreProduct(
        productId = "pro", storeProductId = "play-pro", basePlanId = null,
        offerId = null, placementId = "primary", rawProduct = null, offerToken = null,
        isOfferPersonalized = false, productType = "inapp",
    )

    @Test
    fun nativeButtonsReturnEveryPurchaseAndRestoreChoice() = runTest {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        try {
            val choices = AndroidTestStoreChoices { null }
            for (expected in TestStorePurchaseChoice.entries) {
                val result = async { choices.purchase(product(), controller.get()) }
                runCurrent()
                shadowOf(Looper.getMainLooper()).idle()
                val dialog = ShadowAlertDialog.getLatestAlertDialog()
                assertTrue(dialog.isShowing)
                val text = visibleText(dialog.window!!.decorView)
                assertTrue(text.contains("Nuxie Test Store"))
                assertTrue(text.contains("no charge, no Google Play transaction"))
                assertTrue(text.contains("pro"))
                val label = expected.name.lowercase().replaceFirstChar(Char::uppercaseChar)
                dialog.window!!.decorView.findViewWithTag<Button>(label).performClick()
                assertEquals(expected, result.await())
                assertFalse(dialog.isShowing)
            }
            for ((label, expected) in listOf(
                "Restored" to TestStoreRestoreChoice.RESTORED,
                "No Purchases" to TestStoreRestoreChoice.NO_PURCHASES,
                "Failed" to TestStoreRestoreChoice.FAILED,
            )) {
                val result = async { AndroidTestStoreChoices { controller.get() }.restore() }
                runCurrent()
                shadowOf(Looper.getMainLooper()).idle()
                val dialog = ShadowAlertDialog.getLatestAlertDialog()
                dialog.window!!.decorView.findViewWithTag<Button>(label).performClick()
                assertEquals(expected, result.await())
                assertFalse(dialog.isShowing)
            }
        } finally {
            controller.pause().stop().destroy()
        }
    }

    @Test
    fun cancellationDismissalAndActivityDestructionDoNotLeaveCheckoutWaiting() = runTest {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        val choices = AndroidTestStoreChoices { controller.get() }
        val cancelled = async { choices.purchase(product()) }
        runCurrent()
        shadowOf(Looper.getMainLooper()).idle()
        val first = ShadowAlertDialog.getLatestAlertDialog()
        cancelled.cancel()
        runCurrent()
        shadowOf(Looper.getMainLooper()).idle()
        assertFalse(first.isShowing)
        cancelled.join()

        val dismissed = async { choices.purchase(product()) }
        runCurrent()
        shadowOf(Looper.getMainLooper()).idle()
        ShadowAlertDialog.getLatestAlertDialog().cancel()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(TestStorePurchaseChoice.CANCELLED, dismissed.await())

        val destroyed = async { choices.restore() }
        runCurrent()
        shadowOf(Looper.getMainLooper()).idle()
        val last = ShadowAlertDialog.getLatestAlertDialog()
        controller.pause().stop().destroy()
        assertEquals(TestStoreRestoreChoice.FAILED, destroyed.await())
        assertFalse(last.isShowing)
    }

    @Test
    fun unavailableActivityFailsWithoutOpeningCheckout() = runTest {
        val missing = AndroidTestStoreChoices { null }
        assertEquals(TestStorePurchaseChoice.FAILED, missing.purchase(product()))
        assertEquals(TestStoreRestoreChoice.FAILED, missing.restore())
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        controller.get().finish()
        val finishing = AndroidTestStoreChoices { controller.get() }
        assertEquals(TestStorePurchaseChoice.FAILED, finishing.purchase(product()))
        controller.pause().stop().destroy()
        assertEquals(TestStoreRestoreChoice.FAILED, finishing.restore())
    }
}
