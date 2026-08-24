package ai.nuxie.sdk.journey

import ai.nuxie.sdk.experiences.AuthenticatedRelease
import ai.nuxie.sdk.experiences.ExperienceReleaseProfile
import ai.nuxie.sdk.experiences.ExperienceReleaseVerifier
import ai.nuxie.sdk.experiences.ReleaseHighWaterStore
import ai.nuxie.sdk.experiences.ReplayPolicy
import ai.nuxie.sdk.experiences.SupportedRuntime
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

/** Authenticated releases admitted for a device-local event trigger. */
internal data class AdmittedJourneyRelease(
    val experienceId: String,
    val experienceVersion: String,
    val triggerEventName: String,
    val reentry: JourneyReentry,
    val settingsTemplate: JsonObject,
)

internal sealed class JourneyReentry {
    data object OneTime : JourneyReentry()
    data object EveryTime : JourneyReentry()
    data class OncePerWindow(val windowMillis: Long) : JourneyReentry()
}

/** Seam between profile-backed release admission and Journey enrollment. */
internal fun interface JourneyReleaseProvider {
    fun releasesFor(distinctId: String, triggerEventName: String): List<AdmittedJourneyRelease>
}

/**
 * Holds only authenticated active release descriptors. If the runtime is
 * unavailable, release admission fails closed and the catalog stays empty.
 */
internal class JourneyReleaseCatalog(
    private val trustedKeys: Map<String, ByteArray>,
    private val highWater: ReleaseHighWaterStore,
    private val supportedRuntime: () -> SupportedRuntime?,
    private val authenticate: (ExperienceReleaseProfile.Entry, SupportedRuntime) -> AuthenticatedRelease? = { entry, runtime ->
        runCatching {
            ExperienceReleaseVerifier.authenticate(
                envelopeBytes = entry.envelopeBytes,
                trustedKeys = trustedKeys,
                expectedIdentity = entry.locator,
                supportedRuntime = runtime,
                replayPolicy = ReplayPolicy.Active(highWater.floor(entry.locator.streamKey)),
            )
        }.getOrNull()
    },
) : JourneyReleaseProvider {
    private val lock = Any()
    private var releasesByDistinctId = emptyMap<String, Map<String, List<AdmittedJourneyRelease>>>()

    fun applyProfile(distinctId: String, body: JsonObject) {
        val runtime = supportedRuntime() ?: run {
            synchronized(lock) { releasesByDistinctId = emptyMap() }
            return
        }
        val profile = ExperienceReleaseProfile.fromProfileBody(body) ?: run {
            synchronized(lock) { releasesByDistinctId = emptyMap() }
            return
        }
        val releases = profile.active.mapNotNull { entry ->
            val authenticated = authenticate(entry, runtime) ?: return@mapNotNull null
            authenticated.publishedAtSeqToPromote?.let {
                highWater.promote(authenticated.identity.streamKey, it)
            }
            admitted(authenticated)
        }
        synchronized(lock) {
            // A device serves one identity at a time: applying a profile
            // replaces this user's releases and drops the prior user's map.
            releasesByDistinctId = mapOf(distinctId to releases.groupBy { it.triggerEventName })
        }
    }

    override fun releasesFor(distinctId: String, triggerEventName: String): List<AdmittedJourneyRelease> =
        synchronized(lock) { releasesByDistinctId[distinctId]?.get(triggerEventName).orEmpty() }

    private fun admitted(release: AuthenticatedRelease): AdmittedJourneyRelease? {
        val enrollment = release.descriptor["enrollment"] as? JsonObject ?: return null
        val trigger = enrollment["trigger"] as? JsonObject ?: return null
        if (trigger.string("type") != "event") return null
        val triggerEvent = trigger.string("eventName") ?: return null
        val lifecycle = release.descriptor["lifecycle"] as? JsonObject ?: return null
        val reentryDoc = lifecycle["reentry"] as? JsonObject ?: return null
        val reentry = when (reentryDoc.string("type")) {
            "one_time" -> JourneyReentry.OneTime
            "every_time" -> JourneyReentry.EveryTime
            "once_per_window" -> JourneyReentry.OncePerWindow(
                reentryDoc.long("windowSeconds")?.times(1_000L) ?: return null,
            )
            else -> return null
        }
        val endOnGoal = lifecycle.string("exitPolicy") in setOf("on_goal", "on_goal_or_stop")
        val goalWindowMillis = lifecycle.long("goalWindowSeconds")?.times(1_000L)
        val template = buildJsonObject {
            put("goal", lifecycle["goal"] ?: kotlinx.serialization.json.JsonNull)
            put("conversion_anchor", JsonPrimitive(lifecycle.string("conversionAnchor") ?: "journey_start"))
            put("goal_window_ms", goalWindowMillis?.let(::JsonPrimitive) ?: kotlinx.serialization.json.JsonNull)
            put("end_on_goal", JsonPrimitive(endOnGoal))
        }
        return AdmittedJourneyRelease(
            experienceId = release.identity.experienceId,
            experienceVersion = release.identity.experienceVersionId,
            triggerEventName = triggerEvent,
            reentry = reentry,
            settingsTemplate = template,
        )
    }

    private fun JsonObject.string(key: String): String? =
        (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

    private fun JsonObject.long(key: String): Long? =
        (this[key] as? JsonPrimitive)?.takeIf { !it.isString }?.content?.toLongOrNull()
}
