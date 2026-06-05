package io.nuxie.sdk.flows

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class FlowProductRuntimePayloadTest {
  @Test
  fun buildFlowProductsRuntimePayload_encodes_ios_compatible_product_shape() {
    val payload = buildFlowProductsRuntimePayload(
      listOf(
        FlowProduct(
          id = "pro_monthly",
          name = "Pro Monthly",
          price = "$9.99",
          period = ProductPeriod.MONTH,
        ),
        FlowProduct(
          id = "lifetime_unlock",
          name = "Lifetime Unlock",
          price = "$99.99",
        ),
      )
    )

    val products = payload["products"] as JsonArray
    val subscription = products[0] as JsonObject
    val oneTime = products[1] as JsonObject

    assertEquals(JsonPrimitive("pro_monthly"), subscription["id"])
    assertEquals(JsonPrimitive("Pro Monthly"), subscription["name"])
    assertEquals(JsonPrimitive("$9.99"), subscription["price"])
    assertEquals(JsonPrimitive("month"), subscription["period"])
    assertEquals(JsonPrimitive("lifetime_unlock"), oneTime["id"])
    assertEquals(JsonPrimitive("Lifetime Unlock"), oneTime["name"])
    assertEquals(JsonPrimitive("$99.99"), oneTime["price"])
    assertFalse(oneTime.containsKey("period"))
  }

  @Test
  fun buildFlowProductsRuntimePayload_encodes_all_periods_as_lowercase_wire_values() {
    val payload = buildFlowProductsRuntimePayload(
      listOf(
        FlowProduct(id = "weekly", name = "Weekly", price = "$1.99", period = ProductPeriod.WEEK),
        FlowProduct(id = "monthly", name = "Monthly", price = "$9.99", period = ProductPeriod.MONTH),
        FlowProduct(id = "annual", name = "Annual", price = "$79.99", period = ProductPeriod.YEAR),
        FlowProduct(id = "forever", name = "Forever", price = "$149.99", period = ProductPeriod.LIFETIME),
      )
    )

    val products = payload["products"] as JsonArray

    assertEquals(JsonPrimitive("week"), (products[0] as JsonObject)["period"])
    assertEquals(JsonPrimitive("month"), (products[1] as JsonObject)["period"])
    assertEquals(JsonPrimitive("year"), (products[2] as JsonObject)["period"])
    assertEquals(JsonPrimitive("lifetime"), (products[3] as JsonObject)["period"])
  }
}
