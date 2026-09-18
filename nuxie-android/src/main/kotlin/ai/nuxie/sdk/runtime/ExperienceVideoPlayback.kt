package ai.nuxie.sdk.runtime

import ai.nuxie.sdk.experiences.ExperienceVideoAssetBinding
import ai.nuxie.sdk.experiences.ExperienceVideoElement
import ai.nuxie.sdk.experiences.JourneyVideoAction
import android.content.Context

/** Runtime-lane owner. Acquisition retains the files until this owner closes. */
internal class ExperienceVideoPlayback(
    private val context: Context,
    private val player: NuxieRuntimePlayer,
    private val bindings: List<ExperienceVideoAssetBinding>,
    private val targets: List<ExperienceVideoElement> = emptyList(),
    private val nanoTime: () -> Long = System::nanoTime,
    private val decoderBudget: (() -> NuxieVideoDecoderBudget)? = null,
) : AutoCloseable {
    private class Entry(val binding: ExperienceVideoAssetBinding) {
        var decoder: AndroidVideoDecoder? = null
        var ready = false
        var ended = false
        var failed = false
        var interrupted = false
        var pausedByHost = false
        var resourceBlocked = false
        var rate = 1.0
    }
    private val entries = mutableMapOf<Long, Entry>()
    private val decodeCosts = mutableMapOf<String, Long>()
    private class Admission(var elapsed: Double = 0.0, var decision: Int = 0)
    private val admissions = mutableMapOf<Long, Admission>()
    private var admissionClock = nanoTime()
    private var presentationAdmitted = false

    private fun advanceAdmissionClock() {
        val now = nanoTime()
        val elapsed = (now - admissionClock).coerceAtLeast(0) / 1_000_000_000.0
        admissionClock = now
        if (!hidden) admissions.values.filter { it.decision == 0 }.forEach { it.elapsed += elapsed }
    }

    fun isReadyForPresentation(): Boolean {
        check(!closed)
        advanceAdmissionClock()
        val videos = player.videos()
        val live = videos.map { it.componentId }.toSet()
        admissions.keys.retainAll(live)
        var waiting = false
        for (video in videos.filter { it.readiness == 1 }) {
            val target = targets.single {
                it.sourceArtboardIndex == video.sourceArtboardIndex && it.componentId == video.sourceComponentId
            }
            val admission = admissions.getOrPut(video.componentId) { Admission() }
            if (admission.decision == 0) {
                admission.decision = player.videoReadiness(video.componentId, admission.elapsed,
                    target.readinessTimeoutSeconds, target.optional)
                if (admission.decision == 2) {
                    entries[video.componentId]?.let {
                        it.decoder?.close()
                        it.decoder = null
                        it.failed = true
                    }
                    player.videoCommand(video.componentId, 8)
                    player.videoStep(video.componentId, 0, video.generation)
                }
            }
            check(admission.decision != 3) { "Required video first frame unavailable" }
            waiting = waiting || admission.decision == 0
        }
        // New list rows retain deadlines without hiding the existing screen.
        if (!waiting) presentationAdmitted = true
        return presentationAdmitted
    }


    private fun reconcile(videos: List<NuxieVideoOccurrence>): List<NuxieVideoOccurrence> {
        val live = videos.map { it.componentId }.toSet()
        require(live.size == videos.size) { "Duplicate video occurrence identity" }
        val additions = videos.filter { it.componentId !in entries }.associate { video ->
            require(!video.embedded) { "Published video must use an acquired file" }
            require(targets.any {
                it.sourceArtboardIndex == video.sourceArtboardIndex && it.componentId == video.sourceComponentId
            }) { "Video occurrence differs from signed target inventory" }
            val binding = bindings.single { it.authoredId == video.assetId && it.sourceAssetKey == video.sourceKey }
            video.componentId to Entry(binding)
        }
        val removed = entries.keys.filter { it !in live }.mapNotNull(entries::remove)
        var failure: Throwable? = null
        for (entry in removed) {
            try { entry.decoder?.close() } catch (error: Throwable) {
                if (failure == null) failure = error else failure.addSuppressed(error)
            }
        }
        failure?.let { throw it }
        entries.putAll(additions)
        for (id in additions.keys) player.videoCommand(id, 6, if (hidden) 1.0 else 0.0, 1)
        return videos
    }
    private val captions = mutableMapOf<Pair<String, Int>, List<NuxieVideoCaptionCue>>()
    private var hidden = true
    private var closed = false

    init { reconcile(player.videos()) }

    fun apply(action: JourneyVideoAction) {
        check(!closed)
        val matches = targets.filter { it.artboardId == action.artboardId && it.viewNodeId == action.viewNodeId }
        val live = player.videos().filter { occurrence ->
            matches.any { target ->
                target.sourceArtboardIndex == occurrence.sourceArtboardIndex &&
                    target.componentId == occurrence.sourceComponentId
            }
        }
        require(live.isNotEmpty()) { "Video target is not mounted in this screen" }
        for (occurrence in live) player.videoCommand(occurrence.componentId, action.commandKind, action.commandValue)
    }

    /** Stop audio immediately even when a submitted Vulkan frame is still pending. */
    fun setVisible(visible: Boolean) {
        if (closed || hidden == !visible) return
        advanceAdmissionClock()
        hidden = !visible
        reconcile(player.videos())
        for ((component, entry) in entries) {
            player.videoCommand(component, 6, if (hidden) 1.0 else 0.0, 1)
            if (hidden) {
                entry.decoder?.action(1, 0.0, 0)
                entry.pausedByHost = true
            }
        }
    }

    /** Retire all denied owners before opening any replacement; callbacks use a fresh generation. */
    private fun admit(videos: List<NuxieVideoOccurrence>): List<NuxieVideoOccurrence> {
        val budget = decoderBudget?.invoke() ?: return videos
        for (video in videos) {
            val entry = checkNotNull(entries[video.componentId])
            if (entry.failed) continue
            for (action in player.videoStep(video.componentId, 0, video.generation)) {
                if (action.kind == 3) entry.rate = action.value
                if (action.kind == 5) {
                    entry.decoder?.close()
                    entry.decoder = null
                    entry.failed = true
                } else entry.decoder?.action(action.kind, action.value, action.generation)
            }
        }
        val requests = videos.map { video ->
            val entry = checkNotNull(entries[video.componentId])
            val cost = if (entry.failed) 0L else entry.binding.file?.let { file ->
                try {
                    decodeCosts.getOrPut(file.absolutePath) { ExperienceVideoDecodeCost.read(file) }
                } catch (_: Exception) {
                    entry.failed = true
                    entry.decoder?.close()
                    entry.decoder = null
                    player.videoStep(video.componentId, 6, video.generation)
                    0L
                }
            } ?: 0L
            val scaled = kotlin.math.ceil(cost * maxOf(1.0, entry.rate))
            val pixels = if (scaled >= Long.MAX_VALUE.toDouble()) Long.MAX_VALUE else scaled.toLong()
            NuxieVideoDecoderRequest(video.componentId, pixels, video.priority.toLong() and 0xffff_ffffL,
                !hidden && !entry.failed && entry.binding.file != null)
        }
        val choices = player.videoAllocateDecoders(requests, budget)
        // A failed synchronous close leaves ownership intact and aborts admission.
        for ((index, video) in videos.withIndex()) {
            val entry = checkNotNull(entries[video.componentId])
            if (entry.failed || entry.binding.file == null) continue
            val blocked = choices[index] == NuxieVideoAllocation.Poster
            check(blocked || choices[index] == NuxieVideoAllocation.PlatformManaged) {
                "MediaPlayer cannot force a hardware or software decoder"
            }
            if (blocked && !entry.resourceBlocked) {
                entry.decoder?.close()
                entry.decoder = null
                if (entry.interrupted) {
                    player.videoCommand(video.componentId, 6, 0.0, 4)
                    player.videoStep(video.componentId, 0, video.generation)
                }
                player.videoReclaimDecoder(video.componentId, true)
                entry.resourceBlocked = true
                entry.ready = false
                entry.ended = false
                entry.interrupted = false
                entry.pausedByHost = false
            }
        }
        for ((index, video) in videos.withIndex()) {
            val entry = checkNotNull(entries[video.componentId])
            if (!entry.failed && entry.resourceBlocked && choices[index] == NuxieVideoAllocation.PlatformManaged) {
                player.videoReclaimDecoder(video.componentId, false)
                entry.resourceBlocked = false
            }
        }
        return player.videos()
    }

    /** Called only before a new scene step, never while a native frame is submitted. */
    fun advance(renderer: NuxieAndroidVulkanRenderer, monotonicSeconds: Double) {
        check(!closed)
        for (video in admit(reconcile(player.videos()))) {
            val entry = checkNotNull(entries[video.componentId])
            if (entry.failed) continue
            if (entry.decoder == null && !hidden && !entry.resourceBlocked) {
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
                if (action.kind == 3) entry.rate = action.value
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

    fun captionSnapshot(): Map<Long, NuxieVideoCaption> {
        if (hidden || closed) return emptyMap()
        val live = player.videos().map { it.componentId }.toSet()
        return entries.filter { (id, entry) -> id in live && !entry.failed }.keys.associateWith(player::videoCaption)
    }

    override fun close() {
        if (closed) return
        closed = true
        admissions.clear()
        var failure: Throwable? = null
        for (entry in entries.values) {
            try { entry.decoder?.close() } catch (error: Throwable) {
                if (failure == null) failure = error else failure.addSuppressed(error)
            }
            entry.decoder = null
        }
        captions.clear()
        decodeCosts.clear()
        failure?.let { throw it }
    }
}
