package ai.nuxie.sdk.journey

import ai.nuxie.sdk.events.JsonValueConverter
import ai.nuxie.sdk.util.IsoDates
import kotlinx.serialization.json.jsonPrimitive

/** Uses EventLog.captureIdempotently: stable IDs, ordinary delivery and host
 * privacy policy. Durable beforeSend drops are terminal acknowledgements too. */
internal class JourneyReporter(
    private val journal: JourneyRunJournal,
    private val capture: suspend (String, Map<String, Any?>, String, String) -> Boolean,
) {
    private var onRunRetired: (JourneyRun) -> Unit = {}

    constructor(
        journal: JourneyRunJournal,
        capture: suspend (String, Map<String, Any?>, String, String) -> Boolean,
        onRunRetired: (JourneyRun) -> Unit,
    ) : this(journal, capture) {
        this.onRunRetired = onRunRetired
    }

    suspend fun flushPending() {
        for (run in journal.runs()) {
            if (!run.startedQueued) {
                if (!queue(run, completion = false)) continue
                journal.markStartedQueued(run)
            }
            if (run.completion != null) {
                if (run.experimentExposures.any {
                        it.shownAtMillis != null && !it.queued
                    }
                ) continue
                if (run.pendingPresentationPublication != null) continue
                if (!queue(run, completion = true)) continue
                journal.markCompletionQueued(run)
                onRunRetired(run)
            }
        }
    }

    private suspend fun queue(run: JourneyRun, completion: Boolean): Boolean {
        val properties = linkedMapOf<String, Any?>(
            "journey_id" to run.journeyId,
            "experience_id" to run.experienceId,
            "experience_version_id" to run.reference.getValue("versionId").jsonPrimitive.content,
            "leg_id" to run.reference.getValue("legId").jsonPrimitive.content,
            "leg_generation" to run.generation,
            "started_at" to IsoDates.formatMillis(run.startedAtMillis),
        )
        if (completion) {
            val result = checkNotNull(run.completion)
            properties["completed_at"] = IsoDates.formatMillis(result.atMillis)
            properties["outcome"] = result.outcome
            properties["outputs"] = JsonValueConverter.toNativeMap(run.outputs)
        }
        return capture(if (completion) JourneyEventNames.LEG_COMPLETED else JourneyEventNames.LEG_STARTED, properties,
            if (completion) run.completedEventId else run.startedEventId, journal.distinctId)
    }
}

/** Publishes only selected variants that reached their bound visible screen. */
internal class JourneyExperimentExposureReporter(
    private val journal: JourneyRunJournal,
    private val capture: suspend (String, Map<String, Any?>, String, String) -> Boolean,
) {
    suspend fun flushPending(): Boolean {
        for (run in journal.runs()) {
            for (exposure in run.experimentExposures) {
                if (exposure.shownAtMillis == null || exposure.queued) continue
                val projection = projection(run, exposure)
                if (!capture(
                        projection.first,
                        projection.second,
                        exposure.eventId,
                        journal.distinctId,
                    )
                ) return false
                if (!journal.markExperimentExposureQueued(run.id, exposure.eventId)) {
                    return false
                }
            }
        }
        return true
    }

    private fun projection(
        run: JourneyRun,
        exposure: JourneyRun.ExperimentExposure,
    ): Pair<String, Map<String, Any?>> {
        val properties = linkedMapOf<String, Any?>(
            "journey_id" to run.journeyId,
            "experience_id" to run.experienceId,
            "experience_version" to run.reference.getValue("versionId").jsonPrimitive.content,
            "leg_id" to run.reference.getValue("legId").jsonPrimitive.content,
            "leg_generation" to run.generation,
            "experiment_key" to exposure.experimentId,
            "variant_key" to exposure.variantId,
        )
        when (exposure.kind) {
            JourneyRun.ExperimentExposure.Kind.ASSIGNED -> {
                properties["assignment_source"] = "profile"
            }
            JourneyRun.ExperimentExposure.Kind.FALLBACK -> {
                properties["assignment_source"] = "fallback"
            }
        }
        properties["is_holdout"] = exposure.isHoldout
        return JourneyEventNames.EXPERIMENT_EXPOSURE to properties
    }
}
