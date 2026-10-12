package ai.nuxie.sdk.runtime

import ai.nuxie.sdk.fixtures.FixtureRunner
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test

class NuxiePaywallSelectionTest {
    @Test
    fun `a native plan tap changes the file paywall purchase placement`() {
        val scenario = Json.parseToJsonElement(FixtureRunner.fixturesRoot()
            .resolve("journeys/planes/file-paywall-selection.json").readText()).jsonObject
        val screen = scenario.getValue("screen").jsonObject
        val model = screen.getValue("defaultViewModelName").jsonPrimitive.content
        val root = screen.getValue("defaultInstanceId").jsonPrimitive.content
        val rows = scenario.getValue("viewModelValues").jsonArray.map { it.jsonObject }
        val plans = rows.single { it.getValue("path").jsonPrimitive.content == "paywall/products" }
            .getValue("value").jsonArray.map { it.jsonObject.getValue("vmInstanceId").jsonPrimitive.content }
        val native = PlanTapNative(model, plans)
        val state = NuxieRuntime(native).bindViewModelList(
            NuxieRuntimeFile(10, native),
            NuxieRuntimeArtboard(20, native) { error("Projection reads the catalog") },
            NuxieViewModelListProjection(model, "paywall/products", "paywall/selectedProduct",
                "PaywallProduct", plans.mapIndexed { index, id ->
                    NuxieViewModelListProjection.Item(id, index, index == 0,
                        rows.filter { it.getValue("instanceId").jsonPrimitive.content == id }
                            .associate { it.getValue("path").jsonPrimitive.content to
                                NuxieViewModelScalarValue.StringValue(it.getValue("value").jsonPrimitive.content) },
                        instanceId = id)
                }, defaultInstanceId = root),
        )
        try {
            val path = scenario.getValue("purchaseReference").jsonObject.getValue("ref")
                .jsonObject.getValue("path").jsonPrimitive.content
            assertEquals(scenario.getValue("initialPlacement").jsonPrimitive.content,
                state.captureSnapshot().values.resolveString(path))
            for (entry in scenario.getValue("writes").jsonArray) {
                val write = entry.jsonObject
                // The native tap changes only selectedProductId. Production's
                // post-step capture must derive the reference before purchase.
                native.tap(write.getValue("selectedProductId").jsonPrimitive.content)
                val expected = write.getValue("expectedPlacement").takeUnless { it == JsonNull }?.jsonPrimitive?.content
                assertEquals(expected, state.captureSnapshot().values.resolveString(path))
            }
        } finally { state.close() }
    }

    private class PlanTapNative(private val model: String, private val plans: List<String>) : NuxieTypedRuntimeNative {
        private val values = plans.indices.associate { (41L + it) to mutableMapOf<String, String>() }
        private val selected = plans.indices.associate { (41L + it) to (it == 0) }.toMutableMap()
        private val order = plans.indices.map { 1041L + it }.toMutableList()
        private var selectedProductId = "product-monthly"
        private var selectedHandle = 41L
        private var selectedIndex = 0f

        fun tap(productId: String) { selectedProductId = productId }

        override fun viewModelCatalog(fileHandle: Long) = NativeCallResult(0, NativeViewModelCatalog(
            schemas = arrayOf(
                NativeViewModelSchema(0, model, 0, 1, 0, 0, -1, false),
                NativeViewModelSchema(1, "Paywall", 1, 4, 0, 0, -1, false),
                NativeViewModelSchema(2, "PaywallProduct", 5, 3, 0, 2, 0, false),
            ),
            properties = arrayOf(
                NativeViewModelProperty(0, 0, "paywall", 9, 1, emptyArray()),
                NativeViewModelProperty(1, 1, "products", 8, 2, emptyArray()),
                NativeViewModelProperty(1, 2, "selectedProduct", 9, 2, emptyArray()),
                NativeViewModelProperty(1, 3, "selectedProductId", 1, -1, emptyArray()),
                NativeViewModelProperty(1, 4, "selectedIndex", 2, -1, emptyArray()),
                NativeViewModelProperty(2, 5, "productId", 1, -1, emptyArray()),
                NativeViewModelProperty(2, 6, "placementId", 1, -1, emptyArray()),
                NativeViewModelProperty(2, 7, "isSelected", 3, -1, emptyArray()),
            ),
            authoredInstances = plans.mapIndexed { index, name -> NativeViewModelAuthoredInstance(2, index.toLong(), name) }.toTypedArray(),
        ))

        override fun newDefaultViewModel(artboardHandle: Long) = NativeCallResult(0, 40L)
        override fun newViewModel(fileHandle: Long, schemaIndex: Int, authoredInstanceIndex: Int?) =
            NativeCallResult(0, 41L + checkNotNull(authoredInstanceIndex))
        override fun bindViewModel(artboardHandle: Long, viewModelHandle: Long) = 0
        override fun freeViewModel(handle: Long) = 0

        override fun mutateViewModel(handle: Long, write: NativeViewModelWrite): Int {
            if (handle == 40L) {
                when (write.kind) {
                    NuxieViewModelMutationKind.SET_VIEW_MODEL -> selectedHandle = write.relatedViewModel
                    NuxieViewModelMutationKind.SET_NUMBER -> selectedIndex = write.numberValue
                    NuxieViewModelMutationKind.LIST_SET -> order[write.index.toInt()] = 1000L + write.relatedViewModel
                    else -> error("Unexpected root write ${write.kind}")
                }
            } else when (write.kind) {
                NuxieViewModelMutationKind.SET_STRING -> checkNotNull(values[handle])[write.path] = write.bytesValue.decodeToString()
                NuxieViewModelMutationKind.SET_BOOLEAN -> selected[handle] = write.boolValue
                else -> error("Unexpected product write ${write.kind}")
            }
            return 0
        }

        override fun snapshotViewModel(viewModelHandle: Long): NativeCallResult<NativeViewModelSnapshot> {
            fun product(handle: Long): List<NativeViewModelSnapshotValue> = listOf(
                scalar(1000L + handle, 5, "productId", checkNotNull(values[handle])["productId"].orEmpty()),
                scalar(1000L + handle, 6, "placementId", checkNotNull(values[handle])["placementId"].orEmpty()),
                NativeViewModelSnapshotValue(1000L + handle, 7, "isSelected", 3, byteArrayOf(), 0,
                    boolValue = selected[handle] == true),
            )
            val root = viewModelHandle == 40L
            val instances = buildList {
                if (root) {
                    add(NativeViewModelSnapshotInstance(1, 0))
                    add(NativeViewModelSnapshotInstance(2, 1))
                    plans.indices.forEach { add(NativeViewModelSnapshotInstance(1041L + it, 2)) }
                } else add(NativeViewModelSnapshotInstance(1000L + viewModelHandle, 2))
            }
            val snapshotValues = buildList {
                if (root) {
                    add(NativeViewModelSnapshotValue(1, 0, "paywall", 9, byteArrayOf(), 2))
                    add(NativeViewModelSnapshotValue(2, 1, "products", 8, byteArrayOf(), 0, listItemIds = order.toLongArray()))
                    add(NativeViewModelSnapshotValue(2, 2, "selectedProduct", 9, byteArrayOf(), 1000L + selectedHandle))
                    add(scalar(2, 3, "selectedProductId", selectedProductId))
                    add(NativeViewModelSnapshotValue(2, 4, "selectedIndex", 2, byteArrayOf(), 0, numberValue = selectedIndex))
                    plans.indices.forEach { addAll(product(41L + it)) }
                } else addAll(product(viewModelHandle))
            }
            return NativeCallResult(0, NativeViewModelSnapshot(if (root) 1 else 1000L + viewModelHandle,
                instances.toTypedArray(), snapshotValues.toTypedArray()))
        }

        private fun scalar(owner: Long, property: Long, name: String, text: String) =
            NativeViewModelSnapshotValue(owner, property, name, 1, text.encodeToByteArray(), 0)
    }
}
