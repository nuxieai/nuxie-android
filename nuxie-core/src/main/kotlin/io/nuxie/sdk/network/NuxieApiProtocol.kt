package io.nuxie.sdk.network

import io.nuxie.sdk.features.FeatureCheckResult
import io.nuxie.sdk.flows.RemoteFlow
import io.nuxie.sdk.network.models.BatchRequest
import io.nuxie.sdk.network.models.BatchResponse
import io.nuxie.sdk.network.models.EventResponse
import io.nuxie.sdk.network.models.PlayStorePurchaseRequest
import io.nuxie.sdk.network.models.ProfileResponse
import io.nuxie.sdk.network.models.ResponseAbandonResponse
import io.nuxie.sdk.network.models.ResponseSubmitResponse
import io.nuxie.sdk.network.models.ResponseWriteResponse
import io.nuxie.sdk.features.PurchaseResponse
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonElement

/**
 * Lightweight interface for the Nuxie HTTP client.
 *
 * Mirrors iOS `NuxieApiProtocol` so core services can be unit tested with fakes.
 */
interface NuxieApiProtocol {
  suspend fun fetchProfile(distinctId: String, locale: String? = null): ProfileResponse

  suspend fun trackEvent(
    event: String,
    distinctId: String,
    anonDistinctId: String? = null,
    properties: JsonObject? = null,
    uuid: String,
    value: Double? = null,
    entityId: String? = null,
    timestamp: String,
  ): EventResponse

  suspend fun sendBatch(batch: BatchRequest): BatchResponse

  suspend fun fetchFlow(flowId: String): RemoteFlow

  suspend fun checkFeature(
    customerId: String,
    featureId: String,
    requiredBalance: Int? = null,
    entityId: String? = null,
  ): FeatureCheckResult

  suspend fun syncPlayStorePurchase(request: PlayStorePurchaseRequest): PurchaseResponse {
    throw UnsupportedOperationException("syncPlayStorePurchase is not implemented")
  }

  suspend fun setResponseField(
    distinctId: String,
    journeySessionId: String,
    responseSchemaId: String,
    schemaVersion: Int? = null,
    key: String,
    value: JsonElement,
  ): ResponseWriteResponse {
    throw UnsupportedOperationException("setResponseField is not implemented")
  }

  suspend fun submitResponse(
    distinctId: String,
    journeySessionId: String,
    responseSchemaId: String,
    schemaVersion: Int? = null,
  ): ResponseSubmitResponse {
    throw UnsupportedOperationException("submitResponse is not implemented")
  }

  suspend fun abandonResponses(
    distinctId: String,
    journeySessionId: String,
  ): ResponseAbandonResponse {
    throw UnsupportedOperationException("abandonResponses is not implemented")
  }
}
