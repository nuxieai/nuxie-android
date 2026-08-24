package ai.nuxie.sdk.profile

import ai.nuxie.sdk.identity.IdentityProvider
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
 * - Staleness guard: a fetch whose user changed mid-flight is discarded so
 *   the old user's state never lands on the new user.
 * - 30-minute periodic refresh; on user change a fresh-enough cache
 *   (< 5 min) is served without an immediate network hit.
 * - Fanout on every applied profile: server userProperties -> identity,
 *   segmentMemberships -> the server-authoritative mirror. Releases, facts,
 *   and mailbox are parked for their own PRs (release pipeline, journeys).
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
    private val applyFeatureProfile: suspend (distinctId: String, body: JsonObject) -> Unit = { _, _ -> },
    scope: CoroutineScope,
    private val localeProvider: () -> String?,
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
    fun currentProfile(): CachedProfile? = synchronized(lock) {
        resident?.takeIf { it.distinctId == identity.distinctId() && isFresh(it) }
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
        if (kind == UserTransitionCoordinator.Kind.RESET) {
            clearCache(from)
        }
        handleUserChange(to)
    }

    suspend fun close() {
        signals.close()
        worker.join()
    }

    // MARK: internals

    private suspend fun handleUserChange(newDistinctId: String) {
        val cached = synchronized(lock) { loadCached(newDistinctId) }
        if (cached != null && isFresh(cached)) {
            synchronized(lock) { resident = cached }
            applyProfile(cached)
            // Fresh enough to skip an immediate network hit?
            if (nowMillis() - cached.cachedAtMillis < BACKGROUND_REFRESH_AGE_MILLIS) return
        } else if (cached != null) {
            // Expired: evict before any use.
            synchronized(lock) { fileFor(newDistinctId).delete() }
        }
        refreshNow()
    }

    private suspend fun loadFromDisk() {
        val distinctId = identity.distinctId()
        val cached = synchronized(lock) { loadCached(distinctId) }
        if (cached != null && isFresh(cached)) {
            synchronized(lock) { resident = cached }
            applyProfile(cached)
        } else if (cached != null) {
            synchronized(lock) { fileFor(distinctId).delete() }
        }
    }

    private suspend fun refreshNow(): Boolean {
        val distinctId = identity.distinctId()
        val locale = localeProvider()
        val previous = synchronized(lock) {
            resident?.takeIf { it.distinctId == distinctId && isFresh(it) }
        }
        val validator = previous
            ?.takeIf { it.locale == locale }
            ?.validator
            ?.let { NuxieApi.ProfileCacheValidator(it) }

        val result = runCatching {
            api.fetchProfile(distinctId, locale, revalidating = validator)
        }.getOrElse {
            Log.w(LOG_TAG, "Profile fetch failed; signed authority stays as-is", it)
            return false
        }

        // Staleness guard: the user changed while the fetch was in flight.
        if (identity.distinctId() != distinctId) {
            Log.w(LOG_TAG, "Discarding stale profile fetch - user changed mid-flight")
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

        synchronized(lock) {
            resident = cached
            persist(cached)
        }
        applyProfile(cached)
        return true
    }

    private suspend fun applyProfile(cached: CachedProfile) {
        (cached.body["userProperties"] as? JsonObject)?.let { properties ->
            applyUserProperties(properties.mapValues { (_, value) -> value })
        }
        segments.applySnapshot(
            cached.distinctId,
            cached.body["segmentMemberships"] as? JsonObject,
        )
        applyJourneyProfile(cached.distinctId, cached.body)
        applyFeatureProfile(cached.distinctId, cached.body)
    }

    private fun clearCache(distinctId: String) {
        synchronized(lock) {
            if (resident?.distinctId == distinctId) resident = null
            fileFor(distinctId).delete()
        }
        segments.clearSegments(distinctId)
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
