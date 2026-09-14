package ai.nuxie.sdk

import ai.nuxie.sdk.events.SQLiteEventStore
import ai.nuxie.sdk.events.StoredEvent
import ai.nuxie.sdk.journey.JourneyEntryEvaluator
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.Locale
import java.util.TimeZone
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

class HistoryTargetingDeviceTest {
    @Test
    fun sharedHistoryQueriesKeepUTCMeaningAcrossDeviceTimeZones() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val fixture = instrumentation.context.assets.open("journeys/planes/history-targeting.json")
            .bufferedReader().use { Json.parseToJsonElement(it.readText()).jsonObject }
        val originalZone = TimeZone.getDefault()
        val originalLocale = Locale.getDefault()
        try {
            Locale.setDefault(Locale("ar", "SA"))
            for (zone in listOf("UTC", "Asia/Tokyo", "America/Los_Angeles")) {
                TimeZone.setDefault(TimeZone.getTimeZone(zone))
                for (element in fixture.getValue("cases").jsonArray) {
                    val vector = element.jsonObject
                    val history = vector.getValue("history").jsonObject
                    val database = File(context.cacheDir, "history-${System.nanoTime()}.db")
                    val store = SQLiteEventStore(context, databaseFile = database,
                        nowMillis = { history.getValue("coverageStartMillis").jsonPrimitive.long })
                    try {
                        for (row in history.getValue("events").jsonArray.map { it.jsonObject }) {
                            store.insertPending(StoredEvent(id = row.getValue("id").jsonPrimitive.content,
                                name = row.getValue("name").jsonPrimitive.content, distinctId = "person",
                                timestampMillis = row.getValue("timestampMillis").jsonPrimitive.long,
                                properties = row.getValue("properties").jsonObject))
                        }
                        val matched = JourneyEntryEvaluator.matches(
                            entry = vector.getValue("condition").jsonObject,
                            facts = vector.getValue("facts").jsonObject,
                            references = vector.getValue("references").jsonObject,
                            foreground = vector.getValue("foreground").jsonPrimitive.boolean,
                            event = vector["event"] as? JsonObject,
                            nowMillis = vector.getValue("nowMillis").jsonPrimitive.long,
                            events = store, distinctId = "person",
                        )
                        assertEquals("$zone: ${vector.getValue("name").jsonPrimitive.content}",
                            vector.getValue("expected").jsonPrimitive.boolean, matched)
                    } finally {
                        store.close()
                        context.deleteDatabase(database.absolutePath)
                    }
                }
            }
        } finally {
            Locale.setDefault(originalLocale)
            TimeZone.setDefault(originalZone)
        }
    }
}
