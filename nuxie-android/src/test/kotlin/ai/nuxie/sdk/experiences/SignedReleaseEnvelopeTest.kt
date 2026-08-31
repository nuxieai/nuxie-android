package ai.nuxie.sdk.experiences

import ai.nuxie.sdk.fixtures.FixtureRunner
import android.util.Base64
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SignedReleaseEnvelopeTest {
    private val fixture = Json.parseToJsonElement(
        FixtureRunner.fixturesRoot().resolve("journeys/planes/release.json").readText()).jsonObject
    private val keys = mapOf("TEST_ONLY_DEV_KEYPAIR" to Base64.decode(
        fixture.getValue("publicKeyBase64").jsonPrimitive.content, Base64.NO_WRAP))
    private fun envelope(entry: String = "entry") = fixture.getValue(entry).jsonObject.getValue("envelope").jsonObject

    @Test fun `both device leg envelopes authenticate their exact publisher bytes`() {
        for (entry in listOf("entry", "renderedEntry")) {
            val envelope = envelope(entry)
            val verified = SignedReleaseEnvelope.authenticate(envelope.toString().encodeToByteArray(), keys,
                SignedReleaseEnvelope.Format.DEVICE_LEG)
            assertEquals(envelope.getValue("descriptorSha256").jsonPrimitive.content, verified.sha256)
            assertArrayEquals(Base64.decode(envelope.getValue("descriptorBytesBase64").jsonPrimitive.content, Base64.NO_WRAP),
                verified.descriptorBytes)
        }
    }

    @Test fun `media type substitution cannot cross the signature domain`() {
        val changed = JsonObject(envelope() + ("mediaType" to JsonPrimitive(ExperienceReleaseLimits.MEDIA_TYPE)))
        assertThrows(ReleaseAuthenticationException::class.java) {
            SignedReleaseEnvelope.authenticate(changed.toString().encodeToByteArray(), keys,
                SignedReleaseEnvelope.Format.EXPERIENCE)
        }
    }

    @Test fun `integral JSON numbers do not require a particular lexical spelling`() {
        val original = envelope()
        val signature = JsonObject(original.getValue("signature").jsonObject + ("version" to JsonPrimitive(1.0)))
        val changed = JsonObject(original + mapOf("signature" to signature,
            "descriptorSizeBytes" to JsonPrimitive(original.getValue("descriptorSizeBytes").jsonPrimitive.content.toDouble())))
        val verified = SignedReleaseEnvelope.authenticate(changed.toString().encodeToByteArray(), keys,
            SignedReleaseEnvelope.Format.DEVICE_LEG)
        assertEquals(original.getValue("descriptorSha256").jsonPrimitive.content, verified.sha256)
    }

    @Test fun `duplicate envelope keys are rejected after JSON escape decoding`() {
        for (duplicate in listOf("mediaType", "media\\u0054ype")) {
            val text = envelope().toString().replaceFirst("{", "{\"$duplicate\":\"application/vnd.nuxie.device-leg+json\",")
            assertThrows(ReleaseAuthenticationException::class.java) {
                SignedReleaseEnvelope.authenticate(text.encodeToByteArray(), keys, SignedReleaseEnvelope.Format.DEVICE_LEG)
            }
        }
    }

    @Test fun `deeply nested envelope input fails within a bounded parser depth`() {
        val text = "[".repeat(200) + "0" + "]".repeat(200)
        assertThrows(ReleaseAuthenticationException::class.java) {
            SignedReleaseEnvelope.authenticate(text.encodeToByteArray(), keys, SignedReleaseEnvelope.Format.DEVICE_LEG)
        }
    }

    @Test fun `a recomputed hash does not authenticate changed descriptor bytes`() {
        val envelope = envelope()
        val bytes = Base64.decode(envelope.getValue("descriptorBytesBase64").jsonPrimitive.content, Base64.NO_WRAP)
        bytes[100] = (bytes[100].toInt() xor 1).toByte()
        val digest = java.security.MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        val changed = JsonObject(envelope + mapOf("descriptorSha256" to JsonPrimitive(digest),
            "descriptorBytesBase64" to JsonPrimitive(Base64.encodeToString(bytes, Base64.NO_WRAP))))
        assertThrows(ReleaseAuthenticationException::class.java) {
            SignedReleaseEnvelope.authenticate(changed.toString().encodeToByteArray(), keys, SignedReleaseEnvelope.Format.DEVICE_LEG)
        }
    }
}
