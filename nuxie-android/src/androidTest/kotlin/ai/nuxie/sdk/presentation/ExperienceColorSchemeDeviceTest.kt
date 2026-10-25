package ai.nuxie.sdk.presentation

import ai.nuxie.sdk.runtime.NuxieRuntime
import ai.nuxie.sdk.runtime.NuxieRuntimeLane
import ai.nuxie.sdk.runtime.NuxieRuntimeViewModelState
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.*
import org.junit.Test

/** The unchanged iOS theme-entry fixture, mounted through the real Android surface. */
class ExperienceColorSchemeDeviceTest {
    @Test fun firstFrameAndLiveConfigurationChangesDrawTheMatchingTheme() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        assertTrue("The pinned Vulkan runtime must load", NuxieRuntime.shared.isAvailable)
        val bytes = instrumentation.context.assets.open("theme-entry/theme.riv").use { it.readBytes() }
        val provenance = instrumentation.context.assets.open("theme-entry/theme-provenance.json").bufferedReader().use {
            Json.parseToJsonElement(it.readText()).jsonObject
        }
        assertEquals(provenance.getValue("sha256").jsonPrimitive.content,
            MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) })

        val activity = instrumentation.startActivitySync(Intent(instrumentation.targetContext,
            SurfaceCompatibilityHostActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        try {
            for (initial in ExperienceColorScheme.entries) {
                val lane = NuxieRuntimeLane()
                val failure = AtomicReference<Throwable?>()
                val firstImage = AtomicReference<Bitmap?>()
                val loaded = CountDownLatch(1)
                val firstFrame = CountDownLatch(1)
                lateinit var host: ExperienceSurfaceHost
                var mountedHost: ExperienceSurfaceHost? = null
                try {
                    instrumentation.runOnMainSync {
                        val config = configuration(activity.resources.configuration, initial)
                        host = ExperienceSurfaceHost(activity.createConfigurationContext(config), lane,
                            artboardSize = ExperienceArtboardSize(393f, 852f),
                            initialPresentationSize = ExperienceArtboardSize(393f, 852f),
                            usesSystemFrameCallbacks = false,
                            listener = object : ExperienceSurfaceHost.Listener {
                                override fun onFirstFrame() {
                                    firstImage.set(host.bitmap)
                                    firstFrame.countDown()
                                }
                                override fun onFailure(error: ExperiencePresentationException) { failure.set(error) }
                            })
                        mountedHost = host
                        host.loadArtboard(bytes, "theme", onLoaded = { success ->
                            if (!success) failure.compareAndSet(null, AssertionError("theme.riv did not load"))
                            loaded.countDown()
                        })
                        activity.setContentView(host)
                        host.setPresentationVisible(true)
                    }
                    assertTrue("Theme mount must complete", loaded.await(30, TimeUnit.SECONDS))
                    val deadline = SystemClock.uptimeMillis() + 30_000
                    while (firstFrame.count > 0 && failure.get() == null && SystemClock.uptimeMillis() < deadline) {
                        instrumentation.runOnMainSync { host.doFrame(System.nanoTime()) }
                        delay(16)
                    }
                    failure.get()?.let { throw AssertionError("Theme presentation failed", it) }
                    assertEquals("The first frame must compose", 0L, firstFrame.count)
                    val bitmap = checkNotNull(firstImage.get())
                    assertSurface(bitmap, host.resources.displayMetrics.density, initial)
                    val initialHandle = lane.call { assertBindings(host, initial) }

                    for (scheme in listOf(other(initial), initial)) {
                        instrumentation.runOnMainSync {
                            assertTrue("Configuration changes happen while the surface is shown", host.isShown)
                            // Dispatch the platform View callback without changing the emulator's global setting.
                            host.dispatchConfigurationChanged(configuration(host.resources.configuration, scheme))
                        }
                        var matched = false
                        val changedDeadline = SystemClock.uptimeMillis() + 30_000
                        while (!matched && failure.get() == null && SystemClock.uptimeMillis() < changedDeadline) {
                            instrumentation.runOnMainSync {
                                host.doFrame(System.nanoTime())
                                host.bitmap?.let { image ->
                                    try { matched = surface(image, host.resources.displayMetrics.density) == color(scheme) }
                                    finally { image.recycle() }
                                }
                            }
                            if (!matched) delay(16)
                        }
                        failure.get()?.let { throw AssertionError("Appearance change failed", it) }
                        assertTrue("A live ${scheme.label} configuration must redraw the Theme color", matched)
                        val handle = lane.call { assertBindings(host, scheme) }
                        if (scheme == initial) assertEquals("Switching back reuses the retained instance", initialHandle, handle)
                        else assertNotEquals(initialHandle, handle)
                    }
                } finally {
                    firstImage.get()?.recycle()
                    instrumentation.runOnMainSync { mountedHost?.release() }
                    lane.shutdown()
                    assertTrue("All screen handles must retire", lane.awaitQuiescence(5_000))
                }
            }
        } finally { instrumentation.runOnMainSync { activity.finish() } }
    }

    @Test fun runtimeSchemaDefaultPreservesTheAuthoredLightColor() {
        val runtime = NuxieRuntime.shared
        assertTrue(runtime.isAvailable)
        val bytes = InstrumentationRegistry.getInstrumentation().context.assets.open("theme-entry/theme.riv").use { it.readBytes() }
        val renderer = checkNotNull(runtime.newAndroidVulkanRenderer(393, 852))
        try {
            val file = checkNotNull(runtime.importFile(renderer, bytes))
            try {
                val fallback = checkNotNull(file.newGlobalViewModel("Theme"))
                try {
                    assertEquals("A missing instance name uses the first authored instance through a null index",
                        color(ExperienceColorScheme.LIGHT).toUInt().toLong(),
                        fallback.nativeSnapshot().values.single { it.name == "surface" }.integerValue)
                } finally { fallback.close() }
            } finally { file.close() }
        } finally { renderer.close() }
    }

    /** Inspect the same per-screen instances the host bound, on their owning lane. */
    private fun assertBindings(host: ExperienceSurfaceHost, scheme: ExperienceColorScheme): Long {
        val environment = ExperienceSurfaceHost::class.java.getDeclaredField("environment")
            .apply { isAccessible = true }.get(host) as NuxieRuntimeViewModelState
        assertEquals(if (scheme == ExperienceColorScheme.DARK) 1L else 0L, environment.snapshot().resolveEnumOrdinal("colorScheme"))
        val bindings = ExperienceSurfaceHost::class.java.getDeclaredField("environmentBindings")
            .apply { isAccessible = true }.get(host) as ExperienceEnvironmentBindings
        @Suppress("UNCHECKED_CAST")
        val instances = ExperienceSurfaceHost::class.java.getDeclaredField("themeInstances")
            .apply { isAccessible = true }.get(host) as Map<ExperienceEnvironmentBindings.ThemeSelection, NuxieRuntimeViewModelState>
        val theme = checkNotNull(instances[bindings.themeSelection(scheme)])
        assertEquals(color(scheme).toUInt().toLong(), theme.nativeSnapshot().values.single { it.name == "surface" }.integerValue)
        return theme.requireHandle()
    }

    private fun assertSurface(bitmap: Bitmap, density: Float, scheme: ExperienceColorScheme) {
        assertEquals("The first frame draws the ${scheme.label} authored color", color(scheme), surface(bitmap, density))
    }

    private fun surface(bitmap: Bitmap, density: Float): Int = bitmap.getPixel((100 * density).toInt(), (100 * density).toInt())

    private fun color(scheme: ExperienceColorScheme): Int =
        if (scheme == ExperienceColorScheme.DARK) 0xFF1C1C1E.toInt() else 0xFFF2F2F7.toInt()

    private fun other(scheme: ExperienceColorScheme) =
        if (scheme == ExperienceColorScheme.DARK) ExperienceColorScheme.LIGHT else ExperienceColorScheme.DARK

    private fun configuration(base: Configuration, scheme: ExperienceColorScheme) = Configuration(base).apply {
        uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
            if (scheme == ExperienceColorScheme.DARK) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
    }
}
