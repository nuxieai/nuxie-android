package ai.nuxie.sdk.presentation

import android.content.Context
import android.view.View
import android.view.ViewTreeObserver
import android.view.accessibility.AccessibilityEvent
import android.widget.FrameLayout

/** Window-local focus history includes native recovery controls and other mounted screens. */
internal class ExperienceFocusRoot(context: Context) : FrameLayout(context) {
    var accessibilityRevision = 0L
        private set
    var inputRevision = 0L
        private set

    // Keyboard ownership must work even when no accessibility service is enabled.
    private val inputObserver = ViewTreeObserver.OnGlobalFocusChangeListener { _, focused ->
        // The overlay parks focus on itself when an editor is withdrawn. It is
        // not a new control chosen by the user and must not cancel restoration.
        if (focused != null && focused !is ExperienceTextInputOverlay) inputRevision++
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        viewTreeObserver.addOnGlobalFocusChangeListener(inputObserver)
    }

    override fun onDetachedFromWindow() {
        viewTreeObserver.removeOnGlobalFocusChangeListener(inputObserver)
        super.onDetachedFromWindow()
    }

    override fun onRequestSendAccessibilityEvent(child: View, event: AccessibilityEvent): Boolean {
        when (event.eventType) {
            AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUSED -> accessibilityRevision++
        }
        return super.onRequestSendAccessibilityEvent(child, event)
    }

    companion object {
        fun containing(view: View): ExperienceFocusRoot? {
            var current: View? = view
            var root: ExperienceFocusRoot? = null
            while (current != null) {
                if (current is ExperienceFocusRoot) root = current
                current = current.parent as? View
            }
            return root
        }
    }
}
