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
