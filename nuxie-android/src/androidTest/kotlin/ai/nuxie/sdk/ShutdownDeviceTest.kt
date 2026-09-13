package ai.nuxie.sdk

import ai.nuxie.sdk.core.NuxieCore
import ai.nuxie.sdk.events.SQLiteEventStore
import ai.nuxie.sdk.network.HttpTransport
import android.os.Looper
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** Real main-looper and SQLite qualification; transport never contacts a backend. */
class ShutdownDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext

    @After
    fun tearDown() {
        Nuxie.resetForTesting()
        Nuxie.overridesForTesting = null
    }

    @Test
    fun mainThreadCallbackCanShutdownAndItsAcceptedEventSurvivesReopening() = runBlocking {
        val database = File(context.cacheDir, "shutdown-device-${System.nanoTime()}.db")
        Nuxie.overridesForTesting = NuxieCore.Overrides(
            transport = object : HttpTransport {
                override fun execute(request: HttpTransport.Request) = HttpTransport.Response(503, ByteArray(0))
            },
            eventDatabaseFile = database,
            requestInitialProfileRefresh = false,
        )
        try {
            repeat(3) { iteration ->
                instrumentation.runOnMainSync {
                    Nuxie.setup(context, NuxieConfiguration("pk_test_device_shutdown"))
                }
                val graph = requireNotNull(Nuxie.core)
                val eventName = "device_shutdown_$iteration"
                val listener = NuxieListener { _, _ ->
                    assertSame(Looper.getMainLooper(), Looper.myLooper())
                    val selfAwait = runCatching { runBlocking { Nuxie.shutdownAndAwait() } }.exceptionOrNull()
                    assertTrue(selfAwait is IllegalStateException)
                    Nuxie.trigger(eventName)
                    Nuxie.shutdown()
                    assertFalse(Nuxie.isSetup)
                }
                Nuxie.listener = listener
                withTimeout(10_000) {
                    assertTrue(Nuxie.deliverAppAction(
                        AppAction("shutdown", null, ExperienceRef("device", "1", "journey")),
                    ) { publication -> publication(); true })
                    Nuxie.shutdownAndAwait()
                }
                assertTrue(graph.scope.coroutineContext[Job]!!.isCompleted)
                assertFalse(Nuxie.isSetup)
                val reopened = SQLiteEventStore(context, databaseFile = database)
                try {
                    assertTrue(reopened.hasEvent(eventName, graph.identity.distinctId(), null))
                } finally { reopened.close() }
            }
        } finally {
            Nuxie.shutdownAndAwait()
            context.deleteDatabase(database.absolutePath)
        }
    }
}
