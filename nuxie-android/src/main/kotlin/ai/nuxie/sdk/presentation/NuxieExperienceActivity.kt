package ai.nuxie.sdk.presentation

import ai.nuxie.sdk.runtime.NuxieRuntimeLane
import ai.nuxie.sdk.runtime.NuxieRuntimeEvent
import ai.nuxie.sdk.runtime.NuxieViewModelSnapshot
import android.Manifest
import android.app.Activity
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.graphics.Outline
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal class ScreenCloseState(
    private val report: (CloseReason) -> Unit = {},
) {
    private val selected = AtomicReference<CloseReason?>(null)
    private val reported = AtomicBoolean(false)

    val reason: CloseReason? get() = selected.get()

    fun select(reason: CloseReason): Boolean = selected.compareAndSet(null, reason)

    fun prepareForTeardown(isChangingConfigurations: Boolean) {
        if (reason == null && isChangingConfigurations) return
        select(CloseReason.UserDismissed)
    }

    fun reportAtTeardown(isChangingConfigurations: Boolean) {
        prepareForTeardown(isChangingConfigurations)
        if (reason == null) return
        if (reported.compareAndSet(false, true)) report(requireNotNull(reason))
    }
}

/**
 * The single engine-owned Activity hosting every signed presentation style
 * as in-Activity shells (spec section 16 decision 9). The Intent carries
 * only a process-local presentation id; authenticated content and callbacks
 * remain owned by [ExperiencePresentationService].
 *
 * Process-death policy (decision 10): a cold-recreated instance (no live
 * SDK state behind it) finishes immediately and never re-presents.
 */
internal class NuxieExperienceActivity :
    Activity(),
    PresentationActivityHandle {
    private var host: ExperienceSurfaceHost? = null
    private var lane: NuxieRuntimeLane? = null
    private var presentationId: String? = null
    private val screenClose = ScreenCloseState(::reportSelectedClose)
    private var dismissible = true
    private var predictiveBackCallback: android.window.OnBackInvokedCallback? = null
    private var pendingPermission: Pair<Int, CompletableDeferred<Boolean>>? = null
    private var nextPermissionRequestCode = PERMISSION_REQUEST_CODE_START

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val presentationId = intent.getStringExtra(EXTRA_PRESENTATION_ID)
        this.presentationId = presentationId

        if (isColdRecreation(savedInstanceState, presentationId)) {
            finish()
            return
        }
        if (presentationId == null) {
            Log.w(LOG_TAG, "No presentation id supplied.")
            finish()
            return
        }
        if (!AndroidRenderCapability.isAvailable()) {
            fail(ExperiencePresentationException(
                ExperiencePresentationException.Reason.RUNTIME_UNAVAILABLE,
                "Experience renderer is unavailable on this device",
            ))
            return
        }
        val prepared = PresentationRegistry.resolve(presentationId)
        if (prepared == null || !PresentationRegistry.attach(presentationId, this)) {
            // Missing process-local state is the process-death fail-closed
            // path. Never recover content from persisted Intent data.
            Log.i(LOG_TAG, "Presentation state unavailable; finishing.")
            finish()
            return
        }
        val rivBytes = runCatching { prepared.rivFile.readBytes() }.getOrNull()
        if (rivBytes == null) {
            fail(IllegalStateException("Prepared Experience content is unreadable"))
            return
        }

        val lane = NuxieRuntimeLane()
        this.lane = lane
        val host = ExperienceSurfaceHost(
            context = this,
            lane = lane,
            clearColor = prepared.clearColor,
            artboardSize = prepared.artboardSize,
            listener = object : ExperienceSurfaceHost.Listener {
                override fun onFirstFrame() {
                    PresentationRegistry.reportFirstFrame(presentationId)
                }

                override fun onRuntimeStep(
                    outcome: ai.nuxie.sdk.runtime.NuxiePlayerStepOutcome,
                    correlationId: ULong,
                    viewModelSnapshot: NuxieViewModelSnapshot?,
                ) {
                    PresentationRegistry.reportRuntimeStep(
                        presentationId,
                        outcome,
                        correlationId,
                        viewModelSnapshot,
                    )
                }

                override fun onFailure(error: ExperiencePresentationException) {
                    fail(error)
                }

                override fun onRuntimeEvent(
                    event: NuxieRuntimeEvent,
                    viewModelSnapshot: NuxieViewModelSnapshot?,
                ) = Unit
            },
        )
        this.host = host
        loadPreparedRelease(host, rivBytes, prepared)
        dismissible = prepared.shell.dismissible
        setContentView(shellView(host, prepared.shell))
        registerPredictiveBack()
    }

    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    override fun onBackPressed() {
        if (dismissible) finishTerminal(CloseReason.UserDismissed)
    }

    override fun onDestroy() {
        val changingConfigurations = isChangingConfigurations
        screenClose.prepareForTeardown(changingConfigurations)
        host?.release()
        unregisterPredictiveBack()
        super.onDestroy()

        // Like iOS's presentationCleanupTask, lifecycle teardown only hands
        // off ordered cleanup. Suspend dismissal joins the registry completion
        // published after the lane has released every native handle.
        val completeTeardown = {
            screenClose.reportAtTeardown(changingConfigurations)
            presentationId?.let { PresentationRegistry.detach(it, this) }
            Unit
        }
        lane?.shutdown(completeTeardown) ?: completeTeardown()
        pendingPermission?.second?.complete(false)
        pendingPermission = null
    }

    override fun requestCloseFromService(reason: CloseReason): Boolean = screenClose.select(reason)

    override fun screenCloseReason(): CloseReason? = screenClose.reason

    override fun finishAfterServiceClose() {
        runOnUiThread { finish() }
    }

    override fun purchaseActivity(): Activity = this

    override suspend fun resolveJourneyPermission(request: JourneyPermissionRequest): Boolean =
        withContext(Dispatchers.Main.immediate) {
            when (request) {
                JourneyPermissionRequest.TRACKING,
                JourneyPermissionRequest.UNSUPPORTED,
                -> false
                JourneyPermissionRequest.NOTIFICATIONS -> resolveNotificationPermission()
                else -> resolveRuntimePermission(request.androidPermission())
            }
        }

    @Deprecated("Deprecated in Android SDK; required for minSdk-compatible permission delivery")
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        val pending = pendingPermission?.takeIf { it.first == requestCode } ?: return
        pendingPermission = null
        pending.second.complete(
            grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED },
        )
    }

    private suspend fun resolveNotificationPermission(): Boolean {
        if (Build.VERSION.SDK_INT < 33) {
            return if (Build.VERSION.SDK_INT < 24) {
                true
            } else {
                (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                    .areNotificationsEnabled()
            }
        }
        return resolveRuntimePermission(Manifest.permission.POST_NOTIFICATIONS)
    }

    private suspend fun resolveRuntimePermission(permission: String): Boolean {
        if (checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED) return true
        val declared = runCatching {
            packageManager.getPackageInfo(packageName, PackageManager.GET_PERMISSIONS)
                .requestedPermissions
                .orEmpty()
                .contains(permission)
        }.getOrDefault(false)
        if (!declared || pendingPermission != null || isFinishing || isDestroyed) return false
        val requestCode = nextPermissionRequestCode
        nextPermissionRequestCode = if (requestCode == PERMISSION_REQUEST_CODE_END) {
            PERMISSION_REQUEST_CODE_START
        } else {
            requestCode + 1
        }
        val result = CompletableDeferred<Boolean>()
        pendingPermission = requestCode to result
        requestPermissions(arrayOf(permission), requestCode)
        return try {
            result.await()
        } finally {
            if (pendingPermission?.first == requestCode) pendingPermission = null
        }
    }

    private fun JourneyPermissionRequest.androidPermission(): String = when (this) {
        JourneyPermissionRequest.CAMERA -> Manifest.permission.CAMERA
        JourneyPermissionRequest.LOCATION -> Manifest.permission.ACCESS_FINE_LOCATION
        JourneyPermissionRequest.MICROPHONE -> Manifest.permission.RECORD_AUDIO
        JourneyPermissionRequest.PHOTOS -> if (Build.VERSION.SDK_INT >= 33) {
            Manifest.permission.READ_MEDIA_IMAGES
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }
        else -> error("Permission request has no Android runtime permission")
    }

    private fun fail(error: Throwable) {
        val reason = CloseReason.Error(error)
        if (screenClose.select(reason)) runOnUiThread { finish() }
        presentationId?.let { PresentationRegistry.reportOutcome(it, reason) }
    }

    private fun finishTerminal(reason: CloseReason) {
        if (screenClose.select(reason)) finish()
        presentationId?.let { PresentationRegistry.reportOutcome(it, reason) }
    }

    private fun reportSelectedClose(reason: CloseReason) {
        presentationId?.let { id ->
            when (reason) {
                is CloseReason.Error -> PresentationRegistry.reportFailure(id, reason.cause)
                else -> PresentationRegistry.reportDismissed(id, reason)
            }
        }
    }

    /** Runtime attachment seam for authenticated, acquired Experience content. */
    private fun loadPreparedRelease(
        host: ExperienceSurfaceHost,
        rivBytes: ByteArray,
        prepared: PreparedPresentation,
    ) {
        host.loadArtboard(
            rivBytes = rivBytes,
            artboardName = prepared.artboardName,
            descriptor = prepared.descriptor,
            artifactsByKey = prepared.artifactsByKey,
            viewModelProjection = prepared.viewModelProjection,
        )
    }

    private fun shellView(host: ExperienceSurfaceHost, shell: PresentationShell): View {
        if (shell is PresentationShell.FullScreen) return host

        val root = FrameLayout(this)
        val scrim = View(this).apply {
            setBackgroundColor(SCRIM_COLOR)
            if (shell.dismissible) {
                setOnClickListener { finishTerminal(CloseReason.UserDismissed) }
            }
        }
        root.addView(
            scrim,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            ),
        )
        host.isClickable = true
        root.addView(host, shellLayoutParams(shell))
        return root
    }

    private fun shellLayoutParams(shell: PresentationShell): FrameLayout.LayoutParams = when (shell) {
        PresentationShell.FullScreen -> FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT,
        )
        is PresentationShell.Sheet -> {
            val height = when (shell.detent) {
                PresentationShell.Sheet.Detent.MEDIUM -> resources.displayMetrics.heightPixels / 2
                PresentationShell.Sheet.Detent.LARGE -> FrameLayout.LayoutParams.MATCH_PARENT
            }
            FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, height, Gravity.BOTTOM)
        }
        is PresentationShell.Drawer -> {
            val vertical = shell.edge == PresentationShell.Drawer.Edge.TOP ||
                shell.edge == PresentationShell.Drawer.Edge.BOTTOM
            val width = if (vertical) {
                FrameLayout.LayoutParams.MATCH_PARENT
            } else {
                (resources.displayMetrics.widthPixels * shell.extentRatio).toInt().coerceAtLeast(1)
            }
            val height = if (vertical) {
                (resources.displayMetrics.heightPixels * shell.extentRatio).toInt().coerceAtLeast(1)
            } else {
                FrameLayout.LayoutParams.MATCH_PARENT
            }
            val gravity = when (shell.edge) {
                PresentationShell.Drawer.Edge.TOP -> Gravity.TOP
                PresentationShell.Drawer.Edge.BOTTOM -> Gravity.BOTTOM
                PresentationShell.Drawer.Edge.LEADING -> Gravity.START
                PresentationShell.Drawer.Edge.TRAILING -> Gravity.END
            }
            applyRoundedOutline(shell.cornerRadiusDp)
            FrameLayout.LayoutParams(width, height, gravity)
        }
    }

    private fun applyRoundedOutline(cornerRadiusDp: Float) {
        if (cornerRadiusDp <= 0f) return
        val radius = cornerRadiusDp * resources.displayMetrics.density
        host?.background = GradientDrawable().apply {
            setColor(android.graphics.Color.TRANSPARENT)
            cornerRadius = radius
        }
        host?.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setRoundRect(0, 0, view.width, view.height, radius)
            }
        }
        host?.clipToOutline = true
    }

    private fun registerPredictiveBack() {
        if (android.os.Build.VERSION.SDK_INT < 33) return
        val callback = android.window.OnBackInvokedCallback {
            if (dismissible) finishTerminal(CloseReason.UserDismissed)
        }
        predictiveBackCallback = callback
        onBackInvokedDispatcher.registerOnBackInvokedCallback(
            android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT,
            callback,
        )
    }

    private fun unregisterPredictiveBack() {
        if (android.os.Build.VERSION.SDK_INT < 33) return
        predictiveBackCallback?.let(onBackInvokedDispatcher::unregisterOnBackInvokedCallback)
        predictiveBackCallback = null
    }

    internal companion object {
        const val LOG_TAG = "Nuxie"
        const val SCRIM_COLOR = 0x66000000

        const val EXTRA_PRESENTATION_ID = "ai.nuxie.sdk.internal.PRESENTATION_ID"
        const val PERMISSION_REQUEST_CODE_START = 0x4E00
        const val PERMISSION_REQUEST_CODE_END = 0x4EFF

        internal fun isColdRecreation(savedInstanceState: Bundle?, presentationId: String?): Boolean =
            savedInstanceState != null &&
                (presentationId == null || PresentationRegistry.resolve(presentationId) == null)
    }
}
