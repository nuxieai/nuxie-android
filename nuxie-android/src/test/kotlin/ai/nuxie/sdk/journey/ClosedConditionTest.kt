package ai.nuxie.sdk.journey

import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ClosedConditionTest {
    @Test
    fun allReviewedSharedVectors() {
        val root = File(System.getProperty("user.dir"))
        val path = listOf(File(root,"fixtures/conditions/condition-vectors.json"),File(root.parentFile,"fixtures/conditions/condition-vectors.json")).first { it.isFile }
        val vectors = Json.parseToJsonElement(path.readText()).jsonArray
        assertEquals(244,vectors.size)
        val formatter = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply { timeZone=TimeZone.getTimeZone("UTC"); isLenient=false }
        for(element in vectors) {
            val v=element.jsonObject
            val tree=ClosedCondition(v.getValue("tree").jsonObject)
            val now=checkNotNull(formatter.parse(v.getValue("now").jsonPrimitive.content)).time
            val zone=(v["zone"] as? JsonPrimitive)?.takeIf { it != JsonNull }?.content
            val facts=ClosedConditionFacts(v["facts"] as? JsonObject ?: JsonObject(emptyMap()),now,zone)
            if(v.containsKey("expect")) assertEquals(v.getValue("name").jsonPrimitive.content,v["expect"],tree.evaluate(facts).jsonValue)
            else assertEquals(v.getValue("name").jsonPrimitive.content,v["nextClockChange"],tree.nextClockChange(now,zone)?.let { JsonPrimitive(formatter.format(it)) } ?: JsonNull)
        }
    }
    @Test
    fun closedSchemaRejectsRetiredExtraAndShortWindowNodes() {
        for(text in listOf("""{"type":"User","key":"tier","op":"eq"}""","""{"type":"Bool","value":true,"extra":1}""","""{"type":"Events.Count","name":"read","within":{"type":"Duration","value":59}}""","""{"type":"Run.Value","path":"state.tier"}"""))
            assertThrows(IllegalArgumentException::class.java) { ClosedCondition(Json.parseToJsonElement(text).jsonObject) }
    }
}
