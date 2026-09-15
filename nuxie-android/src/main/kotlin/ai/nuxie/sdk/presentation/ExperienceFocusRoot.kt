package ai.nuxie.sdk.presentation

import android.content.Context
import android.view.View
import android.view.accessibility.AccessibilityEvent
import android.widget.FrameLayout

/** Window-local focus history includes native recovery controls and other mounted screens. */
internal class ExperienceFocusRoot(context: Context) : FrameLayout(context) {
    var accessibilityRevision = 0L
        private set
    var inputRevision = 0L
        private set

    init {
        // Keyboard ownership must work even when no accessibility service is enabled.
        viewTreeObserver.addOnGlobalFocusChangeListener { _, focused ->
            if (focused != null) inputRevision++
        }
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
            while (current != null) {
                if (current is ExperienceFocusRoot) return current
                current = current.parent as? View
            }
            return null
        }
    }
}
