package ai.nuxie.sdk.experiences

import ai.nuxie.sdk.runtime.ExpectedFileAsset
import ai.nuxie.sdk.runtime.FileAssetKind
import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ExperienceAssetImportTest {
    @Test
    fun `fixture release builds the complete expected catalog and ordinal payloads`() {
        val descriptor = fixtureDescriptor()
        val render = descriptor.getValue("render").jsonObject
        val image = render.getValue("assets").let { it as JsonArray }.single().jsonObject
        val imageKey = image.getValue("key").let { it as JsonPrimitive }.content
        val imageBytes = byteArrayOf(1, 2, 3, 4)
        val imageFile = File.createTempFile("asset-import-image-", ".png").apply {
            writeBytes(imageBytes)
            deleteOnExit()
        }
        val inspectedCatalog = listOf(
            ExpectedFileAsset(
                ordinal = 0,
                kind = FileAssetKind.IMAGE,
                authoredId = 1,
                name = "hero",
                fileExtension = "png",
                isEmbedded = false,
                hasContentsRecord = false,
                requiredProviderFlags = 3,
            ),
            ExpectedFileAsset(
                ordinal = 1,
                kind = FileAssetKind.SCRIPT,
                authoredId = 7,
                name = "interaction",
                fileExtension = "lua",
                isEmbedded = true,
                hasContentsRecord = true,
                requiredProviderFlags = 0,
            ),
        )

        val prepared = ExperienceAssetImportBuilder.build(
            descriptor = descriptor,
            artifactsByKey = mapOf(imageKey to imageFile),
            inspectedCatalog = inspectedCatalog,
        )

        assertEquals(inspectedCatalog, prepared.expectedAssets)
        assertEquals(setOf(0), prepared.externalAssets.keys)
        assertArrayEquals(imageBytes, prepared.externalAssets.getValue(0))
    }

    @Test
    fun `fixture declaration rejects embedded and noncanonical catalog identities`() {
        val descriptor = fixtureDescriptor()
        val base = ExpectedFileAsset(
            ordinal = 0,
            kind = FileAssetKind.IMAGE,
            authoredId = 1,
            name = "hero",
            fileExtension = "png",
            isEmbedded = false,
            hasContentsRecord = false,
            requiredProviderFlags = 3,
        )

        listOf(base.copy(isEmbedded = true), base.copy(name = "hero-1")).forEach { catalog ->
            assertThrows(IllegalArgumentException::class.java) {
                ExperienceAssetImportBuilder.build(
                    descriptor = descriptor,
                    artifactsByKey = emptyMap(),
                    inspectedCatalog = listOf(catalog),
                )
            }
        }
    }

    @Test
    fun `bare-name declarations do not bind, matching the iOS convention`() {
        assertThrows(IllegalArgumentException::class.java) {
            ExperienceAssetImportBuilder.build(
                descriptor = rawFixtureDescriptor(),
                artifactsByKey = emptyMap(),
                inspectedCatalog = listOf(
                    ExpectedFileAsset(
                        ordinal = 0,
                        kind = FileAssetKind.IMAGE,
                        authoredId = 1,
                        name = "hero",
                        fileExtension = "png",
                        isEmbedded = false,
                        hasContentsRecord = false,
                        requiredProviderFlags = 3,
                    ),
                ),
            )
        }
    }

    private fun fixtureDescriptor(): JsonObject {
        // The synthetic envelope fixture predates asset binding and carries a
        // bare riveUniqueName; the publisher and the iOS binding both use the
        // Rive-uniquified "name-authoredId" form, so rewrite it here.
        return withCanonicalUniqueNames(rawFixtureDescriptor())
    }

    private fun rawFixtureDescriptor(): JsonObject {
        val fixture = File(
            "${System.getProperty("user.dir")}/../fixtures/experience-release-descriptor/envelope.json",
        )
        val envelope = Json.parseToJsonElement(fixture.readText()).jsonObject
        val encoded = envelope.getValue("descriptorBytesBase64")
            .let { it as JsonPrimitive }.content
        val bytes = java.util.Base64.getDecoder().decode(encoded)
        return Json.parseToJsonElement(bytes.decodeToString()).jsonObject
    }

    private fun withCanonicalUniqueNames(descriptor: JsonObject): JsonObject {
        val render = descriptor.getValue("render").jsonObject
        val assets = JsonArray(
            (render.getValue("assets") as JsonArray).map { value ->
                val asset = value.jsonObject
                val authoredId = (asset.getValue("riveAssetId") as JsonPrimitive).content
                val uniqueName = (asset.getValue("riveUniqueName") as JsonPrimitive).content
                JsonObject(asset + ("riveUniqueName" to JsonPrimitive("$uniqueName-$authoredId")))
            },
        )
        return JsonObject(descriptor + ("render" to JsonObject(render + ("assets" to assets))))
    }

}
