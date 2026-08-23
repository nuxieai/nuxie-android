package ai.nuxie.sdk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NuxieEventTest {
    @Test
    fun configurationStoresBeforeSendWithoutInvokingIt() {
        val configuration = NuxieConfiguration("pk_test")
        var calls = 0
        val hook: (NuxieEvent) -> NuxieEvent? = { event ->
            calls += 1
            event
        }

        configuration.beforeSend = hook

        assertEquals(hook, configuration.beforeSend)
        assertEquals(0, calls)
    }

    @Test
    fun defaultIdsAreMonotonicUuidVersionSevenValues() {
        val ids = List(128) {
            NuxieEvent(name = "button_clicked", distinctId = "user-1").id
        }

        assertEquals(ids.sorted(), ids)
        assertEquals(ids.size, ids.toSet().size)
        ids.forEach { id ->
            assertEquals('7', id[14])
            assertTrue(id[19].lowercaseChar() in "89ab")
        }
    }

    @Test
    fun eventTakesAWriteOnceSnapshotOfItsPayload() {
        val nested = mutableListOf<Any?>("first")
        val source = mutableMapOf<String, Any?>("nested" to nested)
        val timestampMillis = 1_784_462_400_000L

        val event = NuxieEvent(
            id = "0198f0a4-7e11-7000-8000-000000000001",
            name = "button_clicked",
            distinctId = "user-1",
            properties = source,
            timestampMillis = timestampMillis,
        )
        source["later"] = true
        nested += "second"

        assertEquals(mapOf("nested" to listOf("first")), event.properties)
        assertEquals(timestampMillis, event.timestampMillis)
    }
}
