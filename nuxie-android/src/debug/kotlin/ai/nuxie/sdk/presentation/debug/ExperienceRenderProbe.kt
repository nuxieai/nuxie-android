package ai.nuxie.sdk.presentation.debug

import ai.nuxie.sdk.presentation.ExperiencePresentationException
import ai.nuxie.sdk.presentation.ExperienceSurfaceHost
import ai.nuxie.sdk.runtime.FileAssetKind
import ai.nuxie.sdk.runtime.NuxieRuntime
import ai.nuxie.sdk.runtime.NuxieRuntimeLane
import android.content.Context
import android.view.View
import android.widget.FrameLayout
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/**
 * Debug-only device-smoke seam into the real configured Experience renderer.
 * It is absent from release artifacts and is not an SDK integration API.
 */
object ExperienceRenderProbe {
    private val runtime = NuxieRuntime.shared
    /** The authored identity needed to build a synthetic image declaration. */
    data class InspectedImageAsset(
        val name: String,
        val authoredId: Long,
    )

    /**
     * Inspects the fixture through the runtime catalog path before the smoke
     * constructs its synthetic release descriptor.
     */
    @JvmStatic
    fun inspectImageAsset(sceneBytes: ByteArray): InspectedImageAsset {
        check(runtime.isAvailable) {
            "Nuxie runtime library is unavailable"
        }

        return inspectImageAssetOnLane(runtime, NuxieRuntimeLane(), sceneBytes)
    }

    /**
     * Returns a View that owns the same runtime lane and surface host used by
     * normal Experience presentation. Detaching the View releases both.
     */
    @JvmStatic
    fun createView(
        context: Context,
        sceneBytes: ByteArray,
        descriptorJson: String,
        artifactsByKey: Map<String, File>,
        onFirstFrame: () -> Unit,
        onFailure: (String) -> Unit,
    ): View {
        val descriptor = Json.parseToJsonElement(descriptorJson) as? JsonObject
            ?: error("Synthetic Journey release descriptor must be a JSON object")
        val lane = NuxieRuntimeLane()
        val firstFrameReported = AtomicBoolean(false)
        val host = ExperienceSurfaceHost(
            context = context,
            lane = lane,
            clearColor = CLEAR_COLOR_OPAQUE_BLACK,
            listener = object : ExperienceSurfaceHost.Listener {
                override fun onFirstFrame() {
                    if (firstFrameReported.compareAndSet(false, true)) onFirstFrame()
                }

                override fun onFailure(error: ExperiencePresentationException) {
                    onFailure(error.message ?: "Experience rendering failed")
                }
            },
        )
        host.loadArtboard(
            sceneBytes = sceneBytes,
            artboardName = null,
            descriptor = descriptor,
            artifactsByKey = artifactsByKey,
        )

        return object : FrameLayout(context) {
            private val released = AtomicBoolean(false)

            init {
                addView(
                    host,
                    LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT),
                )
            }

            override fun onDetachedFromWindow() {
                if (released.compareAndSet(false, true)) {
                    host.release()
                    lane.shutdown()
                }
                super.onDetachedFromWindow()
            }
        }
    }

    private const val CLEAR_COLOR_OPAQUE_BLACK = 0xFF000000.toInt()
}

/** Inspects on the supplied lane and takes responsibility for shutting it down. */
internal fun inspectImageAssetOnLane(
    runtime: NuxieRuntime,
    lane: NuxieRuntimeLane,
    sceneBytes: ByteArray,
    timeoutMillis: Long = 5_000L,
): ExperienceRenderProbe.InspectedImageAsset {
    val completed = CountDownLatch(1)
    var catalogFailure: Throwable? = null
    var imageAsset: ExperienceRenderProbe.InspectedImageAsset? = null
    val accepted = lane.enqueue {
        runCatching {
            val catalog = checkNotNull(runtime.inspectFileAssets(sceneBytes)) {
                "Runtime could not inspect the Experience asset catalog"
            }
            val image = catalog.singleOrNull { it.kind == FileAssetKind.IMAGE }
                ?: error("Expected exactly one image in the Experience asset catalog")
            ExperienceRenderProbe.InspectedImageAsset(
                name = image.name,
                authoredId = checkNotNull(image.authoredId) {
                    "Inspected image has no authored id"
                },
            )
        }.fold(
            onSuccess = { imageAsset = it },
            onFailure = { catalogFailure = it },
        )
    }
    lane.shutdown { completed.countDown() }

    val inspected = accepted && try {
        completed.await(timeoutMillis, TimeUnit.MILLISECONDS)
    } catch (_: InterruptedException) {
        Thread.currentThread().interrupt()
        false
    }
    check(inspected) { "Timed out inspecting the Experience asset catalog" }
    catalogFailure?.let { throw IllegalStateException(it.message, it) }
    return checkNotNull(imageAsset) { "Runtime returned no inspected image asset" }
}
