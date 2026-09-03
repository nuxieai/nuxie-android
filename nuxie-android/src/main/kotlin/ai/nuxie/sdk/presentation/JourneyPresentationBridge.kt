package ai.nuxie.sdk.presentation

import ai.nuxie.sdk.experiences.AuthenticatedJourneyRelease
import ai.nuxie.sdk.experiences.JourneyReleaseDelivery
import ai.nuxie.sdk.journey.JourneyActionType
import java.io.Closeable
import kotlinx.serialization.json.JsonObject

/** A process-local claim on the one engine-owned presentation surface. */
internal interface JourneyPresentationReservation : Closeable

/** The only surface outcomes the flat journey executor consumes. */
internal enum class JourneySurfaceOutcome {
    DISMISSED,
    ABANDONED,
}

internal enum class JourneyScreenDismissalResult {
    HANDLED,
    COMPLETED,
    REJECTED,
}

internal data class JourneyScreenEmissionSource(
    val screenId: String,
    val actionId: String,
    val componentId: String? = null,
    val instanceId: String? = null,
)

internal data class JourneyScreenEmission(
    val id: String,
    val sequence: Long,
    val occurredAtMillis: Long,
    val name: String,
    val payload: JsonObject,
)

/** One runtime invocation. The executor either durably stages all emissions or rejects all. */
internal data class JourneyScreenEmissionBatch(
    val journeyId: String,
    val batchSequence: Long,
    val invocationId: String,
    val source: JourneyScreenEmissionSource,
    val emissions: List<JourneyScreenEmission>,
)

internal data class JourneyPresentationRequest(
    val release: AuthenticatedJourneyRelease,
    val delivery: JourneyReleaseDelivery,
    val screenId: String,
    val journeyId: String,
    val ownerDistinctId: String,
    val reservation: JourneyPresentationReservation?,
    val canPresent: () -> Boolean,
    val nextBatchSequence: Long = 0,
    val nextEmissionSequence: Long = 0,
    val onScreenChanged: suspend (String) -> Boolean = { true },
    val onScreenDismissed: suspend (
        screenId: String,
        revealingScreenId: String?,
        method: String,
    ) -> JourneyScreenDismissalResult = { _, _, _ ->
        JourneyScreenDismissalResult.HANDLED
    },
    val onEmissionBatch: suspend (JourneyScreenEmissionBatch) -> Boolean = { true },
    val onPresentationRevealed: suspend (String) -> Unit = {},
    val onOutcome: suspend (JourneySurfaceOutcome) -> Unit,
)

internal sealed interface JourneyPresentationResult {
    data object Shown : JourneyPresentationResult
    data object Completed : JourneyPresentationResult
    data object ProductsUnavailable : JourneyPresentationResult
    data object Declined : JourneyPresentationResult
    data object Failed : JourneyPresentationResult
}

internal data class JourneyPresentationOwner(
    val journeyId: String,
    val distinctId: String,
)

internal data class JourneyPresentationPermissionEvent(
    val name: String,
    val properties: Map<String, Any?>,
)

/** Result of an action that must execute against the currently owned surface. */
internal sealed interface JourneyPresentationActionResult {
    data class Navigate(val screenId: String) : JourneyPresentationActionResult
    data class Advanced(val outlet: String) : JourneyPresentationActionResult
    data class PermissionResolved(
        val outlet: String,
        val event: JourneyPresentationPermissionEvent,
    ) : JourneyPresentationActionResult
    data object AwaitingOutcome : JourneyPresentationActionResult
    data object Handled : JourneyPresentationActionResult
    data object ProductsUnavailable : JourneyPresentationActionResult
    data object NoPresentation : JourneyPresentationActionResult
    data object Declined : JourneyPresentationActionResult
    data object Failed : JourneyPresentationActionResult
}

/** Adapter owned by NuxieCore so the executor has no Activity dependency. */
internal interface JourneyPresenting {
    fun reserve(ownerDistinctId: String): JourneyPresentationReservation?

    suspend fun present(request: JourneyPresentationRequest): JourneyPresentationResult

    fun owns(owner: JourneyPresentationOwner): Boolean = false

    fun screenId(owner: JourneyPresentationOwner): String? = null

    fun resolveAction(
        owner: JourneyPresentationOwner,
        action: JsonObject,
        source: JourneyScreenEmissionSource?,
    ): JsonObject? = action

    suspend fun dispatchAction(
        owner: JourneyPresentationOwner,
        action: JsonObject,
        effectId: String,
    ): JourneyPresentationActionResult =
        if (JourneyActionType.from(action)?.isPresentationOwned == true) {
            JourneyPresentationActionResult.Failed
        } else {
            JourneyPresentationActionResult.Declined
        }

    fun cancelBackNavigation(owner: JourneyPresentationOwner) = Unit

    suspend fun shutdownPresentation(ownerDistinctId: String, journeyId: String) {
        shutdownOwnedBy(ownerDistinctId)
    }

    suspend fun shutdownOwnedBy(ownerDistinctId: String)
}
