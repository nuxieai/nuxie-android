package ai.nuxie.sdk.profile

import ai.nuxie.sdk.features.FeatureInfo
import ai.nuxie.sdk.experiences.JourneyArtifactManager
import ai.nuxie.sdk.experiences.JourneyProfileCatalog
import ai.nuxie.sdk.experiences.PreparedJourneyArtifacts
import ai.nuxie.sdk.identity.IdentityProvider
import ai.nuxie.sdk.identity.IdentityScope
import ai.nuxie.sdk.identity.UserTransitionCoordinator
import ai.nuxie.sdk.network.NuxieApi
import ai.nuxie.sdk.network.ProfileDeliveryAuthority
import ai.nuxie.sdk.journey.JourneyProfileConsumer
import android.content.Context
import android.util.Log
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject

/**
 * Profile fetch + cache, porting the iOS `ProfileService` data-plane policy:
 *
 * - Canonical plane profiles remain usable offline and revalidate on every
 *   launch and foreground.
 * - Conditional refetch via the scoped ETag validator (locale-keyed).
 * - Atomic admission by identity decision, effective locale, and monotonic
 *   profile generation before every locale-scoped mutation.
 * - Profiles synchronize at launch and foreground only. User changes may
 *   hydrate an existing canonical cache, while locale changes withdraw the
 *   current delivery; neither introduces another sync point.
 * - Locale-scoped profile cache, release authority, visible Features, and
 *   Journey runtime state share one admission.
 *
 * The profile body is retained as raw JSON (duplicate-key validated at the
 * network layer); typed models arrive with their consumers.
 */
internal class ProfileService(
    context: Context,
    storageScope: ProfileStorageScope,
    private val api: NuxieApi,
    private val identity: IdentityProvider,
    private val journeyProfiles: JourneyProfileCatalog,
    private val journeys: JourneyProfileConsumer,
    private val journeyArtifacts: JourneyArtifactManager? = null,
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
    cacheDirectory: File? = null,
) {
    class CachedProfile(
        val distinctId: String,
        val locale: String?,
        val cachedAtMillis: Long,
        val validator: NuxieApi.ProfileCacheValidator?,
        val body: JsonObject,
    )

    // Lock order for nested admission work: identity -> locale -> profile.
    private val lock = Any()
    private val appContext = context.applicationContext ?: context
    private val baseDir = cacheDirectory ?: storageScope.cacheDirectory(appContext.cacheDir)
    private val authorityStore = ProfileAuthorityBindingStore(appContext, storageScope)
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

    private enum class AuthoritySource { NETWORK, CACHE }

    private data class PreparedPlane(
        val catalog: JourneyProfileCatalog.Prepared,
        val artifacts: PreparedJourneyArtifacts?,
    )

    private sealed interface Signal {
        data class Refresh(val done: CompletableDeferred<Boolean>?) : Signal
    }

    private val signals = Channel<Signal>(capacity = Channel.UNLIMITED)

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private val worker = scope.launch {
        try {
            loadFromDisk()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            Log.w(LOG_TAG, "Profile startup hydration failed; continuing without cache", failure)
        }
        while (true) {
            val signal = signals.receiveCatching().getOrNull() ?: break
            when (signal) {
                is Signal.Refresh -> {
                    var refreshed = false
                    try {
                        refreshed = refreshSafely()
                    } finally {
                        // Every accepted waiter completes even when refresh
                        // processing is cancelled or a consumer throws.
                        signal.done?.complete(refreshed)
                    }
                }
            }
        }
    }

    init {
        worker.invokeOnCompletion {
            signals.close()
            while (true) {
                val pending = signals.tryReceive().getOrNull() ?: break
                when (pending) {
                    is Signal.Refresh -> pending.done?.complete(false)
                }
            }
        }
        // The unscoped v1 cache could contain another configured app's
        // profile and therefore has no trustworthy delivery authority.
        File(appContext.cacheDir, "nuxie/profiles").deleteRecursively()
        baseDir.mkdirs()
    }

    /** The current usable profile for the current user (or null). */
    fun currentProfile(): CachedProfile? {
        val identityScope = identity.captureScope()
        val localeScope = localeSettings.captureScope()
        return withCurrentScope(identityScope, localeScope) {
            synchronized(lock) {
                resident?.takeIf {
                    it.distinctId == identityScope.distinctId &&
                        it.locale == localeScope.identifier &&
                        isUsable(it)
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
        val done = CompletableDeferred<Boolean>()
        if (signals.trySend(Signal.Refresh(done)).isFailure) return false
        return done.await()
    }

    /** Change the effective locale and withdraw the old locale's delivery. */
    suspend fun setLocaleIdentifier(localeIdentifier: String?) {
        val previousLocale = localeSettings.captureScope()
        val localeScope = localeSettings.setLocaleIdentifier(localeIdentifier)
        if (localeScope == previousLocale) return

        val identityScope = identity.captureScope()
        val withdrawal = withCurrentScope(identityScope, localeScope) {
            synchronized(lock) {
                nextProfileGeneration += 1
                latestAppliedGeneration = nextProfileGeneration
                if (resident?.distinctId == identityScope.distinctId) resident = null
                journeyProfiles.clear(identityScope.distinctId)
                identityScope.distinctId to nextProfileGeneration
            }
        } ?: return
        journeys.profileDidWithdraw(withdrawal.first, withdrawal.second)
    }

    /** User-transition observer: clear the old owner and hydrate cached authority only. */
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
        if (cached != null && cached.locale == admission.localeScope.identifier && isUsable(cached)) {
            if (!applyProfile(cached, admission, AuthoritySource.CACHE)) {
                evictCache(newDistinctId, admission)
            }
        } else if (cached != null) {
            // Unsupported or differently localized state cannot become
            // canonical authority for the new customer.
            evictCache(newDistinctId, admission)
        }
    }

    private suspend fun loadFromDisk() {
        val admission = beginAdmission() ?: return
        val distinctId = admission.identityScope.distinctId
        val cached = synchronized(lock) { loadCached(distinctId) }
        if (cached != null && cached.locale == admission.localeScope.identifier && isUsable(cached)) {
            if (!applyProfile(cached, admission, AuthoritySource.CACHE)) {
                evictCache(distinctId, admission)
            }
        } else if (cached != null) {
            // Unsupported or differently localized state cannot become
            // canonical authority at launch.
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
        val previous = scopedResident?.takeIf(::isUsable)
        if (scopedResident != null && previous == null) {
            evictCache(distinctId, admission)
        }
        val validator = previous?.validator

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

        if (result is NuxieApi.ProfileFetchResult.NotModified) {
            val refreshedPrevious = previous ?: return false
            return refreshCacheFreshness(
                CachedProfile(
                    distinctId = refreshedPrevious.distinctId,
                    locale = refreshedPrevious.locale,
                    cachedAtMillis = nowMillis(),
                    validator = refreshedPrevious.validator,
                    body = refreshedPrevious.body,
                ),
                admission,
            )
        }
        val modified = result as NuxieApi.ProfileFetchResult.Modified

        val body = runCatching {
            Json.parseToJsonElement(modified.bodyText).jsonObject
        }.getOrElse {
            Log.w(LOG_TAG, "Profile response was not a JSON object", it)
            return false
        }
        val cached = CachedProfile(
            distinctId = distinctId,
            locale = locale,
            cachedAtMillis = nowMillis(),
            validator = modified.validator,
            body = body,
        )
        return applyProfile(cached, admission, AuthoritySource.NETWORK)
    }

    private suspend fun refreshSafely(): Boolean = try {
        refreshNow()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: Throwable) {
        Log.w(LOG_TAG, "Profile refresh processing failed; authority stays as-is", failure)
        false
    }

    /** A 304 refreshes storage lifetime without replacing runtime authority. */
    private fun refreshCacheFreshness(cached: CachedProfile, admission: Admission): Boolean =
        withCurrentScope(admission.identityScope, admission.localeScope) {
            synchronized(lock) {
                if (admission.generation != nextProfileGeneration ||
                    admission.generation < latestAppliedGeneration
                ) {
                    return@synchronized false
                }
                latestAppliedGeneration = admission.generation
                resident = cached
                persist(cached)
                true
            }
        } == true

    private suspend fun applyProfile(
        cached: CachedProfile,
        admission: Admission,
        authoritySource: AuthoritySource,
    ): Boolean {
        if (!isCanonical(cached.body)) {
            Log.w(LOG_TAG, "Profile rejected: unsupported Journey profile schema")
            return false
        }
        val deliveryAuthority = cached.validator?.authority ?: return false
        val authorityAccepted = runCatching {
            withCurrentScope(admission.identityScope, admission.localeScope) {
                synchronized(lock) {
                    if (admission.generation != nextProfileGeneration ||
                        admission.generation < latestAppliedGeneration
                    ) {
                        return@synchronized false
                    }
                    when (authoritySource) {
                        AuthoritySource.NETWORK -> authorityStore.bind(deliveryAuthority)
                        AuthoritySource.CACHE -> authorityStore.authority() == deliveryAuthority
                    }
                }
            } == true
        }.getOrElse {
            Log.w(LOG_TAG, "Profile authority binding failed", it)
            return false
        }
        if (!authorityAccepted) return false
        val prepared = runCatching {
            journeyProfiles.prepare(cached.body, deliveryAuthority)
        }.getOrElse {
            Log.w(LOG_TAG, "Journey plane profile rejected", it)
            return false
        }
        val artifacts = runCatching {
            journeyArtifacts?.prepareJourneys(prepared.snapshot)
        }.getOrElse {
            Log.w(LOG_TAG, "Journey profile artifacts unavailable", it)
            return false
        }
        val planePrepared = PreparedPlane(prepared, artifacts)

        var featurePublication: FeatureInfo.Mutation? = null
        val admitted = try {
            withCurrentScope(admission.identityScope, admission.localeScope) {
                synchronized(lock) {
                    if (admission.generation != nextProfileGeneration ||
                        admission.generation < latestAppliedGeneration
                    ) {
                        return@synchronized false
                    }
                    latestAppliedGeneration = admission.generation

                    runCatching {
                        journeyProfiles.commit(cached.distinctId, planePrepared.catalog)
                    }.getOrElse {
                        Log.w(LOG_TAG, "Journey plane profile commit failed", it)
                        return@synchronized false
                    }
                    resident = cached
                    persist(cached)
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
        } catch (failure: Throwable) {
            planePrepared.artifacts?.close()
            throw failure
        }
        if (!admitted) {
            planePrepared.artifacts?.close()
            Log.w(LOG_TAG, "Discarding stale profile admission")
        } else {
            try {
                publishFeatureProfile(featurePublication)
            } catch (failure: Throwable) {
                planePrepared.artifacts?.close()
                throw failure
            }
            runCatching {
                // Ownership transfers when publication begins. The runtime
                // closes rejected or superseded leases.
                journeys.profileDidCommit(
                    planePrepared.catalog.snapshot,
                    planePrepared.catalog.authority,
                    cached.distinctId,
                    admission.generation,
                    planePrepared.artifacts,
                )
            }.onFailure {
                Log.w(LOG_TAG, "Journey runtime profile publication failed", it)
            }
        }

        return admitted
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
                Admission(
                    identityScope = identityScope,
                    localeScope = localeScope,
                    generation = nextProfileGeneration,
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

    private fun <T> withCurrentScope(
        identityScope: IdentityScope,
        localeScope: ProfileLocaleScope,
        block: () -> T,
    ): T? = identity.withCurrentScope(identityScope) {
        localeSettings.withCurrentScope(localeScope, block)
    }

    private suspend fun clearCache(distinctId: String) {
        val admissionGeneration = synchronized(lock) {
            nextProfileGeneration += 1
            latestAppliedGeneration = nextProfileGeneration
            if (resident?.distinctId == distinctId) resident = null
            fileFor(distinctId).delete()
            nextProfileGeneration
        }
        journeyProfiles.clear(distinctId)
        journeys.profileDidClear(distinctId, admissionGeneration)
    }

    private suspend fun evictCache(distinctId: String, admission: Admission) {
        val evicted = withCurrentScope(admission.identityScope, admission.localeScope) {
            synchronized(lock) {
                if (admission.generation != nextProfileGeneration ||
                    admission.generation < latestAppliedGeneration
                ) {
                    return@synchronized false
                }
                latestAppliedGeneration = admission.generation
                if (resident?.distinctId == distinctId) resident = null
                fileFor(distinctId).delete()
                journeyProfiles.clear(distinctId)
                true
            }
        } == true
        if (evicted) {
            journeys.profileDidClear(distinctId, admission.generation)
        }
    }

    private fun isUsable(cached: CachedProfile): Boolean =
        isCanonical(cached.body)

    private fun isCanonical(body: JsonObject): Boolean =
        body["schemaVersion"] == JsonPrimitive("nuxie.journey-plane-profile.v1")

    private fun loadCached(distinctId: String): CachedProfile? {
        if (resident?.distinctId == distinctId) return resident
        val file = fileFor(distinctId)
        if (!file.exists()) return null
        return runCatching {
            val root = Json.parseToJsonElement(file.readText()).jsonObject
            val validator = (root["validator"] as? JsonObject)?.let { stored ->
                val authority = (stored["authority"] as? JsonObject)?.let { value ->
                    ProfileDeliveryAuthority(
                        appId = value.requiredString("appId"),
                        environment = value.requiredString("environment"),
                    ).also {
                        if (!it.isValid) throw IllegalArgumentException("Invalid profile authority")
                    }
                }
                NuxieApi.ProfileCacheValidator(
                    rawValue = stored.requiredString("rawValue"),
                    resourceScope = stored.optionalString("resourceScope"),
                    authority = authority,
                )
            }
            CachedProfile(
                distinctId = distinctId,
                locale = (root["locale"] as? JsonPrimitive)?.takeIf { it.isString }?.content,
                cachedAtMillis = (root["cachedAtMillis"] as? JsonPrimitive)
                    ?.content?.toLongOrNull() ?: 0L,
                validator = validator,
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
                cached.validator?.let { validator ->
                    put("validator", buildJsonObject {
                        put("rawValue", JsonPrimitive(validator.rawValue))
                        validator.resourceScope?.let {
                            put("resourceScope", JsonPrimitive(it))
                        }
                        validator.authority?.let { authority ->
                            put("authority", buildJsonObject {
                                put("appId", JsonPrimitive(authority.appId))
                                put("environment", JsonPrimitive(authority.environment))
                            })
                        }
                    })
                }
                put("body", cached.body)
            }
            fileFor(cached.distinctId).writeText(root.toString())
        }.onFailure { Log.w(LOG_TAG, "Failed to persist profile cache", it) }
    }

    private fun fileFor(distinctId: String): File =
        File(baseDir, distinctId.map {
            if (it.isLetterOrDigit() || it == '-' || it == '_') it else '_'
        }.joinToString("") + ".json")

    private fun JsonObject.requiredString(key: String): String =
        (getValue(key) as? JsonPrimitive)?.takeIf { it.isString }?.content
            ?: throw IllegalArgumentException("Missing $key")

    private fun JsonObject.optionalString(key: String): String? =
        (get(key) as? JsonPrimitive)?.takeIf { it.isString }?.content

    private companion object {
        const val LOG_TAG = "Nuxie"
    }
}
