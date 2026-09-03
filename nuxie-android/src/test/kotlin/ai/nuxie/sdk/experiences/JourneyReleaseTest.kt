package ai.nuxie.sdk.experiences

import ai.nuxie.sdk.fixtures.FixtureRunner
import android.util.Base64
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class JourneyReleaseTest {
    private val fixture = Json.parseToJsonElement(
        FixtureRunner.fixturesRoot().resolve("journeys/planes/release.json").readText()).jsonObject
    private val keys = mapOf("TEST_ONLY_DEV_KEYPAIR" to Base64.decode(
        fixture.getValue("publicKeyBase64").jsonPrimitive.content, Base64.NO_WRAP))

    @Test fun `shared admission cases reject invalid local programs before execution`() {
        val cases = Json.parseToJsonElement(FixtureRunner.fixturesRoot()
            .resolve("journeys/planes/admission.json").readText()).jsonObject.getValue("cases").jsonArray
        for (item in cases) {
            val case = item.jsonObject
            val entry = fixture.getValue(case.getValue("entry").jsonPrimitive.content).jsonObject
            val envelope = entry.getValue("envelope").jsonObject
            val bytes = Base64.decode(envelope.getValue("descriptorBytesBase64").jsonPrimitive.content, Base64.NO_WRAP)
            val source = Json.parseToJsonElement(bytes.decodeToString()).jsonObject
            val leg = JsonObject(source.getValue("leg").jsonObject + case.getValue("leg").jsonObject)
            val root = JsonObject(source + ("leg" to leg) + case.getValue("descriptor").jsonObject)
            val name = case.getValue("name").jsonPrimitive.content
            if (case.getValue("valid").jsonPrimitive.content == "true") {
                try { JourneySchemaValidator.validate(root) } catch (error: Exception) { throw AssertionError(name, error) }
            } else {
                assertThrows(name, JourneyReleaseAuthenticationException::class.java) { JourneySchemaValidator.validate(root) }
            }
        }
    }

    @Test fun `admits signed local programs with and without a render closure`() {
        for (key in listOf("entry", "renderedEntry")) {
            val entry = fixture.getValue(key).jsonObject
            val envelope = entry.getValue("envelope").jsonObject
            val bytes = Base64.decode(envelope.getValue("descriptorBytesBase64").jsonPrimitive.content, Base64.NO_WRAP)
            val source = Json.parseToJsonElement(bytes.decodeToString()).jsonObject
            val identity = requireNotNull(
                JourneyReleaseIdentity.fromJson(
                    entry.getValue("locator").jsonObject,
                    setOf("legId"),
                ),
            )
            val release = JourneyReleaseVerifier.authenticate(envelope.toString().encodeToByteArray(), keys,
                identity, "a".repeat(64), runtime(source), JourneyReleaseReplayPolicy.Active(0))
            assertArrayEquals(bytes, release.descriptorBytes)
            assertEquals(identity.publishedAtSeq, release.publishedAtSeqToPromote)
            assertEquals("a".repeat(64), release.leg.getValue("id").jsonPrimitive.content)
            if (key == "entry") assertEquals(JsonNull, release.descriptor["render"])
            val pinned = JourneyReleaseVerifier.authenticate(envelope.toString().encodeToByteArray(), keys,
                identity, "a".repeat(64), runtime(source), JourneyReleaseReplayPolicy.Pinned(identity.experienceVersionId,
                    identity.buildId, release.descriptorSha256))
            assertNull(pinned.publishedAtSeqToPromote)
        }
    }

    @Test fun `release admission rejects unsafe artifact paths and mismatched script exports`() {
        val envelope = fixture.getValue("renderedEntry").jsonObject.getValue("envelope").jsonObject
        val source = Json.parseToJsonElement(Base64.decode(envelope.getValue("descriptorBytesBase64")
            .jsonPrimitive.content, Base64.NO_WRAP).decodeToString()).jsonObject
        val render = source.getValue("render").jsonObject
        val riv = JsonObject(render.getValue("riv").jsonObject + ("key" to JsonPrimitive("../outside.riv")))
        assertThrows(JourneyReleaseAuthenticationException::class.java) {
            JourneySchemaValidator.validate(JsonObject(source + ("render" to JsonObject(render + ("riv" to riv)))))
        }
        val invalid = source.toString().replace("\"kind\":\"declarative\"", "\"kind\":\"script\"")
        assertThrows(JourneyReleaseAuthenticationException::class.java) {
            JourneySchemaValidator.validate(Json.parseToJsonElement(invalid).jsonObject)
        }
    }

    @Test fun `authenticated identity leg and replay policies cannot be substituted`() {
        val envelope = fixture.getValue("entry").jsonObject.getValue("envelope").jsonObject
        val source = Json.parseToJsonElement(Base64.decode(envelope.getValue("descriptorBytesBase64")
            .jsonPrimitive.content, Base64.NO_WRAP).decodeToString()).jsonObject
        val identity = requireNotNull(JourneyReleaseIdentity.fromJson(source.getValue("identity").jsonObject))
        val bytes = envelope.toString().encodeToByteArray()
        assertThrows(JourneyReleaseAuthenticationException::class.java) {
            JourneyReleaseVerifier.authenticate(bytes, keys, identity.copy(experienceId = "other"), "a".repeat(64), runtime(source), JourneyReleaseReplayPolicy.Active(0))
        }
        assertThrows(JourneyReleaseAuthenticationException::class.java) {
            JourneyReleaseVerifier.authenticate(bytes, keys, identity, "b".repeat(64), runtime(source), JourneyReleaseReplayPolicy.Active(0))
        }
        assertThrows(JourneyReleaseAuthenticationException::class.java) {
            JourneyReleaseVerifier.authenticate(bytes, keys, identity, "a".repeat(64), runtime(source), JourneyReleaseReplayPolicy.Active(identity.publishedAtSeq + 1))
        }
        assertThrows(JourneyReleaseAuthenticationException::class.java) {
            JourneyReleaseVerifier.authenticate(bytes, keys, identity, "a".repeat(64), runtime(source), JourneyReleaseReplayPolicy.Pinned(identity.experienceVersionId, identity.buildId, "b".repeat(64)))
        }
    }

    @Test fun `shared script bytes count once and field limits use the canonical byte units`() {
        val envelope = fixture.getValue("renderedEntry").jsonObject.getValue("envelope").jsonObject
        val source = Json.parseToJsonElement(Base64.decode(envelope.getValue("descriptorBytesBase64")
            .jsonPrimitive.content, Base64.NO_WRAP).decodeToString()).jsonObject
        val leg = source.getValue("leg").jsonObject
        val render = source.getValue("render").jsonObject
        val legScreen = leg.getValue("screens").jsonArray.single().jsonObject
        val renderScreen = render.getValue("screens").jsonArray.single().jsonObject
        val names = listOf("screen_welcome", "screen_2", "screen_3", "screen_4", "screen_5")
        val scripts = names.map { name -> Json.parseToJsonElement("""{
            "screenId":"$name", "controls":[{"actionId":"continue", "behavior":{"kind":"script"}}],
            "script":{"protocol":"screen-actions", "exportedActionIds":["continue"],
              "artifact":{"key":"screen-behavior/sha256/${"b".repeat(64)}.bin", "sha256":"${"b".repeat(64)}",
                          "sizeBytes":4194304, "contentType":"application/octet-stream"}}
        }""") }
        JourneySchemaValidator.validate(JsonObject(source + mapOf(
            "leg" to JsonObject(leg + ("screens" to JsonArray(names.map { JsonObject(legScreen + ("id" to JsonPrimitive(it))) }))),
            "render" to JsonObject(render + ("screens" to JsonArray(names.map { JsonObject(renderScreen + ("id" to JsonPrimitive(it))) }))),
            "screenBehaviors" to JsonArray(scripts),
        )))
        val longCapture = JsonObject(legScreen + ("responseCaptures" to JsonArray(listOf(JsonPrimitive("x".repeat(129))))))
        assertThrows(JourneyReleaseAuthenticationException::class.java) {
            JourneySchemaValidator.validate(JsonObject(source + ("leg" to JsonObject(leg + ("screens" to JsonArray(listOf(longCapture)))))))
        }
        val steps = leg.getValue("steps").jsonArray.toMutableList()
        steps[0] = JsonObject(steps[0].jsonObject + ("action" to buildJsonObject {
            put("type", "send_event"); put("eventName", "é".repeat(200))
        }))
        assertThrows(JourneyReleaseAuthenticationException::class.java) {
            JourneySchemaValidator.validate(JsonObject(source + ("leg" to JsonObject(leg + ("steps" to JsonArray(steps))))))
        }
    }

    @Test fun `experiment actions require an authored available fallback`() {
        fun action(fallback: String?) = buildJsonObject {
            put("type", "experiment")
            put("experimentId", "checkout-copy")
            fallback?.let { put("fallbackVariantId", it) }
            put(
                "variants",
                JsonArray(
                    listOf(
                        buildJsonObject {
                            put("id", "control")
                            put("isHoldout", false)
                        },
                        buildJsonObject {
                            put("id", "treatment")
                            put("isHoldout", false)
                        },
                    ),
                ),
            )
        }

        JourneyGrammar.action(action("control"), emptySet(), emptySet())
        assertThrows(JourneyReleaseAuthenticationException::class.java) {
            JourneyGrammar.action(action(null), emptySet(), emptySet())
        }
        assertThrows(JourneyReleaseAuthenticationException::class.java) {
            JourneyGrammar.action(action("missing"), emptySet(), emptySet())
        }
    }

    private fun runtime(source: JsonObject): JourneyReleaseSupportedRuntime {
        val requirements = source["requirements"] as? JsonObject
            ?: return JourneyReleaseSupportedRuntime("0.1.0", emptySet(), emptyMap(), 1, 0, "unused", "unused", emptySet())
        fun JsonObject.string(key: String) = getValue(key).jsonPrimitive.content
        val luau = requirements.getValue("luau").jsonObject
        val scene = requirements.getValue("sceneFormat").jsonObject
        val timezone = requirements.getValue("timezoneData").jsonObject
        return JourneyReleaseSupportedRuntime(requirements.string("minimumSdkVersion"), setOf(requirements.string("runtimeRevision")),
            mapOf(luau.string("revision") to luau.getValue("bytecodeVersions").jsonArray.map { it.jsonPrimitive.int }.toSet()),
            scene.getValue("major").jsonPrimitive.int, scene.getValue("minor").jsonPrimitive.int,
            timezone.string("revision"), timezone.string("sha256"),
            requirements.getValue("requiredCapabilities").jsonArray.map { it.jsonPrimitive.content }.toSet())
    }
}
