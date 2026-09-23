package ai.nuxie.example

import java.net.ServerSocket
import java.net.SocketException
import kotlin.concurrent.thread
import org.json.JSONObject

/** Real loopback HTTP; no internal Feature publication or lifecycle hooks. */
internal class ExampleFeatureServer : AutoCloseable {
  private val socket = ServerSocket(0)
  val url = "http://127.0.0.1:${socket.localPort}"
  @Volatile var balance = 3
  @Volatile var rejectProfiles = false
  private val worker = thread(name = "feature-observation-http") {
    try {
      while (!socket.isClosed) socket.accept().use { connection ->
        connection.soTimeout = 5000
        val input = connection.getInputStream().buffered()
        fun line(): String {
          val bytes = java.io.ByteArrayOutputStream()
          while (true) {
            val value = input.read()
            check(value >= 0) { "Truncated HTTP headers" }
            if (value == 10) return bytes.toString("US-ASCII").trimEnd('\r')
            bytes.write(value)
          }
        }
        val request = line()
        var length = 0
        while (true) {
          val header = line()
          if (header.isEmpty()) break
          if (header.startsWith("Content-Length:", ignoreCase = true)) length = header.substringAfter(':').trim().toInt()
        }
        val requestBytes = ByteArray(length)
        var offset = 0
        while (offset < length) {
          val read = input.read(requestBytes, offset, length - offset)
          check(read > 0)
          offset += read
        }
        val path = request.split(' ')[1]
        val status: Int
        val response: String
        when {
          path == "/profile" && !rejectProfiles -> {
            status = 200
            response = """{"schemaVersion":"nuxie.journey-plane-profile.v2","status":"ok","delivery":{"renderBaseUrl":"https://render.example/","assetBaseUrl":"https://assets.example/"},"features":[{"id":"exports","type":"metered","balance":$balance,"unlimited":false,"nextResetAt":null,"interval":null}],"facts":{"properties":{},"memberships":{},"assignments":{}},"armedLegs":[],"releases":[]}"""
          }
          path == "/entitled" -> {
            status = 200
            response = JSONObject().put("customerId", JSONObject(String(requestBytes, Charsets.UTF_8)).getString("customerId"))
              .put("featureId", "exports").put("requiredBalance", 1).put("code", "allowed")
              .put("type", "metered").put("allowed", true).put("unlimited", false).put("balance", balance).toString()
          }
          else -> { status = 503; response = "{}" }
        }
        val bytes = response.toByteArray()
        connection.getOutputStream().apply {
          write(("HTTP/1.1 $status Test\r\nContent-Type: application/json\r\nContent-Length: ${bytes.size}\r\n" +
            "Nuxie-App-Id: app_golden\r\nNuxie-App-Environment: test\r\nETag: \"feature-$balance\"\r\nConnection: close\r\n\r\n").toByteArray())
          write(bytes)
          flush()
        }
      }
    } catch (closed: SocketException) {
      if (!socket.isClosed) throw closed
    }
  }
  override fun close() {
    socket.close()
    worker.join(6000)
    check(!worker.isAlive) { "HTTP fixture did not stop" }
  }
}
