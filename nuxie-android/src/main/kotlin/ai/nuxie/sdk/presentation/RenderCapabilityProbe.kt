package ai.nuxie.sdk.presentation

import ai.nuxie.sdk.runtime.NuxieRuntime
import ai.nuxie.sdk.runtime.NuxieRuntimeLane
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Caches a real renderer-creation probe rather than treating library load as capability. */
internal class RenderCapabilityProbe(
    private val libraryAvailable: () -> Boolean,
    private val probeRenderer: () -> Boolean,
) {
    @Volatile
    private var cached: Boolean? = null

    fun isAvailable(): Boolean = cached ?: synchronized(this) {
        cached ?: (libraryAvailable() && runCatching(probeRenderer).getOrDefault(false))
            .also { cached = it }
    }
}

/**
 * Device render capability used before artifact acquisition or Activity launch.
 * The headless Vulkan renderer is created and freed on one runtime lane, once.
 */
internal object AndroidRenderCapability {
    private val runtime = NuxieRuntime.shared
    private val probe = RenderCapabilityProbe(
        libraryAvailable = { runtime.isAvailable },
        probeRenderer = { probeNativeRenderer(runtime, NuxieRuntimeLane()) },
    )

    fun isAvailable(): Boolean = probe.isAvailable()
}

/** Creates and frees the probe renderer on the supplied lane, then closes that lane. */
internal fun probeNativeRenderer(
    runtime: NuxieRuntime,
    lane: NuxieRuntimeLane,
    timeoutMillis: Long = 5_000L,
): Boolean {
    val completed = CountDownLatch(1)
    val capable = AtomicBoolean(false)
    val accepted = lane.enqueue {
        val renderer = runtime.newAndroidVulkanRenderer(1, 1)
        if (renderer != null) {
            renderer.close()
            capable.set(true)
        }
    }
    lane.shutdown { completed.countDown() }
    if (!accepted) return false
    return try {
        completed.await(timeoutMillis, TimeUnit.MILLISECONDS) && capable.get()
    } catch (_: InterruptedException) {
        Thread.currentThread().interrupt()
        false
    }
}
