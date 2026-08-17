package io.nuxie.sdk.flows

import io.nuxie.sdk.network.NuxieApiProtocol
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * Cache + fetch for Flow models.
 *
 * Mirrors iOS `FlowStore` behavior:
 * - in-memory cache of enriched `Flow` models
 * - concurrent fetch de-duping per flow id
 */
class FlowStore(
  private val api: NuxieApiProtocol,
  private val productService: FlowProductService = NoopFlowProductService,
) {
  private val mutex = Mutex()
  private val cachedById = mutableMapOf<String, Flow>()
  private val pendingById = mutableMapOf<String, CompletableDeferred<Flow>>()

  suspend fun flow(id: String): Flow {
    // Fast path: cached
    mutex.withLock {
      val cached = cachedById[id]
      if (cached != null) return cached
    }

    val deferred: CompletableDeferred<Flow>
    val shouldFetch: Boolean
    mutex.withLock {
      val cached = cachedById[id]
      if (cached != null) return cached

      val existing = pendingById[id]
      if (existing != null) {
        deferred = existing
        shouldFetch = false
      } else {
        deferred = CompletableDeferred()
        pendingById[id] = deferred
        shouldFetch = true
      }
    }

    if (shouldFetch) {
      try {
        val remote = api.fetchFlow(flowId = id)
        val flow = enrichFlow(remote)
        mutex.withLock { cachedById[id] = flow }
        deferred.complete(flow)
      } catch (t: Throwable) {
        deferred.completeExceptionally(t)
        throw t
      } finally {
        mutex.withLock { pendingById.remove(id) }
      }
    }

    return deferred.await()
  }

  /**
   * Seed the in-memory cache with profile-delivered flows.
   */
  suspend fun preloadFlows(remoteFlows: List<RemoteFlow>) {
    if (remoteFlows.isEmpty()) return
    val flows = remoteFlows.map { enrichFlow(it) }
    mutex.withLock {
      for (flow in flows) {
        cachedById[flow.id] = flow
      }
    }
  }

  suspend fun removeFlow(id: String) {
    mutex.withLock {
      cachedById.remove(id)
      pendingById.remove(id)?.cancel(CancellationException("flow removed"))
    }
  }

  suspend fun clearCache() {
    mutex.withLock {
      cachedById.clear()
      for ((_, pending) in pendingById) {
        pending.cancel(CancellationException("cache cleared"))
      }
      pendingById.clear()
    }
  }

  private suspend fun enrichFlow(remoteFlow: RemoteFlow): Flow {
    val productIds = extractProductIds(remoteFlow)
    val selectedOfferIds = extractProductOfferIds(remoteFlow)
    val products = if (productIds.isEmpty()) {
      emptyList()
    } else {
      productService.fetchProducts(productIds).map { product ->
        val selectedOffer = selectedOfferIds[product.id]?.let { offerId ->
          product.offers.firstOrNull { it.id == offerId }
        }
        product.copy(
          price = selectedOffer?.basePrice ?: product.price,
          period = selectedOffer?.basePeriod ?: product.period,
          offer = selectedOffer,
        )
      }
    }

    return Flow(remoteFlow = remoteFlow, products = products)
  }

  private fun extractProductOfferIds(remoteFlow: RemoteFlow): Map<String, String> {
    val selections = linkedMapOf<String, String>()
    val instances = remoteFlow.viewModelInstances.orEmpty()
    val viewModelsById = remoteFlow.viewModels.associateBy { it.id }
    for (instance in instances) {
      val defaults = viewModelsById[instance.viewModelId]?.properties
        ?.mapNotNull { (name, property) ->
          property.defaultValue?.let { name to it }
        }
        ?.toMap()
        .orEmpty()
      collectProductOfferIds(JsonObject(defaults + instance.values), selections)
    }
    val instanceViewModelIds = instances.mapTo(mutableSetOf()) { it.viewModelId }
    for (viewModel in remoteFlow.viewModels) {
      if (viewModel.id in instanceViewModelIds) continue
      val defaults = viewModel.properties.mapNotNull { (name, property) ->
        property.defaultValue?.let { name to it }
      }.toMap()
      collectProductOfferIds(JsonObject(defaults), selections)
    }
    return selections
  }

  private fun collectProductOfferIds(
    value: JsonElement,
    selections: MutableMap<String, String>,
  ) {
    when (value) {
      is JsonObject -> {
        val productId = (value["productId"] as? JsonPrimitive)
          ?.contentOrNull?.trim()?.takeIf(String::isNotEmpty)
        val offerId = (value["offerId"] as? JsonPrimitive)
          ?.contentOrNull?.trim()?.takeIf(String::isNotEmpty)
        if (productId != null && offerId != null) {
          val existing = selections[productId]
          if (existing != null && existing != offerId) {
            throw FlowProductFetchException(
              "Flow binds product $productId to conflicting offers $existing and $offerId"
            )
          }
          selections[productId] = offerId
        }
        value.values.forEach { collectProductOfferIds(it, selections) }
      }
      is JsonArray -> value.forEach { collectProductOfferIds(it, selections) }
      else -> Unit
    }
  }

  private fun extractProductIds(remoteFlow: RemoteFlow): Set<String> {
    val ids = linkedSetOf<String>()
    val viewModelsById = remoteFlow.viewModels.associateBy { it.id }
    val instancesByViewModel = remoteFlow.viewModelInstances.orEmpty().groupBy { it.viewModelId }
    val instancesById = remoteFlow.viewModelInstances.orEmpty().associateBy { it.instanceId }

    for (viewModel in remoteFlow.viewModels) {
      val instances = instancesByViewModel[viewModel.id].orEmpty()
      if (instances.isEmpty()) {
        collectProductIdsFromSchema(
          schema = viewModel.properties,
          values = emptyMap(),
          viewModelsById = viewModelsById,
          instancesById = instancesById,
          ids = ids,
          path = emptyList(),
          visitedInstanceIds = emptySet(),
        )
      } else {
        for (instance in instances) {
          collectProductIdsFromSchema(
            schema = viewModel.properties,
            values = instance.values,
            viewModelsById = viewModelsById,
            instancesById = instancesById,
            ids = ids,
            path = emptyList(),
            visitedInstanceIds = setOf(instance.instanceId),
          )
        }
      }
    }

    for (interactions in remoteFlow.interactions.values) {
      for (interaction in interactions) {
        collectProductIdsFromActions(interaction.actions, ids)
      }
    }

    return ids
  }

  private fun collectProductIdsFromSchema(
    schema: Map<String, ViewModelProperty>,
    values: Map<String, JsonElement>,
    viewModelsById: Map<String, ViewModel>,
    instancesById: Map<String, ViewModelInstance>,
    ids: MutableSet<String>,
    path: List<String>,
    visitedInstanceIds: Set<String>,
  ) {
    for ((name, property) in schema) {
      val hasInstanceValue = values.containsKey(name)
      val value = if (hasInstanceValue) values[name] else property.defaultValue
      collectProductIdsFromProperty(
        property = property,
        value = value,
        viewModelsById = viewModelsById,
        instancesById = instancesById,
        ids = ids,
        path = path + name,
        visitedInstanceIds = visitedInstanceIds,
      )
    }
  }

  private fun collectProductIdsFromProperty(
    property: ViewModelProperty,
    value: JsonElement?,
    viewModelsById: Map<String, ViewModel>,
    instancesById: Map<String, ViewModelInstance>,
    ids: MutableSet<String>,
    path: List<String>,
    visitedInstanceIds: Set<String>,
  ) {
    if (path.lastOrNull() == "productId") {
      extractProductId(value)?.let(ids::add)
    }

    when (property.type) {
      ViewModelPropertyType.OBJECT -> {
        val objectValue = value as? JsonObject
        val nestedSchema = property.schema.orEmpty()
        collectProductIdsFromSchema(
          schema = nestedSchema,
          values = objectValue?.toMap().orEmpty(),
          viewModelsById = viewModelsById,
          instancesById = instancesById,
          ids = ids,
          path = path,
          visitedInstanceIds = visitedInstanceIds,
        )
      }
      ViewModelPropertyType.VIEW_MODEL -> {
        val viewModel = property.viewModelId?.let(viewModelsById::get) ?: return
        val objectValue = value as? JsonObject
        if (objectValue == null) return

        val referencedInstance = resolveReferencedViewModelInstance(
          value = objectValue,
          expectedViewModelId = viewModel.id,
          instancesById = instancesById,
          visitedInstanceIds = visitedInstanceIds,
        )
        if (referencedInstance != null) {
          collectProductIdsFromSchema(
            schema = viewModel.properties,
            values = referencedInstance.values,
            viewModelsById = viewModelsById,
            instancesById = instancesById,
            ids = ids,
            path = path,
            visitedInstanceIds = visitedInstanceIds + referencedInstance.instanceId,
          )
          return
        }

        collectProductIdsFromSchema(
          schema = viewModel.properties,
          values = objectValue.toMap(),
          viewModelsById = viewModelsById,
          instancesById = instancesById,
          ids = ids,
          path = path,
          visitedInstanceIds = visitedInstanceIds,
        )
      }
      ViewModelPropertyType.LIST -> {
        val itemProperty = property.itemType ?: return
        val arrayValue = value as? JsonArray ?: return
        for (item in arrayValue) {
          collectProductIdsFromProperty(
            property = itemProperty,
            value = item,
            viewModelsById = viewModelsById,
            instancesById = instancesById,
            ids = ids,
            path = path,
            visitedInstanceIds = visitedInstanceIds,
          )
        }
      }
      else -> Unit
    }
  }

  private fun collectProductIdsFromActions(
    actions: List<InteractionAction>,
    ids: MutableSet<String>,
  ) {
    for (action in actions) {
      when (action) {
        is InteractionAction.Purchase -> extractProductId(action.productId)?.let(ids::add)
        is InteractionAction.TimeWindow -> collectProductIdsFromActions(action.successActions.orEmpty(), ids)
        is InteractionAction.Condition -> {
          for (branch in action.branches) {
            collectProductIdsFromActions(branch.actions, ids)
          }
          collectProductIdsFromActions(action.defaultActions.orEmpty(), ids)
        }
        is InteractionAction.Experiment -> {
          for (variant in action.variants) {
            collectProductIdsFromActions(variant.actions, ids)
          }
        }
        else -> Unit
      }
    }
  }

  private fun extractProductId(value: JsonElement?): String? {
    return when (value) {
      is JsonPrimitive -> value.contentOrNull?.trim()?.takeIf(String::isNotEmpty)
      is JsonObject -> {
        val productId = (value["productId"] as? JsonPrimitive)
          ?.contentOrNull
          ?.trim()
          ?.takeIf(String::isNotEmpty)
        productId ?: (value["id"] as? JsonPrimitive)
          ?.contentOrNull
          ?.trim()
          ?.takeIf(String::isNotEmpty)
      }
      else -> null
    }
  }

  private fun resolveReferencedViewModelInstance(
    value: JsonObject,
    expectedViewModelId: String,
    instancesById: Map<String, ViewModelInstance>,
    visitedInstanceIds: Set<String>,
  ): ViewModelInstance? {
    val instanceId = (value["vmInstanceId"] as? JsonPrimitive)?.contentOrNull
      ?: (value["instanceId"] as? JsonPrimitive)?.contentOrNull
      ?: return null
    if (instanceId in visitedInstanceIds) return null

    val instance = instancesById[instanceId] ?: return null
    return instance.takeIf { it.viewModelId == expectedViewModelId }
  }
}
