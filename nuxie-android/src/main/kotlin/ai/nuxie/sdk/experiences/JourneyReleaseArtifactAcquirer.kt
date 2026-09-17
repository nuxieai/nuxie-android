package ai.nuxie.sdk.experiences

import ai.nuxie.sdk.network.HttpTransport
import android.content.Context
import java.io.Closeable
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.longOrNull

/** A fully verified release whose artifacts are ready for local presentation. */
internal class AcquiredJourneyRelease(
    val identity: JourneyReleaseIdentity,
    val artifactsByKey: Map<String, File>,
    val sceneFile: File,
    val artifactDigests: Set<String> = emptySet(),
    private val protection: Closeable,
) : Closeable {
    override fun close() = protection.close()
}

/** Profile-owned leases for every screen-bearing release in one admission. */
internal class PreparedJourneyArtifacts(
    private val releasesByDigest: Map<String, AcquiredJourneyRelease>,
) : Closeable {
    private val closed = AtomicBoolean(false)

    fun digestsForRelease(descriptorSha256: String): Set<String>? =
        releasesByDigest[descriptorSha256]?.artifactDigests

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        releasesByDigest.values.forEach(AcquiredJourneyRelease::close)
    }
}

internal interface JourneyArtifactManager {
    suspend fun prepareJourneys(
        snapshot: JourneyProfileCatalog.Snapshot,
    ): PreparedJourneyArtifacts

    fun retainForRun(runKey: String, digests: Set<String>)

    fun releaseRun(runKey: String)

    fun retainedRunDigests(runKey: String): Set<String>?
}

/** Resolves authenticated descriptor artifacts into the content-addressed cache. */
internal class JourneyReleaseArtifactAcquirer(
    private val cache: JourneyReleaseArtifactCache,
) : JourneyArtifactManager {
    constructor(context: Context, transport: HttpTransport) : this(
        JourneyReleaseArtifactCache(context, transport),
    )

    override suspend fun prepareJourneys(
        snapshot: JourneyProfileCatalog.Snapshot,
    ): PreparedJourneyArtifacts {
        val acquired = linkedMapOf<String, AcquiredJourneyRelease>()
        try {
            snapshot.releasesByDigest.forEach { (digest, release) ->
                if ((release.leg["screens"] as? JsonArray).orEmpty().isNotEmpty()) {
                    acquired[digest] = acquire(release, snapshot.profile.delivery)
                }
            }
            return PreparedJourneyArtifacts(acquired)
        } catch (failure: Throwable) {
            acquired.values.forEach(AcquiredJourneyRelease::close)
            throw failure
        }
    }

    override fun retainForRun(runKey: String, digests: Set<String>) {
        cache.retainForRun(runKey, digests).close()
    }

    override fun releaseRun(runKey: String) = cache.releaseRun(runKey)

    override fun retainedRunDigests(runKey: String): Set<String>? {
        val retained = cache.retainedRunDigests(runKey) ?: return null
        return retained.takeIf { digests ->
            digests.all { digest -> cache.cachedFile(digest)?.isFile == true }
        }
    }

    suspend fun acquire(
        release: AuthenticatedJourneyRelease,
        delivery: JourneyReleaseDelivery,
    ): AcquiredJourneyRelease = acquire(release.identity, release.descriptor, delivery)

    internal suspend fun acquire(
        identity: JourneyReleaseIdentity,
        descriptor: JsonObject,
        delivery: JourneyReleaseDelivery,
    ): AcquiredJourneyRelease {
        var completed: AcquiredJourneyRelease? = null
        try {
            return withContext(Dispatchers.IO) { acquireOnIo(identity, descriptor, delivery).also { completed = it } }
        } catch (error: Throwable) {
            // Dispatch back to the caller is cancellable even after acquisition
            // succeeds. An undelivered result must not retain its cache lease.
            completed?.close()
            throw error
        }
    }

    private suspend fun acquireOnIo(
        identity: JourneyReleaseIdentity,
        descriptor: JsonObject,
        delivery: JourneyReleaseDelivery,
    ): AcquiredJourneyRelease {
        val render = descriptor["render"] as? JsonObject
            ?: invalidDescriptor("<render>", "release render is missing")
        val sceneField = when (render.string("renderer")) {
            "rive" -> "riv"
            "nux" -> "nux"
            else -> invalidDescriptor("<render>", "unsupported release renderer")
        }
        val scene = artifact(render[sceneField] as? JsonObject, "<$sceneField>", ArtifactRole.SCENE)
        val sceneMime = if (sceneField == "nux") "application/vnd.nuxie.scene" else "application/vnd.rive"
        if (scene.key != "renders/sha256/${scene.sha256}.$sceneField" || scene.contentType != sceneMime ||
            (sceneField == "nux" && scene.sizeBytes == 0L)) {
            invalidDescriptor(scene.key, "scene artifact differs from renderer")
        }
        REQUIRED_RENDER_ARRAYS.forEach { field ->
            if (render[field] !is JsonArray) {
                invalidDescriptor(scene.key, "release $field are missing")
            }
        }
        val assets = (render["assets"] as? JsonArray)
            ?.mapIndexedNotNull { index, value ->
                val asset = value as? JsonObject
                if (asset?.string("kind") == "font" && asset.string("location") == "system") {
                    // The authenticated descriptor retains the local requirement;
                    // there is no network/cache object for a device font.
                    return@mapIndexedNotNull null
                }
                artifact(
                    value as? JsonObject,
                    "<asset:$index>",
                    ArtifactRole.ASSET,
                    requiresKind = true,
                )
            }
            ?: invalidDescriptor(scene.key, "release assets are missing")
        val scripts = (descriptor["screenBehaviors"] as? JsonArray)
            ?.mapIndexedNotNull { index, value ->
                val behavior = value as? JsonObject
                    ?: invalidDescriptor("<screen-behavior:$index>", "invalid screen behavior")
                val script = behavior["script"] ?: return@mapIndexedNotNull null
                val scriptObject = script as? JsonObject
                    ?: invalidDescriptor("<script:$index>", "invalid screen behavior script")
                artifact(
                    scriptObject["artifact"] as? JsonObject,
                    "<script:$index>",
                    ArtifactRole.SCRIPT,
                )
            }
            ?: invalidDescriptor(scene.key, "release screen behaviors are missing")
        val references = listOf(scene) + assets + scripts
        references.forEach { item ->
            if (item.sizeBytes > item.role.maximumBytes) {
                invalidDescriptor(item.key, "artifact exceeds size limit")
            }
        }

        references.groupBy(Artifact::key).values.forEach { matchingKey ->
            val first = matchingKey.first()
            if (matchingKey.any { it.sha256 != first.sha256 || !it.hasMatchingMetadata(first) }) {
                invalidDescriptor(first.key, "artifact key has conflicting metadata")
            }
        }
        val artifacts = references.groupBy(Artifact::sha256).values.map { matchingDigest ->
            val first = matchingDigest.first()
            if (matchingDigest.any { !it.hasMatchingMetadata(first) }) {
                invalidDescriptor(first.key, "artifact digest has conflicting metadata")
            }
            NormalizedArtifact(
                acquisition = first,
                keys = matchingDigest.map(Artifact::key).distinct(),
                required = matchingDigest.any(Artifact::required),
            )
        }

        var scriptBytes = 0L
        references.filter { it.role == ArtifactRole.SCRIPT }
            .distinctBy(Artifact::sha256)
            .forEach { item ->
                if (item.sizeBytes >
                    JourneyReleaseLimits.SCRIPT_ARTIFACT_AGGREGATE_BYTES - scriptBytes
                ) {
                    invalidDescriptor(item.key, "screen behavior scripts exceed aggregate size limit")
                }
                scriptBytes += item.sizeBytes
            }
        var aggregateBytes = 0L
        artifacts.forEach { normalized ->
            val item = normalized.acquisition
            if (item.sizeBytes > JourneyReleaseLimits.ARTIFACT_AGGREGATE_BYTES - aggregateBytes) {
                invalidDescriptor(item.key, "release artifacts exceed aggregate size limit")
            }
            aggregateBytes += item.sizeBytes
        }

        cache.validateJourneyReleaseDeliveryOrigin(scene.key, delivery.renderBaseUrl)
        references.firstOrNull { it.role != ArtifactRole.SCENE }?.let { external ->
            cache.validateJourneyReleaseDeliveryOrigin(external.key, delivery.assetBaseUrl)
        }

        // Preflight every authenticated key before the first request. Optional
        // presentation semantics cannot turn an unsafe key into a safe one.
        references.forEach { item ->
            cache.validateLocation(
                key = item.key,
                signedBaseUrl = deliveryOrigin(item.role, delivery),
            )
        }

        val acquisitionContext = currentCoroutineContext()
        acquisitionContext.ensureActive()
        val protection = cache.protect(artifacts.map { it.acquisition.sha256 })
        try {
            val files = LinkedHashMap<String, File>(references.size)
            artifacts.forEach { normalized ->
                val item = normalized.acquisition
                try {
                    val file = cache.acquire(
                        key = item.key,
                        sha256 = item.sha256,
                        expectedSizeBytes = item.sizeBytes,
                        maxBytes = minOf(item.sizeBytes, item.role.maximumBytes),
                        signedBaseUrl = deliveryOrigin(item.role, delivery),
                        expectedContentType = item.contentType,
                        protection = protection,
                        checkActive = acquisitionContext::ensureActive,
                    )
                    normalized.keys.forEach { key -> files[key] = file }
                } catch (error: JourneyReleaseArtifactAcquisitionException) {
                    if (normalized.required || !error.isSafeOptionalFailure()) throw error
                }
            }
            acquisitionContext.ensureActive()
            return AcquiredJourneyRelease(
                identity = identity,
                artifactsByKey = files.toMap(),
                sceneFile = files.getValue(scene.key),
                artifactDigests = files.values.mapTo(linkedSetOf()) { file -> file.name },
                protection = protection,
            )
        } catch (error: Throwable) {
            protection.close()
            throw error
        }
    }

    private fun artifact(
        value: JsonObject?,
        fallbackKey: String,
        role: ArtifactRole,
        requiresKind: Boolean = false,
    ): Artifact {
        val key = value?.string("key") ?: fallbackKey
        val sha256 = value?.string("sha256")
            ?.takeIf { digest ->
                digest.length == 64 && digest.all { it in '0'..'9' || it in 'a'..'f' }
            }
            ?: invalidDescriptor(key, "invalid artifact digest")
        val sizeBytes = value.long("sizeBytes")?.takeIf { it >= 0 }
            ?: invalidDescriptor(key, "invalid artifact size")
        val contentType = value.string("contentType")?.takeIf { it.isNotBlank() }
            ?: invalidDescriptor(key, "invalid artifact content type")
        if (requiresKind && value.string("kind").isNullOrBlank()) {
            invalidDescriptor(key, "invalid artifact kind")
        }
        val required = if (requiresKind) {
            (value["required"] as? JsonPrimitive)?.booleanOrNull
                ?: invalidDescriptor(key, "invalid artifact required flag")
        } else {
            true
        }
        if (key == fallbackKey) invalidDescriptor(key, "invalid artifact key")
        if (!role.accepts(key, sha256)) {
            invalidDescriptor(key, "artifact key does not match descriptor role")
        }
        return Artifact(key, sha256, sizeBytes, contentType, required, role)
    }

    private fun JsonObject.string(key: String): String? =
        (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

    private fun JsonObject?.long(key: String): Long? =
        (this?.get(key) as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull

    private fun invalidDescriptor(key: String, message: String): Nothing =
        throw JourneyReleaseArtifactAcquisitionException(
            artifactKey = key,
            reason = JourneyReleaseArtifactAcquisitionException.Reason.INVALID_DESCRIPTOR,
            message = message,
        )

    private fun deliveryOrigin(role: ArtifactRole, delivery: JourneyReleaseDelivery): String =
        if (role == ArtifactRole.SCENE) delivery.renderBaseUrl else delivery.assetBaseUrl

    private fun JourneyReleaseArtifactAcquisitionException.isSafeOptionalFailure(): Boolean =
        reason == JourneyReleaseArtifactAcquisitionException.Reason.DIGEST_MISMATCH ||
            reason == JourneyReleaseArtifactAcquisitionException.Reason.CONTENT_TYPE_MISMATCH ||
            reason == JourneyReleaseArtifactAcquisitionException.Reason.SIZE_MISMATCH ||
            reason == JourneyReleaseArtifactAcquisitionException.Reason.TRANSPORT ||
            (reason == JourneyReleaseArtifactAcquisitionException.Reason.HTTP_STATUS &&
                (httpStatusCode == 404 || httpStatusCode in 500..599))

    private data class Artifact(
        val key: String,
        val sha256: String,
        val sizeBytes: Long,
        val contentType: String,
        val required: Boolean,
        val role: ArtifactRole,
    ) {
        fun hasMatchingMetadata(other: Artifact): Boolean =
            sizeBytes == other.sizeBytes &&
                contentType == other.contentType &&
                role == other.role
    }

    private data class NormalizedArtifact(
        val acquisition: Artifact,
        val keys: List<String>,
        val required: Boolean,
    )

    private enum class ArtifactRole(
        val maximumBytes: Long,
    ) {
        SCENE(JourneyReleaseLimits.RIV_ARTIFACT_BYTES.toLong()),
        ASSET(JourneyReleaseLimits.EXTERNAL_ASSET_BYTES.toLong()),
        SCRIPT(JourneyReleaseLimits.EXTERNAL_ASSET_BYTES.toLong()),
        ;

        fun accepts(key: String, sha256: String): Boolean = when (this) {
            SCENE -> key == "renders/sha256/$sha256.riv" || key == "renders/sha256/$sha256.nux"
            ASSET -> {
                val prefix = "assets/sha256/$sha256."
                key.startsWith(prefix) && key.removePrefix(prefix) in ASSET_EXTENSIONS
            }
            SCRIPT -> key == "screen-behavior/sha256/$sha256.bin"
        }
    }

    private companion object {
        val REQUIRED_RENDER_ARRAYS = listOf("screens", "transitions", "textInputs", "assets")
        val ASSET_EXTENSIONS = setOf("png", "jpg", "webp", "ttf", "otf", "bin", "mp4")
    }
}
