package ai.nuxie.sdk

import ai.nuxie.sdk.core.NuxieCore
import ai.nuxie.sdk.events.SQLiteEventStore
import kotlinx.coroutines.runBlocking
import ai.nuxie.sdk.testsupport.FakeTransport
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class NuxieSetupPipelineTest {
    @org.junit.Before
    fun installFakeTransport() {
        Nuxie.overridesForTesting = NuxieCore.Overrides(transport = FakeTransport())
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

        // First "process": capture through a real store.
        val first = NuxieCore(
            context = application,
            apiKey = "pk_test_restart",
            environment = NuxieEnvironment.DEVELOPMENT,
            logLevel = LogLevel.NONE,
            beforeSend = null,
            overrides = NuxieCore.Overrides(registerLifecycle = false),
        )
        first.start()
        first.eventLog.awaitBarrier()
        first.eventLog.close()

        // Second "process" over the same database sees the prior events.
        val second = SQLiteEventStore(application)
        val pending = second.pendingBatch(limit = 10)
        assertTrue(pending.map { it.name }.containsAll(listOf("\$app_installed", "\$app_opened")))
        second.close()
    }
}
