package ai.nuxie.sdk.hostrender

import ai.nuxie.sdk.experiences.ExperienceViewModelBinding
import ai.nuxie.sdk.presentation.ExperienceScreenRuntimeValues
import ai.nuxie.sdk.presentation.ExperienceScreenLifecycle
import ai.nuxie.sdk.presentation.ExperienceScreenLifecycle.ExitReason
import ai.nuxie.sdk.presentation.ExperienceScreenLifecycle.Phase
import ai.nuxie.sdk.runtime.FileAssetKind
import ai.nuxie.sdk.runtime.NuxieRuntime
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import java.util.Base64
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

class HostRenderSmokeTest {
    @Test
    fun `dismissal animates before native completion while navigation skips and the final pose stays hidden`() {
        assertTrue("Native proof requires the configured host C API", !System.getenv("NUXIE_HOST_CAPI_LIB").isNullOrBlank())
        val runtime = NuxieRuntime.shared
        assertTrue("Pinned runtime and host JNI must load", runtime.isAvailable)
        val bytes = File(System.getProperty("nuxie.repo.root"),
            "fixtures/runtime/screen-exit-reason/dismissed-only/screen.nux").readBytes()
        val event = "nuxie:screen-exit-complete:scr_screens_stap"
        for (reason in listOf(ExitReason.NAVIGATION, ExitReason.DISMISSED)) {
            val renderer = checkNotNull(runtime.newAndroidVulkanRenderer(100, 100))
            try {
                val file = checkNotNull(runtime.importFile(renderer, bytes))
                try {
                    val artboard = checkNotNull(file.newArtboard("tap"))
                    try {
                        artboard.bindDefaultViewModel(motionModelName("dismissed-only"), "root-sdk-id")
                        val player = file.newExperiencePlayer(artboard, "tap")
                        try {
                            val state = ExperienceScreenLifecycle()
                            fun move(phase: Phase, reason: ExitReason = ExitReason.NONE) {
                                for ((path, value) in state.move(phase, exitReason = reason).filterKeys { it.startsWith("screen/") }) {
                                    assertTrue("Native lifecycle write $path", artboard.setDefaultViewModelValue(path, value))
                                }
                            }
                            fun centerRgb(): List<Int> {
                                val frame = renderer.renderToCpuFrame(player, 0xFF000000.toInt(), 1f)
                                assertEquals(100, frame.width)
                                assertEquals(100, frame.height)
                                val offset = (50 * 100 + 50) * 4
                                return (0..2).map { frame.rgba[offset + it].toInt() and 255 }
                            }
                            fun green(): Int = centerRgb()[1]
                            move(Phase.ENTERING)
                            player.stepTyped(elapsedSeconds = 0.0)
                            move(Phase.ACTIVE)
                            player.stepTyped(elapsedSeconds = 0.0)
                            val activeGreen = green()
                            assertTrue(activeGreen > 245)
                            move(Phase.EXITING, reason)
                            val start = player.stepTyped(elapsedSeconds = 0.0)
                            if (reason == ExitReason.NAVIGATION) {
                                assertEquals(1, start.events.count { it.name == event })
                                assertEquals(activeGreen, green())
                            } else {
                                assertEquals(0, start.events.count { it.name == event })
                                val middle = player.stepTyped(elapsedSeconds = 0.2)
                                assertEquals(0, middle.events.count { it.name == event })
                                val middleGreen = green()
                                assertTrue("The actual native opacity must move between endpoints", middleGreen > 20 && middleGreen < activeGreen - 20)
                                val beforeEnd = player.stepTyped(elapsedSeconds = 0.19)
                                assertEquals(0, beforeEnd.events.count { it.name == event })
                                val complete = player.stepTyped(elapsedSeconds = 0.02)
                                assertEquals(1, complete.events.count { it.name == event })
                                val finalRgb = centerRgb()
                                assertEquals(DEFAULT_PUBLISHED_SCREEN_BACKING_RGB, finalRgb)
                                val settled = player.stepTyped(elapsedSeconds = 0.1)
                                assertEquals(0, settled.events.count { it.name == event })
                                assertEquals("Completion must preserve the terminal pose", finalRgb, centerRgb())
                            }
                        } finally { player.close() }
                    } finally { artboard.close() }
                } finally { file.close() }
            } finally { renderer.close() }
        }
    }

    @Test
    fun `real native screen reasons accept both exits and preserve older lifecycle fields`() {
        assertTrue("Native proof requires the configured host C API", !System.getenv("NUXIE_HOST_CAPI_LIB").isNullOrBlank())
        val runtime = NuxieRuntime.shared
        assertTrue("Pinned runtime and host JNI must load", runtime.isAvailable)
        for (variant in listOf("dismissed-only", "field-absent")) {
            val hasReason = variant == "dismissed-only"
            val bytes = File(System.getProperty("nuxie.repo.root"),
                "fixtures/runtime/screen-exit-reason/$variant/screen.nux").readBytes()
            val renderer = checkNotNull(runtime.newAndroidVulkanRenderer(100, 100))
            try {
                val file = checkNotNull(runtime.importFile(renderer, bytes))
                try {
                    val artboard = checkNotNull(file.newArtboard("tap"))
                    try {
                        artboard.bindDefaultViewModel(motionModelName(variant), "root-sdk-id")
                        val player = file.newExperiencePlayer(artboard, "tap")
                        try {
                            val state = ExperienceScreenLifecycle()
                            val writes = ExperienceScreenRuntimeValues(artboard.supportsDefaultEnumPath("screen/exitReason"))
                            val rejected = mutableListOf<String>()
                            val visits = listOf(
                                Triple(Phase.ENTERING, ExitReason.NONE, 0L),
                                Triple(Phase.ACTIVE, ExitReason.NONE, 0L),
                                Triple(Phase.EXITING, ExitReason.NAVIGATION, 2L),
                                Triple(Phase.HIDDEN, ExitReason.NONE, 0L),
                                Triple(Phase.ENTERING, ExitReason.NONE, 0L),
                                Triple(Phase.ACTIVE, ExitReason.NONE, 0L),
                                Triple(Phase.EXITING, ExitReason.DISMISSED, 1L),
                            )
                            for ((phase, reason, expected) in visits) {
                                val values = state.move(phase, exitReason = reason).filterKeys { it.startsWith("screen/") }
                                // Keep the native absence proof separate from the SDK compatibility path.
                                var missingReasonRejected = false
                                try {
                                    artboard.setDefaultViewModelValue("screen/exitReason", values.getValue("screen/exitReason"))
                                } catch (error: Exception) {
                                    if (hasReason) throw error
                                    missingReasonRejected = true
                                }
                                assertEquals(!hasReason, missingReasonRejected)
                                writes.apply(values, write = { path, value ->
                                    assertTrue("The signed native default must receive $path", artboard.setDefaultViewModelValue(path, value))
                                }, onRejected = { path, _ -> rejected += path })
                                assertTrue("SDK lifecycle writes must not log a rejected path: $rejected", rejected.isEmpty())
                                player.stepTyped(elapsedSeconds = 0.0)
                                val snapshot = checkNotNull(artboard.defaultViewModelSnapshot())
                                assertEquals(if (hasReason) expected else null, snapshot.resolveEnumOrdinal("screen/exitReason"))
                            }
                            val snapshot = checkNotNull(artboard.defaultViewModelSnapshot())
                            assertEquals(3L, snapshot.resolveEnumOrdinal("screen/phase"))
                            assertEquals(ai.nuxie.sdk.runtime.NuxieViewModelScalarValue.NumberValue(2.0),
                                snapshot.resolveScalar(listOf("screen", "appearances")))
                            assertEquals(ai.nuxie.sdk.runtime.NuxieViewModelScalarValue.StringValue(""),
                                snapshot.resolveScalar(listOf("screen", "transition")))
                        } finally { player.close() }
                    } finally { artboard.close() }
                } finally { file.close() }
            } finally { renderer.close() }
        }
    }

    @Test
    fun `a real native placement tap moves the signed purchase target`() {
        assertTrue("Native proof requires the configured host C API", !System.getenv("NUXIE_HOST_CAPI_LIB").isNullOrBlank())
        val runtime = NuxieRuntime.shared
        assertTrue("Pinned runtime and host JNI must load", runtime.isAvailable)
        val bytes = File(System.getProperty("nuxie.repo.root"), "fixtures/runtime/paywall-placement-selection/screen.riv").readBytes()
        val renderer = checkNotNull(runtime.newAndroidVulkanRenderer(320, 100))
        try {
            val file = checkNotNull(runtime.importFile(renderer, bytes))
            try {
                val artboard = checkNotNull(file.newArtboard("Paywall"))
                try {
                    val projection = ai.nuxie.sdk.runtime.NuxieViewModelListProjection(
                        "Runtime", "paywall/products", "paywall/selectedProduct", "PaywallProduct",
                        listOf("monthly", "annual").mapIndexed { index, plan ->
                            ai.nuxie.sdk.runtime.NuxieViewModelListProjection.Item(plan.replaceFirstChar { it.uppercase() }, index, index == 0,
                                mapOf("productId" to ai.nuxie.sdk.runtime.NuxieViewModelScalarValue.StringValue("product-pro"),
                                      "placementId" to ai.nuxie.sdk.runtime.NuxieViewModelScalarValue.StringValue("placement-$plan")), instanceId = plan)
                        }, defaultInstanceId = "root-sdk-id")
                    val state = runtime.bindViewModelList(file, artboard, projection)
                    try {
                        val player = file.newExperiencePlayer(artboard, "Paywall")
                        try {
                            assertEquals("placement-monthly", state.snapshot().resolveScopedString(
                                "paywall.selectedProduct.placementId", "Runtime", "root-sdk-id"))
                            repeat(20) { state.captureSnapshot(player.stepTyped(elapsedSeconds = 0.016).viewModelChanges) }
                            val changes = mutableListOf<ai.nuxie.sdk.runtime.NuxieViewModelChange>()
                            fun advance(pointers: List<ai.nuxie.sdk.runtime.NuxiePlayerPointerEvent> = emptyList()): ai.nuxie.sdk.runtime.NuxieViewModelSnapshot {
                                val step = player.stepTyped(elapsedSeconds = 0.0, pointers = pointers)
                                changes += step.viewModelChanges
                                return state.captureSnapshot(step.viewModelChanges).values
                            }
                            advance(listOf(ai.nuxie.sdk.runtime.NuxiePlayerPointerEvent(
                                ai.nuxie.sdk.runtime.NuxiePlayerPointerKind.DOWN, 80f, 40f, 1, 0f)))
                            advance(listOf(ai.nuxie.sdk.runtime.NuxiePlayerPointerEvent(
                                ai.nuxie.sdk.runtime.NuxiePlayerPointerKind.UP, 80f, 40f, 1, 0.1f)))
                            val after = advance()
                            assertTrue("The real pointer must produce the placement write", changes.any {
                                it.origin == ai.nuxie.sdk.runtime.NuxieViewModelChangeOrigin.RUNTIME &&
                                (it.value as? ai.nuxie.sdk.runtime.NuxieViewModelValue.Bytes)?.value?.decodeToString() == "placement-annual" })
                            assertEquals("placement-annual", after.resolveScopedString(
                                "paywall.selectedProduct.placementId", "Runtime", "root-sdk-id"))
                            assertEquals(ai.nuxie.sdk.runtime.NuxieViewModelScalarValue.NumberValue(1.0),
                                after.resolveScalar(listOf("paywall", "selectedIndex")))
                            assertEquals(true, after.resolveBoolean("paywall.selectedProduct.isSelected"))
                        } finally { player.close() }
                    } finally { state.close() }
                } finally { artboard.close() }
            } finally { file.close() }
        } finally { renderer.close() }
    }

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
                // The same upstream data-binding fixture used by Android's
                // instrumentation smoke declares the Test root view model.
                // Exercise the real snapshot-root schema lookup before binding.
                artboard.bindDefaultViewModel("Test")
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
        val sceneBytes = namedArtboardFixture(
            File(fixtureDirectory, "external-image.riv").readBytes(),
            artboardOffset = 0x11,
            name = "Image smoke",
        )
        val imageBytes = File(fixtureDirectory, "external-image.png").readBytes()
        val imageAsset = checkNotNull(runtime.inspectFileAssets(sceneBytes))
            .single { it.kind == FileAssetKind.IMAGE }
        val authoredId = checkNotNull(imageAsset.authoredId)
        val input = Files.createTempDirectory("host-render-smoke-input-").toFile()
        File(input, "scene.nux").writeBytes(sceneBytes)
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
                put("renderer", "nux")
                put("nux", buildJsonObject { put("key", "scene.nux") })
                put("assets", buildJsonArray {
                    add(buildJsonObject {
                        put("kind", "image")
                        put("key", "assets/external-image.png")
                        put("sha256", sha256(imageBytes))
                        put("sizeBytes", imageBytes.size)
                        put("contentType", "image/png")
                        put("authoredAssetId", authoredId)
                        put("assetUniqueName", "${imageAsset.name}-$authoredId")
                        put("required", true)
                    })
                })
                put("screens", buildJsonArray {
                    add(buildJsonObject {
                        put("id", "image-smoke")
                        put("artboardName", "Image smoke")
                        put("width", 64)
                        put("height", 64)
                    })
                })
            })
            put("leg", buildJsonObject {
                put("screens", buildJsonArray {
                    add(buildJsonObject { put("id", "image-smoke") })
                })
            })
        }
        File(input, "release-descriptor.json").writeText(
            JSON.encodeToString(JsonElement.serializer(), descriptor),
        )
        return input
    }

    private fun prepareScriptedInterpolatorInput(): File {
        val sceneBytes = namedArtboardFixture(
            fixtureBytes("scripted-interpolator.riv.base64"),
            artboardOffset = 0x251,
            name = "Script smoke",
        )
        val input = Files.createTempDirectory("host-render-script-input-").toFile()
        File(input, "scene.nux").writeBytes(sceneBytes)
        val descriptor = buildJsonObject {
            put("presentation", buildJsonObject {
                put("backgroundColor", "#FF00FFFF")
            })
            put("render", buildJsonObject {
                put("renderer", "nux")
                put("nux", buildJsonObject { put("key", "scene.nux") })
                put("assets", buildJsonArray {})
                put("screens", buildJsonArray {
                    add(buildJsonObject {
                        put("id", "script-smoke")
                        put("artboardName", "Script smoke")
                        put("width", 100)
                        put("height", 100)
                    })
                })
            })
            put("leg", buildJsonObject {
                put("screens", buildJsonArray {
                    add(buildJsonObject { put("id", "script-smoke") })
                })
            })
        }
        File(input, "release-descriptor.json").writeText(
            JSON.encodeToString(JsonElement.serializer(), descriptor),
        )
        return input
    }

    private fun namedArtboardFixture(bytes: ByteArray, artboardOffset: Int, name: String): ByteArray {
        // These raw fixtures intentionally have unnamed Artboards. A published
        // Experience must declare a name, so author only ComponentBase's exact
        // upstream name property (key 4), leaving every asset/animation byte and
        // the original on-disk fixture unchanged. Verify the known Artboard(1)
        // followed by width(7) seam before inserting its length-prefixed string.
        require(bytes[artboardOffset] == 1.toByte() && bytes[artboardOffset + 1] == 7.toByte())
        val encodedName = name.encodeToByteArray()
        require(encodedName.size in 1..127)
        val insertion = artboardOffset + 1
        return bytes.copyOfRange(0, insertion) +
            byteArrayOf(4, encodedName.size.toByte()) + encodedName +
            bytes.copyOfRange(insertion, bytes.size)
    }

    private fun fixtureBytes(name: String): ByteArray {
        val resource = checkNotNull(
            javaClass.getResourceAsStream("/hostrender/$name"),
        ) { "Missing host render fixture: $name" }
        return resource.bufferedReader().use { reader ->
            Base64.getMimeDecoder().decode(reader.readText())
        }
    }

    private fun motionModelName(variant: String): String {
        val descriptor = Json.parseToJsonElement(File(System.getProperty("nuxie.repo.root"),
            "fixtures/runtime/screen-exit-reason/$variant/mounted-screen.json").readText()).jsonObject
        return checkNotNull(ExperienceViewModelBinding.defaultSchemaName(descriptor, "tap"))
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
        // Screens without an authored background use the compiler's default navy backing.
        val DEFAULT_PUBLISHED_SCREEN_BACKING_RGB = listOf(19, 37, 61)
        val JSON = Json { prettyPrint = true }
        val MAGENTA_RGBA = byteArrayOf(0xff.toByte(), 0, 0xff.toByte(), 0xff.toByte())
        val OPAQUE_BLACK_RGBA = byteArrayOf(0, 0, 0, 0xff.toByte())
    }
}
