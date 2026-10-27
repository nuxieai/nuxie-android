package ai.nuxie.sdk.presentation

/** History belongs to a native page. Repeated visits keep separate forward references. */
internal data class ExperiencePageHistory(val screens: List<String>,
    val transitions: List<kotlinx.serialization.json.JsonObject?> = List(screens.size) { null }) {
    init { require(screens.isNotEmpty()) }

    fun backTarget(steps: Int): String = screens[(screens.lastIndex - steps.coerceAtLeast(1)).coerceAtLeast(0)]

    fun navigate(target: String, push: Boolean): ExperiencePageHistory {
        // E47 provisional decision: reuse an earlier screen and preserve its state.
        val existing = screens.indexOf(target)
        if (existing >= 0) return ExperiencePageHistory(screens.take(existing + 1))
        return ExperiencePageHistory((if (push) screens else screens.dropLast(1)) + target)
    }

    fun visit(target: String, transition: kotlinx.serialization.json.JsonObject?): ExperiencePageHistory {
        return ExperiencePageHistory(screens + target, transitions + transition)
    }

    fun popping(steps: Int): ExperiencePageHistory {
        val count = (screens.size - steps.coerceAtLeast(1)).coerceAtLeast(1)
        return ExperiencePageHistory(screens.take(count), transitions.take(count))
    }

    fun backTransition(steps: Int, target: String, descriptor: kotlinx.serialization.json.JsonObject): kotlinx.serialization.json.JsonObject? =
        backTransition(steps, target) {
            ai.nuxie.sdk.experiences.ExperiencePageHostContract.transitions(descriptor["render"] as kotlinx.serialization.json.JsonObject)
        }

    fun backTransition(steps: Int, target: String,
        declarations: () -> List<ai.nuxie.sdk.experiences.ExperiencePageHostContract.Transition>): kotlinx.serialization.json.JsonObject? {
        // Steps past the page's first Screen are not taken: a Back that goes one Screen reverses it (B2).
        if (minOf(steps, screens.size - 1) <= 1) return transitions.last()
        val pair = declarations().firstOrNull {
            it.isDefaultPair && it.sourceScreenId == screens.last() && it.destinationScreenId == target
        } ?: return null
        return kotlinx.serialization.json.buildJsonObject {
            put("type", kotlinx.serialization.json.JsonPrimitive("magic-move"))
            put("transitionId", kotlinx.serialization.json.JsonPrimitive(pair.id))
        }
    }
}

/** A page Back across every page's history, the top page last (B1 to B4, P7). */
internal data class ExperiencePageBack(
    /** The page it lands on, counted from the bottom. */
    val page: Int,
    val target: String,
    /** The landing page's history after the Back. */
    val history: ExperiencePageHistory,
    /** The pages above the landing page, which close. */
    val pagesToClose: Int,
) {
    companion object {
        /** The steps-th Screen before the current one, counted across pages and clamped to the first. */
        fun of(steps: Int, histories: List<ExperiencePageHistory>): ExperiencePageBack {
            val locations = histories.flatMapIndexed { page, history -> history.screens.indices.map { page to it } }
            val (page, entry) = locations[maxOf(0, locations.lastIndex - maxOf(1, steps))]
            val history = histories[page]
            val popped = history.screens.lastIndex - entry
            return ExperiencePageBack(page, history.screens[entry],
                if (popped == 0) history else history.popping(popped), histories.lastIndex - page)
        }
    }
}

/** Native lifecycle work around the service's durable hidden/reveal callbacks. */
internal interface PreparedLayerReveal {
    val method: String get() = "native_sheet"
    val pagesToClose: Int get() = 1
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
