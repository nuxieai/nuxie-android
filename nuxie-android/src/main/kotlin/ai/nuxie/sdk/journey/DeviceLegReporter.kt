package ai.nuxie.sdk.journey

import ai.nuxie.sdk.events.JsonValueConverter
import ai.nuxie.sdk.util.IsoDates
import kotlinx.serialization.json.jsonPrimitive

/** Uses EventLog.captureIdempotently: stable IDs, ordinary delivery and host
 * privacy policy. Durable beforeSend drops are terminal acknowledgements too. */
internal class DeviceLegReporter(
    private val journal: DeviceLegRunJournal,
    private val capture: suspend (String, Map<String, Any?>, String, String) -> Boolean,
) {
    private var onRunRetired: (DeviceLegRun) -> Unit = {}

    constructor(
        journal: DeviceLegRunJournal,
        capture: suspend (String, Map<String, Any?>, String, String) -> Boolean,
        onRunRetired: (DeviceLegRun) -> Unit,
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

    private suspend fun queue(run: DeviceLegRun, completion: Boolean): Boolean {
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
internal class DeviceLegExperimentExposureReporter(
    private val journal: DeviceLegRunJournal,
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
        run: DeviceLegRun,
        exposure: DeviceLegRun.ExperimentExposure,
    ): Pair<String, Map<String, Any?>> {
        val properties = linkedMapOf<String, Any?>(
            "journey_id" to run.journeyId,
            "experience_id" to run.experienceId,
            "experience_version" to run.reference.getValue("versionId").jsonPrimitive.content,
            "experiment_key" to exposure.experimentId,
            "variant_key" to exposure.variantId,
        )
        val eventName = when (exposure.kind) {
            DeviceLegRun.ExperimentExposure.Kind.ASSIGNED -> {
                properties["assignment_source"] = "profile"
                properties["is_holdout"] = exposure.isHoldout
                JourneyEventNames.EXPERIMENT_EXPOSURE
            }
            DeviceLegRun.ExperimentExposure.Kind.FALLBACK -> {
                properties["assignment_source"] = "no_assignment"
                JourneyEventNames.EXPERIMENT_EXPOSURE_FALLBACK
            }
            DeviceLegRun.ExperimentExposure.Kind.INVALID_ASSIGNMENT -> {
                properties["variant_key"] = exposure.assignedVariantId ?: exposure.variantId
                properties["reason"] = "variant_not_found"
                JourneyEventNames.EXPERIMENT_EXPOSURE_ERROR
            }
        }
        return eventName to properties
    }
}
