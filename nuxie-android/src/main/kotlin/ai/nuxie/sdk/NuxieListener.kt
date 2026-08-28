package ai.nuxie.sdk

import ai.nuxie.sdk.features.FeatureAccess

/** Listener for callbacks requested by the Nuxie SDK. */
fun interface NuxieListener {
    /** Called when an Experience's Run App Action step asks the host app to act. */
    fun onAppActionRequested(sdk: Nuxie, action: AppAction)

    /** Called when a committed engine activity is available for analytics forwarding. */
    fun onActivityEmitted(sdk: Nuxie, info: NuxieActivityInfo) = Unit

    /** Called for each changed Feature at the same mutation seam as [Nuxie.features]. */
    fun featureAccessDidChange(
        featureId: String,
        oldAccess: FeatureAccess?,
        newAccess: FeatureAccess,
    ) = Unit
}
