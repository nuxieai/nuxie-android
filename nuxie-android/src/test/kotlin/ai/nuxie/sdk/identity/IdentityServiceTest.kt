package ai.nuxie.sdk.identity

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class IdentityServiceTest {
    private fun service() = IdentityService(RuntimeEnvironment.getApplication())

    @Test
    fun anonymousIdMintsOnceAndSurvivesRestart() {
        val first = service()
        val anon = first.anonymousId()
        assertEquals(anon, first.distinctId())
        assertFalse(first.isIdentified)

        // A new instance over the same files sees the same anonymous id.
        assertEquals(anon, service().anonymousId())
    }

    @Test
    fun firstIdentifyMigratesAnonymousProperties() {
        val identity = service()
        identity.setUserProperties(mapOf("plan" to "free", "seed" to 1))
        identity.setDistinctId("user-1")

        assertTrue(identity.isIdentified)
        assertEquals("user-1", identity.distinctId())
        assertEquals("user-1", identity.rawDistinctId())
        assertEquals("free", identity.userProperty("plan"))

        // Restart: identified state and migrated properties persist.
        val restarted = service()
        assertEquals("user-1", restarted.distinctId())
        assertEquals("free", restarted.userProperty("plan"))
    }

    @Test
    fun identifiedToIdentifiedDoesNotMergeProperties() {
        val identity = service()
        identity.setDistinctId("user-1")
        identity.setUserProperties(mapOf("plan" to "pro"))

        identity.setDistinctId("user-2")
        assertNull(identity.userProperty("plan"))
    }

    @Test
    fun reidentifyAfterResetMigratesTheNewAnonymousBag() {
        val identity = service()
        identity.setDistinctId("user-1")
        identity.setUserProperties(mapOf("plan" to "pro"))
        // reset removes the bag of the identity being left (user-1).
        identity.reset(keepAnonymousId = true)

        identity.setUserProperties(mapOf("plan" to "anon-plan", "origin" to "anon"))
        identity.setDistinctId("user-1")
        // Anon -> identified edge again: the anonymous bag migrates onto user-1.
        assertEquals("anon-plan", identity.userProperty("plan"))
        assertEquals("anon", identity.userProperty("origin"))
    }

    @Test
    fun setOnceDoesNotOverwrite() {
        val identity = service()
        identity.setUserProperties(mapOf("plan" to "free"))
        identity.setOnceUserProperties(mapOf("plan" to "pro", "cohort" to "a"))
        assertEquals("free", identity.userProperty("plan"))
        assertEquals("a", identity.userProperty("cohort"))
    }

    @Test
    fun resetClearsIdentityAndOptionallyRotatesAnonymousId() {
        val identity = service()
        val anon = identity.anonymousId()
        identity.setDistinctId("user-1")

        identity.reset(keepAnonymousId = true)
        assertFalse(identity.isIdentified)
        assertEquals(anon, identity.anonymousId())

        identity.setDistinctId("user-2")
        identity.reset(keepAnonymousId = false)
        assertNotEquals(anon, identity.anonymousId())
    }
}
