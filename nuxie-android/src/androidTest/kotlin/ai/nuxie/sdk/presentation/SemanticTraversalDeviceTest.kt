package ai.nuxie.sdk.presentation

import ai.nuxie.sdk.runtime.NativeSemanticNode
import ai.nuxie.sdk.runtime.NuxieSemanticTree
import kotlinx.serialization.json.*
import android.os.Build
import android.content.Context
import android.content.Intent
import android.graphics.Rect
import android.view.KeyEvent
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityNodeProvider
import android.widget.EditText
import android.widget.FrameLayout
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test

class SemanticTraversalDeviceTest {
    @Test fun sharedFocusRestorationReachesAndroidAccessibilityClient() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val automation = instrumentation.uiAutomation
        val originalInfo = automation.serviceInfo
        val originalFlags = originalInfo.flags
        originalInfo.flags = originalFlags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_REQUEST_TOUCH_EXPLORATION_MODE
        automation.serviceInfo = originalInfo
        val fixture = Json.parseToJsonElement(instrumentation.context.assets
            .open("accessibility/focus-restoration.json").bufferedReader().use { it.readText() }).jsonObject
        val activity = instrumentation.startActivitySync(Intent(instrumentation.targetContext,
            SurfaceCompatibilityHostActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        lateinit var host: SemanticView
        fun settle() {
            instrumentation.waitForIdleSync()
            automation.waitForIdle(100, 5000)
        }
        fun find(label: String): AccessibilityNodeInfo = checkNotNull(automation.rootInActiveWindow)
            .findAccessibilityNodeInfosByText(label).single { it.text?.toString() == label }
        try {
            val cases = fixture.getValue("cases").jsonArray.flatMap { listOf(it to false, it to true) }
            for ((item, useNativeField) in cases) {
                val scenario = item.jsonObject
                lateinit var editor: EditText
                instrumentation.runOnMainSync {
                    host = SemanticView(activity).apply { importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES }
                    editor = EditText(activity).apply { setText("2"); id = View.generateViewId() }
                    activity.setContentView(ExperienceFocusRoot(activity).apply {
                        addView(ExperienceFocusRoot(activity).apply {
                            addView(host, FrameLayout.LayoutParams(400, 400))
                            if (useNativeField) addView(editor, FrameLayout.LayoutParams(300, 80).apply { topMargin = 100 })
                        }, FrameLayout.LayoutParams(400, 400))
                        addView(android.widget.Button(activity).apply { text = "shell"; isAllCaps = false },
                            FrameLayout.LayoutParams(200, 80).apply { topMargin = 500 })
                    })
                }
                settle()
                for (raw in scenario.getValue("steps").jsonArray) {
                    val step = raw.jsonObject
                    when (step.getValue("op").jsonPrimitive.content) {
                        "publish" -> instrumentation.runOnMainSync {
                            if (useNativeField) editor.visibility = View.VISIBLE
                            host.semantics.publish(NuxieSemanticTree(1, 1,
                                step.getValue("nodes").jsonArray.mapIndexed { position, id ->
                                    node(id.jsonPrimitive.int.toLong(), position,
                                        if (useNativeField && id.jsonPrimitive.int == 2) 6 else 1,
                                        position * 100f, id.jsonPrimitive.content)
                                }), if (useNativeField) mapOf(2L to editor) else emptyMap())
                        }
                        "focus" -> assertTrue(find(step.getValue("target").jsonPrimitive.content)
                            .performAction(AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS))
                        "withdraw" -> instrumentation.runOnMainSync {
                            host.semantics.withdraw()
                            if (useNativeField) editor.visibility = View.INVISIBLE
                        }
                        "resume" -> Unit
                        "shell" -> assertTrue(find("shell").performAction(AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS))
                        else -> error("Unknown shared focus operation")
                    }
                    settle()
                }
                val focused = automation.findFocus(AccessibilityNodeInfo.FOCUS_ACCESSIBILITY)
                assertEquals("${scenario.getValue("id").jsonPrimitive.content}, native field: $useNativeField",
                    scenario.getValue("expectedFocus").jsonPrimitive.content, focused?.text?.toString())
            }
        } finally {
            instrumentation.runOnMainSync { activity.finish() }
            automation.serviceInfo = automation.serviceInfo.apply { flags = originalFlags }
        }
    }

    private class SemanticView(context: Context) : View(context) {
        val semantics = ExperienceAccessibilityProvider(this, { node ->
            val local = Rect(node.minX.toInt(), node.minY.toInt(), node.maxX.toInt(), node.maxY.toInt())
            val origin = IntArray(2)
            getLocationOnScreen(origin)
            ExperienceAccessibilityProvider.Bounds(local, Rect(local).apply { offset(origin[0], origin[1]) })
        }, { _, _, _ -> true })
        override fun getAccessibilityNodeProvider(): AccessibilityNodeProvider = semantics
        override fun dispatchKeyEvent(event: KeyEvent): Boolean = semantics.key(event) || super.dispatchKeyEvent(event)
        override fun onFocusChanged(gainFocus: Boolean, direction: Int, previouslyFocusedRect: Rect?) {
            super.onFocusChanged(gainFocus, direction, previouslyFocusedRect)
            semantics.hostFocusChanged(gainFocus, direction)
        }
    }

    @Test fun sharedControlStatesReachAndroidAccessibilityClients() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val fixture = Json.parseToJsonElement(instrumentation.context.assets
            .open("accessibility/control-state.json").bufferedReader().use { it.readText() }).jsonObject
        assertEquals(1, fixture.getValue("schemaVersion").jsonPrimitive.int)
        val activity = instrumentation.startActivitySync(Intent(instrumentation.targetContext,
            SurfaceCompatibilityHostActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        lateinit var host: SemanticView
        try {
            instrumentation.runOnMainSync {
                host = SemanticView(activity).apply { importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES }
                activity.setContentView(host)
            }
            fixture.getValue("cases").jsonArray.forEachIndexed { index, item ->
                val case = item.jsonObject
                fun int(key: String) = case.getValue(key).jsonPrimitive.int
                fun bool(key: String) = case.getValue(key).jsonPrimitive.boolean
                fun text(key: String) = case.getValue(key).jsonPrimitive.content
                val label = "State vector " + text("id")
                instrumentation.runOnMainSync {
                    host.semantics.publish(NuxieSemanticTree(index.toLong(), index.toLong(), listOf(
                        node(1, 0, int("role"), 100f, label).copy(
                            traitFlags = int("traits"), stateFlags = int("state"), value = text("value")),
                    )))
                }
                instrumentation.waitForIdleSync()
                instrumentation.uiAutomation.waitForIdle(100, 5000)
                val root = checkNotNull(instrumentation.uiAutomation.rootInActiveWindow)
                val info = root.findAccessibilityNodeInfosByText(label).single { it.text?.toString() == label }
                assertEquals(label, bool("checkable"), info.isCheckable)
                if (Build.VERSION.SDK_INT >= 36) {
                    assertEquals(label, when {
                        bool("mixed") -> AccessibilityNodeInfo.CHECKED_STATE_PARTIAL
                        bool("checked") -> AccessibilityNodeInfo.CHECKED_STATE_TRUE
                        else -> AccessibilityNodeInfo.CHECKED_STATE_FALSE
                    }, info.checked)
                    assertEquals(label, when {
                        !bool("expandable") -> AccessibilityNodeInfo.EXPANDED_STATE_UNDEFINED
                        bool("expanded") -> AccessibilityNodeInfo.EXPANDED_STATE_FULL
                        else -> AccessibilityNodeInfo.EXPANDED_STATE_COLLAPSED
                    }, info.expandedState)
                } else assertEquals(label, bool("checked") && !bool("mixed"), info.isChecked)
                if (Build.VERSION.SDK_INT >= 30) {
                    assertEquals(label, text("value").takeIf { it.isNotEmpty() && int("state") and 4096 == 0 },
                        info.stateDescription?.toString())
                }
            }
        } finally { instrumentation.runOnMainSync { activity.finish() } }
    }

    @Test fun nativeFieldLinksResolveToExactVirtualNeighborsThroughAndroid() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val activity = instrumentation.startActivitySync(Intent(instrumentation.targetContext,
            SurfaceCompatibilityHostActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        try {
            instrumentation.runOnMainSync {
                val host = SemanticView(activity).apply { importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES }
                val field = EditText(activity).apply { setText("Traversal field"); id = View.generateViewId() }
                val root = FrameLayout(activity)
                root.addView(host, FrameLayout.LayoutParams(400, 400))
                root.addView(field, FrameLayout.LayoutParams(300, 80).apply { topMargin = 100 })
                activity.setContentView(root)
                host.semantics.publish(NuxieSemanticTree(1, 1, listOf(
                    node(1, 0, 1, 0f, "Traversal before"),
                    node(2, 1, 6, 100f, "Field"),
                    node(3, 2, 1, 200f, "Traversal after"),
                )), mapOf(2L to field))
            }
            instrumentation.waitForIdleSync()
            instrumentation.uiAutomation.waitForIdle(100, 5000)
            val root = checkNotNull(instrumentation.uiAutomation.rootInActiveWindow)
            val field = root.findAccessibilityNodeInfosByText("Traversal field").single { it.isEditable }
            assertTrue(field.isVisibleToUser)
            val before = checkNotNull(field.traversalAfter) { "Native field must follow the first virtual control" }
            val after = checkNotNull(field.traversalBefore) { "Native field must precede the final virtual control" }
            assertEquals("Traversal before", before.text.toString())
            assertEquals("Traversal after", after.text.toString())
            assertEquals("Traversal field", before.traversalBefore?.text.toString())
            assertEquals("Traversal field", after.traversalAfter?.text.toString())
            assertTrue(before.isClickable)
            assertTrue(after.isClickable)
        } finally { instrumentation.runOnMainSync { activity.finish() } }
    }

    @Test fun nativeFieldTraversalRefreshesAfterAuthoredOrderChanges() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val automation = instrumentation.uiAutomation
        val activity = instrumentation.startActivitySync(Intent(instrumentation.targetContext,
            SurfaceCompatibilityHostActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        lateinit var host: SemanticView
        lateinit var field: EditText
        fun publish(reversed: Boolean) {
            host.semantics.publish(NuxieSemanticTree(if (reversed) 2 else 1, 1, listOf(
                node(1, if (reversed) 2 else 0, 1, 0f, "Before"),
                node(2, 1, 6, 100f, "Field"),
                node(3, if (reversed) 0 else 2, 1, 200f, "After"),
            )), mapOf(2L to field))
        }
        fun settle() {
            instrumentation.waitForIdleSync()
            automation.waitForIdle(100, 5000)
        }
        try {
            instrumentation.runOnMainSync {
                host = SemanticView(activity).apply { importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES }
                field = EditText(activity).apply { setText("Cached native field") }
                activity.setContentView(FrameLayout(activity).apply {
                    addView(host, FrameLayout.LayoutParams(400, 400))
                    addView(field, FrameLayout.LayoutParams(300, 80).apply { topMargin = 100 })
                })
                publish(false)
            }
            settle()
            val original = checkNotNull(automation.rootInActiveWindow)
                .findAccessibilityNodeInfosByText("Cached native field").single { it.isEditable }
            assertEquals("Before", original.traversalAfter?.text?.toString())
            assertEquals("After", original.traversalBefore?.text?.toString())
            val parent = checkNotNull(original.parent)
            val position = (0 until parent.childCount).single { parent.getChild(it) == original }
            instrumentation.runOnMainSync { publish(true) }
            settle()
            // Requery through the existing parent so framework cache invalidation is exercised.
            val refreshed = checkNotNull(parent.getChild(position))
            assertEquals("After", refreshed.traversalAfter?.text?.toString())
            assertEquals("Before", refreshed.traversalBefore?.text?.toString())
        } finally { instrumentation.runOnMainSync { activity.finish() } }
    }

    @Test fun injectedKeyboardTraversesContainerEditorsAndExternalControls() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val activity = instrumentation.startActivitySync(Intent(instrumentation.targetContext,
            SurfaceCompatibilityHostActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        lateinit var host: SemanticView
        lateinit var field: EditText
        lateinit var before: android.widget.Button
        lateinit var after: android.widget.Button
        val keys = mutableListOf<String>()
        try {
            instrumentation.runOnMainSync {
                before = android.widget.Button(activity).apply { text = "Outside before" }
                after = android.widget.Button(activity).apply { text = "Outside after" }
                host = SemanticView(activity).apply { isFocusableInTouchMode = true }
                field = EditText(activity).apply { setText("Ada"); showSoftInputOnFocus = false }
                val group = ExperienceInputContainer(activity, { event ->
                    val from = if (field.hasFocus()) "field" else if (host.hasFocus()) "host" else "outside"
                    val accepted = host.semantics.key(event)
                    keys += "${event.action}:$from:$accepted:${host.semantics.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)?.text}"
                    accepted
                }, host.semantics::keyboardEntry)
                group.addView(host, FrameLayout.LayoutParams(400, 320))
                group.addView(field, FrameLayout.LayoutParams(300, 80).apply { topMargin = 100 })
                val root = android.widget.LinearLayout(activity).apply { orientation = android.widget.LinearLayout.VERTICAL }
                root.addView(before, android.widget.LinearLayout.LayoutParams(400, 80))
                root.addView(group, android.widget.LinearLayout.LayoutParams(400, 320))
                root.addView(after, android.widget.LinearLayout.LayoutParams(400, 80))
                activity.setContentView(root)
                host.semantics.publish(NuxieSemanticTree(1, 1, listOf(
                    node(1, 0, 1, 0f, "First"), node(2, 1, 6, 100f, "Field"),
                    node(3, 2, 1, 200f, "Last"),
                )), mapOf(2L to field))
            }
            instrumentation.waitForIdleSync()
            fun tab(backwards: Boolean = false) {
                val now = android.os.SystemClock.uptimeMillis()
                val modifiers = if (backwards) KeyEvent.META_SHIFT_ON else 0
                instrumentation.sendKeySync(KeyEvent(now, now, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_TAB, 0, modifiers))
                instrumentation.sendKeySync(KeyEvent(now, now, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_TAB, 0, modifiers))
                instrumentation.waitForIdleSync()
            }
            fun awaitFocus(view: View) {
                val deadline = android.os.SystemClock.uptimeMillis() + 5000
                while (android.os.SystemClock.uptimeMillis() < deadline) {
                    val focused = java.util.concurrent.atomic.AtomicBoolean()
                    instrumentation.runOnMainSync { focused.set(view.hasFocus()) }
                    if (focused.get()) return
                    android.os.SystemClock.sleep(20)
                }
                instrumentation.runOnMainSync { fail("Expected focus on $view; actual=${activity.currentFocus}; keys=$keys") }
            }
            instrumentation.runOnMainSync {
                assertTrue(host.requestFocus(View.FOCUS_FORWARD))
                assertEquals("First", host.semantics.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)?.text)
            }
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_DOWN)
            awaitFocus(field)
            instrumentation.runOnMainSync { field.setSelection(2) }
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_LEFT)
            val selectionDeadline = android.os.SystemClock.uptimeMillis() + 5000
            val selectionMoved = java.util.concurrent.atomic.AtomicBoolean()
            while (!selectionMoved.get() && android.os.SystemClock.uptimeMillis() < selectionDeadline) {
                instrumentation.runOnMainSync { selectionMoved.set(field.selectionStart == 1 && field.selectionEnd == 1) }
                if (!selectionMoved.get()) android.os.SystemClock.sleep(20)
            }
            assertTrue("Native editor must retain arrow-key cursor movement", selectionMoved.get())
            tab(true)
            awaitFocus(host)
            instrumentation.runOnMainSync { assertEquals("First", host.semantics.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)?.text) }
            tab()
            awaitFocus(field)
            instrumentation.runOnMainSync { assertTrue(field.hasFocus()); assertEquals("Ada", field.text.toString()) }
            tab()
            awaitFocus(host)
            instrumentation.runOnMainSync {
                assertTrue("focus=${activity.currentFocus}; keys=$keys", host.hasFocus())
                assertEquals("Last", host.semantics.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)?.text)
            }
            tab(true)
            awaitFocus(field)
            instrumentation.runOnMainSync { assertTrue(field.hasFocus()) }
            tab(true)
            awaitFocus(host)
            instrumentation.runOnMainSync { assertEquals("First", host.semantics.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)?.text) }
            tab(true)
            awaitFocus(before)
            instrumentation.runOnMainSync { assertTrue(before.hasFocus()) }
            tab()
            awaitFocus(host)
            instrumentation.runOnMainSync { assertEquals("First", host.semantics.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)?.text) }
            tab()
            awaitFocus(field)
            tab()
            awaitFocus(host)
            tab()
            awaitFocus(after)
            instrumentation.runOnMainSync { assertTrue(after.hasFocus()) }
            tab(true)
            awaitFocus(host)
            instrumentation.runOnMainSync { assertEquals("Last", host.semantics.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)?.text) }
        } finally { instrumentation.runOnMainSync { activity.finish() } }
    }

    private fun node(id: Long, order: Int, role: Int, y: Float, label: String) = NativeSemanticNode(
        id, -1, order, role, 0, 0, 0, if (role == 6) 0 else 1,
        0f, y, 300f, y + 80f, label, "", "")
}
