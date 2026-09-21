package ai.nuxie.sdk.presentation

import ai.nuxie.sdk.runtime.NuxieRuntime
import ai.nuxie.sdk.runtime.NuxieViewModelSnapshot
import android.content.Intent
import android.os.SystemClock
import android.util.Base64
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

/** Qualifies publisher bytes below the still-closed signed descriptor admission gate. */
class PublishedNativeConverterDeviceTest {
    @Test fun plainDurationRejectsInvalidEditAndRecovers() = qualify(false)
    @Test fun secureDurationRejectsInvalidEditAndRecovers() = qualify(true)

    private fun qualify(secure: Boolean) {
        assertTrue(NuxieRuntime.shared.isAvailable)
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val assets = instrumentation.context.assets
        val prefix = if (secure) "native-converter-secure" else "native-converter"
        fun read(name: String) = assets.open("$prefix/$name").use { it.readBytes() }
        fun digest(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }
        val entry = Json.parseToJsonElement(read("release-entry.json").decodeToString()).jsonObject
        val envelope = entry.getValue("envelope").jsonObject
        val descriptorBytes = Base64.decode(envelope.getValue("descriptorBytesBase64").jsonPrimitive.content, Base64.NO_WRAP)
        assertEquals(envelope.getValue("descriptorSha256").jsonPrimitive.content, digest(descriptorBytes))
        val descriptor = Json.parseToJsonElement(descriptorBytes.decodeToString()).jsonObject
        val render = descriptor.getValue("render").jsonObject
        val screen = render.getValue("screens").jsonArray.single().jsonObject
        val screenId = screen.getValue("id").jsonPrimitive.content
        val input = ExperienceTextInput.forScreen(descriptor, screenId).single()
        assertNotNull("Publisher must emit the native endpoint", input.editableValueName)
        assertEquals(secure, input.secure)
        assertEquals(ExperienceTextInput.ResponseCapture.BINDING, input.responseCapture)
        val directory = File(instrumentation.targetContext.cacheDir, "$prefix-${System.nanoTime()}").apply { mkdirs() }
        fun stage(declaration: JsonObject): Pair<String, File> {
            val key = declaration.getValue("key").jsonPrimitive.content
            val bytes = read("bundle/$key")
            assertEquals(declaration.getValue("sha256").jsonPrimitive.content, digest(bytes))
            assertEquals(declaration.getValue("sizeBytes").jsonPrimitive.long, bytes.size.toLong())
            val file = File(directory, key).apply { parentFile!!.mkdirs(); writeBytes(bytes) }
            return key to file
        }
        val scene = stage(render.getValue("nux").jsonObject).second
        val scripts = (descriptor["screenBehaviors"] as? JsonArray).orEmpty().mapNotNull {
            (it.jsonObject["script"] as? JsonObject)?.get("artifact")?.jsonObject
        }
        val artifacts = (render.getValue("assets").jsonArray.map { it.jsonObject } + scripts).map(::stage).toMap()
        val prepared = PreparedPresentation(scene, screen.getValue("artboardName").jsonPrimitive.content,
            0xffdddddd.toInt(), PresentationShell.FullScreen, screenId, descriptor, artifacts,
            ExperienceArtboardSize(screen.getValue("width").jsonPrimitive.float, screen.getValue("height").jsonPrimitive.float))
        val activity = instrumentation.startActivitySync(Intent(instrumentation.targetContext,
            SurfaceCompatibilityHostActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        val failure = AtomicReference<Throwable?>()
        val commits = LinkedBlockingQueue<Pair<String, NuxieViewModelSnapshot?>>()
        var mounted: ExperienceMountedScreen? = null
        lateinit var content: View
        fun editors(view: View): List<EditText> = when (view) {
            is EditText -> listOf(view)
            is ViewGroup -> (0 until view.childCount).flatMap { editors(view.getChildAt(it)) }
            else -> emptyList()
        }
        try {
            instrumentation.runOnMainSync {
                mounted = ExperienceMountedScreen(activity, prepared, object : ExperienceSurfaceHost.Listener {
                    override fun onFirstFrame() { checkNotNull(mounted).activate() }
                    override fun onFailure(error: ExperiencePresentationException) { failure.set(error) }
                    override fun onTextCommitted(inputId: String, text: String, snapshot: NuxieViewModelSnapshot?) {
                        commits.add(text to snapshot)
                    }
                }, failure::set)
                content = checkNotNull(mounted).mount()
                activity.setContentView(content)
                checkNotNull(mounted).observeWindow()
            }
            var editor: EditText? = null
            val deadline = SystemClock.elapsedRealtime() + 15_000
            while (editor == null && failure.get() == null && SystemClock.elapsedRealtime() < deadline) {
                instrumentation.runOnMainSync { editor = editors(content).singleOrNull { it.isEnabled && it.visibility == View.VISIBLE } }
                SystemClock.sleep(25)
            }
            failure.get()?.let { throw AssertionError("Published native input mount failed", it) }
            val field = checkNotNull(editor) { "Published native input must be editable" }
            assertTrue("Mount cannot emit an answer", commits.isEmpty())
            fun edit(text: String) = instrumentation.runOnMainSync { field.requestFocus(); field.setText(text) }
            fun edited(text: String, seconds: Double) {
                edit(text)
                val event = checkNotNull(commits.poll(10, TimeUnit.SECONDS)) { "Expected native edit notification" }
                failure.get()?.let { throw AssertionError("Published native edit failed", it) }
                assertEquals(text, event.first)
                assertEquals(seconds, input.captureResponse(event.first, event.second).double, 0.0)
            }
            edited("2:00", 120.0)
            // A property write accepts the draft, not its validity. Failed reverse
            // conversion preserves the prior typed source and the correctable draft.
            // This notification is not a submission or proof of form validity.
            edited("not a duration", 120.0)
            instrumentation.runOnMainSync { assertEquals("not a duration", field.text.toString()) }
            edited("1:30", 90.0)
            // A valid edit may also map to an unchanged source value.
            edited("01:30", 90.0)
        } finally {
            val closed = CountDownLatch(1)
            instrumentation.runOnMainSync {
                mounted?.close(false) { closed.countDown() } ?: closed.countDown()
                activity.finish()
            }
            assertTrue("Mounted native lane must retire", closed.await(10, TimeUnit.SECONDS))
            directory.deleteRecursively()
        }
    }
}
