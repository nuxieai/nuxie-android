package ai.nuxie.sdk.runtime

import org.junit.Assert.*
import org.junit.Test

class AndroidVideoResourceDeviceTest {
    @Test fun anamorphicBudgetCoversActualDecodedFrame() {
        val instrumentation = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
        val file = java.io.File.createTempFile("anamorphic-video-", ".mp4", instrumentation.targetContext.cacheDir)
        try {
            instrumentation.context.assets.open("video/captions-anamorphic.mp4").use { input ->
                file.outputStream().use { input.copyTo(it) }
            }
            val decoder = AndroidVideoDecoder(instrumentation.targetContext, file, 1, 64 * 1024 * 1024, 1)
            try {
                val preparedDeadline = android.os.SystemClock.elapsedRealtime() + 10_000
                while (!decoder.ready() && decoder.failure() == null && android.os.SystemClock.elapsedRealtime() < preparedDeadline) Thread.sleep(5)
                assertNull(decoder.failure())
                assertTrue(decoder.ready())
                decoder.action(2, 0.1, 2)
                var frame: AndroidVideoDecoder.Frame? = null
                val deadline = android.os.SystemClock.elapsedRealtime() + 3_000
                while (frame == null && android.os.SystemClock.elapsedRealtime() < deadline) {
                    assertNull(decoder.failure())
                    frame = decoder.takeFrame()?.takeIf { it.generation == 2L }
                    if (frame == null) Thread.sleep(5)
                }
                val decoded = checkNotNull(frame)
                assertEquals(128, decoded.width)
                assertEquals(32, decoded.height)
                assertEquals(128 * 32 * 4, decoded.rgba.size)
                assertEquals(decoded.width.toLong() * decoded.height * 31, ExperienceVideoDecodeCost.read(file))
            } finally { decoder.close() }
        } finally { file.delete() }
    }

    @Test fun delayedDecoderRetirementRetainsCapacityUntilResourcesAreReleased() {
        assertTrue(NuxieRuntime.shared.isAvailable)
        val instrumentation = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
        val file = java.io.File.createTempFile("retire-video-", ".mp4", instrumentation.targetContext.cacheDir)
        instrumentation.context.assets.open("video/captions.mp4").use { input -> file.outputStream().use { input.copyTo(it) } }
        val decoder = AndroidVideoDecoder(instrumentation.targetContext, file, 0, 64 * 1024 * 1024, 0)
        val blocked = java.util.concurrent.CountDownLatch(1)
        val unblock = java.util.concurrent.CountDownLatch(1)
        val released = java.util.concurrent.CountDownLatch(1)
        val callbacks = java.util.concurrent.atomic.AtomicInteger()
        val pool = ExperienceVideoDecoderPool { NuxieVideoDecoderBudget(1, 1, 0, 100, 0) }
        val old = java.util.UUID.randomUUID()
        val next = java.util.UUID.randomUUID()
        val demand = listOf(NuxieVideoDecoderRequest(1, 100, 0, true))
        try {
            assertEquals(setOf(1L), pool.update(old, demand))
            // Fault injection blocks the real worker, including its queued release.
            val field = AndroidVideoDecoder::class.java.getDeclaredField("handler").apply { isAccessible = true }
            val handler = field.get(decoder) as android.os.Handler
            assertTrue(handler.post { blocked.countDown(); unblock.await(10, java.util.concurrent.TimeUnit.SECONDS) })
            assertTrue(blocked.await(5, java.util.concurrent.TimeUnit.SECONDS))
            decoder.whenReleased { pool.remove(old); callbacks.incrementAndGet(); released.countDown() }
            try { decoder.close(); fail("The blocked worker must not report successful synchronous release") }
            catch (_: IllegalStateException) { }
            assertEquals(0, callbacks.get())
            assertEquals(emptySet<Long>(), pool.update(next, demand))
            unblock.countDown()
            assertTrue(released.await(5, java.util.concurrent.TimeUnit.SECONDS))
            assertEquals(1, callbacks.get())
            assertEquals(setOf(1L), pool.update(next, demand))
            decoder.close()
            decoder.whenReleased { callbacks.incrementAndGet() }
            assertEquals(2, callbacks.get())
        } finally {
            unblock.countDown()
            decoder.close()
            file.delete()
        }
    }

    @Test fun sharedPoolWaitsForDisposalBeforePriorityHandoff() {
        assertTrue(NuxieRuntime.shared.isAvailable)
        val pool = ExperienceVideoDecoderPool { NuxieVideoDecoderBudget(1, 1, 0, 100, 0) }
        val background = java.util.UUID.randomUUID()
        val foreground = java.util.UUID.randomUUID()
        val low = listOf(NuxieVideoDecoderRequest(5, 100, 0, true))
        val high = listOf(NuxieVideoDecoderRequest(5, 100, 10, true))
        assertEquals(setOf(5L), pool.update(background, low))
        assertEquals(emptySet<Long>(), pool.update(foreground, high))
        assertEquals(emptySet<Long>(), pool.update(background, low))
        assertEquals(emptySet<Long>(), pool.update(foreground, high))
        pool.release(background, 5)
        assertEquals(setOf(5L), pool.update(foreground, high))
        pool.remove(foreground)
        assertEquals(setOf(5L), pool.update(background, low))
    }

    @Test fun sharedPoolRetainsRemovedAndResizedClaimsUntilDisposal() {
        assertTrue(NuxieRuntime.shared.isAvailable)
        val pool = ExperienceVideoDecoderPool { NuxieVideoDecoderBudget(3, 3, 0, 100, 0) }
        val first = java.util.UUID.randomUUID()
        val second = java.util.UUID.randomUUID()
        assertEquals(setOf(1L), pool.update(first, listOf(NuxieVideoDecoderRequest(1, 80, 0, true))))
        assertEquals(emptySet<Long>(), pool.update(first, emptyList()))
        val next = listOf(NuxieVideoDecoderRequest(2, 30, 10, true))
        assertEquals(emptySet<Long>(), pool.update(second, next))
        pool.release(first, 1)
        assertEquals(setOf(2L), pool.update(second, next))
        val resized = listOf(NuxieVideoDecoderRequest(2, 60, 10, true))
        assertEquals(emptySet<Long>(), pool.update(second, resized))
        pool.release(second, 2)
        assertEquals(setOf(2L), pool.update(second, resized))
        assertEquals(setOf(1L), pool.update(first, listOf(NuxieVideoDecoderRequest(1, 40, 0, true))))
    }

    @Test fun concurrentOwnerLanesCannotDoubleReserveCapacity() {
        assertTrue(NuxieRuntime.shared.isAvailable)
        val pool = ExperienceVideoDecoderPool { NuxieVideoDecoderBudget(1, 1, 0, 100, 0) }
        val executor = java.util.concurrent.Executors.newFixedThreadPool(8)
        val start = java.util.concurrent.CountDownLatch(1)
        try {
            val results = (1..8).map {
                executor.submit<Boolean> {
                    check(start.await(5, java.util.concurrent.TimeUnit.SECONDS))
                    pool.update(java.util.UUID.randomUUID(), listOf(NuxieVideoDecoderRequest(5, 100, 0, true))).isNotEmpty()
                }
            }
            start.countDown()
            assertEquals(1, results.count { it.get(5, java.util.concurrent.TimeUnit.SECONDS) })
        } finally { executor.shutdownNow() }
    }

    @Test fun readsBoundedDecodeCostFromRetainedFile() {
        val instrumentation = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
        val file = java.io.File.createTempFile("decode-cost-", ".mp4", instrumentation.targetContext.cacheDir)
        try {
            instrumentation.context.assets.open("video/captions.mp4").use { input ->
                file.outputStream().use { input.copyTo(it) }
            }
            // 64x32 at 30fps; microsecond timestamp rounding conservatively admits 31fps.
            assertEquals(64L * 32 * 31, ExperienceVideoDecodeCost.read(file))
        } finally { file.delete() }
    }

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
