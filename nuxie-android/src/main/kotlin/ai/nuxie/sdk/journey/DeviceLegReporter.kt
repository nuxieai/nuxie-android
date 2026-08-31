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
    suspend fun flushPending() {
        for (run in journal.runs()) {
            if (!run.startedQueued) {
                if (!queue(run, completion = false)) continue
                journal.markStartedQueued(run)
            }
            if (run.completion != null) {
                if (!queue(run, completion = true)) continue
                journal.markCompletionQueued(run)
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
