package ai.nuxie.sdk.presentation

import ai.nuxie.sdk.runtime.NuxieVideoCaption
import android.content.Context
import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import java.util.Locale

/** Stable static-text accessibility nodes; captions never consume scene touches. */
internal class ExperienceVideoCaptionOverlay(context: Context) : LinearLayout(context) {
    private val labels = linkedMapOf<Long, TextView>()
    private val margin = (16 * resources.displayMetrics.density).toInt()
    init {
        orientation = VERTICAL
        gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
        isClickable = false
        isFocusable = false
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        setPadding(margin, margin, margin, margin)
        setOnApplyWindowInsetsListener { _, insets ->
            @Suppress("DEPRECATION")
            setPadding(margin + insets.systemWindowInsetLeft, margin,
                margin + insets.systemWindowInsetRight, margin + insets.systemWindowInsetBottom)
            insets
        }
    }

    fun update(captions: Map<Long, NuxieVideoCaption>) {
        for (id in labels.keys.toList()) {
            if (id !in captions) removeView(labels.remove(id))
        }
        for ((id, caption) in captions) {
            val label = labels.getOrPut(id) {
                TextView(context).apply {
                    textSize = 18f
                    gravity = Gravity.CENTER
                    setTextColor(Color.WHITE)
                    setBackgroundColor(0xD9000000.toInt())
                    setPadding(margin / 2, margin / 4, margin / 2, margin / 4)
                    isClickable = false
                    isFocusable = false
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
                    accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_NONE
                    addView(this, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
                }
            }
            if (label.text.toString() != caption.text) label.text = caption.text
            label.textLocale = if (caption.language.isBlank()) Locale.getDefault() else Locale.forLanguageTag(caption.language)
            label.visibility = if (caption.text.isEmpty()) View.GONE else View.VISIBLE
        }
    }
}
