package ai.nuxie.sdk.hostrender

import java.io.File

internal data class HostRenderSize(val width: Int, val height: Int) {
    init {
        require(width > 0 && height > 0) { "--size dimensions must be positive" }
    }
}

internal data class HostRenderOptions(
    val inputDirectory: File,
    val outputDirectory: File,
    val frameCount: Int = 1,
    val size: HostRenderSize? = null,
    val stepMillis: Long = 16,
) {
    companion object {
        fun parse(args: Array<String>): HostRenderOptions {
            val values = linkedMapOf<String, String>()
            var index = 0
            while (index < args.size) {
                val option = args[index]
                require(option in OPTIONS) { "Unknown option: $option" }
                require(index + 1 < args.size) { "Missing value for $option" }
                require(option !in values) { "Duplicate option: $option" }
                values[option] = args[index + 1]
                index += 2
            }

            val input = values["--input"]?.let(::File)
                ?: throw IllegalArgumentException("Missing required --input <dir>")
            val output = values["--output"]?.let(::File)
                ?: throw IllegalArgumentException("Missing required --output <dir>")
            val frames = values["--frames"]?.let { value ->
                value.toIntOrNull()
                    ?: throw IllegalArgumentException("--frames must be a positive integer")
            } ?: 1
            require(frames > 0) { "--frames must be a positive integer" }
            val stepMillis = values["--step-ms"]?.let { value ->
                value.toLongOrNull()
                    ?: throw IllegalArgumentException("--step-ms must be a non-negative integer")
            } ?: 16L
            require(stepMillis >= 0) { "--step-ms must be a non-negative integer" }
            val size = values["--size"]?.let(::parseSize)
            return HostRenderOptions(input, output, frames, size, stepMillis)
        }

        private fun parseSize(value: String): HostRenderSize {
            val match = SIZE.matchEntire(value)
                ?: throw IllegalArgumentException("--size must be formatted as WxH")
            return HostRenderSize(
                match.groupValues[1].toInt(),
                match.groupValues[2].toInt(),
            )
        }

        private val OPTIONS = setOf("--input", "--output", "--frames", "--size", "--step-ms")
        private val SIZE = Regex("([1-9][0-9]*)[xX]([1-9][0-9]*)")
    }
}
