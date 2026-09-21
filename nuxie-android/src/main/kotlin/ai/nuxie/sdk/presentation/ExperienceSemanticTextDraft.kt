package ai.nuxie.sdk.presentation

/** One native editor's draft. Mirrors the iOS admission loop; UI-thread owned. */
internal class ExperienceSemanticTextDraft(text: String) {
    class Write internal constructor(val id: Long, val captureId: Long, val text: String)
    enum class Outcome { ACCEPTED, STALE_CAPTURE, REJECTED }
    enum class EventKind { EDITING_ENDED, RETURN }
    // Do not synthesize toString(): event payloads can contain secure text.
    class Event(val kind: EventKind, val text: String)

    var text = text
        private set
    var acceptedText = text
        private set
    private var notifiedText = text
    private var captureId: Long? = null
    private var inFlight: Write? = null
    private var composing = false
    private var notificationRequested = false
    private val pendingEvents = mutableListOf<EventKind>()
    private var needsReconciliation = false
    private var nextWriteId = 0L

    fun replaceText(value: String, isComposing: Boolean = false) {
        text = value
        composing = isComposing
    }

    fun present(captureId: Long) { this.captureId = captureId }

    fun receiveSource(value: String): Boolean {
        if (composing || inFlight != null || needsReconciliation || notificationRequested || pendingEvents.isNotEmpty() ||
            text != acceptedText || acceptedText != notifiedText) return false
        text = value
        acceptedText = value
        notifiedText = value
        return true
    }

    fun takeWrite(): Write? {
        val capture = captureId ?: return null
        if (inFlight != null || (!needsReconciliation && text == acceptedText)) return null
        return Write(++nextWriteId, capture, text).also { inFlight = it }
    }

    fun requestNotification(): String? {
        notificationRequested = true
        return takeNotification()
    }

    /** Blur and Return are deliberate events even when the accepted value is unchanged. */
    fun requestEvent(kind: EventKind): List<Event> {
        pendingEvents += kind
        return takeReadyEvents()
    }

    fun takeReadyEvents(): List<Event> {
        if (composing || inFlight != null || needsReconciliation || text != acceptedText) return emptyList()
        return pendingEvents.map { Event(it, acceptedText) }.also { pendingEvents.clear() }
    }

    fun finish(write: Write, outcome: Outcome): String? {
        if (inFlight?.id != write.id) return null
        inFlight = null
        when (outcome) {
            Outcome.ACCEPTED -> { acceptedText = write.text; needsReconciliation = false }
            Outcome.STALE_CAPTURE -> if (captureId == write.captureId) captureId = null
            Outcome.REJECTED -> { withdraw(); return null }
        }
        return takeNotification()
    }

    fun withdraw() {
        if (inFlight != null) needsReconciliation = true
        captureId = null
        inFlight = null
        text = acceptedText
        composing = false
        notificationRequested = false
        pendingEvents.clear()
    }

    private fun takeNotification(): String? {
        if (!notificationRequested || composing || inFlight != null || needsReconciliation || text != acceptedText) return null
        notificationRequested = false
        if (acceptedText == notifiedText) return null
        notifiedText = acceptedText
        return acceptedText
    }
}
