package ai.nuxie.sdk

import ai.nuxie.sdk.core.NuxieCore
import ai.nuxie.sdk.events.SQLiteEventStore
import java.io.File
import kotlinx.coroutines.runBlocking
import ai.nuxie.sdk.testsupport.FakeTransport
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class NuxieSetupPipelineTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @org.junit.Before
    fun installFakeTransport() {
        val storageDirectory = temporaryFolder.newFolder("setup")
        Nuxie.overridesForTesting = NuxieCore.Overrides(
            transport = FakeTransport(),
            eventDatabaseFile = File(storageDirectory, "events.db"),
            profileCacheDirectory = File(storageDirectory, "profiles"),
        )
    }

    @After
    fun tearDown() {
        Nuxie.resetForTesting()
        Nuxie.overridesForTesting = null
    }

    @Test
    fun setupCapturesInstallAndOpenMomentsIntoThePendingStore() = runBlocking {
        val application = RuntimeEnvironment.getApplication()
        Nuxie.setup(application, NuxieConfiguration("pk_test_pipeline"))

        val core = requireNotNull(Nuxie.core)
        core.eventLog.awaitBarrier()

        val pending = core.store.pendingBatch(limit = 10)
        assertEquals(listOf("\$app_installed", "\$app_opened"), pending.map { it.name })
        // Every capture is enriched and attributed to the anonymous identity.
        pending.forEach { event ->
            assertEquals(core.identity.distinctId(), event.distinctId)
        }
    }

    @Test
    fun pendingEventsSurviveRestartOfThePipeline() = runBlocking {
        val application = RuntimeEnvironment.getApplication()
        val storageDirectory = temporaryFolder.newFolder("restart")
        val databaseFile = File(storageDirectory, "events.db")

        // First "process": capture through a real store.
        val first = NuxieCore(
            context = application,
            apiKey = "pk_test_restart",
            environment = NuxieEnvironment.DEVELOPMENT,
            logLevel = LogLevel.NONE,
            beforeSend = null,
            overrides = NuxieCore.Overrides(
                registerLifecycle = false,
                eventDatabaseFile = databaseFile,
                profileCacheDirectory = File(storageDirectory, "profiles"),
            ),
        )
        try {
            first.start()
            first.eventLog.awaitBarrier()
        } finally {
            first.stop()
        }

        // Second "process" over the same database sees the prior events.
        val second = SQLiteEventStore(application, databaseFile = databaseFile)
        try {
            val pending = second.pendingBatch(limit = 10)
            assertTrue(pending.map { it.name }.containsAll(listOf("\$app_installed", "\$app_opened")))
        } finally {
            second.close()
        }
    }
}
