package ai.nuxie.sdk.presentation

import ai.nuxie.sdk.runtime.ExpectedFileAsset
import ai.nuxie.sdk.runtime.NativeCallResult
import ai.nuxie.sdk.runtime.NativeViewModelCatalog
import ai.nuxie.sdk.runtime.NativeViewModelSchema
import ai.nuxie.sdk.runtime.NuxImageDecoder
import ai.nuxie.sdk.runtime.NuxieRuntime
import ai.nuxie.sdk.runtime.NuxieRuntimeCallException
import ai.nuxie.sdk.runtime.NuxieRuntimeLane
import ai.nuxie.sdk.runtime.NuxieTypedRuntimeNative
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.shadows.ShadowLog

@RunWith(RobolectricTestRunner::class)
class ExperienceSurfaceHostCleanupTest {
    @Test
    fun `view model cleanup failure still frees all other owners once and preserves first error`() {
        val native = RecordingNative()
        val lane = NuxieRuntimeLane()
        val host = ExperienceSurfaceHost(
            RuntimeEnvironment.getApplication(), lane, runtime = NuxieRuntime(native),
        )
        val descriptor = Json.parseToJsonElement(
            """{
              "render":{"assets":[],"screens":[{"id":"main","artboardName":"Main"}]},
              "leg":{"screens":[{"id":"main","defaultViewModelName":"Root"}]}
            }""",
        ).jsonObject
        try {
            host.loadArtboard(byteArrayOf(1), "Main", descriptor) { native.loaded = it }
            runBlocking { lane.call {} }
            assertTrue(native.loaded)

            host.release()
            runBlocking { lane.call {} }
            host.release()
            runBlocking { lane.call {} }

            assertEquals(listOf("player", "view-model", "artboard", "file", "renderer"), native.freed)
            val logged = ShadowLog.getLogsForTag("Nuxie")
                .last { it.msg == "Runtime lane task failed" }.throwable
            assertTrue(logged is NuxieRuntimeCallException)
            assertEquals("file cleanup failure", logged.suppressed.single().message)
        } finally {
            lane.shutdown()
            assertTrue(lane.awaitQuiescence(5_000))
        }
    }

    private class RecordingNative : NuxieTypedRuntimeNative {
        override val isAvailable = true
        val freed = CopyOnWriteArrayList<String>()
        var loaded = false

        override fun newAndroidVulkanRenderer(pixelWidth: Int, pixelHeight: Int): Long = 1
        override fun inspectFileAssets(bytes: ByteArray): List<ExpectedFileAsset> = emptyList()
        override fun newFile(
            rendererHandle: Long,
            bytes: ByteArray,
            expectedAssets: List<ExpectedFileAsset>,
            externalAssets: Map<Int, ByteArray>,
            imageDecoder: NuxImageDecoder,
        ): Long = 2
        override fun newNamedArtboard(fileHandle: Long, name: String): Long = 3
        override fun newDefaultViewModel(artboardHandle: Long): NativeCallResult<Long> = NativeCallResult(0, 4L)
        override fun viewModelRootSchemaIndex(viewModelHandle: Long): NativeCallResult<Long> = NativeCallResult(0, 0L)
        override fun viewModelCatalog(fileHandle: Long): NativeCallResult<NativeViewModelCatalog> =
            NativeCallResult(0, NativeViewModelCatalog(
                arrayOf(NativeViewModelSchema(0, "Root", 0, 0, 0, 0, -1, false)),
                emptyArray(), emptyArray(),
            ))
        override fun bindViewModel(artboardHandle: Long, viewModelHandle: Long): Int = 0
        override fun newDefaultPlayer(artboardHandle: Long): Long = 5
        override fun freePlayer(handle: Long) { freed += "player" }
        override fun freeViewModel(handle: Long): Int {
            freed += "view-model"
            return 4
        }
        override fun freeArtboard(handle: Long) { freed += "artboard" }
        override fun freeFile(handle: Long) {
            freed += "file"
            error("file cleanup failure")
        }
        override fun freeRenderer(handle: Long) { freed += "renderer" }
    }
}
