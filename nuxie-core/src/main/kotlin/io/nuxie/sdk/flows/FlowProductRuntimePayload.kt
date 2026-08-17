package io.nuxie.sdk.flows

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

const val FLOW_PRODUCTS_MESSAGE_TYPE: String = "runtime/products"

fun buildFlowProductsRuntimePayload(products: List<FlowProduct>): JsonObject {
  return buildJsonObject {
    put(
      "products",
      JsonArray(
        products.map { product ->
          buildJsonObject {
            put("id", JsonPrimitive(product.id))
            put("name", JsonPrimitive(product.name))
            put("price", JsonPrimitive(product.price))
            product.period?.let { put("period", JsonPrimitive(it.runtimeWireValue)) }
            product.offer?.let { offer ->
              put("hasOffer", JsonPrimitive(true))
              put("offerId", JsonPrimitive(offer.id))
              put("offerType", JsonPrimitive(offer.type))
              put("offerPrice", JsonPrimitive(offer.price))
              put("offerPeriodLabel", JsonPrimitive(formatOfferPeriod(offer)))
              put("offerLabel", JsonPrimitive(offer.label))
            }
          }
        }
      ),
    )
  }
}

private fun formatOfferPeriod(offer: FlowProductOffer): String {
  val unit = offer.period?.runtimeWireValue ?: "period"
  return if (offer.periodCount == 1) unit else "${offer.periodCount} ${unit}s"
}

private val ProductPeriod.runtimeWireValue: String
  get() = when (this) {
    ProductPeriod.DAY -> "day"
    ProductPeriod.WEEK -> "week"
    ProductPeriod.MONTH -> "month"
    ProductPeriod.YEAR -> "year"
    ProductPeriod.LIFETIME -> "lifetime"
  }
