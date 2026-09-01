package ai.nuxie.sdk.runtime

import org.junit.Assert.assertEquals
import org.junit.Test

class NuxieRuntimeViewModelProjectionTest {
    @Test
    fun `live values and graph replacement happen before the root is bound`() {
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

    private class ProjectionNative : NuxieTypedRuntimeNative {
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
                        NativeViewModelProperty(1, 1, "products", 8, 2, emptyArray()),
                        NativeViewModelProperty(1, 2, "selectedProduct", 9, 2, emptyArray()),
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
