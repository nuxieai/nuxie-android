package ai.nuxie.sdk.presentation

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
internal class NuxieExperienceActivity : Activity() {
    private var currentScreen: Screen? = null
    private var dismissible = true
    private var predictiveBackCallback: android.window.OnBackInvokedCallback? = null
    private var pendingPermission: Pair<Int, CompletableDeferred<Boolean>>? = null
    private var nextPermissionRequestCode = PERMISSION_REQUEST_CODE_START

    /** Registry ownership belongs to this screen, even when the Activity hosts another one. */
    private inner class Screen(val id: String, val prepared: PreparedPresentation) :
        PresentationScreenHandle, ExperienceSurfaceHost.Listener {
        var mounted: ExperienceMountedScreen? = null
            private set
        private val closeState = ScreenCloseState { reason ->
            when (reason) {
                is CloseReason.Error -> PresentationRegistry.reportFailure(id, reason.cause)
                else -> PresentationRegistry.reportDismissed(id, reason)
            }
        }

        fun mount(): View {
            val resources = ExperienceMountedScreen(
                this@NuxieExperienceActivity, prepared, this, ::fail,
            )
            mounted = resources
            return resources.mount()
        }

        fun close(changingConfigurations: Boolean) {
            closeState.prepareForTeardown(changingConfigurations)
            val complete = {
                closeState.reportAtTeardown(changingConfigurations)
                PresentationRegistry.detach(id, this)
            }
            mounted?.close(changingConfigurations, complete) ?: complete()
            mounted = null
        }

        override fun requestCloseFromService(reason: CloseReason): Boolean = closeState.select(reason)
        override fun screenCloseReason(): CloseReason? = closeState.reason
        override fun finishAfterServiceClose() {
            runOnUiThread {
                mounted?.exit()
                finish()
            }
        }
        override fun purchaseActivity(): Activity = this@NuxieExperienceActivity
        override suspend fun resolveJourneyPermission(request: JourneyPermissionRequest): Boolean =
            this@NuxieExperienceActivity.resolveJourneyPermission(request)

        override fun onFirstFrame() {
            runOnUiThread {
                if (isDestroyed || isFinishing) return@runOnUiThread
                if (closeState.reason == null) mounted?.activate()
                PresentationRegistry.reportFirstFrame(id)
            }
        }
        override fun onRuntimeStep(
            outcome: ai.nuxie.sdk.runtime.NuxiePlayerStepOutcome,
            correlationId: ULong,
            viewModelSnapshot: NuxieViewModelSnapshot?,
        ) = PresentationRegistry.reportRuntimeStep(id, outcome, correlationId, viewModelSnapshot)
        override fun onRuntimeEvent(event: NuxieRuntimeEvent, viewModelSnapshot: NuxieViewModelSnapshot?) = Unit
        override fun onTextCommitted(inputId: String, text: String) =
            PresentationRegistry.reportTextCommitted(id, this, inputId, text)
        override fun onFailure(error: ExperiencePresentationException) = fail(error)

        fun fail(error: Throwable) = finishTerminal(CloseReason.Error(error))
        fun finishTerminal(reason: CloseReason) {
            if (closeState.select(reason)) finishAfterServiceClose()
            PresentationRegistry.reportOutcome(id, reason)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val id = intent.getStringExtra(EXTRA_PRESENTATION_ID)
        if (isColdRecreation(savedInstanceState, id) || id == null) {
            finish()
            return
        }
        if (!AndroidRenderCapability.isAvailable()) {
            PresentationRegistry.reportFailure(id, ExperiencePresentationException(
                ExperiencePresentationException.Reason.RUNTIME_UNAVAILABLE,
                "Experience renderer is unavailable on this device",
            ))
            finish()
            return
        }
        val prepared = PresentationRegistry.resolve(id)
        if (prepared == null) {
            Log.i(LOG_TAG, "Presentation state unavailable; finishing.")
            finish()
            return
        }
        val screen = Screen(id, prepared)
        if (!PresentationRegistry.attach(id, screen)) {
            finish()
            return
        }
        currentScreen = screen
        dismissible = prepared.shell.dismissible
        try {
            setContentView(shellView(screen.mount(), prepared.shell))
            screen.mounted?.observeWindow()
        } catch (error: Throwable) {
            screen.fail(error)
            return
        }
        registerPredictiveBack()
    }

    override fun onStart() {
        super.onStart()
        currentScreen?.mounted?.setVisible(true)
    }

    override fun onStop() {
        currentScreen?.mounted?.setVisible(false)
        super.onStop()
    }

    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    override fun onBackPressed() {
        if (dismissible) finishTerminal(CloseReason.UserDismissed)
    }

    override fun onDestroy() {
        unregisterPredictiveBack()
        super.onDestroy()
        currentScreen?.close(isChangingConfigurations)
        currentScreen = null
        pendingPermission?.second?.complete(false)
        pendingPermission = null
    }

    private suspend fun resolveJourneyPermission(request: JourneyPermissionRequest): Boolean =
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

    private fun finishTerminal(reason: CloseReason) {
        currentScreen?.finishTerminal(reason)
    }

    private fun shellView(host: View, shell: PresentationShell): View {
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
        if (shell is PresentationShell.Drawer) {
            // The SurfaceView has its own composition layer. Clip it and the
            // common content parent so native editable controls share the shell.
            applyRoundedOutline(host, shell.cornerRadiusDp)
            currentScreen?.mounted?.surface?.takeIf { it !== host }?.let { applyRoundedOutline(it, shell.cornerRadiusDp) }
        }
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
            FrameLayout.LayoutParams(width, height, gravity)
        }
    }

    private fun applyRoundedOutline(content: View, cornerRadiusDp: Float) {
        if (cornerRadiusDp <= 0f) return
        val radius = cornerRadiusDp * resources.displayMetrics.density
        content.background = GradientDrawable().apply {
            setColor(android.graphics.Color.TRANSPARENT)
            cornerRadius = radius
        }
        content.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setRoundRect(0, 0, view.width, view.height, radius)
            }
        }
        content.clipToOutline = true
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
