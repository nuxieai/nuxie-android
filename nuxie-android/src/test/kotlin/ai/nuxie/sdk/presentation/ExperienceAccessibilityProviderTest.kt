package ai.nuxie.sdk.presentation

import ai.nuxie.sdk.runtime.NativeSemanticModalScope
import ai.nuxie.sdk.runtime.NativeSemanticNode
import ai.nuxie.sdk.runtime.NuxieSemanticTree
import java.io.File
import kotlinx.serialization.json.*
import android.os.Build
import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import android.view.KeyEvent
import android.view.View
import android.view.accessibility.AccessibilityManager
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityNodeProvider
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class ExperienceAccessibilityProviderTest {
    @Test @Config(sdk = [23, 27, 28, 36])
    fun `heading declaration survives publication and can be removed on supported versions`() = withHost { host ->
        val provider = provider(host)
        for ((revision, level) in listOf(0, 2, 0).withIndex()) {
            provider.publish(NuxieSemanticTree(1, revision.toLong(), listOf(node().copy(role = 7, headingLevel = level))))
            val info = checkNotNull(provider.createAccessibilityNodeInfo(1))
            val heading = if (Build.VERSION.SDK_INT >= 28) info.isHeading else {
                // External AndroidX wire contract, consumed by accessibility services.
                info.extras.getInt("androidx.view.accessibility.AccessibilityNodeInfoCompat.BOOLEAN_PROPERTY_KEY", 0) and 0x2 != 0
            }
            assertEquals("heading level $level on API ${Build.VERSION.SDK_INT}", level > 0, heading)
            assertEquals("Continue", info.text?.toString())
            assertNull("A heading does not invent collection membership", info.collectionItemInfo)
        }
    }

    @Test fun `service clearing virtual focus before withdrawal preserves the owned restoration target`() = withHost { host ->
        val provider = provider(host)
        val tree = NuxieSemanticTree(1, 1, listOf(node(), node().copy(id = 43, siblingIndex = 1, label = "Second")))
        provider.publish(tree)
        assertTrue(provider.performAction(2, AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS, null))
        assertTrue(provider.performAction(2, AccessibilityNodeInfo.ACTION_CLEAR_ACCESSIBILITY_FOCUS, null))
        assertNull(provider.findFocus(AccessibilityNodeInfo.FOCUS_ACCESSIBILITY))
        provider.publish(tree)
        assertNull("A normal frame must not undo a service clear", provider.findFocus(AccessibilityNodeInfo.FOCUS_ACCESSIBILITY))
        provider.withdraw()
        provider.publish(tree)
        assertEquals("Second", provider.findFocus(AccessibilityNodeInfo.FOCUS_ACCESSIBILITY)?.text)
    }

    @Test fun `cleared virtual focus history yields to a native owner before withdrawal`() = withHost { host ->
        Shadows.shadowOf(host.context.getSystemService(AccessibilityManager::class.java)).setTouchExplorationEnabled(true)
        val provider = provider(host)
        val tree = NuxieSemanticTree(1, 1, listOf(node()))
        provider.publish(tree)
        assertTrue(provider.performAction(1, AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS, null))
        assertTrue(provider.performAction(1, AccessibilityNodeInfo.ACTION_CLEAR_ACCESSIBILITY_FOCUS, null))
        val shell = android.widget.Button(host.context)
        (host.parent as ExperienceFocusRoot).addView(shell)
        shell.layout(0, 0, 100, 50)
        assertTrue(shell.performAccessibilityAction(AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS, null))
        provider.withdraw()
        provider.publish(tree)
        assertNull(provider.findFocus(AccessibilityNodeInfo.FOCUS_ACCESSIBILITY))
        assertTrue(shell.isAccessibilityFocused)
    }

    @Test fun `retirement clears virtual focus history after the service clears focus`() = withHost { host ->
        val provider = provider(host)
        val tree = NuxieSemanticTree(1, 1, listOf(node()))
        provider.publish(tree)
        assertTrue(provider.performAction(1, AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS, null))
        assertTrue(provider.performAction(1, AccessibilityNodeInfo.ACTION_CLEAR_ACCESSIBILITY_FOCUS, null))
        provider.retire()
        provider.publish(tree)
        provider.withdraw()
        provider.publish(tree)
        assertNull(provider.findFocus(AccessibilityNodeInfo.FOCUS_ACCESSIBILITY))
    }

    @Test fun `outer sheet recovery focus cancels restoration in nested content root`() = withHost { host ->
        Shadows.shadowOf(host.context.getSystemService(AccessibilityManager::class.java)).setTouchExplorationEnabled(true)
        val inner = host.parent as ExperienceFocusRoot
        val activity = host.context as Activity
        val outer = ExperienceFocusRoot(activity)
        activity.setContentView(outer)
        outer.addView(inner)
        val shell = android.widget.Button(activity)
        outer.addView(shell)
        shell.layout(0, 0, 100, 50)
        val provider = provider(host)
        val tree = NuxieSemanticTree(1, 1, listOf(node()))
        provider.publish(tree)
        assertTrue(provider.performAction(1, AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS, null))
        provider.withdraw()
        assertTrue(shell.performAccessibilityAction(AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS, null))
        provider.publish(tree)
        assertNull(provider.findFocus(AccessibilityNodeInfo.FOCUS_ACCESSIBILITY))
        assertTrue(shell.isAccessibilityFocused)
    }

    @Test fun `focus history detaches from old window and observes reattachment`() = withHost { host ->
        val root = host.parent as ExperienceFocusRoot
        val activity = host.context as Activity
        val outside = android.widget.EditText(activity)
        activity.setContentView(outside)
        val detachedRevision = root.inputRevision
        outside.clearFocus()
        assertTrue(outside.requestFocus())
        assertEquals(detachedRevision, root.inputRevision)
        activity.setContentView(root)
        val inside = android.widget.EditText(activity)
        root.addView(inside)
        val attachedRevision = root.inputRevision
        assertTrue(inside.requestFocus())
        assertTrue(root.inputRevision > attachedRevision)
    }

    @Test fun `keyboard restoration works without accessibility and yields to a new native owner`() = withHost { host ->
        Shadows.shadowOf(host.context.getSystemService(AccessibilityManager::class.java)).setEnabled(false)
        val provider = provider(host)
        val tree = NuxieSemanticTree(1, 1, listOf(node(), node().copy(id = 43, siblingIndex = 1, label = "Second")))
        provider.publish(tree)
        assertTrue(provider.performAction(2, AccessibilityNodeInfo.ACTION_FOCUS, null))
        provider.withdraw()
        provider.withdraw()
        provider.publish(tree)
        assertEquals("Second", provider.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)?.text)

        provider.withdraw()
        val root = host.parent as ExperienceFocusRoot
        val shell = android.widget.EditText(host.context)
        root.addView(shell)
        shell.layout(0, 0, 200, 50)
        assertTrue(shell.requestFocus())
        provider.publish(tree)
        assertNull(provider.findFocus(AccessibilityNodeInfo.FOCUS_INPUT))
        assertTrue(shell.hasFocus())
    }

    @Test fun `withdrawn native editor restores current control and retirement cancels restoration`() = withHost { host ->
        val provider = provider(host)
        val root = host.parent as ExperienceFocusRoot
        val old = android.widget.EditText(host.context)
        root.addView(old)
        old.layout(0, 0, 100, 50)
        val tree = NuxieSemanticTree(1, 1, listOf(node().copy(role = 6)))
        provider.publish(tree, mapOf(42L to old))
        assertTrue(old.requestFocus())
        provider.withdraw()
        root.removeView(old)
        val replacement = android.widget.EditText(host.context)
        root.addView(replacement)
        replacement.layout(0, 0, 100, 50)
        provider.publish(tree, mapOf(42L to replacement))
        assertTrue(replacement.hasFocus())
        provider.withdraw()
        provider.retire()
        root.removeView(replacement)
        provider.publish(NuxieSemanticTree(2, 2, listOf(node())))
        assertNull(provider.findFocus(AccessibilityNodeInfo.FOCUS_INPUT))
    }

    @Test @Config(sdk = [23, 30, 36])
    fun `shared focus scenarios preserve owned targets and respect native shell focus`() = withHost { host ->
        val fixture = Json.parseToJsonElement(File("../fixtures/accessibility/focus-restoration.json").readText()).jsonObject
        assertEquals(1, fixture.getValue("schemaVersion").jsonPrimitive.int)
        val root = host.parent as ExperienceFocusRoot
        Shadows.shadowOf(host.context.getSystemService(AccessibilityManager::class.java)).setTouchExplorationEnabled(true)
        val shell = android.widget.Button(host.context).apply { text = "Recovery" }
        root.addView(shell)
        shell.layout(0, 0, 100, 50)
        for (item in fixture.getValue("cases").jsonArray) {
            shell.performAccessibilityAction(AccessibilityNodeInfo.ACTION_CLEAR_ACCESSIBILITY_FOCUS, null)
            val scenario = item.jsonObject
            val provider = provider(host)
            for (raw in scenario.getValue("steps").jsonArray) {
                val step = raw.jsonObject
                when (step.getValue("op").jsonPrimitive.content) {
                    "publish" -> provider.publish(NuxieSemanticTree(1, 1,
                        step.getValue("nodes").jsonArray.mapIndexed { index, id -> node().copy(
                            id = id.jsonPrimitive.int.toLong(), siblingIndex = index, label = id.jsonPrimitive.content) }))
                    "focus" -> {
                        val label = step.getValue("target").jsonPrimitive.content
                        val virtual = (1..64).first { provider.createAccessibilityNodeInfo(it)?.text?.toString() == label }
                        assertTrue(provider.performAction(virtual, AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS, null))
                    }
                    "withdraw" -> {
                        provider.withdraw()
                        assertNull(provider.findFocus(AccessibilityNodeInfo.FOCUS_ACCESSIBILITY))
                    }
                    "resume" -> Unit // Only the next presented tree can restore Android targets.
                    "shell" -> assertTrue(shell.performAccessibilityAction(AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS, null))
                    else -> error("Unknown shared focus operation")
                }
            }
            val actual = provider.findFocus(AccessibilityNodeInfo.FOCUS_ACCESSIBILITY)?.text?.toString()
                ?: if (shell.isAccessibilityFocused) "shell" else "none"
            assertEquals(scenario.getValue("id").jsonPrimitive.content,
                scenario.getValue("expectedFocus").jsonPrimitive.content, actual)
            provider.retire()
        }
    }

    @Test fun `shared modal scopes fence traversal and restore the original invoker`() = withHost { host ->
        val fixture = Json.parseToJsonElement(File("../fixtures/accessibility/modal-focus.json").readText()).jsonObject
        assertEquals(1, fixture.getValue("schemaVersion").jsonPrimitive.int)
        val definitions = fixture.getValue("nodes").jsonArray.associate { raw ->
            val definition = raw.jsonObject
            definition.getValue("id").jsonPrimitive.int.toLong() to definition
        }
        for (item in fixture.getValue("cases").jsonArray) {
            val scenario = item.jsonObject
            val provider = provider(host)
            fun virtual(id: Long) = (1..64).first { provider.createAccessibilityNodeInfo(it)?.text?.toString() == id.toString() }
            for (raw in scenario.getValue("steps").jsonArray) {
                val step = raw.jsonObject
                val nodes = step.getValue("nodes").jsonArray.mapIndexed { index, rawId ->
                    val id = rawId.jsonPrimitive.int.toLong()
                    val definition = definitions.getValue(id)
                    node().copy(id = id, parentId = definition["parent"]?.jsonPrimitive?.intOrNull ?: -1,
                        role = definition.getValue("role").jsonPrimitive.int,
                        stateFlags = definition.getValue("flags").jsonPrimitive.int,
                        siblingIndex = index, label = id.toString())
                }
                val scope = when (step.getValue("scope").jsonPrimitive.content) {
                    "none" -> NativeSemanticModalScope.None
                    "active" -> NativeSemanticModalScope.Active(step.getValue("activeModal").jsonPrimitive.int.toLong())
                    "unresolved" -> NativeSemanticModalScope.Unresolved
                    else -> error("Unknown shared modal scope")
                }
                provider.publish(NuxieSemanticTree(1, 1, nodes, scope))
                val exposed = (1..64).mapNotNull { provider.createAccessibilityNodeInfo(it)?.text?.toString() }
                assertEquals(scenario.getValue("id").jsonPrimitive.content,
                    step.getValue("expectedExposed").jsonArray.map { it.jsonPrimitive.content }, exposed)
                step["expectedFocus"]?.let { expected ->
                    assertEquals(scenario.getValue("id").jsonPrimitive.content, expected.jsonPrimitive.content,
                        provider.findFocus(AccessibilityNodeInfo.FOCUS_ACCESSIBILITY)?.text?.toString())
                }
                step["focusAfter"]?.let { target ->
                    assertTrue(provider.performAction(virtual(target.jsonPrimitive.int.toLong()),
                        AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS, null))
                }
                if (step["clearFocusAfter"]?.jsonPrimitive?.booleanOrNull == true) {
                    val focused = provider.findFocus(AccessibilityNodeInfo.FOCUS_ACCESSIBILITY)?.text?.toString()?.toLong()
                    if (focused != null) provider.performAction(virtual(focused), AccessibilityNodeInfo.ACTION_CLEAR_ACCESSIBILITY_FOCUS, null)
                }
            }
            provider.retire()
        }
    }

    @Test fun `modal after withdrawal returns to native invoker despite older virtual focus history`() = withHost { host ->
        Shadows.shadowOf(host.context.getSystemService(AccessibilityManager::class.java)).setTouchExplorationEnabled(true)
        val root = host.parent as ExperienceFocusRoot
        val field = android.widget.EditText(host.context)
        root.addView(field)
        field.layout(0, 0, 100, 50)
        val provider = provider(host)
        val background = listOf(node().copy(id = 1), node().copy(id = 2, role = 6))
        provider.publish(NuxieSemanticTree(1, 1, background), mapOf(2L to field))
        assertTrue(provider.performAction(1, AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS, null))
        assertTrue(field.performAccessibilityAction(AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS, null))
        provider.withdraw()
        root.removeView(field)
        val replacement = android.widget.EditText(host.context)
        root.addView(replacement)
        replacement.layout(0, 0, 100, 50)
        val dialog = node().copy(id = 10, label = "Dialog", role = 14, stateFlags = 1 shl 11)
        provider.publish(NuxieSemanticTree(2, 2, background + dialog, NativeSemanticModalScope.Active(10)), mapOf(2L to replacement))
        assertEquals("Dialog", provider.findFocus(AccessibilityNodeInfo.FOCUS_ACCESSIBILITY)?.text?.toString())
        provider.publish(NuxieSemanticTree(3, 3, background), mapOf(2L to replacement))
        assertTrue("Return to the surviving native invoker", replacement.isAccessibilityFocused)
    }

    @Test fun `modal scope excludes native fields and restores their original setting`() = withHost { host ->
        val field = android.widget.EditText(host.context).apply { importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES }
        (host.parent as ExperienceFocusRoot).addView(field)
        val provider = provider(host)
        val background = listOf(node().copy(id = 1), node().copy(id = 2, role = 6))
        provider.publish(NuxieSemanticTree(1, 1, background), mapOf(2L to field))
        val oldVirtual = (1..64).first { provider.createAccessibilityNodeInfo(it) != null }
        val dialog = node().copy(id = 10, role = 14, stateFlags = 1 shl 11)
        provider.publish(NuxieSemanticTree(2, 2, background + dialog, NativeSemanticModalScope.Active(10)), mapOf(2L to field))
        assertEquals(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS, field.importantForAccessibility)
        assertNull(provider.createAccessibilityNodeInfo(oldVirtual))
        assertFalse(provider.performAction(oldVirtual, AccessibilityNodeInfo.ACTION_CLICK, null))
        provider.publish(NuxieSemanticTree(3, 3, background), mapOf(2L to field))
        assertEquals(View.IMPORTANT_FOR_ACCESSIBILITY_YES, field.importantForAccessibility)
        field.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        provider.publish(NuxieSemanticTree(4, 4, background + dialog, NativeSemanticModalScope.Active(10)), mapOf(2L to field))
        provider.retire()
        assertEquals(View.IMPORTANT_FOR_ACCESSIBILITY_NO, field.importantForAccessibility)
    }

    @Test fun `Android nodes expose authored labels geometry and exact actions`() = withHost { host ->
        val requests = mutableListOf<Triple<Long, Long, Int>>()
        val provider = provider(host) { tree, id, action -> requests += Triple(tree.renderRevision, id, action); true }
        provider.publish(NuxieSemanticTree(7, 1, listOf(node().copy(actions = 3))))
        val info = checkNotNull(provider.createAccessibilityNodeInfo(1))
        assertEquals("Continue", info.text)
        assertEquals("android.widget.Button", info.className)
        assertEquals("More information", info.hintText)
        val screen = Rect()
        info.getBoundsInScreen(screen)
        assertEquals(Rect(30, 40, 130, 90), screen)
        assertTrue(info.isVisibleToUser)
        assertTrue(info.isClickable)
        assertTrue(provider.performAction(1, AccessibilityNodeInfo.ACTION_CLICK, null))
        assertTrue(provider.performAction(1, AccessibilityNodeInfo.ACTION_SCROLL_FORWARD, null))
        assertFalse(provider.performAction(1, AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD, null))
        assertEquals(listOf(Triple(7L, 42L, 0), Triple(7L, 42L, 1)), requests)
        assertEquals(1, provider.findAccessibilityNodeInfosByText("CONTINUE", AccessibilityNodeProvider.HOST_VIEW_ID).size)
    }

    @Test fun `focus survives stable updates and retirement invalidates old virtual ids`() = withHost { host ->
        val provider = provider(host)
        provider.publish(NuxieSemanticTree(1, 1, listOf(node())))
        assertTrue(provider.performAction(1, AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS, null))
        provider.publish(NuxieSemanticTree(2, 2, listOf(node().copy(label = "Changed"))))
        assertEquals("Changed", provider.findFocus(AccessibilityNodeInfo.FOCUS_ACCESSIBILITY)?.text)
        provider.retire()
        assertNull(provider.findFocus(AccessibilityNodeInfo.FOCUS_ACCESSIBILITY))
        provider.publish(NuxieSemanticTree(1, 1, listOf(node())))
        assertNull(provider.createAccessibilityNodeInfo(1))
        assertFalse(provider.performAction(1, AccessibilityNodeInfo.ACTION_CLICK, null))
        assertNotNull(provider.createAccessibilityNodeInfo(2))
    }

    @Test fun `disabled actions and obscured values stay unavailable`() = withHost { host ->
        var calls = 0
        val provider = provider(host) { _, _, _ -> calls++; true }
        provider.publish(NuxieSemanticTree(1, 1, listOf(node().copy(stateFlags = 64 or 4096, value = "secret"))))
        val info = checkNotNull(provider.createAccessibilityNodeInfo(1))
        assertFalse(info.isEnabled)
        assertFalse(info.isClickable)
        assertTrue(info.isPassword)
        assertNull(info.stateDescription)
        assertFalse(provider.performAction(1, AccessibilityNodeInfo.ACTION_CLICK, null))
        assertEquals(0, calls)
    }

    @Test @Config(sdk = [23, 30])
    fun `disabled ancestor is announced and rejects virtual activation until reenabling`() = withHost { host ->
        var calls = 0
        val provider = provider(host) { _, _, _ -> calls++; true }
        val group = node().copy(id = 10, role = 9, actions = 0, label = "Group")
        val child = node().copy(parentId = 10)
        fun publish(disabled: Boolean) = provider.publish(NuxieSemanticTree(1, 1,
            listOf(child, group.copy(stateFlags = if (disabled) 64 else 0))))
        publish(true)
        assertFalse(checkNotNull(provider.createAccessibilityNodeInfo(1)).isEnabled)
        assertFalse(provider.performAction(1, AccessibilityNodeInfo.ACTION_CLICK, null))
        assertFalse(provider.performAction(1, AccessibilityNodeInfo.ACTION_FOCUS, null))
        assertEquals(0, calls)
        publish(false)
        assertTrue(checkNotNull(provider.createAccessibilityNodeInfo(1)).isEnabled)
        assertTrue(provider.performAction(1, AccessibilityNodeInfo.ACTION_CLICK, null))
        assertEquals(1, calls)
    }

    @Test @Config(sdk = [23]) fun `older Android exposes values and hints while omitting secure values`() = withHost { host ->
        val provider = provider(host)
        provider.publish(NuxieSemanticTree(1, 1, listOf(node().copy(value = "50 percent"))))
        val publicInfo = checkNotNull(provider.createAccessibilityNodeInfo(1))
        assertEquals("Continue, More information", publicInfo.contentDescription)
        assertEquals("50 percent", publicInfo.extras.getCharSequence(ExperienceAccessibilityStateDescription.STATE_DESCRIPTION_KEY))
        provider.publish(NuxieSemanticTree(2, 2, listOf(node().copy(stateFlags = 4096, value = "secret"))))
        val secureInfo = checkNotNull(provider.createAccessibilityNodeInfo(1))
        assertEquals("Continue, More information", secureInfo.contentDescription)
        assertNull(secureInfo.extras.getCharSequence(ExperienceAccessibilityStateDescription.STATE_DESCRIPTION_KEY))
    }

    @Test @Config(sdk = [23, 30]) fun `keyboard follows authored order skips disabled nodes and leaves at edges`() = withHost { host ->
        val requests = mutableListOf<Long>()
        val provider = provider(host) { _, id, _ -> requests += id; true }
        provider.publish(NuxieSemanticTree(1, 1, listOf(
            node().copy(id = 90, siblingIndex = 3, label = "Last"),
            node().copy(id = 50, siblingIndex = 1, stateFlags = 64, label = "Disabled"),
            node().copy(id = 42, siblingIndex = 0, label = "First"),
            node().copy(id = 60, siblingIndex = 2, actions = 0, label = "Static"),
        )))
        assertTrue(host.requestFocus())
        fun key(code: Int, modifiers: Int = 0, repeats: Int = 0) =
            provider.key(KeyEvent(0, 0, KeyEvent.ACTION_DOWN, code, repeats, modifiers))
        assertTrue(key(KeyEvent.KEYCODE_TAB))
        assertEquals("First", provider.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)?.text)
        assertFalse(key(KeyEvent.KEYCODE_ENTER, KeyEvent.META_CTRL_ON))
        assertFalse(key(KeyEvent.KEYCODE_ENTER, repeats = 1))
        assertTrue(key(KeyEvent.KEYCODE_ENTER))
        assertTrue(key(KeyEvent.KEYCODE_TAB))
        assertEquals("Last", provider.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)?.text)
        assertFalse(key(KeyEvent.KEYCODE_TAB))
        assertTrue(key(KeyEvent.KEYCODE_TAB, KeyEvent.META_SHIFT_ON))
        assertEquals("First", provider.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)?.text)
        assertFalse(key(KeyEvent.KEYCODE_TAB, KeyEvent.META_SHIFT_ON))
        assertEquals(listOf(42L), requests)
        provider.clearInputFocus()
        assertNull(provider.findFocus(AccessibilityNodeInfo.FOCUS_INPUT))
        assertFalse(key(KeyEvent.KEYCODE_SPACE))
        assertTrue(key(KeyEvent.KEYCODE_TAB, KeyEvent.META_SHIFT_ON))
        assertEquals("Last", provider.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)?.text)
        host.isEnabled = false
        provider.invalidateState()
        assertNull(provider.findFocus(AccessibilityNodeInfo.FOCUS_INPUT))
        assertFalse(key(KeyEvent.KEYCODE_TAB))
    }

    @Test @Config(sdk = [30], qualifiers = "mdpi")
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun `keyboard focus indicator renders on committed bounds and disappears on focus loss`() = withHost { host ->
        host.setBackgroundColor(Color.RED)
        var geometry = Rect(10, 20, 110, 70)
        val provider = ExperienceAccessibilityProvider(host,
            { ExperienceAccessibilityProvider.Bounds(Rect(geometry), Rect(geometry)) }, { _, _, _ -> true })
        provider.publish(NuxieSemanticTree(1, 1, listOf(node())))
        assertTrue(host.requestFocus())
        provider.hostFocusChanged(true, View.FOCUS_FORWARD)
        assertEquals("Continue", provider.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)?.text)
        fun pixel(x: Int, y: Int): Int {
            val bitmap = Bitmap.createBitmap(300, 300, Bitmap.Config.ARGB_8888)
            host.draw(Canvas(bitmap))
            return bitmap.getPixel(x, y).also { bitmap.recycle() }
        }
        assertEquals(Color.BLACK, pixel(10, 40))
        assertEquals(Color.WHITE, pixel(12, 40))
        assertEquals(Color.RED, pixel(20, 40))
        geometry = Rect(50, 20, 150, 70)
        provider.publish(NuxieSemanticTree(2, 1, listOf(node())))
        assertEquals(Color.RED, pixel(12, 40))
        assertEquals(Color.WHITE, pixel(52, 40))
        provider.hostFocusChanged(false, View.FOCUS_FORWARD)
        assertNull(provider.findFocus(AccessibilityNodeInfo.FOCUS_INPUT))
        assertEquals(Color.RED, pixel(52, 40))
        provider.hostFocusChanged(true, View.FOCUS_FORWARD)
        provider.retire()
        assertEquals(Color.RED, pixel(52, 40))
    }

    @Test @Config(sdk = [23, 30])
    fun `reverse focus entry selects final control without an extra tab`() = withHost { host ->
        val provider = provider(host)
        provider.publish(NuxieSemanticTree(1, 1, listOf(node(), node().copy(id = 99, siblingIndex = 1, label = "Last"))))
        assertTrue(host.requestFocus())
        provider.hostFocusChanged(true, View.FOCUS_BACKWARD)
        assertEquals("Last", provider.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)?.text)
        provider.hostFocusChanged(false, View.FOCUS_FORWARD)
        provider.hostFocusChanged(true, View.FOCUS_FORWARD)
        assertEquals("Continue", provider.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)?.text)
    }

    @Test @Config(sdk = [23, 30])
    fun `native fields link to neighboring virtual controls without duplicate children`() = withHost { host ->
        val field = android.widget.EditText(host.context).apply { setText("Ada") }
        val provider = provider(host)
        provider.publish(NuxieSemanticTree(1, 1, listOf(
            node().copy(id = 1, siblingIndex = 0, label = "Before"),
            node().copy(id = 2, siblingIndex = 1, role = 6, label = "Name"),
            node().copy(id = 3, siblingIndex = 2, label = "After"),
        )), mapOf(2L to field))
        assertEquals(2, provider.createAccessibilityNodeInfo(AccessibilityNodeProvider.HOST_VIEW_ID)?.childCount)
        val info = field.createAccessibilityNodeInfo()
        assertTrue(info.isEditable)
        assertEquals("Ada", info.text.toString())
        // Robolectric's traversal setters discard virtual IDs. Exact targets are
        // verified through UiAutomation in SemanticTraversalDeviceTest.
        assertNotNull(info.traversalAfter)
        assertNotNull(info.traversalBefore)
        assertNotNull(provider.createAccessibilityNodeInfo(1)?.traversalBefore)
        assertNotNull(provider.createAccessibilityNodeInfo(3)?.traversalAfter)
        provider.retire()
        val retired = field.createAccessibilityNodeInfo()
        assertNull(retired.traversalAfter)
        assertNull(retired.traversalBefore)
    }

    @Test @Config(sdk = [23, 30])
    fun `keyboard crosses native fields in authored order and leaves the mixed group`() = withHost { host ->
        val activity = host.context as Activity
        (host.parent as android.view.ViewGroup).removeView(host)
        val before = android.widget.Button(activity).apply { text = "Outside before"; isFocusableInTouchMode = true }
        val field = android.widget.EditText(activity).apply { setText("Ada") }
        val after = android.widget.Button(activity).apply { text = "Outside after"; isFocusableInTouchMode = true }
        val root = android.widget.LinearLayout(activity).apply { orientation = android.widget.LinearLayout.VERTICAL }
        listOf(before, host, field, after).forEach {
            root.addView(it, android.widget.LinearLayout.LayoutParams(300, 80))
        }
        activity.setContentView(root)
        root.measure(View.MeasureSpec.makeMeasureSpec(300, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(400, View.MeasureSpec.EXACTLY))
        root.layout(0, 0, 300, 400)
        val provider = provider(host)
        provider.publish(NuxieSemanticTree(1, 1, listOf(
            node().copy(id = 1, siblingIndex = 0, label = "First"),
            node().copy(id = 2, siblingIndex = 1, role = 6),
            node().copy(id = 3, siblingIndex = 2, label = "Last"),
        )), mapOf(2L to field))
        fun tab(backwards: Boolean = false) = provider.key(KeyEvent(0, 0, KeyEvent.ACTION_DOWN,
            KeyEvent.KEYCODE_TAB, 0, if (backwards) KeyEvent.META_SHIFT_ON else 0))
        assertTrue(host.requestFocus())
        provider.hostFocusChanged(true, View.FOCUS_FORWARD)
        assertTrue(tab())
        assertTrue(field.hasFocus())
        assertFalse(provider.key(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER)))
        assertEquals("Ada", field.text.toString())
        assertTrue(tab())
        assertTrue(host.hasFocus())
        assertEquals("Last", provider.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)?.text)
        assertTrue(tab(true))
        assertTrue(field.hasFocus())
        assertTrue(tab(true))
        assertEquals("First", provider.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)?.text)
        assertTrue(tab(true))
        assertTrue(before.hasFocus())
        assertTrue(host.requestFocus())
        provider.hostFocusChanged(true, View.FOCUS_FORWARD)
        assertTrue(tab())
        assertTrue(tab())
        assertTrue(tab())
        assertTrue(after.hasFocus())
    }

    @Test @Config(sdk = [23, 30])
    fun `arrows follow geometry skip disabled controls and do not activate during movement`() = withHost { host ->
        val actions = mutableListOf<Long>()
        val provider = ExperienceAccessibilityProvider(host, { node ->
            val rect = Rect(node.minX.toInt(), node.minY.toInt(), node.maxX.toInt(), node.maxY.toInt())
            ExperienceAccessibilityProvider.Bounds(rect, Rect(rect))
        }, { _, id, _ -> actions += id; true })
        fun button(id: Long, x: Float, y: Float) = node().copy(id = id, siblingIndex = id.toInt(),
            minX = x, minY = y, maxX = x + 20, maxY = y + 20, label = "Button $id")
        provider.publish(NuxieSemanticTree(1, 1, listOf(
            button(1, 0f, 0f), button(2, 100f, 0f), button(3, 0f, 100f), button(4, 100f, 100f),
            button(5, 30f, 40f), button(6, 50f, 0f).copy(stateFlags = 64),
        )))
        assertTrue(host.requestFocus())
        provider.hostFocusChanged(true, View.FOCUS_FORWARD)
        fun key(code: Int, repeats: Int = 0) = provider.key(KeyEvent(0, 0, KeyEvent.ACTION_DOWN, code, repeats))
        assertTrue(key(KeyEvent.KEYCODE_DPAD_RIGHT))
        assertEquals("Button 2", provider.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)?.text)
        assertTrue(key(KeyEvent.KEYCODE_DPAD_DOWN, 1))
        assertEquals("Button 4", provider.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)?.text)
        assertTrue(key(KeyEvent.KEYCODE_DPAD_LEFT))
        assertEquals("Button 3", provider.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)?.text)
        assertTrue(key(KeyEvent.KEYCODE_DPAD_UP))
        assertEquals("Button 1", provider.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)?.text)
        assertTrue(actions.isEmpty())
        assertTrue(key(KeyEvent.KEYCODE_DPAD_CENTER))
        assertEquals(listOf(1L), actions)
    }

    @Test @Config(sdk = [23, 30])
    fun `adjustable control arrows use declared actions and honor RTL`() = withHost { host ->
        val actions = mutableListOf<Int>()
        val provider = provider(host) { _, _, action -> actions += action; true }
        provider.publish(NuxieSemanticTree(1, 1, listOf(node().copy(role = 5, actions = 6))))
        assertTrue(host.requestFocus())
        provider.hostFocusChanged(true, View.FOCUS_FORWARD)
        assertTrue(provider.key(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_LEFT)))
        assertTrue(provider.key(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_RIGHT)))
        host.context.applicationInfo.flags = host.context.applicationInfo.flags or android.content.pm.ApplicationInfo.FLAG_SUPPORTS_RTL
        host.layoutDirection = View.LAYOUT_DIRECTION_RTL
        assertEquals(View.LAYOUT_DIRECTION_RTL, host.layoutDirection)
        assertTrue(provider.key(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_RIGHT)))
        assertTrue(provider.key(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_LEFT)))
        assertEquals(listOf(2, 1, 2, 1), actions)
        assertFalse(provider.key(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_CENTER)))
    }

    @Test @Config(sdk = [23, 30, 36])
    fun `shared control states project through native Android node fields`() = withHost { host ->
        val fixture = Json.parseToJsonElement(File("../fixtures/accessibility/control-state.json").readText()).jsonObject
        assertEquals(1, fixture.getValue("schemaVersion").jsonPrimitive.int)
        val provider = provider(host)
        fixture.getValue("cases").jsonArray.forEachIndexed { index, item ->
            val case = item.jsonObject
            fun int(key: String) = case.getValue(key).jsonPrimitive.int
            fun bool(key: String) = case.getValue(key).jsonPrimitive.boolean
            fun text(key: String) = case.getValue(key).jsonPrimitive.content
            val id = text("id")
            val field = if (int("role") == 6) android.widget.EditText(host.context).apply { setText("Native text") } else null
            provider.publish(NuxieSemanticTree(index.toLong(), index.toLong(), listOf(node().copy(
                role = int("role"), traitFlags = int("traits"), stateFlags = int("state"), value = text("value"),
            ))), field?.let { mapOf(42L to it) } ?: emptyMap())
            val info = checkNotNull(field?.createAccessibilityNodeInfo() ?: provider.createAccessibilityNodeInfo(1))
            assertEquals(id, bool("checkable"), info.isCheckable)
            if (Build.VERSION.SDK_INT >= 36) {
                assertEquals(id, when {
                    bool("mixed") -> AccessibilityNodeInfo.CHECKED_STATE_PARTIAL
                    bool("checked") -> AccessibilityNodeInfo.CHECKED_STATE_TRUE
                    else -> AccessibilityNodeInfo.CHECKED_STATE_FALSE
                }, info.checked)
                assertEquals(id, when {
                    !bool("expandable") -> AccessibilityNodeInfo.EXPANDED_STATE_UNDEFINED
                    bool("expanded") -> AccessibilityNodeInfo.EXPANDED_STATE_FULL
                    else -> AccessibilityNodeInfo.EXPANDED_STATE_COLLAPSED
                }, info.expandedState)
            } else {
                assertEquals(id, bool("checked") && !bool("mixed"), info.isChecked)
            }
            val expected = when (id) {
                "authored-checkable-mixed" -> if (Build.VERSION.SDK_INT < 36) "Partially checked" else null
                "localized-value" -> "Activé, On"
                "expanded" -> if (Build.VERSION.SDK_INT < 36) "Expanded" else null
                "collapsed" -> if (Build.VERSION.SDK_INT < 36) "Collapsed" else null
                "mixed-with-value" -> "2 of 3, Partially checked"
                "required-read-only" -> "Required, Read only"
                "required-toggle-on" -> "On, Required"
                "expanded-required-with-value" -> if (Build.VERSION.SDK_INT < 36) "Details, Expanded, Required" else "Details, Required"
                else -> null
            }
            val description = if (Build.VERSION.SDK_INT >= 30) info.stateDescription
                else info.extras.getCharSequence(ExperienceAccessibilityStateDescription.STATE_DESCRIPTION_KEY)
            assertEquals(id, expected, description?.toString())
            if (field != null) assertEquals("Native text", field.text.toString())

        }
    }

    @Test @Config(sdk = [23, 30, 36])
    fun `native field descriptions update without replacing text or exposing captured values`() = withHost { host ->
        val provider = provider(host)
        val field = android.widget.EditText(host.context).apply {
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
            setText("Native secret")
        }
        val semantic = node().copy(role = 6, stateFlags = 4096 or 32 or 1024, value = "Captured secret")
        provider.publish(NuxieSemanticTree(1, 1, listOf(semantic)), mapOf(42L to field))
        fun description(): CharSequence? {
            val info = field.createAccessibilityNodeInfo()
            return if (Build.VERSION.SDK_INT >= 30) info.stateDescription
                else info.extras.getCharSequence(ExperienceAccessibilityStateDescription.STATE_DESCRIPTION_KEY)
        }
        assertEquals("Required, Read only", description()?.toString())
        assertEquals("Native secret", field.text.toString())
        provider.publish(NuxieSemanticTree(1, 2, listOf(semantic.copy(stateFlags = 4096))), mapOf(42L to field))
        assertNull(description())
        assertEquals("Native secret", field.text.toString())
    }

    @Test @Config(sdk = [30], qualifiers = "fr")
    fun `mixed state words use packaged French resources`() = withHost { host ->
        val provider = provider(host)
        provider.publish(NuxieSemanticTree(1, 1, listOf(node().copy(role = 3, stateFlags = 8 or 32))))
        val info = checkNotNull(provider.createAccessibilityNodeInfo(1))
        assertEquals("Partiellement coché, Obligatoire", info.stateDescription?.toString())
    }

    private fun provider(host: View, dispatch: (NuxieSemanticTree, Long, Int) -> Boolean = { _, _, _ -> true }) =
        ExperienceAccessibilityProvider(host, { ExperienceAccessibilityProvider.Bounds(Rect(10, 20, 110, 70), Rect(30, 40, 130, 90)) }, dispatch)

    private fun node() = NativeSemanticNode(42, -1, 0, 1, 0, 0, 0, 1,
        10f, 20f, 110f, 70f, "Continue", "", "More information")

    private fun withHost(block: (View) -> Unit) {
        val controller = Robolectric.buildActivity(Activity::class.java).setup().visible()
        try {
            val activity = controller.get()
            Shadows.shadowOf(activity.getSystemService(AccessibilityManager::class.java)).setEnabled(true)
            val host = View(activity).apply { isFocusableInTouchMode = true }
            activity.setContentView(ExperienceFocusRoot(activity).apply { addView(host) })
            host.layout(0, 0, 300, 300)
            assertTrue(host.isShown)
            block(host)
        } finally { controller.pause().stop().destroy() }
    }
}
