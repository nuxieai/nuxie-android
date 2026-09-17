package ai.nuxie.sdk.presentation

import ai.nuxie.sdk.runtime.NativeSemanticModalScope
import ai.nuxie.sdk.runtime.NativeSemanticRole
import ai.nuxie.sdk.runtime.NativeSemanticTrait
import ai.nuxie.sdk.runtime.NativeSemanticState
import ai.nuxie.sdk.runtime.NativeSemanticNode
import ai.nuxie.sdk.runtime.NuxieSemanticTree
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityManager
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityNodeProvider

/** UI-thread adapter. Geometry and action ownership are supplied by the presenting host. */
internal class ExperienceAccessibilityProvider(
    private val host: View,
    private val bounds: (NativeSemanticNode) -> Bounds?,
    private val dispatch: (NuxieSemanticTree, Long, Int) -> Boolean,
) : AccessibilityNodeProvider() {
    data class Bounds(val inHost: Rect, val inScreen: Rect)

    private val index = ExperienceSemanticIndex()
    private val manager = host.context.getSystemService(AccessibilityManager::class.java)
    private var nativeViews: Map<Long, View> = emptyMap()
    private var nativeNodes: Map<Long, NativeSemanticNode> = emptyMap()
    private var virtualIds: Map<Long, Int> = emptyMap()
    private var traversalNeighbors: Map<Long, Pair<Long?, Long?>> = emptyMap()
    private var accessibilityFocus: Int? = null
    private var inputFocus: Int? = null
    private var requestingKeyboardTarget = false
    private var hovered: Int? = null
    private data class SavedFocus(val nodeId: Long, val position: Int, val root: ExperienceFocusRoot, val revision: Long)
    private var savedAccessibilityFocus: SavedFocus? = null
    private var lastOwnedAccessibilityFocus: SavedFocus? = null
    private var savedInputFocus: SavedFocus? = null
    private data class ModalFrame(val nodeId: Long, val returnId: Long?)
    private var modalFrames: List<ModalFrame> = emptyList()
    private val excludedNativeImportance = mutableMapOf<View, Int>()
    private val keyboardIndicator = ExperienceKeyboardFocusDrawable(host.resources.displayMetrics.density)

    fun publish(tree: NuxieSemanticTree, nativeFields: Map<Long, View> = emptyMap()) {
        val oldTree = index.tree
        val root = ExperienceFocusRoot.containing(host)
        val ownedFocus = nativeViews.entries.firstOrNull { it.value.isAccessibilityFocused }?.key?.let { id ->
            root?.let { SavedFocus(id, index.readingOrder.indexOf(id), it, it.accessibilityRevision) }
        } ?: listOfNotNull(lastOwnedAccessibilityFocus, savedAccessibilityFocus).firstOrNull {
            root === it.root && root.accessibilityRevision == it.revision
        }
        index.update(tree, nativeFields.keys)
        updateExcludedNativeFields(nativeFields, index.readingOrder.toSet())
        nativeNodes = tree.nodes.filter { it.id in nativeFields }.associateBy { it.id }
        virtualIds = index.entries.values.associate { it.node.id to it.virtualId }
        nativeViews.values.filter { it !in nativeFields.values }.forEach { it.accessibilityDelegate = null }
        for ((id, view) in nativeFields) if (nativeViews[id] !== view) {
            view.accessibilityDelegate = object : View.AccessibilityDelegate() {
                override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfo) {
                    super.onInitializeAccessibilityNodeInfo(host, info)
                    applyTraversal(info, id)
                    nativeNodes[id]?.let { ExperienceAccessibilityStateDescription.apply(info, it, host.resources, nativeEditor = true) }
                }
            }
        }
        nativeViews = nativeFields.toMap()
        traversalNeighbors = index.readingOrder.mapIndexed { position, id ->
            id to (index.readingOrder.getOrNull(position - 1) to index.readingOrder.getOrNull(position + 1))
        }.toMap()
        if (accessibilityFocus !in index.entries) clearAccessibilityFocus()
        if (inputFocus !in index.entries || inputFocus?.let { isKeyboardTarget(it) } == false) clearInputFocus()
        host.isFocusable = index.entries.keys.any(::isKeyboardTarget)
        refreshKeyboardIndicator()
        if (hovered !in index.entries) updateHover(null)
        if (oldTree?.nodes != tree.nodes || oldTree?.modalScope != tree.modalScope) {
            send(HOST_VIEW_ID, AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED)
        }
        val path = modalPath(tree)
        if (path != modalFrames.map { it.nodeId }) {
            val common = modalFrames.map { it.nodeId }.zip(path).takeWhile { it.first == it.second }.size
            val replacing = common < modalFrames.size
            val returnId = if (replacing) modalFrames[common].returnId else ownedFocus?.nodeId
            modalFrames = modalFrames.take(common) + path.drop(common).mapIndexed { index, id ->
                ModalFrame(id, returnId.takeIf { index == 0 })
            }
            val target = if (common < path.size) index.readingOrder.firstOrNull()
                else returnId?.takeIf { it in index.readingOrder } ?: index.readingOrder.firstOrNull()
            if (root != null && (ownedFocus != null || root.accessibilityRevision == 0L) && target != null) {
                savedAccessibilityFocus = SavedFocus(target, index.readingOrder.indexOf(target), root, root.accessibilityRevision)
            }
        }
        restoreFocus()
    }

    private fun modalPath(tree: NuxieSemanticTree): List<Long> {
        val active = when (val scope = tree.modalScope) {
            NativeSemanticModalScope.None -> return emptyList()
            NativeSemanticModalScope.Unresolved -> return modalFrames.map { it.nodeId }
            is NativeSemanticModalScope.Active -> scope.nodeId
        }
        val byId = tree.nodes.associateBy { it.id }
        val path = mutableListOf<Long>()
        var current = byId[active]
        while (current != null) {
            if (current.stateFlags and NativeSemanticState.MODAL != 0 &&
                current.role in setOf(NativeSemanticRole.DIALOG, NativeSemanticRole.ALERT_DIALOG)) path += current.id
            current = if (current.parentId == -1) null else byId[current.parentId.toLong() and 0xffff_ffffL]
        }
        return path.asReversed()
    }

    private fun updateExcludedNativeFields(fields: Map<Long, View>, allowed: Set<Long>) {
        val excluded = fields.filterKeys { it !in allowed }.values.toSet()
        for (view in excludedNativeImportance.keys.toList()) if (view !in excluded) {
            view.importantForAccessibility = excludedNativeImportance.remove(view)!!
        }
        for (view in excluded) {
            excludedNativeImportance.putIfAbsent(view, view.importantForAccessibility)
            view.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        }
    }

    fun invalidateState() {
        if (!host.isEnabled) clearInputFocus()
        send(HOST_VIEW_ID, AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED)
    }

    fun retire() {
        modalFrames = emptyList()
        lastOwnedAccessibilityFocus = null
        savedAccessibilityFocus = null
        savedInputFocus = null
        removeTree(preserveHostFocus = false)
    }

    /** Remove stale interaction targets while retaining focus in this artboard occurrence. */
    fun withdraw() {
        if (index.readingOrder.isNotEmpty()) {
            val root = ExperienceFocusRoot.containing(host)
            fun save(id: Long?, revision: Long): SavedFocus? =
                if (id != null && root != null) SavedFocus(id, index.readingOrder.indexOf(id), root, revision) else null
            val accessibilityId = nativeViews.entries.firstOrNull { it.value.isAccessibilityFocused }?.key
                ?: accessibilityFocus?.let { index.entries[it]?.node?.id }
            val inputId = nativeViews.entries.firstOrNull { it.value.hasFocus() }?.key
                ?: inputFocus?.let { index.entries[it]?.node?.id }
            // TalkBack can clear virtual focus before Activity.onStop withdraws
            // the tree. Keep its last owned target unless another control took focus.
            savedAccessibilityFocus = save(accessibilityId, root?.accessibilityRevision ?: 0)
                ?: lastOwnedAccessibilityFocus?.takeIf {
                    root === it.root && root.accessibilityRevision == it.revision
                }
            savedInputFocus = save(inputId, root?.inputRevision ?: 0)
        }
        removeTree(preserveHostFocus = true)
    }

    private fun restoreFocus() {
        if (!host.isShown || !host.isEnabled) return
        val root = ExperienceFocusRoot.containing(host)
        fun target(saved: SavedFocus, order: List<Long>): Long? =
            saved.nodeId.takeIf { it in order } ?: order.getOrNull(saved.position.coerceIn(0, (order.size - 1).coerceAtLeast(0)))
        savedAccessibilityFocus?.let { saved ->
            if (root == null || root !== saved.root || root.accessibilityRevision != saved.revision) {
                savedAccessibilityFocus = null
            } else {
                val id = target(saved, index.readingOrder)
                val accepted = id?.let { nativeViews[it]?.performAccessibilityAction(AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS, null)
                    ?: virtualIds[it]?.let { virtual -> performAction(virtual, AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS, null) } } == true
                if (accepted) savedAccessibilityFocus = null
            }
        }
        savedInputFocus?.let { saved ->
            if (root == null || root !== saved.root || root.inputRevision != saved.revision) {
                savedInputFocus = null
            } else if (target(saved, keyboardOrder())?.let { focusKeyboardTarget(it, View.FOCUS_FORWARD) } == true) {
                savedInputFocus = null
            }
        }
    }

    private fun removeTree(preserveHostFocus: Boolean) {
        updateExcludedNativeFields(emptyMap(), emptySet())
        clearAccessibilityFocus()
        updateHover(null)
        clearInputFocus()
        index.clearOccurrence()
        nativeViews.values.forEach { it.accessibilityDelegate = null }
        nativeViews = emptyMap()
        nativeNodes = emptyMap()
        traversalNeighbors = emptyMap()
        virtualIds = emptyMap()
        // A resize withdraws virtual targets without moving the host's keyboard
        // focus or resetting its focusable-in-touch-mode policy.
        if (!preserveHostFocus) host.isFocusable = false
        send(HOST_VIEW_ID, AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED)
    }

    override fun createAccessibilityNodeInfo(virtualViewId: Int): AccessibilityNodeInfo? {
        if (virtualViewId == HOST_VIEW_ID) {
            val info = AccessibilityNodeInfo.obtain(host)
            host.onInitializeAccessibilityNodeInfo(info)
            index.children(null).forEach { info.addChild(host, it.virtualId) }
            return info
        }
        val entry = index.entries[virtualViewId] ?: return null
        val node = entry.node
        val geometry = bounds(node) ?: return null
        return AccessibilityNodeInfo.obtain().apply {
            setSource(host, virtualViewId)
            applyTraversal(this, node.id)
            if (entry.parent == null) setParent(host) else setParent(host, entry.parent)
            packageName = host.context.packageName
            className = when (node.role) {
                NativeSemanticRole.BUTTON -> "android.widget.Button"
                NativeSemanticRole.CHECKBOX -> "android.widget.CheckBox"
                NativeSemanticRole.SWITCH_CONTROL -> "android.widget.Switch"
                NativeSemanticRole.SLIDER -> "android.widget.SeekBar"
                NativeSemanticRole.TEXT, NativeSemanticRole.LINK -> "android.widget.TextView"
                NativeSemanticRole.IMAGE -> "android.widget.ImageView"
                NativeSemanticRole.LIST -> "android.widget.ListView"
                NativeSemanticRole.RADIO_GROUP -> "android.widget.RadioGroup"
                NativeSemanticRole.RADIO_BUTTON -> "android.widget.RadioButton"
                else -> "android.view.View"
            }
            text = node.label
            val obscured = node.stateFlags and NativeSemanticState.OBSCURED != 0
            isPassword = obscured
            if (Build.VERSION.SDK_INT >= 26) hintText = node.hint
            ExperienceAccessibilityStateDescription.apply(this, node, host.resources)
            if (Build.VERSION.SDK_INT < 26) {
                contentDescription = listOf(node.label, node.hint).filter(String::isNotEmpty).joinToString(", ")
            }
            if (Build.VERSION.SDK_INT >= 28) {
                isHeading = node.headingLevel > 0
            } else {
                // AccessibilityNodeInfoCompat heading protocol for API 19–27.
                // https://github.com/androidx/androidx/blob/androidx-main/core/core/src/main/java/androidx/core/view/accessibility/AccessibilityNodeInfoCompat.java
                val key = "androidx.view.accessibility.AccessibilityNodeInfoCompat.BOOLEAN_PROPERTY_KEY"
                val headingFlag = 0x2
                val flags = extras.getInt(key, 0) and headingFlag.inv()
                extras.putInt(key, flags or if (node.headingLevel > 0) headingFlag else 0)
            }
            isEnabled = host.isEnabled && node.stateFlags and NativeSemanticState.DISABLED == 0
            isSelected = node.stateFlags and NativeSemanticState.SELECTED != 0
            isCheckable = ExperienceAccessibilityStateDescription.isCheckable(node)
            val mixed = node.stateFlags and NativeSemanticState.MIXED != 0
            val checked = node.stateFlags and (NativeSemanticState.CHECKED or NativeSemanticState.TOGGLED) != 0
            if (Build.VERSION.SDK_INT >= 36) {
                setChecked(when {
                    !isCheckable -> AccessibilityNodeInfo.CHECKED_STATE_FALSE
                    mixed -> AccessibilityNodeInfo.CHECKED_STATE_PARTIAL
                    checked -> AccessibilityNodeInfo.CHECKED_STATE_TRUE
                    else -> AccessibilityNodeInfo.CHECKED_STATE_FALSE
                })
                expandedState = when {
                    node.traitFlags and NativeSemanticTrait.EXPANDABLE == 0 -> AccessibilityNodeInfo.EXPANDED_STATE_UNDEFINED
                    node.stateFlags and NativeSemanticState.EXPANDED != 0 -> AccessibilityNodeInfo.EXPANDED_STATE_FULL
                    else -> AccessibilityNodeInfo.EXPANDED_STATE_COLLAPSED
                }
            } else {
                isChecked = isCheckable && checked && !mixed
            }
            isVisibleToUser = host.isShown && !geometry.inScreen.isEmpty
            isFocusable = true
            isFocused = inputFocus == virtualViewId
            isAccessibilityFocused = accessibilityFocus == virtualViewId
            setBoundsInScreen(geometry.inScreen)
            val parentBounds = entry.parent?.let { index.entries[it]?.node }?.let(bounds)?.inHost
            setBoundsInParent(Rect(geometry.inHost).apply {
                if (parentBounds != null) offset(-parentBounds.left, -parentBounds.top)
            })
            index.children(virtualViewId).forEach { addChild(host, it.virtualId) }
            addAction(if (isAccessibilityFocused) AccessibilityNodeInfo.ACTION_CLEAR_ACCESSIBILITY_FOCUS
                else AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS)
            addAction(if (isFocused) AccessibilityNodeInfo.ACTION_CLEAR_FOCUS else AccessibilityNodeInfo.ACTION_FOCUS)
            if (isEnabled) {
                isClickable = node.actions and 1 != 0
                if (isClickable) addAction(AccessibilityNodeInfo.ACTION_CLICK)
                if (node.actions and 2 != 0) addAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
                if (node.actions and 4 != 0) addAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)
            }
        }
    }

    private fun applyTraversal(info: AccessibilityNodeInfo, id: Long) {
        val neighbors = traversalNeighbors[id] ?: return
        fun target(id: Long?): Pair<View, Int>? {
            if (id == null) return null
            nativeViews[id]?.let { return it to HOST_VIEW_ID }
            return virtualIds[id]?.let { host to it }
        }
        target(neighbors.first)?.let { (view, virtualId) -> info.setTraversalAfter(view, virtualId) }
        target(neighbors.second)?.let { (view, virtualId) -> info.setTraversalBefore(view, virtualId) }
    }

    override fun findAccessibilityNodeInfosByText(text: String, virtualViewId: Int): List<AccessibilityNodeInfo> {
        val pending = ArrayDeque<Int>()
        if (virtualViewId == HOST_VIEW_ID) index.children(null).forEach { pending.addLast(it.virtualId) }
        else if (virtualViewId in index.entries) pending.addLast(virtualViewId)
        val matches = mutableListOf<AccessibilityNodeInfo>()
        while (pending.isNotEmpty()) {
            val id = pending.removeFirst()
            val node = index.entries.getValue(id).node
            if (node.label.contains(text, ignoreCase = true)) createAccessibilityNodeInfo(id)?.let(matches::add)
            index.children(id).forEach { pending.addLast(it.virtualId) }
        }
        return matches
    }

    override fun findFocus(focus: Int): AccessibilityNodeInfo? = when (focus) {
        AccessibilityNodeInfo.FOCUS_ACCESSIBILITY -> accessibilityFocus
        AccessibilityNodeInfo.FOCUS_INPUT -> inputFocus
        else -> null
    }?.let(::createAccessibilityNodeInfo)

    override fun performAction(virtualViewId: Int, action: Int, arguments: Bundle?): Boolean {
        if (virtualViewId == HOST_VIEW_ID) return host.performAccessibilityAction(action, arguments)
        val entry = index.entries[virtualViewId] ?: return false
        if (action == AccessibilityNodeInfo.ACTION_CLEAR_ACCESSIBILITY_FOCUS) {
            if (accessibilityFocus != virtualViewId) return false
            clearAccessibilityFocus()
            return true
        }
        if (!host.isShown || bounds(entry.node)?.inScreen?.isEmpty != false) return false
        when (action) {
            AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS -> {
                if (!manager.isEnabled || accessibilityFocus == virtualViewId) return false
                clearAccessibilityFocus()
                accessibilityFocus = virtualViewId
                send(virtualViewId, AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUSED)
                lastOwnedAccessibilityFocus = ExperienceFocusRoot.containing(host)?.let { root ->
                    SavedFocus(entry.node.id, index.readingOrder.indexOf(entry.node.id), root, root.accessibilityRevision)
                }
                host.invalidate()
                return true
            }
            AccessibilityNodeInfo.ACTION_FOCUS -> {
                if (!host.isEnabled || entry.node.stateFlags and NativeSemanticState.DISABLED != 0 ||
                    inputFocus == virtualViewId || !host.requestFocus()) return false
                inputFocus = virtualViewId
                refreshKeyboardIndicator()
                send(virtualViewId, AccessibilityEvent.TYPE_VIEW_FOCUSED)
                return true
            }
            AccessibilityNodeInfo.ACTION_CLEAR_FOCUS -> {
                if (inputFocus != virtualViewId) return false
                clearInputFocus()
                return true
            }
        }
        if (!host.isEnabled || entry.node.stateFlags and NativeSemanticState.DISABLED != 0) return false
        val nativeAction = when (action) {
            AccessibilityNodeInfo.ACTION_CLICK -> 0
            AccessibilityNodeInfo.ACTION_SCROLL_FORWARD -> 1
            AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD -> 2
            else -> return false
        }
        if (entry.node.actions and (1 shl nativeAction) == 0) return false
        val tree = index.tree ?: return false
        return dispatch(tree, entry.node.id, nativeAction)
    }

    /** Return false at either edge so Android can move focus to a native sibling. */
    fun key(event: KeyEvent): Boolean {
        if (!host.isEnabled || event.action != KeyEvent.ACTION_DOWN) return false
        val nativeFocus = nativeViews.entries.firstOrNull { it.value.hasFocus() }?.key
        if (!host.hasFocus() && nativeFocus == null) return false
        if (event.keyCode == KeyEvent.KEYCODE_TAB &&
            (event.hasNoModifiers() || event.hasModifiers(KeyEvent.META_SHIFT_ON))) {
            val order = keyboardOrder()
            val backwards = event.hasModifiers(KeyEvent.META_SHIFT_ON)
            val current = order.indexOf(nativeFocus ?: inputFocus?.let { index.entries[it]?.node?.id })
            val next = if (current < 0) {
                if (backwards) order.lastOrNull() else order.firstOrNull()
            } else order.getOrNull(current + if (backwards) -1 else 1)
            val direction = if (backwards) View.FOCUS_BACKWARD else View.FOCUS_FORWARD
            return next?.let { focusKeyboardTarget(it, direction) }
                ?: if (nativeViews.isNotEmpty()) leaveKeyboardGroup(direction) else false
        }
        if (!host.hasFocus() || !event.hasNoModifiers()) return false
        val target = inputFocus ?: return false
        val direction = when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT -> View.FOCUS_LEFT
            KeyEvent.KEYCODE_DPAD_RIGHT -> View.FOCUS_RIGHT
            KeyEvent.KEYCODE_DPAD_UP -> View.FOCUS_UP
            KeyEvent.KEYCODE_DPAD_DOWN -> View.FOCUS_DOWN
            else -> null
        }
        if (direction != null) {
            val node = index.entries[target]?.node ?: return false
            if (node.role == NativeSemanticRole.SLIDER && direction in listOf(View.FOCUS_LEFT, View.FOCUS_RIGHT)) {
                val increase = (direction == View.FOCUS_RIGHT) != (host.layoutDirection == View.LAYOUT_DIRECTION_RTL)
                val action = if (increase) AccessibilityNodeInfo.ACTION_SCROLL_FORWARD else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
                return performAction(target, action, null)
            }
            val source = keyboardBounds(node.id) ?: return false
            val candidates = keyboardOrder().filter { it != node.id }.mapNotNull { id -> keyboardBounds(id)?.let { id to it } }
            val next = ExperienceDirectionalFocus.next(source, direction, candidates)
            return next?.let { focusKeyboardTarget(it, direction) }
                ?: if (nativeViews.isNotEmpty()) leaveKeyboardGroup(direction) else false
        }
        if (event.repeatCount != 0) return false
        val action = when (event.keyCode) {
            KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_SPACE ->
                AccessibilityNodeInfo.ACTION_CLICK
            else -> return false
        }
        return performAction(target, action, null)
    }

    fun clearInputFocus() {
        if (inputFocus == null) return
        inputFocus = null
        refreshKeyboardIndicator()
    }

    fun hostFocusChanged(gained: Boolean, direction: Int) {
        if (!gained) {
            clearInputFocus()
            return
        }
        if (inputFocus != null || requestingKeyboardTarget) return
        val order = keyboardOrder()
        val target = if (direction == View.FOCUS_BACKWARD) order.lastOrNull() else order.firstOrNull()
        target?.let { focusKeyboardTarget(it, direction) }
    }

    fun keyboardEntry(direction: Int): View? {
        val order = keyboardOrder()
        val id = (if (direction == View.FOCUS_BACKWARD) order.lastOrNull() else order.firstOrNull()) ?: return null
        return nativeViews[id] ?: host
    }

    private fun keyboardBounds(id: Long): Rect? {
        nativeViews[id]?.let { view ->
            val rect = Rect()
            if (!view.getGlobalVisibleRect(rect)) return null
            val origin = IntArray(2)
            view.rootView.getLocationOnScreen(origin)
            rect.offset(origin[0], origin[1])
            return rect
        }
        return virtualIds[id]?.let { index.entries[it]?.node }?.let(bounds)?.inScreen
    }

    private fun keyboardOrder(): List<Long> = index.readingOrder.filter { id ->
        val native = nativeViews[id]
        if (native != null) native.isShown && native.isEnabled && native.isFocusable
        else virtualIds[id]?.let(::isKeyboardTarget) == true
    }

    private fun focusKeyboardTarget(id: Long, direction: Int): Boolean {
        requestingKeyboardTarget = true
        try {
            nativeViews[id]?.let { view ->
                if (!view.requestFocus(direction)) return false
                clearInputFocus()
                return true
            }
            val virtualId = virtualIds[id] ?: return false
            return performAction(virtualId, AccessibilityNodeInfo.ACTION_FOCUS, null)
        } finally { requestingKeyboardTarget = false }
    }

    private fun leaveKeyboardGroup(direction: Int): Boolean {
        var current = nativeViews.values.firstOrNull { it.hasFocus() } ?: host
        val visited = mutableSetOf<View>()
        // Android sees the surface as one focus stop. Skip the group's native
        // fields when leaving its final virtual control rather than cycling back.
        val owned = mutableSetOf<View>(host)
        for (field in nativeViews.values) {
            var view: View? = field
            while (view != null) { owned += view; view = view.parent as? View }
        }
        while (visited.add(current)) {
            val next = current.focusSearch(direction) ?: return true
            if (next !in owned && next.requestFocus(direction)) {
                clearInputFocus()
                return true
            }
            current = next
        }
        return true
    }

    private fun refreshKeyboardIndicator() {
        val rect = inputFocus?.let { index.entries[it]?.node }?.let(bounds)?.inHost
        if (rect == null || rect.isEmpty || !host.hasFocus() || !host.isEnabled) {
            host.overlay.remove(keyboardIndicator)
        } else {
            keyboardIndicator.bounds = rect
            host.overlay.add(keyboardIndicator)
            keyboardIndicator.invalidateSelf()
        }
        host.invalidate()
    }

    private fun isKeyboardTarget(id: Int): Boolean {
        val node = index.entries[id]?.node ?: return false
        return node.actions != 0 && node.stateFlags and NativeSemanticState.DISABLED == 0 &&
            bounds(node)?.inScreen?.isEmpty == false
    }

    fun hover(event: MotionEvent): Boolean {
        if (!manager.isEnabled || !manager.isTouchExplorationEnabled) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_HOVER_ENTER, MotionEvent.ACTION_HOVER_MOVE -> {
                // Prefer a child over its encompassing group. This only selects focus;
                // activation always uses the exact semantic identity, never a pointer tap.
                val target = index.entries.values.filter {
                    bounds(it.node)?.inHost?.contains(event.x.toInt(), event.y.toInt()) == true
                }.minByOrNull { entry ->
                    val rect = bounds(entry.node)!!.inHost
                    rect.width().toLong() * rect.height()
                }?.virtualId
                updateHover(target)
                return target != null
            }
            MotionEvent.ACTION_HOVER_EXIT -> {
                val hadHover = hovered != null
                updateHover(null)
                return hadHover
            }
        }
        return false
    }

    private fun clearAccessibilityFocus() {
        val previous = accessibilityFocus ?: return
        accessibilityFocus = null
        send(previous, AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUS_CLEARED)
        host.invalidate()
    }

    private fun updateHover(next: Int?) {
        if (hovered == next) return
        val previous = hovered
        hovered = next
        next?.let { send(it, AccessibilityEvent.TYPE_VIEW_HOVER_ENTER) }
        previous?.let { send(it, AccessibilityEvent.TYPE_VIEW_HOVER_EXIT) }
    }

    private fun send(id: Int, type: Int) {
        if (!manager.isEnabled) return
        val event = AccessibilityEvent.obtain(type)
        event.packageName = host.context.packageName
        // Native editors are siblings of the renderer; their traversal metadata shares this update.
        val source = if (type == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED && id == HOST_VIEW_ID)
            host.parent as? View ?: host else host
        if (source === host) event.setSource(host, id) else event.setSource(source)
        if (type == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) {
            event.contentChangeTypes = AccessibilityEvent.CONTENT_CHANGE_TYPE_SUBTREE
        }
        source.parent?.requestSendAccessibilityEvent(source, event)
    }
}
