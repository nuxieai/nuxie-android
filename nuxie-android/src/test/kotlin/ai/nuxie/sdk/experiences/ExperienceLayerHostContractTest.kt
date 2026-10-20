package ai.nuxie.sdk.experiences

import ai.nuxie.sdk.fixtures.FixtureRunner
import ai.nuxie.sdk.runtime.NuxieViewModelCatalog
import java.util.Base64
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class ExperienceLayerHostContractTest {
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
        assertNull(ExperienceLayerHostContract.host(render))
        JourneyRenderSchema.validate(render)
        for (value in cases.getValue("layerHosts").jsonArray) {
            val case = value.jsonObject
            val changed = JsonObject(render + ("layerHost" to case.getValue("value")))
            assertAccepted(case) { JourneyRenderSchema.validate(changed); ExperienceLayerHostContract.host(changed) }
        }
        for (value in cases.getValue("transitionKinds").jsonArray) {
            val case = value.jsonObject
            val transitions = render.getValue("transitions").jsonArray.map {
                JsonObject(it.jsonObject + ("kind" to case.getValue("value")))
            }
            val changed = JsonObject(render + ("transitions" to JsonArray(transitions)))
            assertAccepted(case) { JourneyRenderSchema.validate(changed); ExperienceLayerHostContract.transitions(changed) }
        }
        assertThrows(JourneyReleaseAuthenticationException::class.java) {
            JourneyRenderSchema.validate(JsonObject(render + ("unknown" to JsonNull)))
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
            val accepted = runCatching { JourneyRenderSchema.validate(changed); ExperienceLayerHostContract.transitions(changed) }.isSuccess
            case.getValue("accept").jsonPrimitive.boolean to accepted
        }
        assertEquals("Every imported defaultPairs vector must be exercised", outcomes.map { it.first }, outcomes.map { it.second })
    }

    @Test fun `shared Journey vectors keep styles and transition identifiers`() {
        for (value in cases.getValue("journeyTransitions").jsonArray) {
            val case = value.jsonObject
            assertAccepted(case) {
                val intent = ExperienceLayerHostContract.intent(case.getValue("value"))
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
            val declaration = ExperienceLayerHostContract.transitions(JsonObject(render + ("transitions" to transitions))).first()
            assertEquals(kind.uppercase(), declaration.kind.name)
            val forward = checkNotNull(declaration.resolve(declaration.sourceScreenId, declaration.destinationScreenId))
            val reverse = checkNotNull(declaration.resolve(declaration.destinationScreenId, declaration.sourceScreenId))
            assertFalse(forward.outgoingCompletionEvent.endsWith(".reverse"))
            assertTrue(reverse.outgoingCompletionEvent.endsWith(".reverse"))
            assertNull(declaration.resolve("other", declaration.destinationScreenId))
        }
    }
    @Test fun `row selection converts flat catalog default to local index and rejects ambiguous types`() {
        val catalog = NuxieViewModelCatalog(listOf(
            NuxieViewModelCatalog.Schema(0, "Other", IntRange.EMPTY, 0..1, 0, false),
            NuxieViewModelCatalog.Schema(5, "Row", IntRange.EMPTY, 2..2, 2, false)), emptyList(), listOf(
            NuxieViewModelCatalog.AuthoredInstance(0, 0, "first"),
            NuxieViewModelCatalog.AuthoredInstance(0, 1, "second"),
            NuxieViewModelCatalog.AuthoredInstance(5, 0, "row")))
        val descriptor = Json.parseToJsonElement("""{"leg":{"screens":[{"id":"b","defaultViewModelName":"Row","defaultInstanceId":"row-b"}]}}""").jsonObject
        val selected = ExperienceLayerHostContract.rowSelection(descriptor, "b", catalog)
        assertEquals(5, selected.schemaIndex); assertEquals(0, selected.authoredInstanceIndex)
        assertEquals("row-b", selected.defaultInstanceId)
        val noDefault = catalog.copy(schemas = catalog.schemas.map { it.copy(defaultAuthoredInstance = null) })
        assertNull(ExperienceLayerHostContract.rowSelection(descriptor, "b", noDefault).authoredInstanceIndex)
        for (invalid in listOf(catalog.copy(schemas = catalog.schemas + catalog.schemas.last()),
            catalog.copy(authoredInstances = emptyList()), catalog.copy(schemas = emptyList()))) {
            assertThrows(IllegalArgumentException::class.java) { ExperienceLayerHostContract.rowSelection(descriptor, "b", invalid) }
        }
        val duplicate = Json.parseToJsonElement("""{"leg":{"screens":[{"id":"a","defaultViewModelName":"Row"},{"id":"b","defaultViewModelName":"Row"}]}}""").jsonObject
        assertThrows(IllegalArgumentException::class.java) { ExperienceLayerHostContract.rowSelection(duplicate, "b", catalog) }
    }
    private fun assertAccepted(case: JsonObject, block: () -> Unit) {
        val result = runCatching(block)
        assertEquals("$case: ${result.exceptionOrNull()}", case.getValue("accept").jsonPrimitive.boolean, result.isSuccess)
    }
}
