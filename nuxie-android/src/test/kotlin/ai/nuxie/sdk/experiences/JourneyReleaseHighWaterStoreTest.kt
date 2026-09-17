package ai.nuxie.sdk.experiences

import ai.nuxie.sdk.fixtures.FixtureRunner
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class JourneyReleaseHighWaterStoreTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val preferences get() = context.getSharedPreferences("nuxie_journey_release_high_water", 0)
    private val identity = JourneyReleaseIdentity("app", "test", "experience", "version", "build", 1, "2026-09-13T00:00:00Z", 7)
    @Before fun clear() { preferences.edit().clear().commit() }

    @Test fun sharedPublicationAdmissionVectors() {
        FixtureRunner.run("journeys/planes/publication-admission.json", "journeys/planes/publication-admission") { vector ->
            clear()
            val current = requireNotNull(JourneyReleaseIdentity.fromJson(vector.body.getValue("current").jsonObject))
            val candidate = requireNotNull(JourneyReleaseIdentity.fromJson(vector.body.getValue("candidate").jsonObject))
            val store = JourneyReleaseHighWaterStore(context)
            store.admitBatch(mapOf(current.streamKey to current))
            if (vector.body.getValue("valid").jsonPrimitive.content == "true") {
                store.admitBatch(mapOf(candidate.streamKey to candidate))
                assertEquals(candidate.publishedAtSeq, store.floor(current.streamKey))
            } else {
                assertThrows(JourneyReleaseAuthenticationException::class.java) {
                    store.admitBatch(mapOf(candidate.streamKey to candidate))
                }
                assertEquals(current.publishedAtSeq, store.floor(current.streamKey))
            }
        }
    }

    @Test fun previewAuthorityIsIsolatedFromCustomerAndOtherPreviews() {
        val customer = JourneyReleaseHighWaterStore(context)
        customer.admitBatch(mapOf(identity.streamKey to identity.copy(publishedAtSeq = 20)))
        val preview = JourneyReleaseHighWaterStore.ephemeral()
        assertEquals(0L, preview.floor(identity.streamKey))
        preview.admitBatch(mapOf(identity.streamKey to identity))
        assertEquals(7L, preview.floor(identity.streamKey))
        assertEquals(20L, JourneyReleaseHighWaterStore(context).floor(identity.streamKey))
        assertEquals(0L, JourneyReleaseHighWaterStore.ephemeral().floor(identity.streamKey))
        val other = identity.copy(experienceId = "other")
        assertThrows(JourneyReleaseAuthenticationException::class.java) {
            preview.admitBatch(linkedMapOf(other.streamKey to other,
                identity.streamKey to identity.copy(buildId = "conflict")))
        }
        assertEquals(0L, preview.floor(other.streamKey))
        preview.admitBatch(mapOf(identity.streamKey to identity.copy(publishedAtSeq = 30)))
        assertEquals(20L, customer.floor(identity.streamKey))
    }

    @Test fun exactPublicationReplaysAcrossInstancesButConflictingFieldsDoNot() {
        JourneyReleaseHighWaterStore(context).admitBatch(mapOf(identity.streamKey to identity))
        val reopened = JourneyReleaseHighWaterStore(context)
        reopened.admitBatch(mapOf(identity.streamKey to identity))
        for (changed in listOf(identity.copy(buildId = "other"), identity.copy(experienceVersionId = "other"),
            identity.copy(versionNumber = 2), identity.copy(publishedAt = "2026-09-14T00:00:00Z"),
            identity.copy(publishedAtSeq = 6))) {
            assertThrows(JourneyReleaseAuthenticationException::class.java) {
                reopened.admitBatch(mapOf(identity.streamKey to changed))
            }
        }
        assertEquals(7L, reopened.floor(identity.streamKey))
        reopened.admitBatch(mapOf(identity.streamKey to identity.copy(buildId = "new", publishedAtSeq = 8)))
        assertEquals(8L, JourneyReleaseHighWaterStore(context).floor(identity.streamKey))
    }

    @Test fun batchConflictDoesNotPartiallyPromoteAnotherStream() {
        val store = JourneyReleaseHighWaterStore(context)
        store.admitBatch(mapOf(identity.streamKey to identity))
        val other = identity.copy(experienceId = "other")
        assertThrows(JourneyReleaseAuthenticationException::class.java) {
            store.admitBatch(linkedMapOf(other.streamKey to other,
                identity.streamKey to identity.copy(buildId = "conflict")))
        }
        assertEquals(0L, store.floor(other.streamKey))
    }

    @Test fun legacySequenceOnlyFloorRequiresANewerPublicationToMigrate() {
        preferences.edit().putLong(identity.streamKey, 7).commit()
        val store = JourneyReleaseHighWaterStore(context)
        assertEquals(7L, store.floor(identity.streamKey))
        assertThrows(JourneyReleaseAuthenticationException::class.java) {
            store.admitBatch(mapOf(identity.streamKey to identity))
        }
        val newer = identity.copy(publishedAtSeq = 8)
        store.admitBatch(mapOf(identity.streamKey to newer))
        JourneyReleaseHighWaterStore(context).admitBatch(mapOf(identity.streamKey to newer))
        assertThrows(JourneyReleaseAuthenticationException::class.java) {
            store.admitBatch(mapOf(identity.streamKey to newer.copy(buildId = "conflict")))
        }
    }

    @Test fun corruptOrMisbindingLedgerFailsClosed() {
        for (corrupt in listOf("{}", "not-json")) {
            preferences.edit().putString(identity.streamKey, corrupt).commit()
            assertThrows(JourneyReleaseAuthenticationException::class.java) {
                JourneyReleaseHighWaterStore(context).floor(identity.streamKey)
            }
        }
        preferences.edit().clear().commit()
        assertThrows(JourneyReleaseAuthenticationException::class.java) {
            JourneyReleaseHighWaterStore(context).admitBatch(mapOf("wrong-stream" to identity))
        }
    }
}
