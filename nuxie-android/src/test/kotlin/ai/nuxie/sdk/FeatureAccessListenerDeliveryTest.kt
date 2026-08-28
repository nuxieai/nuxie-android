package ai.nuxie.sdk

import ai.nuxie.sdk.core.NuxieCore
import ai.nuxie.sdk.features.FeatureAccess
import ai.nuxie.sdk.features.FeatureType
import ai.nuxie.sdk.features.LocalPurchaseGrant
import ai.nuxie.sdk.testsupport.FakeTransport
import android.os.Looper
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
class FeatureAccessListenerDeliveryTest {
    @Before
    fun setUp() {
        Nuxie.resetForTesting()
        Nuxie.overridesForTesting = NuxieCore.Overrides(
            transport = FakeTransport(),
            registerLifecycle = false,
        )
    }

    @After
    fun tearDown() {
        Nuxie.resetForTesting()
        Nuxie.overridesForTesting = null
    }

    @Test
    fun purchaseMutationDeliversOneMainThreadTransitionMatchingTheFlow() {
        val callbacks = mutableListOf<Callback>()
        val listener = object : NuxieListener {
            override fun onAppActionRequested(sdk: Nuxie, action: AppAction) = Unit

            override fun featureAccessDidChange(
                featureId: String,
                oldAccess: FeatureAccess?,
                newAccess: FeatureAccess,
            ) {
                callbacks += Callback(
                    featureId,
                    oldAccess,
                    newAccess,
                    Nuxie.features.all.value[featureId],
                    Thread.currentThread(),
                )
            }
        }
        Nuxie.listener = listener
        Nuxie.setup(
            RuntimeEnvironment.getApplication(),
            NuxieConfiguration("pk_test_feature_listener").apply { logLevel = LogLevel.NONE },
        )
        drainCommittedEvents()
        val workerFinished = CountDownLatch(1)

        Thread {
            runBlocking {
                requireNotNull(Nuxie.core).features.applyLocalPurchase(
                    listOf(LocalPurchaseGrant("pro", FeatureType.BOOLEAN)),
                    "transaction-1",
                )
            }
            workerFinished.countDown()
        }.start()

        assertFalse(workerFinished.await(100, TimeUnit.MILLISECONDS))
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
        while (workerFinished.count != 0L && System.nanoTime() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.yield()
        }

        assertTrue(workerFinished.await(100, TimeUnit.MILLISECONDS))
        val expected = FeatureAccess(true, false, null, FeatureType.BOOLEAN)
        assertEquals(listOf(Callback("pro", null, expected, null, Looper.getMainLooper().thread)), callbacks)
        assertEquals(expected, Nuxie.features.all.value.getValue("pro"))
    }

    private fun drainCommittedEvents() {
        val drained = CountDownLatch(1)
        Thread {
            runBlocking { requireNotNull(Nuxie.core).eventLog.awaitBarrier() }
            drained.countDown()
        }.start()
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
        while (drained.count != 0L && System.nanoTime() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.yield()
        }
        assertTrue(drained.await(100, TimeUnit.MILLISECONDS))
    }

    private data class Callback(
        val featureId: String,
        val oldAccess: FeatureAccess?,
        val newAccess: FeatureAccess,
        val flowAccessDuringCallback: FeatureAccess?,
        val thread: Thread,
    )
}
