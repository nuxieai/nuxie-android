package ai.nuxie.sdk.experiences

import ai.nuxie.sdk.fixtures.FixtureRunner
import ai.nuxie.sdk.runtime.NuxieViewModelCatalog
import java.util.Base64
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class ExperiencePageHostContractTest {
    private val cases = Json.parseToJsonElement(FixtureRunner.fixturesRoot()
        .resolve("runtime/layer-host-interim/format-cases.json").readText()).jsonObject
    private fun render(): JsonObject {
        val entry = Json.parseToJsonElement(FixtureRunner.fixturesRoot()
            .resolve("journeys/rendered-custom-transition/release-entry.json").readText()).jsonObject
        val bytes = Base64.getDecoder().decode(entry.getValue("envelope").jsonObject
            .getValue("descriptorBytesBase64").jsonPrimitive.content)
        return Json.parseToJsonElement(bytes.decodeToString()).jsonObject.getValue("render").jsonObject
    }
    @Test fun `shared format vectors validate through exact render schema and thin reader`() {
        val render = render()
        assertEquals("\$nuxie.page", ExperiencePageHostContract.Host().artboardName)
        assertEquals("\$nuxie.Page", ExperiencePageHostContract.Host().modelName)
        assertEquals("screens", ExperiencePageHostContract.Host().listPath)
        JourneyRenderSchema.validate(render)
        for (value in cases.getValue("layerHosts").jsonArray) {
            val case = value.jsonObject
            val changed = JsonObject(render + ("layerHost" to case.getValue("value")))
            assertAccepted(case) { JourneyRenderSchema.validate(changed); ExperiencePageHostContract.readHost(case.getValue("value")) }
        }
        for (value in cases.getValue("transitionKinds").jsonArray) {
            val case = value.jsonObject
            val transitions = render.getValue("transitions").jsonArray.map {
                JsonObject(it.jsonObject + ("kind" to case.getValue("value")))
            }
            val changed = JsonObject(render + ("transitions" to JsonArray(transitions)))
            assertAccepted(case) { JourneyRenderSchema.validate(changed); ExperiencePageHostContract.transitions(changed) }
        }
        assertThrows(JourneyReleaseAuthenticationException::class.java) {
            JourneyRenderSchema.validate(JsonObject(render + ("unknown" to JsonNull)))
        }
    }
    @Test fun `page transition reader does not silently migrate the deferred release validator`() {
        val baseline = render()
        val declarations = baseline.getValue("transitions").jsonArray.map {
            JsonObject(it.jsonObject + ("kind" to JsonPrimitive("page")))
        }
        val page = JsonObject(baseline + ("transitions" to JsonArray(declarations)))
        assertEquals(ExperiencePageHostContract.Kind.PAGE, ExperiencePageHostContract.transitions(page).first().kind)
        assertThrows(JourneyReleaseAuthenticationException::class.java) { JourneyRenderSchema.validate(page) }
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

    @Test fun `shared Journey vectors keep styles and transition identifiers`() {
        for (value in cases.getValue("journeyTransitions").jsonArray) {
            val case = value.jsonObject
            assertAccepted(case) {
                val intent = ExperiencePageHostContract.intent(case.getValue("value"))
                assertEquals(case.getValue("value").jsonObject["transitionId"]?.jsonPrimitive?.content, intent.transitionId)
                JourneyGrammar.action(buildJsonObject {
                    put("type", "navigate"); put("screenId", "one"); put("transition", case.getValue("value"))
                }, setOf("one"), emptySet())
            }
        }
    }
    @Test fun `declarations retain kind and forward and reverse completion names`() {
        for (kind in listOf("layer", "choreographed")) {
            val render = render()
            val transitions = JsonArray(render.getValue("transitions").jsonArray.map {
                JsonObject(it.jsonObject + ("kind" to JsonPrimitive(kind)))
            })
            val declaration = ExperiencePageHostContract.transitions(JsonObject(render + ("transitions" to transitions))).first()
            assertEquals(kind.uppercase(), declaration.kind.name)
            val forward = checkNotNull(declaration.resolve(declaration.sourceScreenId, declaration.destinationScreenId))
            val reverse = checkNotNull(declaration.resolve(declaration.destinationScreenId, declaration.sourceScreenId))
            assertFalse(forward.outgoingCompletionEvent.endsWith(".reverse"))
            assertTrue(reverse.outgoingCompletionEvent.endsWith(".reverse"))
            assertNull(declaration.resolve("other", declaration.destinationScreenId))
        }
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
