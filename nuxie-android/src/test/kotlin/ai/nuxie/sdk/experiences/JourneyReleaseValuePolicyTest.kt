package ai.nuxie.sdk.experiences

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class JourneyReleaseValuePolicyTest {
    @Test fun `nested object declarations keep strict shapes`() {
        fun policy(state: String) = Json.parseToJsonElement("""{"state":$state,"responses":{},"ruleGroups":[]}""").jsonObject
        val accepted = JourneyReleaseValuePolicy.parse(policy("""{"profile":{"type":"object","fields":{"name":{"type":"string"},"minutes":{"type":"number"},"topics":{"type":"enum","values":["Reading, writing","Travel"],"multiple":true},"settings":{"type":"object","fields":{"day":{"type":"date"}}}}}}"""))
        assertTrue(accepted.rules.isEmpty())
        assertTrue(accepted.groups.isEmpty())
        for (state in listOf(
            """{"profile":{"type":"object"}}""",
            """{"profile":{"type":"object","fields":{}}}""",
            """{"profile":{"type":"list","items":{"x":{"type":"number"}},"fields":{"x":{"type":"number"}}}}""",
            """{"profile":{"type":"object","items":{"x":{"type":"number"}},"fields":{"x":{"type":"number"}}}}""",
            """{"profile":{"type":"number","fields":{"x":{"type":"number"}}}}""",
            """{"profile":{"type":"object","fields":{"name":{"type":"string","extra":true}}}}""",
            """{"profile":{"type":"object","fields":{"name":{"type":"string","default":"Ana"}}}}""",
            """{"profile":{"type":"object","fields":{"isset:name":{"type":"boolean"}}}}""",
            """{"profile":{"type":"object","fields":{"x":{"type":"enum","values":["a","a"]}}}}""",
            """{"profile":{"type":"object","fields":{"x":{"type":"number","multiple":true}}}}""",
            """{"profile":{"type":"object","fields":{"x":{"type":"list"}}}}""",
            """{"profile":{"type":"object","fields":{"x":{"type":"date","fields":{}}}}}""",
        )) assertThrows(state, JourneyReleaseAuthenticationException::class.java) { JourneyReleaseValuePolicy.parse(policy(state)) }
        assertThrows(JourneyReleaseAuthenticationException::class.java) {
            JourneyReleaseValuePolicy.parse(Json.parseToJsonElement("""{"state":{},"responses":{"form":{"title":"Form","model":"Responses:form","fields":[{"key":"profile","label":"Profile","type":"object","fields":{"x":{"type":"number"}},"rules":[]}]}},"ruleGroups":[]}""").jsonObject)
        }
    }

    private val policy = Json.parseToJsonElement("""{
      "state":{},
      "responses":{"feedback":{"title":"Feedback","model":"Responses:feedback","fields":[
        {"key":"stars","label":"Stars","type":"number","rules":[
          {"model":"Responses:feedback","property":"stars","kind":1,"mode":0,"number_bound":1,
           "text":"","values":[],"value_count":0,"picked_property":"","bound_flags":0,
           "minimum":0,"maximum":0,"code":"min","message":"At least one"}
        ]}]}},
      "ruleGroups":[{"model":"Responses:feedback","valid":"valid","member_count":1,"members":[
        {"property":"stars","errors_path":"errors/stars","item_model":"ResponseError","code_property":"rule","message_property":"message"}
      ]}]
    }""").jsonObject

    @Test fun `native records preserve operands and enforce strict shapes`() {
        val parsed = JourneyReleaseValuePolicy.parse(policy)
        assertEquals(1.0, parsed.rules.single().numberBound, 0.0)
        assertEquals("At least one", parsed.rules.single().message)
        assertEquals("errors/stars", parsed.groups.single().members.single().errorsPath)
        assertThrows(JourneyReleaseAuthenticationException::class.java) {
            JourneyReleaseValuePolicy.parse(JsonObject(policy + ("ruleGroups" to JsonArray(emptyList()))))
        }
        val responses = policy.getValue("responses").jsonObject
        val form = responses.getValue("feedback").jsonObject
        val field = form.getValue("fields").jsonArray.single().jsonObject
        val rule = field.getValue("rules").jsonArray.single().jsonObject
        for ((key, bad) in listOf("mode" to 1L, "value_count" to 1L, "minimum" to -1L, "maximum" to 4294967296L)) {
            val invalidField = JsonObject(field + ("rules" to JsonArray(listOf(JsonObject(rule + (key to JsonPrimitive(bad)))))))
            val invalidForm = JsonObject(form + ("fields" to JsonArray(listOf(invalidField))))
            val invalid = JsonObject(policy + ("responses" to JsonObject(mapOf("feedback" to invalidForm))))
            assertThrows(key, JourneyReleaseAuthenticationException::class.java) { JourneyReleaseValuePolicy.parse(invalid) }
        }
    }
}
