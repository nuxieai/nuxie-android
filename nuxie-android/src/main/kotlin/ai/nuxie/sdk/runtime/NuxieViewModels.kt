package ai.nuxie.sdk.runtime

/** Exact property-kind values published by `NuxViewModelValueKind`. */
internal enum class NuxieViewModelPropertyKind(val nativeValue: Int) {
    UNSUPPORTED(0),
    STRING(1),
    NUMBER(2),
    BOOLEAN(3),
    COLOR(4),
    ENUM(5),
    TRIGGER(6),
    LIST_INDEX(7),
    LIST(8),
    VIEW_MODEL(9),
    IMAGE(10),
    FONT(11),
    BLOB(12),
    ARTBOARD(13),
    ;

    companion object {
        fun fromNativeValue(value: Int): NuxieViewModelPropertyKind? =
            entries.firstOrNull { it.nativeValue == value }
    }
}

/** Immutable copy of a file's runtime-authored view-model catalog. */
internal data class NuxieViewModelCatalog(
    val schemas: List<Schema>,
    val properties: List<Property>,
    val authoredInstances: List<AuthoredInstance>,
) {
    data class Schema(
        val index: Int,
        val name: String,
        val propertyRange: IntRange,
        val authoredInstanceRange: IntRange,
        val defaultAuthoredInstance: Int?,
        val isGlobal: Boolean,
    )

    data class Property(
        val schemaIndex: Int,
        val index: Int,
        val name: String,
        val kind: NuxieViewModelPropertyKind,
        val referencedSchemaIndex: Int?,
        val enumLabels: List<String>,
    )

    data class AuthoredInstance(
        val schemaIndex: Int,
        val index: Int,
        val name: String?,
    )

    internal fun propertyAtPath(rootSchemaIndex: Int, path: String): Property {
        val directMatches = properties.filter {
            it.schemaIndex == rootSchemaIndex && it.name == path
        }
        require(directMatches.size <= 1) {
            "Ambiguous view-model property '$path' in schema $rootSchemaIndex"
        }
        directMatches.singleOrNull()?.let { return it }

        val segments = path.split('/')
        require(segments.isNotEmpty() && segments.none(String::isEmpty)) {
            "Invalid view-model property path '$path'"
        }
        var schemaIndex = rootSchemaIndex
        segments.forEachIndexed { offset, name ->
            val matches = properties.filter {
                it.schemaIndex == schemaIndex && it.name == name
            }
            require(matches.size == 1) {
                val reason = if (matches.isEmpty()) "Unknown" else "Ambiguous"
                "$reason view-model property path '$path' at segment '$name'"
            }
            val property = matches.single()
            if (offset == segments.lastIndex) return property
            require(property.kind == NuxieViewModelPropertyKind.VIEW_MODEL) {
                "View-model property path '$path' cannot descend through '$name'"
            }
            schemaIndex = requireNotNull(property.referencedSchemaIndex) {
                "View-model property '$name' has no referenced schema"
            }
        }
        error("Invalid view-model property path '$path'")
    }
}

internal enum class NuxieViewModelMutationKind(val nativeValue: Int) {
    SET_STRING(0),
    SET_NUMBER(1),
    SET_BOOLEAN(2),
    SET_COLOR(3),
    SET_ENUM(4),
    FIRE_TRIGGER(5),
}

/** Fixed Kotlin-side encoding of one ABI-v4 `NuxViewModelMutation`. */
internal data class NativeViewModelWrite(
    val kind: NuxieViewModelMutationKind,
    val path: String,
    val bytesValue: ByteArray = byteArrayOf(),
    val numberValue: Float = 0f,
    val integerValue: Long = 0,
    val boolValue: Boolean = false,
)

internal data class NativeCallResult<out T>(val status: Int, val value: T?)

/** Injectable raw seam; production delegates to [NuxieRuntimeBridge]. */
internal interface NuxieTypedRuntimeNative {
    val isAvailable: Boolean get() = error("isAvailable is not implemented")

    fun runtimeInfo(): String = error("runtimeInfo is not implemented")

    fun inspectFileAssets(bytes: ByteArray): List<ExpectedFileAsset>? =
        error("inspectFileAssets is not implemented")

    fun newFile(
        rendererHandle: Long,
        bytes: ByteArray,
        expectedAssets: List<ExpectedFileAsset>,
        externalAssets: Map<Int, ByteArray>,
        imageDecoder: NuxImageDecoder,
    ): Long = error("newFile is not implemented")

    fun freeFile(handle: Long): Unit = error("freeFile is not implemented")

    fun newDefaultArtboard(fileHandle: Long): Long =
        error("newDefaultArtboard is not implemented")

    fun newNamedArtboard(fileHandle: Long, name: String): Long =
        error("newNamedArtboard is not implemented")

    fun freeArtboard(handle: Long): Unit = error("freeArtboard is not implemented")

    fun newDefaultPlayer(artboardHandle: Long): Long =
        error("newDefaultPlayer is not implemented")

    fun newNamedStateMachinePlayer(artboardHandle: Long, name: String): Long =
        error("newNamedStateMachinePlayer is not implemented")

    fun stepPlayerFrame(playerHandle: Long, elapsedSeconds: Double): Int =
        error("stepPlayerFrame is not implemented")

    fun freePlayer(handle: Long): Unit = error("freePlayer is not implemented")

    fun newAndroidVulkanRenderer(pixelWidth: Int, pixelHeight: Int): Long =
        error("newAndroidVulkanRenderer is not implemented")

    fun resizeRenderer(handle: Long, pixelWidth: Int, pixelHeight: Int): Int =
        error("resizeRenderer is not implemented")

    fun renderAndPresent(
        rendererHandle: Long,
        playerHandle: Long,
        windowHandle: Long,
        clearColor: Int,
        fitContainCenter: Boolean,
    ): Int = error("renderAndPresent is not implemented")

    fun renderToCpuFrame(
        rendererHandle: Long,
        playerHandle: Long,
        clearColor: Int,
        fitContainCenter: Boolean,
    ): NuxieCpuFrame = error("renderToCpuFrame is not implemented")

    fun freeRenderer(handle: Long): Unit = error("freeRenderer is not implemented")

    fun acquireWindow(surface: android.view.Surface): Long =
        error("acquireWindow is not implemented")

    fun releaseWindow(handle: Long): Unit = error("releaseWindow is not implemented")

    fun viewModelCatalog(fileHandle: Long): NativeCallResult<NativeViewModelCatalog> =
        error("viewModelCatalog is not implemented")

    fun newViewModel(
        fileHandle: Long,
        schemaIndex: Int,
        authoredInstanceIndex: Int?,
    ): NativeCallResult<Long> = error("newViewModel is not implemented")

    fun newDefaultViewModel(artboardHandle: Long): NativeCallResult<Long> =
        error("newDefaultViewModel is not implemented")

    fun viewModelRootSchemaIndex(viewModelHandle: Long): NativeCallResult<Long> =
        error("viewModelRootSchemaIndex is not implemented")

    fun bindViewModel(artboardHandle: Long, viewModelHandle: Long): Int =
        error("bindViewModel is not implemented")

    fun mutateViewModel(handle: Long, write: NativeViewModelWrite): Int =
        error("mutateViewModel is not implemented")

    fun stepPlayer(
        playerHandle: Long,
        inputs: List<NativePlayerInput>,
        elapsedSeconds: Float,
        correlationId: Long,
    ): NativeCallResult<NativePlayerStepOutcome> = error("stepPlayer is not implemented")

    fun freeViewModel(handle: Long): Int = error("freeViewModel is not implemented")
}

internal object JniNuxieTypedRuntimeNative : NuxieTypedRuntimeNative {
    override val isAvailable: Boolean get() = NuxieRuntimeBridge.isAvailable

    override fun runtimeInfo(): String = NuxieRuntimeBridge.nativeRuntimeInfo()

    override fun inspectFileAssets(bytes: ByteArray): List<ExpectedFileAsset>? =
        NuxieRuntimeBridge.inspectFileAssets(bytes)

    override fun newFile(
        rendererHandle: Long,
        bytes: ByteArray,
        expectedAssets: List<ExpectedFileAsset>,
        externalAssets: Map<Int, ByteArray>,
        imageDecoder: NuxImageDecoder,
    ): Long = NuxieRuntimeBridge.fileNew(
        rendererHandle,
        bytes,
        expectedAssets,
        externalAssets,
        imageDecoder,
    )

    override fun freeFile(handle: Long) {
        NuxieRuntimeBridge.nativeFileFree(handle)
    }

    override fun newDefaultArtboard(fileHandle: Long): Long =
        NuxieRuntimeBridge.nativeArtboardInstanceNewDefault(fileHandle)

    override fun newNamedArtboard(fileHandle: Long, name: String): Long =
        NuxieRuntimeBridge.nativeArtboardInstanceNewNamed(fileHandle, name)

    override fun freeArtboard(handle: Long) {
        NuxieRuntimeBridge.nativeArtboardInstanceFree(handle)
    }

    override fun newDefaultPlayer(artboardHandle: Long): Long =
        NuxieRuntimeBridge.nativePlayerNewDefault(artboardHandle)

    override fun newNamedStateMachinePlayer(artboardHandle: Long, name: String): Long =
        NuxieRuntimeBridge.nativePlayerNewStateMachineNamed(artboardHandle, name)

    override fun stepPlayerFrame(playerHandle: Long, elapsedSeconds: Double): Int =
        NuxieRuntimeBridge.nativePlayerStep(playerHandle, elapsedSeconds)

    override fun freePlayer(handle: Long) {
        NuxieRuntimeBridge.nativePlayerFree(handle)
    }

    override fun newAndroidVulkanRenderer(pixelWidth: Int, pixelHeight: Int): Long =
        NuxieRuntimeBridge.nativeRendererNewAndroidVulkan(pixelWidth, pixelHeight)

    override fun resizeRenderer(handle: Long, pixelWidth: Int, pixelHeight: Int): Int =
        NuxieRuntimeBridge.nativeRendererResize(handle, pixelWidth, pixelHeight)

    override fun renderAndPresent(
        rendererHandle: Long,
        playerHandle: Long,
        windowHandle: Long,
        clearColor: Int,
        fitContainCenter: Boolean,
    ): Int = NuxieRuntimeBridge.nativeRendererRenderPlayer(
        rendererHandle,
        playerHandle,
        windowHandle,
        clearColor,
        fitContainCenter,
    )

    override fun renderToCpuFrame(
        rendererHandle: Long,
        playerHandle: Long,
        clearColor: Int,
        fitContainCenter: Boolean,
    ): NuxieCpuFrame = checkNotNull(
        NuxieRuntimeBridge.nativeRendererRenderPlayerToCpuFrame(
            rendererHandle,
            playerHandle,
            clearColor,
            fitContainCenter,
        ),
    ) { "Android Vulkan renderer did not return a CPU frame" }

    override fun freeRenderer(handle: Long) {
        NuxieRuntimeBridge.nativeRendererFree(handle)
    }

    override fun acquireWindow(surface: android.view.Surface): Long =
        NuxieRuntimeBridge.nativeWindowAcquire(surface)

    override fun releaseWindow(handle: Long) {
        NuxieRuntimeBridge.nativeWindowRelease(handle)
    }

    override fun viewModelCatalog(fileHandle: Long): NativeCallResult<NativeViewModelCatalog> {
        val status = intArrayOf(NUX_STATUS_RUNTIME_ERROR)
        val catalog = NuxieRuntimeBridge.nativeFileViewModelCatalog(fileHandle, status)
        return NativeCallResult(status.single(), catalog)
    }

    override fun newViewModel(
        fileHandle: Long,
        schemaIndex: Int,
        authoredInstanceIndex: Int?,
    ): NativeCallResult<Long> {
        val status = intArrayOf(NUX_STATUS_RUNTIME_ERROR)
        val handle = NuxieRuntimeBridge.nativeViewModelInstanceNew(
            fileHandle,
            schemaIndex,
            authoredInstanceIndex ?: -1,
            status,
        )
        return NativeCallResult(status.single(), handle.takeUnless { it == 0L })
    }

    override fun newDefaultViewModel(artboardHandle: Long): NativeCallResult<Long> {
        val status = intArrayOf(NUX_STATUS_RUNTIME_ERROR)
        val handle = NuxieRuntimeBridge.nativeViewModelInstanceNewDefault(artboardHandle, status)
        return NativeCallResult(status.single(), handle.takeUnless { it == 0L })
    }

    override fun viewModelRootSchemaIndex(viewModelHandle: Long): NativeCallResult<Long> {
        val status = intArrayOf(NUX_STATUS_RUNTIME_ERROR)
        val index = NuxieRuntimeBridge.nativeViewModelRootSchemaIndex(viewModelHandle, status)
        return NativeCallResult(status.single(), index.takeIf { status.single() == NUX_STATUS_OK })
    }

    override fun bindViewModel(artboardHandle: Long, viewModelHandle: Long): Int =
        NuxieRuntimeBridge.nativeArtboardInstanceBindViewModel(artboardHandle, viewModelHandle)

    override fun mutateViewModel(handle: Long, write: NativeViewModelWrite): Int =
        NuxieRuntimeBridge.nativeViewModelMutate(
            viewModel = handle,
            kind = write.kind.nativeValue,
            path = write.path.encodeToByteArray(),
            bytesValue = write.bytesValue,
            numberValue = write.numberValue,
            integerValue = write.integerValue,
            boolValue = write.boolValue,
        )

    override fun stepPlayer(
        playerHandle: Long,
        inputs: List<NativePlayerInput>,
        elapsedSeconds: Float,
        correlationId: Long,
    ): NativeCallResult<NativePlayerStepOutcome> {
        val status = intArrayOf(NUX_STATUS_RUNTIME_ERROR)
        val outcome = NuxieRuntimeBridge.nativePlayerStepTyped(
            player = playerHandle,
            inputKinds = inputs.map(NativePlayerInput::kind).toIntArray(),
            inputNames = inputs.map { it.name.encodeToByteArray() }.toTypedArray(),
            inputBoolValues = inputs.map(NativePlayerInput::boolValue).toBooleanArray(),
            inputNumberValues = inputs.map(NativePlayerInput::numberValue).toFloatArray(),
            elapsedSeconds = elapsedSeconds,
            correlationId = correlationId,
            statusOut = status,
        )
        return NativeCallResult(status.single(), outcome)
    }

    override fun freeViewModel(handle: Long): Int =
        NuxieRuntimeBridge.nativeViewModelInstanceFree(handle)
}

/**
 * Lane-confined typed data-binding operations for an already-created runtime
 * file/artboard/player tuple. This deliberately does not alter presentation's
 * existing handle path in this slice.
 */
internal class NuxieRuntimeViewModels(
    private val lane: NuxieRuntimeLane,
    private val fileHandle: Long,
    private val artboardHandle: Long,
    private val playerHandle: Long,
    private val native: NuxieTypedRuntimeNative = JniNuxieTypedRuntimeNative,
) {
    suspend fun viewModelCatalog(): NuxieViewModelCatalog = lane.call {
        requireNativeValue(
            native.viewModelCatalog(fileHandle),
            "read view-model catalog",
        ).toViewModelCatalog()
    }

    suspend fun bindViewModelToPlayer(
        catalog: NuxieViewModelCatalog,
        schemaIndex: Int,
        authoredInstanceIndex: Int? = null,
    ): NuxieBoundViewModel {
        require(catalog.schemas.any { it.index == schemaIndex }) {
            "Unknown view-model schema index $schemaIndex"
        }
        if (authoredInstanceIndex != null) {
            require(
                catalog.authoredInstances.any {
                    it.schemaIndex == schemaIndex && it.index == authoredInstanceIndex
                },
            ) {
                "Unknown authored instance $authoredInstanceIndex for schema $schemaIndex"
            }
        }
        val handle = lane.call {
            val created = requireNativeValue(
                native.newViewModel(fileHandle, schemaIndex, authoredInstanceIndex),
                "create view model",
            )
            val bindStatus = native.bindViewModel(artboardHandle, created)
            if (bindStatus != NUX_STATUS_OK) {
                throwBindFailureAfterCleanup(created, bindStatus, "bind view model")
            }
            created
        }
        return NuxieBoundViewModel(lane, handle, schemaIndex, catalog, native)
    }

    suspend fun bindDefaultViewModelToPlayer(
        catalog: NuxieViewModelCatalog,
        rootSchemaIndex: Int,
    ): NuxieBoundViewModel {
        require(catalog.schemas.any { it.index == rootSchemaIndex }) {
            "Unknown view-model schema index $rootSchemaIndex"
        }
        val handle = lane.call {
            val created = requireNativeValue(
                native.newDefaultViewModel(artboardHandle),
                "create default view model",
            )
            val bindStatus = native.bindViewModel(artboardHandle, created)
            if (bindStatus != NUX_STATUS_OK) {
                throwBindFailureAfterCleanup(created, bindStatus, "bind default view model")
            }
            created
        }
        return NuxieBoundViewModel(lane, handle, rootSchemaIndex, catalog, native)
    }

    suspend fun step(
        inputs: List<NuxiePlayerInput> = emptyList(),
        elapsedSeconds: Double,
        correlationId: ULong = 0uL,
    ): NuxiePlayerStepOutcome {
        require(elapsedSeconds.isFinite() && elapsedSeconds >= 0.0) {
            "Player elapsed seconds must be finite and nonnegative"
        }
        val nativeElapsed = elapsedSeconds.toFloat()
        require(nativeElapsed.isFinite()) { "Player elapsed seconds exceed the native Float range" }
        val encodedInputs = encodePlayerInputs(inputs)
        return lane.call {
            requireNativeValue(
                native.stepPlayer(
                    playerHandle,
                    encodedInputs,
                    nativeElapsed,
                    correlationId.toLong(),
                ),
                "step player",
            ).toPlayerStepOutcome()
        }
    }

    private fun throwBindFailureAfterCleanup(
        created: Long,
        bindStatus: Int,
        operation: String,
    ): Nothing {
        val bindFailure = NuxieRuntimeCallException(operation, bindStatus)
        val freeStatus = native.freeViewModel(created)
        if (freeStatus != NUX_STATUS_OK && freeStatus != NUX_STATUS_RUNTIME_ERROR) {
            val cleanupFailure = NuxieRuntimeCallException(
                "free view model after failed bind",
                freeStatus,
            )
            bindFailure.addSuppressed(cleanupFailure)
        }
        throw bindFailure
    }
}

/**
 * A retained runtime view model already bound to its artboard occurrence.
 * The native handle never escapes this type and every operation is serialized
 * through [lane].
 */
internal class NuxieBoundViewModel(
    private val lane: NuxieRuntimeLane,
    private val nativeHandle: Long,
    private val rootSchemaIndex: Int,
    private val catalog: NuxieViewModelCatalog,
    private val native: NuxieTypedRuntimeNative,
) {
    private var closed = false

    suspend fun setString(path: String, value: String) {
        requireProperty(path, NuxieViewModelPropertyKind.STRING)
        write(
            NativeViewModelWrite(
                kind = NuxieViewModelMutationKind.SET_STRING,
                path = path,
                bytesValue = value.encodeToByteArray(),
            ),
        )
    }

    suspend fun setNumber(path: String, value: Double) {
        requireProperty(path, NuxieViewModelPropertyKind.NUMBER)
        require(value.isFinite()) { "View-model number must be finite" }
        val nativeValue = value.toFloat()
        require(nativeValue.isFinite()) { "View-model number is outside the native Float range" }
        write(
            NativeViewModelWrite(
                kind = NuxieViewModelMutationKind.SET_NUMBER,
                path = path,
                numberValue = nativeValue,
            ),
        )
    }

    suspend fun setBoolean(path: String, value: Boolean) {
        requireProperty(path, NuxieViewModelPropertyKind.BOOLEAN)
        write(
            NativeViewModelWrite(
                kind = NuxieViewModelMutationKind.SET_BOOLEAN,
                path = path,
                boolValue = value,
            ),
        )
    }

    suspend fun setColor(path: String, argb: Int) {
        requireProperty(path, NuxieViewModelPropertyKind.COLOR)
        write(
            NativeViewModelWrite(
                kind = NuxieViewModelMutationKind.SET_COLOR,
                path = path,
                integerValue = argb.toUInt().toLong(),
            ),
        )
    }

    suspend fun setEnum(path: String, value: String) {
        val property = requireProperty(path, NuxieViewModelPropertyKind.ENUM)
        val ordinal = property.enumLabels.indexOf(value)
        require(ordinal >= 0) {
            "Unknown enum label '$value' for view-model property '$path'"
        }
        write(
            NativeViewModelWrite(
                kind = NuxieViewModelMutationKind.SET_ENUM,
                path = path,
                integerValue = ordinal.toLong(),
            ),
        )
    }

    suspend fun fireTrigger(path: String) {
        requireProperty(path, NuxieViewModelPropertyKind.TRIGGER)
        write(NativeViewModelWrite(NuxieViewModelMutationKind.FIRE_TRIGGER, path))
    }

    suspend fun close() {
        lane.call {
            if (closed) return@call
            val status = native.freeViewModel(nativeHandle)
            // The ABI consumes owned handles even when their destructor
            // reports a runtime error, matching the iOS owned-handle rule.
            if (status == NUX_STATUS_OK || status == NUX_STATUS_RUNTIME_ERROR) {
                closed = true
            }
            requireNativeSuccess(status, "free view model")
        }
    }

    private suspend fun write(value: NativeViewModelWrite) {
        lane.call {
            check(!closed) { "Bound view model is closed" }
            requireNativeSuccess(native.mutateViewModel(nativeHandle, value), "write view model")
        }
    }

    private fun requireProperty(
        path: String,
        kind: NuxieViewModelPropertyKind,
    ): NuxieViewModelCatalog.Property {
        val property = catalog.propertyAtPath(rootSchemaIndex, path)
        require(property.kind == kind) {
            "View-model property '$path' is ${property.kind}, not $kind"
        }
        return property
    }
}

internal class NuxieRuntimeCallException(
    operation: String,
    val status: Int,
) : IllegalStateException("Native runtime $operation failed with status $status")

private fun requireNativeSuccess(status: Int, operation: String) {
    if (status != NUX_STATUS_OK) throw NuxieRuntimeCallException(operation, status)
}

private fun <T> requireNativeValue(result: NativeCallResult<T>, operation: String): T {
    requireNativeSuccess(result.status, operation)
    return checkNotNull(result.value) { "Native runtime $operation returned no value" }
}

private const val NUX_STATUS_OK = 0
private const val NUX_STATUS_RUNTIME_ERROR = 4

/** JNI construction shapes. They are validated and copied before exposure. */
internal data class NativeViewModelCatalog(
    val schemas: Array<NativeViewModelSchema>,
    val properties: Array<NativeViewModelProperty>,
    val authoredInstances: Array<NativeViewModelAuthoredInstance>,
)

internal data class NativeViewModelSchema(
    val index: Long,
    val name: String,
    val firstProperty: Long,
    val propertyCount: Long,
    val firstAuthoredInstance: Long,
    val authoredInstanceCount: Long,
    val defaultAuthoredInstance: Long,
    val isGlobal: Boolean,
)

internal data class NativeViewModelProperty(
    val schemaIndex: Long,
    val index: Long,
    val name: String,
    val kind: Int,
    val referencedSchemaIndex: Long,
    val enumLabels: Array<String>,
)

internal data class NativeViewModelAuthoredInstance(
    val schemaIndex: Long,
    val index: Long,
    val name: String?,
)

internal fun NativeViewModelCatalog.toViewModelCatalog(): NuxieViewModelCatalog {
    val mappedSchemas = schemas.map { schema ->
        val firstProperty = schema.firstProperty.checkedIndex("first property")
        val propertyCount = schema.propertyCount.checkedIndex("property count")
        val firstInstance = schema.firstAuthoredInstance.checkedIndex("first authored instance")
        val instanceCount = schema.authoredInstanceCount.checkedIndex("authored instance count")
        NuxieViewModelCatalog.Schema(
            index = schema.index.checkedIndex("schema index"),
            name = schema.name,
            propertyRange = checkedRange(firstProperty, propertyCount, "property range"),
            authoredInstanceRange = checkedRange(
                firstInstance,
                instanceCount,
                "authored instance range",
            ),
            defaultAuthoredInstance = schema.defaultAuthoredInstance.optionalIndex(
                "default authored instance",
            ),
            isGlobal = schema.isGlobal,
        )
    }
    val mappedProperties = properties.map { property ->
        NuxieViewModelCatalog.Property(
            schemaIndex = property.schemaIndex.checkedIndex("property schema index"),
            index = property.index.checkedIndex("property index"),
            name = property.name,
            kind = checkNotNull(NuxieViewModelPropertyKind.fromNativeValue(property.kind)) {
                "Unknown native view-model property kind ${property.kind}"
            },
            referencedSchemaIndex = property.referencedSchemaIndex.optionalIndex(
                "referenced schema index",
            ),
            enumLabels = property.enumLabels.toList(),
        )
    }
    val mappedInstances = authoredInstances.map { instance ->
        NuxieViewModelCatalog.AuthoredInstance(
            schemaIndex = instance.schemaIndex.checkedIndex("instance schema index"),
            index = instance.index.checkedIndex("authored instance index"),
            name = instance.name,
        )
    }
    return NuxieViewModelCatalog(mappedSchemas, mappedProperties, mappedInstances)
}

private fun Long.checkedIndex(label: String): Int {
    check(this in 0..Int.MAX_VALUE.toLong()) { "$label is out of Kotlin range: $this" }
    return toInt()
}

private fun Long.optionalIndex(label: String): Int? = when (this) {
    -1L -> null
    else -> checkedIndex(label)
}

private fun checkedRange(first: Int, count: Int, label: String): IntRange {
    val endExclusive = first.toLong() + count.toLong()
    check(endExclusive <= Int.MAX_VALUE.toLong()) { "$label overflows Kotlin range" }
    return first until endExclusive.toInt()
}
