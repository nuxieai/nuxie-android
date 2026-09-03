package ai.nuxie.sdk.presentation

import ai.nuxie.sdk.experiences.AuthenticatedDeviceLegRelease
import ai.nuxie.sdk.experiences.Delivery
import java.io.Closeable
import kotlinx.serialization.json.JsonObject

/** A process-local claim on the one engine-owned presentation surface. */
internal interface DeviceLegPresentationReservation : Closeable

/** The only surface outcomes the flat device-leg executor consumes. */
internal enum class DeviceLegSurfaceOutcome {
    DISMISSED,
    ABANDONED,
}

internal enum class DeviceLegScreenDismissalResult {
    HANDLED,
    COMPLETED,
    REJECTED,
}

internal data class DeviceLegScreenEmissionSource(
    val screenId: String,
    val actionId: String,
    val componentId: String? = null,
    val instanceId: String? = null,
)

internal data class DeviceLegScreenEmission(
    val id: String,
    val sequence: Long,
    val occurredAtMillis: Long,
    val name: String,
    val payload: JsonObject,
)

/** One runtime invocation. The executor either durably stages all emissions or rejects all. */
internal data class DeviceLegScreenEmissionBatch(
    val journeyId: String,
    val batchSequence: Long,
    val invocationId: String,
    val source: DeviceLegScreenEmissionSource,
    val emissions: List<DeviceLegScreenEmission>,
)

internal data class DeviceLegPresentationRequest(
    val release: AuthenticatedDeviceLegRelease,
    val delivery: Delivery,
    val screenId: String,
    val journeyId: String,
    val ownerDistinctId: String,
    val reservation: DeviceLegPresentationReservation?,
    val canPresent: () -> Boolean,
    val nextBatchSequence: Long = 0,
    val nextEmissionSequence: Long = 0,
    val onScreenChanged: suspend (String) -> Boolean = { true },
    val onScreenDismissed: suspend (
        screenId: String,
        revealingScreenId: String?,
        method: String,
    ) -> DeviceLegScreenDismissalResult = { _, _, _ ->
        DeviceLegScreenDismissalResult.HANDLED
    },
    val onEmissionBatch: suspend (DeviceLegScreenEmissionBatch) -> Boolean = { true },
    val onPresentationRevealed: suspend (String) -> Unit = {},
    val onOutcome: suspend (DeviceLegSurfaceOutcome) -> Unit,
)

internal sealed interface DeviceLegPresentationResult {
    data object Shown : DeviceLegPresentationResult
    data object Completed : DeviceLegPresentationResult
    data object Declined : DeviceLegPresentationResult
    data object Failed : DeviceLegPresentationResult
}

/** Adapter owned by NuxieCore so the executor has no Activity dependency. */
internal interface DeviceLegPresenting {
    fun reserve(ownerDistinctId: String): DeviceLegPresentationReservation?

    suspend fun present(request: DeviceLegPresentationRequest): DeviceLegPresentationResult

    suspend fun shutdownPresentation(ownerDistinctId: String, journeyId: String) {
        shutdownOwnedBy(ownerDistinctId)
    }

    suspend fun shutdownOwnedBy(ownerDistinctId: String)
}
