package io.nuxie.sdk.flows

import androidx.activity.ComponentActivity
import io.nuxie.sdk.config.NuxieConfiguration
import io.nuxie.sdk.logging.NuxieLogger
import io.nuxie.sdk.network.NuxieApiProtocol
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Umbrella service for Flow subsystems on Android.
 *
 * Mirrors iOS `FlowService` structure:
 * - FlowStore: fetch + in-memory cache
 * - FlowBundleStore: disk cache for the web bundle
 * - FontStore: disk cache + scheme serving for fonts
 */
class FlowService(
  private val api: NuxieApiProtocol,
  private val configuration: NuxieConfiguration,
  private val scope: CoroutineScope,
  cacheDirectory: File,
  productService: FlowProductService = NoopFlowProductService,
) {
  private val flowStore = FlowStore(api, productService = productService)

  private val bundleStore = FlowBundleStore(
    cacheDirectory = File(cacheDirectory, "nuxie_flows"),
    maxConcurrentDownloads = configuration.maxConcurrentFlowDownloads,
    downloadTimeoutSeconds = configuration.flowDownloadTimeoutSeconds,
  )

  private val fontStore = FontStore(
    cacheDirectory = File(cacheDirectory, "nuxie_fonts"),
    downloadTimeoutSeconds = configuration.flowDownloadTimeoutSeconds,
  )

  suspend fun fetchFlow(id: String): Flow = flowStore.flow(id)

  /**
   * Prefetch flows from the `/profile` response (best-effort).
   */
  fun prefetchFlows(remoteFlows: List<RemoteFlow>) {
    if (remoteFlows.isEmpty()) return
    scope.launch(Dispatchers.IO) {
      runCatching { flowStore.preloadFlows(remoteFlows) }

      val fontEntries = remoteFlows.flatMap { it.fontManifest?.fonts.orEmpty() }
      if (fontEntries.isNotEmpty()) {
        runCatching { fontStore.prefetchFonts(fontEntries) }
      }

      for (rf in remoteFlows) {
        runCatching { bundleStore.preloadBundle(Flow(remoteFlow = rf)) }
      }
    }
  }

  /**
   * Create a configured [FlowView] for embedding.
   *
   * Must be called on the main thread (WebView construction).
   */
  suspend fun getFlowView(
    activity: ComponentActivity,
    flowId: String,
    viewId: Int,
    runtimeDelegate: FlowRuntimeDelegate? = null,
    colorSchemeMode: FlowColorSchemeMode = FlowColorSchemeMode.LIGHT,
  ): FlowView {
    val flow = fetchFlow(flowId)
    return withContext(Dispatchers.Main.immediate) {
      FlowView(activity).apply {
        id = viewId
        this.runtimeDelegate = runtimeDelegate
        this.colorSchemeMode = colorSchemeMode
        load(
          flow = flow,
          bundleStore = bundleStore,
          fontStore = fontStore,
          purchaseDelegate = configuration.purchaseDelegate,
          scope = scope,
        )
      }
    }
  }

  /**
   * Convenience for Activity-hosted presentation (used by NuxieFlowActivity).
   */
  suspend fun attachToActivity(
    activity: ComponentActivity,
    flowId: String,
    runtimeDelegate: FlowRuntimeDelegate? = null,
    colorSchemeMode: FlowColorSchemeMode = FlowColorSchemeMode.LIGHT,
  ): FlowView {
    return getFlowView(
      activity = activity,
      flowId = flowId,
      viewId = io.nuxie.sdk.R.id.nuxie_flow_view,
      runtimeDelegate = runtimeDelegate,
      colorSchemeMode = colorSchemeMode,
    )
  }

  suspend fun removeFlows(flowIds: List<String>) {
    for (id in flowIds) {
      runCatching { flowStore.removeFlow(id) }
      runCatching { bundleStore.removeBundles(id) }
    }
  }

  suspend fun clearCache() {
    flowStore.clearCache()
    bundleStore.clearAll()
    fontStore.clearCache()
    NuxieLogger.info("Cleared all flow caches")
  }
}
