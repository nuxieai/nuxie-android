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
    private suspend fun withSurface(globals: Boolean = false, prepareTarget: Boolean = true, body: suspend (ExperienceSurfaceHost, ExperienceInteractiveLayer,
        ExperienceInteractiveLayerTest.RecordingNative, NuxieRuntimeLane) -> Unit) {
        val native = ExperienceInteractiveLayerTest.RecordingNative().apply { this.globals = globals }
        val lane = NuxieRuntimeLane()
        val file = NuxieRuntimeFile(1, native)
        val descriptor = Json.parseToJsonElement("""{"leg":{"screens":[{"id":"a","defaultViewModelName":"A"},
            {"id":"b","defaultViewModelName":"B"}]},"render":{"layerHost":{"artboardName":"LayerList","list":"screens"}}}""").jsonObject
        val layer = ExperienceInteractiveLayer.open(lane, NuxieRuntime(native), file, descriptor, null)
        val surface = ExperienceSurfaceHost(RuntimeEnvironment.getApplication(), lane, runtime = NuxieRuntime(native), usesSystemFrameCallbacks = false)
        try {
            lane.call {
                layer.prepareOnLane("a"); layer.insertOnLane("a", 0); if (prepareTarget) layer.prepareOnLane("b")
                set(surface, "interactiveLayer", layer); set(surface, "artboard", layer.artboard)
                set(surface, "player", layer.player); set(surface, "activeRowId", "a"); set(surface, "file", file)
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

    @Test fun `ordinary layer frames avoid catalog queries and save frames still capture answers`() = runBlocking {
        withSurface { surface, layer, native, lane ->
            lane.call {
                set(surface, "saveDescriptor", Json.parseToJsonElement("""{"responses":{"feedback":{"model":"B",
                    "fields":[{"key":"keptValue","type":"number"}]}}}""").jsonObject)
                layer.insertOnLane("b", 1)
                val ordinary = NuxiePlayerStepOutcome(false, emptyList(), emptyList(), emptyList(), emptyList())
                val step = layer.captureOnLane(ordinary, 1u)
                val capture = surface.javaClass.getDeclaredMethod("captureLayerStep", ExperienceInteractiveLayer.Step::class.java)
                    .apply { isAccessible = true }
                native.catalogQueries = 0
                repeat(60) { capture.invoke(surface, step) }
                val unrelated = NuxieRuntimeEvent(0, 0, "ordinary", "", "", 0f, emptyList(), 200)
                capture.invoke(surface, step.copy(rows = step.rows + ("a" to step.rows.getValue("a").copy(
                    outcome = ordinary.copy(events = listOf(unrelated))))))
                assertEquals("Frames without a response save must not rebuild the file catalog", 0, native.catalogQueries)

                val snapshot = NativeViewModelSnapshot(200, arrayOf(
                    NativeViewModelSnapshotInstance(200, 1), NativeViewModelSnapshotInstance(300, 2)), arrayOf(
                    NativeViewModelSnapshotValue(200, 0, "responses:feedback", 9, byteArrayOf(), 300),
                    NativeViewModelSnapshotValue(300, 2, "keptValue", 2, byteArrayOf(), 0, numberValue = 4f),
                    NativeViewModelSnapshotValue(300, 3, "isset:keptValue", 3, byteArrayOf(), 0, boolValue = true)))
                val event = unrelated.copy(name = ExperienceResponseSaveRequest.EVENT, properties = listOf(
                    NuxieRuntimeEventProperty("form", NuxieRuntimeEventPropertyValue.Bytes("feedback".encodeToByteArray()))))
                val command = NuxieHostCommand(ExperienceResponseSaveRequest.EVENT, NuxieHostValue.Object(listOf(
                    NuxieHostValue.Object.Field("form", NuxieHostValue.String("feedback")))))
                capture.invoke(surface, step.copy(rows = step.rows + ("a" to step.rows.getValue("a").copy(
                    outcome = ordinary.copy(events = listOf(event), hostCommands = listOf(command)), nativeSnapshot = snapshot))))
                assertEquals("Only the requesting row needs a catalog", 1, native.catalogQueries)
                val published = field(surface, "unpublishedSteps") as Iterable<*>
                @Suppress("UNCHECKED_CAST")
                val saves = field(checkNotNull(published.last()), "saves") as List<ExperienceResponseSaveRequest>
                assertEquals(2, saves.size)
                assertTrue(saves.all { it.answers.getValue("keptValue").jsonPrimitive.double == 4.0 })
                capture.invoke(surface, step.copy(rows = step.rows + ("a" to step.rows.getValue("a").copy(
                    outcome = ordinary.copy(hostCommands = listOf(command)), nativeSnapshot = snapshot))))
                assertEquals("A host-command save also needs its catalog", 2, native.catalogQueries)
                @Suppress("UNCHECKED_CAST")
                val commandSaves = field(checkNotNull(published.last()), "saves") as List<ExperienceResponseSaveRequest>
                assertEquals(listOf(saves.last()), commandSaves)
            }
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

    @Test fun `row preparation retires a stalled submission within its operation budget`() = runTest {
        withSurface { surface, _, native, lane ->
            installPendingSubmission(surface, native, lane)
            val scene = java.io.File.createTempFile("layer-prepare", ".riv")
            val target = PreparedPresentation(scene, "B", 0, PresentationShell.FullScreen(), "b")
            val preparation = async { surface.prepareLayerRow(target) }
            runCurrent(); lane.call {}
            advanceTimeBy(251); runCurrent(); lane.call {}; runCurrent()
            try {
                assertTrue("Preparation must reach the transition even if no submission presents", preparation.isCompleted)
                preparation.await()
                assertEquals(1, native.detaches)
            } finally {
                retireAndDrain(surface, lane)
                preparation.cancelAndJoin(); scene.delete()
            }
        }
    }

    @Test fun `cancelled preparation cannot block noncancellable abort restoration or later Back`() = runTest {
        withSurface(prepareTarget = false) { surface, layer, native, lane ->
            installPendingSubmission(surface, native, lane)
            val scene = java.io.File.createTempFile("layer-abort", ".riv")
            val target = PreparedPresentation(scene, "B", 0, PresentationShell.FullScreen(), "b")
            var prepared = false
            var restored = false
            val navigation = launch(start = CoroutineStart.UNDISPATCHED) {
                try { surface.prepareLayerRow(target); prepared = true }
                finally { withContext(NonCancellable) { runCatching { surface.restoreLayerRow("a", "b") }.onSuccess { restored = true } } }
            }
            navigation.cancel(); runCurrent(); lane.call {}
            advanceTimeBy(251); repeat(4) { runCurrent(); lane.call {} }
            try {
                assertTrue("Abort must finish while its parent is cancelled", navigation.isCompleted)
                assertTrue(restored)
                @Suppress("UNCHECKED_CAST")
                val retained = field(surface, "layerRetainedValues") as Map<String, *>
                assertFalse("Cancelled preparation must not mutate retained row ownership", retained.containsKey("b"))
                assertFalse("Cancelled row preparation must never run late", prepared)
                assertEquals(1, native.detaches)
                assertEquals(listOf("a"), layer.mountedScreenIds())
                surface.prepareLayerRow(target)
                surface.layerOperation { it.insertOnLane("b", 1) }
                surface.restoreLayerRow("a", "b")
            } finally { retireAndDrain(surface, lane); navigation.cancelAndJoin(); scene.delete() }
        }
    }

    @Test fun `layer phase preserves queued environment updates for the next frame and their completion`() = runTest {
        withSurface(globals = true) { surface, layer, native, lane ->
            installPendingSubmission(surface, native, lane)
            val update = surface.updateRuntimeValues(mapOf("env/reduceMotion" to NuxieViewModelScalarValue.BooleanValue(true)))
            lane.call {}
            assertFalse(update.isCompleted)
            val phase = async { surface.applyLayerTransition {
                it.setPhasesOnLane(listOf("a" to ExperienceScreenLifecycle.Phase.EXITING))
            } }
            runCurrent(); lane.call {}
            var reducedAtStep = false
            native.onStep = { reducedAtStep = native.writes.any { it.first == 500L && it.second.path == "reduceMotion" && it.second.boolValue } }
            lane.call {
                val layout = checkNotNull(ExperienceSurfaceLayout.create(100, 100, 1f))
                set(surface, "surfaceLayout", layout); set(surface, "appliedLayout", layout)
                set(surface, "pendingPresentation", false); set(surface, "attached", true)
                native.presentation = 1
            }
            surface.doFrame(1_000_000_000L)
            lane.call {}; runCurrent()
            try {
                assertTrue("The queued update must complete with the phase frame", update.isCompleted)
                assertTrue(update.await())
                assertTrue("The next native step must see the pending environment update", reducedAtStep)
                assertEquals(NuxieViewModelScalarValue.BooleanValue(true), layer.lifecycle("a")["env/reduceMotion"])
                phase.await()
            } finally { retireAndDrain(surface, lane); phase.cancelAndJoin() }
        }
    }

    @Test fun `prepared Screen owns the lifecycle of retained and reinserted rows`() = runTest {
        withSurface { surface, layer, _, lane ->
            val scene = java.io.File.createTempFile("layer-lifecycle", ".riv")
            val source = ExperienceScreenLifecycle().apply {
                move(ExperienceScreenLifecycle.Phase.ENTERING, exitReason = ExperienceScreenLifecycle.ExitReason.NONE)
                move(ExperienceScreenLifecycle.Phase.HIDDEN, exitReason = ExperienceScreenLifecycle.ExitReason.NONE)
            }
            val next = source.copyForPreparation()
            val target = PreparedPresentation(scene, "B", 0, PresentationShell.FullScreen(), "b", screenLifecycle = next)
            try {
                surface.layerOperation {
                    it.setPhasesOnLane(listOf("b" to ExperienceScreenLifecycle.Phase.ENTERING))
                    it.setPhasesOnLane(listOf("b" to ExperienceScreenLifecycle.Phase.HIDDEN))
                }
                surface.prepareLayerRow(target)
                surface.layerOperation {
                    it.insertOnLane("b", 1)
                    it.setPhasesOnLane(listOf("b" to ExperienceScreenLifecycle.Phase.ENTERING))
                    it.setPhasesOnLane(listOf("b" to ExperienceScreenLifecycle.Phase.ACTIVE))
                }
                assertEquals("Row visits must update the PreparedPresentation lifecycle owner", 2uL, next.appearances)
                assertEquals(ExperienceScreenLifecycle.Phase.ACTIVE, next.phase)
                assertEquals(1uL, source.appearances)
                assertEquals(NuxieViewModelScalarValue.NumberValue(2.0), layer.lifecycle("b")["screen/appearances"])
                surface.removeLayerRow("b")
                val revisit = next.copyForPreparation()
                surface.prepareLayerRow(target.copy(screenLifecycle = revisit))
                surface.layerOperation {
                    it.insertOnLane("b", 1)
                    it.setPhasesOnLane(listOf("b" to ExperienceScreenLifecycle.Phase.ENTERING))
                }
                assertEquals(3uL, revisit.appearances)
                assertEquals(2uL, next.appearances)
            } finally { scene.delete() }
        }
    }

    @Test fun `stale preparation lifecycle cannot reset a retained row appearance history`() = runTest {
        withSurface { surface, layer, _, _ ->
            val scene = java.io.File.createTempFile("layer-retained-lifecycle", ".riv")
            try {
                surface.layerOperation { rowLayer ->
                    repeat(2) {
                        rowLayer.setPhasesOnLane(listOf("b" to ExperienceScreenLifecycle.Phase.ENTERING))
                        rowLayer.setPhasesOnLane(listOf("b" to ExperienceScreenLifecycle.Phase.HIDDEN))
                    }
                }
                val incoming = ExperienceScreenLifecycle()
                surface.prepareLayerRow(PreparedPresentation(scene, "B", 0, PresentationShell.FullScreen(), "b", screenLifecycle = incoming))
                surface.layerOperation { it.setPhasesOnLane(listOf("b" to ExperienceScreenLifecycle.Phase.ENTERING)) }
                assertEquals("A retained row must carry its own cumulative lifecycle into the new owner", 3uL, incoming.appearances)
                assertEquals(NuxieViewModelScalarValue.NumberValue(3.0), layer.lifecycle("b")["screen/appearances"])
            } finally { scene.delete() }
        }
    }

    private suspend fun installPendingSubmission(surface: ExperienceSurfaceHost,
        native: ExperienceInteractiveLayerTest.RecordingNative, lane: NuxieRuntimeLane) {
        lane.call {
            val renderer = NuxieAndroidVulkanRenderer(900, native)
            val window = NuxieRuntimeWindow(901, native)
            set(surface, "renderer", renderer); set(surface, "window", window)
            set(surface, "running", true)
            val player = field(surface, "player") as NuxieRuntimePlayer
            assertEquals(4, renderer.renderAndPresent(player, window, 0, 1f))
            set(surface, "pendingPresentation", true)
        }
    }

    private suspend fun retireAndDrain(surface: ExperienceSurfaceHost, lane: NuxieRuntimeLane) = lane.call {
        surface.javaClass.getDeclaredMethod("retireSubmission").apply { isAccessible = true }.invoke(surface)
        surface.javaClass.getDeclaredMethod("drainTextWrites").apply { isAccessible = true }.invoke(surface)
    }

    private fun field(owner: Any, name: String): Any? = owner.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(owner)
    private fun set(owner: Any, name: String, value: Any?) = owner.javaClass.getDeclaredField(name).apply { isAccessible = true }.set(owner, value)
}
