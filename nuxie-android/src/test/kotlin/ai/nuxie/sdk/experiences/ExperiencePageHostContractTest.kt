package ai.nuxie.sdk.experiences

import ai.nuxie.sdk.fixtures.FixtureRunner
import ai.nuxie.sdk.runtime.NuxieViewModelCatalog
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class ExperiencePageHostContractTest {
    private val cases = Json.parseToJsonElement(FixtureRunner.fixturesRoot()
        .resolve("runtime/layer-host-interim/format-cases.json").readText()).jsonObject
    private fun render(): JsonObject {
        // Schema-only input: this does not stand in for a published motion fixture.
        return Json.parseToJsonElement("""{
          "renderer":"nux",
          "nux":{"key":"renders/sha256/${"0".repeat(64)}.nux","sha256":"${"0".repeat(64)}","sizeBytes":1,"contentType":"application/vnd.nuxie.scene"},
          "assets":[],"textInputs":[],
          "screens":[{"id":"a","artboardId":"a","artboardName":"a","width":402,"height":874},
                     {"id":"b","artboardId":"b","artboardName":"b","width":402,"height":874}],
          "transitions":[{"id":"ab","kind":"page","sourceScreenId":"a","destinationScreenId":"b","durationMs":400,
            "source":{"completeEventName":"out"},"destination":{"completeEventName":"in"},
            "reverse":{"durationMs":400,"source":{"completeEventName":"out.reverse"},"destination":{"completeEventName":"in.reverse"}}}]
        }""").jsonObject
    }
    @Test fun `page declarations validate and retired host and transition fields are refused`() {
        val render = render()
        assertEquals("\$nuxie.page", ExperiencePageHostContract.Host().artboardName)
        assertEquals("\$nuxie.Page", ExperiencePageHostContract.Host().modelName)
        JourneyRenderSchema.validate(render)
        for (field in listOf("layerHost", "unknown")) assertThrows(JourneyReleaseAuthenticationException::class.java) {
            JourneyRenderSchema.validate(JsonObject(render + (field to JsonObject(emptyMap()))))
        }
        for (kind in listOf("layer", "choreographed", "unknown")) {
            val changed = JsonObject(render + ("transitions" to JsonArray(render.getValue("transitions").jsonArray.map {
                JsonObject(it.jsonObject + ("kind" to JsonPrimitive(kind)))
            })))
            assertThrows(JourneyReleaseAuthenticationException::class.java) { JourneyRenderSchema.validate(changed) }
        }
        val changed = JsonObject(render + ("transitions" to JsonArray(render.getValue("transitions").jsonArray.map {
            JsonObject(it.jsonObject + ("incomingOnTop" to JsonPrimitive(true)))
        })))
        assertThrows(JourneyReleaseAuthenticationException::class.java) { JourneyRenderSchema.validate(changed) }
    }

    @Test fun `reverse requires its own integer duration within the published bounds`() {
        val baseline = render()
        fun withDuration(duration: JsonElement?): JsonObject = JsonObject(baseline + ("transitions" to
            JsonArray(baseline.getValue("transitions").jsonArray.map {
                val reverse = it.jsonObject.getValue("reverse").jsonObject - "durationMs"
                JsonObject(it.jsonObject + ("reverse" to JsonObject(reverse +
                    if (duration == null) emptyMap() else mapOf("durationMs" to duration))))
            })))
        val outcomes = cases.getValue("reverseDurations").jsonArray.map { vector ->
            val case = vector.jsonObject
            val duration = if (case["omit"] == JsonPrimitive(true)) null else case.getValue("value")
            val changed = withDuration(duration)
            val accepted = runCatching { JourneyRenderSchema.validate(changed) }.isSuccess
            if (accepted) {
                val declaration = ExperiencePageHostContract.transitions(changed).first()
                assertEquals(case.getValue("value").jsonPrimitive.long, checkNotNull(declaration.reverse).durationMs)
                assertEquals(baseline.getValue("transitions").jsonArray.first().jsonObject
                    .getValue("durationMs").jsonPrimitive.long, declaration.forward.durationMs)
            }
            case.getValue("accept").jsonPrimitive.boolean to accepted
        }
        assertEquals("Every imported reverseDurations vector must be exercised", outcomes.map { it.first }, outcomes.map { it.second })
        assertThrows(JourneyReleaseAuthenticationException::class.java) {
            ExperiencePageHostContract.transitions(withDuration(null))
        }
    }

    @Test fun `published route references select by source and missing source cuts`() {
        for (style in listOf("magic-move", "fade", "none")) {
            val route = Json.parseToJsonElement("""{"type":"$style","transitionIds":{"a":"ab","c":"cb"}}""").jsonObject
            JourneyReleaseSchema.transition(route)
            assertEquals("ab", ExperiencePageHostContract.intent(route, "a").transitionId)
            assertEquals("cb", ExperiencePageHostContract.intent(route, "c").transitionId)
            assertEquals(JsonPrimitive("none"), ExperiencePageHostContract.resolveIntent(route, "missing")?.get("type"))
        }
    }

    @Test fun `default pair fixture vectors validate optional strict boolean markers`() {
        val baseline = render()
        val outcomes = cases.getValue("defaultPairs").jsonArray.map { vector ->
            val case = vector.jsonObject
            val transitions = baseline.getValue("transitions").jsonArray.map {
                JsonObject((it.jsonObject - "defaultPair") + if (case["omit"] == JsonPrimitive(true)) emptyMap()
                    else mapOf("defaultPair" to case.getValue("value")))
            }
            val changed = JsonObject(baseline + ("transitions" to JsonArray(transitions)))
            val accepted = runCatching { JourneyRenderSchema.validate(changed); ExperiencePageHostContract.transitions(changed) }.isSuccess
            case.getValue("accept").jsonPrimitive.boolean to accepted
        }
        assertEquals("Every imported defaultPairs vector must be exercised", outcomes.map { it.first }, outcomes.map { it.second })
    }

    @Test fun `published transition forms are strict and custom is refused`() {
        for (json in listOf("{\"type\":\"none\"}", "{\"type\":\"fade\"}", "{\"type\":\"push\"}",
            "{\"type\":\"modal\",\"height\":\"fit-content\"}",
            "{\"type\":\"magic-move\",\"transitionId\":\"pair\"}")) {
            val transition = Json.parseToJsonElement(json)
            JourneyGrammar.action(buildJsonObject { put("type", "navigate"); put("screenId", "one"); put("transition", transition) }, setOf("one"), emptySet())
        }
        for (json in listOf("""{"type":"custom","transitionId":"pair"}""",
            """{"type":"fade","transitionIds":{}}""",
            """{"type":"modal","present":"fullscreen","height":"large"}""",
            """{"type":"magic-move"}""", """{"type":"push","transitionId":"pair"}""",
            """{"type":"fade","transitionId":"pair","transitionIds":{}}""",
            """{"type":"none","transitionIds":{"a":4}}""")) {
            assertThrows(JourneyReleaseAuthenticationException::class.java) { ExperiencePageHostContract.intent(Json.parseToJsonElement(json)) }
        }
    }
    @Test fun `page declaration retains forward and reverse completion names`() {
        val declaration = ExperiencePageHostContract.transitions(render()).first()
        assertEquals(ExperiencePageHostContract.Kind.PAGE, declaration.kind)
        assertTrue(checkNotNull(declaration.reverse).outgoingCompletionEvent.endsWith(".reverse"))
        assertNull(declaration.resolve("other", declaration.destinationScreenId))
    }
    @Test fun `row allocation uses discovered schema and ignores authored index and leg type name`() {
        val catalog = NuxieViewModelCatalog(listOf(
            NuxieViewModelCatalog.Schema(5, "AuthoredScreenType", IntRange.EMPTY, 0..0, 0, false)), emptyList(),
            listOf(NuxieViewModelCatalog.AuthoredInstance(5, 0, "authored")))
        val descriptor = Json.parseToJsonElement("""{"leg":{"screens":[{"id":"b","defaultViewModelName":"Misleading","defaultInstanceId":"row-b"}]}}""").jsonObject
        val selected = ExperiencePageHostContract.pageRowSelection(descriptor, "b", catalog, 5)
        assertEquals(5, selected.schemaIndex)
        assertEquals("AuthoredScreenType", selected.rootName)
        assertNull(selected.authoredInstanceIndex)
        assertEquals("row-b", selected.defaultInstanceId)
    }
    private fun assertAccepted(case: JsonObject, block: () -> Unit) {
        val result = runCatching(block)
        assertEquals("$case: ${result.exceptionOrNull()}", case.getValue("accept").jsonPrimitive.boolean, result.isSuccess)
    }
}
