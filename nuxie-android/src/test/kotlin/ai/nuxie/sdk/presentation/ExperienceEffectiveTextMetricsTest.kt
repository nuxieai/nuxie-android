package ai.nuxie.sdk.presentation

import ai.nuxie.sdk.fixtures.FixtureRunner
import ai.nuxie.sdk.runtime.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class ExperienceEffectiveTextMetricsTest {
    @Test fun `resolved pairs follow the shared compatibility contract`() {
        val fixture = Json.parseToJsonElement(FixtureRunner.fixturesRoot()
            .resolve("journeys/planes/text-input-effective-metrics.json").readText()).jsonObject
        val input = ExperienceTextInput.forScreen(textInputDescriptor(), "survey").single().copy(
            geometryPaths = mapOf("xPath" to "nuxieTextInputs/field/x"),
            style = ExperienceTextInput.forScreen(textInputDescriptor(), "survey").single().style.copy(fontSize = 18f, lineHeight = 24f))
        for (entry in fixture.getValue("cases").jsonArray) {
            val case = entry.jsonObject
            val values = mutableListOf(
                NativeViewModelSnapshotValue(1, 0, "nuxieTextInputs", 9, byteArrayOf(), 2),
                NativeViewModelSnapshotValue(2, 0, "field", 9, byteArrayOf(), 3))
            case.getValue("outputs").jsonObject.entries.forEachIndexed { index, (name, value) ->
                val primitive = value as? JsonPrimitive
                val special = (value as? JsonObject)?.get("nativeNumber")?.jsonPrimitive?.content
                val kind = when {
                    value == JsonNull -> 0
                    special != null -> 2
                    primitive?.isString == true -> 1
                    primitive?.booleanOrNull != null -> 3
                    else -> 2
                }
                values += NativeViewModelSnapshotValue(3, index.toLong(), name, kind,
                    if (kind == 1) primitive!!.content.encodeToByteArray() else byteArrayOf(), 0,
                    numberValue = if (kind == 2) (special ?: primitive!!.content).toFloat() else 0f,
                    boolValue = primitive?.booleanOrNull ?: false)
            }
            val snapshot = NuxieViewModelSnapshot.fromNative(NativeViewModelSnapshot(1,
                arrayOf(NativeViewModelSnapshotInstance(1, 0), NativeViewModelSnapshotInstance(2, 1),
                    NativeViewModelSnapshotInstance(3, 2)), values.toTypedArray()))
            val expected = (case.getValue("expected") as? JsonObject)?.let {
                ExperienceTextInput.EffectiveMetrics(it.getValue("fontSize").jsonPrimitive.float,
                    it.getValue("lineHeight").jsonPrimitive.float)
            }
            assertEquals(case.getValue("name").jsonPrimitive.content, expected, input.effectiveMetrics(snapshot))
            assertEquals("Optional root label", expected,
                input.copy(geometryPaths = mapOf("xPath" to "Root/nuxieTextInputs/field/x")).effectiveMetrics(snapshot))
        }
    }
}
