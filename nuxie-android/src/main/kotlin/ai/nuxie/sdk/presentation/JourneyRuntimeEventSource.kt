package ai.nuxie.sdk.presentation

import ai.nuxie.sdk.runtime.NuxieViewModelSnapshot

/** Process-local values from the exact frame that emitted the event. */
internal data class JourneyRuntimeEventSource(val nativeId: Long, val snapshot: NuxieViewModelSnapshot)

internal data class JourneyRuntimeEmissionSources(
    val control: JourneyRuntimeEventSource? = null,
    val drafts: List<JourneyRuntimeEventSource?> = emptyList(),
    val byEmissionId: Map<String, JourneyRuntimeEventSource> = emptyMap(),
    val frameLinks: JourneyFrameLinks? = null,
    val saves: List<ExperienceFrameSave> = emptyList(),
) {
    fun bound(batch: JourneyScreenEmissionBatch): JourneyRuntimeEmissionSources {
        val controlCount = batch.emissions.size - drafts.size
        return copy(byEmissionId = batch.emissions.mapIndexedNotNull { index, emission ->
            (if (index < controlCount) control else drafts[index - controlCount])?.let { emission.id to it }
        }.toMap())
    }
    fun source(eventId: String?): JourneyRuntimeEventSource? =
        if (eventId == null) control else byEmissionId[eventId]
}

/** One accepted frame owns its links even if publication is rejected. */
internal class JourneyFrameLinks(private var operation: (suspend () -> Unit)?) {
    private val gate = kotlinx.coroutines.sync.Mutex()
    suspend fun perform() {
        gate.lock()
        try {
            val work = operation
            operation = null
            work?.invoke()
        } finally { gate.unlock() }
    }
}

/** A captured sheet and the originating screen's fenced native continuation. */
internal data class ExperienceFrameSave(
    val request: ExperienceResponseSaveRequest,
    val screenID: String,
    val onConfirmed: suspend () -> Unit,
)
