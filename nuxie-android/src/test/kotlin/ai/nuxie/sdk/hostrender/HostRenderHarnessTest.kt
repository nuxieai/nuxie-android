package ai.nuxie.sdk.hostrender

import ai.nuxie.sdk.runtime.DecodedImage
import ai.nuxie.sdk.runtime.ExpectedFileAsset
import ai.nuxie.sdk.runtime.FileAssetKind
import ai.nuxie.sdk.runtime.NativeCallResult
import ai.nuxie.sdk.runtime.NativeViewModelAuthoredInstance
import ai.nuxie.sdk.runtime.NativeViewModelCatalog
import ai.nuxie.sdk.runtime.NativeViewModelProperty
import ai.nuxie.sdk.runtime.NativeViewModelSchema
import ai.nuxie.sdk.runtime.NativeViewModelWrite
import ai.nuxie.sdk.runtime.NuxImageDecoder
import ai.nuxie.sdk.runtime.NuxieCpuFrame
import ai.nuxie.sdk.runtime.NuxieRuntime
import ai.nuxie.sdk.runtime.NuxieTypedRuntimeNative
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import java.util.Base64
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class HostRenderHarnessTest {
    @Test
    fun `release descriptor drives configured import fixed steps and CPU frame manifest`() {
        val input = Files.createTempDirectory("host-render-input-").toFile()
        val output = Files.createTempDirectory("host-render-output-").toFile()
        val asset = File(input, "assets/hero.png").apply {
            requireNotNull(parentFile).mkdirs()
            writeBytes(byteArrayOf(1, 2, 3, 4))
        }
        File(input, "scene.riv").writeBytes(byteArrayOf(82, 73, 86, 69))
        File(input, "release-descriptor.json").writeText(
            """
            {
              "presentation":{"backgroundColor":"#102030FF"},
              "render":{
                "renderer":"rive",
                "riv":{"key":"scene.riv"},
                "assets":[{
                  "kind":"image","key":"assets/hero.png","riveAssetId":7,
                  "riveUniqueName":"hero-7","required":true
                }],
                "screens":[{"artboardName":"Main","width":4,"height":2}]
              }
            }
            """.trimIndent(),
        )
        val native = RecordingNative()

        val result = HostRenderHarness(NuxieRuntime(native), PASS_THROUGH_DECODER).run(
            HostRenderOptions(input, output, frameCount = 2, stepMillis = 20),
        )

        assertEquals(2, result.frames.size)
        assertEquals(listOf(0.02, 0.02), native.steps)
        assertEquals(0, native.viewModelCatalogReads)
        assertEquals(emptyList<NativeViewModelWrite>(), native.viewModelWrites)
        assertEquals("Main", native.artboardName)
        assertEquals(HostRenderSize(4, 2), native.rendererSize)
        assertArrayEquals(asset.readBytes(), native.externalAssets.getValue(0))
        assertEquals(listOf("frame-0.rgba", "frame-1.rgba"), output.list()!!.filter {
            it.endsWith(".rgba")
        }.sorted())

        val manifest = Json.parseToJsonElement(File(output, "manifest.json").readText()).jsonObject
        val frames = manifest.getValue("frames").jsonArray
        assertEquals(2, frames.size)
        assertEquals("runtime-info", manifest.getValue("runtime").jsonObject
            .getValue("info").jsonPrimitive.content)
        assertEquals(
            sha256(File(output, "frame-0.rgba").readBytes()),
            frames[0].jsonObject.getValue("sha256").jsonPrimitive.content,
        )
    }

    @Test
    fun `profile release data is applied before the first frame`() {
        val (input, output, descriptor) = seededInput()
        writeProfile(input, descriptor)
        val native = RecordingNative(hasImageAsset = false)

        HostRenderHarness(NuxieRuntime(native), PASS_THROUGH_DECODER).run(
            HostRenderOptions(input, output),
        )

        assertEquals(listOf("catalog", "default", "bind", "write", "step", "free"), native.dataCalls)
        val write = native.viewModelWrites.single()
        assertEquals("label", write.path)
        assertArrayEquals("seeded copy".encodeToByteArray(), write.bytesValue)
    }

    @Test
    fun `release entry data is applied before the first frame`() {
        val (input, output, descriptor) = seededInput()
        writeReleaseEntry(input, descriptor)
        val native = RecordingNative(hasImageAsset = false)

        HostRenderHarness(NuxieRuntime(native), PASS_THROUGH_DECODER).run(
            HostRenderOptions(input, output),
        )

        assertArrayEquals(
            "seeded copy".encodeToByteArray(),
            native.viewModelWrites.single().bytesValue,
        )
    }

    @Test
    fun `descriptor data is skipped when release entry files are absent`() {
        val (input, output) = seededInput()
        val native = RecordingNative(hasImageAsset = false)

        HostRenderHarness(NuxieRuntime(native), PASS_THROUGH_DECODER).run(
            HostRenderOptions(input, output),
        )

        assertEquals(0, native.viewModelCatalogReads)
        assertEquals(emptyList<NativeViewModelWrite>(), native.viewModelWrites)
    }

    private class RecordingNative(
        private val hasImageAsset: Boolean = true,
    ) : NuxieTypedRuntimeNative {
        val steps = mutableListOf<Double>()
        var artboardName: String? = null
        var rendererSize: HostRenderSize? = null
        var externalAssets = emptyMap<Int, ByteArray>()
        var viewModelCatalogReads = 0
        val viewModelWrites = mutableListOf<NativeViewModelWrite>()
        val dataCalls = mutableListOf<String>()

        override val isAvailable = true
        override fun runtimeInfo(): String = "runtime-info"
        override fun inspectFileAssets(bytes: ByteArray) = if (hasImageAsset) {
            listOf(ExpectedFileAsset(0, FileAssetKind.IMAGE, 7, "hero", "png", false, false, 1))
        } else {
            emptyList()
        }
        override fun newFile(
            bytes: ByteArray,
            expectedAssets: List<ExpectedFileAsset>,
            externalAssets: Map<Int, ByteArray>,
            imageDecoder: NuxImageDecoder,
        ): Long {
            this.externalAssets = externalAssets
            return 1
        }
        override fun freeFile(handle: Long) = Unit
        override fun newDefaultArtboard(fileHandle: Long): Long = 2
        override fun newNamedArtboard(fileHandle: Long, name: String): Long {
            artboardName = name
            return 2
        }
        override fun freeArtboard(handle: Long) = Unit
        override fun newDefaultPlayer(artboardHandle: Long): Long = 3
        override fun newNamedStateMachinePlayer(artboardHandle: Long, name: String): Long = 3
        override fun stepPlayerFrame(playerHandle: Long, elapsedSeconds: Double): Int {
            steps += elapsedSeconds
            dataCalls += "step"
            return 0
        }
        override fun freePlayer(handle: Long) = Unit
        override fun newAndroidVulkanRenderer(pixelWidth: Int, pixelHeight: Int): Long {
            rendererSize = HostRenderSize(pixelWidth, pixelHeight)
            return 4
        }
        override fun resizeRenderer(handle: Long, pixelWidth: Int, pixelHeight: Int): Int = 0
        override fun renderToCpuFrame(
            rendererHandle: Long,
            playerHandle: Long,
            clearColor: Int,
            fitContainCenter: Boolean,
        ) = NuxieCpuFrame(4, 2, ByteArray(32) { (it + steps.size).toByte() })
        override fun resetPlayerDomain(rendererHandle: Long, playerHandle: Long): Int = 0
        override fun freeRenderer(handle: Long) = Unit

        override fun viewModelCatalog(fileHandle: Long): NativeCallResult<NativeViewModelCatalog> {
            viewModelCatalogReads++
            dataCalls += "catalog"
            return NativeCallResult(
                0,
                NativeViewModelCatalog(
                    schemas = arrayOf(
                        NativeViewModelSchema(0, "Copy", 0, 1, 0, 1, 0, false),
                    ),
                    properties = arrayOf(
                        NativeViewModelProperty(0, 0, "label", 1, -1, emptyArray()),
                    ),
                    authoredInstances = arrayOf(
                        NativeViewModelAuthoredInstance(0, 0, "Copy Defaults"),
                    ),
                ),
            )
        }

        override fun newDefaultViewModel(artboardHandle: Long): NativeCallResult<Long> {
            dataCalls += "default"
            return NativeCallResult(0, 5)
        }

        override fun bindViewModel(artboardHandle: Long, viewModelHandle: Long): Int {
            dataCalls += "bind"
            return 0
        }

        override fun mutateViewModel(handle: Long, write: NativeViewModelWrite): Int {
            dataCalls += "write"
            viewModelWrites += write
            return 0
        }

        override fun freeViewModel(handle: Long): Int {
            dataCalls += "free"
            return 0
        }
    }

    private fun seededInput(): Triple<File, File, String> {
        val input = Files.createTempDirectory("host-render-data-input-").toFile()
        val output = Files.createTempDirectory("host-render-data-output-").toFile()
        File(input, "scene.riv").writeBytes(byteArrayOf(82, 73, 86, 69))
        val descriptor =
            """
            {
              "journey":{
                "screens":[{
                  "id":"screen","defaultViewModelName":"Copy",
                  "defaultInstanceId":"copy-default"
                }],
                "viewModelValues":[{
                  "viewModelName":"Copy","instanceId":"copy-default",
                  "instanceName":"Copy Defaults","path":"label","value":"seeded copy"
                }]
              },
              "render":{
                "renderer":"rive","riv":{"key":"scene.riv"},"assets":[],
                "screens":[{"id":"screen","width":4,"height":2}]
              }
            }
            """.trimIndent()
        File(input, "release-descriptor.json").writeText(descriptor)
        return Triple(input, output, descriptor)
    }

    private fun releaseEntry(descriptor: String): String {
        val descriptorBytes = descriptor.encodeToByteArray()
        val digest = sha256(descriptorBytes)
        val envelope =
            """
            {
              "descriptorSha256":"$digest",
              "descriptorSizeBytes":${descriptorBytes.size},
              "descriptorBytesBase64":"${Base64.getEncoder().encodeToString(descriptorBytes)}"
            }
            """.trimIndent()
        return """
            {
              "descriptorSha256":"$digest",
              "envelopeBytesBase64":"${Base64.getEncoder().encodeToString(envelope.encodeToByteArray())}"
            }
            """.trimIndent()
    }

    private fun writeReleaseEntry(input: File, descriptor: String) {
        File(input, "release-entry.json").writeText(releaseEntry(descriptor))
    }

    private fun writeProfile(input: File, descriptor: String) {
        val entry = releaseEntry(descriptor)
        File(input, "profile.json").writeText(
            """
            {
              "delivery":{
                "renderBaseUrl":"https://render.example/",
                "assetBaseUrl":"https://assets.example/"
              },
              "active":[$entry],
              "pinned":[]
            }
            """.trimIndent(),
        )
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { "%02x".format(it) }

    private companion object {
        val PASS_THROUGH_DECODER = NuxImageDecoder { encoded, _, _ ->
            DecodedImage(1, 1, 4, encoded.copyOf(4))
        }
    }
}
