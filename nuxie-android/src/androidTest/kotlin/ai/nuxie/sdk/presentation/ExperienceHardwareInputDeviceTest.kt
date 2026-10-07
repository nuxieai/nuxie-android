package ai.nuxie.sdk.presentation

import ai.nuxie.sdk.runtime.*
import android.content.Intent
import android.os.SystemClock
import android.view.KeyEvent
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Real Activity input, surface queue, runtime and drawing. The test prepares upstream fixtures,
 * drives the frame clock and reads captured fields; release assets and semantic publication
 * are qualified by their own suites.
 */
class ExperienceHardwareInputDeviceTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val assets get() = instrumentation.context.assets
    private val oracle get() = JSONObject(assets.open("runtime/rive-focus/expectations.json").bufferedReader().use { it.readText() })

    @Test fun keysAndTypingReachTheShownSurface() = withScreen("text_input_event", "Artboard", "ViewModel1") { screen ->
        val names = listOf("isFocused", "hasKeyed", "hasTexted")
        fun flags() = names.map { (screen.changedValue(it) as NuxieViewModelValue.Bool).value }
        assertEquals(listOf(false, false, false), flags())
        val actual = mutableListOf<List<Boolean>>()
        screen.advance { screen.key(KeyEvent.KEYCODE_TAB) }
        assertEquals(NuxieFocusState(true, true), screen.host.riveFocusState)
        actual.add(flags())
        screen.advance { screen.key(KeyEvent.KEYCODE_B) }
        actual.add(flags())
        screen.advance { assertTrue(screen.host.receiveFocusInput(NuxieFocusInput.Text("b"))) }
        actual.add(flags())
        val editor = android.widget.EditText(screen.activity).apply { showSoftInputOnFocus = false }
        screen.advance {
            screen.container.addView(editor)
            assertTrue(editor.requestFocus())
            screen.key(KeyEvent.KEYCODE_A)
            screen.key(KeyEvent.KEYCODE_A, down = false)
        }
        assertEquals(listOf(true, false, true), flags())
        instrumentation.runOnMainSync { screen.container.removeView(editor); assertTrue(screen.host.requestFocus()) }
        screen.advance { screen.key(KeyEvent.KEYCODE_A) }
        actual.add(flags())
        val checks = oracle.getJSONObject("text").getJSONArray("checks")
        assertEquals((0 until checks.length()).map { row -> (0..2).map { checks.getJSONArray(row).getBoolean(it) } }, actual)
        screen.advance { assertTrue(screen.host.receiveFocusInput(NuxieFocusInput.Clear)) }
        assertFalse(screen.host.riveFocusState.hasFocus)
    }

    @Test fun shownKeyboardMatchesUpstreamCounts() = withScreen("keyboard_listener", "KeyboardInput", "KeyboardInputVM") { screen ->
        screen.assertKeyboardGraph()
        screen.advance { screen.key(KeyEvent.KEYCODE_TAB); screen.key(KeyEvent.KEYCODE_TAB, down = false) }
        val actual = mutableListOf<Int>()
        fun count() = (screen.changedValue("keyCount") as NuxieViewModelValue.Number).value.toInt()
        fun step(code: Int, down: Boolean = true, repeat: Int = 0, modifiers: Int = 0) {
            screen.advance { screen.key(code, down, repeat, modifiers) }
            actual.add(count())
        }
        step(KeyEvent.KEYCODE_A)
        step(KeyEvent.KEYCODE_A, repeat = 1)
        step(KeyEvent.KEYCODE_A, down = false)
        step(KeyEvent.KEYCODE_A, modifiers = KeyEvent.META_SHIFT_ON)
        // The upstream checks these two counts before advancing.
        actual.add(count())
        screen.advance {
            screen.key(KeyEvent.KEYCODE_E, down = false)
            screen.key(KeyEvent.KEYCODE_E, repeat = 1)
            screen.key(KeyEvent.KEYCODE_E)
        }
        actual.add(count())
        screen.advance { screen.key(KeyEvent.KEYCODE_B) }
        step(KeyEvent.KEYCODE_B, down = false)
        // A platform repeat belongs to a press. B down does not increment this fixture.
        screen.advance { screen.key(KeyEvent.KEYCODE_B); screen.key(KeyEvent.KEYCODE_B, repeat = 1) }
        actual.add(count())
        step(KeyEvent.KEYCODE_D)
        step(KeyEvent.KEYCODE_D, modifiers = KeyEvent.META_SHIFT_ON or KeyEvent.META_META_ON)
        step(KeyEvent.KEYCODE_C, modifiers = KeyEvent.META_SHIFT_ON or KeyEvent.META_META_ON)
        step(KeyEvent.KEYCODE_C, modifiers = KeyEvent.META_SHIFT_ON)
        step(KeyEvent.KEYCODE_X, modifiers = KeyEvent.META_SHIFT_ON)
        val expected = oracle.getJSONObject("keyboard").getJSONArray("checks")
        assertEquals((0 until expected.length()).map(expected::getInt), actual)
    }

    @Test fun typingTransportExperiment() {
        val nativeResult = JSONObject()
        val nativeActivity = instrumentation.startActivitySync(Intent(instrumentation.targetContext,
            SurfaceCompatibilityHostActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        try {
            instrumentation.runOnMainSync {
                val editor = android.widget.EditText(nativeActivity)
                editor.showSoftInputOnFocus = false
                nativeActivity.setContentView(editor)
                assertTrue(editor.requestFocus())
                val connection = checkNotNull(editor.onCreateInputConnection(android.view.inputmethod.EditorInfo()))
                assertTrue(connection.setComposingText("に", 1))
                assertTrue(connection.setComposingText("日本", 1))
                assertTrue(connection.finishComposingText())
                assertEquals("日本", editor.text.toString())
                nativeResult.put("composition", editor.text.toString())
                editor.setText("")
                assertTrue(connection.commitText("teh", 1))
                assertTrue(connection.setSelection(0, 3))
                assertTrue(connection.commitText("the", 1))
                assertEquals("the", editor.text.toString())
                nativeResult.put("wordReplacement", editor.text.toString())
                editor.inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
                editor.setText("private-test")
                nativeResult.put("secureObscured", !editor.transformationMethod.getTransformation(editor.text, editor).toString().contains("private-test"))
                assertTrue(nativeResult.getBoolean("secureObscured"))
            }
        } finally { instrumentation.runOnMainSync { nativeActivity.finish() } }
        val riveResult = JSONObject()
        withScreen("text_input_observed", "Text Input - Multiline", null,
            directory = "text-editing-experiment", semantics = true) { screen ->
            fun apply(vararg inputs: NuxieFocusInput): ObservedField {
                screen.advance { inputs.forEach { assertTrue(screen.host.receiveFocusInput(it)) } }
                return screen.field()
            }
            val clear = arrayOf(NuxieFocusInput.Key(65, 8, true, false), NuxieFocusInput.Key(259, 0, true, false))
            apply(NuxieFocusInput.Next)
            assertEquals("", apply(*clear).text)
            val initialComposition = apply(NuxieFocusInput.Text("に")).text
            val composition = apply(NuxieFocusInput.Text("日本")).text
            assertTrue(composition.isNotEmpty())
            apply(*clear)
            val initialWord = apply(NuxieFocusInput.Text("teh")).text
            val correction = apply(NuxieFocusInput.Text("the")).text
            assertTrue(correction.isNotEmpty())
            assertEquals(listOf("に" to true, "日本" to true, "teh" to true, "the" to true), screen.native.textInputs)
            riveResult.put("initialComposition", initialComposition).put("composition", composition)
                .put("initialWord", initialWord).put("wordReplacement", correction)
        }
        withScreen("text_input_secure_observed", "Text Input - Multiline", null,
            directory = "text-editing-experiment", semantics = true) { screen ->
            fun apply(expected: String, vararg inputs: NuxieFocusInput) {
                screen.native.expectedSecureText = expected
                screen.advance { inputs.forEach { assertTrue(screen.host.receiveFocusInput(it)) } }
                assertTrue("Secure field must retain the expected text", screen.native.secureFieldMatches)
            }
            val clear = arrayOf(NuxieFocusInput.Key(65, 8, true, false), NuxieFocusInput.Key(259, 0, true, false))
            screen.advance { assertTrue(screen.host.receiveFocusInput(NuxieFocusInput.Next)) }
            apply("", *clear)
            apply("private-test", NuxieFocusInput.Text("private-test"))
            assertTrue("Secure field must be obscured", screen.native.secureFieldObscured)
            assertTrue("Secure semantic values must be absent", screen.native.secureSemanticsEmpty)
            val first = screen.pixels()
            apply("hidden-value", *clear, NuxieFocusInput.Text("hidden-value"))
            val sameLength = screen.pixels()
            apply("tiny", *clear, NuxieFocusInput.Text("tiny"))
            val shorter = screen.pixels()
            val sameLengthPixelsMatch = first.contentEquals(sameLength)
            val shorterPixelsDiffer = !first.contentEquals(shorter)
            assertTrue("Secure drawing conceals which same-length text was typed", sameLengthPixelsMatch)
            assertTrue("Typing must change the secure drawing", shorterPixelsDiffer)
            assertTrue("All secure insertions must be accepted", screen.native.secureInputMatches == listOf(true, true, true))
            riveResult.put("secureObscured", screen.native.secureFieldObscured)
                .put("sameLengthPixelsMatch", sameLengthPixelsMatch).put("shorterPixelsDiffer", shorterPixelsDiffer)
        }
        println("TYPING_EXPERIMENT " + JSONObject().put("native", nativeResult).put("rive", riveResult))
    }

    private inner class Screen(val activity: android.app.Activity, val container: ExperienceInputContainer,
        val host: ExperienceSurfaceHost, val lane: NuxieRuntimeLane, val native: ObservedNative,
        val failure: AtomicReference<Throwable?>,
        val changes: java.util.concurrent.ConcurrentLinkedQueue<NuxieViewModelChange>,
        val compositions: java.util.concurrent.atomic.AtomicInteger) {
        private val initial = if (native.root == 0L) null else snapshot()
        private val properties = initial?.values.orEmpty().filter { it.ownerInstanceId == initial?.rootInstanceId }
            .associate { it.name to it.propertyIndex.toInt() }
        private val kept = initial?.values.orEmpty().filter { it.ownerInstanceId == initial?.rootInstanceId }.associate {
            it.propertyIndex.toInt() to when (it.name) {
                "keyCount" -> NuxieViewModelValue.Number(it.numberValue)
                else -> NuxieViewModelValue.Bool(it.boolValue)
            }
        }.toMutableMap()
        fun changedValue(name: String): NuxieViewModelValue {
            while (true) {
                val change = changes.poll() ?: break
                if (change.ownerInstanceId.toLong() == initial?.rootInstanceId) kept[change.propertyIndex] = change.value
            }
            return kept.getValue(properties.getValue(name))
        }
        private var time = 1_000_000_000L
        fun key(code: Int, down: Boolean = true, repeat: Int = 0, modifiers: Int = 0) {
            activity.dispatchKeyEvent(KeyEvent(1, SystemClock.uptimeMillis(),
                if (down) KeyEvent.ACTION_DOWN else KeyEvent.ACTION_UP, code, repeat, modifiers))
        }
        fun advance(input: () -> Unit = {}) {
            native.disposition = 0
            native.field = null
            native.secureFieldMatches = false
            native.secureFieldObscured = false
            val before = native.steps
            val composedBefore = compositions.get()
            instrumentation.runOnMainSync { input(); host.doFrame(time) }
            time += 16_000_000
            drain(lane)
            val deadline = SystemClock.elapsedRealtime() + 10_000
            while (native.disposition == 4 && SystemClock.elapsedRealtime() < deadline) {
                SystemClock.sleep(5)
                instrumentation.runOnMainSync { host.doFrame(time) }
                drain(lane)
            }
            failure.get()?.let { throw AssertionError("Surface failure", it) }
            assertTrue("Native frame must present: ${native.disposition}", native.disposition in 1..2)
            assertEquals("One advance performs one player step", before + 1, native.steps)
            val compositionDeadline = SystemClock.elapsedRealtime() + 10_000
            while (compositions.get() <= composedBefore && SystemClock.elapsedRealtime() < compositionDeadline) {
                SystemClock.sleep(5)
            }
            assertTrue("The advanced frame must compose", compositions.get() > composedBefore)
            drain(lane)
        }
        fun field(): ObservedField = checkNotNull(native.field) { "The latest frame must capture its text field" }
        fun pixels(): IntArray {
            var bitmap: android.graphics.Bitmap? = null
            instrumentation.runOnMainSync { bitmap = host.bitmap }
            val image = checkNotNull(bitmap)
            return IntArray(image.width * image.height).also {
                image.getPixels(it, 0, image.width, 0, 0, image.width, image.height)
                image.recycle()
            }
        }
        fun snapshot(): NativeViewModelSnapshot {
            val value = AtomicReference<NativeViewModelSnapshot>()
            assertTrue(lane.enqueue { value.set(checkNotNull(JniNuxieTypedRuntimeNative.snapshotViewModel(native.root).value)) })
            drain(lane)
            return checkNotNull(value.get())
        }
        fun value(name: String): NativeViewModelSnapshotValue = snapshot().let { snapshot ->
            snapshot.values.single { it.ownerInstanceId == snapshot.rootInstanceId && it.name == name }
        }
        fun assertKeyboardGraph() {
            val snapshot = snapshot()
            fun value(owner: Long, name: String) = snapshot.values.single { it.ownerInstanceId == owner && it.name == name }
            val root = snapshot.rootInstanceId
            assertEquals("Initial value", value(root, "rootKey").bytesValue.decodeToString())
            assertEquals("Initial value", value(root, "input").bytesValue.decodeToString())
            assertEquals(0f, value(root, "keyCount").numberValue, 0f)
            val children = value(root, "children").listItemIds.toList()
            assertEquals(5, children.toSet().size)
            assertEquals((children + root).toSet(), snapshot.instances.map { it.id }.toSet())
            assertEquals(listOf("e", "d", "c", "b", "a"), children.map { value(it, "key").bytesValue.decodeToString() })
            children.forEach { assertFalse(value(it, "isFocused").boolValue); assertFalse(value(it, "isFocused2").boolValue) }
        }
    }

    private fun withScreen(fixture: String, artboard: String, model: String?,
        directory: String = "rive-focus", semantics: Boolean = false, block: (Screen) -> Unit) {
        assertTrue("Native runtime must load", NuxieRuntime.shared.isAvailable)
        val activity = instrumentation.startActivitySync(Intent(instrumentation.targetContext,
            SurfaceCompatibilityHostActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        val lane = NuxieRuntimeLane()
        val native = ObservedNative(model != null, semantics, fixture == "text_input_secure_observed")
        val failure = AtomicReference<Throwable?>()
        val firstFrame = CountDownLatch(1)
        val changes = java.util.concurrent.ConcurrentLinkedQueue<NuxieViewModelChange>()
        val compositions = java.util.concurrent.atomic.AtomicInteger()
        var host: ExperienceSurfaceHost? = null
        var container: ExperienceInputContainer? = null
        try {
            val bytes = assets.open("runtime/$directory/$fixture.riv").use { it.readBytes() }
            instrumentation.runOnMainSync {
                host = ExperienceSurfaceHost(activity, lane, runtime = NuxieRuntime(native), usesSystemFrameCallbacks = false,
                    listener = object : ExperienceSurfaceHost.Listener {
                        override fun onFirstFrame() { firstFrame.countDown() }
                        override fun onRuntimeStep(outcome: NuxiePlayerStepOutcome, correlationId: ULong,
                            viewModelSnapshot: NuxieViewModelSnapshot?) { changes.addAll(outcome.viewModelChanges) }
                        override fun onFailure(error: ExperiencePresentationException) { failure.set(error) }
                    })
                checkNotNull(host).loadArtboard(bytes, artboard)
                val surface = checkNotNull(host)
                surface.isFocusableInTouchMode = true
                surface.surfaceTextureListener = object : android.view.TextureView.SurfaceTextureListener {
                    override fun onSurfaceTextureAvailable(texture: android.graphics.SurfaceTexture, width: Int, height: Int) =
                        surface.onSurfaceTextureAvailable(texture, width, height)
                    override fun onSurfaceTextureSizeChanged(texture: android.graphics.SurfaceTexture, width: Int, height: Int) =
                        surface.onSurfaceTextureSizeChanged(texture, width, height)
                    override fun onSurfaceTextureDestroyed(texture: android.graphics.SurfaceTexture) = surface.onSurfaceTextureDestroyed(texture)
                    override fun onSurfaceTextureUpdated(texture: android.graphics.SurfaceTexture) {
                        surface.onSurfaceTextureUpdated(texture)
                        compositions.incrementAndGet()
                    }
                }
                container = ExperienceInputContainer(activity, surface::dispatchExperienceKeyEvent, surface::semanticKeyboardEntry)
                checkNotNull(container).addView(surface)
                activity.setContentView(checkNotNull(container))
                assertTrue(surface.requestFocus())
            }
            val deadline = SystemClock.elapsedRealtime() + 10_000
            while ((model != null && native.root == 0L) || native.window == 0L) {
                failure.get()?.let { throw AssertionError("Surface setup", it) }
                assertTrue("Real window and default model must load", SystemClock.elapsedRealtime() < deadline)
                SystemClock.sleep(10)
            }
            drain(lane)
            val screen = Screen(activity, checkNotNull(container), checkNotNull(host), lane, native, failure, changes, compositions)
            screen.advance()
            instrumentation.runOnMainSync { if (firstFrame.count > 0) checkNotNull(host).doFrame(1_000_000_000L) }
            assertTrue("The real TextureView composes its first frame", firstFrame.await(10, TimeUnit.SECONDS))
            drain(lane)
            block(screen)
        } finally {
            instrumentation.runOnMainSync { host?.release(); activity.finish() }
            lane.shutdown()
            assertTrue(lane.awaitQuiescence(10_000))
        }
    }

    private fun drain(lane: NuxieRuntimeLane) {
        val done = CountDownLatch(1)
        assertTrue(lane.enqueue { done.countDown() })
        assertTrue("Runtime lane drains", done.await(10, TimeUnit.SECONDS))
    }

    private data class ObservedField(val node: NativeSemanticNode, val text: String)

    private class ObservedNative(private val bindModel: Boolean, private val semantics: Boolean, private val secure: Boolean) :
        NuxieTypedRuntimeNative by JniNuxieTypedRuntimeNative {
        // Upstream fixtures carry embedded fonts, not a signed release asset table.
        override fun newFile(rendererHandle: Long, bytes: ByteArray, expectedAssets: List<ExpectedFileAsset>,
            externalAssets: Map<Int, ByteArray>, imageDecoder: NuxImageDecoder, videoEnabled: Boolean): Long =
            JniNuxieTypedRuntimeNative.newFile(rendererHandle, bytes,
                checkNotNull(JniNuxieTypedRuntimeNative.inspectFileAssets(bytes)), externalAssets, imageDecoder, videoEnabled)
        override fun newNamedArtboard(fileHandle: Long, name: String): Long =
            JniNuxieTypedRuntimeNative.newNamedArtboard(fileHandle, name).also { artboard ->
                if (bindModel) {
                    root = checkNotNull(JniNuxieTypedRuntimeNative.newDefaultViewModel(artboard).value)
                    check(JniNuxieTypedRuntimeNative.bindViewModel(artboard, root) == 0)
                }
            }
        override fun freeArtboard(handle: Long) {
            if (root != 0L) { check(JniNuxieTypedRuntimeNative.freeViewModel(root) == 0); root = 0L }
            JniNuxieTypedRuntimeNative.freeArtboard(handle)
        }
        private fun observePresented(playerHandle: Long, result: Int) {
            disposition = result
            if (semantics && result in 1..2) {
                NuxieSemanticSnapshot.capture(playerHandle, this).close()
            }
        }
        @Volatile var root = 0L
        @Volatile var window = 0L
        @Volatile var steps = 0
        @Volatile var disposition = 0
        @Volatile var field: ObservedField? = null
        private var player = 0L
        @Volatile var expectedSecureText = ""
        @Volatile var secureFieldMatches = false
        @Volatile var secureFieldObscured = false
        @Volatile var secureSemanticsEmpty = true
        val secureInputMatches = mutableListOf<Boolean>()
        val textInputs = mutableListOf<Pair<String, Boolean>>()
        override fun semanticNode(snapshot: Long, index: Int) = JniNuxieTypedRuntimeNative.semanticNode(snapshot, index).also {
            if (secure) it.value?.let { node ->
                secureSemanticsEmpty = secureSemanticsEmpty &&
                    (node.stateFlags and NativeSemanticState.OBSCURED == 0 || node.value.isEmpty()) &&
                    (expectedSecureText.isEmpty() || !node.toString().contains(expectedSecureText))
            }
            it.value?.takeIf { node -> node.role == NativeSemanticRole.TEXT_FIELD }?.let { node ->
                val text = checkNotNull(JniNuxieTypedRuntimeNative.fieldStringCopy(
                    player, snapshot, node.id, "experiment-input").value).decodeToString()
                if (secure) {
                    secureFieldMatches = text == expectedSecureText
                    secureFieldObscured = node.stateFlags and NativeSemanticState.OBSCURED != 0
                } else field = ObservedField(node, text)
            }
        }
        override fun newDefaultViewModel(artboardHandle: Long) =
            JniNuxieTypedRuntimeNative.newDefaultViewModel(artboardHandle).also { root = it.value ?: 0L }
        override fun acquireWindow(surface: android.view.Surface) =
            JniNuxieTypedRuntimeNative.acquireWindow(surface).also { window = it }
        override fun stepPlayer(playerHandle: Long, inputs: List<NativePlayerInput>, pointers: List<NativePlayerPointer>,
            elapsedSeconds: Float, correlationId: Long, textRunNames: List<String>, focusInputs: List<NativeFocusInput>) =
            run {
                if (semantics) check(JniNuxieTypedRuntimeNative.enableSemantics(playerHandle) == 0)
                JniNuxieTypedRuntimeNative.stepPlayer(playerHandle, inputs, pointers, elapsedSeconds, correlationId,
                    textRunNames, focusInputs)
            }.also { result ->
                    player = playerHandle
                    steps++
                    focusInputs.forEachIndexed { index, input ->
                        if (input.kind == 4) {
                            val accepted = result.value?.focusResults?.getOrNull(index) == true
                            if (secure) secureInputMatches.add(input.text.decodeToString() == expectedSecureText && accepted)
                            else textInputs.add(input.text.decodeToString() to accepted)
                        }
                    }
                }
        override fun renderAndPresent(rendererHandle: Long, playerHandle: Long, windowHandle: Long,
            clearColor: Int, layoutScaleFactor: Float) = JniNuxieTypedRuntimeNative.renderAndPresent(
                rendererHandle, playerHandle, windowHandle, clearColor, layoutScaleFactor).also { observePresented(playerHandle, it) }
        override fun copyPlayerToWindow(rendererHandle: Long, playerHandle: Long, windowHandle: Long,
            clearColor: Int, layoutScaleFactor: Float) = JniNuxieTypedRuntimeNative.copyPlayerToWindow(
                rendererHandle, playerHandle, windowHandle, clearColor, layoutScaleFactor).also { observePresented(playerHandle, it) }
    }
}
