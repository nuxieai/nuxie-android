package ai.nuxie.example

import android.content.SharedPreferences
import java.util.UUID
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** Durable provider ownership; legacy markers remain unknown rather than acquiring guessed identity. */
internal class ProviderOperationJournal(
  private val preferences: SharedPreferences,
  private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
  enum class Kind { PURCHASE, RESTORE }
  enum class Recovery { NONE, LEGACY, OTHER_SESSION, PURCHASE, RESTORE }
  data class Operation(val id: String, val state: String, val session: String?, val kind: Kind?, val productId: String?)
  private val lock = Any()

  fun hasUnfinished(): Boolean = synchronized(lock) { read().isNotEmpty() }
  fun snapshot(): List<Operation> = synchronized(lock) { read().values.toList() }

  fun recoveryFor(session: String): Recovery = synchronized(lock) {
    val entries = read().values
    when {
      entries.isEmpty() -> Recovery.NONE
      entries.any { it.session == null } -> Recovery.LEGACY
      entries.any { it.session != session } -> Recovery.OTHER_SESSION
      entries.any { it.kind == Kind.PURCHASE } -> Recovery.PURCHASE
      else -> Recovery.RESTORE
    }
  }

  suspend fun begin(session: String, kind: Kind, productId: String? = null): String = withContext(dispatcher) {
    synchronized(lock) {
      val entries = read().toMutableMap()
      check(entries.values.all { it.session == session }) { "Unfinished operations belong to another or unknown session." }
      check(entries.size < 128) { "Too many unfinished provider operations." }
      val id = UUID.randomUUID().toString()
      val operation = Operation(id, "active", session, kind, productId)
      validate(operation)
      entries[id] = operation
      write(entries)
      id
    }
  }

  suspend fun finish(id: String, session: String, pendingPayment: Boolean) = withContext(dispatcher) {
    synchronized(lock) {
      val entries = read().toMutableMap()
      val operation = checkNotNull(entries[id]) { "Provider operation marker is missing." }
      check(operation.session == session) { "Provider operation belongs to another or unknown session." }
      check(operation.state == "active") { "Provider operation is already finished." }
      check(!pendingPayment || operation.kind == Kind.PURCHASE) { "Only a purchase can remain pending." }
      if (pendingPayment) entries[id] = operation.copy(state = "pending") else entries.remove(id)
      write(entries)
    }
  }

  private fun read(): Map<String, Operation> {
    val raw = preferences.getString("provider-operations", null) ?: return emptyMap()
    check(raw.length <= 131_072) { "Invalid provider operation journal." }
    val root = JSONObject(raw)
    val version = root.get("version")
    check(root.keys().asSequence().toSet() == setOf("version", "operations") && (version == 1 || version == 2)) {
      "Unsupported provider operation journal."
    }
    val entries = root.getJSONObject("operations")
    check(entries.length() <= 128) { "Invalid provider operation count." }
    return entries.keys().asSequence().associateWith { id ->
      val operation = if (version == 1) {
        Operation(id, entries.get(id) as? String ?: error("Invalid legacy operation."), null, null, null)
      } else {
        val value = entries.getJSONObject(id)
        check(value.keys().asSequence().toSet() == setOf("state", "session", "kind", "productId")) {
          "Invalid provider operation fields."
        }
        fun string(key: String): String? = value.get(key).let {
          if (it === JSONObject.NULL) null else it as? String ?: error("Invalid operation value.")
        }
        Operation(id, checkNotNull(string("state")), string("session"), string("kind")?.let(Kind::valueOf), string("productId"))
      }
      validate(operation)
      operation
    }
  }

  private fun validate(operation: Operation) {
    check(operation.id.matches(Regex("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}"))) {
      "Invalid provider operation id."
    }
    check(operation.state == "active" || operation.state == "pending") { "Invalid provider operation state." }
    if (operation.session == null) {
      check(operation.kind == null && operation.productId == null) { "Legacy operation cannot acquire partial ownership." }
      return
    }
    check(operation.session.matches(Regex("[a-f0-9]{64}"))) { "Invalid provider session." }
    when (operation.kind) {
      Kind.PURCHASE -> check(!operation.productId.isNullOrBlank() && operation.productId.length <= 512) { "Invalid product identity." }
      Kind.RESTORE -> check(operation.productId == null && operation.state == "active") { "Invalid restore operation." }
      null -> error("Provider operation kind is missing.")
    }
  }

  private fun write(entries: Map<String, Operation>) {
    val editor = preferences.edit()
    if (entries.isEmpty()) editor.remove("provider-operations") else {
      val operations = JSONObject()
      entries.forEach { (id, operation) ->
        validate(operation)
        operations.put(id, JSONObject().put("state", operation.state)
          .put("session", operation.session ?: JSONObject.NULL).put("kind", operation.kind?.name ?: JSONObject.NULL)
          .put("productId", operation.productId ?: JSONObject.NULL))
      }
      val encoded = JSONObject().put("version", 2).put("operations", operations).toString()
      check(encoded.length <= 131_072) { "Provider operation journal is too large." }
      editor.putString("provider-operations", encoded)
    }
    check(editor.commit()) { "Could not persist provider operation ownership." }
  }
}
