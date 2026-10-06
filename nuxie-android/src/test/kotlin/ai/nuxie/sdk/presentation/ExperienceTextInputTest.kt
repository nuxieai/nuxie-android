package ai.nuxie.sdk.presentation

import ai.nuxie.sdk.runtime.NuxieTextGeometryCapture
import ai.nuxie.sdk.runtime.NuxieTextRunGeometry
import ai.nuxie.sdk.fixtures.FixtureRunner
import ai.nuxie.sdk.runtime.NativeViewModelSnapshot
import ai.nuxie.sdk.runtime.NativeViewModelSnapshotInstance
import ai.nuxie.sdk.runtime.NativeViewModelSnapshotValue
import ai.nuxie.sdk.runtime.NuxieViewModelPropertyKind
import ai.nuxie.sdk.runtime.NuxieViewModelSnapshot
import android.app.Activity
import android.graphics.Color
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.float
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner

internal fun textInputDescriptor(value: String = ""): JsonObject = Json.parseToJsonElement("""
    {"render":{"textInputs":[{
      "id":"name","screenId":"survey","textRunName":"headline","value":${JsonPrimitive(value)},"editable":true,
      "responseFieldKey":"answer","secureTextEntry":false,"multiline":false,"maxLength":2,
      "geometry":{"xPath":"x","yPath":"y","widthPath":"width","heightPath":"height",
        "rotationPath":"rotation","scaleXPath":"scaleX","scaleYPath":"scaleY"},
      "style":{"fontFamily":"sans-serif","fontWeight":"400","fontStyle":"normal","fontSize":16,
        "lineHeight":20,"letterSpacing":0,"color":4278190080,"fontAssetUniqueName":"font"}
    }]}}
""") as JsonObject

@RunWith(RobolectricTestRunner::class)
class ExperienceTextInputTest {
    @Test fun `unchanged Return executes the declared action each time but blur does not`() = runTest {
        val descriptor = Json.parseToJsonElement(textInputDescriptor().toString()
            .replace("\"textRunName\":", "\"actionEvent\":\"return\",\"declarativeActionId\":\"submit\",\"textRunName\":")) as JsonObject
        val behaviors = Json.parseToJsonElement("""[{"screenId":"survey","controls":[{
          "actionId":"submit","behavior":{"kind":"declarative","program":[{"type":"emit","eventName":"submitted"}]}
        }]}]""")
        val batches = mutableListOf<JourneyScreenEmissionBatch>()
        val coordinator = JourneyRuntimeEmissionCoordinator("journey", "survey",
            JsonObject(descriptor + ("screenBehaviors" to behaviors)), 0, 0,
            onEmissionBatch = { it, _ -> batches += it; true }, onPresentationRevealed = {})
        coordinator.reveal()
        assertTrue(coordinator.publishTextInputEvent("name", ExperienceSemanticTextDraft.Event(
            ExperienceSemanticTextDraft.EventKind.EDITING_ENDED, "same")))
        assertTrue(batches.isEmpty())
        repeat(2) {
            assertTrue(coordinator.publishTextInputEvent("name", ExperienceSemanticTextDraft.Event(
                ExperienceSemanticTextDraft.EventKind.RETURN, "same")))
        }
        assertEquals(2, batches.size)
        assertTrue(batches.all { it.source.actionId == "submit" && it.source.componentId == "name" })
        assertFalse(coordinator.publishTextInputEvent("unknown", ExperienceSemanticTextDraft.Event(
            ExperienceSemanticTextDraft.EventKind.RETURN, "same")))
        coordinator.close()
    }

    @Test fun `native text commits from separate owners publish no answer events`() = runTest {
        val descriptor = Json.parseToJsonElement(textInputDescriptor().toString()
            .replace("\"textRunName\":", "\"editableValueName\":\"editable\",\"textRunName\":")) as JsonObject
        val batches = mutableListOf<JourneyScreenEmissionBatch>()
        val coordinator = JourneyRuntimeEmissionCoordinator("journey", "survey", descriptor, 0, 0,
            onEmissionBatch = { it, _ -> batches += it; true }, onPresentationRevealed = {})
        fun owner(id: Long) = NuxieViewModelSnapshot.fromNative(NativeViewModelSnapshot(id,
            arrayOf(NativeViewModelSnapshotInstance(id, 0)), emptyArray()))
        coordinator.reveal()
        assertTrue(runCatching { coordinator.publishTextCommit("name", "Al") }.isFailure)
        assertTrue(batches.isEmpty())
        assertTrue(coordinator.publishTextCommit("name", "Al", snapshot = owner(10)))
        assertTrue(coordinator.publishTextCommit("name", "Al", snapshot = owner(10)))
        assertTrue(coordinator.publishTextCommit("name", "Al", snapshot = owner(20)))
        assertTrue(batches.isEmpty())
        coordinator.close()
    }

    @Test
    @org.robolectric.annotation.Config(sdk = [35])
    @org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
    fun `System inputs preserve all nine authored weights without a downloaded font`() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        try {
            val original = ExperienceTextInput.forScreen(textInputDescriptor("ok"), "survey").single()
            for (weight in 100..900 step 100) {
                val input = original.copy(style = original.style.copy(fontFamily = "System", fontWeight = "$weight"))
                val overlay = ExperienceTextInputOverlay(controller.get(), ExperienceArtboardSize(200f, 100f),
                    listOf(input), emptyMap(), { _, _, _, done -> done(Result.success(Unit)) }, { throw it })
                val editor = overlay.findViewWithTag<EditText>("nuxie-text-input-name")
                assertEquals(weight, editor.typeface.weight)
                assertFalse(editor.typeface.isItalic)
            }
        } finally {
            controller.pause().stop().destroy()
        }
    }

    @Test fun `text commits do not publish answer events`() = runTest {
        val batches = mutableListOf<JourneyScreenEmissionBatch>()
        val coordinator = JourneyRuntimeEmissionCoordinator("journey", "survey", textInputDescriptor(), 0, 0,
            onEmissionBatch = { it, _ -> batches += it; true }, onPresentationRevealed = {})
        assertTrue(coordinator.reveal())
        assertTrue(coordinator.publishTextCommit("name", "50"))
        assertTrue(batches.isEmpty())
        coordinator.close()
    }

    @Test fun `semantic native focus survives overlay withdrawal and input rollback`() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup().visible()
        val activity = controller.get()
        val input = ExperienceTextInput.forScreen(textInputDescriptor("ok"), "survey").single()
        val overlay = ExperienceTextInputOverlay(activity, ExperienceArtboardSize(200f, 100f),
            listOf(input), emptyMap(), { _, _, _, done -> done(Result.success(Unit)) }, { throw it })
        val host = View(activity)
        val provider = ExperienceAccessibilityProvider(host, { null }, { _, _, _ -> true })
        val node = ai.nuxie.sdk.runtime.NativeSemanticNode(42, -1, 0, 6, 0, 0, 0, 0,
            0f, 0f, 100f, 30f, "Your name", "", "")
        val tree = ai.nuxie.sdk.runtime.NuxieSemanticTree(1, 1, listOf(node))
        try {
            activity.setContentView(ExperienceFocusRoot(activity).apply {
                addView(host)
                addView(overlay)
            })
            host.layout(0, 0, 400, 400)
            overlay.layout(0, 0, 400, 400)
            overlay.update(snapshot(), capturedGeometry())
            val editor = overlay.findViewWithTag<EditText>("nuxie-text-input-name")
            fun present() {
                overlay.updateSemantics(mapOf("name" to node))
                provider.publish(tree, overlay.semanticViews())
            }
            present()
            assertTrue(editor.requestFocus())
            provider.withdraw()
            overlay.updateSemantics(emptyMap())
            assertFalse(editor.hasFocus())
            present()
            assertTrue("A new semantic frame restores the real editor", editor.hasFocus())
            provider.withdraw()
            overlay.setInputEnabled(false)
            assertFalse(editor.hasFocus())
            overlay.setInputEnabled(true)
            present()
            assertTrue("Navigation rollback restores the real editor", editor.hasFocus())
            assertEquals("ok", editor.text.toString())
        } finally {
            provider.retire()
            overlay.close()
            controller.pause().stop().destroy()
        }
    }

    @Test @org.robolectric.annotation.Config(sdk = [23, 26, 30])
    fun `semantic field label preserves real editing and absent capture retires traversal`() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        val input = ExperienceTextInput.forScreen(textInputDescriptor("ok"), "survey").single()
        val overlay = ExperienceTextInputOverlay(controller.get(), ExperienceArtboardSize(200f, 100f),
            listOf(input), emptyMap(), { _, _, _, done -> done(Result.success(Unit)) }, { throw it })
        try {
            val editor = overlay.findViewWithTag<EditText>("nuxie-text-input-name")
            val node = ai.nuxie.sdk.runtime.NativeSemanticNode(42, -1, 0, 6, 0, 0, 0, 0,
                0f, 0f, 100f, 30f, "Your name", "", "Use your full name")
            controller.get().setContentView(overlay)
            overlay.layout(0, 0, 400, 400)
            overlay.update(snapshot(), capturedGeometry())
            overlay.updateSemantics(mapOf("name" to node))
            assertTrue(editor.requestFocus())
            val info = editor.createAccessibilityNodeInfo()
            assertEquals("ok", info.text.toString())
            val hint = if (android.os.Build.VERSION.SDK_INT >= 26) info.hintText else
                info.extras.getCharSequence("androidx.view.accessibility.AccessibilityNodeInfoCompat.HINT_TEXT_KEY")
            assertEquals("Your name, Use your full name", hint.toString())
            assertNull(info.contentDescription)
            assertTrue(info.isEditable)
            assertTrue("actions=${info.actions}, list=${info.actionList.map { it.id }}", info.actions and
                android.view.accessibility.AccessibilityNodeInfo.ACTION_SET_TEXT != 0)
            assertEquals(View.IMPORTANT_FOR_ACCESSIBILITY_YES, editor.importantForAccessibility)
            val replacement = android.os.Bundle().apply {
                putCharSequence(android.view.accessibility.AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "Ad")
            }
            assertTrue(editor.performAccessibilityAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_SET_TEXT, replacement))
            assertEquals("Ad", editor.text.toString())
            replacement.putCharSequence(android.view.accessibility.AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "Ada")
            assertFalse(editor.performAccessibilityAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_SET_TEXT, replacement))
            assertEquals("Ad", editor.text.toString()) // Rejected whole-value edits preserve the valid draft.
            overlay.updateSemantics(emptyMap())
            assertFalse(editor.performAccessibilityAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_SET_TEXT, replacement))
            assertEquals(View.IMPORTANT_FOR_ACCESSIBILITY_NO, editor.importantForAccessibility)
            assertFalse(editor.isEnabled)
            overlay.setInputEnabled(false)
            overlay.setInputEnabled(true)
            assertFalse(editor.isEnabled)
            overlay.updateSemantics(mapOf("name" to node))
            assertTrue(editor.isEnabled)
        } finally { overlay.close(); controller.pause().stop().destroy() }
    }

    @Test @org.robolectric.annotation.Config(sdk = [23, 30])
    fun `retired semantic fields cannot persist late edits and secure nodes retain native password behavior`() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        val input = ExperienceTextInput.forScreen(textInputDescriptor("ok"), "survey").single().copy(secure = true)
        val writes = mutableListOf<String>()
        val state = ExperienceTextInputState()
        val overlay = ExperienceTextInputOverlay(controller.get(), ExperienceArtboardSize(200f, 100f),
            listOf(input), emptyMap(), { _, text, _, done -> writes += text; done(Result.success(Unit)) }, { throw it }, state)
        try {
            val editor = overlay.findViewWithTag<EditText>("nuxie-text-input-name")
            val node = ai.nuxie.sdk.runtime.NativeSemanticNode(42, -1, 0, 6, 4096, 0, 0, 0,
                0f, 0f, 100f, 30f, "Password", "", "")
            overlay.updateSemantics(mapOf("name" to node))
            val info = editor.createAccessibilityNodeInfo()
            assertTrue(info.isPassword)
            assertTrue(info.isEditable)
            assertNull(info.contentDescription)
            overlay.updateSemantics(emptyMap())
            val count = writes.size
            editor.setText("zz")
            assertEquals(count, writes.size)
            overlay.updateSemantics(mapOf("name" to node))
            // Retirement must restore the last admitted draft, not an IME mutation
            // that arrived while the authored field was absent.
            assertEquals("ok", editor.text.toString())
        } finally { overlay.close(); controller.pause().stop().destroy() }
    }

    @Test @org.robolectric.annotation.Config(sdk = [23, 30])
    fun `disabled semantic ancestor fences native edits and reenabling restores admitted draft`() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        val input = ExperienceTextInput.forScreen(textInputDescriptor("ok"), "survey").single()
        val writes = mutableListOf<String>()
        val overlay = ExperienceTextInputOverlay(controller.get(), ExperienceArtboardSize(200f, 100f),
            listOf(input), emptyMap(), { _, text, _, done -> writes += text; done(Result.success(Unit)) }, { throw it })
        try {
            controller.get().setContentView(overlay)
            overlay.layout(0, 0, 400, 400)
            overlay.update(snapshot(), capturedGeometry())
            val editor = overlay.findViewWithTag<EditText>("nuxie-text-input-name")
            val group = ai.nuxie.sdk.runtime.NativeSemanticNode(10, -1, 0, 9, 0, 0, 0, 0,
                0f, 0f, 200f, 100f, "Group", "", "")
            val field = group.copy(id = 42, parentId = 10, role = 6, label = "Name")
            fun publish(disabled: Boolean) {
                val tree = ai.nuxie.sdk.runtime.NuxieSemanticTree(1, 1,
                    listOf(field, group.copy(stateFlags = if (disabled) 64 else 0)))
                overlay.updateSemantics(mapOf("name" to tree.nodes.single { it.id == 42L }))
            }
            publish(false)
            assertTrue(editor.requestFocus())
            val connection = checkNotNull(editor.onCreateInputConnection(EditorInfo()))
            publish(true)
            assertEquals(View.VISIBLE, editor.visibility)
            assertFalse(editor.isEnabled)
            val before = writes.toList()
            connection.commitText("zz", 1)
            val replacement = android.os.Bundle().apply {
                putCharSequence(android.view.accessibility.AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "Ad")
            }
            assertFalse(editor.performAccessibilityAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_SET_TEXT, replacement))
            assertEquals(before, writes)
            overlay.setInputEnabled(false)
            overlay.setInputEnabled(true)
            assertFalse(editor.isEnabled)
            publish(false)
            assertTrue(editor.isEnabled)
            assertEquals("ok", editor.text.toString())
            assertTrue(editor.performAccessibilityAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_SET_TEXT, replacement))
            assertEquals("Ad", writes.last())
        } finally { overlay.close(); controller.pause().stop().destroy() }
    }

    @Test
    fun `native preparation takes the latest draft and fences stale IME callbacks until abort`() {
        val contract = Json.parseToJsonElement(ai.nuxie.sdk.fixtures.FixtureRunner.fixturesRoot()
            .resolve("journeys/planes/navigation-input-handoff-android.json").readText()) as JsonObject
        fun value(key: String) = (contract.getValue(key) as JsonPrimitive).content
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val state = ExperienceTextInputState()
        val input = ExperienceTextInput.forScreen(textInputDescriptor(), "survey").single().copy(maxLength = null)
        val writes = mutableListOf<String>()
        val overlay = ExperienceTextInputOverlay(activity, ExperienceArtboardSize(200f, 100f),
            listOf(input), emptyMap(), { _, text, _, done -> writes += text; done(Result.success(Unit)) },
            { throw it }, state)
        val editor = overlay.findViewWithTag<EditText>("nuxie-text-input-name")
        editor.setText(value("initialDraft"))
        val target = state.copyForPreparation()
        editor.setText(value("latestDraft"))
        editor.setSelection(1, 3)
        overlay.setInputEnabled(false)
        target.refreshBeforeMount()
        val targetSession = target.bind()
        assertEquals(ExperienceTextInputState.Value(value("latestDraft"), 1, 3), targetSession.read("name"))
        val count = writes.size
        editor.setText(value("staleImeDraft"))
        assertEquals(count, writes.size)
        targetSession.write("name", ExperienceTextInputState.Value("provisional", 0, 0))
        // A response accepted while native preparation runs must not be emitted again on arrival.
        state.recordCommit("name", value("acceptedResponse"))
        assertEquals(value("acceptedResponse"), target.committedValue("name"))
        overlay.setInputEnabled(true)
        assertEquals(value("latestDraft"), editor.text.toString())
        assertEquals(1, editor.selectionStart)
        assertEquals(3, editor.selectionEnd)
        editor.setText("Resumed")
        assertEquals("Resumed", writes.last())
        overlay.close()
    }

    @Test
    fun `recreated editors restore draft and selection without accepting stale owners`() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val state = ExperienceTextInputState()
        val input = ExperienceTextInput.forScreen(textInputDescriptor(), "survey").single().copy(maxLength = null)
        val writes = mutableListOf<String>()
        val callbacks = mutableListOf<(Result<Unit>) -> Unit>()
        val failures = mutableListOf<Throwable>()
        fun overlay() = ExperienceTextInputOverlay(activity, ExperienceArtboardSize(200f, 100f),
            listOf(input), emptyMap(), { _, value, _, done -> writes += value; callbacks += done },
            failures::add, state)
        val first = overlay()
        val oldEditor = first.findViewWithTag<EditText>("nuxie-text-input-name")
        oldEditor.setText("Iris")
        oldEditor.setSelection(1, 3)
        val staleCompletion = callbacks.last()
        // Activity attachment may overlap its predecessor's asynchronous teardown.
        val second = overlay()
        val editor = second.findViewWithTag<EditText>("nuxie-text-input-name")
        assertEquals("Iris", editor.text.toString())
        assertEquals(1, editor.selectionStart)
        assertEquals(3, editor.selectionEnd)
        assertFalse(editor.isSaveEnabled)
        val count = writes.size
        oldEditor.setText("stale")
        staleCompletion(Result.failure(IllegalStateException("old runtime closed")))
        assertEquals(count, writes.size)
        assertTrue(failures.isEmpty())
        first.close()
        second.close()
        val third = overlay()
        assertEquals("Iris", (third.findViewWithTag<EditText>("nuxie-text-input-name")).text.toString())
        third.close()
    }

    @Test @org.robolectric.annotation.Config(sdk = [23, 30])
    fun `absent semantic field stays hidden across geometry updates and restores retained value`() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup().visible()
        val input = ExperienceTextInput.forScreen(textInputDescriptor("ok"), "survey").single()
        val writes = mutableListOf<String>()
        val overlay = ExperienceTextInputOverlay(controller.get(), ExperienceArtboardSize(200f, 100f),
            listOf(input), emptyMap(), { _, text, _, done -> writes += text; done(Result.success(Unit)) }, { throw it })
        try {
            controller.get().setContentView(overlay)
            overlay.layout(0, 0, 400, 400)
            overlay.update(snapshot(), capturedGeometry())
            val editor = overlay.findViewWithTag<EditText>("nuxie-text-input-name")
            val node = ai.nuxie.sdk.runtime.NativeSemanticNode(42, -1, 0, 6, 0, 0, 0, 0,
                10f, 20f, 90f, 40f, "Your name", "", "")
            overlay.updateSemantics(mapOf("name" to node))
            assertEquals(View.VISIBLE, editor.visibility)
            assertTrue(editor.requestFocus())
            overlay.updateSemantics(emptyMap())
            val count = writes.size
            assertEquals(View.INVISIBLE, editor.visibility)
            assertFalse(editor.hasFocus())
            overlay.update(snapshot(), capturedGeometry())
            assertEquals(View.INVISIBLE, editor.visibility)
            editor.setText("zz")
            assertEquals(count, writes.size)
            overlay.updateSemantics(mapOf("name" to node))
            assertEquals(View.VISIBLE, editor.visibility)
            assertEquals("ok", editor.text.toString())
            assertFalse(editor.hasFocus())
            overlay.updateSemantics(mapOf("name" to node.copy(stateFlags = 64)))
            assertEquals(View.VISIBLE, editor.visibility)
            assertFalse(editor.isEnabled)
        } finally { overlay.close(); controller.pause().stop().destroy() }
    }

    @Test
    fun `settled affine fields ignore local channels and retain active composition`() {
        val contract = Json.parseToJsonElement(FixtureRunner.fixturesRoot()
            .resolve("journeys/planes/text-input-affine.json").readText()).jsonObject
        val controller = Robolectric.buildActivity(Activity::class.java).setup().visible()
        val input = ExperienceTextInput.forScreen(textInputDescriptor(), "survey").single().copy(maxLength = null)
        var writes = 0
        val overlay = ExperienceTextInputOverlay(controller.get(), ExperienceArtboardSize(200f, 100f),
            listOf(input), emptyMap(), { _, _, _, done -> writes++; done(Result.success(Unit)) }, { throw it })
        try {
            controller.get().setContentView(overlay)
            val spec = View.MeasureSpec.makeMeasureSpec(400, View.MeasureSpec.EXACTLY)
            overlay.measure(spec, spec)
            overlay.layout(0, 0, 400, 400)
            val editor = overlay.findViewWithTag<EditText>("nuxie-text-input-name")
            var composing = false
            for (entry in contract.getValue("cases").jsonArray) {
                val item = entry.jsonObject
                val coefficients = item.getValue("transform").jsonArray.map { it.jsonPrimitive.float }
                val transform = NuxieTextRunGeometry.Transform(coefficients[0], coefficients[1], coefficients[2],
                    coefficients[3], coefficients[4], coefficients[5])
                val field = NuxieTextRunGeometry(1uL, transform, transform,
                    NuxieTextRunGeometry.Bounds(0f, 0f, 10f, 20f),
                    NuxieTextRunGeometry.Layout(transform, NuxieTextRunGeometry.Bounds(0f, 0f, 10f, 20f)), 12f)
                overlay.update(snapshot(width = Float.NaN), NuxieTextGeometryCapture.Captured(mapOf(input.runName to field)))
                overlay.measure(spec, spec)
                overlay.layout(0, 0, 400, 400)
                if (item.getValue("corners") == JsonNull) {
                    assertEquals(View.INVISIBLE, editor.visibility)
                    continue
                }
                assertEquals(View.VISIBLE, editor.visibility)
                val corners = floatArrayOf(0f, 0f, editor.width.toFloat(), 0f,
                    editor.width.toFloat(), editor.height.toFloat(), 0f, editor.height.toFloat())
                editor.matrix.mapPoints(corners)
                (editor.parent as View).matrix.mapPoints(corners)
                item.getValue("corners").jsonArray.forEachIndexed { index, value ->
                    assertEquals(item.getValue("name").jsonPrimitive.content,
                        value.jsonPrimitive.float, corners[index], 0.002f)
                }
                if (!composing) {
                    assertTrue(editor.requestFocus())
                    assertTrue(checkNotNull(editor.onCreateInputConnection(EditorInfo())).setComposingText("draft", 1))
                    composing = true
                }
                val before = writes
                val selection = editor.selectionStart
                overlay.update(snapshot(), NuxieTextGeometryCapture.Captured(mapOf(input.runName to field)))
                assertEquals("draft", editor.text.toString())
                assertEquals(selection, editor.selectionStart)
                assertTrue(android.view.inputmethod.BaseInputConnection.getComposingSpanStart(editor.text) >= 0)
                assertEquals("Placement cannot emit text transactions", before, writes)
            }
            overlay.update(snapshot(), NuxieTextGeometryCapture.Failed(3))
            assertEquals("Failure cannot restore local-channel placement", View.INVISIBLE, editor.visibility)
            assertFalse(editor.isEnabled)
            val beforeLateIme = writes
            editor.setText("late IME write")
            assertEquals("Missing geometry must fence writes", beforeLateIme, writes)
            overlay.update(snapshot(), capturedGeometry())
            assertEquals(View.VISIBLE, editor.visibility)
            assertTrue(editor.isEnabled)
            assertEquals("draft", editor.text.toString())
            assertEquals("Restoring geometry cannot emit text transactions", beforeLateIme, writes)
        } finally { overlay.close(); controller.pause().stop().destroy() }
    }

    @Test
    fun `geometry uses view density and invalid geometry hides editor`() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val input = ExperienceTextInput.forScreen(textInputDescriptor(), "survey").single().copy(value = "abcd")
        val overlay = ExperienceTextInputOverlay(activity, ExperienceArtboardSize(200f, 100f),
            listOf(input), emptyMap(), { _, _, _, done -> done(Result.success(Unit)) }, { throw it })
        activity.setContentView(overlay)
        overlay.layout(0, 0, 400, 400)
        overlay.update(snapshot(), capturedGeometry())
        val editor = overlay.findViewWithTag<EditText>("nuxie-text-input-name")
        assertEquals("ab", editor.text.toString())
        assertEquals(View.VISIBLE, editor.visibility)
        val origin = floatArrayOf(0f, 0f)
        editor.matrix.mapPoints(origin)
        (editor.parent as View).matrix.mapPoints(origin)
        assertArrayEquals(floatArrayOf(10f, 20f), origin, 0.002f)
        assertEquals(80, editor.layoutParams.width)
        assertEquals(20, editor.layoutParams.height)
        assertEquals(Color.TRANSPARENT, editor.currentTextColor)
        overlay.update(snapshot(), capturedGeometry(width = Float.NaN))
        assertEquals(View.INVISIBLE, editor.visibility)
        overlay.close()
        assertEquals(0, overlay.childCount)
    }

    @Test
    fun `IME composition remains intact and committed text respects grapheme limit`() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val writes = mutableListOf<Pair<String, Boolean>>()
        val input = ExperienceTextInput.forScreen(textInputDescriptor(), "survey").single()
        val overlay = ExperienceTextInputOverlay(activity, ExperienceArtboardSize(200f, 100f),
            listOf(input), emptyMap(), { _, text, commit, done ->
                writes += text to commit; done(Result.success(Unit))
            }, { throw it })
        activity.setContentView(overlay)
        overlay.layout(0, 0, 400, 400)
        overlay.update(snapshot(), capturedGeometry())
        val editor = overlay.findViewWithTag<EditText>("nuxie-text-input-name")
        editor.requestFocus()
        val connection = checkNotNull(editor.onCreateInputConnection(EditorInfo()))
        connection.setComposingText("abc", 1)
        assertEquals("abc", editor.text.toString())
        connection.commitText("e\u0301😀z", 1)
        assertEquals("e\u0301😀", editor.text.toString())
        editor.clearFocus()
        assertTrue(writes.contains("e\u0301😀" to true))
        val count = writes.size
        overlay.close()
        editor.setText("late")
        assertEquals(count, writes.size)
    }

    @Test @org.robolectric.annotation.Config(sdk = [23, 30])
    fun `accessibility replacement resolves composition without losing rejected drafts`() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup().visible()
        val writes = mutableListOf<Pair<String, Boolean>>()
        val input = ExperienceTextInput.forScreen(textInputDescriptor(), "survey").single()
        val overlay = ExperienceTextInputOverlay(controller.get(), ExperienceArtboardSize(200f, 100f),
            listOf(input), emptyMap(), { _, text, commit, done ->
                writes += text to commit; done(Result.success(Unit))
            }, { throw it })
        try {
            controller.get().setContentView(overlay)
            overlay.layout(0, 0, 400, 400)
            overlay.update(snapshot(), capturedGeometry())
            val editor = overlay.findViewWithTag<EditText>("nuxie-text-input-name")
            assertTrue(editor.requestFocus())
            val connection = checkNotNull(editor.onCreateInputConnection(EditorInfo()))
            assertTrue(connection.setComposingText("abc", 1))
            assertEquals("abc", editor.text.toString())
            val before = android.view.inputmethod.BaseInputConnection.getComposingSpanEnd(editor.text)
            assertTrue(before >= 0)
            val arguments = android.os.Bundle().apply {
                putCharSequence(android.view.accessibility.AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "toolong")
            }
            assertFalse(editor.performAccessibilityAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_SET_TEXT, arguments))
            assertEquals("abc", editor.text.toString())
            assertEquals(before, android.view.inputmethod.BaseInputConnection.getComposingSpanEnd(editor.text))
            arguments.putCharSequence(android.view.accessibility.AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "e\u0301😀")
            assertTrue(editor.performAccessibilityAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_SET_TEXT, arguments))
            assertEquals("e\u0301😀", editor.text.toString())
            assertEquals(-1, android.view.inputmethod.BaseInputConnection.getComposingSpanStart(editor.text))
            assertEquals(editor.text.length, editor.selectionStart)
            connection.finishComposingText()
            assertEquals("e\u0301😀", editor.text.toString())
            editor.clearFocus()
            assertEquals("e\u0301😀" to true, writes.last())
        } finally { overlay.close(); controller.pause().stop().destroy() }
    }

    @Test
    fun `focused editor owns complete pre-edit text and restores Rive on blur`() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val writes = mutableListOf<Pair<String, Boolean>>()
        val input = ExperienceTextInput.forScreen(textInputDescriptor(), "survey").single().copy(value = "abc", maxLength = 3)
        val overlay = ExperienceTextInputOverlay(activity, ExperienceArtboardSize(200f, 100f),
            listOf(input), emptyMap(), { _, text, commit, done ->
                writes += text to commit; done(Result.success(Unit))
            }, { throw it })
        activity.setContentView(overlay)
        overlay.layout(0, 0, 400, 400)
        overlay.update(snapshot(), capturedGeometry())
        val editor = overlay.findViewWithTag<EditText>("nuxie-text-input-name")
        assertEquals("abc" to false, writes.last())
        assertEquals(Color.TRANSPARENT, editor.currentTextColor)
        editor.requestFocus()
        assertEquals("" to false, writes.last())
        assertEquals(input.style.color, editor.currentTextColor)
        val connection = checkNotNull(editor.onCreateInputConnection(EditorInfo()))
        editor.setSelection(1, 2)
        connection.setComposingText("XYZ", 1)
        assertEquals("aXYZc", editor.text.toString())
        assertEquals(input.style.color, editor.currentTextColor)
        assertEquals("" to false, writes.last())
        editor.clearFocus()
        assertEquals("aXc" to true, writes.last())
        assertEquals(Color.TRANSPARENT, editor.currentTextColor)
        connection.finishComposingText()
        assertEquals("aXc" to false, writes.last())
        assertEquals(Color.TRANSPARENT, editor.currentTextColor)
        overlay.close()
    }

    @Test
    fun `ordinary over-limit insertion cannot discard unselected text`() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val input = ExperienceTextInput.forScreen(textInputDescriptor(), "survey").single().copy(value = "ab")
        val overlay = ExperienceTextInputOverlay(activity, ExperienceArtboardSize(200f, 100f),
            listOf(input), emptyMap(), { _, _, _, done -> done(Result.success(Unit)) }, { throw it })
        activity.setContentView(overlay)
        val editor = overlay.findViewWithTag<EditText>("nuxie-text-input-name")
        val connection = checkNotNull(editor.onCreateInputConnection(EditorInfo()))
        editor.setSelection(1)
        connection.commitText("X", 1)
        assertEquals("ab", editor.text.toString())
        editor.setSelection(1, 2)
        connection.commitText("XY", 1)
        assertEquals("ab", editor.text.toString())
        editor.setSelection(1, 2)
        connection.commitText("Z", 1)
        assertEquals("aZ", editor.text.toString())
        overlay.close()
    }

    @Test
    fun `composition commit and finish preserve text outside the composing range`() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val input = ExperienceTextInput.forScreen(textInputDescriptor(), "survey").single().copy(value = "abc", maxLength = 3)
        val overlay = ExperienceTextInputOverlay(activity, ExperienceArtboardSize(200f, 100f),
            listOf(input), emptyMap(), { _, _, _, done -> done(Result.success(Unit)) }, { throw it })
        activity.setContentView(overlay)
        val editor = overlay.findViewWithTag<EditText>("nuxie-text-input-name")
        val connection = checkNotNull(editor.onCreateInputConnection(EditorInfo()))
        editor.setSelection(1, 2)
        connection.setComposingText("XYZ", 1)
        assertEquals("aXYZc", editor.text.toString())
        connection.commitText("XYZ", 1)
        assertEquals("aXc", editor.text.toString())
        editor.setText("abc")
        editor.setSelection(1, 2)
        connection.setComposingText("XYZ", 1)
        connection.finishComposingText()
        assertEquals("aXc", editor.text.toString())
        overlay.close()
    }

    @Test
    fun `text commits wait for reveal and stop at close without publications`() = runTest {
        val state = ExperienceTextInputState()
        val coordinator = JourneyRuntimeEmissionCoordinator(
            "journey", "survey", textInputDescriptor(), 3, 7,
            onEmissionBatch = { _, _ -> error("Text commits cannot publish answers") },
            onPresentationRevealed = {},
        )
        val pending = async { coordinator.publishTextCommit("name", "Ada", state) }
        yield()
        assertFalse(pending.isCompleted)
        assertTrue(coordinator.reveal())
        assertTrue(pending.await())
        assertEquals("Ada", state.committedValue("name"))
        assertTrue(coordinator.publishTextCommit("name", "", state))
        assertEquals("", state.committedValue("name"))
        coordinator.close()
        assertFalse(coordinator.publishTextCommit("name", "late", state))
        assertEquals("", state.committedValue("name"))
    }

    @Test
    fun `accepted native text survives preparation and screen replacement`() = runTest {
        val state = ExperienceTextInputState()
        fun coordinator() = JourneyRuntimeEmissionCoordinator(
            "journey", "survey", textInputDescriptor(), 0, 0,
            onEmissionBatch = { _, _ -> error("Text commits cannot publish answers") },
            onPresentationRevealed = {},
        )
        val first = coordinator()
        assertTrue(first.reveal())
        assertTrue(first.publishTextCommit("name", "Al", state))
        val replacement = state.copyForPreparation()
        first.close()
        state.detach()
        val second = coordinator()
        assertTrue(second.reveal())
        assertEquals("Al", replacement.committedValue("name"))
        assertTrue(second.publishTextCommit("name", "Bo", replacement))
        assertEquals("Bo", replacement.committedValue("name"))
        second.close()
    }

    @Test
    fun `limited authored value is the initial response baseline`() = runTest {
        var publications = 0
        val coordinator = JourneyRuntimeEmissionCoordinator(
            "journey", "survey", textInputDescriptor("abcd"), 0, 0,
            onEmissionBatch = { it, _ -> publications++; true }, onPresentationRevealed = {},
        )
        assertTrue(coordinator.reveal())
        assertTrue(coordinator.publishTextCommit("name", "ab"))
        assertEquals(0, publications)
        coordinator.close()
    }

    private fun capturedGeometry(width: Float = 80f): NuxieTextGeometryCapture {
        val transform = NuxieTextRunGeometry.Transform(1f, 0f, 0f, 1f, 10f, 20f)
        val bounds = NuxieTextRunGeometry.Bounds(0f, 0f, width, 20f)
        return NuxieTextGeometryCapture.Captured(mapOf("headline" to NuxieTextRunGeometry(
            1uL, transform, transform, bounds,
            NuxieTextRunGeometry.Layout(transform, bounds), null,
        )))
    }

    private fun snapshot(width: Float = 80f): NuxieViewModelSnapshot =
        NuxieViewModelSnapshot.fromNative(NativeViewModelSnapshot(1,
            arrayOf(NativeViewModelSnapshotInstance(1, 0)),
            mapOf("x" to 10f, "y" to 20f, "width" to width, "height" to 20f,
                "rotation" to 0f, "scaleX" to 1f, "scaleY" to 1f).map { (name, value) ->
                NativeViewModelSnapshotValue(1, 0, name, NuxieViewModelPropertyKind.NUMBER.nativeValue,
                    byteArrayOf(), 0, value)
            }.toTypedArray(),
        ))
}
