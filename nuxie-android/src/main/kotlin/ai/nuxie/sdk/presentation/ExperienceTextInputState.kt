package ai.nuxie.sdk.presentation

/** Process-local screen state. Never serialized into an Activity Bundle or persisted to disk. */
internal class ExperienceTextInputState {
    data class Value(val text: String, val selectionStart: Int, val selectionEnd: Int)

    private val values = mutableMapOf<String, Value>()
    private val committedValues = mutableMapOf<String, String>()
    private var generation = 0L

    @Synchronized
    fun bind(): Session = Session(++generation)

    @Synchronized
    fun detach() {
        generation++
    }

    @Synchronized
    fun committedValue(inputId: String): String? = committedValues[inputId]

    /** Keep only accepted response values, independently of the current draft. */
    @Synchronized
    fun recordCommit(inputId: String, text: String) {
        committedValues[inputId] = text
    }

    @Synchronized
    fun copyForPreparation(): ExperienceTextInputState = ExperienceTextInputState().also {
        it.values.putAll(values)
        it.committedValues.putAll(committedValues)
    }

    @Synchronized
    fun resetUnrevealedAttempt() {
        generation++
        values.clear()
        committedValues.clear()
    }

    inner class Session internal constructor(private val owner: Long) {
        fun isCurrent(): Boolean = synchronized(this@ExperienceTextInputState) { owner == generation }

        fun read(inputId: String): Value? = synchronized(this@ExperienceTextInputState) {
            values[inputId]
        }

        /** A superseded Activity cannot overwrite the newly attached editor. */
        fun write(inputId: String, value: Value): Boolean = synchronized(this@ExperienceTextInputState) {
            if (owner != generation) return@synchronized false
            values[inputId] = value
            true
        }
    }
}
