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
    private val rivBytes = prepared.sceneFile.readBytes()
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
    private var captionOverlay: ExperienceVideoCaptionOverlay? = null
    private var textOverlay: ExperienceTextInputOverlay? = null
    private var windowInsets: ExperienceWindowInsets? = null
    private val lifecycle = prepared.screenLifecycle
    val transitionEvents = ExperienceScreenExitHandshake()
    private var reduceMotionEnabled = false
    private var content: View? = null
    private var awaitingSemanticPublication =
        ((prepared.descriptor?.get("requirements") as? JsonObject)?.get("requiredCapabilities") as? JsonArray)
            .orEmpty().any { (it as? JsonPrimitive)?.content == "experience-accessibility" }
    val surface = ExperienceSurfaceHost(
        context = activity,
        lane = lane,
        clearColor = prepared.clearColor,
        artboardSize = prepared.artboardSize,
        listener = object : ExperienceSurfaceHost.Listener by listener {
            override fun onVideoCaptions(captions: Map<Long, ai.nuxie.sdk.runtime.NuxieVideoCaption>) {
                captionOverlay?.update(captions)
                listener.onVideoCaptions(captions)
            }
            override fun onRuntimeEvent(event: ai.nuxie.sdk.runtime.NuxieRuntimeEvent, viewModelSnapshot: NuxieViewModelSnapshot?) {
                transitionEvents.receive(event.name)
                listener.onRuntimeEvent(event, viewModelSnapshot)
            }
            override fun onSemanticFields(fields: Map<String, ai.nuxie.sdk.runtime.NativeSemanticNode>): Map<Long, View> {
                textOverlay?.updateSemantics(fields)
                return textOverlay?.semanticViews().orEmpty()
            }
            override fun onSemanticTreePublished() {
                if (awaitingSemanticPublication) {
                    // Reveal the native and virtual hierarchy together, once this occurrence is complete.
                    content?.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_AUTO
                    awaitingSemanticPublication = false
                }
            }
            override fun onTextInputSnapshot(snapshot: NuxieViewModelSnapshot, geometry: ai.nuxie.sdk.runtime.NuxieTextGeometryCapture) {
                textOverlay?.update(snapshot, geometry)
            }
        },
    )
    private var reducedMotion: ExperienceReducedMotion? = null

    fun mount(): View {
        lifecycle.updateFontScale(activity.resources.configuration.fontScale)
        surface.updateRuntimeValues(if (lifecycle.phase == ExperienceScreenLifecycle.Phase.HIDDEN)
            lifecycle.move(ExperienceScreenLifecycle.Phase.ENTERING) else lifecycle.snapshot())
        reducedMotion = ExperienceReducedMotion(activity) { reduced ->
            reduceMotionEnabled = reduced
            surface.updateRuntimeValues(lifecycle.updateReduceMotion(reduced))
        }
        surface.loadArtboard(
            rivBytes = rivBytes,
            artboardName = prepared.artboardName,
            descriptor = prepared.descriptor,
            artifactsByKey = prepared.artifactsByKey,
            viewModelProjection = prepared.viewModelProjection,
            textInputs = inputs,
            retainedViewModel = prepared.retainedViewModel,
        )
        return ExperienceInputContainer(activity, surface::dispatchSemanticKeyEvent, surface::semanticKeyboardEntry).apply {
            if (awaitingSemanticPublication) {
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
            }
            setBackgroundColor(prepared.clearColor)
            addView(surface, FrameLayout.LayoutParams(-1, -1))
            captionOverlay = ExperienceVideoCaptionOverlay(activity).also {
                addView(it, FrameLayout.LayoutParams(-1, -1))
            }
            inputSize?.let { size ->
                val overlay = ExperienceTextInputOverlay(activity, size, inputs, fonts,
                    surface::writeText, onFailure, prepared.textInputState)
                textOverlay = overlay
                addView(overlay, FrameLayout.LayoutParams(-1, -1))
            }
        }.also { content = it }
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
        if (visible) {
            refreshFontScale(activity.resources.configuration.fontScale)
            reducedMotion?.refresh()
        }
        surface.setPresentationVisible(visible)
    }

    /** Queued on the same lane as safe-area updates and frame-qualified native input capture. */
    fun refreshFontScale(value: Float) {
        surface.updateRuntimeValues(lifecycle.updateFontScale(value))
    }

    val reduceMotion: Boolean get() = reduceMotionEnabled

    fun setInputEnabled(enabled: Boolean) {
        surface.setInputEnabled(enabled)
        textOverlay?.setInputEnabled(enabled)
    }

    fun activate() {
        if (lifecycle.phase == ExperienceScreenLifecycle.Phase.ENTERING ||
            lifecycle.phase == ExperienceScreenLifecycle.Phase.EXITING) {
            surface.updateRuntimeValues(lifecycle.move(ExperienceScreenLifecycle.Phase.ACTIVE))
        }
    }

    suspend fun awaitExit() {
        val render = prepared.descriptor?.get("render") as? JsonObject
        val screens = render?.get("screens") as? JsonArray
        val screen = screens?.filterIsInstance<JsonObject>()?.firstOrNull {
            (it["id"] as? JsonPrimitive)?.content == prepared.screenId
        }
        transitionEvents.perform(screen?.get("exit") as? JsonObject, reduceMotionEnabled, ::exit)
    }

    fun beginCustomTransition(id: String, outgoing: Boolean) {
        surface.updateRuntimeValues(if (outgoing)
            lifecycle.move(ExperienceScreenLifecycle.Phase.EXITING, id)
        else lifecycle.beginPreparedTransition(id))
    }

    fun exit() {
        surface.updateRuntimeValues(lifecycle.move(ExperienceScreenLifecycle.Phase.EXITING))
    }

    /** Completion follows native handle release, so the owner can release its artifact lease. */
    fun close(changingConfigurations: Boolean, completion: () -> Unit) {
        content = null
        transitionEvents.close()
        reducedMotion?.close()
        windowInsets?.close()
        windowInsets = null
        captionOverlay?.update(emptyMap())
        captionOverlay = null
        textOverlay?.close()
        textOverlay = null
        val finalState = if (changingConfigurations) lifecycle.snapshot()
            else lifecycle.move(ExperienceScreenLifecycle.Phase.HIDDEN)
        surface.release(finalState)
        lane.shutdown(completion)
    }
}
