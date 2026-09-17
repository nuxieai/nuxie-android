package ai.nuxie.sdk.presentation

import android.os.Looper
import ai.nuxie.sdk.experiences.SystemFontCache
import ai.nuxie.sdk.experiences.SystemFontException
import ai.nuxie.sdk.experiences.SystemFontRequirement
import ai.nuxie.sdk.runtime.ExpectedFileAsset
import ai.nuxie.sdk.runtime.FileAssetKind
import ai.nuxie.sdk.runtime.NuxImageDecoder
import ai.nuxie.sdk.runtime.NuxieRuntime
import ai.nuxie.sdk.runtime.NuxieRuntimeLane
import ai.nuxie.sdk.runtime.NuxieTypedRuntimeNative
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ExperienceSurfaceHostFontFailureTest {
    @Test fun `null native import reports failure and evicts the selected font`() = checkRejectedImport(false)

    @Test fun `thrown native import reports failure and evicts the selected font`() = checkRejectedImport(true)

    private fun checkRejectedImport(throws: Boolean) {
        val cache = SystemFontCache()
        val lease = cache.prepare(SystemFontRequirement("native-10", 700, "normal"))
        cache.didImport(listOf(lease))
        val failure = IllegalStateException("native decode failed")
        val native = RejectingNative(if (throws) failure else null)
        withHost(native, cache) { host, lane, failures, loaded ->
            host.loadArtboard(byteArrayOf(1), "Main", descriptor) { loaded += it }
            drain(lane)
            assertEquals(listOf(false), loaded)
            assertEquals(1, failures.size)
            assertEquals(ExperiencePresentationException.Reason.PREPARATION_FAILED, failures.single().reason)
            if (throws) assertSame(failure, failures.single().cause)
            assertEquals(1, native.imports)
            var extracted = false
            cache.candidate(lease.identity) { extracted = true; lease.candidate }
            assertTrue("Rejected bytes must not survive in the cache", extracted)
        }
    }

    @Test
    @Config(sdk = [23])
    @GraphicsMode(GraphicsMode.Mode.LEGACY)
    fun `unavailable device face reaches presentation as a typed failure without native import`() {
        val native = RejectingNative(null)
        withHost(native, SystemFontCache()) { host, lane, failures, loaded ->
            host.loadArtboard(byteArrayOf(1), "Main", descriptor) { loaded += it }
            drain(lane)
            assertEquals(listOf(false), loaded)
            assertEquals(1, failures.size)
            assertEquals(SystemFontException.Reason.FACE_UNAVAILABLE,
                (failures.single().cause as SystemFontException).reason)
            assertEquals(0, native.imports)
        }
    }

    private fun withHost(
        native: RejectingNative,
        cache: SystemFontCache,
        block: (ExperienceSurfaceHost, NuxieRuntimeLane, MutableList<ExperiencePresentationException>, MutableList<Boolean>) -> Unit,
    ) {
        val lane = NuxieRuntimeLane()
        val failures = mutableListOf<ExperiencePresentationException>()
        val loaded = mutableListOf<Boolean>()
        val host = ExperienceSurfaceHost(RuntimeEnvironment.getApplication(), lane,
            listener = object : ExperienceSurfaceHost.Listener {
                override fun onFirstFrame() = fail("Failed imports must never reveal content")
                override fun onFailure(error: ExperiencePresentationException) { failures += error }
            }, runtime = NuxieRuntime(native), systemFontCache = cache)
        try { block(host, lane, failures, loaded) }
        finally {
            host.release()
            drain(lane)
            host.release()
            drain(lane)
            lane.shutdown()
            assertTrue(lane.awaitQuiescence(5_000))
            assertEquals(1, native.rendererFrees)
        }
    }

    private fun drain(lane: NuxieRuntimeLane) {
        runBlocking { lane.call {} }
        shadowOf(Looper.getMainLooper()).idle()
    }

    private class RejectingNative(val failure: Throwable?) : NuxieTypedRuntimeNative {
        override val isAvailable = true
        var imports = 0
        var rendererFrees = 0
        override fun newAndroidVulkanRenderer(pixelWidth: Int, pixelHeight: Int): Long = 1
        override fun inspectFileAssets(bytes: ByteArray) = listOf(
            ExpectedFileAsset(0, FileAssetKind.FONT, 10, "native", "ttf", false, false, 3),
        )
        override fun newFile(rendererHandle: Long, bytes: ByteArray, expectedAssets: List<ExpectedFileAsset>,
            externalAssets: Map<Int, ByteArray>, imageDecoder: NuxImageDecoder): Long {
            imports++
            assertTrue(externalAssets.getValue(0).isNotEmpty())
            failure?.let { throw it }
            return 0
        }
        override fun freeRenderer(handle: Long) { rendererFrees++ }
    }

    private val descriptor = Json.parseToJsonElement("""{
      "render":{"assets":[{"kind":"font","location":"system","family":"System",
      "weight":"700","style":"normal","riveAssetId":10,"riveUniqueName":"native-10","required":true}]}
    }""").jsonObject
}
