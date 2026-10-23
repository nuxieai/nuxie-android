package ai.nuxie.sdk.events

import ai.nuxie.sdk.fixtures.FixtureRunner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class JourneyRetryOwnershipTest {
    @Test fun onlyTheOrderedQueueArmsJourneyRetryTimers() {
        val sources = FixtureRunner.fixturesRoot().parentFile
            .resolve("nuxie-android/src/main/kotlin/ai/nuxie/sdk")
        val service = sources.resolve("journey/JourneyService.kt").readText()
        val log = sources.resolve("events/EventLog.kt").readText()
        val queue = sources.resolve("events/JourneyWorkQueue.kt").readText()
        for (removed in listOf("nativeRestoreRetryJobs", "nativeRestoreRetries", "deadlineRetryAt",
            "foregroundDeadlines", "journeyResumeRetryPending", "requestJourneyResumeRetry", "availabilityWake")) {
            assertFalse("Removed retry state: $removed", (service + log).contains(removed))
        }
        assertEquals("JourneyService only sleeps for an authored deadline", 1,
            Regex("sleepMillis\\(").findAll(service).count())
        assertFalse("No retry delay may be armed in JourneyService", Regex("scope\\.launch\\s*\\{\\s*delay\\(").containsMatchIn(service))
        assertEquals("One retry timer implementation", 1, Regex("retryJob = scope\\.launch").findAll(queue).count())
        assertTrue(queue.contains("coerceIn(1L, 1_000L)"))
        // Receipt acknowledgement is storage cleanup, independent of Journey work.
        assertEquals(1, Regex("delay\\(1_000L\\)").findAll(log).count())
        val setter = service.substringAfter("private var journal: JourneyRunJournal?").substringBefore("private data class NativeRunValues")
        assertTrue("Replacing a journal clears its scheduling markers", setter.contains("enqueuedDeadlines.clear()"))
        assertTrue("Replacement deadlines have different queue identities", setter.contains("journalGeneration++"))
        assertTrue(service.contains("deadline:\$journalGeneration:"))
        assertTrue(log.contains("key = deadline.queueKey"))
    }
}
