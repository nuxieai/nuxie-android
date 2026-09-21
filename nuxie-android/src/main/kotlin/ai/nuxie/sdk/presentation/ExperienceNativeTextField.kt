package ai.nuxie.sdk.presentation

import ai.nuxie.sdk.runtime.NativeSemanticNode
import ai.nuxie.sdk.runtime.NuxieTextInputGeometry
import ai.nuxie.sdk.runtime.NuxieViewModelSnapshot

internal data class ExperienceTextFieldTarget(val inputId: String, val nodeId: Long)

/** Execution-only editor input. Not a data class: diagnostics must not print secure values. */
internal class ExperienceNativeTextField(
    val target: ExperienceTextFieldTarget,
    val ownerId: Long,
    val captureId: Long,
    val node: NativeSemanticNode,
    val geometry: NuxieTextInputGeometry,
    val text: String,
    val snapshot: NuxieViewModelSnapshot,
)
