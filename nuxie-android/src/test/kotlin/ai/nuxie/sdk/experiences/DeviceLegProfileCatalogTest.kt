package ai.nuxie.sdk.experiences

import ai.nuxie.sdk.fixtures.FixtureRunner
import android.util.Base64
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class DeviceLegProfileCatalogTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val fixture = Json.parseToJsonElement(
        FixtureRunner.fixturesRoot().resolve("journeys/planes/release.json").readText(),
    ).jsonObject
    private val entry get() = fixture.getValue("entry").jsonObject
    private val keys get() = mapOf("TEST_ONLY_DEV_KEYPAIR" to Base64.decode(
        fixture.getValue("publicKeyBase64").jsonPrimitive.content,
        Base64.NO_WRAP,
    ))

    @Before fun clearReplayStore() {
        context.getSharedPreferences("nuxie_release_high_water", 0).edit().clear().commit()
    }

    @Test fun `publishes a complete authenticated plane profile only at commit`() {
        val highWater = ReleaseHighWaterStore(context)
        val catalog = DeviceLegProfileCatalog(keys, highWater) { runtime() }
        val prepared = catalog.prepare(profile())
        assertNull(catalog.snapshot("customer"))

        catalog.commit("customer", prepared)
        val snapshot = requireNotNull(catalog.snapshot("customer"))
        val release = snapshot.releasesByDigest.values.single()
        assertEquals(entry.getValue("locator").jsonObject.getValue("legId").jsonPrimitive.content, release.leg.getValue("id").jsonPrimitive.content)
        assertEquals(snapshot.profile.armedLegs.single().reference.getValue("descriptorSha256"), JsonPrimitive(release.descriptorSha256))
        assertNull(catalog.snapshot("other"))
        assertEquals(release.identity.releaseSequence, highWater.floor(release.identity.streamKey))
    }

    @Test fun `a rejected replacement cannot mutate current authority or replay floors`() {
        val highWater = ReleaseHighWaterStore(context)
        val catalog = DeviceLegProfileCatalog(keys, highWater) { runtime() }
        catalog.commit("customer", catalog.prepare(profile()))
        val current = requireNotNull(catalog.snapshot("customer"))
        val release = current.releasesByDigest.values.single()
        val floor = highWater.floor(release.identity.streamKey)

        val signature = entry.getValue("envelope").jsonObject.getValue("signature").jsonObject
        val encoded = signature.getValue("signatureBase64").jsonPrimitive.content
        val changed = (if (encoded.first() == 'A') "B" else "A") + encoded.drop(1)
        val badEnvelope = JsonObject(entry.getValue("envelope").jsonObject +
            ("signature" to JsonObject(signature + ("signatureBase64" to JsonPrimitive(changed)))))
        val badEntry = JsonObject(entry + ("envelope" to badEnvelope))

        assertThrows(ReleaseAuthenticationException::class.java) {
            catalog.prepare(profile(badEntry))
        }
        assertEquals(current, catalog.snapshot("customer"))
        assertEquals(floor, highWater.floor(release.identity.streamKey))
    }

    @Test fun `a prepared profile cannot replace a newer replay floor`() {
        val highWater = ReleaseHighWaterStore(context)
        val catalog = DeviceLegProfileCatalog(keys, highWater) { runtime() }
        val prepared = catalog.prepare(profile())
        val identity = ExperienceReleaseIdentity.fromJson(
            entry.getValue("locator").jsonObject,
            setOf("legId"),
        )!!
        highWater.promote(identity.streamKey, identity.releaseSequence + 1)

        assertThrows(ReleaseAuthenticationException::class.java) {
            catalog.commit("customer", prepared)
        }
        assertNull(catalog.snapshot("customer"))
        assertEquals(identity.releaseSequence + 1, highWater.floor(identity.streamKey))
    }

    @Test fun `a continuation-only release remains pinned behind a newer active floor`() {
        val highWater = ReleaseHighWaterStore(context)
        val catalog = DeviceLegProfileCatalog(keys, highWater) { runtime() }
        val identity = ExperienceReleaseIdentity.fromJson(
            entry.getValue("locator").jsonObject,
            setOf("legId"),
        )!!
        highWater.promote(identity.streamKey, identity.releaseSequence + 1)
        val continuation = buildJsonObject {
            put("type", "continue")
            put("journeyId", "00000000-0000-7000-8000-000000000001")
            put("generation", 4)
        }

        catalog.commit("customer", catalog.prepare(profile(binding = continuation)))

        assertEquals("continue", requireNotNull(catalog.snapshot("customer"))
            .profile.armedLegs.single().binding.getValue("type").jsonPrimitive.content)
        assertEquals(identity.releaseSequence + 1, highWater.floor(identity.streamKey))
    }

    private fun profile(
        releaseEntry: JsonObject = entry,
        binding: JsonObject = buildJsonObject { put("type", "new") },
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
                putJsonObject("properties") { putJsonObject("ready") { put("present", true); put("value", true) } }
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
                    putJsonObject("entryCondition") { put("type", "app_foregrounded") }
                    putJsonObject("context") { putJsonObject("event") {}; putJsonObject("responses") {} }
                }
            }
        }
    }

    private fun runtime(): SupportedRuntime {
        val envelope = entry.getValue("envelope").jsonObject
        val descriptor = Json.parseToJsonElement(Base64.decode(
            envelope.getValue("descriptorBytesBase64").jsonPrimitive.content,
            Base64.NO_WRAP,
        ).decodeToString()).jsonObject
        val requirements = descriptor["requirements"] as? JsonObject
            ?: return SupportedRuntime("0.1.0", emptySet(), emptyMap(), 1, 0, "unused", "unused", emptySet())
        fun JsonObject.string(key: String) = getValue(key).jsonPrimitive.content
        val luau = requirements.getValue("luau").jsonObject
        val scene = requirements.getValue("sceneFormat").jsonObject
        val timezone = requirements.getValue("timezoneData").jsonObject
        return SupportedRuntime(
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
