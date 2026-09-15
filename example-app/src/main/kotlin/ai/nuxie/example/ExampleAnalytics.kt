package ai.nuxie.example

import ai.nuxie.sdk.NuxieActivityInfo

/** Local-only stand-in for an analytics provider. No network or payload logging. */
internal object ExampleAnalytics {
  private val events = linkedMapOf<String, String>()

  @Synchronized
  fun record(info: NuxieActivityInfo) {
    // Keep the stable SDK activity ID when forwarding to a real provider.
    events[info.id] = info.name
    if (events.size > 20) events.remove(events.keys.first())
  }
}
