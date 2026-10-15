package ai.nuxie.sdk.presentation

import android.app.Activity
import android.app.Dialog
import android.graphics.Rect
import android.view.View
import android.view.ViewGroup
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

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
    private var retired = false
    private var inputEnabled = false
    private val layoutListener = View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> updateBounds() }

    init {
        val looks = checkNotNull(javaClass.getResourceAsStream("presentation-looks.json")) {
            "Missing presentation looks resource"
        }.use { Json.parseToJsonElement(it.bufferedReader().readText()).jsonObject.getValue("android").jsonObject }
        dialog = when (shell) {
            is PresentationShell.Sheet -> {
                check(looks.getValue("sheet").jsonPrimitive.content == "material")
                BottomSheetDialog(activity, com.google.android.material.R.style.Theme_MaterialComponents_DayNight_BottomSheetDialog).apply {
                    setContentView(content, ViewGroup.LayoutParams(-1, -1))
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

    fun show() {
        showRequested = true
        if (retired || !heightReady) return
        dialog.show()
        updateBounds()
        (dialog as? BottomSheetDialog)?.behavior?.state = BottomSheetBehavior.STATE_EXPANDED
    }

    private fun updateBounds() {
        if (!dialog.isShowing) return
        val window = dialog.window ?: return
        val bounds = windowBounds(activity)
        when (shell) {
            is PresentationShell.FullScreen -> window.setLayout(-1, -1)
            is PresentationShell.Sheet -> {
                val sheet = dialog as BottomSheetDialog
                val height = when (shell.detent) {
                    PresentationShell.Sheet.Detent.MEDIUM -> bounds.height() / 2
                    PresentationShell.Sheet.Detent.LARGE -> bounds.height()
                    PresentationShell.Sheet.Detent.FIT_CONTENT -> measuredHeight?.let {
                        kotlin.math.ceil(it * activity.resources.displayMetrics.density).toInt().coerceIn(1, bounds.height())
                    } ?: bounds.height()
                }
                sheet.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)?.let { view ->
                    if (view.layoutParams.height != height) view.layoutParams = view.layoutParams.apply { this.height = height }
                }
                sheet.behavior.state = BottomSheetBehavior.STATE_EXPANDED
            }

        }
    }

    /** Null means the file has no presentation binding: show the large fallback. */
    fun updateContentHeight(points: Float?) {
        if (retired || !shell.fitsContent) return
        measuredHeight = points?.takeIf { it.isFinite() && it > 0f }
        heightReady = true
        if (showRequested && !dialog.isShowing) show() else updateBounds()
    }

    companion object {
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

        /** Use Material's width cap before a hidden screen's first native step. */
        fun availableSize(activity: Activity, shell: PresentationShell): ExperienceArtboardSize {
            val bounds = windowBounds(activity)
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
        dialog.setCanceledOnTouchOutside(inputEnabled && shell !is PresentationShell.FullScreen)
        (dialog as? BottomSheetDialog)?.behavior?.isDraggable = inputEnabled
    }

    fun closeFromUser() { if (inputEnabled) dialog.cancel() }

    fun retire() {
        retired = true
        activity.window.decorView.removeOnLayoutChangeListener(layoutListener)
        dialog.setOnDismissListener(null)
        dialog.dismiss()
        (content.parent as? ViewGroup)?.removeView(content)
    }

}
