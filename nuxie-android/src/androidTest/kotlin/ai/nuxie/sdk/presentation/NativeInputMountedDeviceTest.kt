package ai.nuxie.sdk.presentation

import ai.nuxie.sdk.runtime.NuxieViewModelSnapshot
import android.content.Intent
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.*
import org.junit.Test

/** Mounted native input qualification; deliberately does not bypass signed release admission. */
class NativeInputMountedDeviceTest {
    @Test fun mountedNativeEditReachesCapturedOwnerAndSubsequentEditsRemainUsable() {
        assertTrue(ai.nuxie.sdk.runtime.NuxieRuntime.shared.isAvailable)
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val scene = File.createTempFile("native-owner-", ".riv", instrumentation.targetContext.cacheDir)
        instrumentation.context.assets.open("native_input_mounted.riv").use { source ->
            scene.outputStream().use { source.copyTo(it) }
        }
        val font = File.createTempFile("native-font-", ".ttf", instrumentation.targetContext.cacheDir)
        instrumentation.context.assets.open("native_input_font.ttf").use { source ->
            font.outputStream().use { source.copyTo(it) }
        }
        val fontAsset = checkNotNull(ai.nuxie.sdk.runtime.NuxieRuntime.shared.inspectFileAssets(scene.readBytes())).single()
        // Geometry comes from the native occurrence, not these unused legacy paths.
        val descriptor = Json.parseToJsonElement("""{
          "leg":{"screens":[{"id":"screen","defaultViewModelName":"State"}]},
          "render":{"assets":[{"kind":"font","authoredAssetId":${fontAsset.authoredId},
            "assetUniqueName":"${fontAsset.name}-${fontAsset.authoredId}","key":"font","required":true}],
            "screens":[{"id":"screen","artboardName":"Form"}],"textInputs":[{
            "id":"name","screenId":"screen","textRunName":"unused","editableValueName":"editable",
            "value":"","editable":true,"secureTextEntry":false,"multiline":false,
            "geometry":{},"style":{"fontFamily":"sans-serif","fontWeight":"400","fontStyle":"normal",
              "fontSize":16,"lineHeight":20,"letterSpacing":0,"color":4278190080,"fontAssetUniqueName":""}
          }]}}
        """).jsonObject
        val prepared = PreparedPresentation(scene, null, 0xffdddddd.toInt(), PresentationShell.FullScreen,
            "screen", descriptor, artifactsByKey = mapOf("font" to font), artboardSize = ExperienceArtboardSize(200f, 80f))
        val activity = instrumentation.startActivitySync(Intent(instrumentation.targetContext,
            SurfaceCompatibilityHostActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        val failure = AtomicReference<Throwable?>()
        val committed = LinkedBlockingQueue<Pair<String, NuxieViewModelSnapshot?>>()
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
                        committed.add(text to snapshot)
                    }
                }, failure::set)
                content = checkNotNull(mounted).mount()
                activity.setContentView(content)
                checkNotNull(mounted).observeWindow()
            }
            var fields = emptyList<EditText>()
            val deadline = SystemClock.elapsedRealtime() + 15_000
            while (fields.isEmpty() && failure.get() == null && SystemClock.elapsedRealtime() < deadline) {
                instrumentation.runOnMainSync { fields = editors(content) }
                SystemClock.sleep(25)
            }
            failure.get()?.let { throw AssertionError("Native mount failed", it) }
            assertFalse("Native occurrence must create an editor", fields.isEmpty())
            assertTrue("Mount must not emit an initial response", committed.isEmpty())
            val field = fields.first()
            instrumentation.runOnMainSync {
                assertTrue("Native editor must have usable geometry and be enabled", field.isEnabled)
                assertEquals(View.VISIBLE, field.visibility)
                assertEquals("Forward converter must trim the source for presentation", "initial", field.text.toString())
            }
            for (text in listOf("  first é  ", "second 🔒")) {
                instrumentation.runOnMainSync { field.requestFocus(); field.setText(text) }
                val event = committed.poll(10, TimeUnit.SECONDS)
                failure.get()?.let { throw AssertionError("Native edit failed", it) }
                assertNotNull("Accepted edit must notify through the mounted surface", event)
                assertEquals(text, event!!.first)
                assertNotNull("Accepted edit must carry its actual owner snapshot", event.second)
                // Native StringTrim transforms forward presentation; its reverse is passthrough.
                assertEquals("Reverse binding must preserve the edited source", text, event.second!!.resolveString("answer"))
                instrumentation.runOnMainSync { assertEquals(text.trim(), field.text.toString().trim()) }
            }
        } finally {
            val closed = CountDownLatch(1)
            instrumentation.runOnMainSync {
                mounted?.close(false) { closed.countDown() } ?: closed.countDown()
                activity.finish()
            }
            assertTrue("Mounted native lane must retire", closed.await(10, TimeUnit.SECONDS))
            scene.delete()
            font.delete()
        }
    }
}
