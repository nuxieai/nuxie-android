package ai.nuxie.example

import android.content.Context
import android.content.SharedPreferences
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.*
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class LogoutJournalTest {
  private val session = "a".repeat(64)

  @Test fun eachStageSurvivesReaderRecreationAndCannotRegressOrChangeSession() {
    val prefs = RuntimeEnvironment.getApplication().getSharedPreferences("logout-test", Context.MODE_PRIVATE)
    assertNull(LogoutJournal(prefs).read())
    for (stage in LogoutJournal.Stage.entries) {
      val record = LogoutJournal.Record(session, stage)
      LogoutJournal(prefs).write(record)
      assertEquals(record, LogoutJournal(prefs).read())
    }
    assertThrows(IllegalStateException::class.java) { LogoutJournal(prefs).write(LogoutJournal.Record(session, LogoutJournal.Stage.REQUESTED)) }
    assertThrows(IllegalStateException::class.java) { LogoutJournal(prefs).write(LogoutJournal.Record("b".repeat(64), LogoutJournal.Stage.COMPLETE)) }
  }

  @Test fun malformedOrUnknownRecordsAreNeverTreatedAsMissing() {
    val prefs = RuntimeEnvironment.getApplication().getSharedPreferences("logout-invalid", Context.MODE_PRIVATE)
    for (raw in listOf("broken", "{}", """{"version":2,"session":"$session","stage":"COMPLETE"}""",
      """{"version":1,"session":"$session","stage":"UNKNOWN"}""")) {
      assertTrue(prefs.edit().putString("logout", raw).commit())
      assertThrows(Exception::class.java) { LogoutJournal(prefs).read() }
    }
  }

  @Test fun explicitNextSessionSurvivesRestartAndRejectsOldOrDifferentLaunches() {
    val prefs = RuntimeEnvironment.getApplication().getSharedPreferences("next-session", Context.MODE_PRIVATE)
    val journal = LogoutJournal(prefs)
    val next = "b".repeat(64)
    journal.write(LogoutJournal.Record(session, LogoutJournal.Stage.REQUESTED))
    assertThrows(IllegalStateException::class.java) { journal.beginSession(next) }
    journal.write(LogoutJournal.Record(session, LogoutJournal.Stage.COMPLETE))
    assertFalse(journal.admits(next))
    journal.beginSession(next)
    val restarted = LogoutJournal(prefs)
    assertEquals(next, restarted.pendingSession())
    assertFalse(restarted.admits(next))
    assertFalse(restarted.admits(session))
    assertThrows(IllegalStateException::class.java) { restarted.beginSession(session) }
    assertThrows(IllegalStateException::class.java) { restarted.completeSession(session) }
    restarted.beginSession(next)
    restarted.completeSession(next)
    restarted.completeSession(next) // Re-persist an uncertain completion without changing identity.
    val completed = LogoutJournal(prefs)
    assertNull(completed.read())
    assertNull(completed.pendingSession())
    assertTrue(completed.admits(next))
    assertFalse(completed.admits(session))
    completed.write(LogoutJournal.Record(next, LogoutJournal.Stage.REQUESTED))
    assertFalse(completed.admits(next))
  }

  @Test fun failedCommitIsAnError() {
    val prefs = mock(SharedPreferences::class.java)
    val editor = mock(SharedPreferences.Editor::class.java, RETURNS_SELF)
    `when`(prefs.edit()).thenReturn(editor)
    `when`(editor.commit()).thenReturn(false)
    assertThrows(IllegalStateException::class.java) {
      LogoutJournal(prefs).write(LogoutJournal.Record(session, LogoutJournal.Stage.REQUESTED))
    }
  }
}
