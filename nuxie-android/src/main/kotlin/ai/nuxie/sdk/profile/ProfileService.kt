package ai.nuxie.sdk.profile

import ai.nuxie.sdk.features.FeatureInfo
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
 *   profile generation before any cache or fanout side effect.
 * - 30-minute periodic refresh; on user change a fresh-enough cache
 *   (< 5 min) is served without an immediate network hit.
 * - Fanout on every applied profile: user properties, segment memberships,
 *   release authority, Features, and server down-facts all retain the same
 *   admission fence. Android has no profile-mailbox consumer in this slice.
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
    private val applyJourneyFacts: suspend (
        distinctId: String,
        body: JsonObject,
        isCurrent: () -> Boolean,
    ) -> Unit = { _, _, _ -> },
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

    private val lock = Any()
    private val baseDir = File((context.applicationContext ?: context).cacheDir, "nuxie/profiles")
    private var resident: CachedProfile? = null
    @Volatile
    private var nextProfileGeneration = 0L

    @Volatile
    private var latestAppliedGeneration = 0L

    private data class Admission(
        val identityScope: IdentityScope,
        val localeScope: ProfileLocaleScope,
        val generation: Long,
        val featurePurchaseRevision: Long,
        val featureAuthoritativeRevision: Long,
    )

    private sealed interface Signal {
        data class Refresh(val done: kotlinx.coroutines.CompletableDeferred<Boolean>?) : Signal
    }

    private val signals = Channel<Signal>(capacity = Channel.UNLIMITED)

    @kotlinx.coroutines.ExperimentalCoroutinesApi
    private val worker = scope.launch {
        loadFromDisk()
        while (true) {
            val signal = select<Signal?> {
                signals.onReceiveCatching { it.getOrNull() }
                onTimeout(refreshIntervalMillis) { Signal.Refresh(null) }
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

    /** Kick a refresh; used at setup and by the periodic timer. */
    fun requestRefresh() {
        signals.trySend(Signal.Refresh(null))
    }

    /** Refresh and await the outcome (testing + transitions). */
    suspend fun refreshAndWait(): Boolean {
        val done = kotlinx.coroutines.CompletableDeferred<Boolean>()
        if (signals.trySend(Signal.Refresh(done)).isFailure) return false
        return done.await()
    }

    /** User-transition observer: reset/refresh per the iOS coordinator rules. */
    val transitionObserver = UserTransitionCoordinator.Observer { kind, from, to ->
        val admission = beginAdmission(expectedDistinctId = to)
        if (admission == null) {
            Log.w(LOG_TAG, "Discarding superseded profile transition")
            return@Observer
        }
        if (kind == UserTransitionCoordinator.Kind.RESET) {
            clearCache(from, admission)
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
            // Expired: evict before any use.
            if (!isFresh(cached)) evictCache(newDistinctId, admission)
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
            if (!isFresh(cached)) evictCache(distinctId, admission)
        }
    }

    private suspend fun refreshNow(): Boolean {
        val admission = beginAdmission() ?: return false
        val distinctId = admission.identityScope.distinctId
        val locale = admission.localeScope.identifier
        val previous = synchronized(lock) {
            resident?.takeIf {
                it.distinctId == distinctId && it.locale == locale && isFresh(it)
            }
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

        if (!isScopeCurrent(admission)) {
            Log.w(LOG_TAG, "Discarding stale profile fetch - customer scope changed mid-flight")
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
        var featurePublication: FeatureInfo.Mutation? = null
        val admitted = withCurrentScope(admission.identityScope, admission.localeScope) {
            synchronized(lock) {
                if (admission.generation != nextProfileGeneration ||
                    admission.generation < latestAppliedGeneration
                ) {
                    return@synchronized false
                }
                latestAppliedGeneration = admission.generation

                resident = cached
                persist(cached)
                (cached.body["userProperties"] as? JsonObject)?.let { properties ->
                    applyUserProperties(properties.mapValues { (_, value) -> value })
                }
                segments.applySnapshot(
                    cached.distinctId,
                    cached.body["segmentMemberships"] as? JsonObject,
                )
                applyJourneyProfile(cached.distinctId, cached.body)
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
            return false
        }

        publishFeatureProfile(featurePublication)
        applyJourneyFacts(cached.distinctId, cached.body) { isAdmissionCurrent(admission) }
        return true
    }

    private fun beginAdmission(expectedDistinctId: String? = null): Admission? {
        val identityScope = identity.captureScope()
        if (expectedDistinctId != null && identityScope.distinctId != expectedDistinctId) return null
        val localeScope = localeSettings.captureScope()
        return synchronized(lock) {
            if (!identity.isCurrentScope(identityScope) ||
                !localeSettings.isCurrentScope(localeScope) ||
                (expectedDistinctId != null && identityScope.distinctId != expectedDistinctId)
            ) {
                return@synchronized null
            }
            nextProfileGeneration += 1
            Admission(
                identityScope = identityScope,
                localeScope = localeScope,
                generation = nextProfileGeneration,
                featurePurchaseRevision = captureFeaturePurchaseRevision(),
                featureAuthoritativeRevision = reserveFeatureAuthoritativeRevision(),
            )
        }
    }

    private fun isScopeCurrent(admission: Admission): Boolean =
        identity.isCurrentScope(admission.identityScope) &&
            localeSettings.isCurrentScope(admission.localeScope)

    private fun isAdmissionCurrent(admission: Admission): Boolean =
        identity.isCurrentScope(admission.identityScope) &&
            localeSettings.isCurrentScope(admission.localeScope) &&
            nextProfileGeneration == admission.generation &&
            latestAppliedGeneration == admission.generation

    private fun <T> withCurrentScope(
        identityScope: IdentityScope,
        localeScope: ProfileLocaleScope,
        block: () -> T,
    ): T? = identity.withCurrentScope(identityScope) {
        localeSettings.withCurrentScope(localeScope, block)
    }

    private fun clearCache(distinctId: String, admission: Admission) {
        withCurrentScope(admission.identityScope, admission.localeScope) {
            synchronized(lock) {
                if (admission.generation != nextProfileGeneration ||
                    admission.generation < latestAppliedGeneration
                ) {
                    return@synchronized false
                }
                latestAppliedGeneration = admission.generation
                if (resident?.distinctId == distinctId) resident = null
                fileFor(distinctId).delete()
                segments.clearSegments(distinctId)
                true
            }
        }
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
