package ai.nuxie.sdk.journey

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class JourneyStoreTest {
    @Test
    fun persistsAUserScopedRunAcrossStoreRestart() {
        val root = createTempDir(prefix = "nuxie-journey-store-")
        try {
            val run = JourneyRun(
                id = "018fc8e0-7b00-7000-8000-000000000001",
                distinctId = "customer-1",
                experienceId = "experience-1",
                experienceVersion = "version-1",
                epoch = 0,
                plane = JourneyPlane.DEVICE,
                settingsSnapshot = kotlinx.serialization.json.JsonObject(emptyMap()),
                state = JourneyRunState.ACTIVE,
            )

            JourneyStore(root).save(run)

            val restored = JourneyStore(root).loadActive("customer-1")
            assertEquals(listOf(run), restored)
            assertNotNull(JourneyStore(root).load("customer-1", run.id))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun persistsPendingEnrollmentIdentityAcrossStoreRestart() {
        val root = createTempDir(prefix = "nuxie-journey-enrollment-")
        try {
            val run = JourneyRun(
                id = "018fc8e0-7b00-7000-8000-000000000002",
                distinctId = "customer-1",
                experienceId = "experience-1",
                experienceVersion = "version-1",
                epoch = 0,
                plane = JourneyPlane.DEVICE,
                settingsSnapshot = kotlinx.serialization.json.JsonObject(emptyMap()),
                state = JourneyRunState.ENROLLING,
                pendingEnrollmentEventId = "018fc8e0-7b00-7000-8000-000000000003",
                triggerRef = "trigger-1",
            )

            JourneyStore(root).save(run)

            assertEquals(listOf(run), JourneyStore(root).loadPendingEnrollments("customer-1"))
            assertEquals(listOf(run), JourneyStore(root).loadPendingEnrollments())
            assertEquals(emptyList<JourneyRun>(), JourneyStore(root).loadActive("customer-1"))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun completionAccountingIsIdempotentByJourneyId() {
        val root = createTempDir(prefix = "nuxie-journey-completion-")
        try {
            val store = JourneyStore(root)
            store.recordCompletion(
                "customer-1",
                JourneyCompletion("experience-1", "journey-1", 10L),
            )
            store.recordCompletion(
                "customer-1",
                JourneyCompletion("experience-1", "journey-1", 20L),
            )

            assertEquals(1, store.completionCount("customer-1", "experience-1"))
            assertEquals(20L, store.lastCompletionAtMillis("customer-1", "experience-1"))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun pendingHostDismissalsAreDiscoveredAcrossCustomers() {
        val root = createTempDir(prefix = "nuxie-journey-recovery-")
        try {
            val store = JourneyStore(root)
            listOf("customer-1", "customer-2").forEachIndexed { index, distinctId ->
                store.save(
                    JourneyRun(
                        id = "journey-$index",
                        distinctId = distinctId,
                        experienceId = "experience-1",
                        experienceVersion = "version-1",
                        epoch = index.toLong(),
                        plane = JourneyPlane.DEVICE,
                        settingsSnapshot = kotlinx.serialization.json.JsonObject(emptyMap()),
                        state = JourneyRunState.TERMINAL,
                        terminalReason = "dismissed",
                        completedAtMillis = index.toLong(),
                        pendingHostExitCapture = true,
                        pendingHostCompletion = true,
                    ),
                )
            }

            assertEquals(
                setOf("customer-1", "customer-2"),
                store.loadPendingHostDismissals().map(JourneyRun::distinctId).toSet(),
            )
        } finally {
            root.deleteRecursively()
        }
    }
}
