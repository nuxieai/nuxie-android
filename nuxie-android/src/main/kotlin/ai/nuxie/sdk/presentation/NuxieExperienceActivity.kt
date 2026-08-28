package ai.nuxie.sdk.presentation

import ai.nuxie.sdk.runtime.NuxieRuntimeLane
import android.app.Activity
import android.os.Bundle
import android.util.Log
import android.graphics.Outline
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.atomic.AtomicBoolean

internal class TerminalCloseClaim(
    private val report: (CloseReason) -> Unit = {},
) {
    private val claimed = AtomicReference<CloseReason?>(null)
    private val reported = AtomicBoolean(false)

    val reason: CloseReason? get() = claimed.get()

    fun tryClaim(reason: CloseReason): Boolean = claimed.compareAndSet(null, reason)

    fun release(reason: CloseReason): Boolean = claimed.compareAndSet(reason, null)

    fun prepareForTeardown(isChangingConfigurations: Boolean) {
        if (reason == null && isChangingConfigurations) return
        tryClaim(CloseReason.UserDismissed)
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
    private var host: ExperienceSurfaceHost? = null
    private var lane: NuxieRuntimeLane? = null
    private var presentationId: String? = null
    private val terminal = TerminalCloseClaim(::reportClaimedTerminal)
    private var dismissible = true
    private var predictiveBackCallback: android.window.OnBackInvokedCallback? = null

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
            listener = object : ExperienceSurfaceHost.Listener {
                override fun onFirstFrame() {
                    PresentationRegistry.reportFirstFrame(presentationId)
                }

                override fun onFailure(error: ExperiencePresentationException) {
                    fail(error)
                }
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
        terminal.prepareForTeardown(isChangingConfigurations)
        host?.release()
        lane?.shutdown()
        lane?.awaitQuiescence()
        unregisterPredictiveBack()
        super.onDestroy()
        // Publish terminal completion only after the renderer and its lane
        // and the Activity are torn down so awaitable host dismissal covers
        // the complete local cleanup boundary.
        presentationId?.let { PresentationRegistry.detach(it, this) }
        terminal.reportAtTeardown(isChangingConfigurations)
    }

    internal fun claimFromService(reason: CloseReason): Boolean = terminal.tryClaim(reason)

    internal fun claimedCloseReason(): CloseReason? = terminal.reason

    internal fun releaseServiceClaim(reason: CloseReason): Boolean = terminal.release(reason)

    internal fun finishAfterServiceClaim() {
        runOnUiThread { finish() }
    }

    private fun fail(error: Throwable) {
        if (!terminal.tryClaim(CloseReason.Error(error))) return
        runOnUiThread { finish() }
    }

    private fun finishTerminal(reason: CloseReason) {
        if (terminal.tryClaim(reason)) finish()
    }

    private fun reportClaimedTerminal(reason: CloseReason) {
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

        internal fun isColdRecreation(savedInstanceState: Bundle?, presentationId: String?): Boolean =
            savedInstanceState != null &&
                (presentationId == null || PresentationRegistry.resolve(presentationId) == null)
    }
}
