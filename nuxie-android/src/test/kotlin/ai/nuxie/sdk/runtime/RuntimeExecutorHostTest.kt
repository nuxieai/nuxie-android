package ai.nuxie.sdk.runtime

/** Real JNI/runtime contracts selected only by executorContractTest. */
class RuntimeExecutorHostTest : RuntimeExecutorContract() {
    companion object {
        init {
            // Load the prepared runtime explicitly before resolving the JNI
            // adapter, including libraries with a build-relative install name.
            System.load(requireNotNull(System.getProperty("nuxie.executor.capi.lib")))
        }
    }
}
