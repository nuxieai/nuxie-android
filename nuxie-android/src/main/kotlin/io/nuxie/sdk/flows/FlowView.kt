package io.nuxie.sdk.flows

import android.Manifest
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Parcel
import android.os.Parcelable
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import io.nuxie.sdk.NuxieSDK
import io.nuxie.sdk.R
import io.nuxie.sdk.events.SystemEventNames
import io.nuxie.sdk.logging.NuxieLogger
import io.nuxie.sdk.purchases.NuxiePurchaseDelegate
import io.nuxie.sdk.purchases.PlayStorePurchase
import io.nuxie.sdk.purchases.PurchaseOutcome
import io.nuxie.sdk.purchases.PurchaseResult
import io.nuxie.sdk.purchases.RestoreResult
import io.nuxie.sdk.util.toJsonObject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import kotlin.math.max
import java.util.UUID
import java.util.WeakHashMap

internal interface NotificationPermissionHandler {
  fun areNotificationsEnabled(context: Context): Boolean
  fun isPostNotificationsPermissionGranted(context: Context): Boolean
  fun requestPostNotificationsPermission(
    activity: ComponentActivity,
    requestId: String,
    launchIfNeeded: Boolean,
    onResult: (Boolean) -> Unit,
  ): Boolean
}

internal interface NotificationPermissionEventReceiver {
  fun onNotificationPermissionEvent(
    eventName: String,
    properties: Map<String, Any?>,
  )
}

internal interface RuntimePermissionHandler {
  fun hasPermissionAccess(context: Context, permissions: List<String>): Boolean
  fun hasManifestDeclarations(context: Context, permissions: List<String>): Boolean = true
  fun requestPermissions(
    activity: ComponentActivity,
    permissions: List<String>,
    requestId: String,
    launchIfNeeded: Boolean,
    onResult: (Boolean) -> Unit,
  ): Boolean
}

internal interface PermissionEventReceiver {
  fun onPermissionEvent(
    eventName: String,
    properties: Map<String, Any?>,
  )
}

internal object NotificationPermissionRequestRegistry {
  private val callbacks: MutableMap<String, (Boolean) -> Unit> = mutableMapOf()
  private val pendingResults: MutableMap<String, Boolean> = mutableMapOf()
  private val inFlightRequestIds: MutableSet<String> = mutableSetOf()
  private val lock = Any()

  fun bind(requestId: String, onResult: (Boolean) -> Unit) {
    val pendingResult =
      synchronized(lock) {
        callbacks[requestId] = onResult
        pendingResults.remove(requestId)
      }

    if (pendingResult != null) {
      synchronized(lock) {
        callbacks.remove(requestId)
      }
      onResult(pendingResult)
    }
  }

  fun markLaunched(requestId: String): Boolean {
    synchronized(lock) {
      if (inFlightRequestIds.contains(requestId)) return false
      inFlightRequestIds += requestId
      return true
    }
  }

  fun complete(requestId: String, granted: Boolean) {
    val callback =
      synchronized(lock) {
        inFlightRequestIds.remove(requestId)
        callbacks.remove(requestId).also { existing ->
          if (existing == null) {
            pendingResults[requestId] = granted
          }
        }
      }
    callback?.invoke(granted)
  }

  fun hasPendingWork(requestId: String): Boolean {
    synchronized(lock) {
      return inFlightRequestIds.contains(requestId) || pendingResults.containsKey(requestId)
    }
  }

  fun clear(requestId: String) {
    synchronized(lock) {
      callbacks.remove(requestId)
      pendingResults.remove(requestId)
      inFlightRequestIds.remove(requestId)
    }
  }

  internal fun resetForTest() {
    synchronized(lock) {
      callbacks.clear()
      pendingResults.clear()
      inFlightRequestIds.clear()
    }
  }
}

internal object RuntimePermissionRequestRegistry {
  private val callbacks: MutableMap<String, (Boolean) -> Unit> = mutableMapOf()
  private val pendingResults: MutableMap<String, Boolean> = mutableMapOf()
  private val inFlightRequestIds: MutableSet<String> = mutableSetOf()
  private val lock = Any()

  fun bind(requestId: String, onResult: (Boolean) -> Unit) {
    val pendingResult =
      synchronized(lock) {
        callbacks[requestId] = onResult
        pendingResults.remove(requestId)
      }

    if (pendingResult != null) {
      synchronized(lock) {
        callbacks.remove(requestId)
      }
      onResult(pendingResult)
    }
  }

  fun markLaunched(requestId: String): Boolean {
    synchronized(lock) {
      if (inFlightRequestIds.contains(requestId)) return false
      inFlightRequestIds += requestId
      return true
    }
  }

  fun complete(requestId: String, granted: Boolean) {
    val callback =
      synchronized(lock) {
        inFlightRequestIds.remove(requestId)
        callbacks.remove(requestId).also { existing ->
          if (existing == null) {
            pendingResults[requestId] = granted
          }
        }
      }
    callback?.invoke(granted)
  }

  fun hasPendingWork(requestId: String): Boolean {
    synchronized(lock) {
      return inFlightRequestIds.contains(requestId) || pendingResults.containsKey(requestId)
    }
  }

  fun clear(requestId: String) {
    synchronized(lock) {
      callbacks.remove(requestId)
      pendingResults.remove(requestId)
      inFlightRequestIds.remove(requestId)
    }
  }

  internal fun resetForTest() {
    synchronized(lock) {
      callbacks.clear()
      pendingResults.clear()
      inFlightRequestIds.clear()
    }
  }
}

internal class DefaultNotificationPermissionHandler : NotificationPermissionHandler {
  private val activityResultLaunchers:
    MutableMap<ComponentActivity, MutableMap<String, ActivityResultLauncher<String>>> = WeakHashMap()

  override fun areNotificationsEnabled(context: Context): Boolean {
    return NotificationManagerCompat.from(context).areNotificationsEnabled()
  }

  override fun isPostNotificationsPermissionGranted(context: Context): Boolean {
    return ContextCompat.checkSelfPermission(
      context,
      Manifest.permission.POST_NOTIFICATIONS,
    ) == PackageManager.PERMISSION_GRANTED
  }

  override fun requestPostNotificationsPermission(
    activity: ComponentActivity,
    requestId: String,
    launchIfNeeded: Boolean,
    onResult: (Boolean) -> Unit,
  ): Boolean {
    return requestWithActivityResultRegistry(
      activity = activity,
      requestId = requestId,
      launchIfNeeded = launchIfNeeded,
      onResult = onResult,
    )
  }

  private fun requestWithActivityResultRegistry(
    activity: ComponentActivity,
    requestId: String,
    launchIfNeeded: Boolean,
    onResult: (Boolean) -> Unit,
  ): Boolean {
    val shouldLaunch =
      if (launchIfNeeded) {
        NotificationPermissionRequestRegistry.markLaunched(requestId)
      } else {
        false
      }
    NotificationPermissionRequestRegistry.bind(requestId, onResult)

    return runCatching {
      val launcher = notificationPermissionLauncher(activity, requestId)
      if (shouldLaunch) {
        launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
      }
    }.onFailure {
      NotificationPermissionRequestRegistry.clear(requestId)
      NuxieLogger.warning("FlowView: Failed to request notification permission: ${it.message}", it)
    }.isSuccess
  }

  private fun notificationPermissionLauncher(
    activity: ComponentActivity,
    requestId: String,
  ): ActivityResultLauncher<String> {
    synchronized(activityResultLaunchers) {
      val launchersForActivity = activityResultLaunchers.getOrPut(activity) { mutableMapOf() }
      launchersForActivity[requestId]?.let { return it }

      val launcher =
        activity.activityResultRegistry.register(
          notificationPermissionActivityResultKey(requestId),
          ActivityResultContracts.RequestPermission(),
        ) { granted ->
          NotificationPermissionRequestRegistry.complete(requestId, granted)
          unregisterNotificationPermissionLauncher(activity, requestId)
        }

      launchersForActivity[requestId] = launcher
      if (!NotificationPermissionRequestRegistry.hasPendingWork(requestId)) {
        unregisterNotificationPermissionLauncher(activity, requestId)
      }
      return launcher
    }
  }

  private fun unregisterNotificationPermissionLauncher(
    activity: ComponentActivity,
    requestId: String,
  ) {
    synchronized(activityResultLaunchers) {
      val launchersForActivity = activityResultLaunchers[activity] ?: return
      val launcher = launchersForActivity.remove(requestId) ?: return
      launcher.unregister()
      if (launchersForActivity.isEmpty()) {
        activityResultLaunchers.remove(activity)
      }
    }
  }

  companion object {
    internal fun notificationPermissionActivityResultKey(requestId: String): String {
      return "io.nuxie.sdk.notifications.permission.$requestId"
    }
  }
}

internal class DefaultRuntimePermissionHandler : RuntimePermissionHandler {
  private val activityResultLaunchers:
    MutableMap<ComponentActivity, MutableMap<String, ActivityResultLauncher<Array<String>>>> =
    WeakHashMap()

  override fun hasPermissionAccess(context: Context, permissions: List<String>): Boolean {
    return permissions.any { permission ->
      ContextCompat.checkSelfPermission(
        context,
        permission,
      ) == PackageManager.PERMISSION_GRANTED
    }
  }

  override fun hasManifestDeclarations(context: Context, permissions: List<String>): Boolean {
    val declaredPermissions =
      runCatching {
        val packageInfo =
          if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.packageManager.getPackageInfo(
              context.packageName,
              PackageManager.PackageInfoFlags.of(PackageManager.GET_PERMISSIONS.toLong()),
            )
          } else {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageInfo(
              context.packageName,
              PackageManager.GET_PERMISSIONS,
            )
          }
        packageInfo.requestedPermissions?.toSet().orEmpty()
      }.onFailure { error ->
        NuxieLogger.warning(
          "FlowView: Failed to inspect host manifest permissions: ${error.message}",
          error,
        )
      }.getOrDefault(emptySet())

    return permissions.all { permission -> declaredPermissions.contains(permission) }
  }

  override fun requestPermissions(
    activity: ComponentActivity,
    permissions: List<String>,
    requestId: String,
    launchIfNeeded: Boolean,
    onResult: (Boolean) -> Unit,
  ): Boolean {
    val shouldLaunch =
      if (launchIfNeeded) {
        RuntimePermissionRequestRegistry.markLaunched(requestId)
      } else {
        false
      }
    RuntimePermissionRequestRegistry.bind(requestId, onResult)

    return runCatching {
      val launcher = permissionLauncher(activity, requestId)
      if (shouldLaunch) {
        launcher.launch(permissions.toTypedArray())
      }
    }.onFailure {
      RuntimePermissionRequestRegistry.clear(requestId)
      NuxieLogger.warning("FlowView: Failed to request runtime permission: ${it.message}", it)
    }.isSuccess
  }

  private fun permissionLauncher(
    activity: ComponentActivity,
    requestId: String,
  ): ActivityResultLauncher<Array<String>> {
    synchronized(activityResultLaunchers) {
      val launchersForActivity = activityResultLaunchers.getOrPut(activity) { mutableMapOf() }
      launchersForActivity[requestId]?.let { return it }

      val launcher =
        activity.activityResultRegistry.register(
          permissionActivityResultKey(requestId),
          ActivityResultContracts.RequestMultiplePermissions(),
        ) { grantResults ->
          RuntimePermissionRequestRegistry.complete(
            requestId,
            grantResults.values.any { granted -> granted },
          )
          unregisterPermissionLauncher(activity, requestId)
        }

      launchersForActivity[requestId] = launcher
      if (!RuntimePermissionRequestRegistry.hasPendingWork(requestId)) {
        unregisterPermissionLauncher(activity, requestId)
      }
      return launcher
    }
  }

  private fun unregisterPermissionLauncher(
    activity: ComponentActivity,
    requestId: String,
  ) {
    synchronized(activityResultLaunchers) {
      val launchersForActivity = activityResultLaunchers[activity] ?: return
      val launcher = launchersForActivity.remove(requestId) ?: return
      launcher.unregister()
      if (launchersForActivity.isEmpty()) {
        activityResultLaunchers.remove(activity)
      }
    }
  }

  companion object {
    internal fun permissionActivityResultKey(requestId: String): String {
      return "io.nuxie.sdk.permissions.request.$requestId"
    }
  }
}

class FlowView(context: Context) : FrameLayout(context) {
  private companion object {
    const val NULL_PENDING_PERMISSION_JOURNEY_ID = "__NULL_PENDING_PERMISSION_JOURNEY_ID__"
    const val QUEUED_PROMPT_KIND_NOTIFICATION = "notification"
    const val QUEUED_PROMPT_KIND_PERMISSION = "permission"
  }

  private data class PendingPermissionRequest(
    val requestId: String,
    val permissionType: String,
    val journeyId: String?,
  )

  private sealed class PermissionRequestResolution {
    data class Launch(
      val runtimePermissions: List<String>,
      val activity: ComponentActivity,
    ) : PermissionRequestResolution()

    object Granted : PermissionRequestResolution()

    object Denied : PermissionRequestResolution()
  }

  private sealed class PendingPromptRequest {
    abstract val requestId: String
    abstract val journeyId: String?

    data class Notification(
      override val requestId: String,
      override val journeyId: String?,
    ) : PendingPromptRequest()

    data class Permission(
      override val requestId: String,
      val permissionType: String,
      override val journeyId: String?,
    ) : PendingPromptRequest()
  }

  private enum class State {
    LOADING,
    LOADED,
    ERROR,
  }

  var runtimeDelegate: FlowRuntimeDelegate? = null
  var onClose: ((CloseReason) -> Unit)? = null
  var onDismissRequested: ((CloseReason) -> Unit)? = null
  var colorSchemeMode: FlowColorSchemeMode = FlowColorSchemeMode.LIGHT
    set(value) {
      field = value
      sendColorSchemeToRuntime()
    }

  private var didInvokeClose: Boolean = false
  private var state: State = State.LOADING

  private lateinit var webView: FlowWebView
  private lateinit var loadingView: View
  private lateinit var errorView: View

  private var loadTimeoutJob: Job? = null

  private var purchaseDelegate: NuxiePurchaseDelegate? = null
  private var scope: CoroutineScope? = null
  private var flow: Flow? = null
  private var bundleStore: FlowBundleStore? = null
  private var pendingNotificationPermissionRequestId: String? = null
  private var pendingNotificationPermissionJourneyId: String? = null
  private var pendingPermissionRequestId: String? = null
  private var pendingPermissionJourneyId: String? = null
  private var pendingPermissionType: String? = null
  private val queuedPromptRequests = ArrayDeque<PendingPromptRequest>()
  internal var notificationPermissionHandler: NotificationPermissionHandler = DefaultNotificationPermissionHandler()
  internal var runtimePermissionHandler: RuntimePermissionHandler = DefaultRuntimePermissionHandler()
  internal var sdkIntProvider: () -> Int = { Build.VERSION.SDK_INT }
  internal var notificationPermissionRuntimeEventSink:
    (eventName: String, properties: Map<String, Any?>?) -> Unit =
    { eventName, properties ->
      sendNotificationPermissionEventToRuntime(
        eventName = eventName,
        properties = properties,
      )
    }
  internal var notificationPermissionEventSink:
    (eventName: String, properties: Map<String, Any?>?, journeyId: String?) -> Unit =
    { eventName, properties, journeyId ->
      dispatchNotificationPermissionEvent(
        eventName = eventName,
        properties = properties,
        journeyId = journeyId,
      )
    }
  internal var permissionRuntimeEventSink:
    (eventName: String, properties: Map<String, Any?>?) -> Unit =
    { eventName, properties ->
      sendPermissionEventToRuntime(
        eventName = eventName,
        properties = properties,
      )
    }
  internal var permissionEventSink:
    (eventName: String, properties: Map<String, Any?>?, journeyId: String?) -> Unit =
    { eventName, properties, journeyId ->
      dispatchPermissionEvent(
        eventName = eventName,
        properties = properties,
        journeyId = journeyId,
      )
    }
  internal var playStorePurchaseRefresh: () -> Unit = {
    NuxieSDK.shared().refreshPlayStorePurchases()
  }

  private data class SafeAreaInsets(
    val top: Int,
    val bottom: Int,
    val left: Int,
    val right: Int,
  )

  private var latestSafeAreaInsets = SafeAreaInsets(top = 0, bottom = 0, left = 0, right = 0)
  private var dispatchedSafeAreaInsets: SafeAreaInsets? = null

  init {
    if (id == View.NO_ID) {
      id = View.generateViewId()
    }
  }

  override fun onAttachedToWindow() {
    super.onAttachedToWindow()
    requestApplyInsets()
    rebindPendingNotificationPermissionRequestIfNeeded()
    rebindPendingPermissionRequestIfNeeded()
  }

  override fun onSaveInstanceState(): Parcelable? {
    val state = SavedState(super.onSaveInstanceState())
    state.pendingNotificationPermissionRequestId = pendingNotificationPermissionRequestId
    state.pendingNotificationPermissionJourneyId = pendingNotificationPermissionJourneyId
    state.pendingPermissionRequestId = pendingPermissionRequestId
    state.pendingPermissionJourneyId = pendingPermissionJourneyId
    state.pendingPermissionType = pendingPermissionType
    state.queuedPromptKinds =
      ArrayList(
        queuedPromptRequests.map { request ->
          when (request) {
            is PendingPromptRequest.Notification -> QUEUED_PROMPT_KIND_NOTIFICATION
            is PendingPromptRequest.Permission -> QUEUED_PROMPT_KIND_PERMISSION
          }
        },
      )
    state.queuedPromptRequestIds =
      ArrayList(queuedPromptRequests.map { request -> request.requestId })
    state.queuedPromptJourneyIds =
      ArrayList(
        queuedPromptRequests.map { request ->
          request.journeyId ?: NULL_PENDING_PERMISSION_JOURNEY_ID
        },
      )
    state.queuedPromptPermissionTypes =
      ArrayList(
        queuedPromptRequests.map { request ->
          (request as? PendingPromptRequest.Permission)?.permissionType.orEmpty()
        },
      )
    return state
  }

  override fun onRestoreInstanceState(state: Parcelable?) {
    if (state !is SavedState) {
      super.onRestoreInstanceState(state)
      return
    }

    super.onRestoreInstanceState(state.superState)
    pendingNotificationPermissionRequestId = state.pendingNotificationPermissionRequestId
    pendingNotificationPermissionJourneyId = state.pendingNotificationPermissionJourneyId
    pendingPermissionRequestId = state.pendingPermissionRequestId
    pendingPermissionJourneyId = state.pendingPermissionJourneyId
    pendingPermissionType = state.pendingPermissionType
    queuedPromptRequests.clear()
    val queuedRequestKinds = state.queuedPromptKinds
    val queuedRequestIds = state.queuedPromptRequestIds
    val queuedJourneyIds = state.queuedPromptJourneyIds
    val queuedPermissionTypes = state.queuedPromptPermissionTypes
    for (index in queuedRequestIds.indices) {
      val journeyId =
        queuedJourneyIds.getOrNull(index)?.takeUnless { queuedJourneyId ->
          queuedJourneyId == NULL_PENDING_PERMISSION_JOURNEY_ID
        }
      when (queuedRequestKinds.getOrNull(index)) {
        QUEUED_PROMPT_KIND_NOTIFICATION ->
          queuedPromptRequests.addLast(
            PendingPromptRequest.Notification(
              requestId = queuedRequestIds[index],
              journeyId = journeyId,
            ),
          )
        QUEUED_PROMPT_KIND_PERMISSION -> {
          val permissionType = queuedPermissionTypes.getOrNull(index) ?: continue
          queuedPromptRequests.addLast(
            PendingPromptRequest.Permission(
              requestId = queuedRequestIds[index],
              permissionType = permissionType,
              journeyId = journeyId,
            ),
          )
        }
      }
    }
    rebindPendingNotificationPermissionRequestIfNeeded()
    rebindPendingPermissionRequestIfNeeded()
  }

  override fun onApplyWindowInsets(insets: WindowInsets): WindowInsets {
    latestSafeAreaInsets = readSafeAreaInsets(insets)
    dispatchSafeAreaInsets()
    return super.onApplyWindowInsets(insets)
  }

  fun load(
    flow: Flow,
    bundleStore: FlowBundleStore,
    fontStore: FontStore,
    purchaseDelegate: NuxiePurchaseDelegate?,
    scope: CoroutineScope,
  ) {
    this.flow = flow
    this.bundleStore = bundleStore
    this.purchaseDelegate = purchaseDelegate
    this.scope = scope

    removeAllViews()
    didInvokeClose = false

    webView = FlowWebView(context = context, fontStore = fontStore).also { wv ->
      wv.layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
      wv.onLoadingStarted = {
        setState(State.LOADING)
        startLoadTimeout()
      }
      wv.onLoadingFinished = {
        // Page finished; runtime may still be booting but UI can appear.
        setState(State.LOADED)
        cancelLoadTimeout()
      }
      wv.onLoadingFailed = { err ->
        NuxieLogger.warning("FlowView load failed: ${err.message}", err)
        setState(State.ERROR)
        cancelLoadTimeout()
      }
      wv.onBridgeMessage = { env ->
        val payload = (env.payload as? JsonObject) ?: JsonObject(emptyMap())
        handleBridgeMessage(type = env.type, payload = payload, id = env.id)
      }
    }

    loadingView = buildLoadingView()
    errorView = buildErrorView()

    addView(webView)
    addView(loadingView)
    addView(errorView)
    post { requestApplyInsets() }

    // Configure interception: cache-first bundle + fonts
    val interceptor = FlowResourceInterceptor(flow = flow, fontStore = fontStore)
    interceptor.setBundleDir(bundleStore.getCachedBundleDir(flow))
    webView.setResourceInterceptor(interceptor)
    webView.resetBridge()
    sendProductsToRuntime()
    sendColorSchemeToRuntime()

    // Prefetch fonts and bundle in the background (best-effort).
    scope.launch(Dispatchers.IO) {
      runCatching { fontStore.registerManifest(flow.remoteFlow.fontManifest) }
      val fonts = flow.remoteFlow.fontManifest?.fonts.orEmpty()
      if (fonts.isNotEmpty()) {
        runCatching { fontStore.prefetchFonts(fonts) }
      }
      runCatching {
        val dir = bundleStore.preloadBundle(flow)
        interceptor.setBundleDir(dir)
      }
    }

    // Begin load (cache-first via interception).
    val entryFile = bundleStore.resolveMainFile(flow.manifest)
    val base = flow.url.toHttpUrlOrNull()
    if (entryFile == null || base == null) {
      setState(State.ERROR)
      return
    }

    val entryUrl = base.newBuilder().addPathSegments(entryFile.path.trimStart('/')).build().toString()
    setState(State.LOADING)
    startLoadTimeout()
    webView.loadUrl(entryUrl)
    rebindPendingNotificationPermissionRequestIfNeeded()
    rebindPendingPermissionRequestIfNeeded()
  }

  fun sendRuntimeMessage(type: String, payload: JsonObject = JsonObject(emptyMap()), replyTo: String? = null) {
    webView.sendBridgeMessage(type = type, payload = payload, replyTo = replyTo)
  }

  private fun sendImmediateBridgeResult(type: String, payload: JsonObject = JsonObject(emptyMap()), replyTo: String? = null) {
    webView.sendBridgeMessageImmediately(type = type, payload = payload, replyTo = replyTo)
  }

  fun performPurchase(productId: String) {
    handlePurchase(productId)
  }

  fun performRestore() {
    handleRestore()
  }

  fun performRequestNotifications(journeyId: String? = null) {
    val action = { handleRequestNotifications(journeyId) }
    if (Looper.myLooper() == Looper.getMainLooper()) {
      action()
    } else {
      post { action() }
    }
  }

  fun performRequestPermission(permissionType: String, journeyId: String? = null) {
    val action = { handleRequestPermission(permissionType, journeyId) }
    if (Looper.myLooper() == Looper.getMainLooper()) {
      action()
    } else {
      post { action() }
    }
  }

  fun performOpenLink(urlString: String, target: String?) {
    openLinkInternal(urlString, target)
  }

  fun performDismiss(reason: CloseReason = CloseReason.UserDismissed) {
    runtimeDelegate?.onDismissRequested(reason)
    invokeOnCloseOnce(reason)
    onDismissRequested?.invoke(reason)
  }

  private fun invokeOnCloseOnce(reason: CloseReason) {
    if (didInvokeClose) return
    didInvokeClose = true
    onClose?.invoke(reason)
  }

  private fun handleBridgeMessage(type: String, payload: JsonObject, id: String?) {
    when (type) {
      "runtime/ready" -> {
        runtimeDelegate?.onRuntimeMessage(type, payload, id)
        // Runtime expressions require numeric inset values; resend on every runtime boot.
        dispatchSafeAreaInsets(force = true)
        sendColorSchemeToRuntime()
      }
      "runtime/screen_changed",
      "action/did_set",
      "action/event",
      -> runtimeDelegate?.onRuntimeMessage(type, payload, id)

      "action/purchase" -> {
        if (runtimeDelegate != null) {
          runtimeDelegate?.onRuntimeMessage(type, payload, id)
        } else {
          val productId = (payload["productId"] as? JsonPrimitive)?.contentOrNull
          if (productId.isNullOrBlank()) return
          handlePurchase(productId)
        }
      }

      "action/restore" -> {
        if (runtimeDelegate != null) {
          runtimeDelegate?.onRuntimeMessage(type, payload, id)
        } else {
          handleRestore()
        }
      }

      "action/request_notifications" -> {
        if (runtimeDelegate != null) {
          runtimeDelegate?.onRuntimeMessage(type, payload, id)
        } else {
          performRequestNotifications()
        }
      }

      "action/request_permission" -> {
        if (runtimeDelegate != null) {
          runtimeDelegate?.onRuntimeMessage(type, payload, id)
        } else {
          val permissionType = (payload["permissionType"] as? JsonPrimitive)?.contentOrNull ?: return
          performRequestPermission(permissionType)
        }
      }

      "action/open_link" -> {
        if (runtimeDelegate != null) {
          runtimeDelegate?.onRuntimeMessage(type, payload, id)
        } else {
          val url = (payload["url"] as? JsonPrimitive)?.contentOrNull ?: return
          val target = (payload["target"] as? JsonPrimitive)?.contentOrNull
          openLinkInternal(url, target)
        }
      }

      "action/back" -> {
        if (runtimeDelegate != null) {
          runtimeDelegate?.onRuntimeMessage(type, payload, id)
        } else {
          NuxieLogger.debug("FlowView: Unhandled runtime back action")
        }
      }

      "action/dismiss", "dismiss", "closeFlow" -> {
        performDismiss(CloseReason.UserDismissed)
      }

      else -> {
        if (type.startsWith("action/")) {
          runtimeDelegate?.onRuntimeMessage(type, payload, id)
        } else {
          NuxieLogger.debug("FlowView: Unhandled bridge message: $type")
        }
      }
    }
  }

  private fun readSafeAreaInsets(insets: WindowInsets?): SafeAreaInsets {
    if (insets == null) {
      return SafeAreaInsets(top = 0, bottom = 0, left = 0, right = 0)
    }

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
      val resolved = insets.getInsetsIgnoringVisibility(
        WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout(),
      )
      return SafeAreaInsets(
        top = resolved.top,
        bottom = resolved.bottom,
        left = resolved.left,
        right = resolved.right,
      )
    }

    @Suppress("DEPRECATION")
    var top = insets.systemWindowInsetTop
    @Suppress("DEPRECATION")
    var bottom = insets.systemWindowInsetBottom
    @Suppress("DEPRECATION")
    var left = insets.systemWindowInsetLeft
    @Suppress("DEPRECATION")
    var right = insets.systemWindowInsetRight

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
      val cutout = insets.displayCutout
      if (cutout != null) {
        top = max(top, cutout.safeInsetTop)
        bottom = max(bottom, cutout.safeInsetBottom)
        left = max(left, cutout.safeInsetLeft)
        right = max(right, cutout.safeInsetRight)
      }
    }

    return SafeAreaInsets(top = top, bottom = bottom, left = left, right = right)
  }

  private fun readCurrentSafeAreaInsets(): SafeAreaInsets {
    val insets =
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) rootWindowInsets else null
    return readSafeAreaInsets(insets)
  }

  private fun dispatchSafeAreaInsets(force: Boolean = false) {
    if (!::webView.isInitialized) return

    val currentInsets =
      if (latestSafeAreaInsets == SafeAreaInsets(0, 0, 0, 0)) {
        readCurrentSafeAreaInsets()
      } else {
        latestSafeAreaInsets
      }

    // Avoid queueing stale inset snapshots before runtime readiness; send latest once ready.
    if (!webView.isRuntimeReady()) {
      latestSafeAreaInsets = currentInsets
      return
    }

    if (!force && dispatchedSafeAreaInsets == currentInsets) return

    latestSafeAreaInsets = currentInsets
    dispatchedSafeAreaInsets = currentInsets

    webView.sendBridgeMessage(
      type = "system/safe_area_insets",
      payload = buildJsonObject {
        put("top", JsonPrimitive(currentInsets.top))
        put("bottom", JsonPrimitive(currentInsets.bottom))
        put("left", JsonPrimitive(currentInsets.left))
        put("right", JsonPrimitive(currentInsets.right))
      },
    )
  }

  private fun sendColorSchemeToRuntime() {
    if (!::webView.isInitialized) return
    val payload = buildJsonObject {
      put("mode", JsonPrimitive(colorSchemeMode.rawValue))
    }
    webView.sendBridgeMessage(type = "runtime/color_scheme", payload = payload)
  }

  private fun sendProductsToRuntime() {
    if (!::webView.isInitialized) return
    val loadedFlow = flow ?: return
    webView.sendBridgeMessage(
      type = FLOW_PRODUCTS_MESSAGE_TYPE,
      payload = buildFlowProductsRuntimePayload(loadedFlow.products),
    )
  }

  private fun handlePurchase(productId: String) {
    val delegate = purchaseDelegate
    if (delegate == null) {
      sendImmediateBridgeResult(
        type = "purchase_error",
        payload = buildJsonObject { put("error", JsonPrimitive("purchase_delegate_not_configured")) },
      )
      return
    }

    val s = scope ?: return
    s.launch(Dispatchers.Main) {
      val outcome = runCatching { delegate.purchaseOutcomeCompat(productId) }.getOrElse {
        sendImmediateBridgeResult(
          type = "purchase_error",
          payload = buildJsonObject { put("error", JsonPrimitive(it.message ?: "purchase_failed")) },
        )
        return@launch
      }

      when (val res = outcome.result) {
        PurchaseResult.Success -> {
          val confirmedProductId = outcome.productId ?: productId
          sendImmediateBridgeResult(
            type = "purchase_ui_success",
            payload = buildJsonObject { put("productId", JsonPrimitive(confirmedProductId)) },
          )

          val playStorePurchase = outcome.playStorePurchase
            ?.withKnownProductId(confirmedProductId)
            ?: outcome.purchaseToken
            ?.trim()
            ?.takeIf(String::isNotEmpty)
            ?.let { token ->
              PlayStorePurchase(
                purchaseToken = token,
                productIds = listOfNotNull(confirmedProductId),
                orderId = outcome.orderId,
              )
            }

          if (playStorePurchase == null) {
            sendImmediateBridgeResult(
              type = "purchase_confirmed",
              payload = buildJsonObject { put("productId", JsonPrimitive(confirmedProductId)) },
            )
            return@launch
          }

          val response = runCatching {
            NuxieSDK.shared().syncPlayStorePurchase(playStorePurchase)
          }.getOrElse {
            sendImmediateBridgeResult(
              type = "purchase_error",
              payload = buildJsonObject { put("error", JsonPrimitive(it.message ?: "purchase_sync_failed")) },
            )
            return@launch
          }

          if (response.success) {
            sendImmediateBridgeResult(
              type = "purchase_confirmed",
              payload = buildJsonObject { put("productId", JsonPrimitive(confirmedProductId)) },
            )
          } else {
            sendImmediateBridgeResult(
              type = "purchase_error",
              payload = buildJsonObject { put("error", JsonPrimitive(response.error ?: "purchase_sync_failed")) },
            )
          }
        }
        PurchaseResult.Cancelled -> {
          sendImmediateBridgeResult(type = "purchase_cancelled", payload = JsonObject(emptyMap()))
        }
        PurchaseResult.Pending -> {
          sendImmediateBridgeResult(
            type = "purchase_error",
            payload = buildJsonObject { put("error", JsonPrimitive("purchase_pending")) },
          )
        }
        is PurchaseResult.Failed -> {
          sendImmediateBridgeResult(
            type = "purchase_error",
            payload = buildJsonObject { put("error", JsonPrimitive(res.message)) },
          )
        }
      }
    }
  }

  private fun handleRestore() {
    val delegate = purchaseDelegate
    if (delegate == null) {
      sendImmediateBridgeResult(
        type = "restore_error",
        payload = buildJsonObject { put("error", JsonPrimitive("purchase_delegate_not_configured")) },
      )
      return
    }
    val s = scope ?: return
    s.launch(Dispatchers.Main) {
      val result = runCatching { delegate.restore() }.getOrElse {
        sendImmediateBridgeResult(
          type = "restore_error",
          payload = buildJsonObject { put("error", JsonPrimitive(it.message ?: "restore_failed")) },
        )
        return@launch
      }

      when (result) {
        is RestoreResult.Success,
        RestoreResult.NoPurchases,
        -> {
          playStorePurchaseRefresh()
          sendImmediateBridgeResult(type = "restore_success", payload = JsonObject(emptyMap()))
        }

        is RestoreResult.Failed -> sendImmediateBridgeResult(
          type = "restore_error",
          payload = buildJsonObject { put("error", JsonPrimitive(result.message)) },
        )
      }
    }
  }

  private fun handleRequestNotifications(journeyId: String?) {
    val properties = buildNotificationEventProperties(journeyId)
    val emitEnabled = {
      emitNotificationPermissionEvent(
        SystemEventNames.notificationsEnabled,
        properties,
        journeyId,
      )
    }
    val emitDenied = {
      emitNotificationPermissionEvent(
        SystemEventNames.notificationsDenied,
        properties,
        journeyId,
      )
    }

    if (sdkIntProvider() < Build.VERSION_CODES.TIRAMISU) {
      if (notificationPermissionHandler.areNotificationsEnabled(context)) {
        emitEnabled()
      } else {
        emitDenied()
      }
      return
    }

    val notificationsEnabled = notificationPermissionHandler.areNotificationsEnabled(context)
    val permissionGranted = notificationPermissionHandler.isPostNotificationsPermissionGranted(context)

    if (permissionGranted && notificationsEnabled) {
      emitEnabled()
      return
    }

    if (!permissionGranted) {
      val request =
        PendingPromptRequest.Notification(
          requestId = UUID.randomUUID().toString(),
          journeyId = journeyId,
        )
      if (hasPendingSystemPermissionPrompt()) {
        queuedPromptRequests.addLast(request)
        return
      }
      if (!startNotificationPermissionRequest(request)) {
        emitDenied()
      }
      return
    }

    emitDenied()
  }

  private fun handleRequestPermission(permissionType: String, journeyId: String?) {
    val queuedRequest =
      PendingPromptRequest.Permission(
        requestId = UUID.randomUUID().toString(),
        permissionType = permissionType,
        journeyId = journeyId,
      )
    val request =
      PendingPermissionRequest(
        requestId = queuedRequest.requestId,
        permissionType = queuedRequest.permissionType,
        journeyId = queuedRequest.journeyId,
      )
    val resolution = resolvePermissionRequest(request)
    if (hasPendingSystemPermissionPrompt()) {
      when (resolution) {
        PermissionRequestResolution.Granted -> emitPermissionGranted(request)
        PermissionRequestResolution.Denied -> emitPermissionDenied(request)
        is PermissionRequestResolution.Launch -> queuedPromptRequests.addLast(queuedRequest)
      }
      return
    }
    startPermissionRequest(request, resolution)
  }

  private fun startNotificationPermissionRequest(request: PendingPromptRequest.Notification): Boolean {
    val activity = findComponentActivity(context)
    if (activity == null) {
      NuxieLogger.warning(
        "FlowView: Notification permission prompt requires a ComponentActivity host; emitting denied",
      )
      return false
    }

    pendingNotificationPermissionRequestId = request.requestId
    pendingNotificationPermissionJourneyId = request.journeyId
    val launched =
      notificationPermissionHandler.requestPostNotificationsPermission(
        activity = activity,
        requestId = request.requestId,
        launchIfNeeded = true,
        onResult = notificationPermissionResultCallback(request.journeyId),
      )
    if (!launched) {
      clearPendingNotificationPermissionRequest()
    }
    return launched
  }

  private fun startPermissionRequest(
    request: PendingPermissionRequest,
    resolution: PermissionRequestResolution = resolvePermissionRequest(request),
  ) {
    when (resolution) {
      PermissionRequestResolution.Granted -> {
        emitPermissionGranted(request)
        drainNextPermissionRequest()
        return
      }
      PermissionRequestResolution.Denied -> {
        emitPermissionDenied(request)
        drainNextPermissionRequest()
        return
      }
      is PermissionRequestResolution.Launch -> Unit
    }

    pendingPermissionRequestId = request.requestId
    pendingPermissionJourneyId = request.journeyId
    pendingPermissionType = request.permissionType
    val launched =
      runtimePermissionHandler.requestPermissions(
        activity = resolution.activity,
        permissions = resolution.runtimePermissions,
        requestId = request.requestId,
        launchIfNeeded = true,
        onResult = permissionResultCallback(request),
      )
    if (!launched) {
      clearPendingPermissionRequest(request.requestId)
      emitPermissionDenied(request)
      drainNextPermissionRequest()
    }
  }

  private fun resolvePermissionRequest(
    request: PendingPermissionRequest,
  ): PermissionRequestResolution {
    val runtimePermissions = resolveRuntimePermissions(request.permissionType)
    if (runtimePermissions == null) {
      NuxieLogger.warning(
        "FlowView: Unsupported request permission type '${request.permissionType}'; emitting denied",
      )
      return PermissionRequestResolution.Denied
    }

    if (hasSatisfiedPermissionAccess(request.permissionType, runtimePermissions)) {
      return PermissionRequestResolution.Granted
    }

    val launchRuntimePermissions =
      resolveLaunchRuntimePermissions(request.permissionType, runtimePermissions)
    if (launchRuntimePermissions == null) {
      NuxieLogger.warning(
        "FlowView: Host app manifest is missing required declarations for ${runtimePermissions.joinToString()}; emitting denied",
      )
      return PermissionRequestResolution.Denied
    }

    val activity = findComponentActivity(context)
    if (activity == null) {
      NuxieLogger.warning(
        "FlowView: Runtime permission prompt requires a ComponentActivity host; emitting denied",
      )
      return PermissionRequestResolution.Denied
    }

    return PermissionRequestResolution.Launch(
      runtimePermissions = launchRuntimePermissions,
      activity = activity,
    )
  }

  private fun emitPermissionGranted(request: PendingPermissionRequest) {
    emitPermissionEvent(
      SystemEventNames.permissionGranted,
      buildPermissionEventProperties(request.journeyId, request.permissionType),
      request.journeyId,
    )
  }

  private fun emitPermissionDenied(request: PendingPermissionRequest) {
    emitPermissionEvent(
      SystemEventNames.permissionDenied,
      buildPermissionEventProperties(request.journeyId, request.permissionType),
      request.journeyId,
    )
  }

  private fun resolveRuntimePermissions(permissionType: String): List<String>? {
    return when (permissionType) {
      "camera" -> listOf(Manifest.permission.CAMERA)
      "location" ->
        listOf(
          Manifest.permission.ACCESS_FINE_LOCATION,
          Manifest.permission.ACCESS_COARSE_LOCATION,
        )
      "microphone" -> listOf(Manifest.permission.RECORD_AUDIO)
      "photos" ->
        if (sdkIntProvider() >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
          listOf(
            Manifest.permission.READ_MEDIA_IMAGES,
            Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED,
          )
        } else if (sdkIntProvider() >= Build.VERSION_CODES.TIRAMISU) {
          listOf(Manifest.permission.READ_MEDIA_IMAGES)
        } else {
          listOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
      else -> null
    }
  }

  private fun hasSatisfiedPermissionAccess(
    permissionType: String,
    runtimePermissions: List<String>,
  ): Boolean {
    if (permissionType == "location") {
      return runtimePermissionHandler.hasPermissionAccess(
        context,
        listOf(
          Manifest.permission.ACCESS_FINE_LOCATION,
          Manifest.permission.ACCESS_COARSE_LOCATION,
        ),
      )
    }

    if (
      permissionType == "photos" &&
      sdkIntProvider() >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE
    ) {
      return runtimePermissionHandler.hasPermissionAccess(
        context,
        listOf(Manifest.permission.READ_MEDIA_IMAGES),
      )
    }

    return runtimePermissionHandler.hasPermissionAccess(context, runtimePermissions)
  }

  private fun resolveLaunchRuntimePermissions(
    permissionType: String,
    runtimePermissions: List<String>,
  ): List<String>? {
    if (permissionType == "location") {
      val hasFineLocationDeclaration =
        runtimePermissionHandler.hasManifestDeclarations(
          context,
          listOf(Manifest.permission.ACCESS_FINE_LOCATION),
        )
      val hasCoarseLocationDeclaration =
        runtimePermissionHandler.hasManifestDeclarations(
          context,
          listOf(Manifest.permission.ACCESS_COARSE_LOCATION),
        )

      return when {
        hasFineLocationDeclaration && hasCoarseLocationDeclaration ->
          listOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
          )
        hasCoarseLocationDeclaration -> listOf(Manifest.permission.ACCESS_COARSE_LOCATION)
        else -> null
      }
    }

    return if (runtimePermissionHandler.hasManifestDeclarations(context, runtimePermissions)) {
      runtimePermissions
    } else {
      null
    }
  }

  private fun buildNotificationEventProperties(journeyId: String?): Map<String, Any?>? {
    return if (journeyId.isNullOrBlank()) {
      null
    } else {
      mapOf("journey_id" to journeyId)
    }
  }

  private fun buildPermissionEventProperties(
    journeyId: String?,
    permissionType: String,
  ): Map<String, Any?> {
    return buildMap {
      if (!journeyId.isNullOrBlank()) {
        put("journey_id", journeyId)
      }
      put("type", permissionType)
    }
  }

  private fun emitNotificationPermissionEvent(
    eventName: String,
    properties: Map<String, Any?>?,
    journeyId: String?,
  ) {
    notificationPermissionEventSink(eventName, properties, journeyId)
  }

  private fun emitPermissionEvent(
    eventName: String,
    properties: Map<String, Any?>?,
    journeyId: String?,
  ) {
    permissionEventSink(eventName, properties, journeyId)
  }

  private fun dispatchNotificationPermissionEvent(
    eventName: String,
    properties: Map<String, Any?>?,
    journeyId: String?,
  ) {
    val scopedProperties = properties ?: emptyMap()
    val receiver = runtimeDelegate as? NotificationPermissionEventReceiver
    if (!journeyId.isNullOrBlank() && receiver != null) {
      receiver.onNotificationPermissionEvent(
        eventName = eventName,
        properties = scopedProperties,
      )
      return
    }

    notificationPermissionRuntimeEventSink(eventName, properties)
  }

  private fun sendNotificationPermissionEventToRuntime(
    eventName: String,
    properties: Map<String, Any?>?,
  ) {
    if (!::webView.isInitialized) return

    val payload = buildMap<String, Any?> {
      put("name", eventName)
      if (!properties.isNullOrEmpty()) {
        put("properties", properties)
      }
    }
    webView.sendBridgeMessage(
      type = "action/event",
      payload = toJsonObject(payload),
    )
  }

  private fun dispatchPermissionEvent(
    eventName: String,
    properties: Map<String, Any?>?,
    journeyId: String?,
  ) {
    val scopedProperties = properties ?: emptyMap()
    val receiver = runtimeDelegate as? PermissionEventReceiver
    if (!journeyId.isNullOrBlank() && receiver != null) {
      receiver.onPermissionEvent(
        eventName = eventName,
        properties = scopedProperties,
      )
      return
    }

    permissionRuntimeEventSink(eventName, properties)
  }

  private fun sendPermissionEventToRuntime(
    eventName: String,
    properties: Map<String, Any?>?,
  ) {
    if (!::webView.isInitialized) return

    val payload = buildMap<String, Any?> {
      put("name", eventName)
      if (!properties.isNullOrEmpty()) {
        put("properties", properties)
      }
    }
    webView.sendBridgeMessage(
      type = "action/event",
      payload = toJsonObject(payload),
    )
  }

  private fun findComponentActivity(context: Context): ComponentActivity? {
    var current: Context? = context
    while (current is ContextWrapper) {
      if (current is ComponentActivity) {
        return current
      }
      current = current.baseContext
    }
    return current as? ComponentActivity
  }

  private fun rebindPendingNotificationPermissionRequestIfNeeded() {
    val requestId = pendingNotificationPermissionRequestId ?: return
    if (!NotificationPermissionRequestRegistry.hasPendingWork(requestId)) {
      clearPendingNotificationPermissionRequest()
      drainNextPermissionRequest()
      return
    }
    val activity = findComponentActivity(context) ?: return
    notificationPermissionHandler.requestPostNotificationsPermission(
      activity = activity,
      requestId = requestId,
      launchIfNeeded = false,
      onResult = notificationPermissionResultCallback(pendingNotificationPermissionJourneyId),
    )
  }

  private fun rebindPendingPermissionRequestIfNeeded() {
    val requestId = pendingPermissionRequestId ?: return
    val permissionType = pendingPermissionType ?: return
    if (!RuntimePermissionRequestRegistry.hasPendingWork(requestId)) {
      clearPendingPermissionRequest(requestId)
      drainNextPermissionRequest()
      return
    }
    val runtimePermissions = resolveRuntimePermissions(permissionType) ?: run {
      clearPendingPermissionRequest(requestId)
      return
    }
    val permissions = resolveLaunchRuntimePermissions(permissionType, runtimePermissions) ?: run {
      clearPendingPermissionRequest(requestId)
      return
    }
    val activity = findComponentActivity(context) ?: return
    runtimePermissionHandler.requestPermissions(
      activity = activity,
      permissions = permissions,
      requestId = requestId,
      launchIfNeeded = false,
      onResult =
        permissionResultCallback(
          PendingPermissionRequest(
            requestId = requestId,
            permissionType = permissionType,
            journeyId = pendingPermissionJourneyId,
          ),
        ),
    )
  }

  private fun notificationPermissionResultCallback(journeyId: String?): (Boolean) -> Unit {
    return { granted ->
      clearPendingNotificationPermissionRequest()
      val properties = buildNotificationEventProperties(journeyId)
      val enabledAfterResult = notificationPermissionHandler.areNotificationsEnabled(context)
      if (granted && enabledAfterResult) {
        emitNotificationPermissionEvent(
          eventName = SystemEventNames.notificationsEnabled,
          properties = properties,
          journeyId = journeyId,
        )
      } else {
        emitNotificationPermissionEvent(
          eventName = SystemEventNames.notificationsDenied,
          properties = properties,
          journeyId = journeyId,
        )
      }
      drainNextPermissionRequest()
    }
  }

  private fun permissionResultCallback(request: PendingPermissionRequest): (Boolean) -> Unit {
    return { granted ->
      clearPendingPermissionRequest(request.requestId)
      val properties = buildPermissionEventProperties(request.journeyId, request.permissionType)
      val hasGrantedAccess =
        if (granted) {
          val runtimePermissions = resolveRuntimePermissions(request.permissionType)
          runtimePermissions != null &&
            hasSatisfiedPermissionAccess(request.permissionType, runtimePermissions)
        } else {
          false
        }
      if (hasGrantedAccess) {
        emitPermissionEvent(
          eventName = SystemEventNames.permissionGranted,
          properties = properties,
          journeyId = request.journeyId,
        )
      } else {
        emitPermissionEvent(
          eventName = SystemEventNames.permissionDenied,
          properties = properties,
          journeyId = request.journeyId,
        )
      }
      drainNextPermissionRequest()
    }
  }

  private fun clearPendingNotificationPermissionRequest() {
    val requestId = pendingNotificationPermissionRequestId
    pendingNotificationPermissionRequestId = null
    pendingNotificationPermissionJourneyId = null
    if (requestId != null) {
      NotificationPermissionRequestRegistry.clear(requestId)
    }
  }

  private fun clearPendingPermissionRequest(requestId: String? = pendingPermissionRequestId) {
    if (requestId == null) return
    if (pendingPermissionRequestId == requestId) {
      pendingPermissionRequestId = null
      pendingPermissionJourneyId = null
      pendingPermissionType = null
    }
    RuntimePermissionRequestRegistry.clear(requestId)
  }

  private fun drainNextPermissionRequest() {
    if (hasPendingSystemPermissionPrompt()) return
    while (!hasPendingSystemPermissionPrompt() && queuedPromptRequests.isNotEmpty()) {
      when (val request = queuedPromptRequests.removeFirst()) {
        is PendingPromptRequest.Notification -> startNotificationPermissionRequest(request)
        is PendingPromptRequest.Permission ->
          startPermissionRequest(
            PendingPermissionRequest(
              requestId = request.requestId,
              permissionType = request.permissionType,
              journeyId = request.journeyId,
            ),
          )
      }
    }
  }

  private fun hasPendingSystemPermissionPrompt(): Boolean {
    return pendingPermissionRequestId != null || pendingNotificationPermissionRequestId != null
  }

  private class SavedState : View.BaseSavedState {
    var pendingNotificationPermissionRequestId: String? = null
    var pendingNotificationPermissionJourneyId: String? = null
    var pendingPermissionRequestId: String? = null
    var pendingPermissionJourneyId: String? = null
    var pendingPermissionType: String? = null
    var queuedPromptKinds: ArrayList<String> = arrayListOf()
    var queuedPromptRequestIds: ArrayList<String> = arrayListOf()
    var queuedPromptJourneyIds: ArrayList<String> = arrayListOf()
    var queuedPromptPermissionTypes: ArrayList<String> = arrayListOf()

    constructor(superState: Parcelable?) : super(superState)

    private constructor(source: Parcel) : super(source) {
      pendingNotificationPermissionRequestId = source.readString()
      pendingNotificationPermissionJourneyId = source.readString()
      pendingPermissionRequestId = source.readString()
      pendingPermissionJourneyId = source.readString()
      pendingPermissionType = source.readString()
      queuedPromptKinds =
        ArrayList<String>().apply { source.readStringList(this) }
      queuedPromptRequestIds =
        ArrayList<String>().apply { source.readStringList(this) }
      queuedPromptJourneyIds =
        ArrayList<String>().apply { source.readStringList(this) }
      queuedPromptPermissionTypes =
        ArrayList<String>().apply { source.readStringList(this) }
    }

    override fun writeToParcel(out: Parcel, flags: Int) {
      super.writeToParcel(out, flags)
      out.writeString(pendingNotificationPermissionRequestId)
      out.writeString(pendingNotificationPermissionJourneyId)
      out.writeString(pendingPermissionRequestId)
      out.writeString(pendingPermissionJourneyId)
      out.writeString(pendingPermissionType)
      out.writeStringList(queuedPromptKinds)
      out.writeStringList(queuedPromptRequestIds)
      out.writeStringList(queuedPromptJourneyIds)
      out.writeStringList(queuedPromptPermissionTypes)
    }

    companion object {
      @JvmField
      val CREATOR: Parcelable.Creator<SavedState> =
        object : Parcelable.Creator<SavedState> {
          override fun createFromParcel(source: Parcel): SavedState = SavedState(source)

          override fun newArray(size: Int): Array<SavedState?> = arrayOfNulls(size)
        }
    }
  }

  private fun openLinkInternal(urlString: String, target: String?) {
    val uri = runCatching { Uri.parse(urlString) }.getOrNull() ?: return
    val intent = Intent(Intent.ACTION_VIEW, uri).apply {
      addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    runCatching { context.startActivity(intent) }
      .onFailure { NuxieLogger.debug("FlowView: Failed to open link: ${it.message}", it) }
  }

  private fun startLoadTimeout() {
    cancelLoadTimeout()
    val s = scope ?: return
    loadTimeoutJob = s.launch(Dispatchers.Main) {
      delay(15_000)
      if (state == State.LOADING) {
        setState(State.ERROR)
      }
    }
  }

  private fun cancelLoadTimeout() {
    loadTimeoutJob?.cancel()
    loadTimeoutJob = null
  }

  private fun setState(next: State) {
    state = next
    when (next) {
      State.LOADING -> {
        webView.visibility = View.INVISIBLE
        loadingView.visibility = View.VISIBLE
        errorView.visibility = View.GONE
      }
      State.LOADED -> {
        webView.visibility = View.VISIBLE
        loadingView.visibility = View.GONE
        errorView.visibility = View.GONE
      }
      State.ERROR -> {
        webView.visibility = View.INVISIBLE
        loadingView.visibility = View.GONE
        errorView.visibility = View.VISIBLE
      }
    }
  }

  private fun buildLoadingView(): View {
    val container = FrameLayout(context).apply {
      layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
    }
    val pb = ProgressBar(context).apply {
      layoutParams = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
        gravity = Gravity.CENTER
      }
      isIndeterminate = true
    }
    container.addView(pb)
    return container
  }

  private fun buildErrorView(): View {
    val container = FrameLayout(context).apply {
      layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
    }
    val stack = LinearLayout(context).apply {
      orientation = LinearLayout.VERTICAL
      gravity = Gravity.CENTER
      layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
    }

    val title = TextView(context).apply {
      text = "Something went wrong"
      textSize = 16f
      gravity = Gravity.CENTER
    }
    val retry = Button(context).apply {
      text = "Retry"
      setOnClickListener {
        val f = flow ?: return@setOnClickListener
        val bs = bundleStore ?: return@setOnClickListener
        // Reload using the existing setup (bundle store will serve cache if present).
        setState(State.LOADING)
        startLoadTimeout()
        val entryFile = bs.resolveMainFile(f.manifest) ?: run {
          setState(State.ERROR)
          return@setOnClickListener
        }
        val base = f.url.toHttpUrlOrNull() ?: run {
          setState(State.ERROR)
          return@setOnClickListener
        }
        val entryUrl = base.newBuilder().addPathSegments(entryFile.path.trimStart('/')).build().toString()
        webView.loadUrl(entryUrl)
      }
    }
    val close = Button(context).apply {
      text = "Close"
      setOnClickListener { performDismiss(CloseReason.UserDismissed) }
    }

    stack.addView(title)
    stack.addView(retry)
    stack.addView(close)
    container.addView(stack)
    return container
  }
}

internal suspend fun NuxiePurchaseDelegate.purchaseOutcomeCompat(productId: String): PurchaseOutcome {
  return try {
    purchaseOutcome(productId)
  } catch (_: AbstractMethodError) {
    legacyPurchaseOutcome(productId)
  } catch (_: NoSuchMethodError) {
    legacyPurchaseOutcome(productId)
  }
}

private suspend fun NuxiePurchaseDelegate.legacyPurchaseOutcome(productId: String): PurchaseOutcome {
  return PurchaseOutcome(
    result = purchase(productId),
    productId = productId,
  )
}

internal fun PlayStorePurchase.withKnownProductId(productId: String): PlayStorePurchase {
  val normalizedProductId = productId.trim().takeIf(String::isNotEmpty) ?: return this
  val normalizedProductIds = productIds
    .mapNotNull { it.trim().takeIf(String::isNotEmpty) }
    .distinct()

  if (normalizedProductIds.isNotEmpty()) {
    return if (normalizedProductIds == productIds) this else copy(productIds = normalizedProductIds)
  }

  return copy(productIds = listOf(normalizedProductId))
}
