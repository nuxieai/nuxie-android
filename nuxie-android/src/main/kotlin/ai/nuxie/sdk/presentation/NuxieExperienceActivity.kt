package ai.nuxie.sdk.presentation

import ai.nuxie.sdk.runtime.NuxieRuntimeBridge
import ai.nuxie.sdk.runtime.NuxieRuntimeLane
import android.app.Activity
import android.os.Bundle
import android.util.Log
import java.io.File

/**
 * The single engine-owned Activity hosting every signed presentation style
 * as in-Activity shells (spec section 16 decision 9). Tracer scope:
 * full-screen only, driven by an internal riv-path extra so the runtime
 * host can be smoke-tested end to end; the presentation service replaces
 * the entry point with prepared signed releases.
 *
 * Process-death policy (decision 10): a cold-recreated instance (no live
 * SDK state behind it) finishes immediately and never re-presents.
 */
internal class NuxieExperienceActivity : Activity() {
    private var host: ExperienceSurfaceHost? = null
    private var lane: NuxieRuntimeLane? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Cold recreation after process death: never re-present.
        if (savedInstanceState != null) {
            finish()
            return
        }
        if (!NuxieRuntimeBridge.isAvailable) {
            Log.i(LOG_TAG, "Runtime unavailable; nothing to present.")
            finish()
            return
        }
        val rivPath = intent.getStringExtra(EXTRA_RIV_PATH)
        val artboardName = intent.getStringExtra(EXTRA_ARTBOARD_NAME)
        val rivBytes = rivPath?.let { path ->
            runCatching { File(path).readBytes() }.getOrNull()
        }
        if (rivBytes == null) {
            Log.w(LOG_TAG, "No presentable content supplied.")
            finish()
            return
        }

        val lane = NuxieRuntimeLane()
        this.lane = lane
        val host = ExperienceSurfaceHost(this, lane)
        this.host = host
        host.loadArtboard(rivBytes, artboardName) { loaded ->
            if (!loaded) runOnUiThread { finish() }
        }
        setContentView(host)
    }

    override fun onDestroy() {
        host?.release()
        lane?.shutdown()
        super.onDestroy()
    }

    internal companion object {
        const val LOG_TAG = "Nuxie"

        /** Internal tracer extras; not public API. */
        const val EXTRA_RIV_PATH = "ai.nuxie.sdk.internal.RIV_PATH"
        const val EXTRA_ARTBOARD_NAME = "ai.nuxie.sdk.internal.ARTBOARD_NAME"
    }
}
