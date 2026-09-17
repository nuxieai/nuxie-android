package ai.nuxie.sdk.companion

import ai.nuxie.sdk.NuxieEnvironment
import ai.nuxie.sdk.core.supportedRuntimeForEmbeddedRuntime
import ai.nuxie.sdk.experiences.JourneyPreviewProfile
import ai.nuxie.sdk.experiences.JourneyReleaseArtifactAcquirer
import ai.nuxie.sdk.experiences.JourneyReleaseArtifactCache
import ai.nuxie.sdk.experiences.JourneyReleaseSupportedRuntime
import ai.nuxie.sdk.presentation.JourneyScreenDismissalResult
import ai.nuxie.sdk.network.HttpTransport
import ai.nuxie.sdk.network.HttpUrlConnectionTransport
import ai.nuxie.sdk.presentation.ExperiencePresentationService
import ai.nuxie.sdk.runtime.NuxieRuntime
import ai.nuxie.sdk.runtime.nuxieRuntimeSourceRevision
import android.content.Context
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.withContext

/** Opt-in boundary for the Nuxie Companion integration; not a customer SDK presentation API. */
@RequiresOptIn(level = RequiresOptIn.Level.ERROR)
@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION)
annotation class NuxieCompanionApi

/** Renders an exact signed release without setting up a customer SDK session. */
@NuxieCompanionApi
object JourneyPreviewHost {
    /**
     * Opens the ordinary native presentation and suspends until it closes.
     * Cancel the caller's coroutine to close and drain the preview before replacing it.
     * The profile must contain exactly one signed release and one armed leg.
     * [initialScreenId] selects an authenticated screen; null selects the entry screen.
     *
     * [onReady] runs on the main thread after the initial native frame is presented.
     *
     * Preview uses isolated replay authority and an isolated content-addressed cache.
     * It does not execute Journey routes, send analytics, or perform store operations.
     */
    suspend fun present(
        context: Context,
        profileData: ByteArray,
        environment: NuxieEnvironment,
        initialScreenId: String? = null,
        onReady: () -> Unit = {},
    ) {
        JourneyPreviewSession(context, environment).present(profileData, initialScreenId, onReady)
    }
}

internal class JourneyPreviewSession(
    context: Context,
    private val environment: NuxieEnvironment,
    private val transport: HttpTransport = HttpUrlConnectionTransport(),
    private val supportedRuntime: () -> JourneyReleaseSupportedRuntime? = {
        supportedRuntimeForEmbeddedRuntime(nuxieRuntimeSourceRevision())
    },
) {
    private val context = context.applicationContext ?: context

    suspend fun present(profileData: ByteArray, initialScreenId: String? = null, onReady: () -> Unit = {}) {
        val bytes = profileData.copyOf()
        val selection = withContext(Dispatchers.Default) {
            JourneyPreviewProfile.authenticate(bytes, environment, supportedRuntime(), initialScreenId)
        }
        val owner = "companion-${UUID.randomUUID()}"
        // Teardown must retain its worker even when the caller is cancelled.
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val presentations = ExperiencePresentationService(context, { _, _, _ -> }, scope,
            { NuxieRuntime.shared.isAvailable })
        val acquirer = JourneyReleaseArtifactAcquirer(JourneyReleaseArtifactCache(
            context, transport, cacheDirectory = File(context.cacheDir, "nuxie/companion/objects"),
        ))
        val closed = CompletableDeferred<Unit>()
        try {
            presentations.presentJourney(
                release = selection.release,
                screenId = selection.screenId,
                journeyId = owner,
                ownerDistinctId = owner,
                reservation = presentations.reserveJourney(owner),
                acquire = { acquirer.acquire(selection.release, selection.delivery) },
                onScreenDismissed = { _, _, _ ->
                    closed.complete(Unit)
                    JourneyScreenDismissalResult.COMPLETED
                },
                onOutcome = { closed.complete(Unit) },
            )
            withContext(Dispatchers.Main.immediate) { onReady() }
            closed.await()
        } finally {
            try {
                withContext(NonCancellable) { presentations.shutdownOwnedBy(owner) }
            } finally {
                scope.cancel()
            }
        }
    }
}
