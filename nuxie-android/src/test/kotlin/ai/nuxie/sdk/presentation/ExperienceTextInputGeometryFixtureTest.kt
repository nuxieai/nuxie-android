package ai.nuxie.sdk.presentation

import ai.nuxie.sdk.fixtures.FixtureRunner
import ai.nuxie.sdk.runtime.NativeViewModelSnapshot
import ai.nuxie.sdk.runtime.NativeViewModelSnapshotInstance
import ai.nuxie.sdk.runtime.NativeViewModelSnapshotValue
import ai.nuxie.sdk.runtime.NuxieViewModelPropertyKind
import ai.nuxie.sdk.runtime.NuxieViewModelSnapshot
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.float
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.assertEquals
import org.junit.Test

class ExperienceTextInputGeometryFixtureTest {
    @Test
    fun sharedGeometryPaths() {
        val fixture = Json.parseToJsonElement(FixtureRunner.fixturesRoot()
            .resolve("journeys/planes/text-input-geometry.json").readText()).jsonObject
        val values = fixture.getValue("values").jsonArray.mapIndexed { index, entry ->
            val value = entry.jsonObject
            val type = value.getValue("type").jsonPrimitive.content
            val kind = when (type) {
                "reference" -> NuxieViewModelPropertyKind.VIEW_MODEL
                "string" -> NuxieViewModelPropertyKind.STRING
                "number" -> NuxieViewModelPropertyKind.NUMBER
                else -> error("Unknown fixture value type: $type")
            }
            NativeViewModelSnapshotValue(
                value.getValue("owner").jsonPrimitive.long, index.toLong(),
                value.getValue("name").jsonPrimitive.content, kind.nativeValue, byteArrayOf(),
                value["reference"]?.jsonPrimitive?.long ?: 0L,
                value["number"]?.jsonPrimitive?.content?.toFloat() ?: 0f,
            )
        }.toTypedArray()
        val snapshot = NuxieViewModelSnapshot.fromNative(NativeViewModelSnapshot(
            fixture.getValue("rootInstanceId").jsonPrimitive.long,
            fixture.getValue("instances").jsonArray.map {
                NativeViewModelSnapshotInstance(it.jsonPrimitive.long, 0)
            }.toTypedArray(), values,
        ))
        val queries = fixture.getValue("queries").jsonArray
        assertEquals(21, queries.size)
        queries.forEach {
            val query = it.jsonObject
            val path = query.getValue("path").jsonPrimitive.content
            val expected = query.getValue("expected").takeUnless { it == JsonNull }?.jsonPrimitive?.float
            assertEquals(path, expected, snapshot.resolveGeometryNumber(path))
        }
    }
}
