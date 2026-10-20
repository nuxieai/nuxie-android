package ai.nuxie.sdk.presentation

import ai.nuxie.sdk.runtime.*
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
@OptIn(ExperimentalCoroutinesApi::class)
class ExperienceLayerSurfaceFailureTest {
    private suspend fun withSurface(body: suspend (ExperienceSurfaceHost, ExperienceInteractiveLayer,
        ExperienceInteractiveLayerTest.RecordingNative, NuxieRuntimeLane) -> Unit) {
        val native = ExperienceInteractiveLayerTest.RecordingNative()
        val lane = NuxieRuntimeLane()
        val file = NuxieRuntimeFile(1, native)
        val descriptor = Json.parseToJsonElement("""{"leg":{"screens":[{"id":"a","defaultViewModelName":"A"},
            {"id":"b","defaultViewModelName":"B"}]},"render":{"layerHost":{"artboardName":"LayerList","list":"screens"}}}""").jsonObject
        val layer = ExperienceInteractiveLayer.open(lane, NuxieRuntime(native), file, descriptor, null)
        val surface = ExperienceSurfaceHost(RuntimeEnvironment.getApplication(), lane, runtime = NuxieRuntime(native))
        try {
            lane.call {
                layer.prepareOnLane("a"); layer.insertOnLane("a", 0); layer.prepareOnLane("b")
                set(surface, "interactiveLayer", layer); set(surface, "artboard", layer.artboard)
                set(surface, "player", layer.player); set(surface, "activeRowId", "a")
                @Suppress("UNCHECKED_CAST")
                val retained = field(surface, "layerRetainedValues") as MutableMap<String, AtomicReference<NuxieViewModelSnapshot?>>
                retained["a"] = AtomicReference()
            }
            body(surface, layer, native, lane)
        } finally {
            surface.release(); lane.call {}; layer.close(); lane.call { file.close() }
            lane.shutdown(); assertTrue(lane.awaitQuiescence(5_000))
        }
    }

    @Test fun `failed layer release closes handles and completes media retirement exactly once`() = runBlocking {
        withSurface { surface, layer, native, lane ->
            val failure = IllegalStateException("failed frame")
            val waiter = layer.registerCompletion("a", "done")
            val report = surface.javaClass.getDeclaredMethod("reportFailure", ExperiencePresentationException.Reason::class.java,
                String::class.java, Throwable::class.java).apply { isAccessible = true }
            report.invoke(surface, ExperiencePresentationException.Reason.HOST_FAILED, "failed frame", failure)
            lane.call {}
            assertEquals(failure.message, runCatching { waiter.await() }.exceptionOrNull()?.message)
            var retired = 0
            var completed = 0
            val retirement = ExperienceScreenRetirement { completed++ }
            surface.release(onMediaReleased = { retired++; retirement.mediaReleased() }); lane.call {}
            retirement.nativeReleased()
            surface.release(onMediaReleased = { retired++; retirement.mediaReleased() }); lane.call {}
            assertEquals("Media retirement must unblock Screen retirement", 1, retired)
            assertEquals(1, completed)
            assertEquals(1, native.freed.count { it == "player" })
            assertEquals(1, native.freed.count { it == "artboard" })
            assertTrue(native.freed.containsAll(listOf("values:100", "values:200", "values:300")))
        }
    }

    @Test fun `hidden insertion failure finishes the write and fails the surface`() = assertWriteFailure(false, true)
    @Test fun `hidden phase failure finishes the write and fails the surface`() = assertWriteFailure(false, false)
    @Test fun `visible insertion failure finishes the write and fails the surface`() = assertWriteFailure(true, true)
    @Test fun `visible phase failure finishes the write and fails the surface`() = assertWriteFailure(true, false)

    private fun assertWriteFailure(running: Boolean, inserting: Boolean) = runTest {
        withSurface { surface, layer, native, lane ->
            val failures = mutableListOf<Throwable>()
            set(surface, "listener", object : ExperienceSurfaceHost.Listener {
                override fun onFirstFrame() = Unit
                override fun onFailure(error: ExperiencePresentationException) { failures += error }
            })
            val waiter = layer.registerCompletion("a", "done")
            lane.call { set(surface, "running", running); native.failWrites = true }
            val write = async { runCatching { surface.applyLayerTransition {
                if (inserting) it.insertOnLane("b", 1)
                else it.setPhasesOnLane(listOf("a" to ExperienceScreenLifecycle.Phase.EXITING))
            } } }
            runCurrent(); lane.call {}
            if (running) lane.call {
                runCatching { surface.javaClass.getDeclaredMethod("applyNextTransitionWrite").apply { isAccessible = true }.invoke(surface) }
            }
            lane.call {}; runCurrent()
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            try {
                assertTrue("Mutation failures must finish the removed write", write.isCompleted)
                assertTrue(write.await().exceptionOrNull() is NuxieRuntimeCallException)
                assertEquals("The surface must report the failed mutation", 1, failures.size)
                assertTrue("All row waiters must fail", waiter.isCompleted)
            } finally { write.cancelAndJoin(); native.failWrites = false }
        }
    }

    @Test fun `phase barrier without a window finishes within the watchdog and settles mutations`() = runTest {
        withSurface { surface, layer, _, lane ->
            lane.call { set(surface, "running", true) }
            val phase = async { surface.applyLayerTransition {
                it.setPhasesOnLane(listOf("a" to ExperienceScreenLifecycle.Phase.EXITING))
            } }
            runCurrent(); lane.call {}
            advanceTimeBy(251); runCurrent(); lane.call {}; runCurrent()
            try {
                assertTrue("A missing window must not hold navigation before its watchdog", phase.isCompleted)
                phase.await()
                assertEquals(NuxieViewModelScalarValue.StringValue("exiting"), layer.lifecycle("a")["screen/phase"])
                surface.layerOperation { it.insertOnLane("b", 1) }
            } finally { phase.cancelAndJoin() }
        }
    }

    @Test fun `perpetually pending native presentation is retired within the phase budget`() = runTest {
        withSurface { surface, layer, native, lane ->
            val renderer = NuxieAndroidVulkanRenderer(900, native)
            val window = NuxieRuntimeWindow(901, native)
            lane.call {
                set(surface, "running", true); set(surface, "renderer", renderer); set(surface, "window", window)
                repeat(3) { assertEquals(4, renderer.renderAndPresent(layer.player, window, 0, 1f)) }
                set(surface, "pendingPresentation", true)
            }
            val phase = async { surface.applyLayerTransition {
                it.insertOnLane("b", 1)
                it.setPhasesOnLane(listOf("a" to ExperienceScreenLifecycle.Phase.EXITING, "b" to ExperienceScreenLifecycle.Phase.ENTERING))
            } }
            runCurrent(); lane.call {}
            advanceTimeBy(251); runCurrent(); lane.call {}; runCurrent()
            try {
                assertTrue("Healthy pending renders must not hold the phase barrier forever", phase.isCompleted)
                phase.await()
                assertEquals(1, native.detaches)
                assertFalse(field(surface, "pendingPresentation") as Boolean)
                assertEquals(listOf("a", "b"), layer.mountedScreenIds())
                surface.removeLayerRow("a")
                assertEquals(listOf("b"), layer.mountedScreenIds())
            } finally { phase.cancelAndJoin() }
        }
    }

    @Test fun `mounted navigation survives starved phase frames for together and cut halves`() = runTest {
        ai.nuxie.sdk.logging.NuxieLog.configure(ai.nuxie.sdk.LogLevel.WARN)
        for (style in listOf("magic-move", "none")) withSurface { surface, layer, _, lane ->
            org.robolectric.shadows.ShadowLog.clear()
            val activity = org.robolectric.Robolectric.buildActivity(android.app.Activity::class.java).setup()
            val scene = java.io.File.createTempFile("layer-watchdog", ".riv").apply { writeBytes(byteArrayOf(1)) }
            val descriptor = Json.parseToJsonElement("""{"leg":{"screens":[{"id":"a","defaultViewModelName":"A"},
                {"id":"b","defaultViewModelName":"B"}]},"render":{"layerHost":{"artboardName":"LayerList","list":"screens"},
                "transitions":[{"id":"pair","kind":"layer","sourceScreenId":"a","destinationScreenId":"b","durationMs":100,
                "incomingOnTop":true,"source":{"completeEventName":"leave"},"destination":{"completeEventName":"arrive"}}]}}""").jsonObject
            val source = PreparedPresentation(scene, "A", 0, PresentationShell.FullScreen(), "a", descriptor)
            val target = source.copy(screenId = "b", artboardName = "B", transition = Json.parseToJsonElement(
                """{"type":"$style","transitionId":"pair"}""").jsonObject)
            val listener = object : ExperienceSurfaceHost.Listener {
                override fun onFirstFrame() = Unit
                override fun onFailure(error: ExperiencePresentationException) { throw AssertionError(error) }
            }
            val mounted = ExperienceMountedScreen(activity.get(), source, listener, { throw AssertionError(it) })
            val ownedLane = field(mounted, "lane") as NuxieRuntimeLane
            set(mounted, "surface", surface)
            var navigation: Deferred<Unit>? = null
            try {
                mounted.prepareRow(target, listener)
                lane.call { set(surface, "running", true) }
                navigation = async { mounted.navigateLayer(target, false) }
                // Drain real lane handoffs between virtual clock increments. No frame is supplied.
                repeat(32) {
                    repeat(4) { runCurrent(); lane.call {} }
                    advanceTimeBy(25)
                }
                repeat(4) { runCurrent(); lane.call {} }
                assertTrue("The mounted $style step must activate despite missing phase frames", navigation.isCompleted)
                navigation.await()
                assertEquals(listOf("b"), layer.mountedScreenIds())
                assertTrue(surface.isEnabled)
                assertEquals(1, org.robolectric.shadows.ShadowLog.getLogsForTag("ExperienceLayerStep").count {
                    it.msg.contains("presentation watchdog expired")
                })
            } finally {
                navigation?.cancelAndJoin()
                mounted.close(false) {}
                assertTrue(ownedLane.awaitQuiescence(5_000))
                activity.pause().stop().destroy(); scene.delete()
            }
        }
    }

    private fun field(owner: Any, name: String): Any? = owner.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(owner)
    private fun set(owner: Any, name: String, value: Any?) = owner.javaClass.getDeclaredField(name).apply { isAccessible = true }.set(owner, value)
}
