package io.nuxie.sdk.flows

/**
 * Fetches store-specific product metadata for products referenced by a flow.
 *
 * The core SDK stays platform-neutral; Android wires this to Google Play Billing
 * from the Android artifact, while JVM tests and non-Android consumers can use
 * the no-op/default implementation.
 */
fun interface FlowProductService {
  suspend fun fetchProducts(productIds: Set<String>): List<FlowProduct>
}

object NoopFlowProductService : FlowProductService {
  override suspend fun fetchProducts(productIds: Set<String>): List<FlowProduct> = emptyList()
}
