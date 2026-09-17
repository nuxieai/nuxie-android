package ai.nuxie.sdk.runtime

import ai.nuxie.sdk.fixtures.FixtureRunner
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class NuxieOwnedRuntimeTest {
    @Test
    fun `typed entry point exposes availability info catalog and configured import`() {
        val native = RecordingNative()
        val runtime = NuxieRuntime(native)
        val decoder = NuxImageDecoder { _, _, _ -> null }
        val asset = ExpectedFileAsset(
            ordinal = 0,
            kind = FileAssetKind.IMAGE,
            authoredId = 42,
            name = "hero",
            fileExtension = "png",
            isEmbedded = false,
            hasContentsRecord = true,
            requiredProviderFlags = 1,
        )
        native.inspectedAssets = listOf(asset)
        val renderer = checkNotNull(runtime.newAndroidVulkanRenderer(1, 1))

        assertTrue(runtime.isAvailable)
        assertEquals("{\"sourceRevision\":\"abc\"}", runtime.info())
        assertEquals(listOf(asset), runtime.inspectFileAssets(byteArrayOf(9)))
        checkNotNull(runtime.importFile(
            renderer = renderer,
            bytes = byteArrayOf(1),
            expectedAssets = listOf(asset),
            externalAssets = mapOf(0 to byteArrayOf(2)),
            imageDecoder = decoder,
        ))

        assertEquals(listOf(asset), native.importedExpectedAssets)
        assertEquals(50L, native.importedRendererHandle)
        assertEquals(setOf(0), native.importedExternalAssets.keys)
        assertSame(decoder, native.importedImageDecoder)
    }

    @Test
    fun `file close frees once and rejects artboard creation after close`() {
        val native = RecordingNative()
        val runtime = NuxieRuntime(native)
        val renderer = checkNotNull(runtime.newAndroidVulkanRenderer(1, 1))
        val file = checkNotNull(runtime.importFile(renderer, byteArrayOf(1, 2, 3)))

        file.close()
        file.close()

        assertEquals(listOf(10L), native.freedFiles)
        assertThrows(IllegalStateException::class.java) {
            file.newArtboard()
        }
        assertEquals(0, native.defaultArtboardCreations)
    }

    @Test
    fun `file import rejects a closed renderer factory`() {
        val native = RecordingNative()
        val runtime = NuxieRuntime(native)
        val renderer = checkNotNull(runtime.newAndroidVulkanRenderer(1, 1))
        renderer.close()

        assertThrows(IllegalStateException::class.java) {
            runtime.importFile(renderer, byteArrayOf(1))
        }
        assertEquals(null, native.importedRendererHandle)
    }

    @Test
    fun `artboard close frees once and rejects player creation after close`() {
        val native = RecordingNative()
        val runtime = NuxieRuntime(native)
        val renderer = checkNotNull(runtime.newAndroidVulkanRenderer(1, 1))
        val file = checkNotNull(runtime.importFile(renderer, byteArrayOf(1)))
        val artboard = checkNotNull(file.newArtboard("Main"))

        artboard.close()
        artboard.close()

        assertEquals(listOf(20L), native.freedArtboards)
        assertThrows(IllegalStateException::class.java) {
            artboard.newPlayer()
        }
        assertEquals(0, native.defaultPlayerCreations)
    }

    @Test
    fun `player close frees once and rejects frame steps after close`() {
        val native = RecordingNative()
        val runtime = NuxieRuntime(native)
        val renderer = checkNotNull(runtime.newAndroidVulkanRenderer(1, 1))
        val file = checkNotNull(runtime.importFile(renderer, byteArrayOf(1)))
        val artboard = checkNotNull(file.newArtboard())
        val player = checkNotNull(artboard.newPlayer("Idle"))

        assertEquals(7, player.step(0.016))
        player.close()
        player.close()

        assertEquals(listOf(30L), native.freedPlayers)
        assertThrows(IllegalStateException::class.java) {
            player.step(0.032)
        }
        assertEquals(listOf(0.016), native.frameSteps)
    }

    @Test
    fun `configured player step copies emitted runtime events`() {
        val native = RecordingNative()
        val runtime = NuxieRuntime(native)
        val renderer = checkNotNull(runtime.newAndroidVulkanRenderer(1, 1))
        val file = checkNotNull(runtime.importFile(renderer, byteArrayOf(1)))
        val artboard = checkNotNull(file.newArtboard())
        val player = checkNotNull(artboard.newPlayer())

        val outcome = player.stepWithEvents(
            elapsedSeconds = 0.016,
            pointers = listOf(
                NuxiePlayerPointerEvent(
                    kind = NuxiePlayerPointerKind.DOWN,
                    x = 12f,
                    y = 34f,
                    pointerId = 7,
                    timestampSeconds = 1.25f,
                ),
                NuxiePlayerPointerEvent(
                    kind = NuxiePlayerPointerKind.UP,
                    x = 12f,
                    y = 34f,
                    pointerId = 7,
                    timestampSeconds = 1.5f,
                ),
            ),
        )

        assertTrue(outcome.keepGoing)
        assertEquals(listOf("checkout"), outcome.events.map { it.name })
        assertEquals(listOf(0.016f), native.typedFrameSteps)
        assertEquals(
            listOf(
                NativePlayerPointer(0, 12f, 34f, 7, 1.25f),
                NativePlayerPointer(2, 12f, 34f, 7, 1.5f),
            ),
            native.typedPointers,
        )
    }

    @Test
    fun `renderer and window free once and reject rendering after close`() {
        val native = RecordingNative()
        val runtime = NuxieRuntime(native)
        val renderer = checkNotNull(runtime.newAndroidVulkanRenderer(100, 200))
        val file = checkNotNull(runtime.importFile(renderer, byteArrayOf(1)))
        val artboard = checkNotNull(file.newArtboard())
        val player = checkNotNull(artboard.newPlayer())
        val window = NuxieRuntimeWindow(40L, native)

        assertEquals(5, renderer.resize(300, 400))
        assertEquals(1, renderer.renderAndPresent(player, window, 0xFF000000.toInt(), true))

        renderer.detachSurface()
        window.close()
        window.close()
        assertEquals(listOf(40L), native.releasedWindows)
        assertThrows(IllegalStateException::class.java) {
            renderer.renderAndPresent(player, window, 0, true)
        }

        renderer.close()
        renderer.close()
        assertEquals(listOf(50L), native.freedRenderers)
        assertThrows(IllegalStateException::class.java) {
            renderer.resize(1, 1)
        }
    }

    @Test
    fun `surface attachment survives unavailable frames and renews after suboptimal`() {
        val native = RecordingNative()
        val runtime = NuxieRuntime(native)
        val renderer = checkNotNull(runtime.newAndroidVulkanRenderer(100, 200))
        val file = checkNotNull(runtime.importFile(renderer, byteArrayOf(1)))
        val player = checkNotNull(checkNotNull(file.newArtboard()).newPlayer())
        val window = NuxieRuntimeWindow(40L, native)
        native.presentation = 0
        assertEquals(0, renderer.renderAndPresent(player, window, 0, true))
        assertEquals(0, renderer.renderAndPresent(player, window, 0, true))
        assertEquals(listOf("attach:40"), native.surfaceCalls)
        native.presentation = 2
        assertEquals(2, renderer.renderAndPresent(player, window, 0, true))
        native.presentation = 1
        assertEquals(1, renderer.renderAndPresent(player, window, 0, true))
        assertEquals(listOf("attach:40", "attach:40"), native.surfaceCalls)
        renderer.close()
        renderer.close()
        assertEquals(listOf("attach:40", "attach:40", "detach"), native.surfaceCalls)
        assertEquals(listOf(50L), native.freedRenderers)
    }

    @Test
    fun `surface loss retries attachment without reporting a delivered frame`() {
        val native = RecordingNative()
        val runtime = NuxieRuntime(native)
        val renderer = checkNotNull(runtime.newAndroidVulkanRenderer(100, 200))
        val file = checkNotNull(runtime.importFile(renderer, byteArrayOf(1)))
        val player = checkNotNull(checkNotNull(file.newArtboard()).newPlayer())
        val window = NuxieRuntimeWindow(40L, native)
        native.presentation = 3
        assertEquals(0, renderer.renderAndPresent(player, window, 0, true))
        assertEquals(listOf("attach:40"), native.surfaceCalls)
        native.presentation = 1
        assertEquals(1, renderer.renderAndPresent(player, window, 0, true))
        assertEquals(listOf("attach:40", "attach:40"), native.surfaceCalls)
        renderer.close()
        assertEquals(listOf("attach:40", "attach:40", "detach"), native.surfaceCalls)
    }

    @Test
    fun `failed surface attachment never renders and resize retires attached surface`() {
        val native = RecordingNative()
        val runtime = NuxieRuntime(native)
        val renderer = checkNotNull(runtime.newAndroidVulkanRenderer(100, 200))
        val file = checkNotNull(runtime.importFile(renderer, byteArrayOf(1)))
        val player = checkNotNull(checkNotNull(file.newArtboard()).newPlayer())
        val window = NuxieRuntimeWindow(40L, native)
        native.attachStatus = 5
        assertEquals(-5, renderer.renderAndPresent(player, window, 0, true))
        assertEquals(0, native.presentCalls)
        native.attachStatus = 0
        assertEquals(1, renderer.renderAndPresent(player, window, 0, true))
        renderer.resize(300, 400)
        assertEquals(listOf("attach:40", "attach:40", "detach"), native.surfaceCalls)
        native.presentation = -5
        assertEquals(-5, renderer.renderAndPresent(player, window, 0, true))
        assertEquals(listOf("attach:40", "attach:40", "detach", "attach:40", "detach"), native.surfaceCalls)
        renderer.close()
    }

    @Test
    fun `failed detach remains owned until retry or renderer destruction`() {
        val native = RecordingNative()
        val runtime = NuxieRuntime(native)
        val renderer = checkNotNull(runtime.newAndroidVulkanRenderer(100, 200))
        val file = checkNotNull(runtime.importFile(renderer, byteArrayOf(1)))
        val player = checkNotNull(checkNotNull(file.newArtboard()).newPlayer())
        val window = NuxieRuntimeWindow(40L, native)
        assertEquals(1, renderer.renderAndPresent(player, window, 0, true))
        native.detachStatus = 5
        assertEquals(5, renderer.detachSurface())
        native.detachStatus = 0
        assertEquals(0, renderer.detachSurface())
        assertEquals(listOf("attach:40", "detach", "detach"), native.surfaceCalls)
        renderer.close()
        assertEquals(listOf(50L), native.freedRenderers)
    }

    @Test
    fun `renderer returns an owned CPU frame and rejects rendering after close`() {
        val native = RecordingNative()
        val runtime = NuxieRuntime(native)
        val renderer = checkNotNull(runtime.newAndroidVulkanRenderer(2, 1))
        val file = checkNotNull(runtime.importFile(renderer, byteArrayOf(1)))
        val artboard = checkNotNull(file.newArtboard())
        val player = checkNotNull(artboard.newPlayer())

        val frame = renderer.renderToCpuFrame(
            player = player,
            clearColor = 0xFFFF00FF.toInt(),
            fitContainCenter = true,
        )

        assertEquals(2, frame.width)
        assertEquals(1, frame.height)
        assertEquals(8, frame.rgba.size)
        assertEquals(0x7F, frame.rgba[4].toInt() and 0xff)

        renderer.close()
        assertThrows(IllegalStateException::class.java) {
            renderer.renderToCpuFrame(player, 0, true)
        }
    }

    @Test
    fun `unsupported surfaces copy without masking attachment failures`() {
        val contract = Json.parseToJsonElement(FixtureRunner.fixturesRoot()
            .resolve("runtime/surface-capability-android.json").readText()).jsonObject
        val native = RecordingNative().apply { resizeStatus = 0 }
        val runtime = NuxieRuntime(native)
        val renderer = checkNotNull(runtime.newAndroidVulkanRenderer(100, 200))
        val file = checkNotNull(runtime.importFile(renderer, byteArrayOf(1)))
        val player = checkNotNull(checkNotNull(file.newArtboard()).newPlayer())
        val window = NuxieRuntimeWindow(40L, native)
        for (status in contract.getValue("failureStatuses").jsonArray.map { it.jsonPrimitive.int }) {
            native.attachStatus = status
            assertEquals(-status, renderer.renderAndPresent(player, window, 0, true))
        }
        native.attachStatus = -99
        assertEquals(-4, renderer.renderAndPresent(player, window, 0, true))
        assertEquals(0, native.copyCalls)
        assertEquals(0, native.presentCalls)
        native.surfaceCalls.clear()
        native.attachStatus = contract.getValue("unsupportedAttachment").jsonPrimitive.int
        repeat(2) {
            assertEquals(contract.getValue("copyPresented").jsonPrimitive.int,
                renderer.renderAndPresent(player, window, 0, true))
        }
        assertEquals(2, native.copyCalls)
        assertEquals(0, native.presentCalls)
        assertEquals(listOf("attach:40"), native.surfaceCalls)
        native.copyDisposition = contract.getValue("copyFailure").jsonPrimitive.int
        assertEquals(native.copyDisposition, renderer.renderAndPresent(player, window, 0, true))
        window.close()
        val replacement = NuxieRuntimeWindow(41L, native)
        native.attachStatus = contract.getValue("attached").jsonPrimitive.int
        assertEquals(1, renderer.renderAndPresent(player, replacement, 0, true))
        assertEquals(1, native.presentCalls)
        renderer.resize(300, 400)
        native.attachStatus = contract.getValue("unsupportedAttachment").jsonPrimitive.int
        native.copyDisposition = 1
        assertEquals(1, renderer.renderAndPresent(player, replacement, 0, true))
        val attachments = native.surfaceCalls.toList()
        renderer.resize(400, 300)
        native.attachStatus = 0
        assertEquals(1, renderer.renderAndPresent(player, replacement, 0, true))
        assertEquals("Resize must retain the connected CPU producer", attachments, native.surfaceCalls)
        assertEquals(1, native.presentCalls)
        renderer.close()
        renderer.close()
        replacement.close()
        replacement.close()
        assertEquals(listOf("attach:40", "attach:41", "detach", "attach:41"), native.surfaceCalls)
        assertEquals(listOf(50L), native.freedRenderers)
        assertEquals(listOf(40L, 41L), native.releasedWindows)
    }

    private class RecordingNative : NuxieTypedRuntimeNative {
        override val isAvailable = true
        var inspectedAssets: List<ExpectedFileAsset>? = emptyList()
        var importedExpectedAssets = emptyList<ExpectedFileAsset>()
        var importedExternalAssets = emptyMap<Int, ByteArray>()
        var importedImageDecoder: NuxImageDecoder? = null
        var importedRendererHandle: Long? = null
        val freedFiles = mutableListOf<Long>()
        val freedArtboards = mutableListOf<Long>()
        val freedPlayers = mutableListOf<Long>()
        val frameSteps = mutableListOf<Double>()
        val typedFrameSteps = mutableListOf<Float>()
        var typedPointers = emptyList<NativePlayerPointer>()
        val freedRenderers = mutableListOf<Long>()
        val releasedWindows = mutableListOf<Long>()
        var defaultArtboardCreations = 0
        var defaultPlayerCreations = 0

        override fun newFile(
            rendererHandle: Long,
            bytes: ByteArray,
            expectedAssets: List<ExpectedFileAsset>,
            externalAssets: Map<Int, ByteArray>,
            imageDecoder: NuxImageDecoder,
            videoEnabled: Boolean,
        ): Long {
            importedRendererHandle = rendererHandle
            importedExpectedAssets = expectedAssets
            importedExternalAssets = externalAssets
            importedImageDecoder = imageDecoder
            return 10L
        }

        override fun runtimeInfo(): String = "{\"sourceRevision\":\"abc\"}"

        override fun inspectFileAssets(bytes: ByteArray): List<ExpectedFileAsset>? = inspectedAssets

        override fun freeFile(handle: Long) {
            freedFiles += handle
        }

        override fun newDefaultArtboard(fileHandle: Long): Long {
            defaultArtboardCreations += 1
            return 20L
        }

        override fun newNamedArtboard(fileHandle: Long, name: String): Long = 20L

        override fun freeArtboard(handle: Long) {
            freedArtboards += handle
        }

        override fun newDefaultPlayer(artboardHandle: Long): Long {
            defaultPlayerCreations += 1
            return 30L
        }

        override fun newNamedStateMachinePlayer(
            artboardHandle: Long,
            name: String,
        ): Long = 30L

        override fun stepPlayerFrame(playerHandle: Long, elapsedSeconds: Double): Int {
            frameSteps += elapsedSeconds
            return 7
        }

        override fun stepPlayer(
            playerHandle: Long,
            inputs: List<NativePlayerInput>,
            pointers: List<NativePlayerPointer>,
            elapsedSeconds: Float,
            correlationId: Long,
            textRunNames: List<String>,
        ): NativeCallResult<NativePlayerStepOutcome> {
            typedFrameSteps += elapsedSeconds
            typedPointers = pointers
            return NativeCallResult(
                status = 0,
                value = NativePlayerStepOutcome(
                    keepGoing = true,
                    pointerHits = intArrayOf(),
                    events = arrayOf(
                        NativeRuntimeEvent(
                            localIndex = 0,
                            coreType = 0,
                            name = "checkout",
                            url = "",
                            target = "",
                            delay = 0f,
                            properties = emptyArray(),
                        ),
                    ),
                    hostCommands = emptyArray(),
                    viewModelChanges = emptyArray(),
                ),
            )
        }

        override fun freePlayer(handle: Long) {
            freedPlayers += handle
        }

        override fun newAndroidVulkanRenderer(pixelWidth: Int, pixelHeight: Int): Long = 50L

        val surfaceCalls = mutableListOf<String>()
        var attachStatus = 0
        var detachStatus = 0
        var presentation = 1
        var presentCalls = 0
        var copyCalls = 0
        var copyDisposition = 1
        var resizeStatus = 5

        override fun attachRendererSurface(rendererHandle: Long, windowHandle: Long): Int {
            surfaceCalls += "attach:$windowHandle"
            return attachStatus
        }

        override fun detachRendererSurface(rendererHandle: Long): Int {
            surfaceCalls += "detach"
            return detachStatus
        }

        override fun resizeRenderer(handle: Long, pixelWidth: Int, pixelHeight: Int): Int = resizeStatus

        override fun renderAndPresent(
            rendererHandle: Long,
            playerHandle: Long,
            windowHandle: Long,
            clearColor: Int,
            fitContainCenter: Boolean,
        ): Int {
            presentCalls++
            return presentation
        }

        override fun copyPlayerToWindow(
            rendererHandle: Long,
            playerHandle: Long,
            windowHandle: Long,
            clearColor: Int,
            fitContainCenter: Boolean,
        ): Int {
            copyCalls++
            return copyDisposition
        }

        override fun renderToCpuFrame(
            rendererHandle: Long,
            playerHandle: Long,
            clearColor: Int,
            fitContainCenter: Boolean,
        ): NuxieCpuFrame = NuxieCpuFrame(
            width = 2,
            height = 1,
            rgba = byteArrayOf(0, 0, 0, 0, 0x7F, 0, 0, 0),
        )

        override fun freeRenderer(handle: Long) {
            freedRenderers += handle
        }

        override fun releaseWindow(handle: Long) {
            releasedWindows += handle
        }
    }
}
