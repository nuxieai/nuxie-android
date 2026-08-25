package ai.nuxie.sdk.experiences

import ai.nuxie.sdk.network.HttpTransport
import android.content.Context
import java.io.Closeable
import java.io.File
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
    private val protection: Closeable,
) : Closeable {
    override fun close() = protection.close()
}

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
        val scripts = (release.descriptor["screenBehaviors"] as? JsonArray)
            ?.mapIndexedNotNull { index, value ->
                val behavior = value as? JsonObject
                    ?: invalidDescriptor("<screen-behavior:$index>", "invalid screen behavior")
                val script = behavior["script"] ?: return@mapIndexedNotNull null
                val scriptObject = script as? JsonObject
                    ?: invalidDescriptor("<script:$index>", "invalid screen behavior script")
                artifact(scriptObject["artifact"] as? JsonObject, "<script:$index>")
            }
            ?: invalidDescriptor(riv.key, "release screen behaviors are missing")
        val artifacts = listOf(riv) + assets + scripts
        artifacts.groupBy(Artifact::key).entries.firstOrNull { it.value.size > 1 }?.let {
            invalidDescriptor(it.key, "duplicate artifact key")
        }
        artifacts.forEach { item ->
            if (item.sizeBytes > limitFor(item.key)) {
                invalidDescriptor(item.key, "artifact exceeds size limit")
            }
        }
        var aggregateBytes = 0L
        artifacts.distinctBy(Artifact::sha256).forEach { item ->
            if (item.sizeBytes > ExperienceReleaseLimits.ARTIFACT_AGGREGATE_BYTES - aggregateBytes) {
                invalidDescriptor(item.key, "release artifacts exceed aggregate size limit")
            }
            aggregateBytes += item.sizeBytes
        }

        cache.validateDeliveryOrigin(riv.key, delivery.renderBaseUrl)
        cache.validateDeliveryOrigin(
            artifacts.firstOrNull { !it.key.startsWith("renders/") }?.key ?: riv.key,
            delivery.assetBaseUrl,
        )

        // Preflight every authenticated key before the first request. Optional
        // presentation semantics cannot turn an unsafe key into a safe one.
        artifacts.forEach { item ->
            cache.validateLocation(
                key = item.key,
                signedBaseUrl = deliveryOrigin(item.key, delivery),
            )
        }

        val protection = cache.protect(artifacts.map { it.sha256 })
        try {
            val files = LinkedHashMap<String, File>(artifacts.size)
            artifacts.forEach { item ->
                try {
                    files[item.key] = cache.acquire(
                        key = item.key,
                        sha256 = item.sha256,
                        expectedSizeBytes = item.sizeBytes,
                        maxBytes = minOf(item.sizeBytes, limitFor(item.key)),
                        signedBaseUrl = deliveryOrigin(item.key, delivery),
                        expectedContentType = item.contentType,
                    )
                } catch (error: ReleaseArtifactAcquisitionException) {
                    if (item.required || !error.isSafeOptionalFailure()) throw error
                }
            }
            AcquiredRelease(
                identity = release.identity,
                artifactsByKey = files.toMap(),
                rivFile = files.getValue(riv.key),
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
        return Artifact(key, sha256, sizeBytes, contentType, required)
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

    private fun limitFor(key: String): Long = if (key.startsWith("renders/")) {
        ExperienceReleaseLimits.RIV_ARTIFACT_BYTES.toLong()
    } else {
        ExperienceReleaseLimits.EXTERNAL_ASSET_BYTES.toLong()
    }

    private fun deliveryOrigin(key: String, delivery: Delivery): String =
        if (key.startsWith("renders/")) delivery.renderBaseUrl else delivery.assetBaseUrl

    private fun ReleaseArtifactAcquisitionException.isSafeOptionalFailure(): Boolean =
        reason == ReleaseArtifactAcquisitionException.Reason.DIGEST_MISMATCH ||
            reason == ReleaseArtifactAcquisitionException.Reason.CONTENT_TYPE_MISMATCH ||
            (reason == ReleaseArtifactAcquisitionException.Reason.HTTP_STATUS && httpStatusCode == 404)

    private data class Artifact(
        val key: String,
        val sha256: String,
        val sizeBytes: Long,
        val contentType: String,
        val required: Boolean,
    )

    private companion object {
        val REQUIRED_RENDER_ARRAYS = listOf("screens", "transitions", "textInputs", "assets")
    }
}
