package io.nuxie.sdk.flows

import io.nuxie.sdk.features.FeatureCheckResult
import io.nuxie.sdk.network.NuxieApiProtocol
import io.nuxie.sdk.network.models.BatchRequest
import io.nuxie.sdk.network.models.BatchResponse
import io.nuxie.sdk.network.models.EventResponse
import io.nuxie.sdk.network.models.ProfileResponse
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test

class FlowStoreTest {

  private class FakeApi(
    private val onFetchFlow: suspend (String) -> RemoteFlow,
  ) : NuxieApiProtocol {
    var fetchFlowCalls: Int = 0

    override suspend fun fetchProfile(distinctId: String, locale: String?): ProfileResponse {
      throw UnsupportedOperationException()
    }

    override suspend fun trackEvent(
      event: String,
      distinctId: String,
      anonDistinctId: String?,
      properties: JsonObject?,
      uuid: String,
      value: Double?,
      entityId: String?,
      timestamp: String,
    ): EventResponse {
      throw UnsupportedOperationException()
    }

    override suspend fun sendBatch(batch: BatchRequest): BatchResponse {
      throw UnsupportedOperationException()
    }

    override suspend fun fetchFlow(flowId: String): RemoteFlow {
      fetchFlowCalls += 1
      return onFetchFlow(flowId)
    }

    override suspend fun checkFeature(
      customerId: String,
      featureId: String,
      requiredBalance: Int?,
      entityId: String?,
    ): FeatureCheckResult {
      throw UnsupportedOperationException()
    }
  }

  private class FakeProductService(
    private val products: Map<String, FlowProduct> = emptyMap(),
  ) : FlowProductService {
    val requests = mutableListOf<Set<String>>()

    override suspend fun fetchProducts(productIds: Set<String>): List<FlowProduct> {
      requests += productIds
      return productIds.map { productId ->
        products[productId] ?: FlowProduct(
          id = productId,
          name = productId,
          price = "\$9.99",
          period = ProductPeriod.MONTH,
        )
      }
    }
  }

  private fun sampleRemoteFlow(
    id: String,
    contentHash: String = "sha256:abc",
    viewModels: List<ViewModel> = emptyList(),
    viewModelInstances: List<ViewModelInstance>? = null,
  ): RemoteFlow {
    val manifest = BuildManifest(
      totalFiles = 1,
      totalSize = 10,
      contentHash = contentHash,
      files = listOf(
        BuildManifestFile(path = "index.html", size = 10, contentType = "text/html"),
      ),
    )
    return RemoteFlow(
      id = id,
      bundle = FlowBundleRef(
        url = "https://example.com/flows/$id/",
        manifest = manifest,
      ),
      screens = listOf(RemoteFlowScreen(id = "screen_1")),
      interactions = emptyMap(),
      viewModels = viewModels,
      viewModelInstances = viewModelInstances,
    )
  }

  private fun paywallViewModel(
    properties: Map<String, ViewModelProperty>,
  ): ViewModel {
    return ViewModel(
      id = "vm_paywall",
      name = "Paywall",
      properties = properties,
    )
  }

  @Test
  fun flow_returns_cached_on_second_call() = runTest {
    val api = FakeApi { id -> sampleRemoteFlow(id) }
    val store = FlowStore(api)

    val f1 = store.flow("flow_1")
    val f2 = store.flow("flow_1")

    assertEquals("flow_1", f1.id)
    assertEquals("flow_1", f2.id)
    assertEquals(1, api.fetchFlowCalls)
  }

  @Test
  fun flow_dedupes_concurrent_fetches() = runTest {
    val api = FakeApi { id ->
      delay(100)
      sampleRemoteFlow(id)
    }
    val store = FlowStore(api)

    val a = async { store.flow("flow_1") }
    val b = async { store.flow("flow_1") }

    assertEquals("flow_1", a.await().id)
    assertEquals("flow_1", b.await().id)
    assertEquals(1, api.fetchFlowCalls)
  }

  @Test
  fun preloadFlows_seeds_cache_without_network() = runTest {
    val api = FakeApi { throw AssertionError("network should not be called") }
    val store = FlowStore(api)

    store.preloadFlows(listOf(sampleRemoteFlow("flow_1")))

    assertEquals("flow_1", store.flow("flow_1").id)
    assertEquals(0, api.fetchFlowCalls)
  }

  @Test
  fun flow_enriches_products_from_productId_instance_values() = runTest {
    val productService = FakeProductService(
      products = mapOf(
        "pro_monthly" to FlowProduct(
          id = "pro_monthly",
          name = "Nuxie Pro",
          price = "\$9.99",
          period = ProductPeriod.MONTH,
        )
      )
    )
    val remoteFlow = sampleRemoteFlow(
      id = "flow_1",
      viewModels = listOf(
        paywallViewModel(
          properties = mapOf(
            "title" to ViewModelProperty(type = ViewModelPropertyType.STRING),
            "productId" to ViewModelProperty(type = ViewModelPropertyType.STRING),
          )
        )
      ),
      viewModelInstances = listOf(
        ViewModelInstance(
          viewModelId = "vm_paywall",
          instanceId = "paywall_1",
          values = mapOf(
            "title" to JsonPrimitive("Upgrade"),
            "productId" to JsonPrimitive("pro_monthly"),
          ),
        )
      ),
    )
    val api = FakeApi { remoteFlow }
    val store = FlowStore(api, productService = productService)

    val flow = store.flow("flow_1")

    assertEquals(listOf(setOf("pro_monthly")), productService.requests)
    assertEquals(
      listOf(
        FlowProduct(
          id = "pro_monthly",
          name = "Nuxie Pro",
          price = "\$9.99",
          period = ProductPeriod.MONTH,
        )
      ),
      flow.products,
    )
  }

  @Test
  fun flow_enriches_products_from_productId_defaults() = runTest {
    val productService = FakeProductService()
    val remoteFlow = sampleRemoteFlow(
      id = "flow_1",
      viewModels = listOf(
        paywallViewModel(
          properties = mapOf(
            "productId" to ViewModelProperty(
              type = ViewModelPropertyType.STRING,
              defaultValue = JsonPrimitive("pro_annual"),
            ),
          )
        )
      ),
    )
    val api = FakeApi { remoteFlow }
    val store = FlowStore(api, productService = productService)

    val flow = store.flow("flow_1")

    assertEquals(listOf(setOf("pro_annual")), productService.requests)
    assertEquals("pro_annual", flow.products.single().id)
  }

  @Test
  fun flow_extracts_object_shaped_product_ids() = runTest {
    val productService = FakeProductService()
    val remoteFlow = sampleRemoteFlow(
      id = "flow_1",
      viewModels = listOf(
        paywallViewModel(
          properties = mapOf(
            "productId" to ViewModelProperty(
              type = ViewModelPropertyType.OBJECT,
              defaultValue = JsonObject(mapOf("id" to JsonPrimitive("lifetime_unlock"))),
            ),
          )
        )
      ),
    )
    val api = FakeApi { remoteFlow }
    val store = FlowStore(api, productService = productService)

    val flow = store.flow("flow_1")

    assertEquals(listOf(setOf("lifetime_unlock")), productService.requests)
    assertEquals("lifetime_unlock", flow.products.single().id)
  }

  @Test
  fun flow_extracts_nested_productId_schema_fields() = runTest {
    val productService = FakeProductService()
    val remoteFlow = sampleRemoteFlow(
      id = "flow_1",
      viewModels = listOf(
        paywallViewModel(
          properties = mapOf(
            "offer" to ViewModelProperty(
              type = ViewModelPropertyType.OBJECT,
              schema = mapOf(
                "headline" to ViewModelProperty(type = ViewModelPropertyType.STRING),
                "productId" to ViewModelProperty(type = ViewModelPropertyType.STRING),
              ),
            ),
          )
        )
      ),
      viewModelInstances = listOf(
        ViewModelInstance(
          viewModelId = "vm_paywall",
          instanceId = "paywall_1",
          values = mapOf(
            "offer" to JsonObject(
              mapOf(
                "headline" to JsonPrimitive("Annual plan"),
                "productId" to JsonPrimitive("pro_annual"),
              )
            ),
          ),
        )
      ),
    )
    val api = FakeApi { remoteFlow }
    val store = FlowStore(api, productService = productService)

    val flow = store.flow("flow_1")

    assertEquals(listOf(setOf("pro_annual")), productService.requests)
    assertEquals("pro_annual", flow.products.single().id)
  }

  @Test
  fun flow_does_not_fetch_arbitrary_string_values() = runTest {
    val productService = FakeProductService()
    val remoteFlow = sampleRemoteFlow(
      id = "flow_1",
      viewModels = listOf(
        paywallViewModel(
          properties = mapOf(
            "headline" to ViewModelProperty(
              type = ViewModelPropertyType.STRING,
              defaultValue = JsonPrimitive("pro_monthly"),
            ),
            "selectedProductId" to ViewModelProperty(
              type = ViewModelPropertyType.STRING,
              defaultValue = JsonPrimitive("pro_annual"),
            ),
          )
        )
      ),
    )
    val api = FakeApi { remoteFlow }
    val store = FlowStore(api, productService = productService)

    val flow = store.flow("flow_1")

    assertEquals(emptyList<Set<String>>(), productService.requests)
    assertEquals(emptyList<FlowProduct>(), flow.products)
  }

  @Test
  fun preloadFlows_enriches_products_and_caches_flow_without_network() = runTest {
    val productService = FakeProductService()
    val remoteFlow = sampleRemoteFlow(
      id = "flow_1",
      viewModels = listOf(
        paywallViewModel(
          properties = mapOf(
            "productId" to ViewModelProperty(
              type = ViewModelPropertyType.STRING,
              defaultValue = JsonPrimitive("pro_monthly"),
            ),
          )
        )
      ),
    )
    val api = FakeApi { throw AssertionError("network should not be called") }
    val store = FlowStore(api, productService = productService)

    store.preloadFlows(listOf(remoteFlow))
    val flow = store.flow("flow_1")

    assertEquals(0, api.fetchFlowCalls)
    assertEquals(listOf(setOf("pro_monthly")), productService.requests)
    assertEquals("pro_monthly", flow.products.single().id)
  }
}
