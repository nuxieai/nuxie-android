package ai.nuxie.sdk.runtime

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

        override fun resizeRenderer(handle: Long, pixelWidth: Int, pixelHeight: Int): Int = 5

        override fun renderAndPresent(
            rendererHandle: Long,
            playerHandle: Long,
            windowHandle: Long,
            clearColor: Int,
            fitContainCenter: Boolean,
        ): Int = 1

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
