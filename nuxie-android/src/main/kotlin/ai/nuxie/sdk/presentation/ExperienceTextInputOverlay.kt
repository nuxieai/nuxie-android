package ai.nuxie.sdk.presentation

import ai.nuxie.sdk.runtime.NuxieTextGeometryCapture
import ai.nuxie.sdk.runtime.NuxieTextRunGeometry
import ai.nuxie.sdk.runtime.NativeSemanticState
import ai.nuxie.sdk.runtime.NativeSemanticNode
import android.view.accessibility.AccessibilityNodeInfo
import ai.nuxie.sdk.runtime.NuxieViewModelSnapshot
import android.content.Context
import android.graphics.Color
import android.graphics.Rect
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
import kotlin.math.min
import kotlin.math.roundToInt

/** Native editors share the renderer's centered-contain coordinate space. UI-thread owned. */
internal class ExperienceTextInputOverlay(
    context: Context,
    private val artboardSize: ExperienceArtboardSize,
    inputs: List<ExperienceTextInput>,
    private val fonts: Map<String, File>,
    private val writer: (String, String, Boolean, (Result<Unit>) -> Unit) -> Unit,
    private val onFailure: (Throwable) -> Unit,
    state: ExperienceTextInputState = ExperienceTextInputState(),
) : FrameLayout(context) {
    private data class Binding(val input: ExperienceTextInput, val editor: Editor, val container: FrameLayout, var geometryAvailable: Boolean = true)
    private val bindings = mutableListOf<Binding>()
    private val session = state.bind()
    private var snapshot: NuxieViewModelSnapshot? = null
    private var geometryCapture: NuxieTextGeometryCapture? = null
    private var closed = false
    private var inputEnabled = true
    private var semanticFields: Map<String, NativeSemanticNode>? = null
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
        inputs.forEach { input ->
            val retained = session.read(input.id)
            val editor = Editor(context, input.copy(value = retained?.text ?: input.value))
            retained?.let {
                val length = editor.text?.length ?: 0
                editor.setSelection(it.selectionStart.coerceIn(0, length), it.selectionEnd.coerceIn(0, length))
            }
            editor.tag = "nuxie-text-input-${input.id}"
            editor.visibility = View.INVISIBLE
            val container = FrameLayout(context).apply {
                importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
                clipChildren = false
                clipToPadding = false
            }
            container.addView(editor, LayoutParams(1, 1))
            addView(container, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
            bindings += Binding(input, editor, container)
            fun retain(): Boolean = !closed && inputEnabled && editor.isEnabled && session.write(input.id, ExperienceTextInputState.Value(
                editor.text.toString(), editor.selectionStart, editor.selectionEnd,
            ))
            editor.onSelection = { retain(); Unit }
            editor.onChange = { commit ->
                if (retain()) {
                    val nativeEditing = !commit && editor.isEditingText()
                    editor.setTextColor(if (input.secure || nativeEditing) input.style.color else Color.TRANSPARENT)
                    val renderedText = if (!input.secure && nativeEditing) "" else editor.text.toString()
                    writer(input.id, renderedText, commit) { result ->
                        if (!closed && session.isCurrent()) result.exceptionOrNull()?.let(onFailure)
                    }
                }
            }
            editor.onChange(false)
        }
    }

    /** IME callbacks can outlive touch dispatch; fence them during native preparation too. */
    fun setInputEnabled(enabled: Boolean) {
        if (closed || inputEnabled == enabled) return
        if (!enabled) clearEditorFocus()
        inputEnabled = false
        bindings.forEach { binding ->
            val editor = binding.editor
            if (enabled) session.read(binding.input.id)?.let { retained ->
                editor.setText(retained.text)
                val length = editor.text?.length ?: 0
                editor.setSelection(retained.selectionStart.coerceIn(0, length), retained.selectionEnd.coerceIn(0, length))
            }
            val node = semanticFields?.get(binding.input.id)
            editor.isEnabled = enabled && binding.geometryAvailable && (semanticFields == null || node != null && node.stateFlags and NativeSemanticState.DISABLED == 0)
        }
        inputEnabled = enabled
    }

    fun updateSemantics(fields: Map<String, NativeSemanticNode>) {
        if (closed) return
        semanticFields = fields.toMap()
        bindings.forEach { binding ->
            val node = fields[binding.input.id]
            binding.editor.semanticNode = node
            binding.editor.importantForAccessibility = if (node == null) View.IMPORTANT_FOR_ACCESSIBILITY_NO
                else View.IMPORTANT_FOR_ACCESSIBILITY_YES
            val enabled = inputEnabled && binding.geometryAvailable && node != null && node.stateFlags and NativeSemanticState.DISABLED == 0
            if (enabled && !binding.editor.isEnabled) {
                // Restore while disabled so TextWatcher/selection callbacks cannot
                // admit a stale IME draft or emit a second response transaction.
                session.read(binding.input.id)?.let { retained ->
                    binding.editor.setText(retained.text)
                    val length = binding.editor.text?.length ?: 0
                    binding.editor.setSelection(retained.selectionStart.coerceIn(0, length),
                        retained.selectionEnd.coerceIn(0, length))
                }
            }
            binding.editor.isEnabled = enabled
            if (node == null) {
                // Fence callbacks before clearing focus: a late IME commit must not
                // write into an occurrence whose presented field has disappeared.
                if (binding.editor.hasFocus()) clearEditorFocus()
                binding.editor.visibility = View.INVISIBLE
            }
        }
        layoutEditors()
    }

    fun semanticViews(): Map<Long, View> = bindings.mapNotNull { binding ->
        binding.editor.semanticNode?.let { it.id to binding.editor }
    }.toMap()

    fun update(snapshot: NuxieViewModelSnapshot, geometry: NuxieTextGeometryCapture? = null) {
        if (closed || !session.isCurrent()) return
        this.snapshot = snapshot
        geometryCapture = geometry
        layoutEditors()
    }

    fun close() {
        closed = true
        if (viewTreeObserver.isAlive) viewTreeObserver.removeOnGlobalLayoutListener(keyboardLayoutListener)
        applyKeyboardShift(0f)
        bindings.forEach { it.editor.onChange = {}; it.editor.onSelection = null }
        clearEditorFocus()
        removeAllViews()
        bindings.clear()
        snapshot = null
        geometryCapture = null
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

    private fun layoutEditors() {
        val snapshot = snapshot ?: return
        if (width <= 0 || height <= 0) return
        val scale = min(width / artboardSize.width, height / artboardSize.height)
        val left = (width - artboardSize.width * scale) / 2f
        val top = (height - artboardSize.height * scale) / 2f
        for (binding in bindings) {
            val (input, editor, container) = binding
            if (semanticFields != null && input.id !in semanticFields.orEmpty()) {
                editor.visibility = View.INVISIBLE
                continue
            }
            val metrics = input.effectiveMetrics(snapshot)
            val captured = geometryCapture
            if (captured != null) {
                val field = (captured as? NuxieTextGeometryCapture.Captured)?.fields?.get(input.runName)
                binding.geometryAvailable = field != null && metrics != null && placeCapturedField(input, metrics, editor, container, field, scale, left, top)
                val node = semanticFields?.get(input.id)
                val enabled = binding.geometryAvailable && inputEnabled &&
                    (semanticFields == null || node != null && node.stateFlags and NativeSemanticState.DISABLED == 0)
                if (enabled && !editor.isEnabled) {
                    session.read(input.id)?.let { retained ->
                        editor.setText(retained.text)
                        val length = editor.text?.length ?: 0
                        editor.setSelection(retained.selectionStart.coerceIn(0, length), retained.selectionEnd.coerceIn(0, length))
                    }
                }
                editor.isEnabled = enabled
                if (!binding.geometryAvailable) {
                    // Fence late IME callbacks before clearing focus on a missing field.
                    if (editor.hasFocus()) clearEditorFocus()
                    editor.visibility = View.INVISIBLE
                }
                continue
            }
            val geometry = input.geometry(snapshot)
            if (metrics == null || geometry == null || geometry.scaleX <= 0 || geometry.scaleY <= 0) {
                editor.visibility = View.INVISIBLE
                continue
            }
            val sx = scale * geometry.scaleX
            val sy = scale * geometry.scaleY
            val w = geometry.width * sx
            val h = geometry.height * sy
            val x = left + geometry.x * scale
            val y = top + geometry.y * scale
            if (listOf(w, h, x, y).any { !it.isFinite() } || w > Int.MAX_VALUE || h > Int.MAX_VALUE) {
                editor.visibility = View.INVISIBLE
                continue
            }
            val params = editor.layoutParams as LayoutParams
            val targetWidth = w.roundToInt().coerceAtLeast(1)
            val targetHeight = h.roundToInt().coerceAtLeast(1)
            if (params.width != targetWidth || params.height != targetHeight) {
                params.width = targetWidth
                params.height = targetHeight
                editor.layoutParams = params
            }
            editor.x = x
            editor.y = y
            editor.rotation = Math.toDegrees(geometry.rotation.toDouble()).toFloat()
            editor.setTextSize(TypedValue.COMPLEX_UNIT_PX, metrics.fontSize * sy)
            editor.letterSpacing = input.style.letterSpacing * sx / editor.textSize
            // -1 means native font-natural height, not a negative pixel value.
            // Explicit height is the baseline interval, so subtract actual font
            // metrics rather than nominal text size (which excludes leading).
            val extraLineSpacing = if (metrics.lineHeight == -1f) 0f
                else metrics.lineHeight * sy - editor.paint.getFontMetricsInt(null)
            editor.setLineSpacing(extraLineSpacing, 1f)
            editor.visibility = View.VISIBLE
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
        editor.presentedFirstBaseline = field.firstBaseline?.let(::localBaseline)
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

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            super.onMeasure(widthMeasureSpec, heightMeasureSpec)
            // TextView's baseline includes top padding. Measure the actual native layout,
            // then align that baseline without moving or resizing the published field box.
            val desired = presentedFirstBaseline
            // During native editing the runtime run is intentionally blank. Its text
            // origin remains valid even when it has no shaped first-line baseline.
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
        var onSelection: (() -> Unit)? = null
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
            setTextColor(if (input.secure) input.style.color else Color.TRANSPARENT)
            setHintTextColor(input.style.color)
            gravity = Gravity.TOP or when (input.style.textAlign?.lowercase(Locale.ROOT)) {
                "center" -> Gravity.CENTER_HORIZONTAL
                "right", "end" -> Gravity.END
                else -> Gravity.START
            }
            typeface = fonts[input.style.fontAssetName]?.let { runCatching { Typeface.createFromFile(it) }.getOrNull() }
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
                if (!focused) {
                    limitText()
                    BaseInputConnection.removeComposingSpans(text)
                }
                onChange(!focused)
                post { if (!closed) avoidKeyboard() }
            }
            setOnEditorActionListener { _, action, _ ->
                if (action == EditorInfo.IME_ACTION_DONE) {
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
