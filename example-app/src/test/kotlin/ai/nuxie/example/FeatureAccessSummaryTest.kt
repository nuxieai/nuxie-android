package ai.nuxie.example

import ai.nuxie.sdk.features.FeatureAccess
import ai.nuxie.sdk.features.FeatureInfo
import ai.nuxie.sdk.features.FeatureType
import org.junit.Assert.*
import org.junit.Test

class FeatureAccessSummaryTest {
  private val allowed = FeatureAccess(true, false, 3.0, FeatureType.METERED)
  private fun snapshot(state: FeatureInfo.State, access: FeatureAccess? = allowed) =
    FeatureInfo.Snapshot(access?.let { mapOf("exports" to it) }.orEmpty(), state, 1, 1)

  @Test fun provisionalAccessIsNotPresentedAsServerConfirmed() {
    assertEquals("Waiting for exports access for the current customer.",
      featureAccessSummary("exports", snapshot(FeatureInfo.State.Unknown)))
    assertEquals("Reconciling exports access; server confirmation is pending.",
      featureAccessSummary("exports", snapshot(FeatureInfo.State.Reconciling)))
  }

  @Test fun readyAccessShowsDenialBalanceAndUnlimitedWithoutConflatingThem() {
    assertEquals("Current access: exports is unavailable.",
      featureAccessSummary("exports", snapshot(FeatureInfo.State.Ready, null)))
    assertEquals("Current access: exports is unavailable.",
      featureAccessSummary("exports", snapshot(FeatureInfo.State.Ready, allowed.copy(allowed = false))))
    assertEquals("Current access: exports is allowed. Balance: 3.0.",
      featureAccessSummary("exports", snapshot(FeatureInfo.State.Ready)))
    assertEquals("Current access: exports is unlimited.",
      featureAccessSummary("exports", snapshot(FeatureInfo.State.Ready, allowed.copy(unlimited = true))))
  }

}
