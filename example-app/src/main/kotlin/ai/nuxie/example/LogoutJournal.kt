package ai.nuxie.example

import android.content.SharedPreferences
import org.json.JSONObject

/** One durable logout record. Invalid records block startup rather than disappearing. */
internal class LogoutJournal(private val preferences: SharedPreferences) {
  enum class Stage { REQUESTED, DRAINED, SDK_RESET, SDK_RETIRED, COMPLETE }
  data class Record(val session: String, val stage: Stage)

  @Synchronized fun read(): Record? {
    val raw = preferences.getString("logout", null) ?: return null
    val value = JSONObject(raw)
    check(value.keys().asSequence().toSet() == setOf("version", "session", "stage")) { "Invalid logout record." }
    check(value.getInt("version") == 1) { "Unsupported logout record." }
    val session = value.getString("session")
    check(session.matches(Regex("[a-f0-9]{64}"))) { "Invalid logout session." }
    return Record(session, Stage.valueOf(value.getString("stage")))
  }

  @Synchronized fun pendingSession(): String? = sessionValue("pending-session")

  @Synchronized fun admits(session: String): Boolean {
    val active = sessionValue("active-session")
    return read() == null && pendingSession() == null && (active == null || active == session)
  }

  @Synchronized fun beginSession(session: String) {
    require(session.matches(Regex("[a-f0-9]{64}"))) { "Invalid next session." }
    check(read()?.stage == Stage.COMPLETE) { "Previous logout is not complete." }
    check(pendingSession().let { it == null || it == session }) { "Another sign-in needs recovery." }
    check(preferences.edit().putString("pending-session", session).commit()) { "Could not persist sign-in intent." }
  }

  @Synchronized fun completeSession(session: String) {
    val pending = read()?.stage == Stage.COMPLETE && pendingSession() == session
    val completed = read() == null && pendingSession() == null && sessionValue("active-session") == session
    check(pending || completed) { "Sign-in intent changed." }
    check(preferences.edit().putString("active-session", session)
      .remove("pending-session").remove("logout").commit()) { "Could not persist sign-in completion." }
  }

  private fun sessionValue(key: String): String? = preferences.getString(key, null)?.also {
    check(it.matches(Regex("[a-f0-9]{64}"))) { "Invalid session record." }
  }

  @Synchronized fun write(record: Record) {
    val current = read()
    check(current == null || (current.session == record.session && current.stage.ordinal <= record.stage.ordinal)) {
      "Logout progress cannot change session or move backward."
    }
    val encoded = JSONObject().put("version", 1).put("session", record.session).put("stage", record.stage.name).toString()
    check(preferences.edit().putString("logout", encoded).commit()) { "Could not persist logout progress." }
  }
}
