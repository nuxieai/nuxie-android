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
import ai.nuxie.sdk.runtime.NativeRuntimeEventProperty
import ai.nuxie.sdk.runtime.NuxiePlayerPointerEvent
import ai.nuxie.sdk.runtime.NuxiePlayerPointerKind
import ai.nuxie.sdk.runtime.NuxieRuntimeEvent
import ai.nuxie.sdk.runtime.NuxieRuntimeEventProperty
import ai.nuxie.sdk.runtime.NuxieRuntimeEventPropertyValue
import ai.nuxie.sdk.runtime.NuxieRuntimePlayer
import ai.nuxie.sdk.runtime.NuxieTypedRuntimeNative
import android.app.Activity
import com.android.billingclient.api.BillingClient
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ExperienceRuntimeCommerceBridgeTest {
    @Test
    fun `pointer emitted generated control reaches signed event route and retirement drops it`() {
        val native = PointerEventNative(generatedNativeEvent(actionId = "purchase-control"))
        val player = NuxieRuntimePlayer(1L, native)
        val executor = RecordingExecutor()
        val presentation = presentation(
            executor = executor,
            descriptor = descriptor(
                actionId = "purchase-control",
                program = emitProgram("purchase_tapped"),
            ),
        )
        val commerce = requireNotNull(presentation.commerce)
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

    @Test
    fun `ordinary runtime event passes through to signed route`() {
        val executor = RecordingExecutor()
        val presentation = presentation(executor)
        val activity = Robolectric.buildActivity(Activity::class.java).get()

        presentation.handleRuntimeEvent(
            activity,
            runtimeEvent(name = "purchase_tapped", coreType = 0),
        )

        assertEquals(listOf("play.pro"), executor.purchased.map { it.storeProductId })
    }

    @Test
    fun `undeclared generated control is dropped`() {
        val executor = RecordingExecutor()
        val presentation = presentation(
            executor = executor,
            descriptor = descriptor(
                actionId = "declared-control",
                program = emitProgram("purchase_tapped"),
            ),
        )
        val activity = Robolectric.buildActivity(Activity::class.java).get()

        presentation.handleRuntimeEvent(
            activity,
            generatedRuntimeEvent(actionId = "other-control"),
        )

        assertEquals(emptyList<StoreProduct>(), executor.purchased)
    }

    @Test
    fun `generated control declared for another screen is dropped`() {
        val executor = RecordingExecutor()
        val presentation = presentation(
            executor = executor,
            descriptor = descriptor(
                actionId = "purchase-control",
                program = emitProgram("purchase_tapped"),
                screenId = "other-screen",
            ),
        )
        val activity = Robolectric.buildActivity(Activity::class.java).get()

        presentation.handleRuntimeEvent(
            activity,
            generatedRuntimeEvent(actionId = "purchase-control"),
        )

        assertEquals(emptyList<StoreProduct>(), executor.purchased)
    }

    @Test
    fun `malformed generated control envelopes are dropped`() {
        val malformed = listOf(
            generatedRuntimeEvent(actionId = "purchase-control").copy(coreType = 0),
            generatedRuntimeEvent(actionId = "purchase-control").copy(url = "https://nuxie.ai"),
            generatedRuntimeEvent(actionId = "purchase-control").copy(target = "_blank"),
            generatedRuntimeEvent(actionId = "purchase-control").copy(
                properties = generatedProperties(actionId = "purchase-control") +
                    bytesProperty("actionId", "purchase-control"),
            ),
            generatedRuntimeEvent(actionId = "purchase-control").copy(
                properties = generatedProperties(actionId = "purchase-control")
                    .filterNot { it.name == "nuxieTrigger" },
            ),
            generatedRuntimeEvent(actionId = "purchase-control").copy(
                properties = generatedProperties(actionId = "purchase-control")
                    .map {
                        if (it.name == "componentId") {
                            it.copy(value = NuxieRuntimeEventPropertyValue.Number(1f))
                        } else {
                            it
                        }
                    },
            ),
            generatedRuntimeEvent(actionId = "purchase-control").copy(
                properties = generatedProperties(actionId = "purchase-control") +
                    NuxieRuntimeEventProperty(
                        name = "instanceId",
                        value = NuxieRuntimeEventPropertyValue.Trigger,
                    ),
            ),
            generatedRuntimeEvent(actionId = "purchase-control").copy(
                properties = generatedProperties(actionId = "purchase-control")
                    .map {
                        if (it.name == "actionId") {
                            it.copy(value = NuxieRuntimeEventPropertyValue.Bytes(byteArrayOf(0xc3.toByte())))
                        } else {
                            it
                        }
                    },
            ),
            generatedRuntimeEvent(actionId = "purchase-control").copy(
                properties = generatedProperties(actionId = "purchase-control")
                    .map {
                        if (it.name == "nuxieTrigger") {
                            bytesProperty("nuxieTrigger", "   ")
                        } else {
                            it
                        }
                    },
            ),
        )

        malformed.forEach { event ->
            val executor = RecordingExecutor()
            val presentation = presentation(
                executor = executor,
                descriptor = descriptor(
                    actionId = "purchase-control",
                    program = emitProgram("purchase_tapped"),
                ),
            )
            val activity = Robolectric.buildActivity(Activity::class.java).get()

            presentation.handleRuntimeEvent(activity, event)

            assertEquals(emptyList<StoreProduct>(), executor.purchased)
        }
    }

    @Test
    fun `unsupported signed control program is dropped without partial execution`() {
        val executor = RecordingExecutor()
        val presentation = presentation(
            executor = executor,
            descriptor = descriptor(
                actionId = "purchase-control",
                program = buildJsonArray {
                    add(emitAction("purchase_tapped"))
                    add(buildJsonObject {
                        put("type", JsonPrimitive("response_unset"))
                        put("field", JsonPrimitive("choice"))
                    })
                },
            ),
        )
        val activity = Robolectric.buildActivity(Activity::class.java).get()

        presentation.handleRuntimeEvent(
            activity,
            generatedRuntimeEvent(actionId = "purchase-control"),
        )

        assertEquals(emptyList<StoreProduct>(), executor.purchased)
    }

    private fun presentation(
        executor: RecordingExecutor,
        descriptor: JsonObject? = null,
    ): PreparedPresentation {
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
        return PreparedPresentation(
            rivFile = File("unused.riv"),
            artboardName = "Paywall",
            clearColor = 0,
            shell = PresentationShell.FullScreen,
            screenId = "paywall",
            descriptor = descriptor,
            commerce = commerce,
        )
    }

    private fun descriptor(
        actionId: String,
        program: JsonArray,
        screenId: String = "paywall",
    ) = buildJsonObject {
        put("screenBehaviors", buildJsonArray {
            add(buildJsonObject {
                put("screenId", JsonPrimitive(screenId))
                put("controls", buildJsonArray {
                    add(buildJsonObject {
                        put("actionId", JsonPrimitive(actionId))
                        put("behavior", buildJsonObject {
                            put("kind", JsonPrimitive("declarative"))
                            put("program", program)
                        })
                    })
                })
            })
        })
    }

    private fun emitProgram(eventName: String) = buildJsonArray {
        add(emitAction(eventName))
    }

    private fun emitAction(eventName: String) = buildJsonObject {
        put("type", JsonPrimitive("emit"))
        put("eventName", JsonPrimitive(eventName))
    }

    private fun generatedRuntimeEvent(actionId: String) = runtimeEvent(
        name = "Nuxie Interaction",
        coreType = 128,
        properties = generatedProperties(actionId),
    )

    private fun generatedProperties(actionId: String) = listOf(
        bytesProperty("nuxieTrigger", "press"),
        bytesProperty("actionId", actionId),
        bytesProperty("componentId", "paywall-cta"),
    )

    private fun bytesProperty(name: String, value: String) = NuxieRuntimeEventProperty(
        name = name,
        value = NuxieRuntimeEventPropertyValue.Bytes(value.encodeToByteArray()),
    )

    private fun runtimeEvent(
        name: String,
        coreType: Int,
        properties: List<NuxieRuntimeEventProperty> = emptyList(),
    ) = NuxieRuntimeEvent(
        localIndex = 0,
        coreType = coreType,
        name = name,
        url = "",
        target = "",
        delay = 0f,
        properties = properties,
    )

    private fun generatedNativeEvent(actionId: String) = NativeRuntimeEvent(
        localIndex = 0,
        coreType = 128,
        name = "Nuxie Interaction",
        url = "",
        target = "",
        delay = 0f,
        properties = arrayOf(
            nativeBytesProperty("nuxieTrigger", "press"),
            nativeBytesProperty("actionId", actionId),
            nativeBytesProperty("componentId", "paywall-cta"),
        ),
    )

    private fun nativeBytesProperty(name: String, value: String) = NativeRuntimeEventProperty(
        name = name,
        kind = 2,
        numberValue = 0f,
        boolValue = false,
        bytesValue = value.encodeToByteArray(),
        colorValue = 0,
        integerValue = 0L,
    )

    private class PointerEventNative(
        private val event: NativeRuntimeEvent,
    ) : NuxieTypedRuntimeNative {
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
                    pointerHits = intArrayOf(),
                    events = if (pointers.any { it.kind == 2 }) arrayOf(event) else emptyArray(),
                    hostCommands = emptyArray(),
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
