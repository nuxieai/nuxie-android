package ai.nuxie.sdk.journey

import ai.nuxie.sdk.experiences.JourneyPlaneProfile
import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [23])
class JourneyResponseSaveTest {
    private lateinit var directory: File
    @Before fun setup() { directory = File(RuntimeEnvironment.getApplication().filesDir, "response-saves").apply { mkdirs() } }
    @After fun cleanup() { directory.deleteRecursively() }

    @Test fun `sheets outlive run and restart with independent durable sequences`() {
        val journal = JourneyRunJournal(directory, "anon")
        val run = responseSaveRun(journal)
        for (sequence in 1L..3) {
            val sheet = journal.reserveResponseSave(run, "feedback", json("""{"stars":$sequence}"""), true)
            assertEquals(sequence, sheet.sequence)
        }
        assertEquals(1L, journal.reserveResponseSave(run, "onboarding", json("{}"), true).sequence)
        journal.markStartedQueued(run)
        journal.complete(run.id, "done", 2000)
        journal.markCompletionQueued(run)
        val reopened = JourneyRunJournal(directory, "anon")
        assertTrue(reopened.runs().isEmpty())
        val waiting = reopened.reserveResponseSave(run, "feedback", json("{}"), false)
        assertEquals(4L, waiting.sequence)
        assertEquals(listOf(3L, 1L), reopened.pendingResponseSaves().map { it.sequence })
        reopened.confirmResponseSave(waiting, 7)
        assertEquals(listOf("onboarding"), reopened.pendingResponseSaves().map { it.formName })
        val newer = reopened.reserveResponseSave(run, "feedback", json("{}"), true)
        assertEquals(8L, newer.sequence)
        reopened.confirmResponseSave(waiting, 9)
        assertTrue(newer in reopened.pendingResponseSaves())
        reopened.confirmResponseSave(newer, JourneyResponseSave.MAXIMUM_SEQUENCE - 1)
        assertEquals(JourneyResponseSave.MAXIMUM_SEQUENCE, reopened.reserveResponseSave(run, "feedback", json("{}"), false).sequence)
        assertThrows(IllegalStateException::class.java) { reopened.reserveResponseSave(run, "feedback", json("{}"), true) }
    }

    @Test fun `wrong owner and failed journal write never consume or replace a sequence`() {
        val journal = JourneyRunJournal(directory, "anon")
        val run = responseSaveRun(journal)
        val answers = json("""{"é":1,"e\u0301":2,"__proto__":"kept","empty":"","invalid_email":"x","null":null}""")
        val first = journal.reserveResponseSave(run, "feedback", answers, true)
        val other = JourneyRunJournal(directory, "signed-in")
        assertThrows(IllegalStateException::class.java) { other.reserveResponseSave(run, "feedback", answers, true) }
        val journals = File(directory, "journey-state-v2/journals")
        val backup = File(directory, "retained-journals")
        assertTrue(journals.renameTo(backup))
        journals.writeText("blocks directory creation")
        assertThrows(Exception::class.java) { journal.reserveResponseSave(run, "feedback", json("{}"), true) }
        assertTrue(journals.delete())
        assertTrue(backup.renameTo(journals))
        assertEquals(listOf(first), journal.pendingResponseSaves())
        assertEquals(6, first.answers.size)
        assertEquals(2L, journal.reserveResponseSave(run, "feedback", answers, true).sequence)
    }
}

internal fun json(value: String): JsonObject = Json.parseToJsonElement(value).jsonObject
internal fun responseSaveRun(journal: JourneyRunJournal): JourneyRun = requireNotNull(journal.admit(
    JourneyPlaneProfile.Arm(
        reference = json("""{"experienceId":"experience-1","versionId":"version-1","legId":"${"a".repeat(64)}","descriptorSha256":"${"b".repeat(64)}"}"""),
        binding = json("""{"type":"continue","journeyId":"01900000-0000-7000-8000-000000000001","generation":1}"""),
        entryCondition = json("""{"type":"app_foregrounded"}"""),
        context = json("""{"event":{},"responses":{}}"""),
    ), JourneyFrequency.EveryMatch, "screen", 1000,
))
