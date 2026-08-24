package ai.nuxie.sdk.experiences

import ai.nuxie.sdk.fixtures.FixtureRunner
import android.util.Base64
import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class ReleaseAuthenticationTest {
    private val fixtureDir = File(FixtureRunner.fixturesRoot(), "experience-release-descriptor")

    private fun envelopeBytes(): ByteArray = File(fixtureDir, "envelope.json").readBytes()

    private fun trustedKeys(): Map<String, ByteArray> =
        Json.parseToJsonElement(File(fixtureDir, "trusted-public-keys.json").readText())
            .jsonObject.mapValues { (_, value) ->
                Base64.decode(value.jsonPrimitive.content, Base64.NO_WRAP)
            }

    private fun expectedIdentity(): ExperienceReleaseIdentity =
        requireNotNull(
            ExperienceReleaseIdentity.fromJson(
                Json.parseToJsonElement(File(fixtureDir, "expected-identity.json").readText())
                    .jsonObject,
            ),
        )

    /** The golden descriptor's requirements, satisfied. */
    private fun supportedRuntime(): SupportedRuntime = SupportedRuntime(
        currentSdkVersion = "1.2.0",
        supportedRuntimeRevisions = setOf("runtime-1"),
        supportedLuauRevisions = mapOf("luau-1" to setOf(1, 2)),
        sceneFormatMajor = 1,
        sceneFormatMinor = 0,
        timezoneDataRevision = "2026c",
        timezoneDataSha256 = "d4ad5c12a6be491076f333c9b4f96f60cb8ab552495bbfae0d8cdc9730ecb198",
        supportedCapabilities = setOf("rive", "text-input"),
    )

    @Test
    fun goldenEnvelopeAuthenticates() {
        val release = ExperienceReleaseVerifier.authenticate(
            envelopeBytes = envelopeBytes(),
            trustedKeys = trustedKeys(),
            expectedIdentity = expectedIdentity(),
            supportedRuntime = supportedRuntime(),
            replayPolicy = ReplayPolicy.Active(minimumPublishedAtSeq = 0),
        )
        assertEquals("TEST_ONLY_DEV_KEYPAIR", release.keyId)
        assertEquals(42L, release.publishedAtSeqToPromote)
        assertEquals("experience_golden", release.identity.experienceId)
        assertTrue(release.descriptor.containsKey("render"))
    }

    @Test
    fun tamperedDescriptorByteFailsSignature() {
        val envelope = Json.parseToJsonElement(envelopeBytes().decodeToString()).jsonObject
        val raw = Base64.decode(
            envelope.getValue("descriptorBytesBase64").jsonPrimitive.content,
            Base64.NO_WRAP,
        )
        raw[100] = (raw[100].toInt() xor 1).toByte()
        val tamperedB64 = Base64.encodeToString(raw, Base64.NO_WRAP)
        // Recompute sha/size so ONLY the signature protects the content.
        val sha = java.security.MessageDigest.getInstance("SHA-256").digest(raw)
            .joinToString("") { "%02x".format(it) }
        val tampered = buildString {
            append(envelopeBytes().decodeToString())
        }
            .replace(envelope.getValue("descriptorBytesBase64").jsonPrimitive.content, tamperedB64)
            .replace(envelope.getValue("descriptorSha256").jsonPrimitive.content, sha)
            .encodeToByteArray()

        val failure = assertThrows(ReleaseAuthenticationException::class.java) {
            ExperienceReleaseVerifier.authenticate(
                envelopeBytes = tampered,
                trustedKeys = trustedKeys(),
                expectedIdentity = expectedIdentity(),
                supportedRuntime = supportedRuntime(),
                replayPolicy = ReplayPolicy.Active(0),
            )
        }
        assertTrue(failure.message!!.contains("invalid signature"))
    }

    @Test
    fun identityMismatchFails() {
        val wrongIdentity = expectedIdentity().copy(experienceId = "someone_else")
        val failure = assertThrows(ReleaseAuthenticationException::class.java) {
            ExperienceReleaseVerifier.authenticate(
                envelopeBytes(), trustedKeys(), wrongIdentity, supportedRuntime(),
                ReplayPolicy.Active(0),
            )
        }
        assertTrue(failure.message!!.contains("identity mismatch"))
    }

    @Test
    fun unknownKeyFails() {
        val failure = assertThrows(ReleaseAuthenticationException::class.java) {
            ExperienceReleaseVerifier.authenticate(
                envelopeBytes(), emptyMap(), expectedIdentity(), supportedRuntime(),
                ReplayPolicy.Active(0),
            )
        }
        assertTrue(failure.message!!.contains("unknown signing key"))
    }

    @Test
    fun replayBelowFloorFails() {
        val failure = assertThrows(ReleaseAuthenticationException::class.java) {
            ExperienceReleaseVerifier.authenticate(
                envelopeBytes(), trustedKeys(), expectedIdentity(), supportedRuntime(),
                ReplayPolicy.Active(minimumPublishedAtSeq = 43),
            )
        }
        assertTrue(failure.message!!.contains("replay rejected"))
    }

    @Test
    fun pinnedPolicyMatchesExactDigest() {
        val envelope = Json.parseToJsonElement(envelopeBytes().decodeToString()).jsonObject
        val release = ExperienceReleaseVerifier.authenticate(
            envelopeBytes(), trustedKeys(), expectedIdentity(), supportedRuntime(),
            ReplayPolicy.Pinned(
                experienceVersionId = "version_golden",
                buildId = "build_golden",
                expectedDescriptorSha256 = envelope.getValue("descriptorSha256").jsonPrimitive.content,
            ),
        )
        assertEquals(null, release.publishedAtSeqToPromote)
    }

    @Test
    fun missingCapabilityFailsClosed() {
        val limited = supportedRuntime().copy(supportedCapabilities = setOf("rive"))
        val failure = assertThrows(ReleaseAuthenticationException::class.java) {
            ExperienceReleaseVerifier.authenticate(
                envelopeBytes(), trustedKeys(), expectedIdentity(), limited,
                ReplayPolicy.Active(0),
            )
        }
        assertTrue(failure.message!!.contains("unsupported capabilities"))
    }

    @Test
    fun olderSdkThanMinimumFails() {
        val old = supportedRuntime().copy(currentSdkVersion = "1.1.9")
        assertThrows(ReleaseAuthenticationException::class.java) {
            ExperienceReleaseVerifier.authenticate(
                envelopeBytes(), trustedKeys(), expectedIdentity(), old,
                ReplayPolicy.Active(0),
            )
        }
        Unit
    }

    @Test
    fun highWaterStoreIsMonotonic() {
        val store = ReleaseHighWaterStore(RuntimeEnvironment.getApplication())
        val key = expectedIdentity().streamKey
        assertEquals(0L, store.floor(key))
        store.promote(key, 42)
        assertEquals(42L, store.floor(key))
        store.promote(key, 41)
        assertEquals(42L, store.floor(key))
        store.promote(key, 43)
        assertEquals(43L, store.floor(key))
    }
}
