package ai.nuxie.sdk.presentation

import ai.nuxie.sdk.experiences.AuthenticatedDeviceLegRelease
import ai.nuxie.sdk.experiences.Delivery
import java.io.Closeable

/** A process-local claim on the one engine-owned presentation surface. */
internal interface DeviceLegPresentationReservation : Closeable

/** The only surface outcomes the flat device-leg executor consumes. */
internal enum class DeviceLegSurfaceOutcome {
    DISMISSED,
    ABANDONED,
}

internal data class DeviceLegPresentationRequest(
    val release: AuthenticatedDeviceLegRelease,
    val delivery: Delivery,
    val screenId: String,
    val journeyId: String,
    val ownerDistinctId: String,
    val reservation: DeviceLegPresentationReservation?,
    val canPresent: () -> Boolean,
    val onOutcome: suspend (DeviceLegSurfaceOutcome) -> Unit,
)

internal sealed interface DeviceLegPresentationResult {
    data object Shown : DeviceLegPresentationResult
    data object Declined : DeviceLegPresentationResult
    data object Failed : DeviceLegPresentationResult
}

/** Adapter owned by NuxieCore so the executor has no Activity dependency. */
internal interface DeviceLegPresenting {
    fun reserve(ownerDistinctId: String): DeviceLegPresentationReservation?

    suspend fun present(request: DeviceLegPresentationRequest): DeviceLegPresentationResult

    suspend fun shutdownOwnedBy(ownerDistinctId: String)
}
