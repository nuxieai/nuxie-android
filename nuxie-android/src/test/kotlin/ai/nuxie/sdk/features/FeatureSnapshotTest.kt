package ai.nuxie.sdk.features

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class FeatureSnapshotTest {
    @Test
    fun emptyAdmissionAndIdentityResetCarryReadinessWithValues() = runBlocking {
        val info = FeatureInfo()
        val initial = info.snapshot.value
        info.update(emptyMap(), emptyMap(), FeatureInfo.State.Ready)
        val ready = info.snapshot.value
        assertEquals(FeatureInfo.State.Ready, ready.state)
        assertTrue(ready.all.isEmpty())
        assertTrue(ready.revision > initial.revision)
        info.publish(info.stageIdentityChange(emptyMap(), emptyMap(), FeatureInfo.State.Unknown))
        val reset = info.snapshot.value
        assertEquals(FeatureInfo.State.Unknown, reset.state)
        assertTrue(reset.all.isEmpty())
        assertTrue(reset.identityGeneration > ready.identityGeneration)
        assertTrue(reset.revision > ready.revision)
    }

    @Test
    fun stateOnlyReconciliationAndFractionalBalanceRemainVisible() = runBlocking {
        val info = FeatureInfo()
        val values = mapOf("credits" to FeatureAccess(true, false, 1.5, FeatureType.CREDIT_SYSTEM))
        info.update(values, emptyMap(), FeatureInfo.State.Reconciling)
        val projected = info.snapshot.value
        info.update(values, emptyMap(), FeatureInfo.State.Ready)
        assertEquals(1.5, info.snapshot.value.all["credits"]!!.balance!!, 0.0)
        assertEquals(FeatureInfo.State.Ready, info.snapshot.value.state)
        assertTrue(info.snapshot.value.revision > projected.revision)
    }
}
