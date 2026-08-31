package ai.nuxie.sdk.journey

import ai.nuxie.sdk.fixtures.FixtureRunner
import ai.nuxie.sdk.events.SQLiteEventStore
import ai.nuxie.sdk.events.StoredEvent
import java.io.File
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [23])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class DeviceLegEntryTest {
    @Test
    fun `entry evaluation matches the pinned iOS vectors`() = runBlocking {
        val fixture = FixtureRunner.fixturesRoot().resolve("journeys/planes/entry-evaluation.json")
        val suite = Json.parseToJsonElement(fixture.readText()).jsonObject
        assertEquals("device-leg-entry-v1", suite.getValue("suite").jsonPrimitive.content)
        for (element in suite.getValue("cases").jsonArray) {
            val vector = element.jsonObject
            val matched = DeviceLegEntryEvaluator.matches(
                entry = vector.getValue("condition").jsonObject,
                facts = vector.getValue("facts").jsonObject,
                references = vector.getValue("references").jsonObject,
                foreground = vector.getValue("foreground").jsonPrimitive.boolean,
                event = vector["event"] as? JsonObject,
            )
            assertEquals(vector.getValue("name").jsonPrimitive.content,
                vector.getValue("expected").jsonPrimitive.boolean, matched)
        }
    }

    @Test fun `occurrence evaluation matches the pinned iOS vectors`() = runBlocking {
        val fixture = FixtureRunner.fixturesRoot().resolve("journeys/planes/occurrence-evaluation.json")
        val suite = Json.parseToJsonElement(fixture.readText()).jsonObject
        assertEquals("device-leg-occurrence-v1", suite.getValue("suite").jsonPrimitive.content)
        val context = RuntimeEnvironment.getApplication()
        val directory = File(context.filesDir, "nuxie")
        for (element in suite.getValue("cases").jsonArray) {
            val vector = element.jsonObject
            val history = vector.getValue("history").jsonObject
            directory.deleteRecursively()
            val store = SQLiteEventStore(context, nowMillis = { history.getValue("coverageStartMillis").jsonPrimitive.long })
            try {
                for (event in history.getValue("events").jsonArray.map { it.jsonObject }) {
                    store.insertPending(StoredEvent(id = event.getValue("id").jsonPrimitive.content,
                        name = event.getValue("name").jsonPrimitive.content,
                        timestampMillis = event.getValue("timestampMillis").jsonPrimitive.long,
                        distinctId = "person", properties = event.getValue("properties").jsonObject))
                }
                val matched = DeviceLegEntryEvaluator.matches(
                    entry = vector.getValue("condition").jsonObject,
                    facts = vector.getValue("facts").jsonObject,
                    references = vector.getValue("references").jsonObject,
                    foreground = vector.getValue("foreground").jsonPrimitive.boolean,
                    event = vector["event"] as? JsonObject,
                    nowMillis = vector.getValue("nowMillis").jsonPrimitive.long,
                    events = store, distinctId = "person",
                )
                assertEquals(vector.getValue("name").jsonPrimitive.content,
                    vector.getValue("expected").jsonPrimitive.boolean, matched)
            } finally {
                store.close()
                directory.deleteRecursively()
            }
        }
    }
}
