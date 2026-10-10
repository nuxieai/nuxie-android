package ai.nuxie.sdk.presentation

import ai.nuxie.sdk.runtime.NuxieFocusInput
import ai.nuxie.sdk.runtime.NuxieFocusLimits

internal class ExperienceFocusInputQueue {
    private val inputs = ArrayDeque<NuxieFocusInput>()
    val isEmpty: Boolean get() = inputs.isEmpty()

    fun add(input: NuxieFocusInput) {
        if (input !is NuxieFocusInput.Text || input.value.isEmpty()) {
            inputs.addLast(input)
            return
        }
        val text = input.value
        val limit = NuxieFocusLimits.TEXT_BYTES_PER_INPUT
        var start = 0
        var end = 0
        var chunkBytes = 0
        fun flush() {
            if (chunkBytes == 0) return
            inputs.addLast(NuxieFocusInput.Text(text.substring(start, end)))
            chunkBytes = 0
            start = end
        }
        ExperienceGrapheme.forEachCluster(text) { lower, upper, bytes ->
            if (bytes > limit) {
                flush()
                val encoded = text.substring(lower, upper).encodeToByteArray()
                var offset = 0
                while (offset < encoded.size) {
                    var next = minOf(offset + limit, encoded.size)
                    while (next < encoded.size && (encoded[next].toInt() and 0xc0) == 0x80) next--
                    inputs.addLast(NuxieFocusInput.Text(encoded.decodeToString(offset, next)))
                    offset = next
                }
                start = upper
                end = upper
            } else {
                if (chunkBytes + bytes > limit) flush()
                if (chunkBytes == 0) start = lower
                end = upper
                chunkBytes += bytes
            }
        }
        flush()
    }

    fun takeBatch(): List<NuxieFocusInput> {
        val batch = mutableListOf<NuxieFocusInput>()
        var bytes = 0
        while (inputs.isNotEmpty() && batch.size < NuxieFocusLimits.INPUTS_PER_STEP) {
            val input = inputs.first()
            val count = if (input is NuxieFocusInput.Text) input.value.encodeToByteArray().size else 0
            if (bytes + count > NuxieFocusLimits.TEXT_BYTES_PER_STEP) break
            batch.add(inputs.removeFirst())
            bytes += count
        }
        return batch
    }

    fun clear() = inputs.clear()
}
