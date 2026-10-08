package ai.nuxie.sdk.presentation

import org.junit.Assert.*
import org.junit.Test

class ExperienceRunMutationGateTest {
    @Test fun `shared writes wait for every submitted frame and retirement releases waiters`() {
        val gate = ExperienceRunMutationGate()
        val writes = mutableListOf<String>()
        val rejected = mutableListOf<String>()
        gate.setPresentationPending("first", true)
        gate.setPresentationPending("second", true)
        gate.submit({ writes += "one" }, { rejected += "one" })
        gate.submit({ writes += "two" }, { rejected += "two" })
        gate.setPresentationPending("first", false)
        assertTrue(writes.isEmpty())
        gate.setPresentationPending("second", false)
        assertEquals(listOf("one", "two"), writes)
        gate.setPresentationPending("first", true)
        gate.submit({ writes += "retired" }, { rejected += "retired" })
        gate.retire()
        gate.setPresentationPending("first", false)
        gate.submit({ writes += "late" }, { rejected += "late" })
        assertEquals(listOf("one", "two"), writes)
        assertEquals(listOf("retired", "late"), rejected)
    }
}
