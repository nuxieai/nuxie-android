package ai.nuxie.sdk.presentation

/** Process-local screen state. Never serialized into an Activity Bundle or persisted to disk. */
internal class ExperienceTextInputState private constructor(
    private val committedValues: MutableMap<String, String>,
    private var preparationSource: ExperienceTextInputState?,
) {
    constructor() : this(mutableMapOf(), null)
    data class Value(val text: String, val selectionStart: Int, val selectionEnd: Int)

    private val values = mutableMapOf<String, Value>()
    private var generation = 0L

    @Synchronized
    fun bind(): Session = Session(++generation)

    @Synchronized
    fun detach() {
        generation++
    }

    fun committedValue(inputId: String): String? = synchronized(committedValues) { committedValues[inputId] }

    /** Keep only accepted response values, independently of the current draft. */
    fun recordCommit(inputId: String, text: String) {
        synchronized(committedValues) { committedValues[inputId] = text }
    }

    /** Drafts are speculative; accepted response history belongs to the screen across visits. */
    @Synchronized
    fun copyForPreparation(): ExperienceTextInputState = ExperienceTextInputState(committedValues, this).also {
        it.values.putAll(values)
    }

    /** Refresh only before mounting, after outgoing input has been frozen on the UI thread. */
    fun refreshBeforeMount() {
        val source = preparationSource ?: return
        val latest = synchronized(source) { source.values.toMap() }
        synchronized(this) {
            check(generation == 0L) { "Preparation state is already bound" }
            values.clear()
            values.putAll(latest)
            preparationSource = null
        }
    }

    @Synchronized
    fun resetUnrevealedAttempt() {
        generation++
        values.clear()
        synchronized(committedValues) { committedValues.clear() }
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
