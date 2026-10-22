package ai.nuxie.sdk.runtime

import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

/** Port of JourneyRuntimeDelegateTests.testNativePlanTapChangesPurchase using the real scene. */
class NuxiePaywallSelectionDeviceTest {
    @Test
    @SdkSuppress(minSdkVersion = 26)
    fun nativePlanTapChangesThePurchasePlacementAndSelectionFeedback(): Unit = runBlocking {
        val assets = InstrumentationRegistry.getInstrumentation().context.assets
        fun read(path: String) = assets.open(path).use { it.readBytes() }
        val scenario = Json.parseToJsonElement(read("journeys/planes/file-paywall-selection.json").decodeToString()).jsonObject
        val rows = scenario.getValue("viewModelValues").jsonArray.map { it.jsonObject }
        val plans = rows.single { it.getValue("path").jsonPrimitive.content == "paywall/products" }
            .getValue("value").jsonArray.map { it.jsonObject.getValue("vmInstanceId").jsonPrimitive.content }
        val path = scenario.getValue("purchaseReference").jsonObject.getValue("ref").jsonObject
            .getValue("path").jsonPrimitive.content
        val bytes = read("runtime/paywall-selection/screen.riv")
        val lane = NuxieRuntimeLane()
        try {
            lane.call {
                val runtime = NuxieRuntime.shared
                assertTrue(runtime.isAvailable)
                val renderer = checkNotNull(runtime.newAndroidVulkanRenderer(320, 100))
                try {
                    val file = checkNotNull(runtime.importFile(renderer, bytes,
                        checkNotNull(runtime.inspectFileAssets(bytes))))
                    try {
                        val artboard = checkNotNull(file.newArtboard("Paywall"))
                        try {
                            // The portable scene calls its root Runtime and its authored
                            // children Monthly/Annual; the signed identities remain lowercase.
                            val state = runtime.bindViewModelList(file, artboard,
                                NuxieViewModelListProjection("Runtime", "paywall/products", "paywall/selectedProduct",
                                    "PaywallProduct", plans.mapIndexed { index, id ->
                                        NuxieViewModelListProjection.Item(id.replaceFirstChar { it.uppercase() }, index, index == 0,
                                            rows.filter { it.getValue("instanceId").jsonPrimitive.content == id }
                                                .associate { it.getValue("path").jsonPrimitive.content to
                                                    NuxieViewModelScalarValue.StringValue(it.getValue("value").jsonPrimitive.content) },
                                            instanceId = id)
                                    }, defaultInstanceId = "root-sdk-id"))
                            try {
                                val player = file.newExperiencePlayer(artboard, "Paywall")
                                try {
                                    fun step(pointers: List<NuxiePlayerPointerEvent> = emptyList(), elapsed: Double = 0.0): NuxieCapturedViewModelSnapshot {
                                        val outcome = player.stepTyped(elapsedSeconds = elapsed, pointers = pointers)
                                        return state.captureSnapshot(outcome.viewModelChanges)
                                    }
                                    val initial = scenario.getValue("initialPlacement").jsonPrimitive.content
                                    assertEquals(initial, state.snapshot().resolveString(path))
                                    repeat(20) { assertEquals("Opening must preserve the signed choice", initial,
                                        step(elapsed = 0.016).values.resolveString(path)) }
                                    val down = NuxiePlayerPointerEvent(NuxiePlayerPointerKind.DOWN, 80f, 40f, 0, 0f)
                                    step(listOf(down))
                                    step(listOf(down.copy(kind = NuxiePlayerPointerKind.UP, timestampSeconds = 0.1f)))
                                    val expected = scenario.getValue("writes").jsonArray.first().jsonObject
                                        .getValue("expectedPlacement").jsonPrimitive.content
                                    val settled = step(elapsed = 0.016)
                                    assertEquals(expected, settled.values.resolveString(path))
                                    val feedback = step(elapsed = 0.016)
                                    assertEquals("Feedback must not select another plan", expected, feedback.values.resolveString(path))
                                    assertEquals("product-annual", settled.values.resolveString("paywall/selectedProductId"))
                                    assertEquals(NuxieViewModelScalarValue.NumberValue(1.0),
                                        settled.values.resolveScalar(listOf("paywall", "selectedIndex")))
                                    assertEquals(true, settled.values.resolveBoolean("paywall.selectedProduct.isSelected"))
                                    val group = settled.native.values.single { it.ownerInstanceId == settled.native.rootInstanceId && it.name == "paywall" }.referencedInstanceId
                                    val selected = settled.native.values.single { it.ownerInstanceId == group && it.name == "selectedProduct" }.referencedInstanceId
                                    val products = settled.native.values.filter { it.name == "placementId" }
                                    assertEquals(expected, products.single { it.ownerInstanceId == selected }.bytesValue.decodeToString())
                                    for (product in products) {
                                        assertEquals(product.ownerInstanceId == selected,
                                            settled.native.values.single { it.ownerInstanceId == product.ownerInstanceId && it.name == "isSelected" }.boolValue)
                                    }
                                } finally { player.close() }
                            } finally { state.close() }
                        } finally { artboard.close() }
                    } finally { file.close() }
                } finally { renderer.close() }
            }
        } finally {
            lane.shutdown()
            assertTrue("The native lane drains", lane.awaitQuiescence(5_000))
        }
    }
}
