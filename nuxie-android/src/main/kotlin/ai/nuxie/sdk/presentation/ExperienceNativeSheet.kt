package ai.nuxie.sdk.presentation

import android.content.Context
import android.view.View
import android.view.ViewGroup
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog

/** The platform sheet owns its animation, scrim, back handling and drag gesture. */
internal class ExperienceNativeSheet(context: Context, content: View, onClosed: () -> Unit) {
    internal val dialog = BottomSheetDialog(context,
        com.google.android.material.R.style.Theme_MaterialComponents_DayNight_BottomSheetDialog)
    private var retired = false

    init {
        dialog.setContentView(content, ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        dialog.setOnDismissListener { if (!retired) onClosed() }
        dialog.behavior.skipCollapsed = true
    }

    fun show() {
        dialog.show()
        dialog.behavior.state = BottomSheetBehavior.STATE_EXPANDED
    }

    fun setInputEnabled(enabled: Boolean) {
        dialog.setCancelable(enabled)
        dialog.setCanceledOnTouchOutside(enabled)
        dialog.behavior.isDraggable = enabled
    }

    fun closeFromUser() { dialog.cancel() }

    fun retire() {
        retired = true
        dialog.setOnDismissListener(null)
        dialog.dismiss()
    }
}
