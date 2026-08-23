package ai.nuxie.sdk.events

import java.security.SecureRandom
import java.util.UUID

/** Generates lexicographically time-ordered UUIDv7 values. */
internal class TimeBasedEpochGenerator(
    private val currentTimeMillis: () -> Long = System::currentTimeMillis,
    private val fillRandomBytes: (ByteArray) -> Unit = SecureRandom()::nextBytes,
) {
    private var lastTimestamp = -1L
    private val lastEntropy = ByteArray(10)

    @Synchronized
    fun next(): String {
        val timestamp = currentTimeMillis()
        if (timestamp == lastTimestamp) {
            incrementEntropy()
        } else {
            lastTimestamp = timestamp
            fillRandomBytes(lastEntropy)
        }

        val bytes = ByteArray(16)
        for (index in 0 until TIMESTAMP_BYTES) {
            bytes[index] = (timestamp ushr (40 - (index * 8))).toByte()
        }
        lastEntropy.copyInto(bytes, destinationOffset = TIMESTAMP_BYTES)

        bytes[6] = ((bytes[6].toInt() and 0x0f) or 0x70).toByte()
        bytes[8] = ((bytes[8].toInt() and 0x3f) or 0x80).toByte()

        var mostSignificantBits = 0L
        var leastSignificantBits = 0L
        for (index in 0 until 8) {
            mostSignificantBits = (mostSignificantBits shl 8) or (bytes[index].toLong() and 0xff)
            leastSignificantBits = (leastSignificantBits shl 8) or (bytes[index + 8].toLong() and 0xff)
        }
        return UUID(mostSignificantBits, leastSignificantBits).toString()
    }

    private fun incrementEntropy() {
        for (index in lastEntropy.lastIndex downTo 0) {
            lastEntropy[index] = (lastEntropy[index].toInt() + 1).toByte()
            if (lastEntropy[index].toInt() != 0) return
        }
    }

    internal companion object {
        val shared = TimeBasedEpochGenerator()

        private const val TIMESTAMP_BYTES = 6
    }
}
