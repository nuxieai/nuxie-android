package ai.nuxie.sdk.presentation

import ai.nuxie.sdk.runtime.NuxieViewModelScalarValue

/** Screen-owned state retained across visits within one exact Journey release. UI-thread confined. */
internal class ExperienceScreenLifecycle {
    enum class Phase(val wireValue: String) { HIDDEN("hidden"), ENTERING("entering"), ACTIVE("active"), EXITING("exiting") }
    var phase = Phase.HIDDEN
        private set
    var appearances = 0uL
        private set
    private var transition = ""
    private var reduceMotion = false

    fun move(phase: Phase, transition: String = ""): Map<String, NuxieViewModelScalarValue> {
        if (phase == Phase.ENTERING) appearances += 1uL
        this.phase = phase
        this.transition = transition
        return snapshot()
    }

    /** Preparation already counted this appearance; custom entry only adds its transition identifier. */
    fun beginPreparedTransition(id: String): Map<String, NuxieViewModelScalarValue> {
        check(phase == Phase.ENTERING) { "Custom entry requires a prepared screen" }
        transition = id
        return snapshot()
    }

    fun updateReduceMotion(value: Boolean): Map<String, NuxieViewModelScalarValue> {
        reduceMotion = value
        return snapshot()
    }

    /** Called only after an unrevealed native attempt has fully drained. */
    fun resetUnrevealedAttempt() {
        phase = Phase.HIDDEN
        appearances = 0uL
        transition = ""
    }

    fun snapshot(): Map<String, NuxieViewModelScalarValue> = linkedMapOf(
        "screen/phase" to NuxieViewModelScalarValue.StringValue(phase.wireValue),
        "screen/appearances" to NuxieViewModelScalarValue.NumberValue(appearances.toDouble()),
        "screen/transition" to NuxieViewModelScalarValue.StringValue(transition),
        "env/reduceMotion" to NuxieViewModelScalarValue.BooleanValue(reduceMotion),
    )
}
