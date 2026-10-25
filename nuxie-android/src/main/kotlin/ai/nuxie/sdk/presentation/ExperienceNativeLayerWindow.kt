package ai.nuxie.sdk.presentation

import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.content.res.ColorStateList
import android.content.res.TypedArray
import android.graphics.Color
import android.graphics.Outline
import android.graphics.Rect
import android.graphics.RectF
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.ImageView
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.color.MaterialColors
import com.google.android.material.shape.MaterialShapeDrawable
import com.google.android.material.shape.ShapeAppearanceModel

/** Each modal owns a native window; the platform owns its scrim, animation and back handling. */
internal class ExperienceNativeLayerWindow(
    private val activity: Activity,
    private val content: View,
    private val shell: PresentationShell,
    onClosed: () -> Unit,
) {
    internal val dialog: Dialog
    private var heightReady = !shell.fitsContent
    private var measuredHeight: Float? = null
    private var showRequested = false
    private var presentationCompletion: (() -> Unit)? = null
    private var presented = false
    private var retired = false
    private var inputEnabled = false
    /** Another move holds the Experience's one slot: the sheet refuses a drag and outside taps (section 2). */
    private var moveBusy = false
    /** A drag that reached STATE_DRAGGING and got the slot; it ends exactly once. */
    private var dragging = false
    private var dragHandler: DragHandler? = null
    private val dragCallback = object : BottomSheetBehavior.BottomSheetCallback() {
        override fun onSlide(bottomSheet: View, slideOffset: Float) = Unit
        override fun onStateChanged(bottomSheet: View, newState: Int) = dragStateChanged(newState)
    }
    private var backHandler: (() -> Unit)? = null
    private var backCallback: android.window.OnBackInvokedCallback? = null
    private var backPressedCallback: androidx.activity.OnBackPressedCallback? = null
    private val layoutListener = View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> updateBounds() }

    /**
     * Material's current sheet look, drawn over the screen without adding space: the Material 3
     * sheet shape (rounded top corners) and its drag handle. The window keeps the dialog theme
     * whose geometry the sheet sizes are measured against; only these pieces use Material 3.
     */
    private val sheetLook = sheetLook()

    /** Resolved against the Activity's current configuration, so light or dark follows the system. */
    private fun sheetLook(): Context? = (shell as? PresentationShell.Sheet)?.let {
        ContextThemeWrapper(activity, com.google.android.material.R.style.Theme_Material3_DayNight_BottomSheetDialog)
    }
    private val sheetShape: ShapeAppearanceModel? = sheetLook?.let {
        ShapeAppearanceModel.builder(it, com.google.android.material.R.style.ShapeAppearance_Material3_Corner_ExtraLarge,
            com.google.android.material.R.style.ShapeAppearanceOverlay_Material3_Corner_Top).build()
    }
    private val sheetBackground: MaterialShapeDrawable? = sheetShape?.let { shape ->
        MaterialShapeDrawable(shape).apply { fillColor = sheetColor(checkNotNull(sheetLook)) }
    }

    /**
     * Shown, like the iPhone grabber, only when the sheet can be swiped closed (closedby any). It is a
     * picture drawn from Material's drag handle style rather than BottomSheetDragHandleView, which
     * becomes clickable whenever any accessibility service is on: over the screen it would take the
     * screen's taps and collapse the sheet. This one takes no touches and has no actions.
     */
    internal val dragHandle: ImageView? = sheetLook?.let { look ->
        materialDragHandle(look).apply { visibility = if (shell.dismissible) View.VISIBLE else View.GONE }
    }

    init {
        dialog = when (shell) {
            is PresentationShell.Sheet -> {
                BottomSheetDialog(activity, com.google.android.material.R.style.Theme_MaterialComponents_DayNight_BottomSheetDialog).apply {
                    setContentView(sheetContent(), ViewGroup.LayoutParams(-1, -1))
                    behavior.skipCollapsed = true
                }
            }
            is PresentationShell.FullScreen -> Dialog(activity, android.R.style.Theme_Translucent_NoTitleBar_Fullscreen).apply {
                setContentView(content, ViewGroup.LayoutParams(-1, -1))
            }
        }
        dialog.setOnDismissListener { if (!retired) onClosed() }
        activity.window.decorView.addOnLayoutChangeListener(layoutListener)
    }

    /**
     * The Activity handles uiMode changes itself, so nothing rebuilds an open sheet when the system
     * switches between light and dark: re-resolve the sheet and handle colors here.
     */
    fun refreshChrome() {
        val look = sheetLook() ?: return
        sheetBackground?.fillColor = sheetColor(look)
        dragHandle?.imageTintList = handleTint(look)
    }

    /** The screen fills the sheet and is clipped to its rounded top; the handle overlays it. */
    private fun sheetContent(): View = FrameLayout(activity).apply {
        val shape = checkNotNull(sheetShape)
        addView(content, FrameLayout.LayoutParams(-1, -1))
        dragHandle?.let { addView(it, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.CENTER_HORIZONTAL)) }
        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                val bounds = RectF(0f, 0f, view.width.toFloat(), view.height.toFloat())
                val radius = shape.topLeftCornerSize.getCornerSize(bounds)
                // Extend below the bottom edge so only the top corners round.
                outline.setRoundRect(0, 0, view.width, view.height + kotlin.math.ceil(radius).toInt(), radius)
            }
        }
        clipToOutline = true
    }

    /** A sheet drag is a move: it takes the slot at STATE_DRAGGING and gives it back at its end. */
    interface DragHandler {
        /** True when the drag got the slot. A refused drag still moves; if it hides the sheet, the close waits its turn. */
        fun dragStarted(): Boolean
        /** A drag back to where it started, or a retired window: nothing closes. */
        fun dragCancelled()
        /** The drag hid the sheet. Its dismissal work follows through the dismiss listener and ends the move. */
        fun dragHid()
    }

    fun setDragHandler(handler: DragHandler?) { dragHandler = handler }

    /** Whether another move holds the slot. A drag in progress keeps its own sheet draggable. */
    fun setMoveBusy(busy: Boolean) {
        moveBusy = busy
        applyGates()
    }

    private fun applyGates() {
        if (retired) return
        dialog.setCanceledOnTouchOutside(inputEnabled && !moveBusy && shell !is PresentationShell.FullScreen)
        (dialog as? BottomSheetDialog)?.behavior?.isDraggable = inputEnabled && (!moveBusy || dragging)
    }

    private fun dragStateChanged(state: Int) {
        if (retired) return
        when (state) {
            BottomSheetBehavior.STATE_DRAGGING -> if (!dragging) {
                // The slot is taken before the busy listener runs, so mark the drag first.
                dragging = true
                if (dragHandler?.dragStarted() != true) dragging = false
            }
            BottomSheetBehavior.STATE_HIDDEN -> if (dragging) {
                dragging = false
                dragHandler?.dragHid()
            }
            // Material can reach either straight from STATE_DRAGGING, with no settling between.
            BottomSheetBehavior.STATE_EXPANDED, BottomSheetBehavior.STATE_COLLAPSED -> if (dragging) {
                dragging = false
                dragHandler?.dragCancelled()
                applyGates()
            }
        }
    }

    fun whenPresented(completion: () -> Unit) {
        if (presented) completion() else presentationCompletion = completion
    }

    private fun finishPresentation() {
        if (retired || presented) return
        presented = true
        presentationCompletion?.also { presentationCompletion = null }?.invoke()
    }

    fun show() {
        showRequested = true
        if (retired || !heightReady) return
        dialog.show()
        (dialog as? BottomSheetDialog)?.findViewById<View>(com.google.android.material.R.id.coordinator)
            ?.addOnLayoutChangeListener(layoutListener)
        updateBounds()
        val sheet = dialog as? BottomSheetDialog
        if (sheet == null) content.post { finishPresentation() }
        else {
            val callback = object : BottomSheetBehavior.BottomSheetCallback() {
                override fun onSlide(bottomSheet: View, slideOffset: Float) = Unit
                override fun onStateChanged(bottomSheet: View, newState: Int) {
                    if (newState == BottomSheetBehavior.STATE_EXPANDED) {
                        sheet.behavior.removeBottomSheetCallback(this)
                        finishPresentation()
                    }
                }
            }
            sheet.behavior.addBottomSheetCallback(callback)
            sheet.behavior.removeBottomSheetCallback(dragCallback)
            sheet.behavior.addBottomSheetCallback(dragCallback)
            sheet.behavior.state = BottomSheetBehavior.STATE_EXPANDED
            content.post {
                if (sheet.behavior.state == BottomSheetBehavior.STATE_EXPANDED) {
                    sheet.behavior.removeBottomSheetCallback(callback)
                    finishPresentation()
                }
            }
        }
    }

    private fun updateBounds() {
        if (!dialog.isShowing) return
        val window = dialog.window ?: return
        val bounds = availableBounds(activity)
        when (shell) {
            is PresentationShell.FullScreen -> window.setLayout(-1, -1)
            is PresentationShell.Sheet -> {
                val sheet = dialog as BottomSheetDialog
                val panel = sheet.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)
                val availableHeight = (panel?.parent as? View)?.height?.takeIf { it > 0 } ?: bounds.height()
                val height = when (shell.detent) {
                    PresentationShell.Sheet.Detent.MEDIUM -> availableHeight / 2
                    PresentationShell.Sheet.Detent.LARGE -> ViewGroup.LayoutParams.MATCH_PARENT
                    // The runtime centres the hugging content in its surface. Rounding the sheet down
                    // lets that content cover both edges; rounding up left a partly uncovered
                    // pixel row along the top and bottom of the sheet.
                    PresentationShell.Sheet.Detent.FIT_CONTENT -> measuredHeight?.let {
                        kotlin.math.floor(it * activity.resources.displayMetrics.density).toInt().coerceIn(1, availableHeight.coerceAtLeast(1))
                    } ?: ViewGroup.LayoutParams.MATCH_PARENT
                }
                panel?.let { view ->
                    // The behavior installs its own square background on first layout; replace it once.
                    if (view.background !== sheetBackground) view.background = sheetBackground
                    if (view.layoutParams.height != height) view.layoutParams = view.layoutParams.apply { this.height = height }
                }
                // A sheet has one place: collapsing (an accessibility action, or any caller) lands
                // exactly where it is expanded, so nothing can leave it half shown.
                val placed = if (height > 0) height else availableHeight
                if (sheet.behavior.peekHeight != placed) sheet.behavior.peekHeight = placed
            }

        }
    }

    /** Null means the file has no presentation binding: show the large fallback. */
    fun updateContentHeight(points: Float?) {
        if (retired || !shell.fitsContent) return
        val height = points?.takeIf { it.isFinite() && it > 0f }
        if (heightReady && height == measuredHeight) return
        measuredHeight = height
        heightReady = true
        if (showRequested && !dialog.isShowing) show() else updateBounds()
    }

    companion object {
        private fun <T> styled(look: Context, attr: Int, read: (TypedArray) -> T): T {
            val values = look.obtainStyledAttributes(
                com.google.android.material.R.style.Widget_Material3_BottomSheet_DragHandle, intArrayOf(attr))
            try { return read(values) } finally { values.recycle() }
        }

        private fun sheetColor(look: Context) = ColorStateList.valueOf(MaterialColors.getColor(look,
            com.google.android.material.R.attr.colorSurfaceContainerLow, Color.TRANSPARENT))

        private fun handleTint(look: Context): ColorStateList? = styled(look, androidx.appcompat.R.attr.tint) { it.getColorStateList(0) }

        /** Material 3's drag handle look (drawable, tint and spacing) on a plain, inert view. */
        private fun materialDragHandle(look: Context): ImageView {
            return ImageView(look).apply {
                setImageDrawable(styled(look, androidx.appcompat.R.attr.srcCompat) { it.getDrawable(0) })
                imageTintList = handleTint(look)
                scaleType = ImageView.ScaleType.CENTER
                minimumWidth = styled(look, android.R.attr.minWidth) { it.getDimensionPixelSize(0, 0) }
                minimumHeight = styled(look, android.R.attr.minHeight) { it.getDimensionPixelSize(0, 0) }
                setPadding(0, 0, 0, styled(look, android.R.attr.paddingBottom) { it.getDimensionPixelSize(0, 0) })
                isClickable = false
                isFocusable = false
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }
        }

        fun windowBounds(activity: Activity): Rect {
            if (android.os.Build.VERSION.SDK_INT >= 30) return Rect(activity.windowManager.currentWindowMetrics.bounds)
            val bounds = Rect()
            @Suppress("DEPRECATION")
            activity.windowManager.defaultDisplay.getRectSize(bounds)
            if (activity.window.decorView.width > 0 && activity.window.decorView.height > 0) {
                bounds.set(0, 0, activity.window.decorView.width, activity.window.decorView.height)
            }
            return bounds
        }

        private fun availableBounds(activity: Activity): Rect {
            val bounds = windowBounds(activity)
            val insets = activity.window.decorView.rootWindowInsets ?: return bounds
            if (android.os.Build.VERSION.SDK_INT >= 30) {
                val bars = insets.getInsetsIgnoringVisibility(android.view.WindowInsets.Type.systemBars())
                bounds.set(bounds.left + bars.left, bounds.top + bars.top, bounds.right - bars.right, bounds.bottom - bars.bottom)
            } else {
                @Suppress("DEPRECATION")
                bounds.set(bounds.left + insets.stableInsetLeft, bounds.top + insets.stableInsetTop,
                    bounds.right - insets.stableInsetRight, bounds.bottom - insets.stableInsetBottom)
            }
            return bounds
        }

        /** Use Material's width cap before a hidden screen's first native step. */
        fun availableSize(activity: Activity, shell: PresentationShell): ExperienceArtboardSize {
            val bounds = if (shell is PresentationShell.Sheet) availableBounds(activity) else windowBounds(activity)
            val maxWidth = if (shell is PresentationShell.Sheet) {
                val probe = BottomSheetDialog(activity,
                    com.google.android.material.R.style.Theme_MaterialComponents_DayNight_BottomSheetDialog)
                try { probe.setContentView(View(activity)); probe.behavior.maxWidth }
                finally { probe.dismiss() }
            } else -1
            val width = if (maxWidth > 0) minOf(bounds.width(), maxWidth) else bounds.width()
            val density = activity.resources.displayMetrics.density
            return ExperienceArtboardSize(width / density, bounds.height() / density)
        }
    }

    fun setInputEnabled(enabled: Boolean, allowDismissalDuringLoading: Boolean = false) {
        inputEnabled = enabled && (shell.dismissible || allowDismissalDuringLoading)
        dialog.setCancelable(inputEnabled)
        applyGates()
        // Material re-registers its dismissal callback when cancelability changes.
        registerBackCallback()
    }

    fun closeFromUser() { if (inputEnabled) dialog.cancel() }

    /** Layer history handles Back even while authored motion has disabled window dismissal. */
    fun setBackHandler(handler: () -> Unit) {
        backHandler = handler
        dialog.setOnKeyListener { _, code, event ->
            if (code != android.view.KeyEvent.KEYCODE_BACK) false
            else {
                if (event.action == android.view.KeyEvent.ACTION_UP && !event.isCanceled) backHandler?.invoke()
                true
            }
        }
        if (backPressedCallback == null) (dialog as? androidx.activity.ComponentDialog)?.let { component ->
            val callback = object : androidx.activity.OnBackPressedCallback(true) {
                override fun handleOnBackPressed() { backHandler?.invoke() }
            }
            backPressedCallback = callback
            component.onBackPressedDispatcher.addCallback(callback)
        }
        if (android.os.Build.VERSION.SDK_INT >= 33 && backCallback == null) {
            backCallback = android.window.OnBackInvokedCallback { backHandler?.invoke() }
        }
        registerBackCallback()
    }

    private fun registerBackCallback() {
        if (android.os.Build.VERSION.SDK_INT < 33) return
        val callback = backCallback ?: return
        // Equal-priority callbacks run newest first. Keep Screen history ahead of dismissal
        // without raising priority above the platform's input-method handling.
        dialog.onBackInvokedDispatcher.unregisterOnBackInvokedCallback(callback)
        dialog.onBackInvokedDispatcher.registerOnBackInvokedCallback(android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT, callback)
    }

    fun retire() {
        retired = true
        presentationCompletion = null
        (dialog as? BottomSheetDialog)?.behavior?.removeBottomSheetCallback(dragCallback)
        // Retirement promises no further state callback, so a drag in progress ends here.
        if (dragging) { dragging = false; dragHandler?.dragCancelled() }
        dragHandler = null
        if (android.os.Build.VERSION.SDK_INT >= 33) backCallback?.let { dialog.onBackInvokedDispatcher.unregisterOnBackInvokedCallback(it) }
        backCallback = null
        backPressedCallback?.remove()
        backPressedCallback = null
        backHandler = null
        activity.window.decorView.removeOnLayoutChangeListener(layoutListener)
        (dialog as? BottomSheetDialog)?.findViewById<View>(com.google.android.material.R.id.coordinator)
            ?.removeOnLayoutChangeListener(layoutListener)
        dialog.setOnDismissListener(null)
        dialog.dismiss()
        (content.parent as? ViewGroup)?.removeView(content)
    }

}
