package ai.nuxie.sdk.logging

import ai.nuxie.sdk.LogLevel
import android.util.Log
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** One rendering boundary: never pass a raw Throwable to the platform sink. */
internal class NuxieLogger(
    private val output: (Int, String, String) -> Unit = { priority, tag, message ->
        Log.println(priority, tag, message)
        Unit
    },
    correlationKey: ByteArray = ByteArray(32).also { SecureRandom().nextBytes(it) },
) {
    data class Policy(val level: LogLevel = LogLevel.WARN, val redactSensitiveData: Boolean = true)

    class Field private constructor(val name: String, internal val value: Any?, internal val sensitive: Boolean) {
        companion object {
            fun sensitive(name: String, value: Any?) = Field(name, value, true)
            /** Only call-site selected diagnostic counts/statuses belong here. */
            fun publicValue(name: String, value: String) = Field(name, value, false)
            fun status(name: String, value: Number) = Field(name, value, false)
        }
    }

    private val key = SecretKeySpec(correlationKey.copyOf(), "HmacSHA256")
    @Volatile private var policy = Policy()

    fun configure(policy: Policy) { this.policy = policy }

    fun log(level: LogLevel, tag: String, message: String, error: Throwable? = null, vararg fields: Field) {
        val snapshot = policy
        if (level == LogLevel.NONE || snapshot.level == LogLevel.NONE || level.ordinal > snapshot.level.ordinal) return
        val rendered = buildString {
            append(message)
            for (field in fields) {
                append(' ').append(field.name).append('=')
                val value = describe(field.value)
                append(if (snapshot.redactSensitiveData && field.sensitive && field.value != null) summary(value) else value)
            }
            if (error != null) {
                append(" error=").append(error.javaClass.name).append(' ')
                val details = runCatching { error.stackTraceToString() }.getOrDefault("<unavailable>")
                append(if (snapshot.redactSensitiveData) summary(details) else details)
            }
        }
        // Logging must not replace the operation's original result or failure.
        runCatching { output(priority(level), tag, rendered) }
    }

    private fun describe(value: Any?): String = runCatching { value?.toString() ?: "null" }.getOrDefault("<unavailable>")

    private fun summary(value: String): String {
        val mac = Mac.getInstance("HmacSHA256").apply { init(key) }
        val digest = mac.doFinal(value.toByteArray(Charsets.UTF_8)).take(8)
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
        return "<redacted hmac-sha256:$digest>"
    }

    private fun priority(level: LogLevel): Int = when (level) {
        LogLevel.ERROR -> Log.ERROR
        LogLevel.WARN -> Log.WARN
        LogLevel.INFO -> Log.INFO
        LogLevel.DEBUG -> Log.DEBUG
        LogLevel.NONE -> error("NONE has no output priority")
    }
}
