package ai.nuxie.sdk.presentation

/** Cache/presentation ownership ends only after both independent execution owners retire. */
internal class ExperienceScreenRetirement(private val completion: () -> Unit) {
    private var nativeReleased = false
    private var mediaReleased = false
    private var completed = false

    fun nativeReleased() = update(native = true)
    fun mediaReleased() = update(native = false)

    private fun update(native: Boolean) {
        val finish = synchronized(this) {
            if (native) nativeReleased = true else mediaReleased = true
            if (!completed && nativeReleased && mediaReleased) {
                completed = true
                true
            } else false
        }
        if (finish) completion()
    }
}
