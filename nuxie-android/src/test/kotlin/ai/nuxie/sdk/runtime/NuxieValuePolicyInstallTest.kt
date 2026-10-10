package ai.nuxie.sdk.runtime

import org.junit.Assert.*
import org.junit.Test

class NuxieValuePolicyInstallTest {
    @Test fun `tables install in order before the file escapes and failures close it`() {
        for (failure in listOf<String?>(null, "markers", "rules", "groups")) {
            val calls = mutableListOf<String>()
            val native = object : NuxieTypedRuntimeNative {
                override fun newFile(rendererHandle: Long, bytes: ByteArray, expectedAssets: List<ExpectedFileAsset>,
                    externalAssets: Map<Int, ByteArray>, imageDecoder: NuxImageDecoder, videoEnabled: Boolean): Long {
                    calls += "file"; return 2L
                }
                override fun viewModelCatalog(fileHandle: Long) = NativeCallResult(0,
                    NativeViewModelCatalog(emptyArray(), emptyArray(), emptyArray()))
                override fun installValueMarkers(file: Long, entries: Array<NativeValueMarker>): Int {
                    calls += "markers"; return if (failure == "markers") 2 else 0
                }
                override fun installValueRules(file: Long, entries: Array<NativeValueRule>): NativeRuleInstallResult {
                    calls += "rules"; return NativeRuleInstallResult(if (failure == "rules") 2 else 0, null, null)
                }
                override fun installRuleGroups(file: Long, entries: Array<NativeRuleGroup>): Int {
                    calls += "groups"; return if (failure == "groups") 2 else 0
                }
                override fun freeFile(handle: Long) { calls += "close" }
            }
            val runtime = NuxieRuntime(native)
            val renderer = NuxieAndroidVulkanRenderer(1L, native)
            if (failure == null) {
                val file = checkNotNull(runtime.importFile(renderer, byteArrayOf(1), valuePolicy = NuxieValuePolicy(emptyList(), emptyList())))
                assertEquals(listOf("file", "markers", "rules", "groups"), calls)
                file.close()
            } else {
                assertThrows(NuxieRuntimeCallException::class.java) {
                    runtime.importFile(renderer, byteArrayOf(1), valuePolicy = NuxieValuePolicy(emptyList(), emptyList()))
                }
                val prefix = listOf("file", "markers", "rules", "groups").takeWhile { it != failure } + failure
                assertEquals(prefix + "close", calls)
            }
        }
    }

    @Test fun `markers use catalog ownership and flattened component names`() {
        val names = listOf("days", "isset:days", "state:enabled", "state:isset:enabled", "choice", "isset:choice")
        val kinds = listOf(NuxieViewModelPropertyKind.NUMBER, NuxieViewModelPropertyKind.BOOLEAN,
            NuxieViewModelPropertyKind.BOOLEAN, NuxieViewModelPropertyKind.BOOLEAN,
            NuxieViewModelPropertyKind.STRING, NuxieViewModelPropertyKind.BOOLEAN)
        val catalog = NuxieViewModelCatalog(listOf(NuxieViewModelCatalog.Schema(0, "Component", names.indices,
            IntRange.EMPTY, null, false)), names.mapIndexed { i, name ->
            NuxieViewModelCatalog.Property(0, i, name, kinds[i], null, emptyList())
        }, emptyList())
        assertEquals(listOf(NuxieValueMarker("Component", "days", "isset:days"),
            NuxieValueMarker("Component", "state:enabled", "state:isset:enabled")),
            NuxieValuePolicy(emptyList(), emptyList()).markers(catalog))
    }
}
