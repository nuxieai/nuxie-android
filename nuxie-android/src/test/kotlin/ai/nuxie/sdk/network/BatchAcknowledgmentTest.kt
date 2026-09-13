package ai.nuxie.sdk.network

import ai.nuxie.sdk.fixtures.FixtureRunner
import java.io.IOException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class BatchAcknowledgmentTest {
    @Test
    fun successfulHttpVectorsUseTheSharedPerItemAcceptanceContract() {
        var executed = 0
        FixtureRunner.run("events/delivery-disposition.json", "events/delivery-disposition") { vector ->
            val response = vector.body.getValue("response").jsonObject
            if (response.getValue("status_code").jsonPrimitive.int == 200) {
                executed++
                val batch = vector.body.getValue("batch").jsonArray
                val wire = JsonObject(mapOf(
                    "status" to JsonPrimitive("partial"),
                    "processed" to response.getValue("processed"),
                    "failed" to response.getValue("failed"),
                    "total" to response.getValue("total"),
                    "errors" to JsonArray(response.getValue("error_indexes").jsonArray.map {
                        JsonObject(mapOf("index" to it, "event" to JsonPrimitive("event"), "error" to JsonPrimitive("retry")))
                    }),
                )).toString().encodeToByteArray()
                val expected = vector.body.getValue("expect").jsonObject
                if (expected.getValue("disposition").jsonPrimitive.content == "retry") {
                    assertThrows(IOException::class.java) { BatchAcknowledgment.decode(wire, batch.size) }
                } else {
                    assertEquals(expected.getValue("acknowledged").jsonArray.toList(),
                        BatchAcknowledgment.decode(wire, batch.size).acceptedIndexes.map { batch[it] })
                }
            }
        }
        assertEquals(2, executed)
    }

    @Test
    fun rejectsAmbiguousMalformedAndDuplicateFailureMetadata() {
        val invalid = listOf(
            "", "{}", "[]",
            """{"status":"ok","total":2,"processed":1,"failed":1}""",
            """{"status":"ok","total":2,"processed":2,"failed":0,"errors":{}}""",
            """{"status":"ok","total":2,"processed":-1,"failed":3}""",
            """{"status":"ok","total":2,"processed":"2","failed":0}""",
            """{"status":"ok","total":2,"processed":2.5,"failed":0}""",
            """{"status":"ok","total":2,"processed":0,"failed":2,"errors":[{"index":0,"event":"a","error":"x"},{"index":0,"event":"a","error":"x"}]}""",
            """{"status":"ok","total":2,"processed":1,"failed":1,"errors":[{"index":2,"event":"a","error":"x"}]}""",
        )
        invalid.forEach { body ->
            assertThrows(body, IOException::class.java) { BatchAcknowledgment.decode(body.encodeToByteArray(), 2) }
        }
    }

    @Test
    fun completeAcceptanceAllowsMissingOrNullErrors() {
        listOf("", ",\"errors\":null", ",\"errors\":[]").forEach { errors ->
            val body = """{"status":"success","total":2,"processed":2,"failed":0$errors}"""
            assertEquals(listOf(0, 1), BatchAcknowledgment.decode(body.encodeToByteArray(), 2).acceptedIndexes)
        }
    }
}
