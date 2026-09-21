package ai.nuxie.sdk.presentation

import org.junit.Assert.*
import org.junit.Test

class ExperienceSemanticTextDraftTest {
    @Test fun `unchanged text still emits deliberate lifecycle events without another write`() {
        val draft = ExperienceSemanticTextDraft("same")
        draft.present(1)
        assertNull(draft.takeWrite())
        assertNull(draft.requestNotification())
        val event = draft.requestEvent(ExperienceSemanticTextDraft.EventKind.RETURN).single()
        assertEquals(ExperienceSemanticTextDraft.EventKind.RETURN, event.kind)
        assertEquals("same", event.text)
        assertTrue(draft.takeReadyEvents().isEmpty())
    }

    @Test fun `lifecycle events wait for the latest admitted edit and preserve order`() {
        val draft = ExperienceSemanticTextDraft("old")
        draft.present(1)
        draft.replaceText("first")
        val first = draft.takeWrite()!!
        draft.replaceText("latest")
        assertTrue(draft.requestEvent(ExperienceSemanticTextDraft.EventKind.RETURN).isEmpty())
        assertTrue(draft.requestEvent(ExperienceSemanticTextDraft.EventKind.EDITING_ENDED).isEmpty())
        draft.finish(first, ExperienceSemanticTextDraft.Outcome.ACCEPTED)
        assertTrue(draft.takeReadyEvents().isEmpty())
        draft.present(2)
        draft.finish(draft.takeWrite()!!, ExperienceSemanticTextDraft.Outcome.ACCEPTED)
        val events = draft.takeReadyEvents()
        assertEquals(listOf(ExperienceSemanticTextDraft.EventKind.RETURN,
            ExperienceSemanticTextDraft.EventKind.EDITING_ENDED), events.map { it.kind })
        assertEquals(listOf("latest", "latest"), events.map { it.text })
        assertTrue(draft.takeReadyEvents().isEmpty())
    }

    @Test fun `rejected edit drops pending lifecycle events and later valid edit recovers`() {
        val draft = ExperienceSemanticTextDraft("valid")
        draft.present(1)
        draft.replaceText("invalid")
        val write = draft.takeWrite()!!
        draft.requestEvent(ExperienceSemanticTextDraft.EventKind.RETURN)
        draft.finish(write, ExperienceSemanticTextDraft.Outcome.REJECTED)
        assertTrue(draft.takeReadyEvents().isEmpty())
        draft.present(2)
        draft.replaceText("recovered")
        val retry = draft.takeWrite()!!
        draft.requestNotification()
        assertEquals("recovered", draft.finish(retry, ExperienceSemanticTextDraft.Outcome.ACCEPTED))
        assertEquals("recovered", draft.requestEvent(ExperienceSemanticTextDraft.EventKind.RETURN).single().text)
    }

    @Test fun `composition delays lifecycle events and blocks source replacement`() {
        val draft = ExperienceSemanticTextDraft("é")
        draft.present(1)
        draft.replaceText("é", isComposing = true)
        assertTrue(draft.requestEvent(ExperienceSemanticTextDraft.EventKind.EDITING_ENDED).isEmpty())
        assertFalse(draft.receiveSource("remote"))
        draft.replaceText("é")
        assertFalse(draft.receiveSource("remote"))
        assertEquals("é", draft.takeReadyEvents().single().text)
        assertTrue(draft.receiveSource("remote"))
    }

    @Test fun `new native editor reads source without writing a placeholder`() {
        val draft = ExperienceSemanticTextDraft("")
        draft.present(1)
        assertTrue(draft.receiveSource("source"))
        assertNull(draft.takeWrite())
        assertNull(draft.requestNotification())
    }

    @Test fun `rapid edits serialize and notify only the admitted latest draft`() {
        val draft = ExperienceSemanticTextDraft("old")
        draft.present(1)
        draft.replaceText("first")
        val first = draft.takeWrite()!!
        draft.replaceText("second")
        assertNull(draft.takeWrite())
        assertNull(draft.requestNotification())
        assertNull(draft.finish(first, ExperienceSemanticTextDraft.Outcome.ACCEPTED))
        draft.present(2)
        val second = draft.takeWrite()!!
        assertEquals("second", second.text)
        assertEquals("second", draft.finish(second, ExperienceSemanticTextDraft.Outcome.ACCEPTED))
        assertNull(draft.requestNotification())
    }

    @Test fun `stale capture retries current draft on the next presentation`() {
        val draft = ExperienceSemanticTextDraft("old")
        draft.present(1)
        draft.replaceText("new")
        val write = draft.takeWrite()!!
        draft.finish(write, ExperienceSemanticTextDraft.Outcome.STALE_CAPTURE)
        assertNull(draft.takeWrite())
        draft.present(2)
        val retry = draft.takeWrite()!!
        assertEquals(2L, retry.captureId)
        assertEquals("new", retry.text)
    }

    @Test fun `composition protects provisional text and delays notification`() {
        val draft = ExperienceSemanticTextDraft("old")
        draft.present(1)
        draft.replaceText("é", isComposing = true)
        assertFalse(draft.receiveSource("remote"))
        val write = draft.takeWrite()!!
        assertNull(draft.requestNotification())
        assertNull(draft.finish(write, ExperienceSemanticTextDraft.Outcome.ACCEPTED))
        draft.replaceText("é")
        assertEquals("é", draft.requestNotification())
    }

    @Test fun `withdrawal fences late completion and reconciles a possibly completed native write`() {
        val draft = ExperienceSemanticTextDraft("old")
        draft.present(1)
        draft.replaceText("new")
        val write = draft.takeWrite()!!
        draft.withdraw()
        assertNull(draft.finish(write, ExperienceSemanticTextDraft.Outcome.ACCEPTED))
        assertEquals("old", draft.text)
        draft.present(2)
        assertFalse(draft.receiveSource("new"))
        assertEquals("old", draft.takeWrite()!!.text)
    }

    @Test fun `rejected edits restore accepted text and do not emit a response`() {
        val draft = ExperienceSemanticTextDraft("valid")
        draft.present(1)
        draft.replaceText("invalid")
        val write = draft.takeWrite()!!
        draft.requestNotification()
        assertNull(draft.finish(write, ExperienceSemanticTextDraft.Outcome.REJECTED))
        assertEquals("valid", draft.text)
        assertNull(draft.takeWrite())
        draft.present(2)
        assertTrue(draft.receiveSource("valid"))
    }
}
