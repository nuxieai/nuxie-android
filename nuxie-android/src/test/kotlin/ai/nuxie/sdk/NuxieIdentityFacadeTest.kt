package ai.nuxie.sdk

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import ai.nuxie.sdk.core.NuxieCore
import ai.nuxie.sdk.testsupport.FakeTransport
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class NuxieIdentityFacadeTest {
    @Before
    fun setUp() {
        Nuxie.overridesForTesting = NuxieCore.Overrides(transport = FakeTransport())
        Nuxie.setup(
            RuntimeEnvironment.getApplication(),
            NuxieConfiguration("pk_test_identity"),
        )
    }

    @After
    fun tearDown() {
        Nuxie.resetForTesting()
        Nuxie.overridesForTesting = null
    }

    private fun pendingNames(): List<String> = runBlocking {
        val core = requireNotNull(Nuxie.core)
        core.eventLog.awaitBarrier()
        core.userTransitions.drain()
        core.store.pendingBatch(limit = 50).map { it.name }
    }

    @Test
    fun sameIdIdentifyWithoutPropertiesIsAFullNoOp() = runBlocking {
        Nuxie.identify("user-1")
        val core = requireNotNull(Nuxie.core)
        core.eventLog.awaitBarrier()
        val sessionAfterFirst = core.sessions.getSessionId(readOnly = true)
        val identifyCount = pendingNames().count { it == "\$identify" }

        Nuxie.identify("user-1")
        core.eventLog.awaitBarrier()

        assertEquals(identifyCount, pendingNames().count { it == "\$identify" })
        assertEquals(sessionAfterFirst, core.sessions.getSessionId(readOnly = true))
    }

    @Test
    fun anonymousToIdentifiedMigratesEventsAndCarriesAnonDistinctId() = runBlocking {
        val core = requireNotNull(Nuxie.core)
        val anonId = Nuxie.anonymousId

        // Launch lifecycle events were captured under the anonymous id.
        core.eventLog.awaitBarrier()

        Nuxie.identify("user-1")
        core.eventLog.awaitBarrier()
        core.userTransitions.drain()

        assertTrue(Nuxie.isIdentified)
        assertEquals("user-1", Nuxie.distinctId)

        val events = core.store.pendingBatch(limit = 50)
        // Migration: prior anonymous events now belong to user-1.
        assertTrue(events.all { it.distinctId == "user-1" })

        val identify = events.single { it.name == "\$identify" }
        assertEquals(
            JsonPrimitive(anonId),
            identify.properties["\$anon_distinct_id"],
        )
        assertEquals(JsonPrimitive("user-1"), identify.properties["distinct_id"])
    }

    @Test
    fun identifiedToIdentifiedDoesNotMigrateHistory() = runBlocking {
        val core = requireNotNull(Nuxie.core)
        Nuxie.identify("user-1")
        core.eventLog.awaitBarrier()
        core.userTransitions.drain()

        Nuxie.identify("user-2")
        core.eventLog.awaitBarrier()
        core.userTransitions.drain()

        val events = core.store.pendingBatch(limit = 50)
        // user-1's history stays with user-1; only user-2's $identify is new.
        assertTrue(events.any { it.distinctId == "user-1" })
        val secondIdentify = events.filter { it.name == "\$identify" }
            .single { it.distinctId == "user-2" }
        assertNull(secondIdentify.properties["\$anon_distinct_id"])
    }

    @Test
    fun identifyRotatesTheSessionOnlyOnUserChange() = runBlocking {
        val core = requireNotNull(Nuxie.core)
        val before = core.sessions.getSessionId()
        Nuxie.identify("user-1")
        val afterIdentify = core.sessions.getSessionId(readOnly = true)
        assertNotEquals(before, afterIdentify)
    }

    @Test
    fun resetReturnsToAFreshAnonymousIdentity() = runBlocking {
        val core = requireNotNull(Nuxie.core)
        Nuxie.identify("user-1")
        core.userTransitions.drain()
        val anonBefore = Nuxie.anonymousId

        Nuxie.reset()
        core.userTransitions.drain()

        assertFalse(Nuxie.isIdentified)
        assertNotEquals("user-1", Nuxie.distinctId)
        assertNotEquals(anonBefore, Nuxie.anonymousId)
    }

    @Test
    fun capturedEventsCarryASessionId() = runBlocking {
        val core = requireNotNull(Nuxie.core)
        core.eventLog.capture("session_stamped")
        core.eventLog.awaitBarrier()

        val event = core.store.pendingBatch(limit = 50).single { it.name == "session_stamped" }
        assertTrue(event.sessionId != null)
        assertEquals(
            JsonPrimitive(event.sessionId),
            event.properties["\$session_id"],
        )
    }
}
