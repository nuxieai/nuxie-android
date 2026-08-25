package ai.nuxie.sdk.experiences

import ai.nuxie.sdk.runtime.ExpectedFileAsset
import ai.nuxie.sdk.runtime.FileAssetKind
import java.io.File
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ExperienceAssetImportTest {
    @Test
    fun `synthetic release builds the complete expected catalog and ordinal payloads`() {
        val digest = "a".repeat(64)
        val imageKey = "assets/sha256/$digest.png"
        val descriptor = buildJsonObject {
            put("render", buildJsonObject {
                put("assets", buildJsonArray {
                    add(buildJsonObject {
                        put("kind", "image")
                        put("key", imageKey)
                        put("sha256", digest)
                        put("sizeBytes", 4)
                        put("contentType", "image/png")
                        put("riveAssetId", 1)
                        put("riveUniqueName", "hero-1")
                        put("required", true)
                    })
                })
            })
        }
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
    fun `synthetic declaration rejects embedded and noncanonical catalog identities`() {
        val digest = "b".repeat(64)
        val descriptor = buildJsonObject {
            put("render", buildJsonObject {
                put("assets", buildJsonArray {
                    add(buildJsonObject {
                        put("kind", "image")
                        put("key", "assets/sha256/$digest.png")
                        put("sha256", digest)
                        put("sizeBytes", 4)
                        put("contentType", "image/png")
                        put("riveAssetId", 1)
                        put("riveUniqueName", "hero-1")
                        put("required", true)
                    })
                })
            })
        }
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
        val digest = "c".repeat(64)
        val descriptor = buildJsonObject {
            put("render", buildJsonObject {
                put("assets", buildJsonArray {
                    add(buildJsonObject {
                        put("kind", "image")
                        put("key", "assets/sha256/$digest.png")
                        put("sha256", digest)
                        put("sizeBytes", 4)
                        put("contentType", "image/png")
                        put("riveAssetId", 1)
                        put("riveUniqueName", "hero")
                        put("required", true)
                    })
                })
            })
        }

        assertThrows(IllegalArgumentException::class.java) {
            ExperienceAssetImportBuilder.build(
                descriptor = descriptor,
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

}
