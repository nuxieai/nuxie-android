package ai.nuxie.sdk.presentation

import ai.nuxie.sdk.experiences.JourneyReleaseValuePolicy
import ai.nuxie.sdk.experiences.ExperienceAssetImport
import ai.nuxie.sdk.experiences.ExperienceAssetImportBuilder
import ai.nuxie.sdk.experiences.SystemFontCache
import ai.nuxie.sdk.runtime.NuxieAndroidVulkanRenderer
import ai.nuxie.sdk.runtime.NuxieRuntime
import ai.nuxie.sdk.runtime.NuxieRuntimeFile
import ai.nuxie.sdk.runtime.NuxieRuntimeLane
import ai.nuxie.sdk.runtime.NuxieRuntimeViewModelState
import ai.nuxie.sdk.runtime.NuxieValuePolicy
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject

/** One run's file, renderer domain and authored values, confined to one native lane. */
internal class ExperienceRunValues(private val restoredSnapshot: ExperienceRunSnapshot? = null) {
    val lane = NuxieRuntimeLane()
    private val admission = Any()
    private val retired = AtomicBoolean(false)
    private var nativeRetired = false
    private val screens = AtomicInteger()
    private var bytes: ByteArray? = null
    private var policy: NuxieValuePolicy? = null
    private var prepared: Native? = null

    internal class Native(
        val renderer: NuxieAndroidVulkanRenderer,
        val file: NuxieRuntimeFile,
        val imports: ExperienceAssetImport?,
        val values: NuxieRuntimeViewModelState?,
        val origins: Map<Long, List<ExperienceRunListSnapshot.OriginStep>>,
        val authoredIds: Set<Long>,
    )

    /** Called on the lane before binding any screen root. */
    fun prepare(
        sceneBytes: ByteArray,
        descriptor: JsonObject?,
        artifactsByKey: Map<String, File>,
        runtime: NuxieRuntime = NuxieRuntime.shared,
        fonts: SystemFontCache = SystemFontCache.shared,
    ): Native {
        check(!retired.get()) { "The run has ended" }
        val nextPolicy = descriptor?.let(JourneyReleaseValuePolicy::parse)
        prepared?.let {
            check(checkNotNull(bytes).contentEquals(sceneBytes) && policy == nextPolicy) { "A run cannot change its native file" }
            return it
        }
        val renderer = checkNotNull(runtime.newAndroidVulkanRenderer(1, 1)) { "Run renderer creation failed" }
        val leases = mutableListOf<SystemFontCache.Lease>()
        var file: NuxieRuntimeFile? = null
        var values: NuxieRuntimeViewModelState? = null
        try {
            val imports = descriptor?.let {
                ExperienceAssetImportBuilder.build(it, artifactsByKey,
                    checkNotNull(runtime.inspectFileAssets(sceneBytes)),
                    systemFontBytes = { requirement -> fonts.prepare(requirement).also(leases::add).candidate.bytes })
            }
            file = checkNotNull(runtime.importFile(renderer, sceneBytes,
                expectedAssets = imports?.expectedAssets.orEmpty(),
                externalAssets = imports?.externalAssets.orEmpty(),
                videoEnabled = imports?.videos?.isNotEmpty() == true,
                valuePolicy = nextPolicy)) { "Run file import failed" }
            values = file.newAuthoredViewModel("Experience", 0)
            val initial = values?.nativeSnapshot()
            val origins = initial?.let(ExperienceRunListSnapshot::authoredOrigins).orEmpty()
            if (values != null && restoredSnapshot != null) {
                val lists = restoredSnapshot.lists
                if (lists != null) lists.restore(file, values) else values.restoreWrites(restoredSnapshot.writes())
            }
            return Native(renderer, file, imports, values, origins,
                initial?.instances?.map { it.id }?.toSet().orEmpty()).also {
                // Without the shared model, the caller owns this screen's import.
                if (values != null) {
                    policy = nextPolicy
                    bytes = sceneBytes.copyOf()
                    prepared = it
                }
                fonts.didImport(leases)
            }
        } catch (error: Throwable) {
            fonts.didFailImport(leases)
            runCatching { values?.close() }.exceptionOrNull()?.let(error::addSuppressed)
            runCatching { file?.close() }.exceptionOrNull()?.let(error::addSuppressed)
            runCatching { renderer.close() }.exceptionOrNull()?.let(error::addSuppressed)
            throw error
        }
    }

    /** Warm only run-owned state. A file without that state has no screen to own it here. */
    fun prepareForRun(sceneBytes: ByteArray, descriptor: JsonObject?, artifactsByKey: Map<String, File>,
        runtime: NuxieRuntime = NuxieRuntime.shared) {
        val native = prepare(sceneBytes, descriptor, artifactsByKey, runtime)
        if (native.values == null) {
            try { native.file.close() } finally { native.renderer.close() }
        }
    }

    suspend fun snapshot(): ExperienceRunSnapshot? = lane.call {
        check(!retired.get()) { "The run has ended" }
        prepared?.let { state -> state.values?.nativeSnapshot()?.let {
            ExperienceRunSnapshot.capture(it, state.origins, state.authoredIds)
        } }
    }

    suspend fun journeyValues(): JsonObject = snapshot()?.journeyValues ?: JsonObject(emptyMap())

    suspend fun isPrepared(): Boolean = lane.call { prepared != null }

    fun retainScreen() {
        synchronized(admission) {
            check(!retired.get()) { "The run has ended" }
            screens.incrementAndGet()
        }
    }

    /** Enqueued after this screen has released its players and artboard. */
    fun releaseScreen(completion: () -> Unit) {
        check(lane.enqueue {
            try {
                check(screens.decrementAndGet() >= 0) { "Unbalanced run screen ownership" }
                closeIfRetired()
            } finally { completion() }
        }) { "Run lane closed before screen release" }
    }

    suspend fun retire() {
        if (!synchronized(admission) { retired.compareAndSet(false, true) }) return
        withContext(NonCancellable) {
            lane.call {
                nativeRetired = true
                try { prepared?.values?.close() }
                finally { closeIfRetired() }
            }
        }
    }

    private fun closeIfRetired() {
        if (!nativeRetired || screens.get() != 0) return
        val native = prepared
        prepared = null
        bytes = null
        try { native?.file?.close() }
        finally {
            try { native?.renderer?.close() }
            finally { lane.shutdown() }
        }
    }
}
