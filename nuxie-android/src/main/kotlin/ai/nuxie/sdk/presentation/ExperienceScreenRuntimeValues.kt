package ai.nuxie.sdk.presentation

import ai.nuxie.sdk.runtime.NuxieViewModelScalarValue
import kotlinx.coroutines.CancellationException

/** One mounted scene's optional lifecycle capability, resolved from its bound schema. */
internal class ExperienceScreenRuntimeValues(private val supportsExitReason: Boolean) {
    fun apply(
        values: Map<String, NuxieViewModelScalarValue>,
        write: (String, NuxieViewModelScalarValue) -> Unit,
        onRejected: (String, Exception) -> Unit,
    ) {
        for ((path, value) in values) {
            if (path == "screen/exitReason" && !supportsExitReason) continue
            try {
                write(path, value)
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                // Older optional environment fields must not block valid sibling writes.
                onRejected(path, error)
            }
        }
    }
}
