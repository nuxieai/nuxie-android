package ai.nuxie.sdk.presentation

/** Immutable history for one native layer. Its root is never popped by back. */
internal data class ExperienceLayerHistory(val screens: List<String>) {
    init { require(screens.isNotEmpty()) }

    fun backTarget(steps: Int): String = screens[(screens.lastIndex - steps.coerceAtLeast(1)).coerceAtLeast(0)]

    fun navigate(target: String, push: Boolean): ExperienceLayerHistory {
        // E47 provisional decision: reuse an earlier screen and preserve its state.
        val existing = screens.indexOf(target)
        if (existing >= 0) return ExperienceLayerHistory(screens.take(existing + 1))
        return ExperienceLayerHistory((if (push) screens else screens.dropLast(1)) + target)
    }
}

/** Native lifecycle work around the service's durable hidden/reveal callbacks. */
internal interface PreparedLayerReveal {
    suspend fun prepare()
    fun activate()
}

/** A retained screen resumes after the other layers' publications, on the same run cursor. */
internal class ExperienceLayerEmissionCursor(batch: Long, emission: Long) {
    private val gate = kotlinx.coroutines.sync.Mutex()
    private var nextBatch = batch
    private var nextEmission = emission

    suspend fun publish(
        batch: JourneyScreenEmissionBatch,
        sources: JourneyRuntimeEmissionSources?,
        isCurrent: () -> Boolean,
        accept: suspend (JourneyScreenEmissionBatch, JourneyRuntimeEmissionSources?) -> JourneyEmissionBatchResult,
    ): JourneyEmissionBatchResult {
        gate.lock()
        try {
            // A covered layer's input is dropped without consuming the run cursor.
            if (!isCurrent()) return JourneyEmissionBatchResult.DECLINED
            if (nextBatch == Long.MAX_VALUE || batch.emissions.size.toLong() > Long.MAX_VALUE - nextEmission) {
                return JourneyEmissionBatchResult.REJECTED
            }
            val sequenced = batch.copy(batchSequence = nextBatch,
                emissions = batch.emissions.mapIndexed { index, value -> value.copy(sequence = nextEmission + index) })
            // DECLINED (for example a tap during a pending purchase) keeps the surface and does not advance.
            val result = accept(sequenced, sources)
            if (result != JourneyEmissionBatchResult.ACCEPTED) return result
            nextBatch++
            nextEmission += sequenced.emissions.size
            return result
        } finally { gate.unlock() }
    }
}
