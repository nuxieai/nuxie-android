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

  @Synchronized fun write(record: Record) {
    val current = read()
    check(current == null || (current.session == record.session && current.stage.ordinal <= record.stage.ordinal)) {
      "Logout progress cannot change session or move backward."
    }
    val encoded = JSONObject().put("version", 1).put("session", record.session).put("stage", record.stage.name).toString()
    check(preferences.edit().putString("logout", encoded).commit()) { "Could not persist logout progress." }
  }
}
