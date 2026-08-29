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
            ?.let { NuxieRuntimeArtboard(it, native) }
    }

    fun close() = owned.close()
}

/**
 * Lane-confined owned artboard instance. [close] is idempotent and frees the
 * native handle at most once; player creation rejects use after close.
 */
internal class NuxieRuntimeArtboard internal constructor(
    handle: Long,
    private val native: NuxieTypedRuntimeNative,
) {
    private val owned = NuxieOwnedHandle(handle, "artboard", native::freeArtboard)

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

    fun close() = owned.close()
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

    fun step(elapsedSeconds: Double): Int =
        native.stepPlayerFrame(owned.require(), elapsedSeconds)

    fun close() = owned.close()

    internal fun requireHandle(): Long = owned.require()
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
 * Lane-confined owned Android Vulkan renderer. [close] frees once; resize,
 * render/present, and player-domain reset all reject use after close.
 */
internal class NuxieAndroidVulkanRenderer internal constructor(
    handle: Long,
    private val native: NuxieTypedRuntimeNative,
) {
    private val owned = NuxieOwnedHandle(handle, "Android Vulkan renderer", native::freeRenderer)

    fun resize(pixelWidth: Int, pixelHeight: Int): Int =
        native.resizeRenderer(owned.require(), pixelWidth, pixelHeight)

    fun renderAndPresent(
        player: NuxieRuntimePlayer,
        window: NuxieRuntimeWindow,
        clearColor: Int,
        fitContainCenter: Boolean,
    ): Int = native.renderAndPresent(
        owned.require(),
        player.requireHandle(),
        window.requireHandle(),
        clearColor,
        fitContainCenter,
    )

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

    fun resetPlayerDomain(player: NuxieRuntimePlayer): Int =
        native.resetPlayerDomain(owned.require(), player.requireHandle())

    fun close() = owned.close()

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
