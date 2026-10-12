package ai.nuxie.sdk.runtime

import ai.nuxie.sdk.fixtures.FixtureRunner
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.*
import org.junit.Test

class NuxiePaywallSelectionTest {
    @Test
    fun `a native plan tap changes the file paywall purchase placement`() = withPaywall { scenario, native, state ->
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
    }
    @Test
    fun `signed identities survive renderer changes and checkpoint restoration`() = withPaywall { _, native, state ->
        native.changeProductId("annual", "forged")
        native.tap("product-annual")
        val snapshot = state.captureSnapshot().values
        assertEquals("placement-annual", snapshot.resolveString("paywall.selectedProduct.placementId"))
        val restored = NuxieRuntime(native).restoreViewModel(NuxieRuntimeFile(10, native),
            NuxieRuntimeArtboard(20, native) { error("Restoration reads the catalog") }, snapshot)
        try {
            assertNotEquals(snapshot.nativeRootInstanceId, restored.snapshot().nativeRootInstanceId)
            native.tap("forged")
            assertNull(restored.snapshot().resolveString("paywall.selectedProduct.placementId"))
            native.tap("product-monthly")
            assertEquals("placement-monthly", restored.snapshot().resolveString("paywall.selectedProduct.placementId"))
        } finally { restored.close() }
    }

    @Test
    fun `selection follows current order and rejects a removed plan`() = withPaywall { _, native, state ->
        native.reversePlans()
        native.tap("product-annual")
        val selected = state.captureSnapshot().values
        assertEquals("placement-annual", selected.resolveString("paywall.selectedProduct.placementId"))
        assertEquals(NuxieViewModelScalarValue.NumberValue(0.0), selected.resolveScalar(listOf("paywall", "selectedIndex")))
        assertEquals(true, selected.resolveBoolean("paywall.selectedProduct.isSelected"))
        native.keepOnly("monthly")
        val invalid = state.captureSnapshot()
        assertNull(invalid.values.resolveString("paywall.selectedProduct.placementId"))
        assertTrue(invalid.native.values.filter { it.name == "isSelected" }.none { it.boolValue })
    }

    private fun withPaywall(block: (JsonObject, PlanTapNative, NuxieRuntimeViewModelState) -> Unit) {
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
        try { block(scenario, native, state) } finally { state.close() }
    }

    private class PlanTapNative(private val model: String, private val plans: List<String>) : NuxieTypedRuntimeNative {
        private data class Node(val schema: Int, val values: MutableMap<String, Any> = mutableMapOf())
        private val nodes = linkedMapOf<Long, Node>()
        private var nextHandle = 40L
        private var activeRoot = 0L
        private fun allocate(schema: Int): Long = nextHandle++.also { nodes[it] = Node(schema) }
        private fun paywall(): Node = nodes.getValue(nodes.getValue(activeRoot).values.getValue("paywall") as Long)
        @Suppress("UNCHECKED_CAST")
        private fun order(): MutableList<Long> = paywall().values.getValue("products") as MutableList<Long>
        fun tap(productId: String) { paywall().values["selectedProductId"] = productId }
        fun changeProductId(plan: String, id: String) {
            nodes.getValue(order()[plans.indexOf(plan)]).values["productId"] = id
        }
        fun reversePlans() { order().reverse() }
        fun keepOnly(plan: String) {
            val expected = "placement-$plan"
            order().removeAll { nodes.getValue(it).values["placementId"] != expected }
        }

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

        override fun newDefaultViewModel(artboardHandle: Long): NativeCallResult<Long> {
            val root = allocate(0)
            val group = allocate(1)
            nodes.getValue(root).values["paywall"] = group
            val authoredProducts = plans.map { allocate(2) }
            nodes.getValue(group).values.putAll(mapOf("products" to authoredProducts.toMutableList(),
                "selectedProductId" to "product-monthly", "selectedIndex" to 0f))
            nodes.getValue(group).values["selectedProduct"] = authoredProducts.first()
            activeRoot = root
            return NativeCallResult(0, root)
        }
        override fun newViewModel(fileHandle: Long, schemaIndex: Int, authoredInstanceIndex: Int?) = NativeCallResult(0,
            allocate(schemaIndex).also { if (schemaIndex == 2) nodes.getValue(it).values["isSelected"] = authoredInstanceIndex == 0 })
        override fun viewModelRootSchemaIndex(viewModelHandle: Long) = NativeCallResult(0, nodes.getValue(viewModelHandle).schema.toLong())
        override fun bindViewModel(artboardHandle: Long, viewModelHandle: Long): Int { activeRoot = viewModelHandle; return 0 }
        override fun freeViewModel(handle: Long) = 0

        override fun mutateViewModel(handle: Long, write: NativeViewModelWrite): Int {
            val parts = write.path.split('/')
            var target = nodes.getValue(handle)
            for (segment in parts.dropLast(1)) target = nodes.getValue(target.values.getValue(segment) as Long)
            val key = parts.last()
            @Suppress("UNCHECKED_CAST")
            fun list(): MutableList<Long> = target.values.getOrPut(key) { mutableListOf<Long>() } as MutableList<Long>
            when (write.kind) {
                NuxieViewModelMutationKind.SET_VIEW_MODEL -> target.values[key] = write.relatedViewModel
                NuxieViewModelMutationKind.SET_NUMBER -> target.values[key] = write.numberValue
                NuxieViewModelMutationKind.SET_STRING -> target.values[key] = write.bytesValue.decodeToString()
                NuxieViewModelMutationKind.SET_BOOLEAN -> target.values[key] = write.boolValue
                NuxieViewModelMutationKind.LIST_SET -> list()[write.index.toInt()] = write.relatedViewModel
                NuxieViewModelMutationKind.LIST_CLEAR -> list().clear()
                NuxieViewModelMutationKind.LIST_INSERT -> list().add(write.index.toInt(), write.relatedViewModel)
                else -> error("Unexpected fixture write ${write.kind}")
            }
            return 0
        }

        override fun snapshotViewModel(viewModelHandle: Long): NativeCallResult<NativeViewModelSnapshot> {
            val properties = checkNotNull(viewModelCatalog(10).value).properties
            val instances = mutableListOf<NativeViewModelSnapshotInstance>()
            val values = mutableListOf<NativeViewModelSnapshotValue>()
            val seen = mutableSetOf<Long>()
            fun visit(handle: Long) {
                if (!seen.add(handle)) return
                val node = nodes.getValue(handle)
                instances += NativeViewModelSnapshotInstance(handle + 1000, node.schema.toLong())
                for (property in properties.filter { it.schemaIndex == node.schema.toLong() }) {
                    val value = node.values[property.name] ?: continue
                    val children = when (property.kind) {
                        9 -> listOf(value as Long)
                        8 -> (value as List<*>).map { it as Long }
                        else -> emptyList()
                    }
                    values += NativeViewModelSnapshotValue(handle + 1000, property.index, property.name, property.kind,
                        if (value is String) value.encodeToByteArray() else byteArrayOf(),
                        if (property.kind == 9) (value as Long) + 1000 else 0,
                        numberValue = (value as? Float) ?: 0f, boolValue = value == true,
                        listItemIds = if (property.kind == 8) children.map { it + 1000 }.toLongArray() else longArrayOf())
                    children.forEach(::visit)
                }
            }
            visit(viewModelHandle)
            return NativeCallResult(0, NativeViewModelSnapshot(viewModelHandle + 1000,
                instances.toTypedArray(), values.toTypedArray()))
        }
    }
}
