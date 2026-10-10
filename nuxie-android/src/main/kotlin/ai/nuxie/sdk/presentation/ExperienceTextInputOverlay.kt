package ai.nuxie.sdk.presentation

import ai.nuxie.sdk.runtime.NuxieTextRunGeometry
import ai.nuxie.sdk.runtime.NativeSemanticNode
import ai.nuxie.sdk.runtime.NativeSemanticState
import android.view.accessibility.AccessibilityNodeInfo
import android.content.Context
import android.graphics.Color
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import ai.nuxie.sdk.experiences.SystemFontProvider
import ai.nuxie.sdk.experiences.SystemFontRequirement
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.text.Editable
import android.text.InputFilter
import android.text.Spanned
import android.text.InputType
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.view.WindowInsets
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputConnectionWrapper
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import java.io.File
import java.util.Locale
import kotlin.math.roundToInt

/** Native editors share the renderer's layout coordinate space. UI-thread owned. */
internal class ExperienceTextInputOverlay(
    context: Context,
    private var artboardSize: ExperienceArtboardSize,
    inputs: List<ExperienceTextInput>,
    private val fonts: Map<String, File>,
    state: ExperienceTextInputState = ExperienceTextInputState(),
    private val nativeWriter: ((ExperienceTextFieldTarget, ExperienceSemanticTextDraft.Write,
        (ExperienceSemanticTextDraft.Outcome) -> Unit) -> Unit)? = null,
    private val nativeNotification: (ExperienceTextFieldTarget, String) -> Unit = { _, _ -> },
    private val nativeEvent: (ExperienceTextFieldTarget, Long, ExperienceSemanticTextDraft.Event) -> Unit = { _, _, _ -> },
    private val nativeFocus: ((ExperienceTextFieldTarget, Pair<Float, Float>?, (Boolean) -> Unit) -> Unit)? = null,
) : FrameLayout(context) {
    private class Binding(val input: ExperienceTextInput, val editor: Editor, val container: FrameLayout,
        var geometryAvailable: Boolean = true, var field: ExperienceNativeTextField,
        val draft: ExperienceSemanticTextDraft,
        var frozenSelection: Pair<Int, Int>? = null)
    private val bindings = mutableListOf<Binding>()
    private val nativeInputs = inputs.associateBy { it.id }
    private val session = state.bind()
    private var closed = false
    private var inputEnabled = true
    private var keyboardShift = 0f
    private val keyboardLayoutListener = ViewTreeObserver.OnGlobalLayoutListener { avoidKeyboard() }

    init {
        isFocusableInTouchMode = true
        // Focus parking is a keyboard concern; only the authored editor children are semantic controls.
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        setOnApplyWindowInsetsListener { _, insets ->
            post { if (!closed) avoidKeyboard() }
            insets
        }
    }

    /** Complete occurrence inventory from one presented frame; absent owners are retired. */
    fun updateNativeFields(fields: List<ExperienceNativeTextField>) {
        if (closed || !session.isCurrent()) return
        check(fields.map { it.target }.distinct().size == fields.size) { "Duplicate native input occurrence" }
        check(fields.map { it.target.nodeId }.distinct().size == fields.size) { "Multiple inputs name one native field" }
        fields.forEach { field ->
            val input = checkNotNull(nativeInputs[field.target.inputId]) { "Undeclared native input" }
            check(field.node.id == field.target.nodeId &&
                field.node.role == ai.nuxie.sdk.runtime.NativeSemanticRole.TEXT_FIELD &&
                field.geometry.obscured == input.secure) {
                "Native input presentation does not match its declaration"
            }
        }
        if (fields.isNotEmpty()) checkNotNull(nativeWriter) { "Native input writer is unavailable" }
        val incoming = fields.associateBy { it.target }
        bindings.filter { binding -> incoming[binding.field.target]?.ownerId != binding.field.ownerId }.toList().forEach { binding ->
            binding.editor.onChange = {}
            binding.editor.onReturn = null
            binding.draft.withdraw()
            binding.editor.isEnabled = false
            binding.editor.visibility = View.INVISIBLE
            binding.editor.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            if (binding.editor.hasFocus()) clearEditorFocus()
            removeView(binding.container)
            bindings.remove(binding)
        }
        for (field in fields) {
            val input = checkNotNull(nativeInputs[field.target.inputId]) { "Undeclared native input" }
            val binding = bindings.firstOrNull { it.field.target == field.target } ?: run {
                val editor = Editor(context, input.copy(value = field.text))
                editor.tag = "nuxie-text-input-${input.id}-${field.target.nodeId}"
                editor.setTextColor(Color.TRANSPARENT)
                editor.visibility = View.INVISIBLE
                val container = FrameLayout(context).apply {
                    importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
                    clipChildren = false
                    clipToPadding = false
                }
                container.addView(editor, LayoutParams(1, 1))
                addView(container, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
                Binding(input, editor, container, field = field,
                    draft = ExperienceSemanticTextDraft(field.text)).also { created ->
                    bindings += created
                    editor.onBeginEditing = { point ->
                        if (!closed && session.isCurrent() && inputEnabled && editor.isEnabled && created in bindings) {
                            val ownerId = created.field.ownerId
                            nativeFocus?.invoke(created.field.target, point) { accepted ->
                                if (!accepted && !closed && created in bindings && created.field.ownerId == ownerId && editor.hasFocus()) clearEditorFocus()
                            }
                        }
                    }
                    editor.onChange = { commit ->
                        if (!closed && session.isCurrent() && inputEnabled && editor.isEnabled && created in bindings) {
                            created.draft.replaceText(editor.text.toString(), editor.isComposingText())
                            created.draft.requestNotification()?.let { nativeNotification(field.target, it) }
                            if (commit) created.draft.requestEvent(ExperienceSemanticTextDraft.EventKind.EDITING_ENDED)
                                .forEach { nativeEvent(field.target, field.ownerId, it) }
                            flushNativeWrite(created)
                        }
                    }
                    editor.onReturn = {
                        if (!closed && session.isCurrent() && inputEnabled && editor.isEnabled && created in bindings) {
                            created.draft.requestEvent(ExperienceSemanticTextDraft.EventKind.RETURN)
                                .forEach { nativeEvent(field.target, field.ownerId, it) }
                        }
                    }
                }
            }
            binding.field = field
            binding.editor.semanticNode = field.node
            binding.editor.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
            binding.draft.present(field.captureId)
            if (binding.draft.receiveSource(field.text)) binding.editor.replacePresentedText(binding.draft.text)
            flushNativeWrite(binding)
        }
        layoutEditors()
    }

    private fun flushNativeWrite(binding: Binding) {
        if (closed || !inputEnabled || binding !in bindings) return
        val draft = binding.draft
        val field = binding.field
        val write = draft.takeWrite() ?: return
        checkNotNull(nativeWriter).invoke(field.target, write) { outcome ->
            if (!closed && session.isCurrent() && binding in bindings) {
                draft.finish(write, outcome)?.let { nativeNotification(field.target, it) }
                draft.takeReadyEvents().forEach { nativeEvent(field.target, field.ownerId, it) }
                if (outcome == ExperienceSemanticTextDraft.Outcome.REJECTED)
                    binding.editor.replacePresentedText(draft.text)
                flushNativeWrite(binding)
            }
        }
    }

    /** IME callbacks can outlive touch dispatch; fence them during native preparation too. */
    fun setInputEnabled(enabled: Boolean) {
        if (closed || inputEnabled == enabled) return
        if (!enabled) clearEditorFocus()
        inputEnabled = false
        bindings.forEach { binding ->
            val editor = binding.editor
            if (!enabled) binding.draft.let { draft ->
                binding.frozenSelection = editor.selectionStart to editor.selectionEnd
                draft.withdraw()
                editor.replacePresentedText(draft.text)
            }
            if (enabled) binding.draft.let { draft ->
                editor.replacePresentedText(draft.text)
                binding.frozenSelection?.let { (start, end) ->
                    editor.setSelection(start.coerceIn(0, editor.length()), end.coerceIn(0, editor.length()))
                }
                binding.frozenSelection = null
                binding.field.let { draft.present(it.captureId) }
            }
            val node = binding.field.node
            editor.isEnabled = enabled && binding.geometryAvailable && (node.stateFlags and NativeSemanticState.DISABLED == 0)
        }
        inputEnabled = enabled
    }

    fun semanticViews(): Map<Long, View> = bindings.mapNotNull { binding ->
        binding.editor.semanticNode?.let { it.id to binding.editor }
    }.toMap()

    fun close() {
        closed = true
        if (viewTreeObserver.isAlive) viewTreeObserver.removeOnGlobalLayoutListener(keyboardLayoutListener)
        applyKeyboardShift(0f)
        bindings.forEach { it.editor.onChange = {}; it.editor.onSelection = null; it.editor.onReturn = null; it.draft?.withdraw() }
        clearEditorFocus()
        removeAllViews()
        bindings.clear()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        viewTreeObserver.addOnGlobalLayoutListener(keyboardLayoutListener)
        requestApplyInsets()
    }

    override fun onDetachedFromWindow() {
        if (viewTreeObserver.isAlive) viewTreeObserver.removeOnGlobalLayoutListener(keyboardLayoutListener)
        applyKeyboardShift(0f)
        super.onDetachedFromWindow()
    }

    private fun avoidKeyboard() {
        if (closed) return
        val editor = findFocus() as? Editor
        val root = rootView as? ViewGroup ?: return
        if (editor == null || !editor.isShown) {
            applyKeyboardShift(0f)
            return
        }
        val origin = IntArray(2)
        root.getLocationOnScreen(origin)
        val keyboardTop = if (Build.VERSION.SDK_INT >= 30) {
            val insets = root.rootWindowInsets ?: return
            if (!insets.isVisible(WindowInsets.Type.ime())) {
                applyKeyboardShift(0f)
                return
            }
            origin[1] + root.height - insets.getInsets(WindowInsets.Type.ime()).bottom
        } else {
            val visible = Rect()
            root.getWindowVisibleDisplayFrame(visible)
            // Legacy fullscreen windows expose keyboard occlusion through the
            // visible frame; ignore ordinary status/navigation-bar differences.
            if (origin[1] + root.height - visible.bottom < 80 * resources.displayMetrics.density) {
                applyKeyboardShift(0f)
                return
            }
            visible.bottom
        }
        val bottom = transformedBottom(editor, root) ?: return
        applyKeyboardShift(requiredKeyboardShift(
            origin[1] + bottom, keyboardShift, keyboardTop.toFloat(),
            12 * resources.displayMetrics.density,
        ))
    }

    /** Includes authored translation/rotation and ancestor transforms without clipping. */
    private fun transformedBottom(view: View, root: View): Float? {
        val corners = floatArrayOf(0f, 0f, view.width.toFloat(), 0f,
            view.width.toFloat(), view.height.toFloat(), 0f, view.height.toFloat())
        var child = view
        while (child !== root) {
            child.matrix.mapPoints(corners)
            val parent = child.parent as? View ?: return null
            for (offset in corners.indices step 2) {
                corners[offset] += child.left - parent.scrollX
                corners[offset + 1] += child.top - parent.scrollY
            }
            child = parent
        }
        return maxOf(corners[1], corners[3], corners[5], corners[7])
    }

    private fun applyKeyboardShift(value: Float) {
        if (keyboardShift == value) return
        keyboardShift = value
        // The common content parent contains both the SurfaceView and editors,
        // so rendering and pointer projection retain their shared coordinates.
        (parent as? View)?.translationY = -value
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        // The parent's measure pass has already measured children at this
        // point. Apply the new geometry after layout so its new sizes trigger
        // another measure rather than retaining the initial 1px bounds.
        post { if (!closed) layoutEditors() }
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            val editor = findFocus() as? Editor
            if (editor != null) {
                val bounds = Rect()
                editor.getGlobalVisibleRect(bounds)
                if (!bounds.contains(event.rawX.toInt(), event.rawY.toInt())) clearEditorFocus()
            }
        }
        return super.dispatchTouchEvent(event)
    }

    private fun clearEditorFocus() {
        (findFocus() as? Editor)?.let { editor ->
            editor.clearFocus()
            (context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
                .hideSoftInputFromWindow(editor.windowToken, 0)
        }
    }

    fun updateLayoutBounds(bounds: ExperienceArtboardSize) {
        artboardSize = bounds
        layoutEditors()
    }

    private fun layoutEditors() {
        val transform = ExperienceLayoutTransform.create(artboardSize, width.toFloat(), height.toFloat(),
            resources.displayMetrics.density) ?: return
        val scale = transform.scale
        val left = transform.contentLeft
        val top = transform.contentTop
        for (binding in bindings) {
            val input = binding.input
            val editor = binding.editor
            val container = binding.container
            val nativeField = binding.field
            val snapshot = nativeField.snapshot
            val metrics = input.effectiveMetrics(snapshot)
            val field = nativeField.geometry.let { native ->
                NuxieTextRunGeometry(native.renderRevision, native.worldTransform, native.worldTransform,
                    native.textBounds, native.layout, native.firstBaseline)
            }
            binding.geometryAvailable = field != null && metrics != null && placeCapturedField(input, metrics, editor, container, field, scale, left, top)
            val node = nativeField.node
            val enabled = binding.geometryAvailable && inputEnabled &&
                (node.stateFlags and NativeSemanticState.DISABLED == 0)
            if (enabled && !editor.isEnabled) binding.draft.let { editor.replacePresentedText(it.text) }
            editor.isEnabled = enabled
            if (!binding.geometryAvailable) {
                // Fence late IME callbacks before clearing focus on a missing field.
                if (editor.hasFocus()) clearEditorFocus()
                editor.visibility = View.INVISIBLE
            }
        }
        avoidKeyboard()
    }

    private fun placeCapturedField(
        input: ExperienceTextInput, metrics: ExperienceTextInput.EffectiveMetrics, editor: Editor, container: FrameLayout,
        field: NuxieTextRunGeometry, scale: Float, left: Float, top: Float,
    ): Boolean {
        val layout = field.layout ?: return false
        val bounds = layout.bounds
        val w = (bounds.maxX - bounds.minX) * scale
        val h = (bounds.maxY - bounds.minY) * scale
        if (!w.isFinite() || !h.isFinite() || w <= 0 || h <= 0 || w >= Int.MAX_VALUE || h >= Int.MAX_VALUE) return false
        val targetWidth = w.roundToInt().coerceAtLeast(1)
        val targetHeight = h.roundToInt().coerceAtLeast(1)
        val t = layout.transform
        // Account for integer native bounds without changing the authored field corners.
        val transform = NuxieTextRunGeometry.Transform(
            t.a * w / targetWidth, t.b * w / targetWidth,
            t.c * h / targetHeight, t.d * h / targetHeight,
            left + scale * (t.tx + t.a * bounds.minX + t.c * bounds.minY),
            top + scale * (t.ty + t.b * bounds.minX + t.d * bounds.minY),
        )
        val placement = ExperienceAffinePlacement.resolve(transform, targetWidth, targetHeight) ?: return false
        val params = editor.layoutParams
        if (params.width != targetWidth || params.height != targetHeight) {
            params.width = targetWidth
            params.height = targetHeight
            editor.layoutParams = params
        }
        placement.apply(container, editor)
        val content = field.contentTransform
        val determinant = t.a.toDouble() * t.d - t.b.toDouble() * t.c
        fun localBaseline(y: Float): Float? {
            val worldX = content.tx.toDouble() + content.c.toDouble() * y
            val worldY = content.ty.toDouble() + content.d.toDouble() * y
            val localY = (-t.b * (worldX - t.tx) + t.a * (worldY - t.ty)) / determinant
            return ((localY - bounds.minY) * targetHeight / (bounds.maxY - bounds.minY)).toFloat()
                .takeIf { value -> value.isFinite() && value > -Int.MAX_VALUE && value < Int.MAX_VALUE }
        }
        editor.presentedTextOriginY = localBaseline(0f) ?: return false
        // Blanking the bound runtime run during native editing removes its shaped
        // baseline. Keep that baseline while typography matches, then transform it
        // with the current field geometry so density and layout changes stay current.
        editor.capturedFirstBaseline = field.firstBaseline?.let { metrics to it }
            ?: editor.capturedFirstBaseline?.takeIf { it.first == metrics }
        editor.presentedFirstBaseline = editor.capturedFirstBaseline?.second?.let(::localBaseline)
        editor.setTextSize(TypedValue.COMPLEX_UNIT_PX, metrics.fontSize * scale)
        editor.letterSpacing = input.style.letterSpacing * scale / editor.textSize
        val extraLineSpacing = if (metrics.lineHeight == -1f) 0f
            else metrics.lineHeight * scale - editor.paint.getFontMetricsInt(null)
        editor.setLineSpacing(extraLineSpacing, 1f)
        editor.visibility = View.VISIBLE
        return true
    }

    private inner class Editor(context: Context, private val input: ExperienceTextInput) : EditText(context) {
        var semanticNode: NativeSemanticNode? = null
        var capturedFirstBaseline: Pair<ExperienceTextInput.EffectiveMetrics, Float>? = null
        var presentedTextOriginY: Float = 0f
            set(value) {
                if (field == value) return
                field = value
                requestLayout()
            }
        var presentedFirstBaseline: Float? = null
            set(value) {
                if (field == value) return
                field = value
                requestLayout()
            }

        private val composingPaint = Paint().apply { color = input.style.color }
        private val composingPath = Path()

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val value = text ?: return
            val start = BaseInputConnection.getComposingSpanStart(value)
            val end = BaseInputConnection.getComposingSpanEnd(value)
            val textLayout = layout ?: return
            if (start < 0 || end <= start || end > value.length) return
            val saved = canvas.save()
            try {
                // Match TextView's content coordinates and viewport. Layout supplies
                // the shaped selection path, including wrapping and bidirectional runs.
                canvas.clipRect(compoundPaddingLeft + scrollX, extendedPaddingTop + scrollY,
                    width - compoundPaddingRight + scrollX, height - extendedPaddingBottom + scrollY)
                canvas.translate(compoundPaddingLeft.toFloat(), extendedPaddingTop.toFloat())
                composingPaint.strokeWidth = resources.displayMetrics.density.coerceAtLeast(1f)
                for (line in textLayout.getLineForOffset(start)..textLayout.getLineForOffset(end - 1)) {
                    val lineStart = maxOf(start, textLayout.getLineStart(line))
                    var lineEnd = minOf(end, textLayout.getLineEnd(line))
                    if (lineEnd > lineStart && value[lineEnd - 1] == '\n') lineEnd -= 1
                    if (lineEnd <= lineStart) continue
                    composingPath.reset()
                    textLayout.getSelectionPath(lineStart, lineEnd, composingPath)
                    val lineSave = canvas.save()
                    try {
                        canvas.clipPath(composingPath)
                        val y = textLayout.getLineBaseline(line) + paint.fontMetrics.descent / 2f
                        canvas.drawLine(0f, y, textLayout.width.toFloat(), y, composingPaint)
                    } finally { canvas.restoreToCount(lineSave) }
                }
            } finally { canvas.restoreToCount(saved) }
        }

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            super.onMeasure(widthMeasureSpec, heightMeasureSpec)
            // TextView's baseline includes top padding. Measure the actual native layout,
            // then align that baseline without moving or resizing the published field box.
            val desired = presentedFirstBaseline
            // A field without a captured baseline still has a valid text origin.
            val top = if (desired == null) presentedTextOriginY.roundToInt()
                else (desired - (baseline - paddingTop)).roundToInt()
            if (paddingTop != top) {
                setPadding(paddingLeft, top, paddingRight, paddingBottom)
                super.onMeasure(widthMeasureSpec, heightMeasureSpec)
            }
        }

        override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
            super.onInitializeAccessibilityNodeInfo(info)
            if (Build.VERSION.SDK_INT < 26 && isEnabled) {
                info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_TEXT)
            }
            semanticNode?.let { node ->
                // Preserve EditText's value, selection and edit actions. Its label is a hint,
                // never a content description that replaces native editing semantics.
                val authoredHint = listOf(node.label, node.hint)
                    .filter(String::isNotEmpty).joinToString(", ")
                if (Build.VERSION.SDK_INT >= 26) info.hintText = authoredHint
                else {
                    // AccessibilityNodeInfoCompat's pre-26 wire contract. Preserve
                    // the real EditText value/actions without an AndroidX runtime dependency.
                    info.extras.putCharSequence(
                        "androidx.view.accessibility.AccessibilityNodeInfoCompat.HINT_TEXT_KEY", authoredHint)
                }
                info.isPassword = input.secure
                info.isEnabled = isEnabled
            }
        }

        override fun performAccessibilityAction(action: Int, arguments: Bundle?): Boolean {
            if (action == AccessibilityNodeInfo.ACTION_SET_TEXT) {
                if (!isEnabled || closed || !inputEnabled) return false
                val replacement = arguments?.getCharSequence(
                    AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE)?.toString().orEmpty()
                // TextView.setText applies filters against an empty destination. Reject
                // an over-limit whole-value replacement before it can erase a valid draft.
                if (!ExperienceTextInputLimit.fits(replacement, input.maxLength)) return false
                setText(replacement)
                setSelection(text?.length ?: 0)
                return true
            }
            return super.performAccessibilityAction(action, arguments)
        }

        var onChange: (Boolean) -> Unit = {}
        var onBeginEditing: ((Pair<Float, Float>?) -> Unit)? = null
        private var editingTouch: Pair<Float, Float>? = null

        override fun onTouchEvent(event: MotionEvent): Boolean {
            if (event.actionMasked == MotionEvent.ACTION_DOWN) editingTouch = event.rawX to event.rawY
            val handled = super.onTouchEvent(event)
            if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) editingTouch = null
            return handled
        }
        var onSelection: (() -> Unit)? = null
        var onReturn: (() -> Unit)? = null
        private var normalizing = false
        private var committingComposition = false

        init {
            background = null
            isSaveEnabled = false
            setPadding(0, 0, 0, 0)
            includeFontPadding = false
            setSingleLine(!input.multiline || input.secure)
            inputType = keyboardType(input)
            imeOptions = EditorInfo.IME_FLAG_NO_EXTRACT_UI or
                if (input.multiline && !input.secure) EditorInfo.IME_ACTION_NONE else EditorInfo.IME_ACTION_DONE
            hint = input.placeholder
            setTextColor(Color.TRANSPARENT)
            setHintTextColor(input.style.color)
            gravity = Gravity.TOP or when (input.style.textAlign?.lowercase(Locale.ROOT)) {
                "center" -> Gravity.CENTER_HORIZONTAL
                "right", "end" -> Gravity.END
                else -> Gravity.START
            }
            typeface = if (input.style.fontFamily == "System") {
                SystemFontProvider.typeface(SystemFontRequirement(
                    input.style.fontAssetName, input.style.fontWeight.toIntOrNull() ?: 0,
                    if (input.style.italic) "italic" else "normal",
                ))
            } else fonts[input.style.fontAssetName]?.let { runCatching { Typeface.createFromFile(it) }.getOrNull() }
                ?: Typeface.create(input.style.fontFamily, when {
                    input.style.italic && (input.style.fontWeight.toIntOrNull() ?: 400) >= 600 -> Typeface.BOLD_ITALIC
                    input.style.italic -> Typeface.ITALIC
                    (input.style.fontWeight.toIntOrNull() ?: 400) >= 600 -> Typeface.BOLD
                    else -> Typeface.NORMAL
                })
            if (Build.VERSION.SDK_INT >= 26) importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
            setText(input.value)
            limitText()
            filters = arrayOf(InputFilter { source, start, end, destination, replaceStart, replaceEnd ->
                val composing = hasComposingSpan(source) || hasComposingSpan(destination)
                if (composing || committingComposition) return@InputFilter null
                val candidate = destination.substring(0, replaceStart) + source.subSequence(start, end) +
                    destination.substring(replaceEnd)
                if (ExperienceTextInputLimit.fits(candidate, input.maxLength)) null
                else destination.subSequence(replaceStart, replaceEnd).toString()
            })
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
                override fun afterTextChanged(value: Editable?) {
                    if (!normalizing) {
                        if (value != null && BaseInputConnection.getComposingSpanStart(value) < 0) limitText()
                        onChange(false)
                    }
                }
            })
            onFocusChangeListener = OnFocusChangeListener { _, focused ->
                if (focused) {
                    val touch = editingTouch
                    editingTouch = null
                    onBeginEditing?.invoke(touch)
                }
                if (!focused) {
                    limitText()
                    BaseInputConnection.removeComposingSpans(text)
                }
                onChange(!focused)
                post { if (!closed) avoidKeyboard() }
            }
            setOnEditorActionListener { _, action, _ ->
                if (action == EditorInfo.IME_ACTION_DONE) {
                    onReturn?.invoke()
                    clearEditorFocus()
                    true
                } else false
            }
        }

        override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection? {
            val connection = super.onCreateInputConnection(outAttrs) ?: return null
            return object : InputConnectionWrapper(connection, false) {
                override fun finishComposingText(): Boolean {
                    limitText()
                    val accepted = super.finishComposingText()
                    onChange(false)
                    return accepted
                }
                override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
                    val current = this@Editor.text
                    val start = BaseInputConnection.getComposingSpanStart(current)
                    val end = BaseInputConnection.getComposingSpanEnd(current)
                    committingComposition = start >= 0 && end >= start
                    val replacement = if (committingComposition) {
                        ExperienceTextInputLimit.composingReplacement(current.toString(), start, end,
                            text?.toString().orEmpty(), input.maxLength)
                    } else text
                    val accepted = try { super.commitText(replacement, newCursorPosition) }
                    finally { committingComposition = false }
                    limitText()
                    onChange(false)
                    return accepted
                }
            }
        }

        override fun onSelectionChanged(selStart: Int, selEnd: Int) {
            super.onSelectionChanged(selStart, selEnd)
            onSelection?.invoke()
        }

        fun isEditingText(): Boolean = hasFocus() || hasComposingSpan(text)

        fun isComposingText(): Boolean = hasComposingSpan(text)

        fun replacePresentedText(value: String) {
            if (text.toString() == value) return
            normalizing = true
            try {
                setText(value)
                setSelection(text?.length ?: 0)
            } finally { normalizing = false }
        }

        private fun hasComposingSpan(value: CharSequence): Boolean = value is Spanned &&
            value.getSpans(0, value.length, Any::class.java).any {
                value.getSpanFlags(it) and Spanned.SPAN_COMPOSING != 0
            }

        private fun limitText() {
            val maximum = input.maxLength ?: return
            val editable = text ?: return
            val composingStart = BaseInputConnection.getComposingSpanStart(editable)
            val composingEnd = BaseInputConnection.getComposingSpanEnd(editable)
            if (composingStart >= 0 && composingEnd >= composingStart) {
                val current = editable.toString()
                val replacement = current.substring(composingStart, composingEnd)
                val limited = ExperienceTextInputLimit.composingReplacement(current, composingStart,
                    composingEnd, replacement, maximum)
                if (limited != replacement) {
                    normalizing = true
                    try { editable.replace(composingStart, composingEnd, limited) }
                    finally { normalizing = false }
                }
                return
            }
            val end = ExperienceTextInputLimit.apply(editable.toString(), maximum).length
            if (end < editable.length) {
                normalizing = true
                try { editable.delete(end, editable.length) } finally { normalizing = false }
            }
        }
    }

    private fun keyboardType(input: ExperienceTextInput): Int {
        if (input.secure) return InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        val type = when (input.keyboardType?.lowercase(Locale.ROOT)) {
            "email", "email-address" -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
            "number", "number-pad", "numeric" -> InputType.TYPE_CLASS_NUMBER
            "decimal", "decimal-pad" -> InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            "phone", "phone-pad", "tel" -> InputType.TYPE_CLASS_PHONE
            "url" -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            "web-search" -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_WEB_EDIT_TEXT
            else -> InputType.TYPE_CLASS_TEXT
        }
        return if (input.multiline && type and InputType.TYPE_MASK_CLASS == InputType.TYPE_CLASS_TEXT) {
            type or InputType.TYPE_TEXT_FLAG_MULTI_LINE
        } else type
    }

    companion object {
        internal fun requiredKeyboardShift(bottom: Float, currentShift: Float, keyboardTop: Float, padding: Float): Float =
            (bottom + currentShift + padding - keyboardTop).coerceAtLeast(0f)
    }
}
