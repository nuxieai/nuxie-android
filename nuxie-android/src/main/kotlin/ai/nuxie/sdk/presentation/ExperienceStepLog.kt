package ai.nuxie.sdk.presentation

import ai.nuxie.sdk.logging.NuxieLog
import android.content.Context
import android.content.pm.ApplicationInfo
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.Deferred

/**
 * Magic Move step timing for debug builds, under the tag [TAG] (README, "Magic Move step timing").
 *
 * Each line carries the Choreographer frame time a page's player step used, so the lines line up with a
 * screen recording read through `adb logcat -v monotonic`. A page surface in a debuggable app owns one;
 * a release app has none, so nothing on this path runs or logs. Every call runs on the surface's lane.
 */
internal class ExperienceStepLog {
    /** One Magic Move step, from the batch that begins its move to the batch that lands it. */
    class Step(internal val from: String, internal val to: String, internal val back: Boolean) {
        internal var id = 0L
        internal var completions: List<Deferred<Unit>> = emptyList()
        internal var startNanos = 0L
        internal var lastNanos = 0L
        internal var arrivedNanos: Long? = null
    }

    private var beginning: Step? = null
    private var moving: Step? = null

    /** In the write that begins [step]'s move. The player step that follows it in the same frame starts the step. */
    fun begin(step: Step, completions: List<Deferred<Unit>>) {
        step.completions = completions
        beginning = step
    }

    /** After each Choreographer player step, with its frame time and the elapsed time it advanced the player by. */
    fun stepped(frameNanos: Long, elapsedSeconds: Double) {
        beginning?.let { step ->
            beginning = null
            step.id = ids.incrementAndGet()
            step.startNanos = frameNanos
            moving = step
            NuxieLog.d(TAG, "step start", null, NuxieLog.status("id", step.id), NuxieLog.sensitive("from", step.from),
                NuxieLog.sensitive("to", step.to), NuxieLog.status("back", if (step.back) 1 else 0),
                NuxieLog.status("frameNanos", frameNanos))
        }
        val step = moving ?: return
        step.lastNanos = frameNanos
        // The step lands at the player step that reported its last awaited completion event.
        if (step.arrivedNanos == null && step.completions.all { it.isCompleted && !it.isCancelled }) {
            step.arrivedNanos = frameNanos
        }
        NuxieLog.v(TAG, "frame", null, NuxieLog.status("id", step.id), NuxieLog.status("frameNanos", frameNanos),
            NuxieLog.status("elapsedMs", milliseconds(elapsedSeconds * 1_000_000_000.0)))
    }

    /** After a player step outside a frame. A move begun in it has no frame time, so its step logs nothing. */
    fun unframed() {
        beginning = null
    }

    /**
     * In the write that lands [step]: at the frame where its last completion event arrived, or, when its
     * watchdog ended the wait first, at the last frame that stepped it.
     */
    fun land(step: Step) {
        if (beginning === step) beginning = null
        if (moving !== step) return
        moving = null
        val nanos = step.arrivedNanos ?: step.lastNanos
        NuxieLog.d(TAG, "step land", null, NuxieLog.status("id", step.id), NuxieLog.status("frameNanos", nanos),
            NuxieLog.status("lengthMs", milliseconds((nanos - step.startNanos).toDouble())))
    }

    /** [step] ended without landing, interrupted or failed: it logs nothing more. */
    fun close(step: Step) {
        if (beginning === step) beginning = null
        if (moving === step) moving = null
    }

    companion object {
        const val TAG = "NuxieMagicMove"

        /** Counts steps across the process, so two pages' lines never share an id. */
        private val ids = AtomicLong()

        /** A debuggable app's step log; a release app has none. */
        fun forApp(context: Context): ExperienceStepLog? =
            if (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) ExperienceStepLog() else null

        /** Nanoseconds as milliseconds, rounded to the microsecond. */
        private fun milliseconds(nanos: Double): Double = Math.round(nanos / 1_000.0) / 1_000.0
    }
}
