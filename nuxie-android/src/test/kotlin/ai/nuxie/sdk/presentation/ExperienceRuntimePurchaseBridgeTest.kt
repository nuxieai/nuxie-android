package ai.nuxie.sdk.presentation

import ai.nuxie.sdk.billing.ExperiencePurchaseAction
import ai.nuxie.sdk.billing.ExperiencePurchaseContext
import ai.nuxie.sdk.billing.ExperiencePurchaseExecutor
import ai.nuxie.sdk.billing.ExperiencePurchaseRoute
import ai.nuxie.sdk.billing.ExperiencePurchaseSession
import ai.nuxie.sdk.billing.ExperienceOutcomeProgram
import ai.nuxie.sdk.billing.ExperienceOutcomeProgramExecutor
import ai.nuxie.sdk.billing.ExperiencePlacementValue
import ai.nuxie.sdk.billing.PurchaseResult
import ai.nuxie.sdk.billing.RestoreResult
import ai.nuxie.sdk.billing.StoreProduct
import ai.nuxie.sdk.runtime.NativeCallResult
import ai.nuxie.sdk.runtime.NativePlayerInput
import ai.nuxie.sdk.runtime.NativePlayerPointer
import ai.nuxie.sdk.runtime.NativePlayerStepOutcome
import ai.nuxie.sdk.runtime.NativeRuntimeEvent
import ai.nuxie.sdk.runtime.NuxiePlayerPointerEvent
import ai.nuxie.sdk.runtime.NuxiePlayerPointerKind
import ai.nuxie.sdk.runtime.NuxieRuntimePlayer
import ai.nuxie.sdk.runtime.NuxieTypedRuntimeNative
import android.app.Activity
import com.android.billingclient.api.BillingClient
import java.io.File
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ExperienceRuntimeCommerceBridgeTest {
    @Test
    fun `pointer emitted signed event reaches active session and retirement drops it`() {
        val native = PointerEventNative()
        val player = NuxieRuntimePlayer(1L, native)
        val executor = RecordingExecutor()
        val commerce = ExperiencePurchaseSession(
            products = listOf(storeProduct()),
            routes = listOf(
                ExperiencePurchaseRoute(
                    screenId = "paywall",
                    eventName = "purchase_tapped",
                    action = ExperiencePurchaseAction.Purchase(
                        ExperiencePlacementValue.Literal("primary"),
                    ),
                ),
            ),
            executor = executor,
            programExecutor = object : ExperienceOutcomeProgramExecutor {
                override fun validate(
                    program: ExperienceOutcomeProgram,
                    context: ExperiencePurchaseContext,
                ) = Unit

                override suspend fun execute(
                    activity: Activity,
                    program: ExperienceOutcomeProgram,
                    context: ExperiencePurchaseContext,
                ) = Unit
            },
            scope = kotlinx.coroutines.CoroutineScope(Dispatchers.Unconfined),
            context = ExperiencePurchaseContext(null, "customer-1"),
        )
        val presentation = PreparedPresentation(
            rivFile = File("unused.riv"),
            artboardName = "Paywall",
            clearColor = 0,
            shell = PresentationShell.FullScreen,
            screenId = "paywall",
            commerce = commerce,
        )
        val activity = Robolectric.buildActivity(Activity::class.java).get()
        val pointers = listOf(
            NuxiePlayerPointerEvent(NuxiePlayerPointerKind.DOWN, 10f, 20f, 0, 1f),
            NuxiePlayerPointerEvent(NuxiePlayerPointerKind.UP, 10f, 20f, 0, 1.1f),
        )

        val first = player.stepWithEvents(0.016, pointers)
        first.events.forEach { presentation.handleRuntimeEvent(activity, it) }

        assertEquals(listOf("play.pro"), executor.purchased.map { it.storeProductId })
        assertEquals(listOf(0, 2), native.pointers.map { it.kind })

        commerce.retire()
        val afterRetirement = player.stepWithEvents(0.016, pointers)
        afterRetirement.events.forEach { presentation.handleRuntimeEvent(activity, it) }

        assertEquals(listOf("play.pro"), executor.purchased.map { it.storeProductId })
        player.close()
    }

    private class PointerEventNative : NuxieTypedRuntimeNative {
        var pointers = emptyList<NativePlayerPointer>()

        override fun stepPlayer(
            playerHandle: Long,
            inputs: List<NativePlayerInput>,
            pointers: List<NativePlayerPointer>,
            elapsedSeconds: Float,
            correlationId: Long,
        ): NativeCallResult<NativePlayerStepOutcome> {
            this.pointers = pointers
            return NativeCallResult(
                0,
                NativePlayerStepOutcome(
                    keepGoing = true,
                    events = if (pointers.any { it.kind == 2 }) {
                        arrayOf(
                            NativeRuntimeEvent(
                                localIndex = 0,
                                coreType = 0,
                                name = "purchase_tapped",
                                url = "",
                                target = "",
                                delay = 0f,
                                properties = emptyArray(),
                            ),
                        )
                    } else {
                        emptyArray()
                    },
                    viewModelChanges = emptyArray(),
                ),
            )
        }

        override fun freePlayer(handle: Long) = Unit
    }

    private class RecordingExecutor : ExperiencePurchaseExecutor {
        val purchased = mutableListOf<StoreProduct>()

        override suspend fun purchase(
            activity: Activity,
            product: StoreProduct,
            expectedOwnerDistinctId: String?,
        ): PurchaseResult {
            purchased += product
            return PurchaseResult.Purchased
        }

        override suspend fun restore(expectedOwnerDistinctId: String?): RestoreResult =
            RestoreResult.Restored
    }

    private fun storeProduct() = StoreProduct(
        productId = "pro",
        storeProductId = "play.pro",
        basePlanId = "annual",
        offerId = null,
        placementId = "primary",
        rawProduct = null,
        offerToken = null,
        isOfferPersonalized = false,
        productType = BillingClient.ProductType.SUBS,
    )
}
