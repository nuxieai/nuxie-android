package ai.nuxie.sdk.profile

import ai.nuxie.sdk.features.FeatureInfo
import ai.nuxie.sdk.experiences.DeviceLegProfileCatalog
import ai.nuxie.sdk.identity.IdentityProvider
import ai.nuxie.sdk.identity.IdentityScope
import ai.nuxie.sdk.identity.UserTransitionCoordinator
import ai.nuxie.sdk.network.NuxieApi
import ai.nuxie.sdk.segments.SegmentService
import android.content.Context
import android.util.Log
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.onTimeout
import kotlinx.coroutines.selects.select
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject

/**
 * Profile fetch + cache, porting the iOS `ProfileService` data-plane policy:
 *
 * - 24-hour validity window: an expired cached profile is EVICTED, never
 *   served (evict-don't-serve-stale).
 * - Conditional refetch via the scoped ETag validator (locale-keyed).
 * - Atomic admission by identity decision, effective locale, and monotonic
 *   profile generation before every locale-scoped mutation.
 * - Canonical plane profiles synchronize at launch and foreground only;
 *   legacy profiles retain the 30-minute transition refresh. On user change
 *   a fresh-enough cache (< 5 min) is served without an immediate network hit.
 * - Locale-scoped profile cache, segment membership, release authority, and
 *   visible Features share one admission. User properties and server facts
 *   are customer-scoped instead; Android has no profile-mailbox consumer in
 *   this slice.
 *
 * The profile body is retained as raw JSON (duplicate-key validated at the
 * network layer); typed models arrive with their consumers.
 */
internal class ProfileService(
    context: Context,
    private val api: NuxieApi,
    private val identity: IdentityProvider,
    private val segments: SegmentService,
    private val applyUserProperties: (Map<String, Any?>) -> Unit,
    private val applyJourneyProfile: (distinctId: String, body: JsonObject) -> Unit = { _, _ -> },
    private val deviceLegProfiles: DeviceLegProfileCatalog? = null,
    private val applyJourneyFacts: suspend (
        distinctId: String,
        body: JsonObject,
    ) -> Unit = { _, _ -> },
    private val stageFeatureProfile: (
        distinctId: String,
        body: JsonObject,
        purchaseRevision: Long,
        authoritativeRevision: Long,
        isCurrent: () -> Boolean,
    ) -> FeatureInfo.Mutation? = { _, _, _, _, _ -> null },
    private val publishFeatureProfile: suspend (FeatureInfo.Mutation?) -> Unit = {},
    private val captureFeaturePurchaseRevision: () -> Long = { 0L },
    private val reserveFeatureAuthoritativeRevision: () -> Long = { 0L },
    scope: CoroutineScope,
    private val localeSettings: ProfileLocaleSettings,
    private val nowMillis: () -> Long = System::currentTimeMillis,
    private val refreshIntervalMillis: Long = REFRESH_INTERVAL_MILLIS,
) {
    class CachedProfile(
        val distinctId: String,
        val locale: String?,
        val cachedAtMillis: Long,
        val validator: String?,
        val body: JsonObject,
    )

    // Lock order for nested admission work: identity -> locale -> profile.
    private val lock = Any()
    private val baseDir = File((context.applicationContext ?: context).cacheDir, "nuxie/profiles")
    private var resident: CachedProfile? = null
    @Volatile
    private var nextProfileGeneration = 0L

    @Volatile
    private var nextCustomerGeneration = 0L

    @Volatile
    private var latestAppliedGeneration = 0L

    /** Legacy payloads retain their periodic refresh during the transition.
     * Canonical plane delivery synchronizes only at launch and foreground. */
    private var periodicRefreshEnabled = true

    private data class Admission(
        val identityScope: IdentityScope,
        val localeScope: ProfileLocaleScope,
        val generation: Long,
        val customerGeneration: Long,
        val featurePurchaseRevision: Long,
        val featureAuthoritativeRevision: Long,
    )

    private sealed interface Signal {
        data class Refresh(val done: kotlinx.coroutines.CompletableDeferred<Boolean>?) : Signal
    }

    private val signals = Channel<Signal>(capacity = Channel.UNLIMITED)

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private val worker = scope.launch {
        loadFromDisk()
        while (true) {
            val signal = select<Signal?> {
                signals.onReceiveCatching { it.getOrNull() }
                if (synchronized(lock) { periodicRefreshEnabled }) {
                    onTimeout(refreshIntervalMillis) { Signal.Refresh(null) }
                }
            } ?: break
            when (signal) {
                is Signal.Refresh -> {
                    val refreshed = refreshNow()
                    signal.done?.complete(refreshed)
                }
            }
        }
    }

    init {
        baseDir.mkdirs()
    }

    /** The current, non-expired profile for the current user (or null). */
    fun currentProfile(): CachedProfile? {
        val identityScope = identity.captureScope()
        val localeScope = localeSettings.captureScope()
        return withCurrentScope(identityScope, localeScope) {
            synchronized(lock) {
                resident?.takeIf {
                    it.distinctId == identityScope.distinctId &&
                        it.locale == localeScope.identifier &&
                        isFresh(it)
                }
            }
        }
    }

    /** Kick an explicit launch/foreground refresh. */
    fun requestRefresh() {
        signals.trySend(Signal.Refresh(null))
    }

    /** Refresh and await the outcome (testing + transitions). */
    suspend fun refreshAndWait(): Boolean {
        val done = kotlinx.coroutines.CompletableDeferred<Boolean>()
        if (signals.trySend(Signal.Refresh(done)).isFailure) return false
        return done.await()
    }

    /** Change the effective locale and invalidate all older locale-scoped work. */
    fun setLocaleIdentifier(localeIdentifier: String?) {
        // Preserve the global lock order: locale -> profile (identity is not
        // needed because locale invalidation is customer-agnostic).
        localeSettings.setLocaleIdentifier(localeIdentifier)
        synchronized(lock) {
            nextProfileGeneration += 1
        }
    }

    /** User-transition observer: reset/refresh per the iOS coordinator rules. */
    val transitionObserver = UserTransitionCoordinator.Observer { kind, from, to ->
        if (kind == UserTransitionCoordinator.Kind.RESET) {
            // Ordered cleanup belongs to the old identity's keys. A newer
            // admission must never cancel it.
            clearCache(from)
        }
        val admission = beginAdmission(expectedDistinctId = to)
        if (admission == null) {
            Log.w(LOG_TAG, "Discarding superseded profile transition")
            return@Observer
        }
        handleUserChange(to, admission)
    }

    suspend fun close() {
        signals.close()
        worker.join()
    }

    // MARK: internals

    private suspend fun handleUserChange(newDistinctId: String, admission: Admission) {
        val cached = synchronized(lock) { loadCached(newDistinctId) }
        if (cached != null && cached.locale == admission.localeScope.identifier && isFresh(cached)) {
            if (!applyProfile(cached, admission)) return
            // Fresh enough to skip an immediate network hit?
            if (nowMillis() - cached.cachedAtMillis < BACKGROUND_REFRESH_AGE_MILLIS) return
        } else if (cached != null) {
            // Expired OR from another locale: evict before any use so a cold
            // start cannot rehydrate old-locale state (segments included).
            evictCache(newDistinctId, admission)
        }
        refreshNow()
    }

    private suspend fun loadFromDisk() {
        val admission = beginAdmission() ?: return
        val distinctId = admission.identityScope.distinctId
        val cached = synchronized(lock) { loadCached(distinctId) }
        if (cached != null && cached.locale == admission.localeScope.identifier && isFresh(cached)) {
            applyProfile(cached, admission)
        } else if (cached != null) {
            // Expired OR from another locale: evict before any use so a cold
            // start cannot rehydrate old-locale state (segments included).
            evictCache(distinctId, admission)
        }
    }

    private suspend fun refreshNow(): Boolean {
        val admission = beginAdmission() ?: return false
        val distinctId = admission.identityScope.distinctId
        val locale = admission.localeScope.identifier
        val scopedResident = synchronized(lock) {
            resident?.takeIf {
                it.distinctId == distinctId && it.locale == locale
            }
        }
        val previous = scopedResident?.takeIf(::isFresh)
        if (scopedResident != null && previous == null) {
            evictCache(distinctId, admission)
        }
        val validator = previous
            ?.validator
            ?.let { NuxieApi.ProfileCacheValidator(it) }

        val result = runCatching {
            api.fetchProfile(distinctId, locale, revalidating = validator)
        }.getOrElse {
            Log.w(LOG_TAG, "Profile fetch failed; signed authority stays as-is", it)
            return false
        }

        if (!identity.isCurrentScope(admission.identityScope)) {
            Log.w(LOG_TAG, "Discarding stale profile fetch - customer changed mid-flight")
            return false
        }

        val cached = when (result) {
            is NuxieApi.ProfileFetchResult.NotModified -> {
                val refreshedPrevious = previous ?: return false
                CachedProfile(
                    distinctId = distinctId,
                    locale = locale,
                    cachedAtMillis = nowMillis(),
                    validator = refreshedPrevious.validator,
                    body = refreshedPrevious.body,
                )
            }
            is NuxieApi.ProfileFetchResult.Modified -> {
                val body = runCatching {
                    Json.parseToJsonElement(result.bodyText).jsonObject
                }.getOrElse {
                    Log.w(LOG_TAG, "Profile response was not a JSON object", it)
                    return false
                }
                CachedProfile(
                    distinctId = distinctId,
                    locale = locale,
                    cachedAtMillis = nowMillis(),
                    validator = result.validator?.rawValue,
                    body = body,
                )
            }
        }

        return applyProfile(cached, admission)
    }

    private suspend fun applyProfile(
        cached: CachedProfile,
        admission: Admission,
    ): Boolean {
        val schemaVersion = (cached.body["schemaVersion"] as? JsonPrimitive)
            ?.takeIf { it.isString }
            ?.content
        val planePrepared = if (schemaVersion == "nuxie.journey-plane-profile.v1") {
            val catalog = deviceLegProfiles ?: return false
            runCatching { catalog.prepare(cached.body) }.getOrElse {
                Log.w(LOG_TAG, "Device leg plane profile rejected", it)
                return false
            }
        } else null

        var featurePublication: FeatureInfo.Mutation? = null
        val admitted = withCurrentScope(admission.identityScope, admission.localeScope) {
            synchronized(lock) {
                if (admission.generation != nextProfileGeneration ||
                    admission.generation < latestAppliedGeneration
                ) {
                    return@synchronized false
                }
                latestAppliedGeneration = admission.generation

                if (planePrepared != null) {
                    runCatching {
                        deviceLegProfiles?.commit(cached.distinctId, planePrepared)
                    }.getOrElse {
                        Log.w(LOG_TAG, "Device leg plane profile commit failed", it)
                        return@synchronized false
                    }
                } else {
                    deviceLegProfiles?.clear(cached.distinctId)
                }
                periodicRefreshEnabled = planePrepared == null
                resident = cached
                persist(cached)
                segments.applySnapshot(
                    cached.distinctId,
                    cached.body["segmentMemberships"] as? JsonObject,
                )
                if (planePrepared == null) applyJourneyProfile(cached.distinctId, cached.body)
                featurePublication = stageFeatureProfile(
                    cached.distinctId,
                    cached.body,
                    admission.featurePurchaseRevision,
                    admission.featureAuthoritativeRevision,
                    { isAdmissionCurrent(admission) },
                )
                true
            }
        } == true
        if (!admitted) {
            Log.w(LOG_TAG, "Discarding stale profile admission")
        } else {
            publishFeatureProfile(featurePublication)
        }

        // Customer-scoped payloads (properties, server facts) are locale-
        // independent and still commit from a LOCALE-FLIP discard: the same
        // payloads arrive under any locale and their dedupe is correct. A
        // discard whose effective locale equals the admission's is
        // indistinguishable from supersession by a newer fetch, and
        // superseded responses contribute nothing (they redeliver on the
        // replacement fetch).
        val localeFlipDiscard = !admitted &&
            localeSettings.captureScope().identifier != admission.localeScope.identifier
        if (admitted || (localeFlipDiscard && isCustomerAdmissionCurrent(admission))) {
            applyCustomerProperties(cached, admission)
            if (planePrepared == null && isCustomerAdmissionCurrent(admission)) {
                applyJourneyFacts(cached.distinctId, cached.body)
            }
        }
        return admitted
    }

    private fun applyCustomerProperties(cached: CachedProfile, admission: Admission) {
        identity.withCurrentScope(admission.identityScope) current@ {
            synchronized(lock) {
                if (admission.customerGeneration != nextCustomerGeneration) return@current
                (cached.body["userProperties"] as? JsonObject)?.let { properties ->
                    applyUserProperties(properties.mapValues { (_, value) -> value })
                }
            }
        }
    }

    private fun beginAdmission(expectedDistinctId: String? = null): Admission? {
        val identityScope = identity.captureScope()
        if (expectedDistinctId != null && identityScope.distinctId != expectedDistinctId) return null
        val localeScope = localeSettings.captureScope()
        return withCurrentScope(identityScope, localeScope) {
            synchronized(lock) {
                if (expectedDistinctId != null &&
                    identityScope.distinctId != expectedDistinctId
                ) {
                    return@synchronized null
                }
                nextProfileGeneration += 1
                nextCustomerGeneration += 1
                Admission(
                    identityScope = identityScope,
                    localeScope = localeScope,
                    generation = nextProfileGeneration,
                    customerGeneration = nextCustomerGeneration,
                    featurePurchaseRevision = captureFeaturePurchaseRevision(),
                    featureAuthoritativeRevision = reserveFeatureAuthoritativeRevision(),
                )
            }
        }
    }

    private fun isAdmissionCurrent(admission: Admission): Boolean =
        identity.isCurrentScope(admission.identityScope) &&
            localeSettings.isCurrentScope(admission.localeScope) &&
            nextProfileGeneration == admission.generation &&
            latestAppliedGeneration == admission.generation

    private fun isCustomerAdmissionCurrent(admission: Admission): Boolean =
        identity.isCurrentScope(admission.identityScope) &&
            nextCustomerGeneration == admission.customerGeneration

    private fun <T> withCurrentScope(
        identityScope: IdentityScope,
        localeScope: ProfileLocaleScope,
        block: () -> T,
    ): T? = identity.withCurrentScope(identityScope) {
        localeSettings.withCurrentScope(localeScope, block)
    }

    private fun clearCache(distinctId: String) {
        synchronized(lock) {
            if (resident?.distinctId == distinctId) resident = null
            fileFor(distinctId).delete()
        }
        segments.clearSegments(distinctId)
        deviceLegProfiles?.clear(distinctId)
    }

    private fun evictCache(distinctId: String, admission: Admission) {
        withCurrentScope(admission.identityScope, admission.localeScope) {
            synchronized(lock) {
                if (admission.generation != nextProfileGeneration ||
                    admission.generation < latestAppliedGeneration
                ) {
                    return@synchronized
                }
                latestAppliedGeneration = admission.generation
                if (resident?.distinctId == distinctId) resident = null
                fileFor(distinctId).delete()
                // The persisted segment mirror is locale-scoped state admitted
                // with the profile; it must not survive the profile's eviction.
                segments.clearSegments(distinctId)
                deviceLegProfiles?.clear(distinctId)
            }
        }
    }

    private fun isFresh(cached: CachedProfile): Boolean =
        nowMillis() - cached.cachedAtMillis < CACHE_TTL_MILLIS

    private fun loadCached(distinctId: String): CachedProfile? {
        if (resident?.distinctId == distinctId) return resident
        val file = fileFor(distinctId)
        if (!file.exists()) return null
        return runCatching {
            val root = Json.parseToJsonElement(file.readText()).jsonObject
            CachedProfile(
                distinctId = distinctId,
                locale = (root["locale"] as? JsonPrimitive)?.takeIf { it.isString }?.content,
                cachedAtMillis = (root["cachedAtMillis"] as? JsonPrimitive)
                    ?.content?.toLongOrNull() ?: 0L,
                validator = (root["validator"] as? JsonPrimitive)?.takeIf { it.isString }?.content,
                body = root.getValue("body").jsonObject,
            )
        }.getOrElse {
            Log.w(LOG_TAG, "Failed to load cached profile; evicting.", it)
            file.delete()
            null
        }
    }

    private fun persist(cached: CachedProfile) {
        runCatching {
            val root = buildJsonObject {
                cached.locale?.let { put("locale", JsonPrimitive(it)) }
                put("cachedAtMillis", JsonPrimitive(cached.cachedAtMillis))
                cached.validator?.let { put("validator", JsonPrimitive(it)) }
                put("body", cached.body)
            }
            fileFor(cached.distinctId).writeText(root.toString())
        }.onFailure { Log.w(LOG_TAG, "Failed to persist profile cache", it) }
    }

    private fun fileFor(distinctId: String): File =
        File(baseDir, distinctId.map {
            if (it.isLetterOrDigit() || it == '-' || it == '_') it else '_'
        }.joinToString("") + ".json")

    private companion object {
        const val LOG_TAG = "Nuxie"
        const val CACHE_TTL_MILLIS = 24L * 60L * 60L * 1000L
        const val BACKGROUND_REFRESH_AGE_MILLIS = 5L * 60L * 1000L
        const val REFRESH_INTERVAL_MILLIS = 30L * 60L * 1000L
    }
}
