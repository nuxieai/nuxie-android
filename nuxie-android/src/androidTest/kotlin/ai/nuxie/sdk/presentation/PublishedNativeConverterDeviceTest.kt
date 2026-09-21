package ai.nuxie.sdk.presentation

import ai.nuxie.sdk.runtime.NuxieRuntime
import ai.nuxie.sdk.runtime.NuxieViewModelSnapshot
import ai.nuxie.sdk.runtime.NuxieHostCommand
import ai.nuxie.sdk.runtime.NuxieHostValue
import ai.nuxie.sdk.runtime.NuxiePlayerStepOutcome
import ai.nuxie.sdk.experiences.JourneyReleaseEnvelope
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
    @Test fun plainDurationValidatesDraftBeforeSubmission() = qualify(false, true)
    @Test fun secureDurationValidatesDraftBeforeSubmission() = qualify(true, true)

    private fun qualify(secure: Boolean, validate: Boolean = false) {
        assertTrue(NuxieRuntime.shared.isAvailable)
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val assets = instrumentation.context.assets
        val prefix = "native-converter" + (if (secure) "-secure" else "") + (if (validate) "-validated" else "")
        fun read(name: String) = assets.open("$prefix/$name").use { it.readBytes() }
        fun digest(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }
        val entry = Json.parseToJsonElement(read("release-entry.json").decodeToString()).jsonObject
        val envelope = entry.getValue("envelope").jsonObject
        val descriptorBytes = JourneyReleaseEnvelope.authenticate(
            envelope.toString().encodeToByteArray(),
            mapOf("TEST_ONLY_DEV_KEYPAIR" to Base64.decode(
                "IVL40Zt5HSRFMkLhXy6rbLfP+ntqXtMAl5YOBpiB2xI=", Base64.NO_WRAP)),
        ).descriptorBytes
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
        val commands = LinkedBlockingQueue<NuxieHostCommand>()
        val latestCommit = AtomicReference<Pair<String, NuxieViewModelSnapshot?>?>()
        val completionEvents = LinkedBlockingQueue<Pair<ExperienceSemanticTextDraft.Event, Pair<String, NuxieViewModelSnapshot?>?>>()
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
                    override fun onRuntimeStep(outcome: NuxiePlayerStepOutcome, correlationId: ULong, viewModelSnapshot: NuxieViewModelSnapshot?) {
                        commands.addAll(outcome.hostCommands)
                    }
                    override fun onTextCommitted(inputId: String, text: String, snapshot: NuxieViewModelSnapshot?) {
                        latestCommit.set(text to snapshot)
                        commits.add(text to snapshot)
                    }
                    override fun onTextInputEvent(inputId: String, event: ExperienceSemanticTextDraft.Event) {
                        completionEvents.add(event to latestCommit.get())
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
            if (validate) {
                assertTrue("Mount cannot submit", commands.isEmpty())
                fun complete(draft: String, expected: Double?) {
                    instrumentation.runOnMainSync { field.requestFocus(); field.setText(draft); field.clearFocus() }
                    checkNotNull(commits.poll(10, TimeUnit.SECONDS)) { "Expected draft notification before validation" }
                    if (expected == null) {
                        assertNull("Invalid draft must not emit a response or continuation", commands.poll(1, TimeUnit.SECONDS))
                    } else {
                        val response = checkNotNull(commands.poll(10, TimeUnit.SECONDS)) { "Expected validated response" }
                        assertEquals("\$response_set", response.name)
                        val fields = (response.value as NuxieHostValue.Object).fields.associate { it.key to it.value }
                        assertEquals(NuxieHostValue.String("durationSeconds"), fields["field"])
                        assertEquals(NuxieHostValue.Number(expected), fields["value"])
                        assertEquals("duration_ready", checkNotNull(commands.poll(10, TimeUnit.SECONDS)).name)
                    }
                    failure.get()?.let { throw AssertionError("Published validation failed", it) }
                }
                complete("2:00", 120.0)
                complete("invalid", null)
                complete("", null)
                complete("1:60", null)
                complete("0:45", 45.0)
                complete("00:45", 45.0)
                return
            }
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
            fun blurAfterEdit(draft: String, seconds: Double) {
                // Deliberately do not wait for conversion between editing and blur.
                instrumentation.runOnMainSync {
                    field.requestFocus()
                    field.setText(draft)
                    field.clearFocus()
                }
                val (event, precedingCommit) = checkNotNull(completionEvents.poll(10, TimeUnit.SECONDS)) {
                    "Expected input completion after the pending edit"
                }
                failure.get()?.let { throw AssertionError("Published native completion failed", it) }
                assertEquals(ExperienceSemanticTextDraft.EventKind.EDITING_ENDED, event.kind)
                assertEquals(draft, event.text)
                val committed = checkNotNull(precedingCommit)
                assertEquals("Completion must follow the latest value notification", draft, committed.first)
                assertEquals(seconds, input.captureResponse(committed.first, committed.second).double, 0.0)
            }
            blurAfterEdit("3:00", 180.0)
            // Authored validation must receive the invalid draft, not a reformatted
            // previous source. Completion is not permission to submit that source.
            blurAfterEdit("invalid", 180.0)
            blurAfterEdit("0:45", 45.0)
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
