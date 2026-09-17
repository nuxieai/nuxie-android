package ai.nuxie.sdk.runtime

import ai.nuxie.sdk.fixtures.FixtureRunner
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertNull
import org.junit.Test

class NuxiePurchaseScopeSnapshotTest {
    @Test
    fun `signed reference aliases reject conflicting native identity and model`() {
        val current = snapshot("root", "secondary")
        val first = NuxieViewModelInstanceBinding("WelcomeModel", "welcome", "product", "product.first", "Product")
        val second = NuxieViewModelInstanceBinding("WelcomeModel", "secondary", "product", "product.second", "Product")
        val aliases = current.captureInstanceIds(listOf(first, second))
        assertEquals(3L, aliases["product.first"])
        assertEquals(4L, aliases["product.second"])
        assertThrows(IllegalArgumentException::class.java) {
            current.captureInstanceIds(listOf(first, second.copy(instanceId = "product.first")))
        }
        assertThrows(IllegalArgumentException::class.java) {
            current.captureInstanceIds(listOf(first.copy(modelName = "WelcomeModel")))
        }
    }

    @Test
    fun `shared purchase scopes use the selected live instance`() {
        val fixture = Json.parseToJsonElement(FixtureRunner.fixturesRoot()
            .resolve("journeys/planes/purchase-reference-scopes.json").readText()).jsonObject
        val values = fixture.getValue("values").jsonArray.map { it.jsonObject }
        fun initial(id: String) = values.single { it.getValue("instanceId").jsonPrimitive.content == id }
            .getValue("value").jsonObject.getValue("placementId").jsonPrimitive.content
        val before = snapshot(initial("welcome"), initial("secondary"))
        val current = snapshot("golden:monthly", initial("secondary"))
        assertEquals("golden:yearly", before.resolveScopedString("product.placementId", null, null))
        for (entry in fixture.getValue("cases").jsonArray) {
            val vector = entry.jsonObject
            val ref = vector.getValue("reference").jsonObject.getValue("ref").jsonObject
            assertEquals(vector.getValue("name").jsonPrimitive.content,
                vector.getValue("expected").jsonPrimitive.contentOrNull,
                current.resolveScopedString(ref.getValue("path").jsonPrimitive.content,
                    ref["viewModelName"]?.jsonPrimitive?.content,
                    vector["instanceId"]?.jsonPrimitive?.content,
                    ref["isRelative"]?.jsonPrimitive?.booleanOrNull))
        }
        assertNull(current.resolveScopedString("product.placementId", null, "retired"))
        assertNull(current.resolveScopedString("placementId", "Product", null))
        assertNull(current.resolveScopedString("product.placementId", "Product", "secondary"))
    }

    @Test
    fun `detached instance mapping cannot read another root value`() {
        val snapshot = NuxieViewModelSnapshot.fromNative(NativeViewModelSnapshot(1,
            arrayOf(NativeViewModelSnapshotInstance(1, 0)), emptyArray()),
            schemaNames = mapOf(0L to "WelcomeModel"), instanceIds = mapOf("secondary" to 2L))
        assertNull(snapshot.resolveScopedString("placementId", null, "secondary"))
    }

    private fun snapshot(root: String, secondary: String): NuxieViewModelSnapshot {
        fun reference(owner: Long, child: Long) = NativeViewModelSnapshotValue(
            owner, 0, "product", NuxieViewModelPropertyKind.VIEW_MODEL.nativeValue, byteArrayOf(), child)
        fun placement(owner: Long, value: String) = NativeViewModelSnapshotValue(
            owner, 0, "placementId", NuxieViewModelPropertyKind.STRING.nativeValue, value.encodeToByteArray(), 0)
        return NuxieViewModelSnapshot.fromNative(NativeViewModelSnapshot(1,
            arrayOf(NativeViewModelSnapshotInstance(1, 0), NativeViewModelSnapshotInstance(2, 0),
                NativeViewModelSnapshotInstance(3, 1), NativeViewModelSnapshotInstance(4, 1)),
            arrayOf(reference(1, 3), reference(2, 4), placement(3, root), placement(4, secondary))),
            schemaNames = mapOf(0L to "WelcomeModel", 1L to "Product"),
            instanceIds = mapOf("secondary" to 2L), defaultInstanceId = "welcome")
    }
}
