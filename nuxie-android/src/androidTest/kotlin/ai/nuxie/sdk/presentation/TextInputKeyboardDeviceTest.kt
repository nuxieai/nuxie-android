package ai.nuxie.sdk.presentation

import ai.nuxie.sdk.runtime.NativeViewModelSnapshot
import ai.nuxie.sdk.runtime.NativeViewModelSnapshotInstance
import ai.nuxie.sdk.runtime.NativeViewModelSnapshotValue
import ai.nuxie.sdk.runtime.NuxieRuntime
import ai.nuxie.sdk.runtime.NuxieViewModelPropertyKind
import ai.nuxie.sdk.runtime.NuxieViewModelSnapshot
import android.app.Activity
import android.app.Instrumentation
import android.content.Context
import android.content.Intent
import android.graphics.Rect
import android.os.SystemClock
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.*
import org.junit.Test

@SdkSuppress(minSdkVersion = 30)
class TextInputKeyboardDeviceTest {
    @Test
    fun keyboardAvoidanceMovesTheLiveSurfaceAndEditorTogetherWithoutDrift() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        if (android.os.Build.VERSION.SDK_INT < 34) {
            verifyDockedKeyboardAvoidance(instrumentation)
            return
        }
        // This case requires a docked IME. Gboard handwriting can be visible
        // with a zero-height inset, so it cannot exercise occlusion avoidance.
        val key = "stylus_handwriting_enabled"
        val resolver = instrumentation.targetContext.contentResolver
        val original = android.provider.Settings.Secure.getString(resolver, key)
        val automation = instrumentation.uiAutomation
        fun setHandwriting(value: String?) {
            automation.adoptShellPermissionIdentity(android.Manifest.permission.WRITE_SECURE_SETTINGS)
            try {
                assertTrue(android.provider.Settings.Secure.putString(resolver, key, value))
                assertEquals(value, android.provider.Settings.Secure.getString(resolver, key))
            } finally { automation.dropShellPermissionIdentity() }
        }
        try {
            setHandwriting("0")
            verifyDockedKeyboardAvoidance(instrumentation)
        } finally {
            setHandwriting(original)
        }
    }

    private fun verifyDockedKeyboardAvoidance(instrumentation: Instrumentation) {
        val context = instrumentation.targetContext
        assertTrue(NuxieRuntime.shared.isAvailable)
        val file = File(context.cacheDir, "keyboard-${UUID.randomUUID()}.riv")
        instrumentation.context.assets.open("data_binding_test.riv").use { input ->
            file.outputStream().use(input::copyTo)
        }
        val monitor = Instrumentation.ActivityMonitor(NuxieExperienceActivity::class.java.name, null, false)
        instrumentation.addMonitor(monitor)
        val id = UUID.randomUUID().toString()
        val rendered = CountDownLatch(1)
        val failure = AtomicReference<Throwable>()
        PresentationRegistry.register(id,
            PreparedPresentation(file, null, 0xffdddddd.toInt(), PresentationShell.FullScreen),
            onFirstFrame = {
                kotlinx.coroutines.runBlocking { assertTrue(PresentationRegistry.reveal(id)) }
                rendered.countDown()
            }, onFailure = { failure.set(it); rendered.countDown() },
            onDismissed = {}, onOutcome = {})
        var activity: Activity? = null
        var overlay: ExperienceTextInputOverlay? = null
        try {
            context.startActivity(Intent(context, NuxieExperienceActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra(NuxieExperienceActivity.EXTRA_PRESENTATION_ID, id))
            activity = checkNotNull(monitor.waitForActivityWithTimeout(15_000))
            assertTrue("Renderer did not present", rendered.await(20, TimeUnit.SECONDS))
            failure.get()?.let { throw AssertionError("Render setup failed", it) }
            val owner = activity
            lateinit var editor: EditText
            lateinit var host: ExperienceSurfaceHost
            lateinit var content: FrameLayout
            val snapshot = geometry()
            instrumentation.runOnMainSync {
                host = checkNotNull(findHost(owner.window.decorView))
                // Use the mounted screen's common parent so the fixture exercises
                // keyboard avoidance without replacing the Activity's owned tree.
                content = host.parent as FrameLayout
                val input = ExperienceTextInput("name", "headline", "", "answer", "Keyboard target", null,
                    false, false, null,
                    listOf("x", "y", "width", "height", "rotation", "scaleX", "scaleY")
                        .associate { "${it}Path" to it },
                    ExperienceTextInput.Style("sans-serif", "400", false, 24f, 30f, 0f,
                        0xff000000.toInt(), "font", null))
                // Geometry is an independent fixture. The live SurfaceView still
                // uses its real renderer and participates in the same transform.
                overlay = ExperienceTextInputOverlay(owner, ExperienceArtboardSize(400f, 1_000f),
                    listOf(input), emptyMap(), { _, _, _, done -> done(Result.success(Unit)) },
                    { throw AssertionError(it) })
                content.addView(overlay, FrameLayout.LayoutParams(-1, -1))
                overlay!!.update(snapshot)
                editor = overlay!!.getChildAt(0) as EditText
            }
            awaitUi(instrumentation, "Editor did not receive its authored layout") { editor.isShown && editor.height > 1 }
            var originalHostTop = 0
            var originalEditorTop = 0
            instrumentation.runOnMainSync {
                originalHostTop = screenTop(host)
                originalEditorTop = screenTop(editor)
                assertTrue(editor.requestFocus())
                val ime = owner.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                assertTrue(ime.showSoftInput(editor, InputMethodManager.SHOW_IMPLICIT))
            }
            // IME visibility may become true before its animated inset is nonzero.
            // Establish real occlusion independently of the SDK's avoidance shift.
            awaitUi(instrumentation, "Docked keyboard did not occlude the original editor position", diagnosis = {
                "originalEditorTop=$originalEditorTop, editorHeight=${editor.height}, " +
                    "rootHeight=${editor.rootView.height}, ime=${editor.rootWindowInsets?.getInsets(WindowInsets.Type.ime())}, " +
                    "visible=${editor.rootWindowInsets?.isVisible(WindowInsets.Type.ime())}, focused=${editor.hasFocus()}"
            }) {
                val root = editor.rootView
                val insets = root.rootWindowInsets ?: return@awaitUi false
                val keyboardTop = screenTop(root) + root.height - insets.getInsets(WindowInsets.Type.ime()).bottom
                insets.isVisible(WindowInsets.Type.ime()) &&
                    originalEditorTop + editor.height > keyboardTop
            }
            awaitUi(instrumentation, "Focused editor remained under the keyboard", diagnosis = {
                "shift=${content.translationY}, editorTop=${screenTop(editor)}, editorHeight=${editor.height}, " +
                    "rootTop=${screenTop(editor.rootView)}, rootHeight=${editor.rootView.height}, " +
                    "ime=${editor.rootWindowInsets?.getInsets(WindowInsets.Type.ime())}, focused=${editor.hasFocus()}"
            }) {
                val root = editor.rootView
                val insets = root.rootWindowInsets ?: return@awaitUi false
                val keyboardTop = screenTop(root) + root.height - insets.getInsets(WindowInsets.Type.ime()).bottom
                screenTop(editor) + editor.height <= keyboardTop
            }
            instrumentation.runOnMainSync {
                val shift = content.translationY
                assertTrue("The low editor must require a shift: shift=$shift, " +
                    "originalHost=$originalHostTop, originalEditor=$originalEditorTop, " +
                    "hostTop=${screenTop(host)}, editorTop=${screenTop(editor)}, editorHeight=${editor.height}, " +
                    "rootTop=${screenTop(editor.rootView)}, rootHeight=${editor.rootView.height}, " +
                    "contentHeight=${content.height}, overlayHeight=${overlay!!.height}, " +
                    "ime=${editor.rootWindowInsets?.getInsets(WindowInsets.Type.ime())}, " +
                    "visible=${editor.rootWindowInsets?.isVisible(WindowInsets.Type.ime())}, focused=${editor.hasFocus()}", shift < 0f)
                assertEquals(originalHostTop + shift, screenTop(host).toFloat(), 2f)
                assertEquals(originalEditorTop + shift, screenTop(editor).toFloat(), 2f)
                repeat(10) { overlay!!.update(snapshot) }
                assertEquals("Repeated geometry updates must not accumulate a keyboard shift", shift, content.translationY, 2f)
                val rotated = geometry(rotation = (Math.PI / 2).toFloat(), y = 700f)
                overlay!!.update(rotated)
                val rotatedBounds = Rect()
                assertTrue(editor.getGlobalVisibleRect(rotatedBounds))
                assertEquals("Rotated field must not be clipped", editor.width.toFloat(), rotatedBounds.height().toFloat(), 2f)
                val root = editor.rootView
                val keyboardTop = screenTop(root) + root.height - root.rootWindowInsets.getInsets(WindowInsets.Type.ime()).bottom
                assertTrue("Rotated field remained under keyboard", rotatedBounds.bottom <= keyboardTop)
                val rotatedShift = content.translationY
                repeat(10) { overlay!!.update(rotated) }
                assertEquals(rotatedShift, content.translationY, 2f)
                val ime = owner.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                ime.hideSoftInputFromWindow(editor.windowToken, 0)
            }
            awaitUi(instrumentation, "Keyboard shift did not reset") { content.translationY == 0f }
            failure.get()?.let { throw AssertionError("Renderer failed during keyboard movement", it) }
        } finally {
            instrumentation.runOnMainSync { overlay?.close(); activity?.finish() }
            instrumentation.removeMonitor(monitor)
            PresentationRegistry.clearForTesting()
            file.delete()
        }
    }

    private fun findHost(view: android.view.View): ExperienceSurfaceHost? {
        if (view is ExperienceSurfaceHost) return view
        if (view is ViewGroup) for (index in 0 until view.childCount) {
            findHost(view.getChildAt(index))?.let { return it }
        }
        return null
    }

    private fun screenTop(view: android.view.View): Int = IntArray(2).also(view::getLocationOnScreen)[1]

    private fun awaitUi(instrumentation: Instrumentation, message: String,
        diagnosis: () -> String = { "" }, condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 10_000
        while (SystemClock.elapsedRealtime() < deadline) {
            var ready = false
            instrumentation.runOnMainSync { ready = condition() }
            if (ready) return
            SystemClock.sleep(100)
        }
        var details = ""
        instrumentation.runOnMainSync { details = diagnosis() }
        fail("$message $details")
    }

    private fun geometry(rotation: Float = 0f, y: Float = 900f): NuxieViewModelSnapshot = NuxieViewModelSnapshot.fromNative(
        NativeViewModelSnapshot(1, arrayOf(NativeViewModelSnapshotInstance(1, 0)),
            mapOf("x" to 10f, "y" to y, "width" to 200f, "height" to 60f,
                "rotation" to rotation, "scaleX" to 1f, "scaleY" to 1f).map { (name, value) ->
                NativeViewModelSnapshotValue(1, 0, name, NuxieViewModelPropertyKind.NUMBER.nativeValue,
                    byteArrayOf(), 0, value)
            }.toTypedArray()),
    )
}
