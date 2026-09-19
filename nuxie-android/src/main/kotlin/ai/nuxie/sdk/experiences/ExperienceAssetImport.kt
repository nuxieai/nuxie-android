package ai.nuxie.sdk.experiences

import ai.nuxie.sdk.runtime.ExpectedFileAsset
import ai.nuxie.sdk.runtime.FileAssetKind
import java.io.File
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.longOrNull

/** Signed device-font request; it deliberately has no downloadable artifact identity. */
internal data class SystemFontRequirement(val uniqueName: String, val weight: Int, val style: String)

/** Immutable inputs for one configured native file import. */
internal data class ExperienceAssetImport(
    val expectedAssets: List<ExpectedFileAsset>,
    val externalAssets: Map<Int, ByteArray>,
    val videos: List<ExperienceVideoAssetBinding> = emptyList(),
    val videoElements: List<ExperienceVideoElement> = emptyList(),
)

/** Authored identity and local component slot in this exact signed scene. */
internal data class ExperienceVideoElement(
    val sourceArtboardIndex: Long,
    val artboardId: String,
    val viewNodeId: String,
    val renderedNodeId: String,
    val componentId: Long,
    val readinessTimeoutSeconds: Double,
    val optional: Boolean,
)

/** Verified file-backed video; never copied into the image/font byte provider. */
internal data class ExperienceVideoAssetBinding(
    val ordinal: Int,
    val authoredId: Long,
    val sourceAssetKey: String,
    val file: File?,
    val required: Boolean,
    val captionTracks: List<ExperienceVideoCaptionTrack> = emptyList(),
)

internal data class ExperienceVideoCaptionTrack(val streamIndex: Int, val language: String?)

/**
 * Binds the signed release declarations and acquired files to the scene's
 * complete, file-order catalog. The native configured import validates the
 * same catalog again before invoking any provider hook.
 */
internal object ExperienceAssetImportBuilder {
    fun build(
        descriptor: JsonObject,
        artifactsByKey: Map<String, File>,
        inspectedCatalog: List<ExpectedFileAsset>,
        systemFontBytes: (SystemFontRequirement) -> ByteArray = {
            error("System font provider is unavailable")
        },
    ): ExperienceAssetImport {
        require(inspectedCatalog.withIndex().all { (index, asset) ->
            asset.ordinal == index
        }) { "Experience asset catalog must be complete and file-ordered" }

        val declarations = declarations(descriptor).toMutableList()
        require(declarations.distinctBy(Declaration::identity).size == declarations.size) {
            "Signed Experience assets contain duplicate authored identities"
        }
        val systemFonts = declarations.mapNotNull { (it.source as? Source.System)?.requirement }
            .associateBy { it.uniqueName }
        val inputs = (descriptor["render"] as? JsonObject)?.get("textInputs") as? JsonArray
        inputs?.forEach { value ->
            val style = (value as? JsonObject)?.get("style") as? JsonObject
                ?: error("Experience text input style is missing")
            val system = systemFonts[style.string("fontAssetUniqueName")]
            require(if (system != null) {
                style.string("fontFamily") == "System" &&
                    style.string("fontWeight") == system.weight.toString() &&
                    style.string("fontStyle") == system.style
            } else style.string("fontFamily") != "System") {
                "Experience text input does not match its System font declaration"
            }
        }
        val bindings = mutableListOf<Pair<ExpectedFileAsset, Declaration>>()
        inspectedCatalog.forEach { asset ->
            val declarationIndex = declarations.indexOfFirst { declaration ->
                declaration.matches(asset)
            }
            if (declarationIndex >= 0) {
                val declaration = declarations.removeAt(declarationIndex)
                bindings += asset to declaration
            } else {
                require(asset.mayRemainInBand()) {
                    "Authored Experience asset is not declared: ordinal ${asset.ordinal}"
                }
            }
        }
        require(declarations.isEmpty()) {
            "Signed Experience assets do not exactly match the authored catalog"
        }

        // Resolve bytes only after the entire signed catalog has matched.
        val externalAssets = linkedMapOf<Int, ByteArray>()
        val videos = mutableListOf<ExperienceVideoAssetBinding>()
        bindings.forEach { (asset, declaration) ->
            when (val source = declaration.source) {
                is Source.System -> externalAssets[asset.ordinal] = systemFontBytes(source.requirement)
                is Source.Download -> {
                    val file = artifactsByKey[source.key]
                    if (file == null) {
                        require(!declaration.required) {
                            "Required Experience asset was not acquired: ${source.key}"
                        }
                    }
                    if (asset.kind == FileAssetKind.VIDEO) {
                        require(asset.requiredProviderFlags == 4) { "Video provider contract mismatch" }
                        require(file == null || file.isFile) { "Video file is unavailable" }
                        videos += ExperienceVideoAssetBinding(asset.ordinal, declaration.authoredId,
                            requireNotNull(declaration.sourceAssetKey), file, declaration.required, declaration.captionTracks)
                    } else if (file != null) {
                        externalAssets[asset.ordinal] = file.readBytes()
                    }
                }
            }
        }

        return ExperienceAssetImport(
            expectedAssets = inspectedCatalog.toList(),
            externalAssets = externalAssets.toMap(),
            videos = videos.toList(),
            videoElements = JourneyRenderSchema.videoElements(descriptor["render"] as JsonObject),
        )
    }

    private fun declarations(descriptor: JsonObject): List<Declaration> {
        val render = descriptor["render"] as? JsonObject
            ?: error("Journey release render is missing")
        val assets = render["assets"] as? JsonArray
            ?: error("Journey release assets are missing")
        return assets.mapIndexedNotNull { index, value ->
            val asset = value as? JsonObject
                ?: error("Journey release asset $index is invalid")
            val kind = when (asset.string("kind")) {
                "image" -> FileAssetKind.IMAGE
                "font" -> FileAssetKind.FONT
                "video" -> FileAssetKind.VIDEO
                // Script and shader bytes remain authenticated in-band. They
                // are represented by the complete native catalog but have no
                // external provider entry in the authoritative iOS binding.
                "script", "shader" -> return@mapIndexedNotNull null
                else -> error("Journey release asset $index has an unsupported kind")
            }
            Declaration(
                kind = kind,
                authoredId = asset.long("authoredAssetId")
                    ?.takeIf { it in 0..UINT32_MAX }
                    ?: error("Journey release asset $index has an invalid authored id"),
                uniqueName = asset.string("assetUniqueName")
                    ?.takeIf(String::isNotBlank)
                    ?: error("Journey release asset $index has no unique name"),
                source = if (kind == FileAssetKind.FONT && asset.string("location") == "system") {
                    require(asset.string("family") == "System" && asset.string("style") == "normal")
                    val weight = asset.string("weight")
                    require(weight in (100..900 step 100).map(Int::toString))
                    require((asset["required"] as? JsonPrimitive)?.booleanOrNull == true)
                    Source.System(SystemFontRequirement(
                        uniqueName = asset.string("assetUniqueName")!!,
                        weight = weight!!.toInt(),
                        style = "normal",
                    ))
                } else {
                    Source.Download(asset.string("key")
                        ?: error("Journey release asset $index has no artifact key"))
                },
                sourceAssetKey = if (kind == FileAssetKind.VIDEO) {
                    asset.string("sourceAssetKey")?.takeIf {
                        it.length <= 128 && it.matches(Regex("^asset:[A-Za-z0-9_-]+$"))
                    } ?: error("Journey video asset $index has an invalid source identity")
                } else null,
                captionTracks = (asset["captionTracks"] as? JsonArray).orEmpty().map { value ->
                    val track = value as? JsonObject ?: error("Invalid caption track")
                    require(track.string("codec") == "mov_text")
                    val stream = track.long("streamIndex") ?: error("Missing caption stream")
                    require(stream in 0..Int.MAX_VALUE.toLong())
                    ExperienceVideoCaptionTrack(stream.toInt(), track.string("language"))
                },
                required = (asset["required"] as? JsonPrimitive)?.booleanOrNull
                    ?: error("Journey release asset $index has no required flag"),
            )
        }
    }

    private sealed interface Source {
        data class Download(val key: String) : Source
        data class System(val requirement: SystemFontRequirement) : Source
    }

    private data class Declaration(
        val kind: FileAssetKind,
        val authoredId: Long,
        val uniqueName: String,
        val source: Source,
        val sourceAssetKey: String?,
        val required: Boolean,
        val captionTracks: List<ExperienceVideoCaptionTrack>,
    ) {
        fun identity(): Triple<FileAssetKind, Long, String> =
            Triple(kind, authoredId, uniqueName)

        fun matches(asset: ExpectedFileAsset): Boolean {
            // The declaration identity is the runtime-uniquified "name-authoredId";
            // the iOS binding accepts exactly that form and nothing looser.
            // Production declarations are always external on the wire, so a
            // declaration can bind only a non-embedded catalog descriptor.
            return !asset.isEmbedded &&
                kind == asset.kind &&
                authoredId == asset.authoredId &&
                uniqueName == "${asset.name}-${asset.authoredId}"
        }
    }

    private fun ExpectedFileAsset.mayRemainInBand(): Boolean = when (kind) {
        FileAssetKind.SCRIPT, FileAssetKind.SHADER ->
            isEmbedded && hasContentsRecord && requiredProviderFlags == 0
        FileAssetKind.AUDIO -> isEmbedded
        else -> false
    }

    private fun JsonObject.string(key: String): String? =
        (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

    private fun JsonObject.long(key: String): Long? =
        (this[key] as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull

    private const val UINT32_MAX = 0xffff_ffffL
}
