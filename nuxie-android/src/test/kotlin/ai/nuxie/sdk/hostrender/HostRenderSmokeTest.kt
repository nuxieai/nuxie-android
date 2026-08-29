package ai.nuxie.sdk.hostrender

import ai.nuxie.sdk.runtime.FileAssetKind
import ai.nuxie.sdk.runtime.NuxieRuntime
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import java.util.Base64
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

class HostRenderSmokeTest {
    @Test
    fun `signed release data changes real data-bound content`() {
        assumeHostRuntime()
        val seededInput = prepareFontConverterInput(includeReleaseEntry = true)
        val unseededInput = prepareFontConverterInput(includeReleaseEntry = false)
        val seededOutput = Files.createTempDirectory("host-render-seeded-output-").toFile()
        val unseededOutput = Files.createTempDirectory("host-render-unseeded-output-").toFile()

        val seeded = HostRenderHarness().run(
            HostRenderOptions(seededInput, seededOutput),
        ).frames.single()
        val unseeded = HostRenderHarness().run(
            HostRenderOptions(unseededInput, unseededOutput),
        ).frames.single()

        assertFalse(
            "Skipping the signed release entry must change the data-bound frame",
            seeded.sha256 == unseeded.sha256,
        )
    }

    @Test
    fun `script-dependent interpolator changes the rendered frame`() {
        assumeHostRuntime()
        val input = prepareScriptedInterpolatorInput()
        val output = Files.createTempDirectory("host-render-script-output-").toFile()

        val result = HostRenderHarness().run(
            HostRenderOptions(
                input,
                output,
                frameCount = 2,
                size = HostRenderSize(100, 100),
            ),
        )
        val secondFrame = File(output, "frame-1.rgba").readBytes().asPixels()

        assertTrue(
            "The script must drive the second frame to the authored black-and-magenta state",
            secondFrame.all { pixel ->
                pixel.contentEquals(OPAQUE_BLACK_RGBA) || pixel.contentEquals(MAGENTA_RGBA)
            },
        )
        assertFalse(
            "The script must change the rendered frame",
            result.frames[0].sha256 == result.frames[1].sha256,
        )
    }

    @Test
    fun `real configured Experience renders content into a host CPU frame`() {
        assumeHostRuntime()
        val input = prepareInput(includeExternalAsset = true)
        val output = Files.createTempDirectory("host-render-smoke-output-").toFile()
        val repeatedOutput = Files.createTempDirectory("host-render-repeat-output-").toFile()

        val frame = HostRenderHarness().run(
            HostRenderOptions(input, output, size = HostRenderSize(64, 64)),
        ).frames.single()
        val repeatedFrame = HostRenderHarness().run(
            HostRenderOptions(input, repeatedOutput, size = HostRenderSize(64, 64)),
        ).frames.single()
        val rgba = File(output, "frame-0.rgba").readBytes()

        assertTrue(
            "The same input and driver must produce the same frame hash",
            frame.sha256 == repeatedFrame.sha256,
        )
        assertTrue("Smoke frame must have RGBA pixels", rgba.size == frame.width * frame.height * 4)
        assertFalse(
            "Smoke frame must not be uniformly the magenta clear color",
            rgba.asPixels().all { it.contentEquals(MAGENTA_RGBA) },
        )
        assertFalse(
            "Smoke frame must not be opaque black",
            rgba.asPixels().all { it.contentEquals(OPAQUE_BLACK_RGBA) },
        )
    }

    @Test
    fun `omitting external asset wiring changes the real host smoke frame`() {
        assumeHostRuntime()
        val input = prepareInput(includeExternalAsset = true)
        val expectedOutput = Files.createTempDirectory("host-render-wired-output-").toFile()
        val output = Files.createTempDirectory("host-render-mutant-output-").toFile()

        val expected = HostRenderHarness().run(
            HostRenderOptions(input, expectedOutput, size = HostRenderSize(64, 64)),
        ).frames.single()
        val mutant = HostRenderHarness(wireExternalAssets = { emptyMap() }).run(
            HostRenderOptions(input, output, size = HostRenderSize(64, 64)),
        ).frames.single()

        assertFalse(
            "Skipping required external-asset wiring must fail the configured-content oracle",
            expected.sha256 == mutant.sha256,
        )
    }

    private fun prepareInput(includeExternalAsset: Boolean): File {
        val runtime = NuxieRuntime.shared
        assertTrue("Host nux_capi and JNI adapter must load", runtime.isAvailable)
        val fixtureDirectory = File(
            System.getProperty("nuxie.repo.root"),
            "example-app/src/debug/assets/asset-smoke",
        )
        val rivBytes = File(fixtureDirectory, "external-image.riv").readBytes()
        val imageBytes = File(fixtureDirectory, "external-image.png").readBytes()
        val imageAsset = checkNotNull(runtime.inspectFileAssets(rivBytes))
            .single { it.kind == FileAssetKind.IMAGE }
        val authoredId = checkNotNull(imageAsset.authoredId)
        val input = Files.createTempDirectory("host-render-smoke-input-").toFile()
        File(input, "scene.riv").writeBytes(rivBytes)
        if (includeExternalAsset) {
            File(input, "assets/external-image.png").apply {
                requireNotNull(parentFile).mkdirs()
                writeBytes(imageBytes)
            }
        }
        val descriptor = buildJsonObject {
            put("presentation", buildJsonObject {
                put("backgroundColor", "#FF00FFFF")
            })
            put("render", buildJsonObject {
                put("renderer", "rive")
                put("riv", buildJsonObject { put("key", "scene.riv") })
                put("assets", buildJsonArray {
                    add(buildJsonObject {
                        put("kind", "image")
                        put("key", "assets/external-image.png")
                        put("sha256", sha256(imageBytes))
                        put("sizeBytes", imageBytes.size)
                        put("contentType", "image/png")
                        put("riveAssetId", authoredId)
                        put("riveUniqueName", "${imageAsset.name}-$authoredId")
                        put("required", true)
                    })
                })
                put("screens", buildJsonArray {
                    add(buildJsonObject {
                        put("width", 64)
                        put("height", 64)
                    })
                })
            })
        }
        File(input, "release-descriptor.json").writeText(
            JSON.encodeToString(JsonElement.serializer(), descriptor),
        )
        return input
    }

    private fun prepareScriptedInterpolatorInput(): File {
        val rivBytes = fixtureBytes("scripted-interpolator.riv.base64")
        val input = Files.createTempDirectory("host-render-script-input-").toFile()
        File(input, "scene.riv").writeBytes(rivBytes)
        val descriptor = buildJsonObject {
            put("presentation", buildJsonObject {
                put("backgroundColor", "#FF00FFFF")
            })
            put("render", buildJsonObject {
                put("renderer", "rive")
                put("riv", buildJsonObject { put("key", "scene.riv") })
                put("assets", buildJsonArray {})
                put("screens", buildJsonArray {
                    add(buildJsonObject {
                        put("width", 100)
                        put("height", 100)
                    })
                })
            })
        }
        File(input, "release-descriptor.json").writeText(
            JSON.encodeToString(JsonElement.serializer(), descriptor),
        )
        return input
    }

    /** Exact production-generated fixture copied from the iOS reference corpus. */
    private fun prepareFontConverterInput(includeReleaseEntry: Boolean): File {
        val entryBytes = fixtureBytes("font-converter.release-entry.json.base64")
        val entry = JSON.parseToJsonElement(entryBytes.decodeToString()).jsonObject
        val envelopeBytes = Base64.getDecoder().decode(
            entry.getValue("envelopeBytesBase64").jsonPrimitive.content,
        )
        val envelope = JSON.parseToJsonElement(envelopeBytes.decodeToString()).jsonObject
        val descriptorBytes = Base64.getDecoder().decode(
            envelope.getValue("descriptorBytesBase64").jsonPrimitive.content,
        )
        val descriptor = JSON.parseToJsonElement(descriptorBytes.decodeToString()).jsonObject
        val render = descriptor.getValue("render").jsonObject
        val rivKey = render.getValue("riv").jsonObject.getValue("key").jsonPrimitive.content
        val fontKey = render.getValue("assets").jsonArray.single()
            .jsonObject.getValue("key").jsonPrimitive.content
        val input = Files.createTempDirectory("host-render-font-converter-input-").toFile()

        File(input, "release-descriptor.json").writeBytes(descriptorBytes)
        if (includeReleaseEntry) File(input, "release-entry.json").writeBytes(entryBytes)
        File(input, rivKey).apply {
            requireNotNull(parentFile).mkdirs()
            writeBytes(fixtureBytes("font-converter.riv.base64"))
        }
        File(input, fontKey).apply {
            requireNotNull(parentFile).mkdirs()
            writeBytes(fixtureBytes("font-converter.ttf.base64"))
        }
        return input
    }

    private fun fixtureBytes(name: String): ByteArray {
        val resource = checkNotNull(
            javaClass.getResourceAsStream("/hostrender/$name"),
        ) { "Missing host render fixture: $name" }
        return resource.bufferedReader().use { reader ->
            Base64.getMimeDecoder().decode(reader.readText())
        }
    }

    private fun assumeHostRuntime() {
        assumeTrue(
            "NUXIE_HOST_CAPI_LIB must name a host-built nux_capi library",
            !System.getenv("NUXIE_HOST_CAPI_LIB").isNullOrBlank(),
        )
    }

    private fun ByteArray.asPixels(): List<ByteArray> =
        asList().chunked(4).map { it.toByteArray() }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { "%02x".format(it) }

    private companion object {
        val JSON = Json { prettyPrint = true }
        val MAGENTA_RGBA = byteArrayOf(0xff.toByte(), 0, 0xff.toByte(), 0xff.toByte())
        val OPAQUE_BLACK_RGBA = byteArrayOf(0, 0, 0, 0xff.toByte())
    }
}
