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
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
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
    interactions: Map<String, List<Interaction>> = emptyMap(),
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
      interactions = interactions,
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
  fun flow_does_not_cache_when_product_enrichment_fails() = runTest {
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
    val api = FakeApi { remoteFlow }
    var productFetchCalls = 0
    val productService = FlowProductService { productIds ->
      productFetchCalls += 1
      if (productFetchCalls == 1) {
        throw FlowProductFetchException("billing unavailable")
      }
      productIds.map { productId ->
        FlowProduct(
          id = productId,
          name = productId,
          price = "\$9.99",
          period = ProductPeriod.MONTH,
        )
      }
    }
    val store = FlowStore(api, productService = productService)

    try {
      store.flow("flow_1")
      fail("expected FlowProductFetchException")
    } catch (_: FlowProductFetchException) {
    }

    val flow = store.flow("flow_1")

    assertEquals(2, api.fetchFlowCalls)
    assertEquals(2, productFetchCalls)
    assertEquals("pro_monthly", flow.products.single().id)
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
  fun flow_extracts_product_ids_from_referenced_view_model_instances() = runTest {
    val productService = FakeProductService()
    val productViewModel = ViewModel(
      id = "vm_product",
      name = "Product",
      properties = mapOf(
        "productId" to ViewModelProperty(type = ViewModelPropertyType.STRING),
      ),
    )
    val remoteFlow = sampleRemoteFlow(
      id = "flow_1",
      viewModels = listOf(
        paywallViewModel(
          properties = mapOf(
            "primaryOffer" to ViewModelProperty(
              type = ViewModelPropertyType.VIEW_MODEL,
              viewModelId = "vm_product",
            ),
            "otherOffers" to ViewModelProperty(
              type = ViewModelPropertyType.LIST,
              itemType = ViewModelProperty(
                type = ViewModelPropertyType.VIEW_MODEL,
                viewModelId = "vm_product",
              ),
            ),
          )
        ),
        productViewModel,
      ),
      viewModelInstances = listOf(
        ViewModelInstance(
          viewModelId = "vm_paywall",
          instanceId = "paywall_1",
          values = mapOf(
            "primaryOffer" to JsonObject(mapOf("vmInstanceId" to JsonPrimitive("product_monthly"))),
            "otherOffers" to JsonArray(
              listOf(
                JsonObject(mapOf("instanceId" to JsonPrimitive("product_annual"))),
              )
            ),
          ),
        ),
        ViewModelInstance(
          viewModelId = "vm_product",
          instanceId = "product_monthly",
          values = mapOf("productId" to JsonPrimitive("pro_monthly")),
        ),
        ViewModelInstance(
          viewModelId = "vm_product",
          instanceId = "product_annual",
          values = mapOf("productId" to JsonPrimitive("pro_annual")),
        ),
      ),
    )
    val api = FakeApi { remoteFlow }
    val store = FlowStore(api, productService = productService)

    val flow = store.flow("flow_1")

    assertEquals(listOf(setOf("pro_monthly", "pro_annual")), productService.requests)
    assertEquals(listOf("pro_monthly", "pro_annual"), flow.products.map { it.id })
  }

  @Test
  fun flow_extracts_product_ids_from_purchase_actions() = runTest {
    val productService = FakeProductService()
    val purchasePlacement = JsonPrimitive(0)
    val remoteFlow = sampleRemoteFlow(
      id = "flow_1",
      interactions = mapOf(
        "screen_1" to listOf(
          Interaction(
            id = "purchase_button",
            trigger = InteractionTrigger.Press,
            actions = listOf(
              InteractionAction.Purchase(
                placementIndex = purchasePlacement,
                productId = JsonPrimitive("direct_monthly"),
              ),
              InteractionAction.Condition(
                branches = listOf(
                  InteractionAction.ConditionBranch(
                    id = "annual_branch",
                    actions = listOf(
                      InteractionAction.Purchase(
                        placementIndex = purchasePlacement,
                        productId = JsonPrimitive("branch_annual"),
                      )
                    ),
                  )
                ),
                defaultActions = listOf(
                  InteractionAction.Experiment(
                    experimentId = "pricing_test",
                    variants = listOf(
                      InteractionAction.ExperimentVariant(
                        id = "lifetime",
                        percentage = 100.0,
                        actions = listOf(
                          InteractionAction.TimeWindow(
                            startTime = "00:00",
                            endTime = "23:59",
                            timezone = "UTC",
                            successActions = listOf(
                              InteractionAction.Purchase(
                                placementIndex = purchasePlacement,
                                productId = JsonObject(mapOf("id" to JsonPrimitive("window_lifetime"))),
                              )
                            ),
                          )
                        ),
                      )
                    ),
                  )
                ),
              )
            ),
          )
        ),
      ),
    )
    val api = FakeApi { remoteFlow }
    val store = FlowStore(api, productService = productService)

    val flow = store.flow("flow_1")

    assertEquals(listOf(setOf("direct_monthly", "branch_annual", "window_lifetime")), productService.requests)
    assertEquals(listOf("direct_monthly", "branch_annual", "window_lifetime"), flow.products.map { it.id })
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

  @Test
  fun flow_applies_the_selected_offers_own_renewal_terms() = runTest {
    val monthlyOffer = FlowProductOffer(
      id = "monthly-offer",
      type = "developerDetermined",
      price = "\$1.99",
      period = ProductPeriod.MONTH,
      periodCount = 1,
      label = "\$1.99 for 1 month",
      offerToken = "monthly-token",
      basePrice = "\$9.99",
      basePeriod = ProductPeriod.MONTH,
    )
    val annualOffer = FlowProductOffer(
      id = "annual-offer",
      type = "developerDetermined",
      price = "\$19.99",
      period = ProductPeriod.YEAR,
      periodCount = 1,
      label = "\$19.99 for 1 year",
      offerToken = "annual-token",
      basePrice = "\$99.99",
      basePeriod = ProductPeriod.YEAR,
    )
    val productService = FakeProductService(
      mapOf(
        "pro" to FlowProduct(
          id = "pro",
          name = "Pro",
          price = "\$9.99",
          period = ProductPeriod.MONTH,
          offer = monthlyOffer,
          offers = listOf(monthlyOffer, annualOffer),
        )
      )
    )
    val remoteFlow = sampleRemoteFlow(
      id = "flow_1",
      viewModels = listOf(
        paywallViewModel(
          mapOf(
            "productId" to ViewModelProperty(type = ViewModelPropertyType.STRING),
            "offerId" to ViewModelProperty(type = ViewModelPropertyType.STRING),
          )
        )
      ),
      viewModelInstances = listOf(
        ViewModelInstance(
          viewModelId = "vm_paywall",
          instanceId = "paywall_1",
          values = mapOf(
            "productId" to JsonPrimitive("pro"),
            "offerId" to JsonPrimitive("annual-offer"),
          ),
        )
      ),
    )

    val flow = FlowStore(FakeApi { remoteFlow }, productService).flow("flow_1")
    val product = flow.products.single()

    assertEquals("annual-offer", product.offer?.id)
    assertEquals("annual-token", product.offer?.offerToken)
    assertEquals("\$99.99", product.price)
    assertEquals(ProductPeriod.YEAR, product.period)
  }

  @Test
  fun flow_rejects_conflicting_offer_bindings_for_the_same_product() = runTest {
    val remoteFlow = sampleRemoteFlow(
      id = "flow_1",
      viewModels = listOf(
        paywallViewModel(
          mapOf(
            "productId" to ViewModelProperty(type = ViewModelPropertyType.STRING),
            "offerId" to ViewModelProperty(type = ViewModelPropertyType.STRING),
          )
        )
      ),
      viewModelInstances = listOf(
        ViewModelInstance(
          viewModelId = "vm_paywall",
          instanceId = "paywall_1",
          values = mapOf(
            "productId" to JsonPrimitive("pro"),
            "offerId" to JsonPrimitive("monthly-offer"),
          ),
        ),
        ViewModelInstance(
          viewModelId = "vm_paywall",
          instanceId = "paywall_2",
          values = mapOf(
            "productId" to JsonPrimitive("pro"),
            "offerId" to JsonPrimitive("annual-offer"),
          ),
        ),
      ),
    )

    try {
      FlowStore(FakeApi { remoteFlow }, FakeProductService()).flow("flow_1")
      fail("expected FlowProductFetchException")
    } catch (_: FlowProductFetchException) {
      // Expected: the runtime cannot safely choose between moment-bound offers.
    }
  }

  @Test
  fun flow_combines_defaults_with_sparse_instance_offer_bindings() = runTest {
    val selectedOffer = FlowProductOffer(
      id = "annual-offer",
      type = "developerDetermined",
      price = "\$19.99",
      period = ProductPeriod.YEAR,
      periodCount = 1,
      label = "\$19.99 for 1 year",
      offerToken = "annual-token",
    )
    val productService = FakeProductService(
      mapOf(
        "pro" to FlowProduct(
          id = "pro",
          name = "Pro",
          price = "\$99.99",
          offers = listOf(selectedOffer),
        )
      )
    )
    val cases = listOf(
      mapOf("productId" to JsonPrimitive("pro")) to
        mapOf("offerId" to JsonPrimitive("annual-offer")),
      mapOf("offerId" to JsonPrimitive("annual-offer")) to
        mapOf("productId" to JsonPrimitive("pro")),
    )

    for ((defaults, instanceValues) in cases) {
      val remoteFlow = sampleRemoteFlow(
        id = "flow_${defaults.keys.single()}",
        viewModels = listOf(
          paywallViewModel(
            mapOf(
              "productId" to ViewModelProperty(
                type = ViewModelPropertyType.STRING,
                defaultValue = defaults["productId"],
              ),
              "offerId" to ViewModelProperty(
                type = ViewModelPropertyType.STRING,
                defaultValue = defaults["offerId"],
              ),
            )
          )
        ),
        viewModelInstances = listOf(
          ViewModelInstance(
            viewModelId = "vm_paywall",
            instanceId = "paywall_1",
            values = instanceValues,
          )
        ),
      )

      val flow = FlowStore(FakeApi { remoteFlow }, productService).flow(remoteFlow.id)

      assertEquals("annual-offer", flow.products.single().offer?.id)
    }
  }
}
