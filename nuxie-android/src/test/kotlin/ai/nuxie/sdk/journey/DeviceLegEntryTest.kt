package ai.nuxie.sdk.journey

import ai.nuxie.sdk.fixtures.FixtureRunner
import ai.nuxie.sdk.events.SQLiteEventStore
import ai.nuxie.sdk.events.StoredEvent
import ai.nuxie.sdk.features.FeatureAccess
import ai.nuxie.sdk.features.FeatureType
import java.io.File
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
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
    fun `feature expressions use the complete cached access snapshot`() = runBlocking {
        val facts = JsonObject(
            mapOf(
                "properties" to JsonObject(emptyMap()),
                "memberships" to JsonObject(emptyMap()),
            ),
        )
        val references = JsonObject(
            mapOf(
                "propertyKeys" to JsonArray(emptyList()),
                "segmentIds" to JsonArray(emptyList()),
            ),
        )
        val access = FeatureAccess(
            allowed = true,
            unlimited = true,
            balance = 7.0,
            type = FeatureType.CREDIT_SYSTEM,
        )
        val cases = listOf(
            Triple("has", null, true),
            Triple("not_has", null, false),
            Triple("is_unlimited", null, true),
            Triple("credits_eq", 7.0, true),
            Triple("credits_gt", 8.0, false),
            Triple("credits_gte", 7.0, true),
            Triple("credits_lt", 8.0, true),
            Triple("credits_lte", 6.0, false),
        )

        for ((operation, value, expected) in cases) {
            val expression = linkedMapOf<String, kotlinx.serialization.json.JsonElement>(
                "type" to JsonPrimitive("Feature"),
                "op" to JsonPrimitive(operation),
                "id" to JsonPrimitive("credits"),
            ).apply {
                value?.let { put("value", JsonObject(mapOf("type" to JsonPrimitive("Number"), "value" to JsonPrimitive(it)))) }
            }
            val entry = JsonObject(
                mapOf(
                    "type" to JsonPrimitive("app_foregrounded"),
                    "condition" to JsonObject(
                        mapOf(
                            "ir_version" to JsonPrimitive(1),
                            "expr" to JsonObject(expression),
                        ),
                    ),
                ),
            )

            assertEquals(
                operation,
                expected,
                DeviceLegEntryEvaluator.matches(
                    entry = entry,
                    facts = facts,
                    references = references,
                    foreground = true,
                    event = null,
                    featureAccess = { featureId -> access.takeIf { featureId == "credits" } },
                ),
            )
        }
    }

    @Test
    fun `uncached feature access is denied rather than unknown`() = runBlocking {
        val facts = JsonObject(
            mapOf(
                "properties" to JsonObject(emptyMap()),
                "memberships" to JsonObject(emptyMap()),
            ),
        )
        val references = JsonObject(
            mapOf(
                "propertyKeys" to JsonArray(emptyList()),
                "segmentIds" to JsonArray(emptyList()),
            ),
        )

        for ((operation, expected) in listOf("has" to false, "not_has" to true)) {
            val entry = JsonObject(
                mapOf(
                    "type" to JsonPrimitive("app_foregrounded"),
                    "condition" to JsonObject(
                        mapOf(
                            "ir_version" to JsonPrimitive(1),
                            "expr" to JsonObject(
                                mapOf(
                                    "type" to JsonPrimitive("Feature"),
                                    "op" to JsonPrimitive(operation),
                                    "id" to JsonPrimitive("uncached"),
                                ),
                            ),
                        ),
                    ),
                ),
            )

            assertEquals(
                operation,
                expected,
                DeviceLegEntryEvaluator.matches(
                    entry = entry,
                    facts = facts,
                    references = references,
                    foreground = true,
                    event = null,
                    featureAccess = { null },
                ),
            )
        }
    }

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
