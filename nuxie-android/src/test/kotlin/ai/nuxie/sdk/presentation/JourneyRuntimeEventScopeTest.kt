package ai.nuxie.sdk.presentation

import ai.nuxie.sdk.runtime.NativeViewModelSnapshot
import ai.nuxie.sdk.runtime.NativeViewModelSnapshotInstance
import ai.nuxie.sdk.runtime.NuxiePlayerStepOutcome
import ai.nuxie.sdk.runtime.NuxieRuntimeEvent
import ai.nuxie.sdk.runtime.NuxieRuntimeEventProperty
import ai.nuxie.sdk.runtime.NuxieRuntimeEventPropertyValue
import ai.nuxie.sdk.runtime.NuxieViewModelSnapshot
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class JourneyRuntimeEventScopeTest {
    @Test
    fun `native event identity becomes only its unique live authenticated alias`() = runTest {
        val batches = mutableListOf<JourneyScreenEmissionBatch>()
        val coordinator = JourneyRuntimeEmissionCoordinator(
            journeyId = "journey", screenId = "screen", descriptor = JsonObject(emptyMap()),
            nextBatchSequence = 0, nextEmissionSequence = 0,
            onEmissionBatch = { batches += it; true }, onPresentationRevealed = {},
        )
        assertTrue(coordinator.reveal())
        fun snapshot(aliases: Map<String, Long>, childPresent: Boolean = true) =
            NuxieViewModelSnapshot.fromNative(NativeViewModelSnapshot(1,
                (listOf(NativeViewModelSnapshotInstance(1, 0)) +
                    if (childPresent) listOf(NativeViewModelSnapshotInstance(2, 1)) else emptyList()).toTypedArray(),
                emptyArray()), instanceIds = aliases, defaultInstanceId = "root")
        val event = NuxieRuntimeEvent(0, 0, "purchase_requested", "", "", 0f, emptyList(), 2)
        fun outcome(event: NuxieRuntimeEvent) = NuxiePlayerStepOutcome(
            false, emptyList(), listOf(event), emptyList(), emptyList())
        assertTrue(coordinator.publish(outcome(event), 1uL, snapshot = snapshot(mapOf("plan.first" to 2))))
        assertEquals("plan.first", batches.single().source.instanceId)
        val invalid = listOf(
            snapshot(emptyMap()),
            snapshot(mapOf("plan.first" to 2, "plan.second" to 2)),
            snapshot(mapOf("plan.first" to 2), childPresent = false),
        )
        for (frame in invalid) {
            assertTrue(runCatching { coordinator.publish(outcome(event), 2uL, snapshot = frame) }.isFailure)
        }
        assertTrue(runCatching { coordinator.publish(outcome(event), 3uL) }.isFailure)
        val conflicting = event.copy(properties = listOf(NuxieRuntimeEventProperty("instanceId",
            NuxieRuntimeEventPropertyValue.Bytes("plan.second".encodeToByteArray()))))
        assertTrue(runCatching { coordinator.publish(outcome(conflicting), 4uL,
            snapshot = snapshot(mapOf("plan.first" to 2))) }.isFailure)
        assertEquals("Rejected sources cannot publish a fallback purchase", 1, batches.size)
    }
}
