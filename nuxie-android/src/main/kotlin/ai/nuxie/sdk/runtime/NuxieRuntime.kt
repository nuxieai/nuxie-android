package ai.nuxie.sdk.runtime

/**
 * Typed entry point for the runtime operations used by Android presentation.
 * Callers create, use, and close every returned owned wrapper on one
 * [NuxieRuntimeLane].
 */
internal class NuxieRuntime(
    private val native: NuxieTypedRuntimeNative = JniNuxieTypedRuntimeNative,
) {
    val isAvailable: Boolean get() = native.isAvailable

    fun info(): String = native.runtimeInfo()

    fun inspectFileAssets(bytes: ByteArray): List<ExpectedFileAsset>? =
        native.inspectFileAssets(bytes)

    fun importFile(
        renderer: NuxieAndroidVulkanRenderer,
        bytes: ByteArray,
        expectedAssets: List<ExpectedFileAsset> = emptyList(),
        externalAssets: Map<Int, ByteArray> = emptyMap(),
        imageDecoder: NuxImageDecoder = AndroidImageDecoder,
    ): NuxieRuntimeFile? = native.newFile(
        renderer.requireHandle(),
        bytes,
        expectedAssets,
        externalAssets,
        imageDecoder,
    )
        .takeUnless { it == 0L }
        ?.let { NuxieRuntimeFile(it, native) }

    fun newAndroidVulkanRenderer(
        pixelWidth: Int,
        pixelHeight: Int,
    ): NuxieAndroidVulkanRenderer? =
        native.newAndroidVulkanRenderer(pixelWidth, pixelHeight)
            .takeUnless { it == 0L }
            ?.let { NuxieAndroidVulkanRenderer(it, native) }

    fun acquireWindow(surface: android.view.Surface): NuxieRuntimeWindow? =
        native.acquireWindow(surface)
            .takeUnless { it == 0L }
            ?.let { NuxieRuntimeWindow(it, native) }

    /**
     * Replace one authored list with freshly projected children and bind the
     * resulting root before player creation. This is synchronous by design:
     * presentation invokes it from the already-confined runtime lane.
     */
    fun bindViewModelList(
        file: NuxieRuntimeFile,
        artboard: NuxieRuntimeArtboard,
        projection: NuxieViewModelListProjection,
        instanceBindings: List<NuxieViewModelInstanceBinding> = emptyList(),
    ): NuxieRuntimeViewModelState {
        require(projection.items.map { it.listIndex } == projection.items.indices.toList()) {
            "Projected view-model list indices must be contiguous and ordered"
        }
        require(projection.items.count { it.selected } <= 1) {
            "Projected view-model list may select at most one item"
        }
        val catalog = requireNativeValue(
            native.viewModelCatalog(file.requireHandle()),
            "read view-model catalog",
        ).toViewModelCatalog()
        val rootSchema = catalog.schemas.singleOrNull {
            it.name == projection.rootSchemaName
        } ?: error("Unknown or ambiguous root view-model '${projection.rootSchemaName}'")
        val itemSchema = catalog.schemas.singleOrNull {
            it.name == projection.itemSchemaName
        } ?: error("Unknown or ambiguous item view-model '${projection.itemSchemaName}'")
        val listProperty = catalog.propertyAtPath(rootSchema.index, projection.listPath)
        require(listProperty.kind == NuxieViewModelPropertyKind.LIST) {
            "View-model property '${projection.listPath}' is not a list"
        }
        require(
            listProperty.referencedSchemaIndex == null ||
                listProperty.referencedSchemaIndex == itemSchema.index,
        ) {
            "View-model list '${projection.listPath}' does not contain '${projection.itemSchemaName}'"
        }
        projection.selectedItemPath?.let { path ->
            val selectedProperty = catalog.propertyAtPath(rootSchema.index, path)
            require(selectedProperty.kind == NuxieViewModelPropertyKind.VIEW_MODEL) {
                "View-model property '$path' is not a view model"
            }
            require(selectedProperty.referencedSchemaIndex == itemSchema.index) {
                "View-model property '$path' does not reference '${projection.itemSchemaName}'"
            }
        }

        val root = requireNativeValue(
            native.newDefaultViewModel(artboard.requireHandle()),
            "create default view model",
        )
        val children = mutableListOf<Long>()
        val usedAuthoredInstances = mutableSetOf<Int>()
        val instanceIds = mutableMapOf<String, Long>()
        try {
            projection.items.forEach { item ->
                val authored = catalog.authoredInstances.firstOrNull {
                    it.schemaIndex == itemSchema.index &&
                        it.name == item.authoredInstanceName &&
                        it.index !in usedAuthoredInstances
                } ?: error(
                    "Unknown authored '${projection.itemSchemaName}' instance " +
                        "'${item.authoredInstanceName}'",
                )
                usedAuthoredInstances += authored.index
                val child = requireNativeValue(
                    native.newViewModel(file.requireHandle(), itemSchema.index, authored.index),
                    "create projected item view model",
                )
                children += child
                item.instanceId?.let { id ->
                    require(id.isNotEmpty() && id != projection.defaultInstanceId && id !in instanceIds) {
                        "Projected view-model instance identity is empty or duplicated"
                    }
                    instanceIds[id] = requireNativeValue(
                        native.snapshotViewModel(child), "identify projected view model",
                    ).rootInstanceId
                }
                item.values.forEach { (path, value) ->
                    val property = catalog.propertyAtPath(itemSchema.index, path)
                    val write = value.toNativeWrite(path, property.kind, property.enumLabels)
                    requireNativeSuccess(
                        native.mutateViewModel(child, write),
                        "project live ProductDetails into '$path'",
                    )
                }
                requireNativeSuccess(
                    native.mutateViewModel(
                        root,
                        NativeViewModelWrite(
                            kind = NuxieViewModelMutationKind.LIST_SET,
                            path = projection.listPath,
                            relatedViewModel = child,
                            index = item.listIndex.toLong(),
                        ),
                    ),
                    "replace projected view-model list item",
                )
                if (item.selected && projection.selectedItemPath != null) {
                    requireNativeSuccess(
                        native.mutateViewModel(
                            root,
                            NativeViewModelWrite(
                                kind = NuxieViewModelMutationKind.SET_VIEW_MODEL,
                                path = projection.selectedItemPath,
                                relatedViewModel = child,
                            ),
                        ),
                        "replace selected projected view model",
                    )
                }
            }
            requireNativeSuccess(
                native.bindViewModel(artboard.requireHandle(), root),
                "bind projected default view model",
            )
            val capturedIds = if (instanceBindings.isEmpty()) instanceIds else {
                NuxieViewModelSnapshot.fromNative(
                    requireNativeValue(native.snapshotViewModel(root), "identify projected graph"),
                    schemaNames = catalog.schemas.associate { it.index.toLong() to it.name },
                    instanceIds = instanceIds,
                    defaultInstanceId = projection.defaultInstanceId,
                ).captureInstanceIds(instanceBindings)
            }
            return NuxieRuntimeViewModelState(root, children, native, catalog, rootSchema.index,
                capturedIds, projection.defaultInstanceId)
        } catch (error: Throwable) {
            freeViewModelHandles(root, children, native)
            throw error
        }
    }

    companion object {
        val shared = NuxieRuntime()
    }
}

/**
 * Lane-confined owned runtime file. [close] is idempotent and frees the
 * native handle at most once; all other operations reject use after close.
 */
internal class NuxieRuntimeFile(
    handle: Long,
    private val native: NuxieTypedRuntimeNative,
) {
    private val owned = NuxieOwnedHandle(handle, "file", native::freeFile)

    fun newArtboard(name: String? = null): NuxieRuntimeArtboard? {
        val file = owned.require()
        val handle = if (name == null) {
            native.newDefaultArtboard(file)
        } else {
            native.newNamedArtboard(file, name)
        }
        return handle
            .takeUnless { it == 0L }
            ?.let { NuxieRuntimeArtboard(it, native, ::viewModelCatalog) }
    }

    /** Preserve the authored scene while enabling the publisher's interaction machine. */
    fun newExperiencePlayer(artboard: NuxieRuntimeArtboard, artboardName: String?): NuxieRuntimePlayer {
        val names = requireNativeValue(native.stateMachineNames(owned.require(), artboardName), "read state machines")
        val interaction = listOf("Generated Nuxie Pressable Interaction", "Generated Nuxie Interaction")
            .firstOrNull { it in names }
        val primary = checkNotNull(artboard.newPlayer()) { "Experience primary player creation failed" }
        try {
            if (interaction == null || requireNativeValue(
                native.playerStateMachineName(primary.requireHandle()), "read primary player selection",
            ) == interaction) return primary
            val auxiliary = checkNotNull(artboard.newPlayer(interaction)) { "Experience interaction player creation failed" }
            primary.installInteractionPlayer(auxiliary)
            return primary
        } catch (error: Throwable) {
            runCatching { primary.close() }.exceptionOrNull()?.let(error::addSuppressed)
            throw error
        }
    }

    private fun viewModelCatalog(): NuxieViewModelCatalog = requireNativeValue(
        native.viewModelCatalog(owned.require()),
        "read view-model catalog",
    ).toViewModelCatalog()

    fun close() = owned.close()

    internal fun requireHandle(): Long = owned.require()
}

/**
 * Lane-confined owned artboard instance. [close] is idempotent and frees the
 * native handle at most once; player creation rejects use after close.
 */
internal class NuxieRuntimeArtboard internal constructor(
    handle: Long,
    private val native: NuxieTypedRuntimeNative,
    private val viewModelCatalog: () -> NuxieViewModelCatalog,
) {
    private val owned = NuxieOwnedHandle(handle, "artboard", native::freeArtboard)
    private var defaultViewModel: NuxieOwnedHandle? = null
    private var boundDefaultSchemaName: String? = null
    private var boundDefaultInstanceId: String? = null
    private var boundInstanceIds: Map<String, Long> = emptyMap()
    private var boundCatalog: NuxieViewModelCatalog? = null
    private var boundRootSchemaIndex: Int? = null

    /** Mutate the existing signed default; never create a model for undeclared screens. */
    fun setDefaultViewModelValue(path: String, value: NuxieViewModelScalarValue): Boolean {
        owned.require()
        val model = defaultViewModel ?: return false
        writeBoundScalar(native, model.require(), checkNotNull(boundCatalog),
            checkNotNull(boundRootSchemaIndex), path, value)
        return true
    }

    /** Write one exact authored TextValueRun on the owning runtime lane. */
    fun setTextRun(name: String, text: String): Boolean {
        val result = native.setTextRun(owned.require(), name, text)
        if (result.status != NUX_STATUS_OK) throw NuxieRuntimeCallException("set text run", result.status)
        return checkNotNull(result.value) { "Native runtime returned no text mutation result" }
    }

    /** Snapshot only the signed default bound to this artboard, on its owning lane. */
    fun defaultViewModelSnapshot(): NuxieViewModelSnapshot? {
        owned.require()
        val model = defaultViewModel ?: return null
        return NuxieViewModelSnapshot.fromNative(
            requireNativeValue(native.snapshotViewModel(model.require()), "snapshot default view model"),
            schemaNames = checkNotNull(boundCatalog).schemas.associate { it.index.toLong() to it.name },
            defaultInstanceId = boundDefaultInstanceId,
            instanceIds = boundInstanceIds,
        )
    }

    /**
     * Bind this artboard's exact authored default before creating its player,
     * only when the selected signed Journey screen declares [expectedSchemaName].
     * Validate the instantiated root's schema, not merely catalog membership.
     * A missing declared default is an error; absence is handled by the caller.
     * This artboard owns the returned view-model handle.
     */
    fun bindDefaultViewModel(
        expectedSchemaName: String,
        defaultInstanceId: String? = null,
        instanceBindings: List<NuxieViewModelInstanceBinding> = emptyList(),
    ) {
        val artboard = owned.require()
        require(expectedSchemaName.isNotEmpty()) { "Declared default view-model name is empty" }
        if (defaultViewModel != null) {
            check(boundDefaultSchemaName == expectedSchemaName && boundDefaultInstanceId == defaultInstanceId) {
                "Artboard already has a different declared default view model"
            }
            return
        }
        val result = native.newDefaultViewModel(artboard)
        if (result.status != NUX_STATUS_OK) {
            throw NuxieRuntimeCallException("create default view model", result.status)
        }
        val viewModel = NuxieOwnedHandle(
            checkNotNull(result.value?.takeUnless { it == 0L }) {
                "Native runtime create default view model returned no handle"
            },
            "default view model",
        ) { value ->
            val status = native.freeViewModel(value)
            if (status != NUX_STATUS_OK) {
                throw NuxieRuntimeCallException("free default view model", status)
            }
        }
        try {
            val schema = native.viewModelRootSchemaIndex(viewModel.require())
            if (schema.status != NUX_STATUS_OK) {
                throw NuxieRuntimeCallException("read default view-model root schema", schema.status)
            }
            val schemaIndex = checkNotNull(schema.value)
            check(schemaIndex in 0..Int.MAX_VALUE.toLong()) { "Default view-model schema index is invalid" }
            val catalog = viewModelCatalog()
            val actualName = checkNotNull(catalog.schemas.singleOrNull { it.index == schemaIndex.toInt() }) {
                "Default view model references an unavailable schema"
            }.name
            check(actualName == expectedSchemaName) {
                "Declared default view model $expectedSchemaName does not match artboard default $actualName"
            }
            val instanceIds = if (instanceBindings.isEmpty()) emptyMap() else {
                NuxieViewModelSnapshot.fromNative(
                    requireNativeValue(native.snapshotViewModel(viewModel.require()), "identify authored view models"),
                    schemaNames = catalog.schemas.associate { it.index.toLong() to it.name },
                    defaultInstanceId = defaultInstanceId,
                ).captureInstanceIds(instanceBindings)
            }
            val status = native.bindViewModel(artboard, viewModel.require())
            if (status != NUX_STATUS_OK) {
                throw NuxieRuntimeCallException("bind default view model", status)
            }
            boundCatalog = catalog
            boundRootSchemaIndex = schemaIndex.toInt()
            boundInstanceIds = instanceIds
        } catch (error: Throwable) {
            runCatching { viewModel.close() }.exceptionOrNull()?.let(error::addSuppressed)
            throw error
        }
        defaultViewModel = viewModel
        boundDefaultSchemaName = expectedSchemaName
        boundDefaultInstanceId = defaultInstanceId
    }

    fun newPlayer(stateMachineName: String? = null): NuxieRuntimePlayer? {
        val artboard = owned.require()
        val handle = if (stateMachineName == null) {
            native.newDefaultPlayer(artboard)
        } else {
            native.newNamedStateMachinePlayer(artboard, stateMachineName)
        }
        return handle
            .takeUnless { it == 0L }
            ?.let { NuxieRuntimePlayer(it, native) }
    }

    fun close() {
        try {
            defaultViewModel?.close()
        } catch (error: Throwable) {
            runCatching { owned.close() }.exceptionOrNull()?.let(error::addSuppressed)
            throw error
        }
        owned.close()
    }

    internal fun requireHandle(): Long = owned.require()
}

internal class NuxieRuntimeViewModelState(
    private var root: Long?,
    children: List<Long>,
    private val native: NuxieTypedRuntimeNative,
    private val catalog: NuxieViewModelCatalog,
    private val rootSchemaIndex: Int,
    private val instanceIds: Map<String, Long> = emptyMap(),
    private val defaultInstanceId: String? = null,
) {
    private val children = children.toMutableList()

    /** Update the same root that owns projected commerce children, on its runtime lane. */
    fun setValue(path: String, value: NuxieViewModelScalarValue) {
        val rootHandle = checkNotNull(root) { "Runtime view-model state is closed" }
        writeBoundScalar(native, rootHandle, catalog, rootSchemaIndex, path, value)
    }

    /** Must be called on the owning runtime lane before the next player step. */
    fun snapshot(): NuxieViewModelSnapshot {
        val rootHandle = checkNotNull(root) { "Runtime view-model state is closed" }
        return NuxieViewModelSnapshot.fromNative(
            requireNativeValue(
                native.snapshotViewModel(rootHandle),
                "snapshot view model",
            ),
            schemaNames = catalog.schemas.associate { it.index.toLong() to it.name },
            instanceIds = instanceIds,
            defaultInstanceId = defaultInstanceId,
        )
    }

    fun close() {
        val rootHandle = root ?: return
        root = null
        freeViewModelHandles(rootHandle, children, native)
        children.clear()
    }
}

private fun writeBoundScalar(
    native: NuxieTypedRuntimeNative,
    root: Long,
    catalog: NuxieViewModelCatalog,
    rootSchemaIndex: Int,
    path: String,
    value: NuxieViewModelScalarValue,
) {
    val property = catalog.propertyAtPath(rootSchemaIndex, path)
    requireNativeSuccess(
        native.mutateViewModel(root, value.toNativeWrite(path, property.kind, property.enumLabels)),
        "write bound view-model property '$path'",
    )
}

private fun NuxieViewModelScalarValue.toNativeWrite(
    path: String,
    propertyKind: NuxieViewModelPropertyKind,
    enumLabels: List<String>,
): NativeViewModelWrite = when (this) {
    is NuxieViewModelScalarValue.StringValue -> {
        if (propertyKind == NuxieViewModelPropertyKind.ENUM) {
            val ordinal = enumLabels.indexOf(value)
            require(ordinal >= 0) { "Unknown enum label for view-model property '$path'" }
            NativeViewModelWrite(kind = NuxieViewModelMutationKind.SET_ENUM, path = path,
                integerValue = ordinal.toLong())
        } else {
            require(propertyKind == NuxieViewModelPropertyKind.STRING) {
                "View-model property '$path' is $propertyKind, not STRING"
            }
            NativeViewModelWrite(kind = NuxieViewModelMutationKind.SET_STRING,
                path = path, bytesValue = value.encodeToByteArray())
        }
    }
    is NuxieViewModelScalarValue.NumberValue -> {
        require(propertyKind == NuxieViewModelPropertyKind.NUMBER) {
            "View-model property '$path' is $propertyKind, not NUMBER"
        }
        require(value.isFinite()) { "View-model number must be finite" }
        val nativeValue = value.toFloat()
        require(nativeValue.isFinite()) { "View-model number is outside the native Float range" }
        NativeViewModelWrite(
            kind = NuxieViewModelMutationKind.SET_NUMBER,
            path = path,
            numberValue = nativeValue,
        )
    }
    is NuxieViewModelScalarValue.BooleanValue -> {
        require(propertyKind == NuxieViewModelPropertyKind.BOOLEAN) {
            "View-model property '$path' is $propertyKind, not BOOLEAN"
        }
        NativeViewModelWrite(
            kind = NuxieViewModelMutationKind.SET_BOOLEAN,
            path = path,
            boolValue = value,
        )
    }
}

private fun freeViewModelHandles(
    root: Long,
    children: List<Long>,
    native: NuxieTypedRuntimeNative,
) {
    // Drop the graph owner before the handles it references.
    native.freeViewModel(root)
    children.asReversed().forEach(native::freeViewModel)
}

/**
 * Lane-confined owned player. [close] is idempotent and frees the native
 * handle at most once; frame steps and renderer operations reject it after close.
 */
internal class NuxieRuntimePlayer internal constructor(
    handle: Long,
    private val native: NuxieTypedRuntimeNative,
) {
    private val owned = NuxieOwnedHandle(handle, "player", native::freePlayer)
    private var interactionPlayer: NuxieRuntimePlayer? = null
    private var interactionStepPending = true
    private var stepFailed = false

    fun enableSemantics() {
        val status = native.enableSemantics(requireHandle())
        if (status != NUX_STATUS_OK) throw NuxieRuntimeCallException("enable semantics", status)
    }

    fun captureSemantics(): NuxieSemanticSnapshot = NuxieSemanticSnapshot.capture(requireHandle(), native)

    /** Queue the exact authored action; normal stepping drains its generated listener output. */
    fun queueSemanticAction(snapshot: NuxieSemanticSnapshot, nodeId: Long, action: Int): Int {
        val status = snapshot.queueAction(requireHandle(), nodeId, action)
        if (status == NUX_STATUS_OK) interactionStepPending = true
        return status
    }

    internal fun installInteractionPlayer(player: NuxieRuntimePlayer) {
        check(interactionPlayer == null)
        interactionPlayer = player
    }


    fun step(elapsedSeconds: Double): Int {
        if (interactionPlayer == null) return native.stepPlayerFrame(requireHandle(), elapsedSeconds)
        stepTyped(elapsedSeconds = elapsedSeconds)
        return NUX_STATUS_OK
    }

    /**
     * Advance one configured ProductHost frame and copy every emitted event
     * before the native result is released. Presentation uses this path so a
     * signed screen action can reach the SDK's host-owned commerce executor.
     */
    fun stepWithEvents(
        elapsedSeconds: Double,
        pointers: List<NuxiePlayerPointerEvent> = emptyList(),
    ): NuxiePlayerStepOutcome = stepTyped(
        inputs = emptyList(),
        pointers = pointers,
        elapsedSeconds = elapsedSeconds,
    )

    fun stepTyped(
        inputs: List<NuxiePlayerInput> = emptyList(),
        pointers: List<NuxiePlayerPointerEvent> = emptyList(),
        elapsedSeconds: Double,
        correlationId: ULong = 0uL,
        textRunNames: List<String> = emptyList(),
    ): NuxiePlayerStepOutcome {
        require(elapsedSeconds.isFinite() && elapsedSeconds >= 0.0) {
            "Player elapsed seconds must be finite and nonnegative"
        }
        val nativeElapsed = elapsedSeconds.toFloat()
        require(nativeElapsed.isFinite()) { "Player elapsed seconds exceed the native Float range" }
        requireHandle()
        val auxiliary = interactionPlayer
        if (auxiliary == null) return stepSingle(inputs, pointers, nativeElapsed, correlationId, textRunNames)
        try {
            val stepsInteraction = interactionStepPending || inputs.isNotEmpty() || pointers.isNotEmpty()
            val primary = stepSingle(emptyList(), pointers, nativeElapsed, correlationId,
                if (stepsInteraction) emptyList() else textRunNames)
            if (!stepsInteraction) return primary
            val interaction = auxiliary.stepTyped(inputs, pointers, 0.0, correlationId, textRunNames)
            interactionStepPending = false
            return NuxiePlayerStepOutcome(
                keepGoing = primary.keepGoing || interaction.keepGoing,
                pointerHits = pointers.indices.map { index ->
                    listOfNotNull(primary.pointerHits.getOrNull(index), interaction.pointerHits.getOrNull(index))
                        .maxByOrNull { it.nativeValue } ?: NuxiePlayerPointerHit.NONE
                },
                events = primary.events + interaction.events,
                hostCommands = primary.hostCommands + interaction.hostCommands,
                viewModelChanges = primary.viewModelChanges + interaction.viewModelChanges,
                textGeometry = interaction.textGeometry,
            )
        } catch (error: Throwable) {
            // Native mutations cannot be rolled back after a partial composite step.
            // Do not publish its partial result or allow rendering/retrying that occurrence.
            stepFailed = true
            throw error
        }
    }

    private fun stepSingle(
        inputs: List<NuxiePlayerInput>,
        pointers: List<NuxiePlayerPointerEvent>,
        nativeElapsed: Float,
        correlationId: ULong,
        textRunNames: List<String>,
    ): NuxiePlayerStepOutcome {
        val result = native.stepPlayer(
            playerHandle = owned.require(),
            inputs = encodePlayerInputs(inputs),
            pointers = encodePlayerPointers(pointers),
            elapsedSeconds = nativeElapsed,
            correlationId = correlationId.toLong(),
            textRunNames = textRunNames,
        )
        if (result.status != NUX_STATUS_OK) {
            throw NuxieRuntimeCallException("step player", result.status)
        }
        return checkNotNull(result.value) {
            "Native runtime step player returned no value"
        }.toPlayerStepOutcome()
    }

    fun close() {
        try { interactionPlayer?.close() } finally { owned.close() }
    }

    internal fun requireHandle(): Long {
        check(!stepFailed) { "Experience player failed during a composite step" }
        return owned.require()
    }
}

/** JVM-owned tightly packed, top-row-first RGBA8 premultiplied-sRGB pixels. */
internal data class NuxieCpuFrame(
    val width: Int,
    val height: Int,
    val rgba: ByteArray,
) {
    init {
        require(width > 0 && height > 0) { "CPU frame dimensions must be positive" }
        val expectedBytes = width.toLong() * height.toLong() * RGBA_BYTES_PER_PIXEL
        require(expectedBytes <= Int.MAX_VALUE && rgba.size == expectedBytes.toInt()) {
            "CPU frame must contain tightly packed RGBA8 pixels"
        }
    }

    private companion object {
        const val RGBA_BYTES_PER_PIXEL = 4L
    }
}

/**
 * Lane-confined owned Android Vulkan renderer. [close] frees once; resize and
 * render/present reject use after close.
 */
internal class NuxieAndroidVulkanRenderer internal constructor(
    handle: Long,
    private val native: NuxieTypedRuntimeNative,
) {
    private val owned = NuxieOwnedHandle(handle, "Android Vulkan renderer", native::freeRenderer)

    private var attachedWindow: Long? = null
    private var copiesToWindow = false

    fun detachSurface(): Int {
        val handle = owned.require()
        if (attachedWindow == null) return NUX_STATUS_OK
        val status = if (copiesToWindow) NUX_STATUS_OK else native.detachRendererSurface(handle)
        if (status == NUX_STATUS_OK) {
            attachedWindow = null
            copiesToWindow = false
        }
        return status
    }

    fun resize(pixelWidth: Int, pixelHeight: Int): Int {
        // Android keeps the CPU producer connected after unlockAndPost. Resize
        // that producer in place instead of attempting Vulkan on the same window.
        if (copiesToWindow) return native.resizeRenderer(owned.require(), pixelWidth, pixelHeight)
        val status = detachSurface()
        if (status != NUX_STATUS_OK) return status
        return native.resizeRenderer(owned.require(), pixelWidth, pixelHeight)
    }

    fun renderAndPresent(
        player: NuxieRuntimePlayer,
        window: NuxieRuntimeWindow,
        clearColor: Int,
        fitContainCenter: Boolean,
    ): Int {
        val rendererHandle = owned.require()
        val playerHandle = player.requireHandle()
        val windowHandle = window.requireHandle()
        if (attachedWindow != windowHandle) {
            val detached = detachSurface()
            if (detached != NUX_STATUS_OK) return -detached
            val status = native.attachRendererSurface(rendererHandle, windowHandle)
            if (status != NUX_STATUS_OK && status != NUX_SURFACE_ATTACHMENT_UNSUPPORTED) {
                return if (status > 0) -status else -NUX_STATUS_RUNTIME_ERROR
            }
            attachedWindow = windowHandle
            copiesToWindow = status == NUX_SURFACE_ATTACHMENT_UNSUPPORTED
        }
        if (copiesToWindow) {
            val disposition = native.copyPlayerToWindow(
                rendererHandle, playerHandle, windowHandle, clearColor, fitContainCenter,
            )
            if (disposition < 0) detachSurface()
            return disposition
        }
        val disposition = native.renderAndPresent(
            rendererHandle, playerHandle, windowHandle, clearColor, fitContainCenter,
        )
        // REATTACH retires the surface without delivering a frame. Let the host
        // schedule its next frame normally, without activating an unseen screen.
        if (disposition == 3) {
            attachedWindow = null
            return 0
        }
        // Native SUBOPTIMAL retires the surface after delivering this frame.
        // Other errors require explicit recovery; don't reuse cached attachment.
        if (disposition == 2) attachedWindow = null
        if (disposition < 0) detachSurface()
        return disposition
    }

    fun renderToCpuFrame(
        player: NuxieRuntimePlayer,
        clearColor: Int,
        fitContainCenter: Boolean,
    ): NuxieCpuFrame = native.renderToCpuFrame(
        owned.require(),
        player.requireHandle(),
        clearColor,
        fitContainCenter,
    )

    fun close() {
        try {
            if (attachedWindow != null) detachSurface()
        } finally {
            owned.close()
        }
    }

    internal fun requireHandle(): Long = owned.require()
}

/**
 * Lane-confined owned `ANativeWindow` reference. [close] releases once and
 * render/present rejects the wrapper after close.
 */
internal class NuxieRuntimeWindow internal constructor(
    handle: Long,
    native: NuxieTypedRuntimeNative,
) {
    private val owned = NuxieOwnedHandle(handle, "window", native::releaseWindow)

    fun close() = owned.close()

    internal fun requireHandle(): Long = owned.require()
}

/** Small free-once guard shared by all lane-confined owned wrappers. */
private class NuxieOwnedHandle(
    handle: Long,
    private val name: String,
    private val free: (Long) -> Unit,
) {
    private var handle: Long? = handle

    fun require(): Long = checkNotNull(handle) { "Runtime $name is closed" }

    fun close() {
        val value = handle ?: return
        handle = null
        free(value)
    }
}

private const val NUX_STATUS_OK = 0
private const val NUX_STATUS_RUNTIME_ERROR = 4
private const val NUX_SURFACE_ATTACHMENT_UNSUPPORTED = -1
