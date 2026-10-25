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
    @Test fun `stalled submission retirement bounds lane and completion waits`() = runTest {
        for (phase in listOf(false, true)) withSurface { surface, _, _, lane ->
            val entered = java.util.concurrent.CountDownLatch(1)
            val unblock = java.util.concurrent.CountDownLatch(1)
            lane.enqueue { entered.countDown(); check(unblock.await(30, java.util.concurrent.TimeUnit.SECONDS)) }
            assertTrue(entered.await(5, java.util.concurrent.TimeUnit.SECONDS))
            val operation = async {
                runCatching {
                    if (phase) surface.applyLayerTransition(250) { error("Cancelled phase must not mutate the page") }
                    else surface.layerOperation { error("Cancelled operation must not mutate the page") }
                }.exceptionOrNull()
            }
            try {
                runCurrent()
                advanceTimeBy(251)
                runCurrent()
                assertFalse(operation.isCompleted)
                advanceTimeBy(5_001)
                runCurrent()
                assertTrue("Stalled retirement must stop waiting on the lane", operation.isCompleted)
                val failure = operation.await() as ExperiencePresentationException
                assertEquals(ExperiencePresentationException.Reason.HOST_FAILED, failure.reason)
                assertTrue(failure.cause is TimeoutCancellationException)
            } finally {
                unblock.countDown()
                operation.cancelAndJoin()
            }
        }
    }

    // A real native handoff must drain before runTest can auto-advance a retirement deadline.
    private fun <T> NuxieRuntimeLane.blockingCall(work: () -> T): T = runBlocking { call(work) }

    private suspend fun withSurface(globals: Boolean = false, prepareTarget: Boolean = true, body: suspend (ExperienceSurfaceHost, ExperienceInteractivePage,
        ExperienceInteractivePageTest.RecordingNative, NuxieRuntimeLane) -> Unit) {
        val native = ExperienceInteractivePageTest.RecordingNative().apply { this.globals = globals }
        val lane = NuxieRuntimeLane()
        val file = NuxieRuntimeFile(1, native)
        val descriptor = Json.parseToJsonElement("""{"leg":{"screens":[{"id":"a","defaultViewModelName":"A"},
            {"id":"b","defaultViewModelName":"B"},{"id":"c","defaultViewModelName":"C"}]},"render":{"screens":[{"id":"a","artboardName":"A"},{"id":"b","artboardName":"B"},{"id":"c","artboardName":"C"}],"layerHost":{"artboardName":"LayerList","list":"screens"}}}""").jsonObject
        val layer = ExperienceInteractivePage.open(lane, NuxieRuntime(native), file, descriptor, null)
        val surface = ExperienceSurfaceHost(RuntimeEnvironment.getApplication(), lane, runtime = NuxieRuntime(native), usesSystemFrameCallbacks = false)
        try {
            lane.blockingCall {
                layer.prepareOnLane("a"); layer.insertOnLane("a", 0); if (prepareTarget) layer.prepareOnLane("b")
                set(surface, "interactiveLayer", layer); set(surface, "artboard", layer.artboard)
                set(surface, "player", layer.player); set(surface, "activeRowId", "a"); set(surface, "file", file)
                @Suppress("UNCHECKED_CAST")
                val retained = field(surface, "layerRetainedValues") as MutableMap<String, AtomicReference<NuxieViewModelSnapshot?>>
                retained["a"] = AtomicReference()
            }
            body(surface, layer, native, lane)
        } finally {
            surface.release(); lane.blockingCall {}; runBlocking { layer.close() }; lane.blockingCall { file.close() }
            lane.shutdown(); assertTrue(lane.awaitQuiescence(5_000))
        }
    }

    @Test fun `row mutations defer pending events and commands to the first ordinary frame exactly once`() = runBlocking {
        withSurface { surface, page, native, lane ->
            val commands = mutableListOf<String>()
            val events = mutableListOf<String>()
            lane.blockingCall {
                set(surface, "firstFrameComposed", true)
                set(surface, "listener", object : ExperienceSurfaceHost.Listener {
                    override fun onFirstFrame() = Unit
                    override fun onFailure(error: ExperiencePresentationException) { throw error }
                    override fun onLayerRuntimeStep(screenId: String, outcome: NuxiePlayerStepOutcome, correlationId: ULong,
                        snapshot: NuxieViewModelSnapshot, saves: List<ExperienceResponseSaveRequest>) {
                        assertEquals("a", screenId)
                        commands += outcome.hostCommands.map { it.name }
                        events += outcome.events.map { it.name }
                    }
                })
                native.events = arrayOf(NativeRuntimeEvent(0, 0, "pending", "", "", 0f, emptyArray(), 200))
                native.trace.clear()
                page.takeIncomingOnLane("b", "a", false)
                page.beginOnLane("b", "pair"); page.endOnLane("b", "pair")
                // Row mutations never drain commands; the first ordinary frame does.
                page.takeIncomingOnLane("a", "b", false)
                assertTrue(commands.isEmpty())
                assertTrue(events.isEmpty())
                assertFalse(native.trace.any { it.startsWith("step:") })
                captureAndPublish(surface, page.stepOnLane(0.016))
                assertEquals(listOf("pending"), events)
                assertEquals(listOf("takeoff", "landing"), commands)
                page.takeIncomingOnLane("b", "a", false)
                captureAndPublish(surface, page.stepOnLane(0.016))
                assertEquals(listOf("pending"), events)
                assertEquals(listOf("takeoff", "landing"), commands)
            }
        }
    }

    @Test fun `page commands share active B snapshot saves and row effects exactly once`() = runBlocking {
        for (removed in listOf(false, true)) withSurface { surface, page, native, lane ->
            lane.blockingCall {
                page.insertOnLane("b", 1)
                set(surface, "activeRowId", "b")
                set(surface, "saveDescriptor", Json.parseToJsonElement("""{"responses":{"feedback":{"model":"B",
                    "fields":[{"key":"keptValue","type":"number"}]}}}""").jsonObject)
                val snapshot = NativeViewModelSnapshot(300, arrayOf(
                    NativeViewModelSnapshotInstance(300, 2), NativeViewModelSnapshotInstance(400, 2),
                    NativeViewModelSnapshotInstance(500, 3)), arrayOf(
                    NativeViewModelSnapshotValue(300, 0, "responses:feedback", 9, byteArrayOf(), 400),
                    NativeViewModelSnapshotValue(300, 1, "state", 9, byteArrayOf(), 500),
                    NativeViewModelSnapshotValue(400, 2, "keptValue", 2, byteArrayOf(), 0, numberValue = 4f),
                    NativeViewModelSnapshotValue(400, 3, "isset:keptValue", 3, byteArrayOf(), 0, boolValue = true),
                    NativeViewModelSnapshotValue(500, 0, "held", 2, byteArrayOf(), 0, numberValue = 7f)))
                val save = NuxieHostCommand(ExperienceResponseSaveRequest.EVENT, NuxieHostValue.Object(listOf(
                    NuxieHostValue.Object.Field("form", NuxieHostValue.String("feedback")),
                    NuxieHostValue.Object.Field("awaitTrigger", NuxieHostValue.String("state/continue")),
                    NuxieHostValue.Object.Field("keep:held", NuxieHostValue.String("state/held")))))
                val commands = listOf(save.copy(name = "before"), save, save.copy(name = "after"))
                val event = NuxieRuntimeEvent(0, 0, "owned", "", "", 0f, emptyList(), 300)
                val change = NuxieViewModelChange(NuxieViewModelChangeOrigin.RUNTIME, 42u, 300u, 2,
                    NuxieViewModelValue.Number(4f))
                val outcome = NuxiePlayerStepOutcome(false, emptyList(), listOf(event), commands, listOf(change))
                if (removed) {
                    native.snapshotOverrides[300] = snapshot
                    page.removeOnLane("b")
                }
                val step = page.captureOnLane(outcome, 42u)
                val captured = if (removed) step else step.copy(rows = step.rows +
                    ("b" to step.rows.getValue("b").copy(nativeSnapshot = snapshot)))
                val expectedSnapshot = if (removed) page.snapshotOnLane("b") else captured.rows.getValue("b").snapshot
                var calls = 0
                set(surface, "listener", object : ExperienceSurfaceHost.Listener {
                    override fun onFirstFrame() = Unit
                    override fun onFailure(error: ExperiencePresentationException) { throw error }
                    override fun onRuntimeStep(outcome: NuxiePlayerStepOutcome, correlationId: ULong,
                        viewModelSnapshot: NuxieViewModelSnapshot?, saves: List<ExperienceResponseSaveRequest>) {
                        fail("Page commands must use the row listener")
                    }
                    override fun onLayerRuntimeStep(screenId: String, outcome: NuxiePlayerStepOutcome, correlationId: ULong,
                        snapshot: NuxieViewModelSnapshot, saves: List<ExperienceResponseSaveRequest>) {
                        calls++
                        assertEquals("b", screenId)
                        assertEquals(42uL, correlationId)
                        assertSame(expectedSnapshot, snapshot)
                        assertEquals(commands, outcome.hostCommands)
                        assertEquals(if (removed) emptyList<NuxieRuntimeEvent>() else listOf(event), outcome.events)
                        assertEquals(if (removed) emptyList<NuxieViewModelChange>() else listOf(change), outcome.viewModelChanges)
                        val request = saves.single()
                        assertEquals("state/continue", request.awaitTrigger)
                        assertEquals(4.0, request.answers.getValue("keptValue").jsonPrimitive.double, 0.0)
                        assertEquals("state/held", request.kept.single().jsonObject.getValue("path").jsonPrimitive.content)
                        assertEquals(7.0, request.kept.single().jsonObject.getValue("value").jsonPrimitive.double, 0.0)
                    }
                })
                captureAndPublish(surface, captured)
                assertEquals(1, calls)
            }
        }
    }

    @Test fun `late step from removed A delivers commands once to active B`() = assertLateCommands("b")

    @Test fun `removed active row keeps its snapshot for late commands`() = assertLateCommands("a")

    private fun assertLateCommands(activeId: String) = runBlocking {
        withSurface { surface, page, _, lane ->
            lane.blockingCall {
                page.insertOnLane("b", 1)
                page.beginOnLane("b", "pair"); page.endOnLane("b", "pair")
                page.removeOnLane("a")
                set(surface, "activeRowId", activeId)
            }
            val late = page.stepFromRow("a")
            lane.blockingCall {
                val received = mutableListOf<String>()
                set(surface, "listener", object : ExperienceSurfaceHost.Listener {
                    override fun onFirstFrame() = Unit
                    override fun onFailure(error: ExperiencePresentationException) { throw error }
                    override fun onLayerRuntimeStep(screenId: String, outcome: NuxiePlayerStepOutcome, correlationId: ULong,
                        snapshot: NuxieViewModelSnapshot, saves: List<ExperienceResponseSaveRequest>) {
                        assertEquals(activeId, screenId)
                        assertEquals(if (activeId == "a") 200L else 300L, snapshot.nativeRootInstanceId)
                        received += outcome.hostCommands.map { it.name }
                    }
                })
                captureAndPublish(surface, late)
                captureAndPublish(surface, page.stepOnLane(0.0))
                assertEquals(listOf("takeoff", "landing"), received)
            }
        }
    }

    private fun captureAndPublish(surface: ExperienceSurfaceHost, step: ExperienceInteractivePage.Step) {
        surface.javaClass.getDeclaredMethod("captureLayerStep", ExperienceInteractivePage.Step::class.java)
            .apply { isAccessible = true }.invoke(surface, step)
        set(surface, "firstFrameComposed", true)
        repeat(2) { surface.javaClass.getDeclaredMethod("publishSteps").apply { isAccessible = true }.invoke(surface) }
    }

    @Test fun `ordinary layer frames avoid catalog queries and save frames still capture answers`() = runBlocking {
        withSurface { surface, layer, native, lane ->
            lane.blockingCall {
                set(surface, "saveDescriptor", Json.parseToJsonElement("""{"responses":{"feedback":{"model":"B",
                    "fields":[{"key":"keptValue","type":"number"}]}}}""").jsonObject)
                layer.insertOnLane("b", 1)
                val ordinary = NuxiePlayerStepOutcome(false, emptyList(), emptyList(), emptyList(), emptyList())
                val step = layer.captureOnLane(ordinary, 1u)
                val capture = surface.javaClass.getDeclaredMethod("captureLayerStep", ExperienceInteractivePage.Step::class.java)
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
            lane.blockingCall {}
            assertEquals(failure.message, runCatching { waiter.await() }.exceptionOrNull()?.message)
            var retired = 0
            var completed = 0
            val retirement = ExperienceScreenRetirement { completed++ }
            surface.release(onMediaReleased = { retired++; retirement.mediaReleased() }); lane.blockingCall {}
            retirement.nativeReleased()
            surface.release(onMediaReleased = { retired++; retirement.mediaReleased() }); lane.blockingCall {}
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
            lane.blockingCall { set(surface, "running", running); native.failWrites = true }
            val write = async { runCatching { surface.applyLayerTransition {
                if (inserting) it.insertOnLane("b", 1)
                else it.setPhasesOnLane(listOf("a" to ExperienceScreenLifecycle.Phase.EXITING))
            } } }
            runCurrent(); lane.blockingCall {}
            if (running) lane.blockingCall {
                runCatching { surface.javaClass.getDeclaredMethod("applyNextTransitionWrite").apply { isAccessible = true }.invoke(surface) }
            }
            // Native failure cleanup crosses the lane, coroutine dispatcher and UI queue.
            repeat(4) {
                lane.blockingCall {}; runCurrent()
                org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            }
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
            lane.blockingCall { set(surface, "running", true) }
            val phase = async { surface.applyLayerTransition {
                it.setPhasesOnLane(listOf("a" to ExperienceScreenLifecycle.Phase.EXITING))
            } }
            runCurrent(); lane.blockingCall {}
            advanceTimeBy(251); runCurrent(); lane.blockingCall {}; runCurrent()
            try {
                assertTrue("A missing window must not hold navigation before its watchdog", phase.isCompleted)
                phase.await()
                assertEquals(NuxieViewModelScalarValue.StringValue("exiting"), layer.lifecycle("a")["screen/phase"])
                runBlocking { surface.layerOperation { it.insertOnLane("b", 1) } }
            } finally { phase.cancelAndJoin() }
        }
    }

    @Test fun `perpetually pending native presentation is retired within the phase budget`() = runTest {
        withSurface { surface, layer, native, lane ->
            val renderer = NuxieAndroidVulkanRenderer(900, native)
            val window = NuxieRuntimeWindow(901, native)
            lane.blockingCall {
                set(surface, "running", true); set(surface, "renderer", renderer); set(surface, "window", window)
                repeat(3) { assertEquals(4, renderer.renderAndPresent(layer.player, window, 0, 1f)) }
                set(surface, "pendingPresentation", true)
            }
            val phase = async { surface.applyLayerTransition {
                it.insertOnLane("b", 1)
                it.setPhasesOnLane(listOf("a" to ExperienceScreenLifecycle.Phase.EXITING, "b" to ExperienceScreenLifecycle.Phase.ENTERING))
            } }
            runCurrent(); lane.blockingCall {}
            advanceTimeBy(251); runCurrent(); lane.blockingCall {}; runCurrent()
            try {
                assertTrue("Healthy pending renders must not hold the phase barrier forever", phase.isCompleted)
                phase.await()
                assertEquals(1, native.detaches)
                assertFalse(field(surface, "pendingPresentation") as Boolean)
                assertEquals(listOf("a", "b"), layer.mountedScreenIds())
                runBlocking { surface.removeLayerRow("a") }
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
                {"id":"b","defaultViewModelName":"B"},{"id":"c","defaultViewModelName":"C"}]},"render":{"screens":[{"id":"a","artboardName":"A"},{"id":"b","artboardName":"B"},{"id":"c","artboardName":"C"}],"layerHost":{"artboardName":"LayerList","list":"screens"},
                "transitions":[{"id":"pair","kind":"page","sourceScreenId":"a","destinationScreenId":"b","durationMs":100,
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
                runBlocking { mounted.prepareRow(target, listener) }
                lane.blockingCall { set(surface, "running", true) }
                navigation = async { mounted.navigateLayer(target, false) }
                // Drain real lane handoffs between virtual clock increments. No frame is supplied.
                repeat(32) {
                    repeat(4) { runCurrent(); lane.blockingCall {} }
                    advanceTimeBy(25)
                }
                repeat(4) { runCurrent(); lane.blockingCall {} }
                assertTrue("The mounted $style step must activate despite missing phase frames", navigation.isCompleted)
                navigation.await()
                assertEquals(listOf("b"), layer.mountedScreenIds())
                assertTrue(surface.isEnabled)
                assertEquals(1, org.robolectric.shadows.ShadowLog.getLogsForTag("ExperiencePageStep").count {
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

    @Test fun `late page text overlay uses delivered bounds without another layout delivery`() = runBlocking {
        for (deliverBeforeNavigation in listOf(true, false)) withSurface { surface, page, _, _ ->
            val activity = org.robolectric.Robolectric.buildActivity(android.app.Activity::class.java).setup()
            val scene = java.io.File.createTempFile("page-overlay-bounds", ".riv").apply { writeBytes(byteArrayOf(1)) }
            val baseline = field(page, "descriptor") as JsonObject
            val render = baseline.getValue("render").jsonObject
            val input = textInputDescriptor().getValue("render").jsonObject.getValue("textInputs").jsonArray.single().jsonObject
            val descriptor = JsonObject(baseline + ("render" to JsonObject(render + ("textInputs" to
                JsonArray(listOf(JsonObject(input + ("screenId" to JsonPrimitive("b")))))))))
            val source = PreparedPresentation(scene, "A", 0, PresentationShell.FullScreen(), "a", descriptor,
                artboardSize = ExperienceArtboardSize(200f, 100f), pageHistory = listOf("a"))
            val target = source.copy(screenId = "b", artboardName = "B", pageHistory = listOf("a", "b"))
            val delivered = mutableListOf<ExperienceArtboardSize>()
            val listener = object : ExperienceSurfaceHost.Listener {
                override fun onFirstFrame() = Unit
                override fun onFailure(error: ExperiencePresentationException) { throw AssertionError(error) }
                override fun onLayoutBounds(bounds: ExperienceArtboardSize) { delivered += bounds }
            }
            val mounted = ExperienceMountedScreen(activity.get(), source, listener, { throw AssertionError(it) })
            val ownedLane = field(mounted, "lane") as NuxieRuntimeLane
            val pageListener = field(mounted.surface, "listener") as ExperienceSurfaceHost.Listener
            set(mounted, "surface", surface)
            set(surface, "listener", pageListener)
            set(mounted, "content", android.widget.FrameLayout(activity.get()))
            val bounds = ExperienceArtboardSize(400f, 300f, -30f, -50f)
            try {
                assertTrue(ExperienceTextInput.forScreen(descriptor, "a").isEmpty())
                assertNull(field(mounted, "textOverlay"))
                if (deliverBeforeNavigation) pageListener.onLayoutBounds(bounds)
                mounted.prepareRow(target, listener)
                mounted.navigateLayer(target, false)
                val overlay = field(mounted, "textOverlay") as ExperienceTextInputOverlay
                overlay.layout(0, 0, 400, 300)
                val transform = NuxieTextRunGeometry.Transform(1f, 0f, 0f, 1f, 0f, 0f)
                val fieldBounds = NuxieTextRunGeometry.Bounds(0f, 0f, 100f, 40f)
                pageListener.onNativeTextFields(listOf(ExperienceNativeTextField(
                    ExperienceTextFieldTarget("name", 10), 10, 1,
                    NativeSemanticNode(10, -1, 0, NativeSemanticRole.TEXT_FIELD, 0, 0, 0, 0,
                        0f, 0f, 100f, 40f, "Name", "", ""),
                    NuxieTextInputGeometry(1u, transform, fieldBounds, NuxieTextRunGeometry.Layout(transform, fieldBounds),
                        20f, false, false), "",
                    NuxieViewModelSnapshot.fromNative(NativeViewModelSnapshot(10,
                        arrayOf(NativeViewModelSnapshotInstance(10, 0)), emptyArray())))))
                val editor = overlay.semanticViews().getValue(10)
                val container = editor.parent as android.view.View
                val density = activity.get().resources.displayMetrics.density
                if (!deliverBeforeNavigation) {
                    assertEquals(0f, container.x, 0.001f)
                    assertEquals(0f, container.y, 0.001f)
                    pageListener.onLayoutBounds(bounds)
                }
                assertEquals(30f * density, container.x, 0.001f)
                assertEquals(50f * density, container.y, 0.001f)
                assertEquals(listOf(bounds), delivered)
                assertTrue(editor.isEnabled)
            } finally {
                mounted.close(false) {}
                assertTrue(ownedLane.awaitQuiescence(5_000))
                activity.pause().stop().destroy(); scene.delete()
            }
        }
    }

    @Test fun `recreated page Back positions missing destination below current Screen for visible and covered cuts`() = runBlocking {
        for (covered in listOf(false, true)) withSurface(prepareTarget = false) { surface, page, native, lane ->
            val activity = org.robolectric.Robolectric.buildActivity(android.app.Activity::class.java).setup()
            val scene = java.io.File.createTempFile("page-recreated-back", ".riv").apply { writeBytes(byteArrayOf(1)) }
            val descriptor = field(page, "descriptor") as JsonObject
            // Warm recreation restores only current A, retaining the earlier B visit in history.
            val source = PreparedPresentation(scene, "A", 0, PresentationShell.FullScreen(), "a", descriptor,
                pageHistory = listOf("b", "a"))
            val target = source.copy(screenId = "b", artboardName = "B", pageHistory = listOf("b"),
                screenLifecycle = ExperienceScreenLifecycle())
            val listener = object : ExperienceSurfaceHost.Listener {
                override fun onFirstFrame() = Unit
                override fun onFailure(error: ExperiencePresentationException) { throw AssertionError(error) }
            }
            val mounted = ExperienceMountedScreen(activity.get(), source, listener, { throw AssertionError(it) })
            val ownedLane = field(mounted, "lane") as NuxieRuntimeLane
            set(mounted, "surface", surface)
            try {
                mounted.prepareRow(source, listener)
                assertEquals(listOf("a"), page.mountedScreenIds())
                lane.blockingCall {
                    page.stepOnLane(0.0)
                    native.traceListMutations = true
                    native.trace.clear()
                }
                if (covered) mounted.cutCoveredPage(target, listener)
                else {
                    mounted.prepareRow(target, listener)
                    mounted.navigateLayer(target, isBack = true)
                }
                assertEquals(listOf("insert:1", "move:1:0", "begin:B:null"), native.trace.take(3))
                assertFalse(native.trace.any { it.startsWith("step:") })
                assertEquals(listOf("b"), page.mountedScreenIds())
            } finally {
                native.onStep = {}
                mounted.close(false) {}
                assertTrue(ownedLane.awaitQuiescence(5_000))
                activity.pause().stop().destroy(); scene.delete()
            }
        }
    }

    @Test fun `covered skipping Back stays hidden until one reveal and ordinary pop enters once`() = runBlocking {
        for (skip in listOf(false, true)) withSurface { surface, page, native, lane ->
            val activity = org.robolectric.Robolectric.buildActivity(android.app.Activity::class.java).setup()
            val scene = java.io.File.createTempFile("page-covered-cut", ".riv").apply { writeBytes(byteArrayOf(1)) }
            val descriptor = field(page, "descriptor") as JsonObject
            val source = PreparedPresentation(scene, "B", 0, PresentationShell.FullScreen(), "b", descriptor,
                pageHistory = listOf("a", "b"))
            val target = source.copy(screenId = "a", artboardName = "A", pageHistory = listOf("a"),
                screenLifecycle = ExperienceScreenLifecycle())
            val listener = object : ExperienceSurfaceHost.Listener {
                override fun onFirstFrame() = Unit
                override fun onFailure(error: ExperiencePresentationException) { throw AssertionError(error) }
            }
            val mounted = ExperienceMountedScreen(activity.get(), source, listener, { throw AssertionError(it) })
            val ownedLane = field(mounted, "lane") as NuxieRuntimeLane
            set(mounted, "surface", surface)
            try {
                mounted.prepareRow(source, listener)
                lane.blockingCall {
                    page.insertOnLane("b", 1)
                    page.setPhasesOnLane(listOf("a" to ExperienceScreenLifecycle.Phase.HIDDEN,
                        "b" to ExperienceScreenLifecycle.Phase.HIDDEN))
                    native.writes.clear()
                    native.trace.clear()
                }
                val id = if (skip) "a" else "b"
                val before = page.lifecycle(id)["screen/appearances"]
                if (skip) {
                    mounted.cutCoveredPage(target, listener)
                    assertTrue(native.writes.filter { it.second.path == "screen/phase" }.all { it.second.integerValue == 0L })
                    assertEquals(before, page.lifecycle(id)["screen/appearances"])
                    assertTrue(native.trace.any { it.startsWith("begin:") })
                    assertTrue(native.trace.any { it.startsWith("end:") })
                }
                mounted.enterPage()
                lane.blockingCall {}
                val count = (before as NuxieViewModelScalarValue.NumberValue).value
                assertEquals(NuxieViewModelScalarValue.NumberValue(count + 1), page.lifecycle(id)["screen/appearances"])
                mounted.activate()
                lane.blockingCall {}
                assertEquals(NuxieViewModelScalarValue.StringValue("active"), page.lifecycle(id)["screen/phase"])
                assertEquals(NuxieViewModelScalarValue.NumberValue(count + 1), page.lifecycle(id)["screen/appearances"])
            } finally {
                mounted.close(false) {}
                assertTrue(ownedLane.awaitQuiescence(5_000))
                activity.pause().stop().destroy(); scene.delete()
            }
        }
    }

    @Test fun `cut swap keeps the incoming completion waiter alive until its arrival`() = runBlocking {
        withSurface { surface, page, native, _ ->
            val activity = org.robolectric.Robolectric.buildActivity(android.app.Activity::class.java).setup()
            val scene = java.io.File.createTempFile("page-cut-waiter", ".riv").apply { writeBytes(byteArrayOf(1)) }
            val baseline = field(page, "descriptor") as JsonObject
            val render = baseline.getValue("render").jsonObject
            val descriptor = JsonObject(baseline + ("render" to JsonObject(render + ("transitions" to
                Json.parseToJsonElement("""[{"id":"pair","kind":"page","sourceScreenId":"a","destinationScreenId":"b",
                    "durationMs":100,"source":{"completeEventName":"leave"},"destination":{"completeEventName":"arrive"}}]""")))))
            val source = PreparedPresentation(scene, "A", 0, PresentationShell.FullScreen(), "a", descriptor,
                pageHistory = listOf("a"))
            val target = source.copy(screenId = "b", artboardName = "B", pageHistory = listOf("a", "b"),
                transition = Json.parseToJsonElement("""{"type":"none","transitionId":"pair"}""").jsonObject)
            val listener = object : ExperienceSurfaceHost.Listener {
                override fun onFirstFrame() = Unit
                override fun onFailure(error: ExperiencePresentationException) { throw AssertionError(error) }
            }
            val mounted = ExperienceMountedScreen(activity.get(), source, listener, { throw AssertionError(it) })
            val ownedLane = field(mounted, "lane") as NuxieRuntimeLane
            set(mounted, "surface", surface)
            try {
                mounted.prepareRow(target, listener)
                native.events = arrayOf(
                    NativeRuntimeEvent(0, 0, "leave", "", "", 0f, emptyArray(), 200),
                    NativeRuntimeEvent(0, 0, "arrive", "", "", 0f, emptyArray(), 300))
                withTimeout(5_000) { mounted.navigateLayer(target, false) }
                assertEquals(listOf("a", "b"), page.mountedScreenIds())
                assertTrue(page.isCollapsed("a"))
                assertEquals(NuxieViewModelScalarValue.StringValue("active"), page.lifecycle("b")["screen/phase"])
            } finally {
                mounted.close(false) {}
                assertTrue(ownedLane.awaitQuiescence(5_000))
                activity.pause().stop().destroy(); scene.delete()
            }
        }
    }

    @Test fun `Back cut cancellation restores a fresh source and permits a following step`() = runBlocking {
        cancelledBackCut(skipping = false)
    }

    @Test fun `skipping Back cut cancellation retains skipped rows for a later Back`() = runBlocking {
        cancelledBackCut(skipping = true)
    }

    @Test fun `together cancellation after stepEnd cuts back before retiring target`() = runBlocking {
        cancelledEndedStep(reducedMotion = false)
    }

    @Test fun `reduced motion cancellation after stepEnd cuts back before retiring target`() = runBlocking {
        cancelledEndedStep(reducedMotion = true)
    }

    private suspend fun CoroutineScope.cancelledEndedStep(reducedMotion: Boolean) {
        for (keepTarget in listOf(false, true)) withSurface { surface, page, native, lane ->
            native.traceListMutations = true
            var activated = false
            var batches = 0
            val navigation = launch {
                try {
                    ExperiencePageStep.perform("a", "b", ai.nuxie.sdk.experiences.ExperiencePageHostContract.Style.MAGIC_MOVE,
                        false, ExperienceScreenTransitionPlan.Custom("pair", 100, true, "leave", "arrive"),
                        ExperiencePageStep.Exit(null, null), reducedMotion, object : ExperiencePageStep.Operations {
                            val mutations = mutableListOf<(ExperienceInteractivePage) -> Unit>()
                            var ended = false
                            override fun input(enabled: Boolean) = Unit
                            override suspend fun prepare() = Unit
                            override suspend fun register(screenId: String, event: String): Deferred<Unit> = CompletableDeferred(Unit)
                            override suspend fun batch(present: Boolean, watchdogMs: Long, writes: suspend () -> Unit) {
                                writes()
                                surface.layerOperation { layer -> mutations.forEach { it(layer) }; mutations.clear() }
                                batches++
                                if (ended) currentCoroutineContext().cancel()
                            }
                            override suspend fun takeIncoming() { mutations += { it.takeIncomingOnLane("b", "a", false) } }
                            override suspend fun phases(values: List<Pair<String, ExperienceScreenLifecycle.Phase>>, transition: String) {
                                mutations += { it.setPhasesOnLane(values, transition) }
                            }
                            override suspend fun begin(transition: String?) { mutations += { it.beginOnLane("b", transition) } }
                            override suspend fun end(transition: String?) { mutations += { it.endOnLane("b", transition) }; ended = true }
                            // Exercise both a retained source and one removed by the completed batch.
                            override suspend fun retire() { mutations += { it.retireOnLane(if (keepTarget) setOf("b") else setOf("a", "b"), "b") } }
                            override suspend fun present() = Unit
                            override suspend fun hidden() = Unit
                            override suspend fun active() { activated = true }
                        })
                } finally {
                    withContext(NonCancellable) {
                        lane.blockingCall { assertNull(field(page, "liveStep")); native.trace.clear() }
                        surface.restoreLayerRow("a", "b", keepTarget)
                    }
                }
            }
            navigation.join()
            assertTrue(navigation.isCancelled)
            assertFalse(activated)
            assertEquals(if (reducedMotion) 1 else 2, batches)
            assertEquals(listOf("begin:A:null", "end:A:null"), native.trace.filter { it.startsWith("begin:") || it.startsWith("end:") })
            val end = native.trace.indexOf("end:A:null")
            assertTrue(native.trace.indexOf(if (keepTarget) "collapse:1:true" else "remove") > end)
            assertEquals(if (keepTarget) listOf("a", "b") else listOf("a"), page.mountedScreenIds())
            assertFalse(page.isCollapsed("a"))
            assertEquals(NuxieViewModelScalarValue.StringValue("active"), page.lifecycle("a")["screen/phase"])
            if (keepTarget) {
                assertTrue(page.isCollapsed("b"))
                assertEquals(NuxieViewModelScalarValue.StringValue("hidden"), page.lifecycle("b")["screen/phase"])
                assertTrue(native.trace.indexOf("move:1:0") < native.trace.indexOf("begin:A:null"))
            }
            surface.layerOperation {
                it.prepareOnLane("b")
                it.takeIncomingOnLane("b", "a", false)
                it.beginOnLane("b", null); it.endOnLane("b", null)
                it.retireOnLane(setOf("a", "b"), "b")
                it.setPhasesOnLane(listOf("a" to ExperienceScreenLifecycle.Phase.HIDDEN, "b" to ExperienceScreenLifecycle.Phase.ACTIVE))
            }
            assertEquals(NuxieViewModelScalarValue.StringValue("active"), page.lifecycle("b")["screen/phase"])
        }
    }

    private suspend fun CoroutineScope.cancelledBackCut(skipping: Boolean) {
        withSurface { surface, page, native, lane ->
            val activity = org.robolectric.Robolectric.buildActivity(android.app.Activity::class.java).setup()
            val scene = java.io.File.createTempFile("page-back-abort", ".riv").apply { writeBytes(byteArrayOf(1)) }
            val baseline = field(page, "descriptor") as JsonObject
            val render = baseline.getValue("render").jsonObject
            val sourceId = if (skipping) "c" else "b"
            val descriptor = JsonObject(baseline + ("render" to JsonObject(render + ("transitions" to
                Json.parseToJsonElement("""[{"id":"back","kind":"page","sourceScreenId":"$sourceId","destinationScreenId":"a",
                    "durationMs":60000,"source":{"completeEventName":"leave"},"destination":{"completeEventName":"arrive"}}]""")))))
            val initial = PreparedPresentation(scene, "A", 0, PresentationShell.FullScreen(), "a", descriptor,
                pageHistory = listOf("a"))
            val listener = object : ExperienceSurfaceHost.Listener {
                override fun onFirstFrame() = Unit
                override fun onFailure(error: ExperiencePresentationException) { throw AssertionError(error) }
            }
            val mounted = ExperienceMountedScreen(activity.get(), initial, listener, { throw AssertionError(it) })
            val ownedLane = field(mounted, "lane") as NuxieRuntimeLane
            set(mounted, "surface", surface)
            var navigation: Job? = null
            try {
                val b = initial.copy(screenId = "b", artboardName = "B", pageHistory = listOf("a", "b"), retainedViewModel = AtomicReference())
                mounted.prepareRow(b, listener)
                mounted.navigateLayer(b, false)
                val staleB = page.snapshot("b")
                page.applyValues("b", mapOf("keptValue" to NuxieViewModelScalarValue.NumberValue(42.0)))
                val oldB = page.snapshot("b").nativeRootInstanceId
                val source = if (skipping) b.copy(screenId = "c", artboardName = "C", pageHistory = listOf("a", "b", "c"), retainedViewModel = AtomicReference()) else b
                if (skipping) {
                    mounted.prepareRow(source, listener)
                    mounted.navigateLayer(source, false)
                    page.applyValues("c", mapOf("keptValue" to NuxieViewModelScalarValue.NumberValue(73.0)))
                }
                val oldSource = page.snapshot(sourceId).nativeRootInstanceId
                val target = initial.copy(transition = Json.parseToJsonElement("""{"type":"none","transitionId":"back"}""").jsonObject)
                mounted.prepareRow(target, listener)
                native.events = arrayOf(NativeRuntimeEvent(0, 0, "leave", "", "", 0f, emptyArray(), oldSource))
                navigation = launch {
                    try { mounted.navigateLayer(target, true) }
                    finally { withContext(NonCancellable) { mounted.restoreRow(source, "a") } }
                }
                // Use real time: a test scheduler can skip the arrival watchdog
                // while the caller suspends on the separate native lane.
                withTimeout(5_000) {
                    while (page.mountedScreenIds() != listOf("a")) delay(1)
                }
                lane.blockingCall { assertEquals("a" to "back", field(page, "liveStep")) }
                assertTrue(navigation.isActive)
                assertTrue(native.freed.contains("values:$oldSource"))
                native.writes.clear()
                navigation.cancelAndJoin()
                val fresh = page.snapshot(sourceId).nativeRootInstanceId
                assertNotEquals(oldSource, fresh)
                assertEquals(listOf(sourceId, "a"), page.mountedScreenIds())
                assertTrue(page.isCollapsed("a"))
                assertEquals(NuxieViewModelScalarValue.StringValue("active"), page.lifecycle(sourceId)["screen/phase"])
                assertEquals(if (skipping) 73.0 else 42.0,
                    native.writes.single { it.first == fresh && it.second.path == "keptValue" }.second.numberValue.toDouble(), 0.0)
                assertFalse(native.writes.any { it.first == oldSource || it.first == oldB })
                assertEquals(source.pageHistory, (field(mounted, "prepared") as PreparedPresentation).pageHistory)
                assertTrue((field(mounted, "rows") as Map<*, *>).keys.containsAll(source.pageHistory))
                native.events = emptyArray()
                if (skipping) {
                    assertEquals(oldB, page.snapshot("b").nativeRootInstanceId)
                    mounted.prepareRow(b.copy(retainedViewModel = AtomicReference(staleB)), listener)
                    val freshB = page.snapshot("b").nativeRootInstanceId
                    assertNotEquals(oldB, freshB)
                    assertEquals(42.0, native.writes.single { it.first == freshB && it.second.path == "keptValue" }.second.numberValue.toDouble(), 0.0)
                    native.traceListMutations = true
                    native.trace.clear()
                    mounted.navigateLayer(b, true)
                    assertTrue(native.trace.indexOf("move:2:0") in 0 until native.trace.indexOf("begin:B:null"))
                    assertEquals(listOf("b", "a"), page.mountedScreenIds())
                } else {
                    mounted.prepareRow(initial, listener)
                    mounted.navigateLayer(initial, true)
                    assertEquals(listOf("a"), page.mountedScreenIds())
                }
                assertFalse(native.writes.any { it.first == oldSource || it.first == oldB })
                assertTrue(native.allocations.all { it.second == null })
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
            runCurrent(); lane.blockingCall {}
            advanceTimeBy(251); runCurrent(); lane.blockingCall {}; runCurrent()
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
            navigation.cancel(); runCurrent(); lane.blockingCall {}
            advanceTimeBy(251); repeat(4) { runCurrent(); lane.blockingCall {} }
            try {
                assertTrue("Abort must finish while its parent is cancelled", navigation.isCompleted)
                assertTrue(restored)
                @Suppress("UNCHECKED_CAST")
                val retained = field(surface, "layerRetainedValues") as Map<String, *>
                assertFalse("Cancelled preparation must not mutate retained row ownership", retained.containsKey("b"))
                assertFalse("Cancelled row preparation must never run late", prepared)
                assertEquals(1, native.detaches)
                assertEquals(listOf("a"), layer.mountedScreenIds())
                runBlocking {
                    surface.prepareLayerRow(target)
                    surface.layerOperation { it.insertOnLane("b", 1) }
                    surface.restoreLayerRow("a", "b")
                }
            } finally { retireAndDrain(surface, lane); navigation.cancelAndJoin(); scene.delete() }
        }
    }

    @Test fun `layer phase preserves queued environment updates for the next frame and their completion`() = runTest {
        withSurface(globals = true) { surface, layer, native, lane ->
            installPendingSubmission(surface, native, lane)
            val update = surface.updateRuntimeValues(mapOf("env/reduceMotion" to NuxieViewModelScalarValue.BooleanValue(true)))
            lane.blockingCall {}
            assertFalse(update.isCompleted)
            val phase = async { surface.applyLayerTransition {
                it.setPhasesOnLane(listOf("a" to ExperienceScreenLifecycle.Phase.EXITING))
            } }
            runCurrent(); lane.blockingCall {}
            var reducedAtStep = false
            native.onStep = { reducedAtStep = native.writes.any { it.first == 500L && it.second.path == "reduceMotion" && it.second.boolValue } }
            lane.blockingCall {
                val layout = checkNotNull(ExperienceSurfaceLayout.create(100, 100, 1f))
                set(surface, "surfaceLayout", layout); set(surface, "appliedLayout", layout)
                set(surface, "pendingPresentation", false); set(surface, "attached", true)
                native.presentation = 1
            }
            surface.doFrame(1_000_000_000L)
            lane.blockingCall {}; runCurrent()
            try {
                assertTrue("The queued update must complete with the phase frame", update.isCompleted)
                assertTrue(update.await())
                assertTrue("The next native step must see the pending environment update", reducedAtStep)
                assertEquals(NuxieViewModelScalarValue.BooleanValue(true), layer.lifecycle("a")["env/reduceMotion"])
                phase.await()
            } finally { retireAndDrain(surface, lane); phase.cancelAndJoin() }
        }
    }

    @Test fun `prepared Screen owns the lifecycle of retained and reinserted rows`() = runBlocking {
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

    @Test fun `stale preparation lifecycle cannot reset a retained row appearance history`() = runBlocking {
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
        native: ExperienceInteractivePageTest.RecordingNative, lane: NuxieRuntimeLane) {
        lane.blockingCall {
            val renderer = NuxieAndroidVulkanRenderer(900, native)
            val window = NuxieRuntimeWindow(901, native)
            set(surface, "renderer", renderer); set(surface, "window", window)
            set(surface, "running", true)
            val player = field(surface, "player") as NuxieRuntimePlayer
            assertEquals(4, renderer.renderAndPresent(player, window, 0, 1f))
            set(surface, "pendingPresentation", true)
        }
    }

    private suspend fun retireAndDrain(surface: ExperienceSurfaceHost, lane: NuxieRuntimeLane) = lane.blockingCall {
        surface.javaClass.getDeclaredMethod("retireSubmission").apply { isAccessible = true }.invoke(surface)
        surface.javaClass.getDeclaredMethod("drainTextWrites").apply { isAccessible = true }.invoke(surface)
    }

    private fun field(owner: Any, name: String): Any? = owner.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(owner)
    private fun set(owner: Any, name: String, value: Any?) = owner.javaClass.getDeclaredField(name).apply { isAccessible = true }.set(owner, value)
}
