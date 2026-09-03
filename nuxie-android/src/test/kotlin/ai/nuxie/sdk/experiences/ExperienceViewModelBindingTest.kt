package ai.nuxie.sdk.experiences

import ai.nuxie.sdk.fixtures.FixtureRunner
import java.io.File
import java.util.Base64
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class ExperienceViewModelBindingTest {
    @Test
    fun `shared iOS descriptor selects its declared default without reinterpreting instance id`() {
        val envelope = Json.parseToJsonElement(
            File(FixtureRunner.fixturesRoot(), "journeys/planes/release.json").readText(),
        ).jsonObject.getValue("renderedEntry").jsonObject.getValue("envelope").jsonObject
        val descriptor = Json.parseToJsonElement(
            Base64.getDecoder().decode(
                envelope.getValue("descriptorBytesBase64").jsonPrimitive.content,
            ).decodeToString(),
        ).jsonObject

        assertEquals("WelcomeModel", ExperienceViewModelBinding.defaultSchemaName(descriptor, "Welcome"))
        assertEquals("WelcomeModel", ExperienceViewModelBinding.defaultSchemaName(descriptor, null))
    }

    @Test
    fun `named artboard resolves Journey screen identity rather than array position`() {
        val descriptor = descriptor(
            journeyScreens = listOf(journey("second", "Second"), journey("first", "First")),
        )
        assertEquals("First", ExperienceViewModelBinding.defaultSchemaName(descriptor, "First artboard"))
        assertEquals("Second", ExperienceViewModelBinding.defaultSchemaName(descriptor, "Second artboard"))
    }

    @Test
    fun `implicit selection requires one screen rather than choosing array order`() {
        assertEquals("First", ExperienceViewModelBinding.defaultSchemaName(
            descriptor(renderScreens = listOf(render("first", "First artboard"))), null,
        ))
        assertThrows(IllegalArgumentException::class.java) {
            ExperienceViewModelBinding.defaultSchemaName(descriptor(), null)
        }
    }

    @Test
    fun `screen with no declared default does not inherit another screen schema`() {
        val descriptor = descriptor(
            journeyScreens = listOf(journey("first"), journey("second", "Second")),
        )
        assertNull(ExperienceViewModelBinding.defaultSchemaName(descriptor, "First artboard"))
        assertEquals("Second", ExperienceViewModelBinding.defaultSchemaName(descriptor, "Second artboard"))
    }

    @Test
    fun `default instance id alone does not opt into native binding`() {
        val first = JsonObject(journey("first") + ("defaultInstanceId" to JsonPrimitive("authored-id")))
        assertNull(ExperienceViewModelBinding.defaultSchemaName(
            descriptor(journeyScreens = listOf(first, journey("second", "Second"))),
            "First artboard",
        ))
    }

    @Test
    fun `declared default must be an exact valid iOS identifier`() {
        val invalid = listOf(
            JsonNull, JsonPrimitive(false), JsonPrimitive(7), JsonPrimitive(""),
            JsonPrimitive("bad\u0000name"), JsonPrimitive("x".repeat(129)),
            JsonArray(emptyList()), JsonObject(emptyMap()),
        )
        invalid.forEach { value ->
            val first = JsonObject(journey("first") + ("defaultViewModelName" to value))
            assertThrows(IllegalArgumentException::class.java) {
                ExperienceViewModelBinding.defaultSchemaName(
                    descriptor(journeyScreens = listOf(first, journey("second", "Second"))), "First artboard",
                )
            }
        }
        val exact = "  Authored schema  "
        assertEquals(exact, ExperienceViewModelBinding.defaultSchemaName(
            descriptor(journeyScreens = listOf(journey("first", exact), journey("second"))), "First artboard",
        ))
    }

    @Test
    fun `missing named artboard does not fall back to the first screen`() {
        assertThrows(IllegalArgumentException::class.java) {
            ExperienceViewModelBinding.defaultSchemaName(descriptor(), "Missing")
        }
    }

    @Test
    fun `ambiguous named artboard cannot select an arbitrary Journey declaration`() {
        val renderScreens = listOf(render("first", "Shared"), render("second", "Shared"))
        assertThrows(IllegalArgumentException::class.java) {
            ExperienceViewModelBinding.defaultSchemaName(descriptor(renderScreens = renderScreens), "Shared")
        }
    }

    @Test
    fun `missing or duplicate matching Journey identity fails closed`() {
        listOf(
            listOf(journey("second", "Second")),
            listOf(journey("first", "First"), journey("first", "Other")),
        ).forEach { journeyScreens ->
            assertThrows(IllegalArgumentException::class.java) {
                ExperienceViewModelBinding.defaultSchemaName(descriptor(journeyScreens = journeyScreens), "First artboard")
            }
        }
    }

    @Test
    fun `missing malformed or duplicate render identity fails closed`() {
        val invalid = listOf(
            emptyList(),
            listOf(render("first", "First"), render("first", "Other")),
            listOf(JsonObject(mapOf("artboardName" to JsonPrimitive("First")))),
            listOf(JsonObject(mapOf("id" to JsonPrimitive("first")))),
        )
        invalid.forEach { renderScreens ->
            assertThrows(IllegalArgumentException::class.java) {
                ExperienceViewModelBinding.defaultSchemaName(descriptor(renderScreens = renderScreens), null)
            }
        }
    }

    @Test
    fun `missing or malformed descriptor sections do not silently opt out`() {
        val good = descriptor()
        val invalid = listOf(
            JsonObject(good - "render"),
            JsonObject(good - "leg"),
            JsonObject(good + ("leg" to JsonNull)),
            JsonObject(good + ("leg" to JsonObject(mapOf("screens" to JsonPrimitive("invalid"))))),
            JsonObject(good + ("leg" to JsonObject(mapOf("screens" to JsonArray(listOf(JsonNull)))))),
        )
        invalid.forEach { value ->
            assertThrows(IllegalArgumentException::class.java) {
                ExperienceViewModelBinding.defaultSchemaName(value, "First artboard")
            }
        }
    }

    private fun descriptor(
        renderScreens: List<JsonObject> = listOf(render("first", "First artboard"), render("second", "Second artboard")),
        journeyScreens: List<JsonObject> = listOf(journey("first", "First"), journey("second", "Second")),
    ): JsonObject = JsonObject(mapOf(
        "render" to JsonObject(mapOf("screens" to JsonArray(renderScreens))),
        "leg" to JsonObject(mapOf("screens" to JsonArray(journeyScreens))),
    ))

    private fun render(id: String, artboard: String): JsonObject = JsonObject(mapOf(
        "id" to JsonPrimitive(id), "artboardName" to JsonPrimitive(artboard),
    ))

    private fun journey(id: String, schema: String? = null): JsonObject = JsonObject(
        mutableMapOf<String, JsonElement>("id" to JsonPrimitive(id)).apply {
            schema?.let { put("defaultViewModelName", JsonPrimitive(it)) }
        },
    )
}
