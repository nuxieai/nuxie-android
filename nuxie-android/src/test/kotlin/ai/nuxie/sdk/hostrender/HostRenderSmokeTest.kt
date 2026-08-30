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
import kotlinx.serialization.json.put
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

class HostRenderSmokeTest {
    @Test
    fun `plain file import supplies an inert sized config`() {
        assumeHostRuntime()
        val runtime = NuxieRuntime.shared
        assertTrue("Host nux_capi and JNI adapter must load", runtime.isAvailable)
        val bytes = File(
            System.getProperty("nuxie.repo.root"),
            "nuxie-android/src/androidTest/assets/data_binding_test.riv",
        ).readBytes()
        checkNotNull(runtime.inspectFileAssets(bytes)) {
            "Asset inspection must pass a valid inert render callback table"
        }
        val renderer = checkNotNull(runtime.newAndroidVulkanRenderer(64, 64))
        try {
            val file = checkNotNull(runtime.importFile(renderer, bytes)) {
                "Plain JNI import must pass a valid config even without optional capabilities"
            }
            try {
                val artboard = checkNotNull(file.newArtboard())
                artboard.close()
            } finally {
                file.close()
            }
        } finally {
            renderer.close()
        }
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
        // This is fixtures/p2d/scripted_interpolator.riv from nuxie-runtime.
        // Its transform is factor² + (calls - 1) * 0.005. Pinned C++
        // 4ac7b327 keyframe_color.cpp uses the stateful interpolator and
        // shapes/paint/color.cpp rounds colorLerp to bytes: at 16/32 ms,
        // round(255 * .016²) = 0; round(255 * (.032² + .005)) = 2.
        // Sample inside the moving rectangle, not its antialiased edges.
        for ((index, expected) in listOf(OPAQUE_BLACK_RGBA, byteArrayOf(2, 2, 2, -1)).withIndex()) {
            val pixels = File(output, "frame-$index.rgba").readBytes().asPixels()
            for ((x, y) in listOf(5 to 2, 10 to 5, 15 to 8)) {
                assertArrayEquals("Scripted interior at frame $index ($x,$y)", expected, pixels[y * 100 + x])
            }
            for ((x, y) in listOf(50 to 25, 50 to 50, 75 to 75)) {
                assertArrayEquals("Clear background at frame $index ($x,$y)", MAGENTA_RGBA, pixels[y * 100 + x])
            }
        }
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
