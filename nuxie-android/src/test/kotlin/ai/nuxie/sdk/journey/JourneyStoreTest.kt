package ai.nuxie.sdk.journey

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class JourneyStoreTest {
    @Test
    fun persistsAUserScopedRunAcrossStoreRestart() {
        val root = createTempDir(prefix = "nuxie-journey-store-")
        try {
            val run = JourneyRun(
                id = "018fc8e0-7b00-7000-8000-000000000001",
                distinctId = "customer-1",
                experienceId = "experience-1",
                experienceVersion = "version-1",
                epoch = 0,
                plane = JourneyPlane.DEVICE,
                settingsSnapshot = kotlinx.serialization.json.JsonObject(emptyMap()),
                state = JourneyRunState.ACTIVE,
            )

            JourneyStore(root).save(run)

            val restored = JourneyStore(root).loadActive("customer-1")
            assertEquals(listOf(run), restored)
            assertNotNull(JourneyStore(root).load("customer-1", run.id))
        } finally {
            root.deleteRecursively()
        }
    }
}
