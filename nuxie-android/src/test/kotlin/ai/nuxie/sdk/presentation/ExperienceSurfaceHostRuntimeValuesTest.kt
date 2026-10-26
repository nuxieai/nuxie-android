package ai.nuxie.sdk.presentation

import ai.nuxie.sdk.runtime.TestRuntimeLane

import ai.nuxie.sdk.runtime.NativeCallResult
import ai.nuxie.sdk.runtime.NativeViewModelWrite
import ai.nuxie.sdk.runtime.NuxieViewModelCatalog
import ai.nuxie.sdk.runtime.NuxieViewModelPropertyKind
import ai.nuxie.sdk.runtime.NuxieRuntimeArtboard
import ai.nuxie.sdk.runtime.NuxieRuntimeLane
import ai.nuxie.sdk.runtime.NuxieTypedRuntimeNative
import ai.nuxie.sdk.runtime.NuxieViewModelScalarValue
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
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
        org.robolectric.shadows.ShadowLog.clear()
        val lane = TestRuntimeLane()
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
            // Ordinary screens without a default view model ignore writes quietly.
            assertTrue(org.robolectric.shadows.ShadowLog.getLogsForTag("Nuxie")
                .none { it.msg.startsWith("Experience state path rejected") })
        } finally {
            host.release()
            lane.shutdown()
            assertTrue(lane.awaitQuiescence(5_000))
        }
    }

    @Test fun `write queued before loading completes as rejected when released`() {
        val lane = TestRuntimeLane()
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

    @Test fun `transition phase wins over an older write held by a submitted frame`() {
        val lane = TestRuntimeLane()
        val host = ExperienceSurfaceHost(RuntimeEnvironment.getApplication(), lane)
        val writes = java.util.concurrent.CopyOnWriteArrayList<String>()
        val native = object : NuxieTypedRuntimeNative {
            override val isAvailable = true
            override fun newDefaultViewModel(artboardHandle: Long) = NativeCallResult(0, 2L)
            override fun viewModelRootSchemaIndex(viewModelHandle: Long) = NativeCallResult(0, 0L)
            override fun bindViewModel(artboardHandle: Long, viewModelHandle: Long) = 0
            override fun mutateViewModel(viewModelHandle: Long, write: NativeViewModelWrite): Int {
                writes += write.bytesValue.decodeToString()
                return 0
            }
            override fun freeViewModel(handle: Long) = 0
            override fun freeArtboard(handle: Long) {}
        }
        val catalog = NuxieViewModelCatalog(
            listOf(NuxieViewModelCatalog.Schema(0, "Root", 0..0, IntRange.EMPTY, null, false)),
            listOf(NuxieViewModelCatalog.Property(0, 0, "phase", NuxieViewModelPropertyKind.STRING, null, emptyList())),
            emptyList(),
        )
        try {
            runBlocking {
                val pending = ExperienceSurfaceHost::class.java.getDeclaredField("pendingPresentation").apply { isAccessible = true }
                lane.call {
                    val artboard = NuxieRuntimeArtboard(1, native) { catalog }.apply { bindDefaultViewModel("Root") }
                    ExperienceSurfaceHost::class.java.getDeclaredField("artboard").apply { isAccessible = true }.set(host, artboard)
                    pending.setBoolean(host, true)
                }
                val older = host.updateRuntimeValues(mapOf("phase" to NuxieViewModelScalarValue.StringValue("enter")))
                lane.call {}
                val transition = async(start = CoroutineStart.UNDISPATCHED) {
                    host.applyTransitionValues(mapOf("phase" to NuxieViewModelScalarValue.StringValue("exit")))
                }
                try {
                    lane.call {
                        pending.setBoolean(host, false)
                        ExperienceSurfaceHost::class.java.getDeclaredMethod("applyNextTransitionWrite")
                            .apply { isAccessible = true }.invoke(host)
                    }
                    assertTrue(withTimeout(5_000) { older.await() })
                    assertEquals("A queued screen value must not replace the later transition phase", "exit", writes.last())
                } finally { transition.cancelAndJoin() }
            }
        } finally {
            host.release()
            lane.shutdown()
            assertTrue(lane.awaitQuiescence(5_000))
        }
    }

    private val values = mapOf("paused" to NuxieViewModelScalarValue.BooleanValue(true))
}
