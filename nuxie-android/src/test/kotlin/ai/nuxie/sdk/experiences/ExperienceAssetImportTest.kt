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
    fun `input font identity and typography must match before resolving device bytes`() {
        val catalog = listOf(ExpectedFileAsset(0, FileAssetKind.FONT, 10, "native", "ttf", false, false, 3))
        var resolutions = 0
        fun bind(family: String, weight: String, style: String, name: String) =
            ExperienceAssetImportBuilder.build(buildJsonObject {
                put("render", buildJsonObject {
                    put("assets", buildJsonArray { add(buildJsonObject {
                        put("kind", "font"); put("location", "system"); put("family", "System")
                        put("weight", "700"); put("style", "normal"); put("riveAssetId", 10)
                        put("riveUniqueName", "native-10"); put("required", true)
                    }) })
                    put("textInputs", buildJsonArray { add(buildJsonObject {
                        put("style", buildJsonObject {
                            put("fontFamily", family); put("fontWeight", weight)
                            put("fontStyle", style); put("fontAssetRiveUniqueName", name)
                        })
                    }) })
                })
            }, emptyMap(), catalog) { resolutions++; byteArrayOf(1) }
        for ((family, weight, style, name) in listOf(
            listOf("Inter", "700", "normal", "native-10"),
            listOf("System", "400", "normal", "native-10"),
            listOf("System", "700", "italic", "native-10"),
            listOf("System", "700", "normal", "unknown-10"),
        )) {
            assertThrows(IllegalArgumentException::class.java) { bind(family, weight, style, name) }
            assertEquals(0, resolutions)
        }
        bind("System", "700", "normal", "native-10")
        assertEquals(1, resolutions)
    }

    @Test
    fun `system and CDN fonts bind distinct ordinals after complete catalog validation`() {
        val system = buildJsonObject {
            put("kind", "font")
            put("location", "system")
            put("family", "System")
            put("weight", "700")
            put("style", "normal")
            put("riveAssetId", 10)
            put("riveUniqueName", "native-10")
            put("required", true)
        }
        val cdn = buildJsonObject {
            put("kind", "font")
            put("location", "cdn")
            put("key", "downloaded-font")
            put("riveAssetId", 20)
            put("riveUniqueName", "custom-20")
            put("required", true)
        }
        val descriptor = buildJsonObject {
            put("render", buildJsonObject {
                put("assets", buildJsonArray { add(system); add(cdn) })
            })
        }
        val catalog = listOf(
            ExpectedFileAsset(0, FileAssetKind.FONT, 20, "custom", "ttf", false, false, 3),
            ExpectedFileAsset(1, FileAssetKind.FONT, 10, "native", "ttf", false, false, 3),
        )
        val cdnBytes = byteArrayOf(1, 2, 3)
        val localBytes = byteArrayOf(4, 5, 6)
        val file = File.createTempFile("cdn-font-", ".ttf").apply { writeBytes(cdnBytes) }
        try {
            val requests = mutableListOf<SystemFontRequirement>()
            val resolver: (SystemFontRequirement) -> ByteArray = { requests += it; localBytes }
            val bound = ExperienceAssetImportBuilder.build(
                descriptor, mapOf("downloaded-font" to file), catalog, resolver,
            )
            assertEquals(listOf(SystemFontRequirement("native-10", 700, "normal")), requests)
            assertArrayEquals(cdnBytes, bound.externalAssets.getValue(0))
            assertArrayEquals(localBytes, bound.externalAssets.getValue(1))
            requests.clear()
            assertThrows(IllegalArgumentException::class.java) {
                ExperienceAssetImportBuilder.build(
                    descriptor, emptyMap(),
                    listOf(catalog[1].copy(ordinal = 0), catalog[0].copy(ordinal = 1, name = "wrong")),
                    resolver,
                )
            }
            assertEquals(emptyList<SystemFontRequirement>(), requests)
            assertThrows(IllegalArgumentException::class.java) {
                ExperienceAssetImportBuilder.build(
                    descriptor, emptyMap(),
                    listOf(catalog[0], catalog[1].copy(isEmbedded = true)), resolver,
                )
            }
            assertEquals(emptyList<SystemFontRequirement>(), requests)
        } finally {
            file.delete()
        }
    }

    @Test
    fun `video binding retains file and exact identity without provider bytes`() {
        val file = File.createTempFile("video-binding-", ".mp4")
        try {
            val key = "assets/sha256/${"b".repeat(64)}.mp4"
            fun descriptor(required: Boolean = true, source: String = "asset:greeting") = buildJsonObject {
                put("render", buildJsonObject {
                    put("assets", buildJsonArray {
                        add(buildJsonObject {
                            put("kind", "video"); put("key", key)
                            put("riveAssetId", 2); put("riveUniqueName", "greeting-2")
                            put("sourceAssetKey", source); put("required", required)
                        })
                    })
                })
            }
            val catalog = ExpectedFileAsset(0, FileAssetKind.VIDEO, 2, "greeting", "mp4", false, false, 4)
            val binding = ExperienceAssetImportBuilder.build(descriptor(), mapOf(key to file), listOf(catalog))
            assertEquals(emptyMap<Int, ByteArray>(), binding.externalAssets)
            assertEquals(listOf(ExperienceVideoAssetBinding(0, 2, "asset:greeting", file, true)), binding.videos)
            for (invalid in listOf(catalog.copy(isEmbedded = true), catalog.copy(authoredId = 3),
                catalog.copy(requiredProviderFlags = 3), catalog.copy(name = "other"))) {
                assertThrows(IllegalArgumentException::class.java) {
                    ExperienceAssetImportBuilder.build(descriptor(), mapOf(key to file), listOf(invalid))
                }
            }
            assertThrows(IllegalArgumentException::class.java) {
                ExperienceAssetImportBuilder.build(descriptor(), emptyMap(), listOf(catalog))
            }
            assertThrows(IllegalStateException::class.java) {
                ExperienceAssetImportBuilder.build(descriptor(source = "https://provider.example/video"), mapOf(key to file), listOf(catalog))
            }
            val optional = ExperienceAssetImportBuilder.build(descriptor(required = false), emptyMap(), listOf(catalog))
            assertEquals(listOf(ExperienceVideoAssetBinding(0, 2, "asset:greeting", null, false)), optional.videos)
        } finally { file.delete() }
    }

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
