package ai.nuxie.sdk.experiences

import ai.nuxie.sdk.NuxieEnvironment
import ai.nuxie.sdk.network.ProfileDeliveryAuthority
import ai.nuxie.sdk.presentation.AuthenticatedPresentationScreen
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** An exact, authenticated Companion selection, independent of customer SDK state. */
internal class JourneyPreviewProfile private constructor(
    val release: AuthenticatedJourneyRelease,
    val delivery: JourneyReleaseDelivery,
    val screenId: String,
) {
    companion object {
        fun authenticate(
            profileBytes: ByteArray,
            environment: NuxieEnvironment,
            supportedRuntime: JourneyReleaseSupportedRuntime?,
            initialScreenId: String? = null,
        ): JourneyPreviewProfile {
            // Decode before parsing again for catalog admission: the byte limit is part
            // of the untrusted profile boundary, including rejected multi-release input.
            val profile = JourneyPlaneProfile.decode(profileBytes)
            if (profile.releases.size != 1 || profile.armedLegs.size != 1) {
                throw JourneyReleaseAuthenticationException("Preview requires one release and one armed leg")
            }
            val locator = profile.releases.single().locator
            val catalog = JourneyProfileCatalog(
                trustedKeys = JourneyTrustRoots.keys(environment),
                highWater = JourneyReleaseHighWaterStore.ephemeral(),
                supportedRuntime = { supportedRuntime },
            )
            val prepared = catalog.prepare(
                JourneyReleaseEnvelope.parseObject(profileBytes),
                ProfileDeliveryAuthority(locator.appId, locator.environment),
            )
            catalog.commit("companion-preview", prepared)
            val digest = profile.armedLegs.single().reference.getValue("descriptorSha256").jsonPrimitive.content
            val release = prepared.snapshot.releasesByDigest.getValue(digest)
            val screenId = initialScreenId ?: entryScreen(release)
            AuthenticatedPresentationScreen.resolve(release, screenId)
            return JourneyPreviewProfile(release, profile.delivery, screenId)
        }

        private fun entryScreen(release: AuthenticatedJourneyRelease): String {
            val leg = release.leg
            val entryId = leg.getValue("entryStepId")
            val entry = leg.getValue("steps").jsonArray.map { it.jsonObject }
                .singleOrNull { it["id"] == entryId }
            val action = entry?.get("action") as? JsonObject
            val screen = action?.get("screenId") as? JsonPrimitive
            if (screen?.isString == true) return screen.content
            return leg.getValue("screens").jsonArray.singleOrNull()?.jsonObject
                ?.getValue("id")?.jsonPrimitive?.content
                ?: throw JourneyReleaseAuthenticationException("Preview requires an explicit initial screen")
        }
    }
}
