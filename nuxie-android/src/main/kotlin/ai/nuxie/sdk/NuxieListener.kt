package ai.nuxie.sdk

/** Listener for callbacks requested by the Nuxie SDK. */
fun interface NuxieListener {
    /** Called when an Experience's Run App Action step asks the host app to act. */
    fun onAppActionRequested(sdk: Nuxie, action: AppAction)
}
