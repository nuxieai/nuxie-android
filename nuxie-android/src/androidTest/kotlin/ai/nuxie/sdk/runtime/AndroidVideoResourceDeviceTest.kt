package ai.nuxie.sdk.runtime

import org.junit.Assert.*
import org.junit.Test

class AndroidVideoResourceDeviceTest {
    @Test fun nativeAllocatorHonorsPriorityBudgetAndInputOrder() {
        assertTrue(NuxieRuntime.shared.isAvailable)
        val budget = NuxieVideoDecoderBudget(2, 1, 1, 100, 100)
        val requests = listOf(
            NuxieVideoDecoderRequest(9, 100, 0, true, true, true),
            NuxieVideoDecoderRequest(4, 100, 10, true, true, true),
            NuxieVideoDecoderRequest(3, 100, 10, true),
            NuxieVideoDecoderRequest(1, 1, 99, false, true, true),
        )
        assertEquals(listOf(NuxieVideoAllocation.Poster, NuxieVideoAllocation.Hardware,
            NuxieVideoAllocation.PlatformManaged, NuxieVideoAllocation.Poster),
            JniNuxieTypedRuntimeNative.videoAllocateDecoders(requests, budget))
        assertEquals(emptyList<NuxieVideoAllocation>(),
            JniNuxieTypedRuntimeNative.videoAllocateDecoders(emptyList(), budget))
        val status = intArrayOf(-1)
        assertNull(NuxieRuntimeBridge.nativeVideoAllocateDecoders(longArrayOf(1, 1, 0, 3, 1, 1, 0, 3),
            budget.nativeValues(), status))
        assertEquals(5, status[0]) // NUX_STATUS_INVALID_ARGUMENT from the C ABI.
        assertNull(NuxieRuntimeBridge.nativeVideoAllocateDecoders(longArrayOf(1, 1, 0), budget.nativeValues(), status))
        assertEquals(5, status[0]) // NUX_STATUS_INVALID_ARGUMENT from the C ABI.
    }
    @Test fun reclamationPreservesPausedPositionAndRejectsOldReadyObservation() {
        val bytes = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().context.assets
            .open("video/greeting.nux").use { it.readBytes() }
        val runtime = NuxieRuntime.shared
        assertTrue(runtime.isAvailable)
        val renderer = checkNotNull(runtime.newAndroidVulkanRenderer(320, 640))
        try {
            val file = checkNotNull(runtime.importFile(renderer, bytes,
                checkNotNull(runtime.inspectFileAssets(bytes)), videoEnabled = true))
            try {
                val artboard = checkNotNull(file.newArtboard("Video Frame"))
                try {
                    val player = checkNotNull(artboard.newPlayer())
                    try {
                        val video = player.videos().single()
                        player.videoStep(video.componentId, 1, video.generation, 2.022)
                        player.videoCommand(video.componentId, 1)
                        player.videoCommand(video.componentId, 2, 1.0)
                        player.videoStep(video.componentId, 0, video.generation)
                        val oldGeneration = player.videos().single().generation
                        val denied = player.videoReclaimDecoder(video.componentId, true)
                        assertTrue(denied > oldGeneration)
                        assertTrue(player.videoStep(video.componentId, 1, oldGeneration, 2.022).isEmpty())
                        val reopened = player.videoReclaimDecoder(video.componentId, false)
                        assertTrue(reopened > denied)
                        val actions = player.videoStep(video.componentId, 1, reopened, 2.022)
                        assertTrue(actions.any { it.kind == 2 && it.value == 1.0 })
                        assertFalse(actions.any { it.kind == 0 })
                        assertFalse(player.videos().single().wantsPlay)
                    } finally { player.close() }
                } finally { artboard.close() }
            } finally { file.close() }
        } finally { renderer.close() }
    }

}
