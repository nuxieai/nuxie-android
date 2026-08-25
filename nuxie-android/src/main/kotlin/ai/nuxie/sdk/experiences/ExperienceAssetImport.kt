package ai.nuxie.sdk.experiences

import ai.nuxie.sdk.runtime.ExpectedFileAsset
import ai.nuxie.sdk.runtime.FileAssetKind
import java.io.File
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.longOrNull

/** Immutable inputs for one configured native file import. */
internal data class ExperienceAssetImport(
    val expectedAssets: List<ExpectedFileAsset>,
    val externalAssets: Map<Int, ByteArray>,
)

/**
 * Binds the signed release declarations and acquired files to the RIV's
 * complete, file-order catalog. The native configured import validates the
 * same catalog again before invoking any provider hook.
 */
internal object ExperienceAssetImportBuilder {
    fun build(
        descriptor: JsonObject,
        artifactsByKey: Map<String, File>,
        inspectedCatalog: List<ExpectedFileAsset>,
    ): ExperienceAssetImport {
        require(inspectedCatalog.withIndex().all { (index, asset) ->
            asset.ordinal == index
        }) { "Experience asset catalog must be complete and file-ordered" }

        val declarations = declarations(descriptor).toMutableList()
        require(declarations.distinctBy(Declaration::identity).size == declarations.size) {
            "Signed Experience assets contain duplicate authored identities"
        }
        val externalAssets = linkedMapOf<Int, ByteArray>()
        inspectedCatalog.forEach { asset ->
            val declarationIndex = declarations.indexOfFirst { declaration ->
                declaration.matches(asset)
            }
            if (declarationIndex >= 0) {
                val declaration = declarations.removeAt(declarationIndex)
                if (!asset.isEmbedded) {
                    val file = artifactsByKey[declaration.artifactKey]
                    if (file == null) {
                        require(!declaration.required) {
                            "Required Experience asset was not acquired: ${declaration.artifactKey}"
                        }
                    } else {
                        externalAssets[asset.ordinal] = file.readBytes()
                    }
                }
            } else {
                require(asset.mayRemainInBand()) {
                    "Authored Experience asset is not declared: ordinal ${asset.ordinal}"
                }
            }
        }
        require(declarations.isEmpty()) {
            "Signed Experience assets do not exactly match the authored catalog"
        }

        return ExperienceAssetImport(
            expectedAssets = inspectedCatalog.toList(),
            externalAssets = externalAssets.toMap(),
        )
    }

    private fun declarations(descriptor: JsonObject): List<Declaration> {
        val render = descriptor["render"] as? JsonObject
            ?: error("Experience release render is missing")
        val assets = render["assets"] as? JsonArray
            ?: error("Experience release assets are missing")
        return assets.mapIndexedNotNull { index, value ->
            val asset = value as? JsonObject
                ?: error("Experience release asset $index is invalid")
            val kind = when (asset.string("kind")) {
                "image" -> FileAssetKind.IMAGE
                "font" -> FileAssetKind.FONT
                // Script and shader bytes remain authenticated in-band. They
                // are represented by the complete native catalog but have no
                // external provider entry in the authoritative iOS binding.
                "script", "shader" -> return@mapIndexedNotNull null
                else -> error("Experience release asset $index has an unsupported kind")
            }
            Declaration(
                kind = kind,
                authoredId = asset.long("riveAssetId")
                    ?.takeIf { it in 0..UINT32_MAX }
                    ?: error("Experience release asset $index has an invalid authored id"),
                uniqueName = asset.string("riveUniqueName")
                    ?.takeIf(String::isNotBlank)
                    ?: error("Experience release asset $index has no unique name"),
                artifactKey = asset.string("key")
                    ?: error("Experience release asset $index has no artifact key"),
                required = (asset["required"] as? JsonPrimitive)?.booleanOrNull
                    ?: error("Experience release asset $index has no required flag"),
            )
        }
    }

    private data class Declaration(
        val kind: FileAssetKind,
        val authoredId: Long,
        val uniqueName: String,
        val artifactKey: String,
        val required: Boolean,
    ) {
        fun identity(): Triple<FileAssetKind, Long, String> =
            Triple(kind, authoredId, uniqueName)

        fun matches(asset: ExpectedFileAsset): Boolean {
            // The declaration identity is the Rive-uniquified "name-authoredId";
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
