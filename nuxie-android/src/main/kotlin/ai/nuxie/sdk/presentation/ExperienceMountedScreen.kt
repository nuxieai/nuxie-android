package ai.nuxie.sdk.presentation

import ai.nuxie.sdk.runtime.NuxieRuntimeLane
import ai.nuxie.sdk.runtime.NuxieViewModelSnapshot
import android.app.Activity
import android.view.View
import android.widget.FrameLayout
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Owns one screen's native graph and input, independently of the Activity shell. */
internal class ExperienceMountedScreen(
    private val activity: Activity,
    private val prepared: PreparedPresentation,
    listener: ExperienceSurfaceHost.Listener,
    private val onFailure: (Throwable) -> Unit,
) {
    // Validate synchronous inputs before allocating a native lane or observers.
    private val rivBytes = prepared.rivFile.readBytes()
    private val inputs = ExperienceTextInput.forScreen(prepared.descriptor, prepared.screenId)
    private val inputSize = if (inputs.isEmpty()) null else requireNotNull(prepared.artboardSize) {
        "Editable Experience has no authored artboard extent"
    }
    private val fonts = ((prepared.descriptor?.get("render") as? JsonObject)?.get("assets") as? JsonArray)
        .orEmpty().mapNotNull { value ->
            val asset = value as? JsonObject ?: return@mapNotNull null
            if ((asset["kind"] as? JsonPrimitive)?.content != "font") return@mapNotNull null
            val name = (asset["riveUniqueName"] as? JsonPrimitive)?.content ?: return@mapNotNull null
            val key = (asset["key"] as? JsonPrimitive)?.content ?: return@mapNotNull null
            prepared.artifactsByKey[key]?.let { name to it }
        }.toMap()
    private val lane = NuxieRuntimeLane()
    private var textOverlay: ExperienceTextInputOverlay? = null
    private var windowInsets: ExperienceWindowInsets? = null
    private val lifecycle = prepared.screenLifecycle
    val surface = ExperienceSurfaceHost(
        context = activity,
        lane = lane,
        clearColor = prepared.clearColor,
        artboardSize = prepared.artboardSize,
        listener = object : ExperienceSurfaceHost.Listener by listener {
            override fun onTextInputSnapshot(snapshot: NuxieViewModelSnapshot) {
                textOverlay?.update(snapshot)
            }
        },
    )
    private var reducedMotion: ExperienceReducedMotion? = null

    fun mount(): View {
        surface.updateRuntimeValues(if (lifecycle.phase == ExperienceScreenLifecycle.Phase.HIDDEN)
            lifecycle.move(ExperienceScreenLifecycle.Phase.ENTERING) else lifecycle.snapshot())
        reducedMotion = ExperienceReducedMotion(activity) { reduced ->
            surface.updateRuntimeValues(lifecycle.updateReduceMotion(reduced))
        }
        surface.loadArtboard(
            rivBytes = rivBytes,
            artboardName = prepared.artboardName,
            descriptor = prepared.descriptor,
            artifactsByKey = prepared.artifactsByKey,
            viewModelProjection = prepared.viewModelProjection,
            textInputs = inputs,
        )
        return inputSize?.let { size ->
            FrameLayout(activity).apply {
                addView(surface, FrameLayout.LayoutParams(-1, -1))
                val overlay = ExperienceTextInputOverlay(activity, size, inputs, fonts,
                    surface::writeText, onFailure, prepared.textInputState)
                textOverlay = overlay
                addView(overlay, FrameLayout.LayoutParams(-1, -1))
            }
        } ?: surface
    }

    /** Install after attaching content, so projection uses the real window geometry. */
    fun observeWindow() {
        windowInsets?.close()
        windowInsets = prepared.artboardSize?.let { size ->
            ExperienceWindowInsets(activity, surface, size) { insets ->
                surface.updateRuntimeValues(insets.stateValues())
            }
        }
    }

    fun setVisible(visible: Boolean) {
        if (visible) reducedMotion?.refresh()
        surface.setPresentationVisible(visible)
    }

    fun activate() {
        if (lifecycle.phase == ExperienceScreenLifecycle.Phase.ENTERING) {
            surface.updateRuntimeValues(lifecycle.move(ExperienceScreenLifecycle.Phase.ACTIVE))
        }
    }

    fun exit() {
        surface.updateRuntimeValues(lifecycle.move(ExperienceScreenLifecycle.Phase.EXITING))
    }

    /** Completion follows native handle release, so the owner can release its artifact lease. */
    fun close(changingConfigurations: Boolean, completion: () -> Unit) {
        reducedMotion?.close()
        windowInsets?.close()
        windowInsets = null
        textOverlay?.close()
        textOverlay = null
        val finalState = if (changingConfigurations) lifecycle.snapshot()
            else lifecycle.move(ExperienceScreenLifecycle.Phase.HIDDEN)
        surface.release(finalState)
        lane.shutdown(completion)
    }
}
