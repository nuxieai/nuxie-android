package ai.nuxie.sdk.presentation

import ai.nuxie.sdk.runtime.NuxieViewModelScalarValue

/** Screen-owned state retained across visits; preparation copies are atomic with UI transitions. */
internal class ExperienceScreenLifecycle {
    enum class Phase(val wireValue: String) { HIDDEN("hidden"), ENTERING("entering"), ACTIVE("active"), EXITING("exiting") }
    enum class ExitReason(val wireValue: String) { NONE("none"), DISMISSED("dismissed"), NAVIGATION("navigation") }
    private var exitReason = ExitReason.NONE
    var phase = Phase.HIDDEN
        private set
    var appearances = 0uL
        private set
    private var transition = ""
    private var reduceMotion = false
    private var fontScale = 1.0

    @Synchronized
    fun move(phase: Phase, transition: String = "", exitReason: ExitReason): Map<String, NuxieViewModelScalarValue> {
        if (phase == Phase.ENTERING) appearances += 1uL
        this.phase = phase
        this.exitReason = if (phase == Phase.EXITING) exitReason else ExitReason.NONE
        this.transition = transition
        return snapshot()
    }

    /** Preparation already counted this appearance; custom entry only adds its transition identifier. */
    @Synchronized
    fun beginPreparedTransition(id: String): Map<String, NuxieViewModelScalarValue> {
        check(phase == Phase.ENTERING) { "Custom entry requires a prepared screen" }
        transition = id
        return snapshot()
    }

    @Synchronized
    fun updateReduceMotion(value: Boolean): Map<String, NuxieViewModelScalarValue> {
        reduceMotion = value
        return snapshot()
    }

    @Synchronized
    fun updateFontScale(value: Float): Map<String, NuxieViewModelScalarValue> {
        fontScale = if (value.isFinite() && value > 0f) value.toDouble() else 1.0
        return snapshot()
    }

    @Synchronized
    fun copyForPreparation(): ExperienceScreenLifecycle = ExperienceScreenLifecycle().also {
        it.appearances = appearances
        it.reduceMotion = reduceMotion
        it.fontScale = fontScale
    }

    /** A kept row supplies its state to the new preparation owner without re-entering. */
    internal fun restoreRetainedState(source: ExperienceScreenLifecycle) {
        if (source === this) return
        val retained = source.copyRetainedState()
        synchronized(this) {
            phase = retained.phase
            appearances = retained.appearances
            transition = retained.transition
            exitReason = retained.exitReason
            reduceMotion = retained.reduceMotion
            fontScale = retained.fontScale
        }
    }

    @Synchronized
    private fun copyRetainedState(): ExperienceScreenLifecycle = copyForPreparation().also {
        it.phase = phase
        it.transition = transition
        it.exitReason = exitReason
    }

    /** Called only after an unrevealed native attempt has fully drained. */
    @Synchronized
    fun resetUnrevealedAttempt() {
        phase = Phase.HIDDEN
        appearances = 0uL
        transition = ""
        exitReason = ExitReason.NONE
    }

    @Synchronized
    fun snapshot(): Map<String, NuxieViewModelScalarValue> = linkedMapOf(
        "screen/phase" to NuxieViewModelScalarValue.StringValue(phase.wireValue),
        "screen/appearances" to NuxieViewModelScalarValue.NumberValue(appearances.toDouble()),
        "screen/exitReason" to NuxieViewModelScalarValue.StringValue(exitReason.wireValue),
        "screen/transition" to NuxieViewModelScalarValue.StringValue(transition),
        "env/reduceMotion" to NuxieViewModelScalarValue.BooleanValue(reduceMotion),
        "fontScale" to NuxieViewModelScalarValue.NumberValue(fontScale),
    )
}
