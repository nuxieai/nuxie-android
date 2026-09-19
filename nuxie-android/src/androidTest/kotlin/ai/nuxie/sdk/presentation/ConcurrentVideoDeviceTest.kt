package ai.nuxie.sdk.presentation

import ai.nuxie.sdk.runtime.ExperienceVideoDecoderPool
import ai.nuxie.sdk.runtime.NuxieRuntime
import android.content.Intent
import android.graphics.Color
import android.os.SystemClock
import android.os.Debug
import java.util.UUID
import ai.nuxie.sdk.runtime.NuxieVideoDecoderRequest
import android.view.View
import android.widget.LinearLayout
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlinx.serialization.json.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test

class ConcurrentVideoDeviceTest {
    @Test fun fiveMountedLanesSaturateProductionPoolAndResumeAfterRetirement() = qualify(false)

    @Test fun repeatedPlaybackRetiresResourcesAndReusesPool() = qualify(true)

    private fun qualify(repeated: Boolean) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        assertTrue(NuxieRuntime.shared.isAvailable)
        val directory = File(instrumentation.targetContext.cacheDir, "concurrent-video-test").apply { mkdirs() }
        fun copy(name: String) = File(directory, name).also { file ->
            instrumentation.context.assets.open("video/$name").use { input -> file.outputStream().use { input.copyTo(it) } }
        }
        val scene = copy("greeting.nux")
        val video = copy("captions-720p.mp4")
        val digest = MessageDigest.getInstance("SHA-256").digest(video.readBytes()).joinToString("") { "%02x".format(it) }
        val key = "assets/sha256/$digest.mp4"
        val inventory = instrumentation.context.assets.open("video/inventory.json").bufferedReader().use {
            Json.parseToJsonElement(it.readText()).jsonObject
        }
        val asset = JsonObject(inventory.getValue("assets").jsonArray.single().jsonObject + mapOf(
            "key" to JsonPrimitive(key), "sha256" to JsonPrimitive(digest), "sizeBytes" to JsonPrimitive(video.length()),
            "width" to JsonPrimitive(1280), "height" to JsonPrimitive(720),
        ))
        val descriptor = buildJsonObject {
            put("render", JsonObject(inventory + mapOf("renderer" to JsonPrimitive("nux"), "assets" to JsonArray(listOf(asset)))))
            put("leg", instrumentation.context.assets.open("video/greeting-journey.json").bufferedReader().use {
                Json.parseToJsonElement(it.readText())
            })
        }
        val prepared = PreparedPresentation(scene, "Video Frame", Color.BLACK, PresentationShell.FullScreen,
            "screen", descriptor, mapOf(key to video), ExperienceArtboardSize(320f, 640f))
        val activity = instrumentation.startActivitySync(Intent(instrumentation.targetContext,
            SurfaceCompatibilityHostActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        val failure = AtomicReference<Throwable?>()
        val screens = mutableListOf<ExperienceMountedScreen>()
        val views = mutableListOf<View>()
        val retired = mutableSetOf<Int>()
        lateinit var container: LinearLayout
        fun mount() {
            val first = CountDownLatch(1)
            instrumentation.runOnMainSync {
                val screen = ExperienceMountedScreen(activity, prepared, object : ExperienceSurfaceHost.Listener {
                    override fun onFirstFrame() { first.countDown() }
                    override fun onFailure(error: ExperiencePresentationException) { failure.set(error) }
                }, failure::set, videoDecoderPool = ExperienceVideoDecoderPool.shared)
                screens += screen
                val view = screen.mount()
                views += view
                container.addView(view, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
                screen.observeWindow()
            }
            assertTrue("Mounted lane presents its scene", first.await(15, TimeUnit.SECONDS))
            // Motion backgrounds are muted; audible greetings would compete for audio focus.
            runBlocking {
                for (command in listOf("{\"type\":\"mute\",\"muted\":true}", "{\"type\":\"play\"}")) {
                    assertTrue(withTimeout(5_000) { screens.last().surface.applyVideoCommand(
                        ai.nuxie.sdk.experiences.JourneyVideoAction.parse(Json.parseToJsonElement(
                            """{"type":"video","target":{"artboardId":"screen","viewNodeId":"clip-view"},"command":$command}"""))) })
                }
            }
        }
        fun colors(): List<Boolean?> {
            failure.get()?.let { throw AssertionError("Concurrent screen failed", it) }
            val result = MutableList<Boolean?>(screens.size) { null }
            instrumentation.runOnMainSync {
                screens.forEachIndexed { index, screen ->
                    if (index !in retired) screen.surface.bitmap?.let { bitmap ->
                        val scale = minOf(bitmap.width / 320f, bitmap.height / 640f)
                        val x = ((bitmap.width - 320f * scale) / 2 + 100f * scale).toInt()
                        val y = ((bitmap.height - 640f * scale) / 2 + 80f * scale).toInt()
                        val pixel = bitmap.getPixel(x, y)
                        result[index] = when {
                            Color.red(pixel) > 180 && Color.blue(pixel) < 70 -> true
                            Color.blue(pixel) > 180 && Color.red(pixel) < 70 -> false
                            else -> null
                        }
                        bitmap.recycle()
                    }
                }
            }
            return result
        }
        fun awaitPhases(indices: Set<Int>, denied: Int? = null) {
            val observed = indices.associateWith { mutableSetOf<Boolean>() }
            val deadline = SystemClock.elapsedRealtime() + 30_000
            while (SystemClock.elapsedRealtime() < deadline && observed.values.any { it.size < 2 }) {
                val pixels = colors()
                denied?.let { assertNull("Fifth visible lane must not decode while four slots are occupied", pixels[it]) }
                observed.forEach { (index, phases) -> pixels[index]?.let(phases::add) }
                Thread.sleep(30)
            }
            assertTrue("Every admitted lane must render both video phases: $observed", observed.values.all { it.size == 2 })
        }
        try {
            instrumentation.runOnMainSync {
                container = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
                activity.setContentView(container)
            }
            if (repeated) {
                fun decoderThreads() = Thread.getAllStackTraces().keys.count { it.isAlive && it.name == "NuxieVideo" }
                val baselineThreads = decoderThreads()
                // Process PSS is observational, not a GPU measurement or pass ceiling.
                // Emulator Vulkan first-draw retention also reproduces without video.
                val pssKiB = mutableListOf<Int>()
                repeat(10) { cycle ->
                    mount()
                    awaitPhases(setOf(0))
                    runBlocking {
                        assertTrue(withTimeout(5_000) { screens.single().surface.applyVideoCommand(
                            ai.nuxie.sdk.experiences.JourneyVideoAction.parse(Json.parseToJsonElement(
                                """{"type":"video","target":{"artboardId":"screen","viewNodeId":"clip-view"},"command":{"type":"pause"}}"""))) })
                    }
                    val closed = CountDownLatch(1)
                    instrumentation.runOnMainSync {
                        retired += 0
                        screens.single().close(false) { closed.countDown() }
                        container.removeAllViews()
                    }
                    assertTrue("Cycle $cycle acknowledges decoder/native retirement", closed.await(10, TimeUnit.SECONDS))
                    screens.clear()
                    views.clear()
                    retired.clear()
                    val deadline = SystemClock.elapsedRealtime() + 5_000
                    while (decoderThreads() > baselineThreads && SystemClock.elapsedRealtime() < deadline) Thread.sleep(20)
                    assertEquals("No decoder worker survives a closed session", baselineThreads, decoderThreads())
                    val owner = UUID.randomUUID()
                    try {
                        val admitted = ExperienceVideoDecoderPool.shared.update(owner, (0L..3L).map {
                            NuxieVideoDecoderRequest(it, 1280L * 720 * 31, 0, true)
                        })
                        assertEquals("All production reservations are reusable", 4, admitted.size)
                    } finally {
                        ExperienceVideoDecoderPool.shared.remove(owner)
                    }
                    Thread.sleep(100)
                    val memory = Debug.MemoryInfo()
                    Debug.getMemoryInfo(memory)
                    pssKiB += memory.totalPss
                }
                val warmed = pssKiB.drop(2)
                println("VIDEO_LIFECYCLE cycles=10 retiredDecoderThreads=$baselineThreads reusableSlots=4 pssKiB=$pssKiB warmedMin=${warmed.minOrNull()} warmedMax=${warmed.maxOrNull()} measurement=processPSS_notGPU localFixture=noNetwork")
            } else {
                repeat(4) { mount() }
                awaitPhases(setOf(0, 1, 2, 3))
                mount()
                awaitPhases(setOf(0, 1, 2, 3), denied = 4)
                val closed = CountDownLatch(1)
                instrumentation.runOnMainSync {
                    retired += 0
                    screens[0].close(false) { closed.countDown() }
                    container.removeView(views[0])
                }
                assertTrue("Retirement must acknowledge native/decoder cleanup", closed.await(10, TimeUnit.SECONDS))
                awaitPhases(setOf(1, 2, 3, 4))
                println("concurrent-video: four independent 720p30 runtime lanes rendered both phases; fifth waited and recovered after retirement")
            }
        } finally {
            val closed = CountDownLatch(screens.size - retired.size)
            instrumentation.runOnMainSync {
                screens.forEachIndexed { index, screen -> if (index !in retired) screen.close(false) { closed.countDown() } }
                activity.finish()
            }
            assertTrue("All concurrent lanes retire", closed.await(15, TimeUnit.SECONDS))
            directory.deleteRecursively()
        }
    }
}
