package ai.nuxie.sdk.presentation

import ai.nuxie.sdk.journey.JourneyActionType
import ai.nuxie.sdk.runtime.NuxieViewModelScalarValue

/** Correlates store feedback with the screen and invocation that started it. */
internal class JourneyCommerceFeedback {
    data class Write(val screenId: String, val values: Map<String, NuxieViewModelScalarValue>)
    private data class Projection(val screenId: String, val effectId: String)
    private val projections = mutableMapOf<JourneyActionType, Projection>()

    @Synchronized
    fun begin(type: JourneyActionType, screenId: String, effectId: String): Write {
        require(type.isCommerce)
        val projection = Projection(screenId, effectId)
        projections[type] = projection
        return write(type, projection, "idle", "")
    }

    @Synchronized
    fun running(type: JourneyActionType, effectId: String): Write? =
        projections[type]?.takeIf { it.effectId == effectId }?.let { write(type, it, "running", "") }

    @Synchronized
    fun outcome(type: JourneyActionType, outlet: String, errorCode: String, effectId: String): Write? {
        val projection = projections[type]?.takeIf { it.effectId == effectId } ?: return null
        val status = when (outlet) {
            "completed", "restored" -> "success"
            "failed" -> "error"
            "cancelled" -> "cancelled"
            "noPurchases" -> "not_found"
            else -> return null
        }
        projections.remove(type)
        return write(type, projection, status, if (outlet == "failed") errorCode else "")
    }

    private fun write(type: JourneyActionType, projection: Projection, status: String, errorCode: String): Write {
        val path = "paywall/${type.wireValue}"
        return Write(projection.screenId, mapOf(
            "$path/status" to NuxieViewModelScalarValue.StringValue(status),
            "$path/errorCode" to NuxieViewModelScalarValue.StringValue(errorCode),
            "$path/invocationId" to NuxieViewModelScalarValue.StringValue(projection.effectId),
        ))
    }
}
