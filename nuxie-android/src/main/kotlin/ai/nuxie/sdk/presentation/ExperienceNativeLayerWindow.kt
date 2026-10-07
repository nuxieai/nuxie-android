package ai.nuxie.sdk.presentation

import android.app.Activity
import android.app.Dialog
import android.graphics.Rect
import android.graphics.RectF
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.math.roundToInt

/** Each modal owns a native window; the platform owns its scrim, animation and back handling. */
internal class ExperienceNativeLayerWindow(
    private val activity: Activity,
    private val content: View,
    private val shell: PresentationShell,
    private var laidOutHeight: Float? = null,
    onClosed: () -> Unit,
) {
    internal val dialog: Dialog
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
            is PresentationShell.Dialog -> {
                check(looks.getValue("dialog").jsonPrimitive.content == "material")
                val context = ContextThemeWrapper(activity, com.google.android.material.R.style.Theme_MaterialComponents_DayNight)
                MaterialAlertDialogBuilder(context)
                    // Q1 owns the entire card's extent, including its native background.
                    .setBackgroundInsetStart(0).setBackgroundInsetEnd(0)
                    .setBackgroundInsetTop(0).setBackgroundInsetBottom(0)
                    .setView(content, 0, 0, 0, 0).create()
            }
            is PresentationShell.FullScreen -> Dialog(activity, android.R.style.Theme_Translucent_NoTitleBar_Fullscreen).apply {
                setContentView(content, ViewGroup.LayoutParams(-1, -1))
            }
        }
        dialog.setOnDismissListener { if (!retired) onClosed() }
        activity.window.decorView.addOnLayoutChangeListener(layoutListener)
    }

    fun show() {
        dialog.show()
        updateBounds()
        (dialog as? BottomSheetDialog)?.behavior?.state = BottomSheetBehavior.STATE_EXPANDED
    }

    fun updateLaidOutHeight(height: Float) {
        require(height.isFinite() && height > 0f)
        laidOutHeight = height
        updateBounds()
    }

    private fun updateBounds() {
        if (!dialog.isShowing) return
        val window = dialog.window ?: return
        val bounds = Rect()
        if (android.os.Build.VERSION.SDK_INT >= 30) bounds.set(activity.windowManager.currentWindowMetrics.bounds)
        else {
            @Suppress("DEPRECATION")
            activity.windowManager.defaultDisplay.getRectSize(bounds)
            if (activity.window.decorView.width > 0 && activity.window.decorView.height > 0)
                bounds.set(0, 0, activity.window.decorView.width, activity.window.decorView.height)
        }
        val density = activity.resources.displayMetrics.density
        when (shell) {
            is PresentationShell.FullScreen -> window.setLayout(-1, -1)
            is PresentationShell.Sheet -> {
                val sheet = dialog as BottomSheetDialog
                val height = if (shell.detent == PresentationShell.Sheet.Detent.MEDIUM) bounds.height() / 2 else bounds.height()
                sheet.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)?.let { view ->
                    if (view.layoutParams.height != height) view.layoutParams = view.layoutParams.apply { this.height = height }
                }
                sheet.behavior.state = BottomSheetBehavior.STATE_EXPANDED
            }
            is PresentationShell.Dialog -> {
                val insets = activity.window.decorView.rootWindowInsets
                val safe = RectF(0f, 0f, bounds.width().toFloat(), bounds.height().toFloat())
                if (android.os.Build.VERSION.SDK_INT >= 30 && insets != null) {
                    val bars = insets.getInsetsIgnoringVisibility(android.view.WindowInsets.Type.systemBars() or android.view.WindowInsets.Type.displayCutout())
                    safe.set(bars.left.toFloat(), bars.top.toFloat(), safe.right - bars.right, safe.bottom - bars.bottom)
                } else if (insets != null) {
                    @Suppress("DEPRECATION")
                    safe.set(insets.stableInsetLeft.toFloat(), insets.stableInsetTop.toFloat(),
                        safe.right - insets.stableInsetRight, safe.bottom - insets.stableInsetBottom)
                }
                val height = checkNotNull(laidOutHeight) { "Dialog requires runtime layout before presentation" }
                val frame = dialogFrame(bounds.width() / density, safe.top / density, safe.bottom / density, height)
                window.addFlags(WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN)
                window.setGravity(Gravity.TOP or Gravity.LEFT)
                window.attributes = window.attributes.apply {
                    x = (frame.left * density).roundToInt()
                    y = (frame.top * density).roundToInt()
                    width = (frame.width() * density).roundToInt()
                    this.height = (frame.height() * density).roundToInt()
                }
            }
        }
    }

    fun setInputEnabled(enabled: Boolean) {
        inputEnabled = enabled && shell.dismissible
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

    companion object {
        fun dialogFrame(windowWidth: Float, safeTop: Float, safeBottom: Float, laidOutHeight: Float): RectF {
            val height = laidOutHeight.coerceIn(0f, (safeBottom - safeTop).coerceAtLeast(0f))
            val top = safeTop + (safeBottom - safeTop - height) / 2f
            return RectF(16f, top, (windowWidth - 16f).coerceAtLeast(16f), top + height)
        }
    }
}
