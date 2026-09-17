package ai.nuxie.sdk.runtime

import ai.nuxie.sdk.experiences.ExperienceVideoAssetBinding
import android.content.Context

/** Runtime-lane owner. Acquisition retains the files until this owner closes. */
internal class ExperienceVideoPlayback(
    private val context: Context,
    private val player: NuxieRuntimePlayer,
    bindings: List<ExperienceVideoAssetBinding>,
) : AutoCloseable {
    private class Entry(val binding: ExperienceVideoAssetBinding) {
        var decoder: AndroidVideoDecoder? = null
        var ready = false
        var ended = false
        var failed = false
        var interrupted = false
        var pausedByHost = false
    }
    private val entries = player.videos().associate { video ->
        require(!video.embedded) { "Published video must use an acquired file" }
        val binding = bindings.single { it.authoredId == video.assetId && it.sourceAssetKey == video.sourceKey }
        video.componentId to Entry(binding)
    }
    private val captions = mutableMapOf<Pair<String, Int>, List<NuxieVideoCaptionCue>>()
    private var hidden = true
    private var closed = false

    /** Stop audio immediately even when a submitted Vulkan frame is still pending. */
    fun setVisible(visible: Boolean) {
        if (closed || hidden == !visible) return
        hidden = !visible
        for ((component, entry) in entries) {
            player.videoCommand(component, 6, if (hidden) 1.0 else 0.0, 1)
            if (hidden) {
                entry.decoder?.action(1, 0.0, 0)
                entry.pausedByHost = true
            }
        }
    }

    /** Called only before a new scene step, never while a native frame is submitted. */
    fun advance(renderer: NuxieAndroidVulkanRenderer, monotonicSeconds: Double) {
        check(!closed)
        for (video in player.videos()) {
            val entry = entries[video.componentId] ?: error("Video occurrence changed outside its owning session")
            if (entry.failed) continue
            if (entry.decoder == null && !hidden) {
                entry.decoder = entry.binding.file?.let { file ->
                    entry.binding.captionTracks.firstOrNull()?.let { track ->
                        val cues = captions.getOrPut(file.absolutePath to track.streamIndex) {
                            ExperienceVideoCaptions.read(file, track.streamIndex)
                        }
                        player.videoSetCaptions(video.componentId, track.language.orEmpty(), cues)
                    }
                    AndroidVideoDecoder(context, file, video.generation, 64 * 1024 * 1024, video.audioPolicy)
                }
            }
            val decoder = entry.decoder
            val observation = when {
                entry.binding.file == null || decoder?.failure() != null -> { entry.failed = true; 6 }
                decoder != null && decoder.ready() && !entry.ready -> { entry.ready = true; 1 }
                decoder != null && decoder.ended() && !entry.ended -> { entry.ended = true; 3 }
                decoder != null && decoder.playing() -> { entry.ended = false; 2 }
                else -> 0
            }
            if (decoder != null) {
                val interrupted = decoder.interrupted()
                if (entry.interrupted != interrupted) {
                    entry.interrupted = interrupted
                    player.videoCommand(video.componentId, 6, if (interrupted) 1.0 else 0.0, 4)
                }
                if (decoder.takePermanentLoss()) player.videoCommand(video.componentId, 1)
            }
            val blocked = decoder?.takePlayBlocked() == true
            val actions = player.videoStep(video.componentId, if (blocked && observation != 6) 5 else observation,
                video.generation, if (observation == 1) checkNotNull(decoder).duration() else 0.0)
            for (action in actions) {
                if (action.kind == 5) {
                    decoder?.close()
                    entry.decoder = null
                    entry.failed = true
                } else decoder?.action(action.kind, action.value, action.generation)
            }
            if (entry.failed) {
                decoder?.close()
                entry.decoder = null
                continue
            }
            // A hide/show can occur entirely while presentation is pending. The
            // decoder was paused immediately; restore only actual native play intent.
            if (!hidden && entry.pausedByHost) {
                val current = player.videos().first { it.componentId == video.componentId }
                if (current.state == 2 && current.wantsPlay) decoder?.action(0, 0.0, current.generation)
                entry.pausedByHost = false
            }
            val clock = decoder?.clock()
            player.videoClock(video.componentId, monotonicSeconds,
                if (clock == null) NuxieVideoClock(video.generation, 0.0, 0.0, false, false)
                else NuxieVideoClock(clock.generation, clock.seconds, clock.rate, clock.playing, true))
            decoder?.takeFrame()?.let {
                player.videoPresent(renderer, video.componentId,
                    NuxieVideoFrame(it.generation, it.seconds, it.width, it.height, it.rgba))
            }
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        var failure: Throwable? = null
        for (entry in entries.values) {
            try { entry.decoder?.close() } catch (error: Throwable) {
                if (failure == null) failure = error else failure.addSuppressed(error)
            }
            entry.decoder = null
        }
        captions.clear()
        failure?.let { throw it }
    }
}
