package ai.nuxie.sdk.presentation

import ai.nuxie.sdk.runtime.*
import android.content.Context
import android.view.View
import android.widget.EditText
import java.io.File
import java.util.WeakHashMap

/** Isolated platform-editor fixture. These occurrences do not qualify a published native endpoint. */
private class NativeOverlayState(val inputs: List<ExperienceTextInput>) {
    val texts = inputs.associate { it.id to ExperienceTextInputLimit.apply(it.value, it.maxLength) }.toMutableMap()
    var revision = 0L
}
private val nativeOverlayStates = WeakHashMap<ExperienceTextInputOverlay, NativeOverlayState>()

internal fun nativeInputOverlayFixture(context: Context, size: ExperienceArtboardSize,
    inputs: List<ExperienceTextInput>, fonts: Map<String, File>,
    writer: (String, String, Boolean, (Result<Unit>) -> Unit) -> Unit,
    failure: (Throwable) -> Unit, state: ExperienceTextInputState = ExperienceTextInputState()): ExperienceTextInputOverlay {
    val source = NativeOverlayState(inputs)
    val overlay = ExperienceTextInputOverlay(context, size, inputs, fonts, state,
        nativeWriter = { target, write, done ->
            writer(target.inputId, write.text, false) { result ->
                if (result.isSuccess) source.texts[target.inputId] = write.text
                done(if (result.isSuccess) ExperienceSemanticTextDraft.Outcome.ACCEPTED else ExperienceSemanticTextDraft.Outcome.REJECTED)
            }
        }, nativeEvent = { target, _, event ->
            writer(target.inputId, event.text, true) { result -> result.exceptionOrNull()?.let(failure) }
        })
    nativeOverlayStates[overlay] = source
    val snapshot = NuxieViewModelSnapshot.fromNative(NativeViewModelSnapshot(1,
        arrayOf(NativeViewModelSnapshotInstance(1, 0)), emptyArray()))
    val transform = NuxieTextRunGeometry.Transform(1f, 0f, 0f, 1f, 0f, 0f)
    val bounds = NuxieTextRunGeometry.Bounds(0f, 0f, 200f, 100f)
    overlay.layout(0, 0, 400, 400)
    overlay.updateNativeFixture(snapshot, NuxieTextGeometryCapture.Captured(inputs.associate { it.id to
        NuxieTextRunGeometry(1u, transform, transform, bounds, NuxieTextRunGeometry.Layout(transform, bounds), null) }))
    return overlay
}

/** Geometry order is explicit test data, separate from release input discovery. */
internal fun ExperienceTextInputOverlay.updateNativeFixture(snapshot: NuxieViewModelSnapshot,
    geometry: NuxieTextGeometryCapture) {
    val source = checkNotNull(nativeOverlayStates[this])
    val fields = (geometry as? NuxieTextGeometryCapture.Captured)?.fields?.values?.toList().orEmpty()
    val invalid = NuxieTextRunGeometry.Transform(Float.NaN, 0f, 0f, 1f, 0f, 0f)
    val bounds = NuxieTextRunGeometry.Bounds(0f, 0f, 200f, 100f)
    updateNativeFields(source.inputs.mapIndexed { index, input ->
        val id = index.toLong() + 1
        val field = fields.getOrNull(index)
        ExperienceNativeTextField(ExperienceTextFieldTarget(input.id, id), 1, ++source.revision,
            NativeSemanticNode(id, -1, 0, NativeSemanticRole.TEXT_FIELD,
                if (input.secure) NativeSemanticState.OBSCURED else 0, 0, 0, 0,
                0f, 0f, 200f, 100f, input.placeholder.orEmpty(), "", ""),
            NuxieTextInputGeometry(1u, field?.worldTransform ?: invalid, field?.textBounds ?: bounds,
                field?.layout, field?.firstBaseline, input.secure, input.multiline),
            source.texts.getValue(input.id), snapshot)
    })
    source.inputs.forEachIndexed { index, input ->
        findViewWithTag<EditText>("nuxie-text-input-${input.id}-${index + 1}")?.tag = "nuxie-text-input-${input.id}"
    }
}
