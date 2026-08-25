package ai.nuxie.sdk.experiences

import ai.nuxie.sdk.network.HttpTransport
import android.content.Context
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull

/** A fully verified release whose artifacts are ready for local presentation. */
internal data class AcquiredRelease(
    val identity: ExperienceReleaseIdentity,
    val artifactsByKey: Map<String, File>,
    val rivFile: File,
)

/** Resolves authenticated descriptor artifacts into the content-addressed cache. */
internal class ReleaseArtifactAcquirer(
    private val cache: ReleaseArtifactCache,
) {
    constructor(context: Context, transport: HttpTransport) : this(
        ReleaseArtifactCache(context, transport),
    )

    suspend fun acquire(
        release: AuthenticatedRelease,
        delivery: Delivery,
    ): AcquiredRelease = withContext(Dispatchers.IO) {
        val render = release.descriptor["render"] as? JsonObject
            ?: invalidDescriptor("<render>", "release render is missing")
        val riv = artifact(render["riv"] as? JsonObject, "<riv>")
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
                artifact(value as? JsonObject, "<asset:$index>", requiresKind = true)
            }
            ?: invalidDescriptor(riv.key, "release assets are missing")
        val artifacts = listOf(riv) + assets
        artifacts.groupBy(Artifact::key).entries.firstOrNull { it.value.size > 1 }?.let {
            invalidDescriptor(it.key, "duplicate artifact key")
        }

        cache.validateDeliveryOrigin(riv.key, delivery.renderBaseUrl)
        cache.validateDeliveryOrigin(assets.firstOrNull()?.key ?: riv.key, delivery.assetBaseUrl)

        // Preflight every authenticated key before the first request. Optional
        // presentation semantics cannot turn an unsafe key into a safe one.
        artifacts.forEach { item ->
            cache.validateLocation(
                key = item.key,
                signedBaseUrl = if (item === riv) delivery.renderBaseUrl else delivery.assetBaseUrl,
            )
        }

        cache.withProtection(artifacts.map { it.sha256 }) {
            val files = LinkedHashMap<String, File>(artifacts.size)
            artifacts.forEach { item ->
                files[item.key] = cache.acquire(
                    key = item.key,
                    sha256 = item.sha256,
                    expectedSizeBytes = item.sizeBytes,
                    maxBytes = item.sizeBytes,
                    signedBaseUrl = if (item === riv) {
                        delivery.renderBaseUrl
                    } else {
                        delivery.assetBaseUrl
                    },
                    expectedContentType = item.contentType,
                )
            }
            AcquiredRelease(
                identity = release.identity,
                artifactsByKey = files.toMap(),
                rivFile = files.getValue(riv.key),
            )
        }
    }

    private fun artifact(
        value: JsonObject?,
        fallbackKey: String,
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
        if (key == fallbackKey) invalidDescriptor(key, "invalid artifact key")
        return Artifact(key, sha256, sizeBytes, contentType)
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

    private data class Artifact(
        val key: String,
        val sha256: String,
        val sizeBytes: Long,
        val contentType: String,
    )

    private companion object {
        val REQUIRED_RENDER_ARRAYS = listOf("screens", "transitions", "textInputs", "assets")
    }
}
