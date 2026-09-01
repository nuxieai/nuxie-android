package ai.nuxie.sdk.experiences

import ai.nuxie.sdk.fixtures.FixtureRunner
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class JourneyPlaneProfileTest {
    private fun fixture(): JsonObject {
        val golden = Json.parseToJsonElement(FixtureRunner.fixturesRoot().resolve("journeys/planes/release.json").readText()).jsonObject
        val entry = golden.getValue("entry").jsonObject
        val locator = entry.getValue("locator").jsonObject
        return buildJsonObject {
            put("schemaVersion", "nuxie.journey-plane-profile.v1"); put("status", "ok")
            putJsonObject("delivery") { put("renderBaseUrl", "https://renders.example.com"); put("assetBaseUrl", "https://assets.example.com/") }
            putJsonArray("features") {}
            putJsonObject("facts") {
                putJsonObject("properties") {
                    putJsonObject("missing") { put("present", false) }
                    putJsonObject("null") { put("present", true); put("value", JsonNull) }
                }
                putJsonObject("memberships") { put("opaque", false) }
                putJsonObject("assignments") { put("unfetched", JsonNull) }
            }
            putJsonArray("releases") { add(entry) }
            putJsonArray("armedLegs") { addJsonObject {
                putJsonObject("reference") {
                    put("experienceId", locator.getValue("experienceId")); put("versionId", locator.getValue("experienceVersionId"))
                    put("legId", locator.getValue("legId")); put("descriptorSha256", entry.getValue("envelope").jsonObject.getValue("descriptorSha256"))
                }
                putJsonObject("binding") { put("type", "continue"); put("journeyId", "00000000-0000-7000-8000-000000000001"); put("generation", 7) }
                putJsonObject("entryCondition") { put("type", "app_foregrounded") }
                putJsonObject("context") { putJsonObject("event") {}; putJsonObject("responses") {} }
            } }
        }
    }

    @Test fun `flat delivery preserves opaque facts and exact continuation bindings`() {
        val profile = JourneyPlaneProfile.decode(fixture().toString().encodeToByteArray())
        assertEquals(7, profile.armedLegs.single().binding.getValue("generation").jsonPrimitive.int)
        assertEquals(JsonPrimitive(false), profile.facts.getValue("memberships").jsonObject["opaque"])
        assertEquals(1, profile.releases.size)
    }

    @Test fun `rejects legacy authority and incomplete or duplicate release bindings`() {
        val root = fixture()
        val arm = root.getValue("armedLegs").jsonArray.single()
        for (change in listOf(
            mapOf("mailbox" to JsonArray(emptyList())),
            mapOf("releases" to JsonArray(emptyList())),
            mapOf("armedLegs" to JsonArray(emptyList())),
            mapOf("armedLegs" to JsonArray(listOf(arm, arm))),
        )) assertThrows(ReleaseAuthenticationException::class.java) {
            JourneyPlaneProfile.decode(JsonObject(root + change).toString().encodeToByteArray())
        }
    }

    @Test fun `rejects boolean generations and distinguishes absent properties from unfetched facts`() {
        val original = fixture().toString()
        for (bad in listOf(
            original.replace("\"generation\":7", "\"generation\":true"),
            original.replace("\"generation\":7", "\"generation\":1.5"),
            original.replace("\"present\":false", "\"present\":false,\"value\":0"),
            original.replace("\"opaque\":false", "\"opaque\":0"),
            original.replace("00000000-0000-7000-8000", "00000000-0000-4000-8000"),
        )) assertThrows(ReleaseAuthenticationException::class.java) { JourneyPlaneProfile.decode(bad.encodeToByteArray()) }
    }

    @Test fun `validates locator envelope and delivery origins before replacement`() {
        val original = fixture().toString()
        for (bad in listOf(
            original.replace("\"versionNumber\":7", "\"versionNumber\":0"),
            original.replace("\"environment\":\"live\"", "\"environment\":\"other\""),
            original.replace("2026-08-12T12:00:00.000Z", "2026-02-31T12:00:00.000Z"),
            original.replace("\"algorithm\":\"ed25519\"", "\"algorithm\":\"rsa\""),
            original.replace("https://assets.example.com/", "https://name:password@assets.example.com/"),
            original.replace("https://assets.example.com/", "https://assets.example.com/base"),
            original.replace("https://assets.example.com/", "https://assets.example.com/?query=1"),
            original.replace("https://assets.example.com/", "http://assets.example.com/"),
        )) assertThrows(ReleaseAuthenticationException::class.java) { JourneyPlaneProfile.decode(bad.encodeToByteArray()) }
    }

    @Test fun `cached runs may receive fresh facts without being rearmed`() {
        val root = JsonObject(fixture() + mapOf("armedLegs" to JsonArray(emptyList()), "releases" to JsonArray(emptyList())))
        val profile = JourneyPlaneProfile.decode(root.toString().encodeToByteArray())
        assertTrue(profile.armedLegs.isEmpty())
        assertEquals(JsonPrimitive(false), profile.facts.getValue("memberships").jsonObject["opaque"])
    }

    @Test fun `accepts canonical credit system features and rejects unknown intervals`() {
        val feature = buildJsonObject {
            put("id", "credits"); put("type", "creditSystem"); put("balance", 20)
            put("unlimited", false); put("nextResetAt", JsonNull); put("interval", "semiAnnual")
        }
        val root = JsonObject(fixture() + ("features" to JsonArray(listOf(feature))))
        assertEquals(feature, JourneyPlaneProfile.decode(root.toString().encodeToByteArray()).features.single())
        assertThrows(ReleaseAuthenticationException::class.java) {
            JourneyPlaneProfile.decode(root.toString().replace("semiAnnual", "fortnight").encodeToByteArray())
        }
    }

    @Test fun `entry IR rejects malformed expression payloads and accepts precise locator timestamps`() {
        val original = fixture().toString()
        for (expr in listOf("{}", "{\"type\":\"Bool\",\"value\":\"false\"}", "{\"type\":\"Number\",\"value\":false}")) {
            val invalid = original.replace("\"type\":\"app_foregrounded\"", "\"type\":\"app_foregrounded\",\"condition\":{\"ir_version\":1,\"expr\":$expr}")
            assertThrows(ReleaseAuthenticationException::class.java) { JourneyPlaneProfile.decode(invalid.encodeToByteArray()) }
        }
        val precise = original.replace("2026-08-12T12:00:00.000Z", "2026-08-12T12:00:00.123456Z")
        assertEquals("2026-08-12T12:00:00.123456Z", JourneyPlaneProfile.decode(precise.encodeToByteArray()).releases.single().locator.releaseCreatedAt)
    }

    @Test fun `canonical numbers cannot duplicate arm authority or smuggle non JSON facts`() {
        val root = fixture()
        val arm = root.getValue("armedLegs").jsonArray.single().jsonObject
        val equivalent = JsonObject(arm + ("binding" to JsonObject(arm.getValue("binding").jsonObject + ("generation" to JsonPrimitive(7.0)))))
        assertThrows(ReleaseAuthenticationException::class.java) {
            JourneyPlaneProfile.decode(JsonObject(root + ("armedLegs" to JsonArray(listOf(arm, equivalent)))).toString().encodeToByteArray())
        }
        for (invalid in listOf("NaN", "Infinity", "unquoted_value", "1e999", "01", "+1")) {
            val body = root.toString().replace("\"present\":true,\"value\":null", "\"present\":true,\"value\":$invalid")
            assertThrows(invalid, ReleaseAuthenticationException::class.java) { JourneyPlaneProfile.decode(body.encodeToByteArray()) }
        }
    }
}
