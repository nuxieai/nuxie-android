package ai.nuxie.example

import android.content.SharedPreferences
import java.util.UUID
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** App-owned marker writes precede provider dispatch and follow its reported outcome. */
internal class ProviderOperationJournal(
  private val preferences: SharedPreferences,
  private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
  private val lock = Any()

  fun hasUnfinished(): Boolean = synchronized(lock) { read().isNotEmpty() }

  suspend fun begin(): String = withContext(dispatcher) {
    synchronized(lock) {
      val entries = read().toMutableMap()
      check(entries.size < 128) { "Too many unfinished provider operations." }
      val id = UUID.randomUUID().toString()
      entries[id] = "active"
      write(entries)
      id
    }
  }

  suspend fun finish(id: String, pendingPayment: Boolean) = withContext(dispatcher) {
    synchronized(lock) {
      val entries = read().toMutableMap()
      check(entries[id] == "active") { "Provider operation marker is missing or already finished." }
      if (pendingPayment) entries[id] = "pending" else entries.remove(id)
      write(entries)
    }
  }

  private fun read(): Map<String, String> {
    val raw = preferences.getString("provider-operations", null) ?: return emptyMap()
    check(raw.length <= 16_384) { "Invalid provider operation journal." }
    val root = JSONObject(raw)
    check(root.keys().asSequence().toSet() == setOf("version", "operations") && root.getInt("version") == 1) {
      "Unsupported provider operation journal."
    }
    val entries = root.getJSONObject("operations")
    check(entries.length() <= 128) { "Invalid provider operation count." }
    return entries.keys().asSequence().associateWith { id ->
      check(id.matches(Regex("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}"))) {
        "Invalid provider operation id."
      }
      entries.getString(id).also { check(it == "active" || it == "pending") { "Invalid provider operation state." } }
    }
  }

  private fun write(entries: Map<String, String>) {
    val editor = preferences.edit()
    if (entries.isEmpty()) editor.remove("provider-operations") else {
      val encoded = JSONObject().put("version", 1).put("operations", JSONObject(entries)).toString()
      editor.putString("provider-operations", encoded)
    }
    check(editor.commit()) { "Could not persist provider operation ownership." }
  }
}
