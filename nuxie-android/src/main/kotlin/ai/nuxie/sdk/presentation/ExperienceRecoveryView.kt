package ai.nuxie.sdk.presentation

import android.app.Activity
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.view.Gravity
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import kotlin.math.roundToInt

/** Native, scrollable recovery controls; acquisition remains owned by the service. */
internal class ExperienceRecoveryView(
    private val activity: Activity,
    backgroundColor: Int,
    phase: AcquisitionProgress.Phase,
    retry: () -> Boolean,
    onClose: () -> Unit,
) : FrameLayout(activity), AutoCloseable {
    private var insets: ExperienceWindowInsets? = null
    private fun dp(value: Int) = (value * resources.displayMetrics.density).roundToInt()

    init {
        val light = loadingHighlight(backgroundColor) and 0xffffff != 0
        val foreground = if (light) Color.WHITE else Color.BLACK
        val panel = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(20), dp(20), dp(20))
            background = GradientDrawable().apply {
                setColor(if (light) 0xf21e1e1e.toInt() else 0xf2ffffff.toInt())
                cornerRadius = dp(16).toFloat()
            }
            elevation = dp(8).toFloat()
        }
        panel.addView(TextView(activity).apply {
            text = if (phase == AcquisitionProgress.Phase.FAILED) "Unable to load this Experience" else "Still loading"
            textSize = 20f
            setTextColor(foreground)
        })
        panel.addView(TextView(activity).apply {
            text = if (phase == AcquisitionProgress.Phase.RETRYING) "You can close this screen while you wait."
                else "Try again, or close this screen."
            textSize = 16f
            setTextColor(foreground)
            setPadding(0, dp(12), 0, dp(16))
        })
        panel.addView(Button(activity).apply {
            text = if (phase == AcquisitionProgress.Phase.RETRYING) "Retrying…" else "Retry"
            minHeight = dp(48)
            isEnabled = phase != AcquisitionProgress.Phase.RETRYING
            setOnClickListener { if (retry()) isEnabled = false }
        }, LinearLayout.LayoutParams(-1, -2))
        panel.addView(Button(activity).apply {
            text = "Close"
            minHeight = dp(48)
            setOnClickListener { onClose() }
        }, LinearLayout.LayoutParams(-1, -2))
        addView(ScrollView(activity).apply { addView(panel) }, LayoutParams(-1, -2, Gravity.CENTER))
        if (Build.VERSION.SDK_INT >= 28) accessibilityPaneTitle = "Experience recovery"
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        insets = ExperienceWindowInsets(activity, this) {
            setPadding(dp(16) + it.left.roundToInt(), dp(16) + it.top.roundToInt(),
                dp(16) + it.right.roundToInt(), dp(16) + it.bottom.roundToInt())
        }
    }

    override fun onDetachedFromWindow() {
        close()
        super.onDetachedFromWindow()
    }

    override fun close() {
        insets?.close()
        insets = null
    }
}
