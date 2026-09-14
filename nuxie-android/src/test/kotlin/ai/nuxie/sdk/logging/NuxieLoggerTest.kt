package ai.nuxie.sdk.logging

import ai.nuxie.sdk.LogLevel
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

class NuxieLoggerTest {
    @Test fun `every level gates all severities including NONE`() {
        val enabled = mapOf(
            LogLevel.NONE to emptyList(), LogLevel.ERROR to listOf(LogLevel.ERROR),
            LogLevel.WARN to listOf(LogLevel.ERROR, LogLevel.WARN),
            LogLevel.INFO to listOf(LogLevel.ERROR, LogLevel.WARN, LogLevel.INFO),
            LogLevel.DEBUG to listOf(LogLevel.ERROR, LogLevel.WARN, LogLevel.INFO, LogLevel.DEBUG),
        )
        for ((threshold, expected) in enabled) {
            val messages = mutableListOf<String>()
            val logger = NuxieLogger(output = { _, _, message -> messages += message })
            logger.configure(NuxieLogger.Policy(threshold))
            for (level in LogLevel.entries) logger.log(level, "Nuxie", level.name)
            assertEquals(threshold.name, expected.map { it.name }, messages)
        }
    }

    @Test fun `sensitive fields use the RFC 4231 HMAC vector and retain public status`() {
        val messages = mutableListOf<String>()
        val logger = NuxieLogger({ _, _, message -> messages += message }, ByteArray(20) { 0x0b })
        repeat(2) { logger.log(LogLevel.WARN, "Nuxie", "Request rejected", null,
            NuxieLogger.Field.sensitive("customer", "Hi There"), NuxieLogger.Field.status("status", 503)) }
        assertEquals(listOf("Request rejected customer=<redacted hmac-sha256:b0344c61d8db3853> status=503",
            "Request rejected customer=<redacted hmac-sha256:b0344c61d8db3853> status=503"), messages)
    }

    @Test fun `exceptions never disclose payload paths causes or suppressed details by default`() {
        val messages = mutableListOf<String>()
        val logger = NuxieLogger(output = { _, _, message -> messages += message })
        val error = IOException("response=customer-secret", IllegalStateException("/private/storage-secret"))
        error.addSuppressed(IllegalArgumentException("token-secret"))
        logger.log(LogLevel.WARN, "Nuxie", "Request failed", error)
        assertTrue(messages.single().contains("java.io.IOException"))
        for (secret in listOf("customer-secret", "storage-secret", "token-secret")) assertFalse(messages.single().contains(secret))
        logger.configure(NuxieLogger.Policy(redactSensitiveData = false))
        logger.log(LogLevel.WARN, "Nuxie", "Request failed", error)
        for (secret in listOf("customer-secret", "storage-secret", "token-secret")) assertTrue(messages.last().contains(secret))
    }

    @Test fun `disabled output never evaluates sensitive values`() {
        val logger = NuxieLogger(output = { _, _, _ -> fail("Disabled sink called") })
        logger.configure(NuxieLogger.Policy(LogLevel.NONE))
        logger.log(LogLevel.ERROR, "Nuxie", "Disabled", null, NuxieLogger.Field.sensitive("payload",
            object { override fun toString(): String = throw AssertionError("Value evaluated") }))
    }

    @Test fun `configuration changes preserve correlation and new process keys separate it`() {
        val messages = mutableListOf<String>()
        val logger = NuxieLogger(output = { _, _, message -> messages += message })
        val field = NuxieLogger.Field.sensitive("value", "same-customer")
        logger.log(LogLevel.WARN, "Nuxie", "Event", null, field)
        logger.configure(NuxieLogger.Policy(LogLevel.ERROR, false))
        logger.configure(NuxieLogger.Policy())
        logger.log(LogLevel.WARN, "Nuxie", "Event", null, field)
        NuxieLogger(output = { _, _, message -> messages += message }).log(LogLevel.WARN, "Nuxie", "Event", null, field)
        assertEquals(messages[0], messages[1])
        assertNotEquals(messages[0], messages[2])
    }

    @Test fun `concurrent rendering remains stable and output failure cannot escape`() {
        val messages = CopyOnWriteArrayList<String>()
        val logger = NuxieLogger(output = { _, _, message -> messages += message })
        val executor = Executors.newFixedThreadPool(4)
        try {
            val futures = (1..40).map { executor.submit {
                logger.log(LogLevel.WARN, "Nuxie", "Event", null, NuxieLogger.Field.sensitive("value", "same"))
            } }
            futures.forEach { it.get(5, TimeUnit.SECONDS) }
        } finally { executor.shutdownNow() }
        assertEquals(40, messages.size)
        assertEquals(1, messages.toSet().size)
        NuxieLogger(output = { _, _, _ -> throw IllegalStateException("sink failed") }).log(LogLevel.ERROR, "Nuxie", "Failure")
    }
}
