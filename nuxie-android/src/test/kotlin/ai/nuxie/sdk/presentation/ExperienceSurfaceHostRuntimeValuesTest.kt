package ai.nuxie.sdk.presentation

import ai.nuxie.sdk.runtime.NuxieRuntimeArtboard
import ai.nuxie.sdk.runtime.NuxieRuntimeLane
import ai.nuxie.sdk.runtime.NuxieTypedRuntimeNative
import ai.nuxie.sdk.runtime.NuxieViewModelScalarValue
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@org.robolectric.annotation.Config(sdk = [35])
@org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.LEGACY)
@RunWith(RobolectricTestRunner::class)
class ExperienceSurfaceHostRuntimeValuesTest {
    @Test fun `write without a bound model reports rejection`() {
        val lane = NuxieRuntimeLane()
        val host = ExperienceSurfaceHost(RuntimeEnvironment.getApplication(), lane)
        val native = object : NuxieTypedRuntimeNative {
            override val isAvailable = true
            override fun freeArtboard(handle: Long) {}
        }
        try {
            runBlocking {
                lane.call {
                    val artboard = NuxieRuntimeArtboard(1, native) { error("Unbound write must not inspect a model") }
                    ExperienceSurfaceHost::class.java.getDeclaredField("artboard").apply { isAccessible = true }.set(host, artboard)
                }
                assertFalse(withTimeout(5_000) { host.updateRuntimeValues(values).await() })
            }
        } finally {
            host.release()
            lane.shutdown()
            assertTrue(lane.awaitQuiescence(5_000))
        }
    }

    @Test fun `write queued before loading completes as rejected when released`() {
        val lane = NuxieRuntimeLane()
        val host = ExperienceSurfaceHost(RuntimeEnvironment.getApplication(), lane)
        try {
            val pending = host.updateRuntimeValues(values)
            runBlocking { lane.call {} }
            assertFalse(pending.isCompleted)
            host.release()
            runBlocking { assertFalse(withTimeout(5_000) { pending.await() }) }
            runBlocking { assertFalse(host.updateRuntimeValues(values).await()) }
        } finally {
            host.release()
            lane.shutdown()
            assertTrue(lane.awaitQuiescence(5_000))
        }
    }

    private val values = mapOf("paused" to NuxieViewModelScalarValue.BooleanValue(true))
}
