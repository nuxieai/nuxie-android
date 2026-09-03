package ai.nuxie.sdk.experiences

import ai.nuxie.sdk.network.HttpTransport
import android.content.Context
import java.io.Closeable
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.longOrNull

/** A fully verified release whose artifacts are ready for local presentation. */
internal class AcquiredRelease(
    val identity: ExperienceReleaseIdentity,
    val artifactsByKey: Map<String, File>,
    val rivFile: File,
    val artifactDigests: Set<String> = emptySet(),
    private val protection: Closeable,
) : Closeable {
    override fun close() = protection.close()
}

/** Profile-owned leases for every screen-bearing release in one admission. */
internal class PreparedDeviceLegArtifacts(
    private val releasesByDigest: Map<String, AcquiredRelease>,
) : Closeable {
    private val closed = AtomicBoolean(false)

    fun digestsForRelease(descriptorSha256: String): Set<String>? =
        releasesByDigest[descriptorSha256]?.artifactDigests

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        releasesByDigest.values.forEach(AcquiredRelease::close)
    }
}

internal interface DeviceLegArtifactManager {
    suspend fun prepareDeviceLegs(
        snapshot: DeviceLegProfileCatalog.Snapshot,
    ): PreparedDeviceLegArtifacts

    fun retainForRun(runKey: String, digests: Set<String>)

    fun releaseRun(runKey: String)

    fun retainedRunDigests(runKey: String): Set<String>?
}

/** Resolves authenticated descriptor artifacts into the content-addressed cache. */
internal class ReleaseArtifactAcquirer(
    private val cache: ReleaseArtifactCache,
) : DeviceLegArtifactManager {
    constructor(context: Context, transport: HttpTransport) : this(
        ReleaseArtifactCache(context, transport),
    )

    suspend fun acquire(
        release: AuthenticatedRelease,
        delivery: Delivery,
    ): AcquiredRelease = acquire(release.identity, release.descriptor, delivery)

    override suspend fun prepareDeviceLegs(
        snapshot: DeviceLegProfileCatalog.Snapshot,
    ): PreparedDeviceLegArtifacts {
        val acquired = linkedMapOf<String, AcquiredRelease>()
        try {
            snapshot.releasesByDigest.forEach { (digest, release) ->
                if ((release.leg["screens"] as? JsonArray).orEmpty().isNotEmpty()) {
                    acquired[digest] = acquire(release, snapshot.profile.delivery)
                }
            }
            return PreparedDeviceLegArtifacts(acquired)
        } catch (failure: Throwable) {
            acquired.values.forEach(AcquiredRelease::close)
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
        release: AuthenticatedDeviceLegRelease,
        delivery: Delivery,
    ): AcquiredRelease = acquire(release.identity, release.descriptor, delivery)

    private suspend fun acquire(
        identity: ExperienceReleaseIdentity,
        descriptor: JsonObject,
        delivery: Delivery,
    ): AcquiredRelease = withContext(Dispatchers.IO) {
        val render = descriptor["render"] as? JsonObject
            ?: invalidDescriptor("<render>", "release render is missing")
        val riv = artifact(render["riv"] as? JsonObject, "<riv>", ArtifactRole.RIV)
        if (render.string("renderer") != "rive") {
            invalidDescriptor(riv.key, "unsupported release renderer")
        }
        REQUIRED_RENDER_ARRAYS.forEach { field ->
            if (render[field] !is JsonArray) {
                invalidDescriptor(riv.key, "release $field are missing")
            }
        }
        val assets = (render["assets"] as? JsonArray)
            ?.mapIndexed { index, value ->
                artifact(
                    value as? JsonObject,
                    "<asset:$index>",
                    ArtifactRole.ASSET,
                    requiresKind = true,
                )
            }
            ?: invalidDescriptor(riv.key, "release assets are missing")
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
            ?: invalidDescriptor(riv.key, "release screen behaviors are missing")
        val references = listOf(riv) + assets + scripts
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
                    ExperienceReleaseLimits.SCRIPT_ARTIFACT_AGGREGATE_BYTES - scriptBytes
                ) {
                    invalidDescriptor(item.key, "screen behavior scripts exceed aggregate size limit")
                }
                scriptBytes += item.sizeBytes
            }
        var aggregateBytes = 0L
        artifacts.forEach { normalized ->
            val item = normalized.acquisition
            if (item.sizeBytes > ExperienceReleaseLimits.ARTIFACT_AGGREGATE_BYTES - aggregateBytes) {
                invalidDescriptor(item.key, "release artifacts exceed aggregate size limit")
            }
            aggregateBytes += item.sizeBytes
        }

        cache.validateDeliveryOrigin(riv.key, delivery.renderBaseUrl)
        references.firstOrNull { it.role != ArtifactRole.RIV }?.let { external ->
            cache.validateDeliveryOrigin(external.key, delivery.assetBaseUrl)
        }

        // Preflight every authenticated key before the first request. Optional
        // presentation semantics cannot turn an unsafe key into a safe one.
        references.forEach { item ->
            cache.validateLocation(
                key = item.key,
                signedBaseUrl = deliveryOrigin(item.role, delivery),
            )
        }

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
                    )
                    normalized.keys.forEach { key -> files[key] = file }
                } catch (error: ReleaseArtifactAcquisitionException) {
                    if (normalized.required || !error.isSafeOptionalFailure()) throw error
                }
            }
            AcquiredRelease(
                identity = identity,
                artifactsByKey = files.toMap(),
                rivFile = files.getValue(riv.key),
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
        throw ReleaseArtifactAcquisitionException(
            artifactKey = key,
            reason = ReleaseArtifactAcquisitionException.Reason.INVALID_DESCRIPTOR,
            message = message,
        )

    private fun deliveryOrigin(role: ArtifactRole, delivery: Delivery): String =
        if (role == ArtifactRole.RIV) delivery.renderBaseUrl else delivery.assetBaseUrl

    private fun ReleaseArtifactAcquisitionException.isSafeOptionalFailure(): Boolean =
        reason == ReleaseArtifactAcquisitionException.Reason.DIGEST_MISMATCH ||
            reason == ReleaseArtifactAcquisitionException.Reason.CONTENT_TYPE_MISMATCH ||
            reason == ReleaseArtifactAcquisitionException.Reason.SIZE_MISMATCH ||
            reason == ReleaseArtifactAcquisitionException.Reason.TRANSPORT ||
            (reason == ReleaseArtifactAcquisitionException.Reason.HTTP_STATUS &&
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
        RIV(ExperienceReleaseLimits.RIV_ARTIFACT_BYTES.toLong()),
        ASSET(ExperienceReleaseLimits.EXTERNAL_ASSET_BYTES.toLong()),
        SCRIPT(ExperienceReleaseLimits.EXTERNAL_ASSET_BYTES.toLong()),
        ;

        fun accepts(key: String, sha256: String): Boolean = when (this) {
            RIV -> key == "renders/sha256/$sha256.riv"
            ASSET -> {
                val prefix = "assets/sha256/$sha256."
                key.startsWith(prefix) && key.removePrefix(prefix) in ASSET_EXTENSIONS
            }
            SCRIPT -> key == "screen-behavior/sha256/$sha256.bin"
        }
    }

    private companion object {
        val REQUIRED_RENDER_ARRAYS = listOf("screens", "transitions", "textInputs", "assets")
        val ASSET_EXTENSIONS = setOf("png", "jpg", "webp", "ttf", "otf", "bin")
    }
}
