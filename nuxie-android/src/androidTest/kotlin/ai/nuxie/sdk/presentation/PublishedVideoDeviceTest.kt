package ai.nuxie.sdk.presentation

import ai.nuxie.sdk.runtime.NuxieRuntime
import android.content.Intent
import android.graphics.Color
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
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

class PublishedVideoDeviceTest {
    @Test
    fun mountedPublishedVideoPresentsPixelsCaptionsAndRetiresThemWhenHidden() {
        verifyPublishedVideo("greeting", "clip-view", 1, 100, 80)
    }

    @Test
    fun mountedListVideosPresentAndAcceptJourneyCommands() {
        verifyPublishedVideo("list", "item-card", 2, 20, 30)
    }

    @Test fun firstPresentationWaitsForDecodedVideo() {
        verifyPublishedVideo("waiting", "clip-view", 1, 100, 80)
    }

    private fun verifyPublishedVideo(sceneName: String, viewNodeId: String, expectedOwners: Int,
        sampleX: Int, sampleY: Int) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        assertTrue(NuxieRuntime.shared.isAvailable)
        val directory = File(instrumentation.targetContext.cacheDir, "published-video-test").apply { mkdirs() }
        fun copy(name: String) = File(directory, name).also { file ->
            instrumentation.context.assets.open("video/$name").use { input -> file.outputStream().use { input.copyTo(it) } }
        }
        val scene = copy("$sceneName.nux")
        val video = copy("captions.mp4")
        val digest = MessageDigest.getInstance("SHA-256").digest(video.readBytes()).joinToString("") { "%02x".format(it) }
        val key = "assets/sha256/$digest.mp4"
        val inventoryName = if (sceneName == "greeting") "inventory" else "$sceneName-inventory"
        val inventory = instrumentation.context.assets.open("video/$inventoryName.json").bufferedReader().use {
            Json.parseToJsonElement(it.readText()).jsonObject
        }
        val asset = JsonObject(inventory.getValue("assets").jsonArray.single().jsonObject + mapOf(
            "key" to JsonPrimitive(key), "sha256" to JsonPrimitive(digest), "sizeBytes" to JsonPrimitive(video.length()),
            "captionTracks" to buildJsonArray { add(buildJsonObject {
                put("streamIndex", 2); put("codec", "mov_text"); put("language", "en"); put("title", JsonNull)
            }) },
        ))
        val descriptor = buildJsonObject {
            put("render", JsonObject(inventory + mapOf("renderer" to JsonPrimitive("nux"), "assets" to JsonArray(listOf(asset)))))
            put("leg", instrumentation.context.assets.open("video/$sceneName-journey.json").bufferedReader().use {
                Json.parseToJsonElement(it.readText())
            })
        }
        val screen = inventory.getValue("screens").jsonArray.single().jsonObject
        val width = screen.getValue("width").jsonPrimitive.float
        val height = screen.getValue("height").jsonPrimitive.float
        val prepared = PreparedPresentation(scene, screen.getValue("artboardName").jsonPrimitive.content,
            Color.BLACK, PresentationShell.FullScreen,
            "screen", descriptor, mapOf(key to video), ExperienceArtboardSize(width, height))
        val activity = instrumentation.startActivitySync(Intent(instrumentation.targetContext,
            SurfaceCompatibilityHostActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        val failure = AtomicReference<Throwable?>()
        val first = CountDownLatch(1)
        val firstContainsVideo = java.util.concurrent.atomic.AtomicBoolean()
        val closed = CountDownLatch(1)
        val ownerCount = java.util.concurrent.atomic.AtomicInteger()
        var created = false
        lateinit var mounted: ExperienceMountedScreen
        lateinit var content: View
        fun labels(view: View): List<TextView> = if (view is TextView) listOf(view)
            else if (view is ViewGroup) (0 until view.childCount).flatMap { labels(view.getChildAt(it)) } else emptyList()
        try {
            instrumentation.runOnMainSync {
                mounted = ExperienceMountedScreen(activity, prepared, object : ExperienceSurfaceHost.Listener {
                    override fun onFirstFrame() {
                        if (sceneName == "waiting") {
                            mounted.surface.bitmap?.let { bitmap ->
                                val scale = minOf(bitmap.width / width, bitmap.height / height)
                                val x = ((bitmap.width - width * scale) / 2 + sampleX * scale).toInt()
                                val y = ((bitmap.height - height * scale) / 2 + sampleY * scale).toInt()
                                val pixel = bitmap.getPixel(x, y)
                                firstContainsVideo.set((Color.red(pixel) > 180 && Color.blue(pixel) < 70) ||
                                    (Color.blue(pixel) > 180 && Color.red(pixel) < 70))
                                bitmap.recycle()
                            }
                        }
                        first.countDown()
                    }
                    override fun onVideoCaptions(captions: Map<Long, ai.nuxie.sdk.runtime.NuxieVideoCaption>) {
                        ownerCount.accumulateAndGet(captions.size, ::maxOf)
                    }
                    override fun onFailure(error: ExperiencePresentationException) { failure.set(error); first.countDown() }
                }, failure::set)
                created = true
                content = mounted.mount()
                activity.setContentView(content)
                mounted.observeWindow()
            }
            assertTrue("First presented frame", first.await(15, TimeUnit.SECONDS))
            failure.get()?.let { throw AssertionError("First presentation failed", it) }
            if (sceneName == "waiting") assertTrue("First admitted presentation contains decoded video", firstContainsVideo.get())
            val colors = mutableListOf<Boolean>()
            val captions = mutableSetOf<String>()
            val accessibleCaptions = mutableSetOf<String>()
            var screenshotSaved = false
            var layoutChecks = 0
            fun collectAccessibility(node: android.view.accessibility.AccessibilityNodeInfo) {
                node.text?.toString()?.let { accessibleCaptions += it }
                for (index in 0 until node.childCount) node.getChild(index)?.let(::collectAccessibility)
            }
            val deadline = SystemClock.elapsedRealtime() + 15_000
            while (SystemClock.elapsedRealtime() < deadline && (colors.size < 4 || captions.size < 2)) {
                failure.get()?.let { throw AssertionError("Mounted playback failed", it) }
                instrumentation.runOnMainSync {
                    mounted.surface.bitmap?.let { bitmap ->
                        val scale = minOf(bitmap.width / width, bitmap.height / height)
                        val x = ((bitmap.width - width * scale) / 2 + sampleX * scale).toInt()
                        val y = ((bitmap.height - height * scale) / 2 + sampleY * scale).toInt()
                        val pixel = bitmap.getPixel(x, y)
                        if (Color.red(pixel) > 180 && Color.blue(pixel) < 70 && colors.lastOrNull() != true) colors += true
                        if (Color.blue(pixel) > 180 && Color.red(pixel) < 70 && colors.lastOrNull() != false) colors += false
                        bitmap.recycle()
                    }
                    labels(content).filter { it.visibility == View.VISIBLE && it.text.isNotEmpty() }.forEach {
                        assertEquals(View.IMPORTANT_FOR_ACCESSIBILITY_YES, it.importantForAccessibility)
                        if (it.height > 0) {
                            layoutChecks++
                            val owner = it.parent as View
                            assertTrue("Caption bottom padding=${owner.paddingBottom}, density=${it.resources.displayMetrics.density}",
                                owner.paddingBottom >= (16 * it.resources.displayMetrics.density).toInt())
                            val visible = android.graphics.Rect()
                            assertTrue(it.getGlobalVisibleRect(visible))
                            val windowBounds = android.graphics.Rect()
                            it.getWindowVisibleDisplayFrame(windowBounds)
                            val screenPosition = IntArray(2)
                            it.getLocationOnScreen(screenPosition)
                            assertTrue("Caption must fit visible screen: y=${screenPosition[1]} height=${it.height} window=$windowBounds",
                                screenPosition[1] + it.height <= windowBounds.bottom)
                            assertTrue("Caption must fit visible window: $visible in $windowBounds", visible.bottom <= windowBounds.bottom)
                            assertEquals("Caption clipped: label top=${it.top} bottom=${it.bottom} owner=${owner.height} padding=${owner.paddingBottom}", it.height, visible.height())
                            assertTrue("Caption must retain bottom padding: bottom=${it.bottom} owner=${owner.height} padding=${owner.paddingBottom}",
                                it.bottom <= owner.height - owner.paddingBottom)
                        }
                        captions += it.text.toString()
                    }
                }
                instrumentation.uiAutomation.rootInActiveWindow?.let(::collectAccessibility)
                if (!screenshotSaved && layoutChecks >= 3 && colors.size >= 2 && captions.contains("Welcome")) {
                    instrumentation.uiAutomation.takeScreenshot()?.let { bitmap ->
                        File(instrumentation.targetContext.getExternalFilesDir(null), "task3b-$sceneName-video-caption.png").outputStream().use {
                            bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
                        }
                        bitmap.recycle()
                        screenshotSaved = true
                    }
                }
                Thread.sleep(30)
            }
            assertEquals(expectedOwners, ownerCount.get())
            assertEquals(listOf(true, false, true, false), colors.take(4))
            assertTrue("Visible captions: $captions", captions.containsAll(listOf("Hello 👋", "Welcome")))
            assertTrue("Accessibility tree captions: $accessibleCaptions", accessibleCaptions.any { it == "Hello 👋" || it == "Welcome" })
            assertTrue("Screenshot captured", screenshotSaved)
            assertTrue("Caption layout must be measured", layoutChecks > 0)
            fun command(type: String, extra: String = "", view: String = viewNodeId): Boolean = runBlocking {
                withTimeout(5_000) {
                    mounted.surface.applyVideoCommand(ai.nuxie.sdk.experiences.JourneyVideoAction.parse(
                        Json.parseToJsonElement("""{"type":"video","target":{"artboardId":"screen","viewNodeId":"$view"},"command":{"type":"$type"$extra}}""")))
                }
            }
            fun pixelMatches(expectRed: Boolean = true): Boolean {
                var matches = false
                instrumentation.runOnMainSync {
                    mounted.surface.bitmap?.let { bitmap ->
                        val scale = minOf(bitmap.width / width, bitmap.height / height)
                        val x = ((bitmap.width - width * scale) / 2 + sampleX * scale).toInt()
                        val y = ((bitmap.height - height * scale) / 2 + sampleY * scale).toInt()
                        val pixel = bitmap.getPixel(x, y)
                        matches = if (expectRed) Color.red(pixel) > 180 && Color.blue(pixel) < 70
                            else Color.blue(pixel) > 180 && Color.red(pixel) < 70
                        bitmap.recycle()
                    }
                }
                return matches
            }
            assertFalse("Unknown targets must fail without affecting playback", command("pause", view = "missing"))
            assertTrue("Pause must acknowledge native application", command("pause"))
            assertTrue("Seek must acknowledge native application", command("seek", ",\"seconds\":0.1"))
            val seekDeadline = SystemClock.elapsedRealtime() + 3_000
            while (!pixelMatches() && SystemClock.elapsedRealtime() < seekDeadline) Thread.sleep(20)
            assertTrue("Paused seek must present its red frame", pixelMatches())
            Thread.sleep(1_200)
            assertTrue("Paused video must retain its frame", pixelMatches())
            repeat(60) { index ->
                val red = index % 2 != 0
                val seconds = if (red) 0.1 else 1.2
                assertTrue(command("seek", ",\"seconds\":$seconds"))
                val deadline = SystemClock.elapsedRealtime() + 3_000
                while (!pixelMatches(red) && SystemClock.elapsedRealtime() < deadline) Thread.sleep(10)
                assertTrue("Paused seek $index must present ${if (red) "red" else "blue"}", pixelMatches(red))
            }
            assertTrue("Play must acknowledge native application", command("play"))
            instrumentation.runOnMainSync { mounted.setVisible(false) }
            Thread.sleep(250)
            instrumentation.runOnMainSync { assertTrue(labels(content).none { it.visibility == View.VISIBLE && it.text.isNotEmpty() }) }
        } finally {
            instrumentation.runOnMainSync {
                if (created) mounted.close(false) { closed.countDown() } else closed.countDown()
                activity.finish()
            }
            assertTrue("Decoder and renderer teardown", closed.await(10, TimeUnit.SECONDS))
            directory.deleteRecursively()
        }
    }
}
