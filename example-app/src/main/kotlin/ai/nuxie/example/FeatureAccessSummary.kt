package ai.nuxie.example

import ai.nuxie.sdk.features.FeatureInfo

/** Read access and readiness from one publication, including after identity changes. */
internal fun featureAccessSummary(featureId: String, snapshot: FeatureInfo.Snapshot): String =
  when (snapshot.state) {
    FeatureInfo.State.Unknown -> "Waiting for $featureId access for the current customer."
    FeatureInfo.State.Reconciling -> "Reconciling $featureId access; server confirmation is pending."
    FeatureInfo.State.Ready -> {
      val access = snapshot.all[featureId]
      when {
        access == null || !access.allowed -> "Current access: $featureId is unavailable."
        access.unlimited -> "Current access: $featureId is unlimited."
        access.balance != null -> "Current access: $featureId is allowed. Balance: ${access.balance}."
        else -> "Current access: $featureId is allowed."
      }
    }
  }
