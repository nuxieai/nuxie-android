package ai.nuxie.sdk.testsupport

import ai.nuxie.sdk.experiences.AcquiredJourneyRelease
import ai.nuxie.sdk.experiences.AuthenticatedJourneyRelease
import ai.nuxie.sdk.experiences.JourneyReleaseIdentity
import ai.nuxie.sdk.experiences.JourneyReleaseReplayPolicy
import ai.nuxie.sdk.experiences.JourneyReleaseSupportedRuntime
import ai.nuxie.sdk.experiences.JourneyReleaseVerifier
import ai.nuxie.sdk.fixtures.FixtureRunner
import ai.nuxie.sdk.presentation.JourneyPresentationFences
import android.util.Base64
import java.io.Closeable
import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

internal fun acquired(
    identity: JourneyReleaseIdentity,
    lease: Closeable,
    extraArtifacts: Map<String, File> = emptyMap(),
): AcquiredJourneyRelease {
    val file = File.createTempFile("journey-presentation-", ".riv").apply {
        writeBytes(byteArrayOf(1))
        deleteOnExit()
    }
    return AcquiredJourneyRelease(
        identity = identity,
        artifactsByKey = mapOf("renders/main.riv" to file) + extraArtifacts,
        sceneFile = file,
        protection = lease,
    )
}

internal fun renderedJourneyRelease(
    file: String = "release.json",
    entryKey: String = "renderedEntry",
): AuthenticatedJourneyRelease {
    val fixture = Json.parseToJsonElement(
        FixtureRunner.fixturesRoot().resolve("journeys/planes/$file").readText(),
    ).jsonObject
    val entry = fixture.getValue(entryKey).jsonObject
    val envelope = entry.getValue("envelope").jsonObject
    val descriptor = Json.parseToJsonElement(
        Base64.decode(
            envelope.getValue("descriptorBytesBase64").jsonPrimitive.content,
            Base64.NO_WRAP,
        ).decodeToString(),
    ).jsonObject
    val identity = requireNotNull(
        JourneyReleaseIdentity.fromJson(
            entry.getValue("locator").jsonObject,
            additionalKeys = setOf("legId"),
        ),
    )
    val requirements = descriptor.getValue("requirements").jsonObject
    val luau = requirements.getValue("luau").jsonObject
    val scene = requirements.getValue("sceneFormat").jsonObject
    val timezone = requirements.getValue("timezoneData").jsonObject
    val runtime = JourneyReleaseSupportedRuntime(
        currentSdkVersion = requirements.getValue("minimumSdkVersion").jsonPrimitive.content,
        supportedRuntimeRevisions = setOf(
            requirements.getValue("runtimeRevision").jsonPrimitive.content,
        ),
        supportedLuauRevisions = mapOf(
            luau.getValue("revision").jsonPrimitive.content to
                luau.getValue("bytecodeVersions").jsonArray
                    .map { it.jsonPrimitive.int }
                    .toSet(),
        ),
        sceneFormatMajor = scene.getValue("major").jsonPrimitive.int,
        sceneFormatMinor = scene.getValue("minor").jsonPrimitive.int,
        timezoneDataRevision = timezone.getValue("revision").jsonPrimitive.content,
        timezoneDataSha256 = timezone.getValue("sha256").jsonPrimitive.content,
        supportedCapabilities = requirements.getValue("requiredCapabilities").jsonArray
            .map { it.jsonPrimitive.content }
            .toSet(),
    )
    val trustedKeys = mapOf(
        "TEST_ONLY_DEV_KEYPAIR" to Base64.decode(
            fixture.getValue("publicKeyBase64").jsonPrimitive.content,
            Base64.NO_WRAP,
        ),
    )
    return JourneyReleaseVerifier.authenticate(
        envelopeBytes = envelope.toString().encodeToByteArray(),
        trustedKeys = trustedKeys,
        expectedIdentity = identity,
        expectedLegId = "a".repeat(64),
        supportedRuntime = runtime,
        replayPolicy = JourneyReleaseReplayPolicy.Active(0),
    )
}

internal fun testPresentationFences(): JourneyPresentationFences {
    val identity = object : ai.nuxie.sdk.identity.IdentityProvider {
        override fun distinctId() = "customer-1"
        override fun anonymousId() = "anonymous"
        override fun rawDistinctId() = "customer-1"
        override val isIdentified = true
    }
    val execution = ai.nuxie.sdk.journey.JourneyExecutionFence()
    return JourneyPresentationFences(identity, identity.captureScope(), execution, execution.token())
}

