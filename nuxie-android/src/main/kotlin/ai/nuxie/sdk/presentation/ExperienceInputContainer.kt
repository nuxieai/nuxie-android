package ai.nuxie.sdk.presentation

import android.content.Context
import android.view.KeyEvent
import android.view.View
import android.widget.FrameLayout

/** Route authored traversal before a focused native editor consumes Tab. */
internal class ExperienceInputContainer(
    context: Context,
    private val semanticKey: (KeyEvent) -> Boolean,
    private val semanticEntry: (Int) -> View?,
) : FrameLayout(context) {
    override fun addFocusables(views: ArrayList<View>, direction: Int, focusableMode: Int) {
        val sequential = direction == View.FOCUS_FORWARD || direction == View.FOCUS_BACKWARD
        val entry = if (sequential && !hasFocus()) semanticEntry(direction) else null
        if (entry != null) entry.addFocusables(views, direction, focusableMode)
        else super.addFocusables(views, direction, focusableMode)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_TAB && semanticKey(event)) return true
        return super.dispatchKeyEvent(event)
    }
}
