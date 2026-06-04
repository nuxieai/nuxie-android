package io.nuxie.sdk.network.models

import io.nuxie.sdk.purchases.PlayStoreProductType
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

@Serializable
data class ProfileRequest(
  @SerialName("distinct_id")
  val distinctId: String,
  val locale: String? = null,
  val groups: JsonObject? = null,
  val version: Int? = 1,
)

@Serializable
data class EventRequest(
  val event: String,
  @SerialName("distinct_id")
  val distinctId: String,
  @SerialName("\$anon_distinct_id")
  val anonDistinctId: String? = null,
  val timestamp: String? = null,
  val properties: JsonObject? = null,
  val uuid: String? = null,
  val value: Double? = null,
  val entityId: String? = null,
)

@Serializable
data class BatchEventItem(
  val event: String,
  @SerialName("distinct_id")
  val distinctId: String,
  @SerialName("\$anon_distinct_id")
  val anonDistinctId: String? = null,
  val timestamp: String? = null,
  val properties: JsonObject? = null,
  val uuid: String? = null,
  val value: Double? = null,
  val entityId: String? = null,
)

@Serializable
data class BatchRequest(
  @SerialName("historical_migration")
  val historicalMigration: Boolean? = null,
  val batch: List<BatchEventItem>,
)

@Serializable
data class FeatureCheckRequest(
  val customerId: String,
  val featureId: String,
  val requiredBalance: Int? = null,
  val entityId: String? = null,
)

@Serializable
data class PlayStorePurchaseRequest(
  val type: String,
  @SerialName("purchase_token")
  val purchaseToken: String,
  @SerialName("product_id")
  val productId: String? = null,
  @SerialName("package_name")
  val packageName: String? = null,
  @SerialName("base_plan_id")
  val basePlanId: String? = null,
  @SerialName("distinct_id")
  val distinctId: String? = null,
  @SerialName("product_type")
  val productType: PlayStoreProductType? = null,
  @SerialName("consume_purchase")
  val consumePurchase: Boolean? = null,
) {
  init {
    require(type == "playstore") { "Play Store purchase requests must use type=playstore" }
  }
}

@Serializable
data class ResponseFieldRequest(
  @SerialName("distinct_id")
  val distinctId: String,
  @SerialName("journey_session_id")
  val journeySessionId: String,
  @SerialName("response_schema_id")
  val responseSchemaId: String,
  @SerialName("schema_version")
  val schemaVersion: Int? = null,
  val key: String,
  val value: JsonElement,
)

@Serializable
data class ResponseSubmitRequest(
  @SerialName("distinct_id")
  val distinctId: String,
  @SerialName("journey_session_id")
  val journeySessionId: String,
  @SerialName("response_schema_id")
  val responseSchemaId: String,
  @SerialName("schema_version")
  val schemaVersion: Int? = null,
)

@Serializable
data class ResponseAbandonRequest(
  @SerialName("distinct_id")
  val distinctId: String,
  @SerialName("journey_session_id")
  val journeySessionId: String,
)
