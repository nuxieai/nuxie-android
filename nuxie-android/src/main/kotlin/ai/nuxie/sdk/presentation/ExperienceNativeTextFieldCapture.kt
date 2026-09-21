package ai.nuxie.sdk.presentation

import ai.nuxie.sdk.runtime.NativeSemanticNode
import ai.nuxie.sdk.runtime.NativeSemanticRole
import ai.nuxie.sdk.runtime.NuxieTextInputGeometry
import ai.nuxie.sdk.runtime.NuxieViewModelSnapshot

/** Runs on the runtime lane. Native endpoint lookup, not names or list order, establishes ownership. */
internal object ExperienceNativeTextFieldCapture {
    fun read(
        inputs: List<ExperienceTextInput>,
        nodes: List<NativeSemanticNode>,
        captureId: Long,
        geometry: (Long, String) -> NuxieTextInputGeometry?,
        readText: (Long, String) -> ByteArray,
        readOwner: (Long, String) -> NuxieViewModelSnapshot,
    ): List<ExperienceNativeTextField> {
        val endpoints = inputs.filter { it.editableValueName != null }
        val captured = mutableListOf<ExperienceNativeTextField>()
        for (node in nodes.filter { it.role == NativeSemanticRole.TEXT_FIELD }) {
            val matches = endpoints.mapNotNull { input ->
                geometry(node.id, checkNotNull(input.editableValueName))?.let { input to it }
            }
            check(matches.size <= 1) { "Multiple input declarations match one native occurrence" }
            val (input, fieldGeometry) = matches.singleOrNull() ?: continue
            check(fieldGeometry.obscured == input.secure) { "Native input obscuring does not match its declaration" }
            val name = checkNotNull(input.editableValueName)
            val bytes = readText(node.id, name)
            val text = try { bytes.decodeToString(throwOnInvalidSequence = true) }
                catch (_: CharacterCodingException) { error("Native input contains invalid UTF-8") }
            val owner = readOwner(node.id, name)
            captured += ExperienceNativeTextField(ExperienceTextFieldTarget(input.id, node.id),
                owner.nativeRootInstanceId, captureId, node, fieldGeometry, text, owner)
        }
        return captured
    }
}
