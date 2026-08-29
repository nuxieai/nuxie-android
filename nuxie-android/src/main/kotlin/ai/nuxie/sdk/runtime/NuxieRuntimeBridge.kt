package ai.nuxie.sdk.runtime

import android.util.Log
import java.io.File

/**
 * JNI binding surface over the engine's portable C ABI (`nux_capi`) plus the
 * headless Android Vulkan presentation extension
 * (`nux_renderer_*_android_vulkan`).
 *
 * The handle-based, owned-result contract mirrors the iOS adapter: every
 * `native*` call returns a status or an owned handle the caller must free,
 * all calls are confined to the [NuxieRuntimeLane], and panics never cross
 * the boundary (the shim converts them to error statuses).
 *
 * The engine `.so` is a pinned prebuilt artifact (spec section 7). When it
 * is absent or fails to load, the SDK degrades per section 16 decision 3:
 * setup succeeds, experiences never present.
 */
internal object NuxieRuntimeBridge {
    private const val LOG_TAG = "Nuxie"
    private const val LIBRARY = "nuxie_runtime_android"
    private const val HOST_CAPI_ENV = "NUXIE_HOST_CAPI_LIB"
    private const val HOST_JNI_PROPERTY = "nuxie.host.jni.lib"

    val isAvailable: Boolean by lazy {
        val hostCapi = if (isAndroidRuntime()) {
            null
        } else {
            System.getenv(HOST_CAPI_ENV)?.takeIf(String::isNotBlank)
        }
        if (hostCapi == null) loadAndroidRuntime() else loadHostRuntime(hostCapi)
    }

    private fun isAndroidRuntime(): Boolean =
        System.getProperty("java.runtime.name") == "Android Runtime" ||
            System.getProperty("java.vm.name") == "Dalvik"

    private fun loadAndroidRuntime(): Boolean = try {
        // Keep the shipped Android loader and failure behavior unchanged.
        System.loadLibrary(LIBRARY)
        true
    } catch (error: UnsatisfiedLinkError) {
        Log.i(
            LOG_TAG,
            "Nuxie runtime library unavailable; experiences will not present.",
            error,
        )
        false
    }

    private fun loadHostRuntime(hostCapi: String): Boolean {
        val capiFile = File(hostCapi).canonicalFile
        require(capiFile.isFile) { "$HOST_CAPI_ENV is not a file: $capiFile" }
        val hostJni = System.getProperty(HOST_JNI_PROPERTY)
            ?.takeIf(String::isNotBlank)
            ?.let(::File)
            ?.canonicalFile
            ?: error("Missing -D$HOST_JNI_PROPERTY for the host JNI adapter")
        require(hostJni.isFile) { "Host JNI adapter is not a file: $hostJni" }
        try {
            System.load(capiFile.path)
            System.load(hostJni.path)
        } catch (error: UnsatisfiedLinkError) {
            throw IllegalStateException(
                "Host nux_capi is missing required Vulkan or scripting symbols. " +
                    "Build it with `$HOST_BUILD_COMMAND` and set $HOST_CAPI_ENV to that library. " +
                    "Native loader: ${error.message}",
                error,
            )
        }
        val scriptingStatus = nativeHostScriptingProbe()
        check(scriptingStatus == NUX_STATUS_OK) {
            "Host nux_capi was built without working scripting support " +
                "(probe status $scriptingStatus). Build it with `$HOST_BUILD_COMMAND` and set " +
                "$HOST_CAPI_ENV to that library."
        }
        return true
    }

    private external fun nativeHostScriptingProbe(): Int

    // MARK: portable core (nux_capi)

    /** Factory-first import over verified release bytes -> file handle (0 = failure). */
    external fun nativeFileNew(renderer: Long, bytes: ByteArray): Long

    /** Script-inert import followed by an exact copy of the authored catalog. */
    private external fun nativeFileInspectAssets(bytes: ByteArray): Array<NativeExpectedFileAsset>?

    /** Configured product import carrying host commands, asset hooks, and exact catalog. */
    private external fun nativeFileNewConfigured(
        renderer: Long,
        bytes: ByteArray,
        ordinals: IntArray,
        kinds: IntArray,
        hasAuthoredIds: BooleanArray,
        authoredIds: LongArray,
        names: Array<ByteArray>,
        fileExtensions: Array<ByteArray>,
        embedded: BooleanArray,
        contentsRecords: BooleanArray,
        providerFlags: IntArray,
        externalOrdinals: IntArray,
        externalPayloads: Array<ByteArray>,
        imageDecoder: NuxImageDecoder,
    ): Long

    fun inspectFileAssets(bytes: ByteArray): List<ExpectedFileAsset>? =
        nativeFileInspectAssets(bytes)?.map { native ->
            ExpectedFileAsset(
                ordinal = native.ordinal,
                kind = FileAssetKind.fromNativeValue(native.kind) ?: return null,
                authoredId = native.authoredId.takeIf { native.hasAuthoredId },
                name = native.name,
                fileExtension = native.fileExtension,
                isEmbedded = native.isEmbedded,
                hasContentsRecord = native.hasContentsRecord,
                requiredProviderFlags = native.requiredProviderFlags,
            )
        }

    fun fileNew(
        renderer: Long,
        bytes: ByteArray,
        expectedAssets: List<ExpectedFileAsset> = emptyList(),
        externalAssets: Map<Int, ByteArray> = emptyMap(),
        imageDecoder: NuxImageDecoder = AndroidImageDecoder,
    ): Long {
        if (expectedAssets.isEmpty()) {
            return if (externalAssets.isEmpty()) nativeFileNew(renderer, bytes) else 0L
        }
        if (!expectedAssets.withIndex().all { (index, asset) -> asset.ordinal == index } ||
            !externalAssets.keys.all { it in expectedAssets.indices }
        ) {
            return 0L
        }
        val external = externalAssets.toSortedMap()
        return nativeFileNewConfigured(
            renderer = renderer,
            bytes = bytes,
            ordinals = expectedAssets.map(ExpectedFileAsset::ordinal).toIntArray(),
            kinds = expectedAssets.map { it.kind.nativeValue }.toIntArray(),
            hasAuthoredIds = expectedAssets.map { it.authoredId != null }.toBooleanArray(),
            authoredIds = expectedAssets.map { it.authoredId ?: 0L }.toLongArray(),
            names = expectedAssets.map { it.name.encodeToByteArray() }.toTypedArray(),
            fileExtensions = expectedAssets
                .map { it.fileExtension.encodeToByteArray() }.toTypedArray(),
            embedded = expectedAssets.map(ExpectedFileAsset::isEmbedded).toBooleanArray(),
            contentsRecords = expectedAssets
                .map(ExpectedFileAsset::hasContentsRecord).toBooleanArray(),
            providerFlags = expectedAssets
                .map(ExpectedFileAsset::requiredProviderFlags).toIntArray(),
            externalOrdinals = external.keys.toIntArray(),
            externalPayloads = external.values.toTypedArray(),
            imageDecoder = imageDecoder,
        )
    }

    external fun nativeFileFree(file: Long)

    /** Copies `nux_file_view_model_catalog` into JVM-owned construction shapes. */
    external fun nativeFileViewModelCatalog(
        file: Long,
        statusOut: IntArray,
    ): NativeViewModelCatalog?

    /** nux_artboard_instance_new_named -> artboard handle (0 = failure). */
    external fun nativeArtboardInstanceNewNamed(file: Long, artboardName: String): Long

    /** nux_artboard_instance_new at index 0 (the default artboard). */
    external fun nativeArtboardInstanceNewDefault(file: Long): Long

    external fun nativeArtboardInstanceFree(artboard: Long)

    external fun nativeViewModelInstanceNew(
        file: Long,
        schemaIndex: Int,
        authoredInstanceIndex: Int,
        statusOut: IntArray,
    ): Long

    external fun nativeViewModelInstanceNewDefault(artboard: Long, statusOut: IntArray): Long

    external fun nativeArtboardInstanceBindViewModel(artboard: Long, viewModel: Long): Int

    external fun nativeViewModelMutate(
        viewModel: Long,
        kind: Int,
        path: ByteArray,
        bytesValue: ByteArray,
        numberValue: Float,
        integerValue: Long,
        boolValue: Boolean,
    ): Int

    external fun nativeViewModelInstanceFree(viewModel: Long): Int

    /** nux_player_new_default over an artboard -> player handle (0 = failure). */
    external fun nativePlayerNewDefault(artboard: Long): Long

    /** nux_player_new_state_machine_named -> player handle (0 = failure). */
    external fun nativePlayerNewStateMachineNamed(artboard: Long, stateMachineName: String): Long

    external fun nativePlayerFree(player: Long)

    /** nux_player_step: advance by elapsed seconds; returns a status code. */
    external fun nativePlayerStep(player: Long, elapsedSeconds: Double): Int

    external fun nativePlayerStepTyped(
        player: Long,
        inputKinds: IntArray,
        inputNames: Array<ByteArray>,
        inputBoolValues: BooleanArray,
        inputNumberValues: FloatArray,
        elapsedSeconds: Float,
        correlationId: Long,
        statusOut: IntArray,
    ): NativePlayerStepOutcome?

    // MARK: Android Vulkan presentation extension

    /**
     * nux_renderer_new_android_vulkan at the requested pixel extent ->
     * renderer handle (0 = failure).
     */
    external fun nativeRendererNewAndroidVulkan(
        pixelWidth: Int,
        pixelHeight: Int,
    ): Long

    external fun nativeRendererResize(renderer: Long, pixelWidth: Int, pixelHeight: Int): Int

    /** Acquire an ANativeWindow reference from a Surface (0 = failure). */
    external fun nativeWindowAcquire(surface: android.view.Surface): Long

    external fun nativeWindowRelease(window: Long)

    /**
     * Renders a headless Vulkan frame and blits it into [window]. Returns 1
     * when presented or a negative status on failure.
     */
    external fun nativeRendererRenderPlayer(
        renderer: Long,
        player: Long,
        window: Long,
        clearColor: Int,
        fitContainCenter: Boolean,
    ): Int

    /** Renders into JVM-owned, tightly packed RGBA8 premultiplied-sRGB pixels. */
    external fun nativeRendererRenderPlayerToCpuFrame(
        renderer: Long,
        player: Long,
        clearColor: Int,
        fitContainCenter: Boolean,
    ): NuxieCpuFrame?

    external fun nativeRendererFree(renderer: Long)

    /** Engine build/compatibility facts (nux_capi_runtime_info) as JSON. */
    external fun nativeRuntimeInfo(): String

    private const val HOST_BUILD_COMMAND =
        "cargo build -p nux-capi --features android-authored-wgsl,android-vulkan,scripting"
    private const val NUX_STATUS_OK = 0
}
