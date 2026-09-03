package ai.nuxie.sdk.hostrender

import ai.nuxie.sdk.experiences.ExperienceAssetImportBuilder
import ai.nuxie.sdk.experiences.ExperienceViewModelBinding
import ai.nuxie.sdk.runtime.DecodedImage
import ai.nuxie.sdk.runtime.NuxImageDecoder
import ai.nuxie.sdk.runtime.NuxieRuntime
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

internal data class HostRenderFrame(
    val index: Int,
    val sha256: String,
    val width: Int,
    val height: Int,
)

internal data class HostRenderResult(val frames: List<HostRenderFrame>)

/**
 * Host-only adapter over the same configured Experience import and owned runtime
 * wrappers used by Android presentation. One [run] owns the complete native graph.
 */
internal class HostRenderHarness(
    private val runtime: NuxieRuntime = NuxieRuntime.shared,
    private val imageDecoder: NuxImageDecoder = HostImageDecoder,
    private val wireExternalAssets: (Map<Int, ByteArray>) -> Map<Int, ByteArray> = { it },
) {
    fun run(options: HostRenderOptions): HostRenderResult {
        val input = options.inputDirectory.canonicalFile
        require(input.isDirectory) { "--input is not a directory: $input" }
        val descriptorFile = File(input, DESCRIPTOR_FILE)
        require(descriptorFile.isFile) { "Input is missing $DESCRIPTOR_FILE" }
        val descriptor = Json.parseToJsonElement(descriptorFile.readText()).jsonObject
        val render = descriptor["render"] as? JsonObject
            ?: error("Journey release render is missing")
        require(render.string("renderer") == "rive") {
            "Journey release renderer must be rive"
        }
        val rivFile = resolveRiv(input, render)
        val size = options.size ?: render.defaultSize()
        val artifacts = collectArtifacts(input)
        val clearColor = descriptor.clearColor()

        check(runtime.isAvailable) {
            "Nuxie host runtime is unavailable; set NUXIE_HOST_CAPI_LIB to the host nux_capi library"
        }
        val rivBytes = rivFile.readBytes()
        val inspected = checkNotNull(runtime.inspectFileAssets(rivBytes)) {
            "Runtime could not inspect the Experience asset catalog"
        }
        val prepared = ExperienceAssetImportBuilder.build(descriptor, artifacts, inspected)
        var file: ai.nuxie.sdk.runtime.NuxieRuntimeFile? = null
        var artboard: ai.nuxie.sdk.runtime.NuxieRuntimeArtboard? = null
        var player: ai.nuxie.sdk.runtime.NuxieRuntimePlayer? = null
        val renderer = checkNotNull(runtime.newAndroidVulkanRenderer(size.width, size.height)) {
            "Headless Android Vulkan renderer creation failed"
        }
        try {
            file = checkNotNull(
                runtime.importFile(
                    renderer = renderer,
                    bytes = rivBytes,
                    expectedAssets = prepared.expectedAssets,
                    externalAssets = wireExternalAssets(prepared.externalAssets),
                    imageDecoder = imageDecoder,
                ),
            ) { "Runtime rejected the configured Experience import" }
            val artboardName = render.defaultArtboardName()
            artboard = checkNotNull(file.newArtboard(artboardName)) {
                "Experience artboard is unavailable"
            }
            ExperienceViewModelBinding.defaultSchemaName(descriptor, artboardName)?.let {
                artboard.bindDefaultViewModel(it)
            }
            player = checkNotNull(artboard.newPlayer()) {
                "Experience player is unavailable"
            }

            val output = options.outputDirectory.canonicalFile
            require(output.mkdirs() || output.isDirectory) {
                "Could not create --output directory: $output"
            }
            val frames = buildList(options.frameCount) {
                repeat(options.frameCount) { index ->
                    val stepStatus = player.step(options.stepMillis / 1_000.0)
                    check(stepStatus == NUX_STATUS_OK) {
                        "Experience player step failed with status $stepStatus"
                    }
                    val frame = renderer.renderToCpuFrame(player, clearColor, true)
                    check(frame.width == size.width && frame.height == size.height) {
                        "Runtime returned ${frame.width}x${frame.height}; expected ${size.width}x${size.height}"
                    }
                    val frameFile = File(output, "frame-$index.rgba")
                    frameFile.writeBytes(frame.rgba)
                    add(
                        HostRenderFrame(
                            index = index,
                            sha256 = sha256(frame.rgba),
                            width = frame.width,
                            height = frame.height,
                        ),
                    )
                }
            }
            File(output, MANIFEST_FILE).writeText(manifest(frames, runtime.info()))
            return HostRenderResult(frames)
        } finally {
            try {
                player?.close()
            } finally {
                try {
                    artboard?.close()
                } finally {
                    try {
                        file?.close()
                    } finally {
                        renderer.close()
                    }
                }
            }
        }
    }

    private fun resolveRiv(input: File, render: JsonObject): File {
        val key = (render["riv"] as? JsonObject)?.string("key")
            ?: error("Journey release riv key is missing")
        val declared = File(input, key)
        if (declared.isFile) return declared
        val topLevel = File(input, File(key).name)
        if (topLevel.isFile) return topLevel
        error("Input must contain the declared .riv file: $key")
    }

    private fun collectArtifacts(input: File): Map<String, File> {
        val assets = File(input, "assets")
        if (!assets.exists()) return emptyMap()
        require(assets.isDirectory) { "Input assets path is not a directory" }
        return assets.walkTopDown()
            .filter(File::isFile)
            .associateBy { file -> file.relativeTo(input).invariantSeparatorsPath }
    }

    private fun manifest(frames: List<HostRenderFrame>, runtimeInfo: String): String {
        val json = JsonObject(
            mapOf(
                "frames" to JsonArray(frames.map { frame ->
                    JsonObject(
                        mapOf(
                            "index" to JsonPrimitive(frame.index),
                            "sha256" to JsonPrimitive(frame.sha256),
                            "width" to JsonPrimitive(frame.width),
                            "height" to JsonPrimitive(frame.height),
                        ),
                    )
                }),
                "runtime" to JsonObject(mapOf("info" to JsonPrimitive(runtimeInfo))),
            ),
        )
        return PRETTY_JSON.encodeToString(JsonElement.serializer(), json) + "\n"
    }

    private fun JsonObject.defaultSize(): HostRenderSize {
        // The selected first screen's published width/height are its authored
        // artboard extent in the release contract. nux_capi has no separate
        // bounds accessor for an opaque artboard handle.
        val first = (this["screens"] as? JsonArray)?.firstOrNull() as? JsonObject
            ?: error("Journey release has no screen size; pass --size WxH")
        val width = first["width"]?.jsonPrimitive?.intOrNull
        val height = first["height"]?.jsonPrimitive?.intOrNull
        require(width != null && height != null) {
            "Journey release has no valid screen size; pass --size WxH"
        }
        return HostRenderSize(width, height)
    }

    private fun JsonObject.defaultArtboardName(): String? {
        val first = (this["screens"] as? JsonArray)?.firstOrNull() as? JsonObject
        return first?.string("artboardName")
    }

    private fun JsonObject.clearColor(): Int {
        val value = (this["presentation"] as? JsonObject)?.string("backgroundColor")
            ?: return OPAQUE_BLACK
        val hex = value.removePrefix("#")
        return runCatching {
            when (hex.length) {
                6 -> (0xFF000000L or hex.toLong(16)).toInt()
                8 -> {
                    val rgba = hex.toLong(16)
                    (((rgba and 0xFF) shl 24) or (rgba ushr 8)).toInt()
                }
                else -> OPAQUE_BLACK
            }
        }.getOrDefault(OPAQUE_BLACK)
    }

    private fun JsonObject.string(key: String): String? =
        (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { "%02x".format(it) }

    private companion object {
        const val DESCRIPTOR_FILE = "release-descriptor.json"
        const val MANIFEST_FILE = "manifest.json"
        const val NUX_STATUS_OK = 0
        const val OPAQUE_BLACK = 0xFF000000.toInt()
        val PRETTY_JSON = Json { prettyPrint = true }
    }
}

/** JDK image adapter matching AndroidImageDecoder's canonical RGBA contract. */
private object HostImageDecoder : NuxImageDecoder {
    override fun decode(
        encoded: ByteArray,
        maximumDimension: Int,
        maximumDecodedBytes: Long,
    ): DecodedImage? {
        if (maximumDimension <= 0 || maximumDecodedBytes < 0) return null
        val imageIo = Class.forName("javax.imageio.ImageIO")
        val image = imageIo.getMethod("read", InputStream::class.java)
            .invoke(null, ByteArrayInputStream(encoded)) ?: return null
        val imageClass = image.javaClass
        val width = imageClass.getMethod("getWidth").invoke(image) as Int
        val height = imageClass.getMethod("getHeight").invoke(image) as Int
        if (width <= 0 || height <= 0 ||
            width > maximumDimension || height > maximumDimension
        ) {
            return null
        }
        val rowBytes = width.toLong() * 4L
        val byteCount = rowBytes * height.toLong()
        if (rowBytes > Int.MAX_VALUE || byteCount > Int.MAX_VALUE ||
            byteCount > maximumDecodedBytes
        ) {
            return null
        }
        val argb = IntArray(width * height)
        imageClass.getMethod(
            "getRGB",
            Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType,
            IntArray::class.java,
            Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType,
        ).invoke(image, 0, 0, width, height, argb, 0, width)
        val rgba = ByteArray(byteCount.toInt())
        argb.forEachIndexed { index, color ->
            val alpha = color ushr 24
            val destination = index * 4
            rgba[destination] = premultiply(color ushr 16, alpha).toByte()
            rgba[destination + 1] = premultiply(color ushr 8, alpha).toByte()
            rgba[destination + 2] = premultiply(color, alpha).toByte()
            rgba[destination + 3] = alpha.toByte()
        }
        return DecodedImage(width, height, rowBytes.toInt(), rgba)
    }

    private fun premultiply(channel: Int, alpha: Int): Int =
        ((channel and 0xff) * alpha + 127) / 255
}

fun main(args: Array<String>) {
    val options = HostRenderOptions.parse(args)
    val result = HostRenderHarness().run(options)
    println("Rendered ${result.frames.size} frame(s) to ${options.outputDirectory.canonicalPath}")
}
