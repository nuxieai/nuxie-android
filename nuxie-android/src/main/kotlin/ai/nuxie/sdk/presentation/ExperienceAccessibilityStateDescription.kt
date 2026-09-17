package ai.nuxie.sdk.presentation

import ai.nuxie.sdk.R
import ai.nuxie.sdk.runtime.NativeSemanticNode
import ai.nuxie.sdk.runtime.NativeSemanticRole
import ai.nuxie.sdk.runtime.NativeSemanticState
import ai.nuxie.sdk.runtime.NativeSemanticTrait
import android.content.res.Resources
import android.os.Build
import android.view.accessibility.AccessibilityNodeInfo

internal object ExperienceAccessibilityStateDescription {
    // AndroidX's public interoperability transport for stateDescription before API 30.
    // https://android.googlesource.com/platform/frameworks/support/+/f3061eb1f416402fcfbdcce298234da249b8123f/core/core/src/main/java/androidx/core/view/accessibility/AccessibilityNodeInfoCompat.java
    const val STATE_DESCRIPTION_KEY = "androidx.view.accessibility.AccessibilityNodeInfoCompat.STATE_DESCRIPTION_KEY"

    fun isCheckable(node: NativeSemanticNode): Boolean =
        node.role in listOf(NativeSemanticRole.CHECKBOX, NativeSemanticRole.SWITCH_CONTROL, NativeSemanticRole.RADIO_BUTTON)
            || node.traitFlags and (NativeSemanticTrait.CHECKABLE or NativeSemanticTrait.TOGGLEABLE) != 0

    fun apply(info: AccessibilityNodeInfo, node: NativeSemanticNode, resources: Resources, nativeEditor: Boolean = false) {
        val value = if (nativeEditor || node.stateFlags and NativeSemanticState.OBSCURED != 0) "" else node.value
        val states = mutableListOf<String>()
        if (Build.VERSION.SDK_INT < 36 && node.traitFlags and NativeSemanticTrait.EXPANDABLE != 0) {
            states += resources.getString(if (node.stateFlags and NativeSemanticState.EXPANDED != 0)
                R.string.nuxie_accessibility_expanded else R.string.nuxie_accessibility_collapsed)
        }
        if (node.stateFlags and NativeSemanticState.REQUIRED != 0) states += resources.getString(R.string.nuxie_accessibility_required)
        if (node.stateFlags and NativeSemanticState.READ_ONLY != 0) states += resources.getString(R.string.nuxie_accessibility_read_only)
        val mixed = node.stateFlags and NativeSemanticState.MIXED != 0
        val parts = mutableListOf<String>()
        if (value.isNotEmpty()) parts += value
        // A supplied description replaces the reader's default checked speech.
        // Preserve the independent checked state whenever we supply other words.
        if (isCheckable(node) && node.stateFlags and NativeSemanticState.OBSCURED == 0
            && (value.isNotEmpty() || states.isNotEmpty() || (mixed && Build.VERSION.SDK_INT < 36))) {
            val on = node.stateFlags and (NativeSemanticState.CHECKED or NativeSemanticState.TOGGLED) != 0
            val toggle = node.role == NativeSemanticRole.SWITCH_CONTROL || node.traitFlags and NativeSemanticTrait.TOGGLEABLE != 0
            parts += resources.getString(when {
                mixed -> R.string.nuxie_accessibility_mixed
                toggle && on -> R.string.nuxie_accessibility_on
                toggle -> R.string.nuxie_accessibility_off
                on -> R.string.nuxie_accessibility_checked
                else -> R.string.nuxie_accessibility_unchecked
            })
        }
        parts += states
        val description = parts.joinToString(", ").takeIf { it.isNotEmpty() }
        if (Build.VERSION.SDK_INT >= 30) info.stateDescription = description
        else if (description == null) info.extras.remove(STATE_DESCRIPTION_KEY)
        else info.extras.putCharSequence(STATE_DESCRIPTION_KEY, description)
    }
}
