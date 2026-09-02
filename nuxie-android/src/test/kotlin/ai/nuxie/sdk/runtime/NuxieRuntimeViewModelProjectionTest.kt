package ai.nuxie.sdk.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class NuxieRuntimeViewModelProjectionTest {
    @Test
    fun `a list without a declared item schema binds projected values before the root`() {
        val native = ProjectionNative()
        val runtime = NuxieRuntime(native)
        val state = runtime.bindViewModelList(
            file = NuxieRuntimeFile(10, native),
            artboard = NuxieRuntimeArtboard(20, native) { "Runtime" },
            projection = NuxieViewModelListProjection(
                rootSchemaName = "Runtime",
                listPath = "paywall/products",
                selectedItemPath = "paywall/selectedProduct",
                itemSchemaName = "PaywallProduct",
                items = listOf(
                    NuxieViewModelListProjection.Item(
                        authoredInstanceName = "Pro",
                        listIndex = 0,
                        selected = true,
                        values = linkedMapOf(
                            "price" to NuxieViewModelScalarValue.StringValue("€9.99"),
                            "hasTrial" to NuxieViewModelScalarValue.BooleanValue(true),
                        ),
                    ),
                ),
            ),
        )

        assertEquals(
            listOf(
                "catalog",
                "new-default:20",
                "new-authored:2:0",
                "write:41:SET_STRING:price:0:0",
                "write:41:SET_BOOLEAN:hasTrial:0:0",
                "write:40:LIST_SET:paywall/products:41:0",
                "write:40:SET_VIEW_MODEL:paywall/selectedProduct:41:0",
                "bind:20:40",
            ),
            native.calls,
        )

        state.close()
        assertEquals(listOf("free:40", "free:41"), native.calls.takeLast(2))
    }

    @Test
    fun `a list with a different declared item schema is rejected`() {
        val native = ProjectionNative(listReferencedSchemaIndex = 1)
        val error = assertThrows(IllegalArgumentException::class.java) {
            NuxieRuntime(native).bindViewModelList(
                file = NuxieRuntimeFile(10, native),
                artboard = NuxieRuntimeArtboard(20, native) { "Runtime" },
                projection = NuxieViewModelListProjection(
                    rootSchemaName = "Runtime",
                    listPath = "paywall/products",
                    selectedItemPath = "paywall/selectedProduct",
                    itemSchemaName = "PaywallProduct",
                    items = emptyList(),
                ),
            )
        }

        assertEquals(
            "View-model list 'paywall/products' does not contain 'PaywallProduct'",
            error.message,
        )
        assertEquals(listOf("catalog"), native.calls)
    }

    @Test
    fun `a selected view model without the exact item schema is rejected`() {
        val native = ProjectionNative(selectedReferencedSchemaIndex = -1)
        val error = assertThrows(IllegalArgumentException::class.java) {
            NuxieRuntime(native).bindViewModelList(
                file = NuxieRuntimeFile(10, native),
                artboard = NuxieRuntimeArtboard(20, native) { "Runtime" },
                projection = NuxieViewModelListProjection(
                    rootSchemaName = "Runtime",
                    listPath = "paywall/products",
                    selectedItemPath = "paywall/selectedProduct",
                    itemSchemaName = "PaywallProduct",
                    items = emptyList(),
                ),
            )
        }

        assertEquals(
            "View-model property 'paywall/selectedProduct' does not reference 'PaywallProduct'",
            error.message,
        )
        assertEquals(listOf("catalog"), native.calls)
    }

    @Test
    fun `snapshot resolves the current selected product through nested references`() {
        val native = SnapshotNative()
        val state = NuxieRuntimeViewModelState(40, listOf(41, 42), native)

        val first = state.snapshot()
        assertEquals("primary", first.resolveString("paywall.selectedProduct.placementId"))
        assertEquals("primary", first.resolveString("paywall/selectedProduct/placementId"))

        native.selectedProductId = 42
        val second = state.snapshot()
        assertEquals("primary", first.resolveString("paywall.selectedProduct.placementId"))
        assertEquals("annual", second.resolveString("paywall.selectedProduct.placementId"))
        assertNull(second.resolveString("paywall.selectedProduct.missing"))
        assertNull(second.resolveString("paywall.selectedProduct.price"))
        assertNull(second.resolveString("paywall.optionalProduct.placementId"))
        assertNull(second.resolveString("paywall..selectedProduct.placementId"))

        state.close()
        assertEquals(
            listOf("snapshot:40", "snapshot:40", "free:40", "free:42", "free:41"),
            native.calls,
        )
    }

    private class SnapshotNative : NuxieTypedRuntimeNative {
        val calls = mutableListOf<String>()
        var selectedProductId = 41L

        override fun snapshotViewModel(viewModelHandle: Long): NativeCallResult<NativeViewModelSnapshot> {
            calls += "snapshot:$viewModelHandle"
            return NativeCallResult(
                0,
                NativeViewModelSnapshot(
                    rootInstanceId = 1,
                    instances = arrayOf(
                        NativeViewModelSnapshotInstance(1, 0),
                        NativeViewModelSnapshotInstance(2, 1),
                        NativeViewModelSnapshotInstance(41, 2),
                        NativeViewModelSnapshotInstance(42, 2),
                    ),
                    values = arrayOf(
                        referenceValue(1, 0, "paywall", 2),
                        referenceValue(2, 1, "selectedProduct", selectedProductId),
                        referenceValue(2, 2, "optionalProduct", 0),
                        stringValue(41, 2, "placementId", "primary"),
                        stringValue(41, 3, "price", "9.99", kind = 2),
                        stringValue(42, 2, "placementId", "annual"),
                        stringValue(42, 3, "price", "19.99", kind = 2),
                    ),
                ),
            )
        }

        override fun freeViewModel(handle: Long): Int {
            calls += "free:$handle"
            return 0
        }

        private fun referenceValue(
            owner: Long,
            property: Long,
            name: String,
            referenced: Long,
        ) = NativeViewModelSnapshotValue(
            ownerInstanceId = owner,
            propertyIndex = property,
            name = name,
            kind = NuxieViewModelPropertyKind.VIEW_MODEL.nativeValue,
            bytesValue = byteArrayOf(),
            referencedInstanceId = referenced,
        )

        private fun stringValue(
            owner: Long,
            property: Long,
            name: String,
            value: String,
            kind: Int = NuxieViewModelPropertyKind.STRING.nativeValue,
        ) = NativeViewModelSnapshotValue(
            ownerInstanceId = owner,
            propertyIndex = property,
            name = name,
            kind = kind,
            bytesValue = value.encodeToByteArray(),
            referencedInstanceId = 0,
        )
    }

    private class ProjectionNative(
        private val listReferencedSchemaIndex: Long = -1,
        private val selectedReferencedSchemaIndex: Long = 2,
    ) : NuxieTypedRuntimeNative {
        val calls = mutableListOf<String>()

        override fun viewModelCatalog(fileHandle: Long): NativeCallResult<NativeViewModelCatalog> {
            calls += "catalog"
            return NativeCallResult(
                0,
                NativeViewModelCatalog(
                    schemas = arrayOf(
                        NativeViewModelSchema(0, "Runtime", 0, 1, 0, 0, -1, false),
                        NativeViewModelSchema(1, "Paywall", 1, 2, 0, 0, -1, false),
                        NativeViewModelSchema(2, "PaywallProduct", 3, 2, 0, 1, 0, false),
                    ),
                    properties = arrayOf(
                        NativeViewModelProperty(0, 0, "paywall", 9, 1, emptyArray()),
                        NativeViewModelProperty(
                            1,
                            1,
                            "products",
                            8,
                            listReferencedSchemaIndex,
                            emptyArray(),
                        ),
                        NativeViewModelProperty(
                            1,
                            2,
                            "selectedProduct",
                            9,
                            selectedReferencedSchemaIndex,
                            emptyArray(),
                        ),
                        NativeViewModelProperty(2, 3, "price", 1, -1, emptyArray()),
                        NativeViewModelProperty(2, 4, "hasTrial", 3, -1, emptyArray()),
                    ),
                    authoredInstances = arrayOf(
                        NativeViewModelAuthoredInstance(2, 0, "Pro"),
                    ),
                ),
            )
        }

        override fun newDefaultViewModel(artboardHandle: Long): NativeCallResult<Long> {
            calls += "new-default:$artboardHandle"
            return NativeCallResult(0, 40)
        }

        override fun newViewModel(
            fileHandle: Long,
            schemaIndex: Int,
            authoredInstanceIndex: Int?,
        ): NativeCallResult<Long> {
            calls += "new-authored:$schemaIndex:$authoredInstanceIndex"
            return NativeCallResult(0, 41)
        }

        override fun mutateViewModel(handle: Long, write: NativeViewModelWrite): Int {
            calls += "write:$handle:${write.kind}:${write.path}:" +
                "${write.relatedViewModel}:${write.index}"
            return 0
        }

        override fun bindViewModel(artboardHandle: Long, viewModelHandle: Long): Int {
            calls += "bind:$artboardHandle:$viewModelHandle"
            return 0
        }

        override fun freeViewModel(handle: Long): Int {
            calls += "free:$handle"
            return 0
        }
    }
}
