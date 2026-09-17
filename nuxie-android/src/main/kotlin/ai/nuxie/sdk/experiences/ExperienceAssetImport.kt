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
            val system = systemFonts[style.string("fontAssetRiveUniqueName")]
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
        bindings.forEach { (asset, declaration) ->
            when (val source = declaration.source) {
                is Source.System -> externalAssets[asset.ordinal] = systemFontBytes(source.requirement)
                is Source.Download -> {
                    val file = artifactsByKey[source.key]
                    if (file == null) {
                        require(!declaration.required) {
                            "Required Experience asset was not acquired: ${source.key}"
                        }
                    } else {
                        externalAssets[asset.ordinal] = file.readBytes()
                    }
                }
            }
        }

        return ExperienceAssetImport(
            expectedAssets = inspectedCatalog.toList(),
            externalAssets = externalAssets.toMap(),
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
                // Script and shader bytes remain authenticated in-band. They
                // are represented by the complete native catalog but have no
                // external provider entry in the authoritative iOS binding.
                "script", "shader" -> return@mapIndexedNotNull null
                else -> error("Journey release asset $index has an unsupported kind")
            }
            Declaration(
                kind = kind,
                authoredId = asset.long("riveAssetId")
                    ?.takeIf { it in 0..UINT32_MAX }
                    ?: error("Journey release asset $index has an invalid authored id"),
                uniqueName = asset.string("riveUniqueName")
                    ?.takeIf(String::isNotBlank)
                    ?: error("Journey release asset $index has no unique name"),
                source = if (kind == FileAssetKind.FONT && asset.string("location") == "system") {
                    require(asset.string("family") == "System" && asset.string("style") == "normal")
                    val weight = asset.string("weight")
                    require(weight in (100..900 step 100).map(Int::toString))
                    require((asset["required"] as? JsonPrimitive)?.booleanOrNull == true)
                    Source.System(SystemFontRequirement(
                        uniqueName = asset.string("riveUniqueName")!!,
                        weight = weight!!.toInt(),
                        style = "normal",
                    ))
                } else {
                    Source.Download(asset.string("key")
                        ?: error("Journey release asset $index has no artifact key"))
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
