package ai.nuxie.sdk.presentation

import ai.nuxie.sdk.logging.NuxieLog

import ai.nuxie.sdk.runtime.NuxieRuntimeLane
import ai.nuxie.sdk.runtime.NuxieViewModelSnapshot
import android.app.Activity
import android.view.View
import android.widget.FrameLayout
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** One mounted surface and overlays; layer-host releases retain their Screen rows here. */
internal class ExperienceMountedScreen(
    private val activity: Activity,
    private var prepared: PreparedPresentation,
    listener: ExperienceSurfaceHost.Listener,
    private val onFailure: (Throwable) -> Unit,
    videoDecoderPool: ai.nuxie.sdk.runtime.ExperienceVideoDecoderPool? = null,
) {
    val isLayer: Boolean = ai.nuxie.sdk.experiences.ExperienceLayerHostContract.isEnabled(prepared.descriptor)
    private class Row(val prepared: PreparedPresentation, val listener: ExperienceSurfaceHost.Listener)
    private val rows = java.util.concurrent.ConcurrentHashMap<String, Row>()
    private var activeListener = listener
    init { prepared.screenId?.let { rows[it] = Row(prepared, listener) } }
    // Validate synchronous inputs before allocating a native lane or observers.
    private val sceneBytes = prepared.sceneFile.readBytes()
    private val inputs = ExperienceTextInput.forScreen(prepared.descriptor, prepared.screenId)
    private val inputSize = if (inputs.isEmpty()) null else requireNotNull(prepared.artboardSize) {
        "Editable Experience has no authored artboard extent"
    }
    private val fonts = ((prepared.descriptor?.get("render") as? JsonObject)?.get("assets") as? JsonArray)
        .orEmpty().mapNotNull { value ->
            val asset = value as? JsonObject ?: return@mapNotNull null
            if ((asset["kind"] as? JsonPrimitive)?.content != "font") return@mapNotNull null
            val name = (asset["assetUniqueName"] as? JsonPrimitive)?.content ?: return@mapNotNull null
            val key = (asset["key"] as? JsonPrimitive)?.content ?: return@mapNotNull null
            prepared.artifactsByKey[key]?.let { name to it }
        }.toMap()
    private val runValues = prepared.runValues?.also { it.retainScreen() }
    private val lane = runValues?.lane ?: NuxieRuntimeLane()
    private var captionOverlay: ExperienceVideoCaptionOverlay? = null
    private var textOverlay: ExperienceTextInputOverlay? = null
    private var captionInsets: ExperienceWindowInsets? = null
    private var windowInsets: ExperienceWindowInsets? = null
    private val lifecycle get() = prepared.screenLifecycle
    val transitionEvents = ExperienceScreenExitHandshake()
    private var reduceMotionEnabled = false
    private var content: View? = null
    private var awaitingSemanticPublication =
        ((prepared.descriptor?.get("requirements") as? JsonObject)?.get("requiredCapabilities") as? JsonArray)
            .orEmpty().any { (it as? JsonPrimitive)?.content == "experience-accessibility" } ||
            inputs.isNotEmpty()
    val surface = ExperienceSurfaceHost(
        context = activity,
        lane = lane,
        runValues = runValues,
        videoDecoderPool = videoDecoderPool,
        clearColor = prepared.clearColor,
        artboardSize = prepared.artboardSize,
        fitsContentHeight = prepared.fitsContentHeight,
        initialPresentationSize = ExperienceNativeLayerWindow.availableSize(activity, prepared.shell).let { available ->
            val retained = prepared.layerContentHeight.get()
            if (!prepared.fitsContentHeight && retained != null) available.copy(height = minOf(available.height, retained))
            else available
        },
        listener = object : ExperienceSurfaceHost.Listener by listener {
            override fun onLayoutBounds(bounds: ExperienceArtboardSize) {
                textOverlay?.updateLayoutBounds(bounds)
                activeListener.onLayoutBounds(bounds)
            }
            override fun onVideoCaptions(captions: Map<Long, ai.nuxie.sdk.runtime.NuxieVideoCaption>) {
                captionOverlay?.update(captions)
                activeListener.onVideoCaptions(captions)
            }
            override fun onRuntimeEvent(event: ai.nuxie.sdk.runtime.NuxieRuntimeEvent, viewModelSnapshot: NuxieViewModelSnapshot?) {
                transitionEvents.receive(event.name)
                listener.onRuntimeEvent(event, viewModelSnapshot)
            }
            override fun onLayerRuntimeStep(screenId: String, outcome: ai.nuxie.sdk.runtime.NuxiePlayerStepOutcome,
                correlationId: ULong, snapshot: NuxieViewModelSnapshot, saves: List<ExperienceResponseSaveRequest>) {
                rows[screenId]?.listener?.onRuntimeStep(outcome, correlationId, snapshot, saves)
            }
            override fun onLayerRuntimeEvent(screenId: String, event: ai.nuxie.sdk.runtime.NuxieRuntimeEvent,
                snapshot: NuxieViewModelSnapshot) {
                rows[screenId]?.listener?.onRuntimeEvent(event, snapshot)
            }
            override fun onTextCommitted(inputId: String, text: String, snapshot: NuxieViewModelSnapshot?) =
                activeListener.onTextCommitted(inputId, text, snapshot)
            override fun onTextInputEvent(inputId: String, event: ExperienceSemanticTextDraft.Event) =
                activeListener.onTextInputEvent(inputId, event)
            override fun onFailure(error: ExperiencePresentationException) = activeListener.onFailure(error)
            override fun onNativeTextFields(fields: List<ExperienceNativeTextField>): Map<Long, View> {
                textOverlay?.updateNativeFields(fields)
                val ids = fields.map { it.target.nodeId }.toSet()
                return textOverlay?.semanticViews().orEmpty().filterKeys { it in ids }
            }
            override fun onSemanticTreePublished() {
                if (awaitingSemanticPublication) {
                    // Reveal the native and virtual hierarchy together, once this occurrence is complete.
                    content?.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_AUTO
                    awaitingSemanticPublication = false
                }
            }

        },
    )
    private var reducedMotion: ExperienceReducedMotion? = null

    fun mount(entering: Boolean = true): View {
        lifecycle.updateFontScale(activity.resources.configuration.fontScale)
        if (isLayer) surface.updateRuntimeValues(lifecycle.snapshot().filterKeys { it == "fontScale" })
        surface.updateRuntimeValues(prepared.commerceValues.get())
        if (!isLayer) surface.updateRuntimeValues(if (entering && lifecycle.phase == ExperienceScreenLifecycle.Phase.HIDDEN)
            lifecycle.move(ExperienceScreenLifecycle.Phase.ENTERING, exitReason = ExperienceScreenLifecycle.ExitReason.NONE) else lifecycle.snapshot())
        reducedMotion = ExperienceReducedMotion(activity) { reduced ->
            reduceMotionEnabled = reduced
            val values = lifecycle.updateReduceMotion(reduced)
            surface.updateRuntimeValues(if (isLayer) values.filterKeys { it == "env/reduceMotion" } else values)
        }
        surface.loadArtboard(
            sceneBytes = sceneBytes,
            artboardName = prepared.artboardName,
            descriptor = prepared.descriptor,
            artifactsByKey = prepared.artifactsByKey,
            viewModelProjection = prepared.viewModelProjection,
            textInputs = inputs,
            retainedViewModel = if (isLayer) null else prepared.retainedViewModel,
            layerScreen = if (isLayer) prepared else null,
        )
        return ExperienceInputContainer(activity, surface::dispatchExperienceKeyEvent, surface::semanticKeyboardEntry).apply {
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
                    prepared.textInputState,
                    surface::writeNativeText, surface::notifyNativeText, surface::nativeTextEvent, surface::beginNativeEditing)
                textOverlay = overlay
                addView(overlay, FrameLayout.LayoutParams(-1, -1))
            }
        }.also { content = it }
    }

    /** Before a new layer's window exists, lay the screen out for the space that layer gives it. */
    fun assumeLayerInsets(shell: PresentationShell) {
        surface.updateRuntimeValues(ExperienceWindowInsets.forLayer(activity, shell).stateValues())
    }

    /** Install after attaching content, so projection uses the real window geometry. */
    fun observeWindow() {
        captionInsets?.close()
        captionInsets = captionOverlay?.let { overlay ->
            ExperienceWindowInsets(activity, overlay, overlay::updateInsets)
        }
        windowInsets?.close()
        windowInsets = ExperienceWindowInsets(activity, surface) { insets ->
            surface.updateRuntimeValues(insets.stateValues())
        }
    }

    fun setVisible(visible: Boolean, preservePendingInput: Boolean = false) {
        if (visible) {
            refreshFontScale(activity.resources.configuration.fontScale)
            reducedMotion?.refresh()
        }
        surface.setPresentationVisible(visible, preservePendingInput)
    }

    /** Queued on the same lane as safe-area updates and frame-qualified native input capture. */
    fun refreshFontScale(value: Float) {
        val values = lifecycle.updateFontScale(value)
        surface.updateRuntimeValues(if (isLayer) values.filterKeys { it == "fontScale" } else values)
    }

    val reduceMotion: Boolean get() = reduceMotionEnabled

    fun setInputEnabled(enabled: Boolean) {
        surface.setInputEnabled(enabled)
        textOverlay?.setInputEnabled(enabled)
        if (isLayer) textOverlay?.visibility = if (enabled) View.VISIBLE else View.INVISIBLE
    }

    suspend fun updateRowRuntimeValues(screenId: String, values: Map<String, ai.nuxie.sdk.runtime.NuxieViewModelScalarValue>) {
        if (isLayer) surface.layerOperation { it.applyRuntimeValuesOnLane(screenId, values) }
        else surface.updateRuntimeValues(values)
    }

    fun activate() {
        if (isLayer) {
            surface.queueLayerPhases(listOf(checkNotNull(prepared.screenId) to ExperienceScreenLifecycle.Phase.ACTIVE))
            return
        }
        if (lifecycle.phase == ExperienceScreenLifecycle.Phase.ENTERING ||
            lifecycle.phase == ExperienceScreenLifecycle.Phase.EXITING) {
            surface.updateRuntimeValues(lifecycle.move(ExperienceScreenLifecycle.Phase.ACTIVE, exitReason = ExperienceScreenLifecycle.ExitReason.NONE))
        }
    }

    suspend fun awaitExit(reason: ExperienceScreenLifecycle.ExitReason) {
        if (isLayer) {
            val id = checkNotNull(prepared.screenId)
            val exit = layerExit(prepared)
            val waiter = if (!reduceMotionEnabled) exit.event?.let { name ->
                surface.layerOperation { it.registerCompletionOnLane(id, name) }
            } else null
            try {
                val write: (ExperienceInteractiveLayer) -> Unit = {
                    it.setPhasesOnLane(listOf(id to ExperienceScreenLifecycle.Phase.EXITING), exitReason = reason)
                }
                val budget = exit.watchdogMs ?: 250
                val presented = if (reduceMotionEnabled) { surface.layerOperation(write); true }
                    else surface.applyLayerTransition(budget, write)
                if (!presented) logPresentationTimeout(budget)
                if (presented && waiter != null && exit.watchdogMs != null) kotlinx.coroutines.withTimeoutOrNull(exit.watchdogMs) { waiter.await() }
            } finally { if (waiter != null) surface.layerOperation { it.removeCompletionOnLane(id, waiter) } }
            return
        }
        val render = prepared.descriptor?.get("render") as? JsonObject
        val screens = render?.get("screens") as? JsonArray
        val screen = screens?.filterIsInstance<JsonObject>()?.firstOrNull {
            (it["id"] as? JsonPrimitive)?.content == prepared.screenId
        }
        transitionEvents.perform(screen?.get("exit") as? JsonObject, reduceMotionEnabled) { exit(reason) }
    }

    suspend fun beginCustomTransition(id: String, outgoing: Boolean) {
        surface.applyTransitionValues(if (outgoing)
            lifecycle.move(ExperienceScreenLifecycle.Phase.EXITING, id, exitReason = ExperienceScreenLifecycle.ExitReason.NAVIGATION)
        else lifecycle.beginPreparedTransition(id))
    }

    fun enter() {
        surface.updateRuntimeValues(lifecycle.move(ExperienceScreenLifecycle.Phase.ENTERING, exitReason = ExperienceScreenLifecycle.ExitReason.NONE))
    }

    fun hide() {
        if (isLayer) {
            surface.queueLayerPhases(listOf(checkNotNull(prepared.screenId) to ExperienceScreenLifecycle.Phase.HIDDEN))
            setVisible(false)
            setInputEnabled(false)
            return
        }
        surface.updateRuntimeValues(lifecycle.move(ExperienceScreenLifecycle.Phase.HIDDEN, exitReason = ExperienceScreenLifecycle.ExitReason.NONE))
        setVisible(false)
        setInputEnabled(false)
    }

    fun exit(reason: ExperienceScreenLifecycle.ExitReason) {
        if (isLayer) {
            surface.queueLayerPhases(listOf(checkNotNull(prepared.screenId) to ExperienceScreenLifecycle.Phase.EXITING), reason)
            return
        }
        surface.updateRuntimeValues(lifecycle.move(ExperienceScreenLifecycle.Phase.EXITING, exitReason = reason))
    }

    suspend fun prepareRow(target: PreparedPresentation, listener: ExperienceSurfaceHost.Listener) {
        check(isLayer)
        rows[checkNotNull(target.screenId)] = Row(target, listener)
        surface.prepareLayerRow(target)
    }

    /** The step controls rows; the Activity only transfers the presentation lease afterward. */
    suspend fun navigateLayer(target: PreparedPresentation, isBack: Boolean) {
        val sourceId = checkNotNull(prepared.screenId)
        val targetId = checkNotNull(target.screenId)
        val intent = target.transition?.let(ai.nuxie.sdk.experiences.ExperienceLayerHostContract::intent)
        val style = intent?.style ?: ai.nuxie.sdk.experiences.ExperienceLayerHostContract.Style.NONE
        val plan = ExperienceScreenTransitionPlan.resolveLayer(target.transition, checkNotNull(target.descriptor), sourceId, targetId)
        val waiters = mutableListOf<Pair<String, kotlinx.coroutines.Deferred<Unit>>>()
        var insertion: Int? = null
        var phasePresented = true
        var loggedPresentationTimeout = false
        try {
            ExperienceLayerStep.perform(sourceId, targetId, style, isBack, plan, layerExit(prepared), reduceMotionEnabled,
                object : ExperienceLayerStep.Operations {
                    override fun input(enabled: Boolean) { setInputEnabled(enabled) }
                    override suspend fun prepare() = Unit
                    override suspend fun register(screenId: String, event: String): kotlinx.coroutines.Deferred<Unit> =
                        surface.layerOperation { it.registerCompletionOnLane(screenId, event) }.also { waiters += screenId to it }
                    override suspend fun insert(index: Int) { insertion = index }
                    override suspend fun phases(values: List<Pair<String, ExperienceScreenLifecycle.Phase>>, transition: String) {
                        val insert = insertion.also { insertion = null }
                        val work: (ExperienceInteractiveLayer) -> Unit = { layer ->
                            if (insert != null) layer.insertOnLane(targetId, insert)
                            layer.setPhasesOnLane(values, transition)
                        }
                        val finalPhases = values.all { it.second == ExperienceScreenLifecycle.Phase.HIDDEN ||
                            it.second == ExperienceScreenLifecycle.Phase.ACTIVE }
                        val budget = if (values.any { it.second == ExperienceScreenLifecycle.Phase.EXITING })
                            plan?.watchdogMs ?: layerExit(prepared).watchdogMs ?: 250 else plan?.watchdogMs ?: 250
                        phasePresented = when {
                            reduceMotionEnabled -> { surface.layerOperation(work); true }
                            // Final phases need a settled mutation, not another drawable barrier.
                            finalPhases -> { surface.applyLayerTransition(0, work); true }
                            else -> surface.applyLayerTransition(budget, work)
                        }
                        if (!phasePresented && !loggedPresentationTimeout) {
                            loggedPresentationTimeout = true
                            logPresentationTimeout(budget)
                        }
                    }
                    override suspend fun present() = Unit
                    override suspend fun wait(completions: List<kotlinx.coroutines.Deferred<Unit>>, milliseconds: Long) {
                        if (phasePresented) super<ExperienceLayerStep.Operations>.wait(completions, milliseconds)
                    }
                    override suspend fun remove() {
                        surface.removeLayerRow(sourceId, insertion?.let { targetId to it }, waiters)
                        insertion = null
                    }
                    override suspend fun hidden() = Unit
                    override suspend fun active() {
                        prepared = target
                        activeListener = checkNotNull(rows[targetId]).listener
                        surface.selectLayerRow(targetId)
                        bindTextOverlay()
                    }
                })
        } finally {
            kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                surface.layerOperation { layer -> waiters.forEach { (id, waiter) -> layer.removeCompletionOnLane(id, waiter) } }
            }
        }
    }

    private fun logPresentationTimeout(milliseconds: Long) {
        NuxieLog.w("ExperienceLayerStep",
            "Experience phase presentation watchdog expired", null, NuxieLog.sensitive("watchdogMs", milliseconds))
    }

    suspend fun restoreRow(source: PreparedPresentation, targetId: String) {
        val sourceId = checkNotNull(source.screenId)
        surface.restoreLayerRow(sourceId, targetId)
        prepared = source
        activeListener = checkNotNull(rows[sourceId]).listener
        surface.selectLayerRow(sourceId)
        bindTextOverlay()
    }

    private fun bindTextOverlay() {
        val root = content as? FrameLayout ?: return
        textOverlay?.let { it.close(); root.removeView(it) }
        textOverlay = null
        val inputs = ExperienceTextInput.forScreen(prepared.descriptor, prepared.screenId)
        if (inputs.isEmpty()) return
        val size = checkNotNull(prepared.artboardSize) { "Editable Experience has no authored artboard extent" }
        textOverlay = ExperienceTextInputOverlay(activity, size, inputs, fonts, prepared.textInputState,
            surface::writeNativeText, surface::notifyNativeText, surface::nativeTextEvent, surface::beginNativeEditing).also {
            root.addView(it, FrameLayout.LayoutParams(-1, -1))
        }
    }

    private fun layerExit(content: PreparedPresentation): ExperienceLayerStep.Exit {
        val render = content.descriptor?.get("render") as? JsonObject
        val screen = (render?.get("screens") as? JsonArray)?.filterIsInstance<JsonObject>()?.firstOrNull {
            (it["id"] as? JsonPrimitive)?.content == content.screenId
        }
        val exit = screen?.get("exit") as? JsonObject
        val event = (exit?.get("completeEventName") as? JsonPrimitive)?.content
        val duration = (exit?.get("durationMs") as? JsonPrimitive)?.content?.toLongOrNull()
        return ExperienceLayerStep.Exit(event, duration?.coerceIn(0, Long.MAX_VALUE - 250)?.plus(250))
    }

    /** Completion follows native handle release, so the owner can release its artifact lease. */
    fun close(changingConfigurations: Boolean, completion: () -> Unit) {
        content = null
        transitionEvents.close()
        reducedMotion?.close()
        windowInsets?.close()
        windowInsets = null
        captionInsets?.close()
        captionInsets = null
        captionOverlay?.update(emptyMap())
        captionOverlay = null
        textOverlay?.close()
        textOverlay = null
        val finalState = if (changingConfigurations) lifecycle.snapshot()
            else lifecycle.move(ExperienceScreenLifecycle.Phase.HIDDEN, exitReason = ExperienceScreenLifecycle.ExitReason.NONE)
        val retirement = ExperienceScreenRetirement(completion)
        surface.release(if (isLayer) emptyMap() else finalState, retirement::mediaReleased)
        if (runValues != null) runValues.releaseScreen(retirement::nativeReleased)
        else lane.shutdown(retirement::nativeReleased)
    }
}
