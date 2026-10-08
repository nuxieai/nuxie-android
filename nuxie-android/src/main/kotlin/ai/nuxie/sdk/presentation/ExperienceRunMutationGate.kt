package ai.nuxie.sdk.presentation

/** Confined to the shared native lane. Submitted frames own their model revision until retired. */
internal class ExperienceRunMutationGate {
    private data class Write(val apply: () -> Unit, val reject: () -> Unit)
    private val pending = mutableSetOf<String>()
    private val writes = ArrayDeque<Write>()
    private var retired = false

    fun setPresentationPending(owner: String, value: Boolean) {
        if (retired) return
        if (value) pending += owner else pending -= owner
        drain()
    }

    fun submit(apply: () -> Unit, reject: () -> Unit) {
        if (retired) { reject(); return }
        writes.addLast(Write(apply, reject))
        drain()
    }

    fun retire() {
        retired = true
        pending.clear()
        while (writes.isNotEmpty()) writes.removeFirst().reject()
    }

    private fun drain() {
        while (!retired && pending.isEmpty() && writes.isNotEmpty()) writes.removeFirst().apply()
    }
}
