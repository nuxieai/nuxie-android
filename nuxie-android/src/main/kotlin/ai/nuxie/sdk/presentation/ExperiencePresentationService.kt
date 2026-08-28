package ai.nuxie.sdk.presentation

import ai.nuxie.sdk.ExperienceRef
import ai.nuxie.sdk.events.SystemEventNames
import ai.nuxie.sdk.experiences.AcquiredRelease
import ai.nuxie.sdk.experiences.AuthenticatedRelease
import ai.nuxie.sdk.experiences.Delivery
import ai.nuxie.sdk.experiences.ReleaseArtifactAcquirer
import android.content.Context
import android.content.Intent
import java.io.File
import java.lang.ref.WeakReference
import java.util.Collections
import java.util.IdentityHashMap
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** The authenticated catalog entry consumed by presentation. */
internal data class PresentationRelease(
    val release: AuthenticatedRelease,
    val delivery: Delivery,
)

/** Resolves only releases that passed profile authentication and admission. */
internal fun interface PresentationReleaseProvider {
    fun releaseFor(experienceVersionId: String): PresentationRelease?
}

/** Kotlin analog of the iOS presentation close-reason set. */
internal sealed interface CloseReason {
    data object UserDismissed : CloseReason
    data object HostDismissed : CloseReason
    data object IdentityChanged : CloseReason
    data object GoalMet : CloseReason
    data object PurchaseCompleted : CloseReason
    data object Timeout : CloseReason
    data class Error(val cause: Throwable) : CloseReason
}

internal class ExperiencePresentationException(
    val reason: Reason,
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause) {
    enum class Reason {
        RUNTIME_UNAVAILABLE,
        RELEASE_NOT_FOUND,
        ACQUISITION_FAILED,
        PREPARATION_FAILED,
        HOST_FAILED,
        FIRST_FRAME_TIMEOUT,
        SUPERSEDED,
    }
}

internal data class PresentationOutcome(
    val ref: ExperienceRef,
    val reason: CloseReason,
    val ownerDistinctId: String? = null,
    val initiatingDistinctId: String? = null,
)

/** Prepared content remains service-owned; only its opaque id crosses the Activity boundary. */
internal data class PreparedPresentation(
    val rivFile: File,
    val artboardName: String?,
    val clearColor: Int,
    val shell: PresentationShell,
    val descriptor: JsonObject? = null,
    val artifactsByKey: Map<String, File> = emptyMap(),
)

internal sealed interface PresentationShell {
    val dismissible: Boolean

    data object FullScreen : PresentationShell {
        override val dismissible: Boolean = true
    }

    data class Sheet(
        val detent: Detent,
        override val dismissible: Boolean,
    ) : PresentationShell {
        enum class Detent { MEDIUM, LARGE }
    }

    data class Drawer(
        val edge: Edge,
        val extentRatio: Float,
        val cornerRadiusDp: Float,
        override val dismissible: Boolean,
    ) : PresentationShell {
        enum class Edge { TOP, BOTTOM, LEADING, TRAILING }
    }
}

/** Claim operations the registry coordinates with its attached host Activity. */
internal interface PresentationActivityHandle {
    fun claimFromService(reason: CloseReason): Boolean

    fun claimedCloseReason(): CloseReason?

    fun finishAfterServiceClaim()
}

/**
 * Process-local handoff between [ExperiencePresentationService] and the one
 * engine-owned Activity. Absence after process death is deliberate: content
 * is never reconstructed from Intent extras.
 */
internal object PresentationRegistry {
    private class Entry(
        val content: PreparedPresentation,
        val onFirstFrame: () -> Unit,
        val onFailure: (Throwable) -> Unit,
        val onDismissed: (CloseReason) -> Unit,
    ) {
        val terminal = AtomicBoolean(false)
        val firstFrame = AtomicBoolean(false)
        var latestActivity = WeakReference<PresentationActivityHandle>(null)
        // Configuration recreation changes the dismissal target, but every
        // instance remains pending until its runtime lane calls detach.
        val attachedActivities: MutableSet<PresentationActivityHandle> =
            Collections.newSetFromMap(IdentityHashMap())
        var dismissalReason: CloseReason? = null
    }

    private val lock = Any()
    private val entries = mutableMapOf<String, Entry>()

    fun register(
        id: String,
        content: PreparedPresentation,
        onFirstFrame: () -> Unit,
        onFailure: (Throwable) -> Unit,
        onDismissed: (CloseReason) -> Unit,
    ) {
        synchronized(lock) {
            check(id !in entries) { "duplicate presentation id" }
            entries[id] = Entry(content, onFirstFrame, onFailure, onDismissed)
        }
    }

    fun resolve(id: String): PreparedPresentation? = synchronized(lock) { entries[id]?.content }

    fun attach(id: String, activity: PresentationActivityHandle): Boolean = synchronized(lock) {
        val entry = entries[id] ?: return@synchronized false
        if (entry.dismissalReason != null) return@synchronized false
        entry.attachedActivities += activity
        entry.latestActivity = WeakReference(activity)
        true
    }

    fun detach(id: String, activity: PresentationActivityHandle) {
        val completion = synchronized(lock) {
            val entry = entries[id] ?: return
            entry.attachedActivities.remove(activity)
            if (entry.latestActivity.get() === activity) {
                entry.latestActivity = WeakReference(null)
            }
            if (entry.dismissalReason == null) {
                entry.dismissalReason = activity.claimedCloseReason()
            }
            completionIfReady(id, entry)
        }
        completion?.invoke()
    }

    fun reportFirstFrame(id: String) {
        val callback = synchronized(lock) {
            val entry = entries[id] ?: return
            if (entry.terminal.get() || !entry.firstFrame.compareAndSet(false, true)) return
            entry.onFirstFrame
        }
        callback()
    }

    fun reportFailure(id: String, error: Throwable) {
        dismiss(id, CloseReason.Error(error))
    }

    fun reportDismissed(id: String, reason: CloseReason) {
        val completion = synchronized(lock) {
            val entry = entries[id] ?: return
            if (entry.dismissalReason == null) entry.dismissalReason = reason
            completionIfReady(id, entry)
        }
        completion?.invoke()
    }

    fun dismiss(id: String, reason: CloseReason) {
        var completion: (() -> Unit)? = null
        val activityToFinish = synchronized(lock) {
            val entry = entries[id] ?: return
            val attached = entry.latestActivity.get()
            val selected = entry.dismissalReason
            when {
                selected != null -> {
                    if (attached?.claimedCloseReason() == selected) attached else null
                }
                attached == null -> {
                    entry.dismissalReason = reason
                    completion = completionIfReady(id, entry)
                    null
                }
                attached.claimFromService(reason) || attached.claimedCloseReason() == reason -> {
                    entry.dismissalReason = reason
                    attached
                }
                else -> null
            }
        }
        completion?.invoke()
        activityToFinish?.finishAfterServiceClaim()
    }

    private fun completionIfReady(id: String, entry: Entry): (() -> Unit)? {
        val reason = entry.dismissalReason ?: return null
        if (entry.attachedActivities.isNotEmpty()) return null
        entries.remove(id)
        if (!entry.terminal.compareAndSet(false, true)) return null
        return when (reason) {
            is CloseReason.Error -> ({ entry.onFailure(reason.cause) })
            else -> ({ entry.onDismissed(reason) })
        }
    }

    internal fun clearForTesting() {
        synchronized(lock) { entries.clear() }
    }
}

/** Trigger-to-first-frame presentation authority. */
internal class ExperiencePresentationService(
    private val releases: PresentationReleaseProvider,
    private val acquire: suspend (PresentationRelease) -> AcquiredRelease,
    private val emit: (String, Map<String, Any?>, String?) -> Unit,
    private val scope: CoroutineScope,
    private val runtimeAvailable: () -> Boolean,
    private val launch: (String) -> Unit,
    private val markOutcomeInMemory: suspend (PresentationOutcome) -> Boolean = { true },
    private val reportOutcome: suspend (PresentationOutcome) -> Unit = {},
    private val firstFrameTimeoutMillis: Long = FIRST_FRAME_TIMEOUT_MILLIS,
    private val beforeHostSemanticClaimForTesting: () -> Unit = {},
) {
    constructor(
        context: Context,
        releases: PresentationReleaseProvider,
        acquirer: ReleaseArtifactAcquirer,
        emit: (String, Map<String, Any?>, String?) -> Unit,
        scope: CoroutineScope,
        runtimeAvailable: () -> Boolean,
        markOutcomeInMemory: suspend (PresentationOutcome) -> Boolean = { true },
        reportOutcome: suspend (PresentationOutcome) -> Unit = {},
    ) : this(
        releases = releases,
        acquire = { admitted -> acquirer.acquire(admitted.release, admitted.delivery) },
        emit = emit,
        scope = scope,
        runtimeAvailable = runtimeAvailable,
        launch = AndroidPresentationLauncher(context.applicationContext ?: context),
        markOutcomeInMemory = markOutcomeInMemory,
        reportOutcome = reportOutcome,
        firstFrameTimeoutMillis = FIRST_FRAME_TIMEOUT_MILLIS,
    )

    private data class ActivePresentation(
        val id: String,
        val ref: ExperienceRef,
        val acquired: AcquiredRelease,
        val ownerDistinctId: String?,
        val firstFrame: CompletableDeferred<ExperienceRef>,
        val closed: AtomicBoolean = AtomicBoolean(false),
        val shown: AtomicBoolean = AtomicBoolean(false),
        val semanticReported: AtomicBoolean = AtomicBoolean(false),
        val finished: CompletableDeferred<Unit> = CompletableDeferred(),
        // One host dismissal applies the run transition; every concurrent caller awaits it.
        val runTransitionFinished: CompletableDeferred<Unit> = CompletableDeferred(),
    )

    private val presentationMutex = Mutex()
    private val stateLock = Any()
    private var current: ActivePresentation? = null
    private val identityEpochByOwner = mutableMapOf<String, Long>()

    private data class PresentationRequest(
        val ownerDistinctId: String?,
        val identityEpoch: Long,
    )

    private fun captureRequest(ownerDistinctId: String?): PresentationRequest =
        synchronized(stateLock) {
            PresentationRequest(
                ownerDistinctId = ownerDistinctId,
                identityEpoch = ownerDistinctId?.let { identityEpochByOwner[it] } ?: 0L,
            )
        }

    private fun isCurrentIdentity(request: PresentationRequest): Boolean =
        request.ownerDistinctId == null ||
            (identityEpochByOwner[request.ownerDistinctId] ?: 0L) == request.identityEpoch

    suspend fun present(
        experienceVersionId: String,
        journeyId: String? = null,
        ownerDistinctId: String? = null,
    ): ExperienceRef {
        val request = captureRequest(ownerDistinctId)
        val active = presentationMutex.withLock {
            if (!synchronized(stateLock) { isCurrentIdentity(request) }) {
                throw supersededByIdentityTransition()
            }
            run {
                if (!runtimeAvailable()) {
                    throw ExperiencePresentationException(
                        ExperiencePresentationException.Reason.RUNTIME_UNAVAILABLE,
                        "Experience renderer is unavailable on this device",
                    )
                }

                synchronized(stateLock) { current }?.let {
                    PresentationRegistry.dismiss(it.id, CloseReason.UserDismissed)
                    it.finished.await()
                }

                val admitted = releases.releaseFor(experienceVersionId)
                    ?: throw ExperiencePresentationException(
                        ExperiencePresentationException.Reason.RELEASE_NOT_FOUND,
                        "Authenticated Experience release not found: $experienceVersionId",
                    )
                val acquired = try {
                    acquire(admitted)
                } catch (error: Throwable) {
                    if (error is CancellationException) throw error
                    throw ExperiencePresentationException(
                        ExperiencePresentationException.Reason.ACQUISITION_FAILED,
                        "Experience artifact acquisition failed: ${error.message ?: "unknown error"}",
                        error,
                    )
                }
                val identity = admitted.release.identity
                val ref = ExperienceRef(
                    identity.experienceId,
                    identity.experienceVersionId,
                    journeyId,
                )
                val id = UUID.randomUUID().toString()
                val pending = ActivePresentation(
                    id,
                    ref,
                    acquired,
                    request.ownerDistinctId,
                    CompletableDeferred(),
                )
                val launched = synchronized(stateLock) {
                    if (!isCurrentIdentity(request)) {
                        false
                    } else {
                        // Identity shutdown and late launch admission share this
                        // transition: teardown either invalidates the epoch first
                        // or observes a fully registered presentation afterward.
                        current = pending
                        PresentationRegistry.register(
                            id = id,
                            content = PreparedPresentation(
                                rivFile = acquired.rivFile,
                                artboardName = admitted.release.defaultArtboardName(),
                                clearColor = admitted.release.presentationClearColor(),
                                shell = admitted.release.presentationShell(),
                                descriptor = admitted.release.descriptor,
                                artifactsByKey = acquired.artifactsByKey,
                            ),
                            onFirstFrame = { firstFrame(pending) },
                            onFailure = { error -> failed(pending, error) },
                            onDismissed = { reason -> ended(pending, reason) },
                        )
                        try {
                            launch(id)
                        } catch (error: Throwable) {
                            PresentationRegistry.reportFailure(id, error)
                        }
                        true
                    }
                }
                if (!launched) {
                    acquired.close()
                    throw supersededByIdentityTransition()
                }
                pending
            }
        }
        return try {
            withTimeout(firstFrameTimeoutMillis) { active.firstFrame.await() }
        } catch (_: TimeoutCancellationException) {
            val timeout = ExperiencePresentationException(
                ExperiencePresentationException.Reason.FIRST_FRAME_TIMEOUT,
                "Experience presentation did not attach and render its first frame in time",
            )
            PresentationRegistry.reportFailure(active.id, timeout)
            throw timeout
        }
    }

    fun dismiss(reason: CloseReason = CloseReason.UserDismissed) {
        synchronized(stateLock) { current }?.let { PresentationRegistry.dismiss(it.id, reason) }
    }

    suspend fun dismissFromHost(initiatingDistinctId: String) {
        val active = synchronized(stateLock) { current } ?: return
        val outcome = PresentationOutcome(
            ref = active.ref,
            reason = CloseReason.HostDismissed,
            ownerDistinctId = active.ownerDistinctId,
            initiatingDistinctId = initiatingDistinctId,
        )
        // Reserve the matching durable reporter before requesting teardown so
        // a synchronous terminal callback cannot claim semantic reporting.
        beforeHostSemanticClaimForTesting()
        val reportsOutcome = active.semanticReported.compareAndSet(false, true)
        val teardownReason = if (active.ownerDistinctId == initiatingDistinctId) {
            CloseReason.HostDismissed
        } else {
            CloseReason.IdentityChanged
        }
        PresentationRegistry.dismiss(active.id, teardownReason)
        if (reportsOutcome) {
            val transition = scope.launch {
                if (runCatching { markOutcomeInMemory(outcome) }.getOrDefault(false)) {
                    scope.launch { runCatching { reportOutcome(outcome) } }
                }
            }
            transition.invokeOnCompletion {
                active.runTransitionFinished.complete(Unit)
            }
        }
        joinAll(active.finished, active.runTransitionFinished)
    }

    /**
     * Tears down a presentation owned by the departing customer without
     * attributing a dismissal or terminal Journey outcome to either identity.
     * Mirrors iOS identity-transition presentation shutdown (`reason: nil`).
     */
    suspend fun shutdownOwnedBy(ownerDistinctId: String) {
        synchronized(stateLock) {
            identityEpochByOwner[ownerDistinctId] =
                (identityEpochByOwner[ownerDistinctId] ?: 0L) + 1L
        }
        val active = synchronized(stateLock) {
            current?.takeIf { it.ownerDistinctId == ownerDistinctId }
        } ?: return
        PresentationRegistry.dismiss(active.id, CloseReason.IdentityChanged)
        active.finished.await()
    }

    fun close() = dismiss(CloseReason.UserDismissed)

    private fun supersededByIdentityTransition() = ExperiencePresentationException(
        ExperiencePresentationException.Reason.SUPERSEDED,
        "Experience presentation was superseded by an identity transition",
    )

    private fun firstFrame(active: ActivePresentation) {
        if (active.closed.get() || !active.shown.compareAndSet(false, true)) return
        runCatching {
            emit(
                SystemEventNames.EXPERIENCE_SHOWN,
                mapOf(
                    "journey_id" to active.ref.journeyId,
                    "experience_id" to active.ref.experienceId,
                    "experience_version" to active.ref.experienceVersion,
                ),
                active.ownerDistinctId,
            )
        }
        active.firstFrame.complete(active.ref)
    }

    private fun failed(active: ActivePresentation, error: Throwable) {
        if (!active.closed.compareAndSet(false, true)) return
        clearCurrent(active)
        active.acquired.close()
        active.finished.complete(Unit)
        val typed = if (error is ExperiencePresentationException) error else {
            ExperiencePresentationException(
                ExperiencePresentationException.Reason.HOST_FAILED,
                "Experience presentation host failed: ${error.message ?: "unknown error"}",
                error,
            )
        }
        active.firstFrame.completeExceptionally(typed)
        if (active.shown.get()) emitCloseFact(active, CloseReason.Error(typed))
        scope.launch {
            if (active.semanticReported.compareAndSet(false, true)) {
                reportTerminalOutcome(
                    active,
                    PresentationOutcome(
                        ref = active.ref,
                        reason = CloseReason.Error(typed),
                        ownerDistinctId = active.ownerDistinctId,
                    ),
                )
            }
        }
    }

    private fun ended(active: ActivePresentation, reason: CloseReason) {
        if (!active.closed.compareAndSet(false, true)) return
        clearCurrent(active)
        active.acquired.close()
        if (!active.firstFrame.isCompleted) {
            active.firstFrame.completeExceptionally(
                ExperiencePresentationException(
                    ExperiencePresentationException.Reason.SUPERSEDED,
                    "Experience presentation ended before its first frame",
                ),
            )
        }
        val reportsSemanticOutcome = reason != CloseReason.IdentityChanged
        if (active.shown.get() && reportsSemanticOutcome) emitCloseFact(active, reason)
        active.finished.complete(Unit)
        if (reportsSemanticOutcome) {
            scope.launch {
                if (active.semanticReported.compareAndSet(false, true)) {
                    reportTerminalOutcome(
                        active,
                        PresentationOutcome(
                            ref = active.ref,
                            reason = reason,
                            ownerDistinctId = active.ownerDistinctId,
                        ),
                    )
                }
            }
        }
    }

    private suspend fun reportTerminalOutcome(
        active: ActivePresentation,
        outcome: PresentationOutcome,
    ) {
        try {
            reportOutcome(outcome)
        } finally {
            active.runTransitionFinished.complete(Unit)
        }
    }

    private fun clearCurrent(active: ActivePresentation) {
        synchronized(stateLock) {
            if (current === active) current = null
        }
    }

    private fun emitCloseFact(active: ActivePresentation, reason: CloseReason) {
        val ref = active.ref
        val properties = linkedMapOf<String, Any?>(
            "journey_id" to ref.journeyId,
            "experience_id" to ref.experienceId,
            "experience_version" to ref.experienceVersion,
        )
        when (reason) {
            CloseReason.UserDismissed -> properties["reason"] = "user"
            CloseReason.HostDismissed -> properties["reason"] = "host"
            CloseReason.IdentityChanged -> return
            CloseReason.GoalMet -> properties["reason"] = "goal_met"
            else -> Unit
        }
        val name = when (reason) {
            CloseReason.UserDismissed, CloseReason.HostDismissed, CloseReason.GoalMet ->
                SystemEventNames.EXPERIENCE_DISMISSED
            CloseReason.IdentityChanged -> error("identity-change shutdown has no close fact")
            CloseReason.PurchaseCompleted -> {
                properties["product_id"] = null
                SystemEventNames.EXPERIENCE_PURCHASED
            }
            CloseReason.Timeout -> SystemEventNames.EXPERIENCE_TIMED_OUT
            is CloseReason.Error -> {
                properties["error_message"] = reason.cause.message
                SystemEventNames.EXPERIENCE_ERRORED
            }
        }
        runCatching { emit(name, properties, active.ownerDistinctId) }
    }

    private class AndroidPresentationLauncher(private val context: Context) : (String) -> Unit {
        override fun invoke(presentationId: String) {
            context.startActivity(
                Intent(context, NuxieExperienceActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    putExtra(NuxieExperienceActivity.EXTRA_PRESENTATION_ID, presentationId)
                },
            )
        }
    }
}

private fun AuthenticatedRelease.defaultArtboardName(): String? {
    val render = descriptor["render"] as? JsonObject ?: return null
    val firstScreen = (render["screens"] as? JsonArray)?.firstOrNull() as? JsonObject
    return (firstScreen?.get("artboardName") as? JsonPrimitive)
        ?.takeIf { it.isString }?.content
}

private fun AuthenticatedRelease.presentationClearColor(): Int {
    val presentation = descriptor["presentation"] as? JsonObject ?: return OPAQUE_BLACK
    val value = (presentation["backgroundColor"] as? JsonPrimitive)
        ?.takeIf { it.isString }?.content ?: return OPAQUE_BLACK
    val hex = value.removePrefix("#")
    return runCatching {
        when (hex.length) {
            6 -> (0xFF000000L or hex.toLong(16)).toInt()
            8 -> {
                val rgba = hex.toLong(16)
                ((rgba and 0xFF) shl 24 or (rgba ushr 8)).toInt()
            }
            else -> OPAQUE_BLACK
        }
    }.getOrDefault(OPAQUE_BLACK)
}

private fun AuthenticatedRelease.presentationShell(): PresentationShell {
    val presentation = descriptor["presentation"] as? JsonObject
        ?: return PresentationShell.FullScreen
    return when (presentation.string("style")) {
        "sheet" -> {
            val sheet = presentation["sheet"] as? JsonObject
                ?: return PresentationShell.FullScreen
            PresentationShell.Sheet(
                detent = when (sheet.string("detent")) {
                    "medium" -> PresentationShell.Sheet.Detent.MEDIUM
                    else -> PresentationShell.Sheet.Detent.LARGE
                },
                dismissible = sheet.boolean("dismissible") ?: true,
            )
        }
        "drawer" -> {
            val drawer = presentation["drawer"] as? JsonObject
                ?: return PresentationShell.FullScreen
            PresentationShell.Drawer(
                edge = when (drawer.string("edge")) {
                    "top" -> PresentationShell.Drawer.Edge.TOP
                    "leading", "left" -> PresentationShell.Drawer.Edge.LEADING
                    "trailing", "right" -> PresentationShell.Drawer.Edge.TRAILING
                    else -> PresentationShell.Drawer.Edge.BOTTOM
                },
                extentRatio = drawer.float("extentRatio")?.coerceIn(0.1f, 1f) ?: 0.5f,
                cornerRadiusDp = drawer.float("cornerRadius")?.coerceAtLeast(0f) ?: 0f,
                dismissible = drawer.boolean("dismissible") ?: true,
            )
        }
        else -> PresentationShell.FullScreen
    }
}

private fun JsonObject.string(key: String): String? =
    (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

private fun JsonObject.boolean(key: String): Boolean? =
    (this[key] as? JsonPrimitive)?.takeIf { !it.isString }?.content?.toBooleanStrictOrNull()

private fun JsonObject.float(key: String): Float? =
    (this[key] as? JsonPrimitive)?.takeIf { !it.isString }?.content?.toFloatOrNull()

private const val OPAQUE_BLACK = 0xFF000000.toInt()
private const val FIRST_FRAME_TIMEOUT_MILLIS = 30_000L
