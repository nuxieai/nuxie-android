package ai.nuxie.sdk.experiences

import ai.nuxie.sdk.NuxieEnvironment
import ai.nuxie.sdk.fixtures.FixtureRunner
import ai.nuxie.sdk.presentation.ExperiencePresentationException
import android.util.Base64
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class JourneyPreviewProfileTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val entry = Json.parseToJsonElement(
        FixtureRunner.fixturesRoot().resolve("journeys/rendered-purchase-navigation/release-entry.json").readText(),
    ).jsonObject

    @Before fun clear() {
        context.getSharedPreferences("nuxie_journey_release_high_water", 0).edit().clear().commit()
    }

    @Test fun signedPreviewUsesTheEntryScreenOrAnExplicitAuthenticatedScreen() {
        val selected = authenticate()
        assertEquals("screen", selected.screenId)
        assertEquals("details", authenticate(screenId = "details").screenId)
        assertEquals(entry.getValue("envelope").jsonObject.getValue("descriptorSha256").jsonPrimitive.content,
            selected.release.descriptorSha256)
        assertThrows(ExperiencePresentationException::class.java) { authenticate(screenId = "missing") }
    }

    @Test fun previewNeitherReadsNorAdvancesCustomerReplayAuthority() {
        val selected = authenticate()
        val identity = selected.release.identity
        val customer = JourneyReleaseHighWaterStore(context)
        customer.admitBatch(mapOf(identity.streamKey to identity.copy(publishedAtSeq = identity.publishedAtSeq + 1)))
        assertEquals(selected.release.descriptorSha256, authenticate().release.descriptorSha256)
        assertEquals(identity.publishedAtSeq + 1, customer.floor(identity.streamKey))
        val before = context.getSharedPreferences("nuxie_journey_release_high_water", 0).all.toMap()
        authenticate()
        assertEquals(before, context.getSharedPreferences("nuxie_journey_release_high_water", 0).all)
    }

    @Test fun previewRequiresAnExactReleaseWithTrustedSignatureAndAvailableRuntime() {
        val valid = profile()
        for (field in listOf("armedLegs", "releases")) {
            assertThrows(JourneyReleaseAuthenticationException::class.java) {
                authenticate(JsonObject(valid + (field to JsonArray(emptyList()))))
            }
            assertThrows(JourneyReleaseAuthenticationException::class.java) {
                authenticate(JsonObject(valid + (field to JsonArray(valid.getValue(field).jsonArray.toList() + valid.getValue(field).jsonArray.toList()))))
            }
        }
        assertThrows(JourneyReleaseAuthenticationException::class.java) {
            JourneyPreviewProfile.authenticate(valid.toString().encodeToByteArray(), NuxieEnvironment.PRODUCTION, runtime())
        }
        assertThrows(JourneyReleaseAuthenticationException::class.java) {
            JourneyPreviewProfile.authenticate(valid.toString().encodeToByteArray(), NuxieEnvironment.DEVELOPMENT, null)
        }
        val envelope = entry.getValue("envelope").jsonObject
        val tampered = JsonObject(entry + ("envelope" to JsonObject(envelope + ("descriptorSha256" to JsonPrimitive("0".repeat(64))))))
        assertThrows(JourneyReleaseAuthenticationException::class.java) { authenticate(profile(tampered)) }
    }

    private fun authenticate(body: JsonObject = profile(), screenId: String? = null) =
        JourneyPreviewProfile.authenticate(body.toString().encodeToByteArray(), NuxieEnvironment.DEVELOPMENT, runtime(), screenId)

    private fun profile(
        releaseEntry: JsonObject = entry,
        binding: JsonObject = buildJsonObject { put("type", "new") },
        entryCondition: JsonObject = buildJsonObject { put("type", "app_foregrounded") },
    ): JsonObject {
        val locator = releaseEntry.getValue("locator").jsonObject
        val envelope = releaseEntry.getValue("envelope").jsonObject
        return buildJsonObject {
            put("schemaVersion", "nuxie.journey-plane-profile.v1")
            put("status", "ok")
            putJsonObject("delivery") {
                put("renderBaseUrl", "https://renders.example.com/")
                put("assetBaseUrl", "https://assets.example.com/")
            }
            putJsonArray("features") {}
            putJsonObject("facts") {
                putJsonObject("properties") {}
                putJsonObject("memberships") {}
                putJsonObject("assignments") {}
            }
            put("releases", JsonArray(listOf(releaseEntry)))
            putJsonArray("armedLegs") {
                addJsonObject {
                    putJsonObject("reference") {
                        put("experienceId", locator.getValue("experienceId"))
                        put("versionId", locator.getValue("experienceVersionId"))
                        put("legId", locator.getValue("legId"))
                        put("descriptorSha256", envelope.getValue("descriptorSha256"))
                    }
                    put("binding", binding)
                    put("entryCondition", entryCondition)
                    putJsonObject("context") { putJsonObject("event") {}; putJsonObject("responses") {} }
                }
            }
        }
    }

    private fun runtime(): JourneyReleaseSupportedRuntime {
        val envelope = entry.getValue("envelope").jsonObject
        val descriptor = Json.parseToJsonElement(Base64.decode(
            envelope.getValue("descriptorBytesBase64").jsonPrimitive.content,
            Base64.NO_WRAP,
        ).decodeToString()).jsonObject
        val requirements = descriptor["requirements"] as? JsonObject
            ?: return JourneyReleaseSupportedRuntime("0.1.0", emptySet(), emptyMap(), 1, 0, "unused", "unused", emptySet())
        fun JsonObject.string(key: String) = getValue(key).jsonPrimitive.content
        val luau = requirements.getValue("luau").jsonObject
        val scene = requirements.getValue("sceneFormat").jsonObject
        val timezone = requirements.getValue("timezoneData").jsonObject
        return JourneyReleaseSupportedRuntime(
            requirements.string("minimumSdkVersion"),
            setOf(requirements.string("runtimeRevision")),
            mapOf(luau.string("revision") to luau.getValue("bytecodeVersions").jsonArray.map { it.jsonPrimitive.int }.toSet()),
            scene.getValue("major").jsonPrimitive.int,
            scene.getValue("minor").jsonPrimitive.int,
            timezone.string("revision"),
            timezone.string("sha256"),
            requirements.getValue("requiredCapabilities").jsonArray.map { it.jsonPrimitive.content }.toSet(),
        )
    }
}
