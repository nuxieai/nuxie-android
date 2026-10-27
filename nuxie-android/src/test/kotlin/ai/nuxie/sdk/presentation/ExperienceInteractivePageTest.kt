package ai.nuxie.sdk.presentation

import ai.nuxie.sdk.experiences.ExperiencePageHostContract.Style
import ai.nuxie.sdk.runtime.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class ExperienceInteractivePageTest {
    @Test fun `new rows append on top and kept rows expand in place with the same live instance`() = runBlocking {
        withLayer { page, native ->
            page.insertIncoming("a", Style.NONE, false)
            page.insertIncoming("b", Style.MAGIC_MOVE, false)
            val identity = page.snapshot("a").nativeRootInstanceId
            page.setCollapsed("a", true)
            native.writes.clear()
            page.insertIncoming("a", Style.MAGIC_MOVE, false)
            assertEquals(listOf("a", "b"), page.mountedScreenIds())
            assertEquals(identity, page.snapshot("a").nativeRootInstanceId)
            assertFalse(page.isCollapsed("a"))
            assertTrue(native.writes.isEmpty())
            assertEquals(listOf("collapse:0:true", "collapse:0:false"), native.trace.filter { it.startsWith("collapse:") })
            assertEquals(2, native.allocated)
            assertEquals(1, native.players)
        }
    }

    @Test fun `new forward rows append on top and new Back rows move below source without advancing`() = runBlocking {
        for (isBack in listOf(false, true)) withLayer { page, native ->
            page.insertIncoming("a", Style.NONE, false)
            native.traceListMutations = true
            native.trace.clear()
            page.insertIncoming("b", Style.MAGIC_MOVE, isBack, source = "a")
            assertEquals(if (isBack) listOf("b", "a") else listOf("a", "b"), page.mountedScreenIds())
            assertEquals(if (isBack) listOf("insert:1", "move:1:0")
                else listOf("insert:1"), native.trace)
        }
    }

    @Test fun `Back removes unreachable rows after end and remaining indexes follow list removals`() = runBlocking {
        withLayer { page, native ->
            page.insertIncoming("a", Style.NONE, false)
            page.insertIncoming("b", Style.NONE, false)
            page.setCollapsed("a", true)
            native.trace.clear()
            page.insertIncoming("a", Style.NONE, true)
            page.begin("a", "pair")
            page.end("a", "pair"); page.retire(setOf("a"), "a")
            assertEquals(listOf("a"), page.mountedScreenIds())
            assertEquals(listOf("collapse:0:false", "begin:A:pair", "end:A:pair", "remove"), native.trace)
            page.insertIncoming("b", Style.NONE, false)
            page.remove("a")
            native.trace.clear()
            page.setCollapsed("b", true)
            assertEquals(listOf("collapse:0:true"), native.trace)
            assertTrue(page.snapshot("b").nativeRootInstanceId in native.collapsedOwners)
            assertFalse(native.writes.any { it.second.kind == NuxieViewModelMutationKind.LIST_SET })
        }
    }

    @Test fun `retained rows move above on navigate and below on Back without allocating or removing`() = runBlocking {
        withLayer { page, native ->
            page.insertIncoming("a", Style.NONE, false)
            page.insertIncoming("b", Style.NONE, false)
            val identity = page.snapshot("a").nativeRootInstanceId
            page.setCollapsed("a", true)
            native.writes.clear(); native.trace.clear()
            page.takeIncoming("a", "b", false)
            assertEquals(listOf("b", "a"), page.mountedScreenIds())
            assertEquals(0L to 1L, native.writes.single().second.let { it.index to it.secondIndex })
            assertEquals(NuxieViewModelMutationKind.LIST_MOVE, native.writes.single().second.kind)
            assertEquals(listOf("collapse:1:false"), native.trace)
            page.setCollapsed("a", true)
            native.writes.clear(); native.trace.clear()
            page.takeIncoming("a", "b", true)
            assertEquals(listOf("a", "b"), page.mountedScreenIds())
            assertEquals(1L to 0L, native.writes.single().second.let { it.index to it.secondIndex })
            assertEquals(listOf("collapse:0:false"), native.trace)
            assertEquals(identity, page.snapshot("a").nativeRootInstanceId)
            assertEquals(2, native.allocated)
        }
    }

    @Test fun `three rows revisit and skipping Back collapse at the new index`() = runBlocking {
        withLayer { page, native ->
            for (id in listOf("a", "b", "c")) page.insertIncoming(id, Style.NONE, false)
            page.setCollapsed("a", true); page.setCollapsed("b", true)
            native.trace.clear()
            page.takeIncoming("a", "c", false)
            assertEquals(listOf("b", "c", "a"), page.mountedScreenIds())
            assertEquals(listOf("collapse:2:false"), native.trace)
            page.setCollapsed("c", true)
            native.trace.clear()
            page.takeIncoming("c", "a", true)
            assertEquals(listOf("b", "c", "a"), page.mountedScreenIds())
            assertEquals(listOf("collapse:1:false"), native.trace)
            native.trace.clear()
            page.takeIncoming("b", "a", true)
            assertEquals(listOf("c", "b", "a"), page.mountedScreenIds())
            assertEquals(listOf("collapse:1:false"), native.trace)
            page.retire(setOf("b", "c"), "b")
            assertEquals(listOf("c", "b"), page.mountedScreenIds())
            assertEquals(listOf("collapse:0:true", "remove"), native.trace.takeLast(2))
            assertEquals(3, native.allocated)
        }
    }

    @Test fun `a release without the fixed page artboard is refused`() = runBlocking {
        val native = RecordingNative().apply { pageAvailable = false }
        assertTrue(runCatching { withLayer(native) { _, _ -> fail("Missing page admitted") } }
            .exceptionOrNull()?.message.orEmpty().contains("Page host artboard is unavailable"))
        assertEquals(0, native.players)
    }

    @Test fun `step begin refuses a collapsed incoming row before calling native`() = runBlocking {
        withLayer { page, native ->
            page.insertIncoming("a", Style.NONE, false)
            page.setCollapsed("a", true)
            native.trace.clear()
            assertTrue(runCatching { page.begin("a", null) }.exceptionOrNull() is IllegalStateException)
            assertTrue(native.trace.isEmpty())
            page.insertIncoming("a", Style.NONE, false)
            page.begin("a", null); page.end("a", null)
            assertEquals(listOf("collapse:0:false", "begin:A:null", "end:A:null"), native.trace)
        }
    }

    @Test fun `takeoff and landing host commands are delivered once in the next whole page step`() = runBlocking {
        withLayer { page, native ->
            page.insertIncoming("a", Style.NONE, false)
            page.insertIncoming("b", Style.NONE, false)
            native.trace.clear()
            page.begin("b", "pair")
            page.end("b", "pair")
            assertFalse(native.trace.any { it.startsWith("step:") })
            val next = page.step(0.0)
            assertEquals(listOf("takeoff", "landing"), next.native.hostCommands.map { it.name })
            assertEquals(next.native.hostCommands, next.unattributedHostCommands)
            assertTrue(next.rows.values.all { it.outcome.hostCommands.isEmpty() })
            assertTrue(page.step(0.0).native.hostCommands.isEmpty())
        }
    }

    @Test fun `pending events and commands survive insertion and expansion until first ordinary frame`() = runBlocking {
        withLayer { page, native ->
            page.insertIncoming("a", Style.NONE, false)
            page.insertIncoming("b", Style.NONE, false)
            page.setCollapsed("a", true)
            native.events = arrayOf(event("pending", 200))
            native.tracePhases = true
            native.traceListMutations = true
            native.trace.clear()
            page.takeIncoming("a", "b", false)
            page.setPhases(listOf("b" to ExperienceScreenLifecycle.Phase.EXITING,
                "a" to ExperienceScreenLifecycle.Phase.ENTERING), "pair")
            page.begin("a", "pair")
            assertEquals(listOf("move:0:1", "collapse:1:false", "phase:300:3", "phase:200:1", "begin:A:pair"), native.trace)
            val first = page.step(0.016)
            assertEquals(listOf("pending"), first.rows.getValue("a").outcome.events.map { it.name })
            assertEquals(listOf("takeoff"), first.native.hostCommands.map { it.name })
            val second = page.step(0.016)
            assertTrue(second.native.events.isEmpty())
            assertTrue(second.native.hostCommands.isEmpty())
            page.end("a", "pair")
            assertEquals(listOf("landing"), page.step(0.016).native.hostCommands.map { it.name })
            assertTrue(page.step(0.016).native.hostCommands.isEmpty())
        }
    }

    @Test fun `new page enters before reveal while a restored covered page stays hidden`() = runBlocking {
        withLayer { page, _ ->
            page.prepare("a")
            page.mountInitial("a", entering = false)
            assertEquals(NuxieViewModelScalarValue.StringValue("hidden"), page.lifecycle("a")["screen/phase"])
            assertEquals(NuxieViewModelScalarValue.NumberValue(0.0), page.lifecycle("a")["screen/appearances"])
        }
        withLayer { page, _ ->
            page.prepare("a")
            page.mountInitial("a", entering = true)
            assertEquals(NuxieViewModelScalarValue.StringValue("entering"), page.lifecycle("a")["screen/phase"])
            assertEquals(NuxieViewModelScalarValue.NumberValue(1.0), page.lifecycle("a")["screen/appearances"])
        }
    }

    @Test fun `phase batches target each row and Journey reserved writes cannot replace them`() = runBlocking {
        withLayer { layer, native ->
            layer.insertIncoming("a", Style.MAGIC_MOVE, false)
            layer.insertIncoming("b", Style.MAGIC_MOVE, false)
            native.trace.clear(); native.writes.clear()
            layer.setPhases(listOf("a" to ExperienceScreenLifecycle.Phase.EXITING,
                "b" to ExperienceScreenLifecycle.Phase.ENTERING), "pair")
            assertFalse(native.trace.any { it.startsWith("step") })
            val byRoot = native.writes.groupBy { it.first }
            assertEquals(setOf(200L, 300L), byRoot.keys)
            assertEquals(3L, byRoot.getValue(200).single { it.second.path == "screen/phase" }.second.integerValue)
            assertEquals(1L, byRoot.getValue(300).single { it.second.path == "screen/phase" }.second.integerValue)
            assertTrue(byRoot.values.all { writes -> writes.single { it.second.path == "screen/transition" }.second.bytesValue.decodeToString() == "pair" })
            native.writes.clear()
            layer.applyJourneyValues("b", mapOf("screen/phase" to string("hidden"),
                "env/reduceMotion" to NuxieViewModelScalarValue.BooleanValue(true),
                "keptValue" to NuxieViewModelScalarValue.NumberValue(42.0)))
            assertEquals(listOf("keptValue"), native.writes.map { it.second.path })
            assertEquals(300L, native.writes.single().first)
            assertEquals(NuxieViewModelScalarValue.NumberValue(1.0), layer.lifecycle("b")["screen/appearances"])
        }
    }
    @Test fun `events use their graph owner and exact names, a shared instance goes to the Screen on top, and removal never advances`() = runBlocking {
        withLayer { layer, native ->
            layer.insertIncoming("a", Style.MAGIC_MOVE, false)
            layer.insertIncoming("b", Style.MAGIC_MOVE, false)
            val a = layer.registerCompletion("a", "done")
            val b = layer.registerCompletion("b", "done")
            val suffix = layer.registerCompletion("a", "done.reverse")
            native.events = arrayOf(event("done", 201), event("done", 300), event("shared", 999),
                event("missing", 777), event("zero", 0))
            val frame = layer.step(0.1)
            assertTrue(a.isCompleted); assertTrue(b.isCompleted); assertFalse(suffix.isCompleted)
            // Instance 999 is in both rows' graphs; "a" is on top until another row is selected (F1).
            assertEquals(listOf("done", "shared"), frame.rows.getValue("a").outcome.events.map { it.name })
            assertEquals(listOf("done"), frame.rows.getValue("b").outcome.events.map { it.name })
            assertEquals(listOf("missing", "zero"), frame.unattributedEvents.map { it.name })
            layer.selectActive("b")
            native.events = arrayOf(event("shared", 999))
            val selected = layer.step(0.1)
            assertEquals(emptyList<String>(), selected.rows.getValue("a").outcome.events.map { it.name })
            assertEquals(listOf("shared"), selected.rows.getValue("b").outcome.events.map { it.name })
            layer.selectActive("a")
            assertEquals(setOf("alias-a"), frame.rows.getValue("a").snapshot.instanceAliases(200))
            assertTrue(runCatching { layer.remove("a") }.isFailure)
            layer.removeCompletion("a", suffix)
            native.trace.clear()
            native.events = arrayOf(event("detach", 200))
            val removal = layer.remove("a", 77u)
            assertEquals(listOf("remove"), native.trace)
            assertEquals(setOf("b"), removal.rows.keys)
            assertEquals(77uL, removal.correlationId)
            layer.step(0.5)
            layer.insertIncoming("a", Style.PUSH, true)
            assertNotEquals(200L, layer.snapshot("a").nativeRootInstanceId)
            assertEquals(3, native.allocated)
        }
    }
    @Test fun `each row routes with its own tables and removal creates no synthetic effects`() = runBlocking {
        withLayer { layer, native ->
            val batches = mutableListOf<JourneyScreenEmissionBatch>()
            suspend fun routing(id: String): JourneyRuntimeEmissionCoordinator {
                val descriptor = Json.parseToJsonElement("""{"screenBehaviors":[{"screenId":"$id","controls":[
                    {"actionId":"tap","behavior":{"kind":"declarative","program":[{"type":"emit","eventName":"clicked-$id"}]}}
                ]}]}""").jsonObject
                return JourneyRuntimeEmissionCoordinator("journey", id, descriptor, 0, 0,
                    onEmissionBatch = { batch, _ -> batches += batch; JourneyEmissionBatchResult.ACCEPTED },
                    onPresentationRevealed = {}).also { assertTrue(it.reveal()) }
            }
            val a = routing("a")
            val b = routing("b")
            try {
                layer.insertIncoming("a", Style.MAGIC_MOVE, false, routing = a)
                layer.insertIncoming("b", Style.MAGIC_MOVE, false, routing = b)
                native.events = arrayOf(event("tap", 200), event("tap", 300), event("unowned", 0))
                assertTrue(layer.deliver(layer.step(0.0)))
                assertEquals(listOf("a", "b"), batches.map { it.source.screenId })
                assertEquals(listOf("clicked-a", "clicked-b"), batches.flatMap { it.emissions }.map { it.name })
                native.events = arrayOf(event("tap", 200))
                assertTrue(layer.deliver(layer.remove("a")))
                assertEquals(2, batches.size)
                assertEquals("b", batches.last().source.screenId)
            } finally { a.close(); b.close() }
        }
    }
    @Test fun `native step failure fails every row waiter and rejects further operations`() = runBlocking {
        withLayer { layer, native ->
            layer.insertIncoming("a", Style.MAGIC_MOVE, false)
            layer.insertIncoming("b", Style.MAGIC_MOVE, false)
            val a = layer.registerCompletion("a", "done")
            val b = layer.registerCompletion("b", "done")
            native.failStep = true
            assertTrue(runCatching { layer.step(0.1) }.exceptionOrNull() is NuxieRuntimeCallException)
            assertTrue(runCatching { a.await() }.exceptionOrNull() is NuxieRuntimeCallException)
            assertTrue(runCatching { b.await() }.exceptionOrNull() is NuxieRuntimeCallException)
            assertTrue(runCatching { layer.snapshot("a") }.isFailure)
        }
    }
    @Test fun `env is bound once and both rows link the same Experience instance`() = runBlocking {
        val native = RecordingNative().apply { globals = true }
        withLayer(native) { layer, _ ->
            layer.insertIncoming("a", Style.NONE, false)
            layer.insertIncoming("b", Style.NONE, false)
            assertEquals(listOf("env" to 500L), native.globalBindings)
            assertEquals(listOf(200L to 600L, 300L to 600L), native.writes.filter {
                it.second.kind == NuxieViewModelMutationKind.SET_VIEW_MODEL && it.second.path == "experience"
            }.map { it.first to it.second.relatedViewModel })
            layer.updateEnvironment(mapOf("reduceMotion" to NuxieViewModelScalarValue.BooleanValue(true)))
            assertEquals(500L, native.writes.last().first)
            native.writes.clear()
            layer.applyRuntimeValues("a", mapOf("env/fontScale" to NuxieViewModelScalarValue.NumberValue(1.4)))
            assertEquals(500L, native.writes.single().first)
            assertEquals("fontScale", native.writes.single().second.path)
        }
    }
    @Test fun `static host is refused before rows mount`() = runBlocking {
        val native = RecordingNative().apply { kind = 0 }
        assertTrue(runCatching { withLayer(native) { _, _ -> error("Static host was accepted") } }
            .exceptionOrNull()?.message.orEmpty().contains("default state machine"))
    }

    @Test fun `reserved namespaces use root path segments and leave ordinary fields alone`() {
        val ordinary = mapOf("screen.phase" to string("data"), "screening/phase" to string("data"),
            "fontScale" to NuxieViewModelScalarValue.NumberValue(1.5))
        val reserved = mapOf("screen" to string("hidden"), "screen/phase" to string("hidden"),
            "env" to string("data"), "env/reduceMotion" to NuxieViewModelScalarValue.BooleanValue(true))
        assertEquals(ordinary, ExperienceInteractivePage.journeyValues(ordinary + reserved))
    }

    @Test fun `completion resolution never uses prefixes`() {
        val completion = CompletableDeferred<Unit>()
        val waiters = mutableMapOf(completion to "complete")
        ExperienceInteractivePage.resolveCompletions(waiters, listOf(NuxieRuntimeEvent(0, 0, "complete.reverse", "", "", 0f, emptyList(), 1)))
        assertFalse(completion.isCompleted)
        ExperienceInteractivePage.resolveCompletions(waiters, listOf(NuxieRuntimeEvent(0, 0, "complete", "", "", 0f, emptyList(), 1)))
        assertTrue(completion.isCompleted); assertTrue(waiters.isEmpty())
    }

    @Test fun `unqualified values lists and triggers target each retained row own root`() = runBlocking {
        withLayer { layer, native ->
            layer.prepare("a"); layer.prepare("b")
            native.writes.clear()
            layer.applyValues(null, mapOf("keptValue" to NuxieViewModelScalarValue.NumberValue(42.0)))
            assertEquals(listOf(200L, 300L), native.writes.map { it.first })
            native.writes.clear()
            layer.applyListOperation(null, NativeViewModelWrite(NuxieViewModelMutationKind.LIST_CLEAR, "items"))
            assertEquals(listOf(200L, 300L), native.writes.map { it.first })
            native.writes.clear()
            layer.fireTrigger(null, "accepted")
            assertEquals(listOf(200L, 300L), native.writes.map { it.first })
            native.writes.clear()
            layer.applyValues(null, mapOf("keptValue" to NuxieViewModelScalarValue.NumberValue(7.0)), "B")
            assertEquals(listOf(300L), native.writes.map { it.first })
        }
    }

    @Test fun `late removed row steps still return every mounted row effect exactly once`() = runBlocking {
        withLayer { layer, native ->
            layer.insertIncoming("a", Style.NONE, false)
            layer.insertIncoming("b", Style.NONE, false)
            native.events = arrayOf(event("leave", 200), event("arrive", 300))
            val both = layer.stepFromRow("a")
            assertEquals(listOf("leave"), both.rows.getValue("a").outcome.events.map { it.name })
            assertEquals(listOf("arrive"), both.rows.getValue("b").outcome.events.map { it.name })
            native.events = emptyArray()
            layer.remove("a")
            native.events = arrayOf(event("mounted", 300))
            val late = layer.stepFromRow("a")
            assertEquals(setOf("b"), late.rows.keys)
            assertEquals(listOf("mounted"), late.rows.getValue("b").outcome.events.map { it.name })
        }
    }

    @Test fun `first preparation initializes once and revisiting never replays a cached snapshot`() = runBlocking {
        withLayer { layer, native ->
            assertTrue(layer.prepare("a"))
            layer.applyValues("a", mapOf("keptValue" to NuxieViewModelScalarValue.NumberValue(21.0)))
            native.writes.clear()
            assertFalse(layer.prepare("a"))
            layer.insertIncoming("a", Style.NONE, false)
            layer.remove("a")
            layer.insertIncoming("a", Style.NONE, false)
            assertEquals(2, native.allocated)
            assertEquals(21.0, native.writes.last { it.second.path == "keptValue" }.second.numberValue.toDouble(), 0.0)
        }
    }

    @Test fun `same Screen Back retires unreachable rows without a step or phase writes`() = runBlocking {
        withLayer { page, native ->
            page.insertIncoming("a", Style.NONE, false)
            page.insertIncoming("b", Style.NONE, false)
            page.setCollapsed("a", true)
            page.takeIncoming("a", "b", false)
            page.setCollapsed("b", true)
            native.trace.clear(); native.writes.clear()
            page.retire(setOf("a"), "a")
            assertEquals(listOf("a"), page.mountedScreenIds())
            assertEquals(listOf("remove"), native.trace)
            assertEquals(listOf(NuxieViewModelMutationKind.LIST_REMOVE), native.writes.map { it.second.kind })
        }
    }

    @Test fun `covered same Screen Back removes B and reveal only writes retained A`() = runBlocking {
        withLayer { page, native ->
            page.insertIncoming("a", Style.NONE, false)
            page.insertIncoming("b", Style.NONE, false)
            page.applyValues("b", mapOf("keptValue" to NuxieViewModelScalarValue.NumberValue(42.0)))
            val removedRoot = page.snapshot("b").nativeRootInstanceId
            val retainedRoot = page.snapshot("a").nativeRootInstanceId
            page.takeIncoming("a", "b", false)
            page.setCollapsed("b", true)
            page.setPhases(listOf("a" to ExperienceScreenLifecycle.Phase.HIDDEN,
                "b" to ExperienceScreenLifecycle.Phase.HIDDEN))
            native.trace.clear(); native.writes.clear()
            page.retire(setOf("a"), "a")
            assertEquals(listOf("a"), page.mountedScreenIds())
            assertEquals(listOf("remove"), native.trace)
            assertEquals(listOf(NuxieViewModelMutationKind.LIST_REMOVE), native.writes.map { it.second.kind })
            assertTrue(native.freed.contains("values:$removedRoot"))
            assertEquals(NuxieViewModelScalarValue.StringValue("hidden"), page.lifecycle("a")["screen/phase"])
            native.writes.clear()
            page.setPhases(listOf("a" to ExperienceScreenLifecycle.Phase.ENTERING))
            page.step(0.016)
            page.setPhases(listOf("a" to ExperienceScreenLifecycle.Phase.ACTIVE))
            assertTrue(native.writes.isNotEmpty())
            assertTrue(native.writes.all { it.first == retainedRoot })
            assertEquals(removedRoot, page.snapshot("b").nativeRootInstanceId)
        }
    }

    @Test fun `removed B rejects native writes, holds Journey writes, and revisits from retained values then the held writes`() = runBlocking {
        withLayer { page, native ->
            page.insertIncoming("a", Style.NONE, false)
            page.insertIncoming("b", Style.NONE, false)
            val cached = page.snapshot("b")
            page.applyValues("b", mapOf("keptValue" to NuxieViewModelScalarValue.NumberValue(42.0)))
            val old = page.snapshot("b").nativeRootInstanceId
            page.takeIncoming("a", "b", true)
            page.begin("a", "pair.reverse"); page.end("a", "pair.reverse")
            page.retire(setOf("a"), "a")
            assertTrue(native.freed.contains("values:$old"))
            native.writes.clear()
            page.applyRuntimeValues("b", mapOf("env/fontScale" to NuxieViewModelScalarValue.NumberValue(2.0),
                "env/reduceMotion" to NuxieViewModelScalarValue.BooleanValue(true), "keptValue" to string("ignored")))
            // R6b: Journey writes while B is removed wait for its next row.
            page.applyJourneyValues("b", mapOf("keptValue" to NuxieViewModelScalarValue.NumberValue(7.0)))
            page.applyValues("b", mapOf("keptValue" to NuxieViewModelScalarValue.NumberValue(99.0)))
            page.applyListOperation("b", NativeViewModelWrite(NuxieViewModelMutationKind.LIST_CLEAR, "items"))
            page.fireTrigger("b", "accepted")
            page.setPhases(listOf("b" to ExperienceScreenLifecycle.Phase.HIDDEN))
            native.lane.call {
                page.applyLifecycleOnLane("b")
                page.commandOnLane("b") { it.restoreWrites(listOf(NativeViewModelWrite(NuxieViewModelMutationKind.SET_STRING, "text"))) }
                assertTrue(runCatching { page.valuesOnLane("b") }.exceptionOrNull() is IllegalArgumentException)
                assertTrue(runCatching { page.insertOnLane("b", 1) }.exceptionOrNull() is IllegalArgumentException)
                assertFalse(page.canWriteNativeOwnerOnLane(old))
                assertFalse(page.canWriteNativeOwnerOnLane(old + 1))
            }
            assertTrue(runCatching { page.setCollapsed("b", true) }.isFailure)
            assertTrue(native.writes.isEmpty())
            assertEquals(old, page.snapshot("b").nativeRootInstanceId)
            // A stale Journey checkpoint must not override the retained person's value.
            assertFalse(native.lane.call { page.prepareOnLane("b", snapshot = cached) })
            page.takeIncoming("b", "a", false)
            val fresh = page.snapshot("b").nativeRootInstanceId
            assertNotEquals(old, fresh)
            native.lane.call {
                assertFalse(page.canWriteNativeOwnerOnLane(old))
                assertTrue(page.canWriteNativeOwnerOnLane(fresh))
            }
            assertEquals(listOf("a", "b"), page.mountedScreenIds())
            val freshWrites = native.writes.filter { it.first == fresh }.map { it.second }
            val restored = freshWrites.indexOfFirst { it.path == "keptValue" && it.numberValue == 42f }
            assertTrue("The new row is seeded from B's retained values first", restored >= 0)
            val held = freshWrites.drop(restored + 1).filter { it.path in setOf("keptValue", "items", "accepted", "text") }
            assertEquals(listOf("keptValue" to 7f, "keptValue" to 99f, "items" to 0f, "accepted" to 0f, "text" to 0f),
                held.map { it.path to it.numberValue })
            assertEquals(listOf(NuxieViewModelMutationKind.LIST_CLEAR, NuxieViewModelMutationKind.FIRE_TRIGGER,
                NuxieViewModelMutationKind.SET_STRING), held.drop(2).map { it.kind })
            assertFalse(native.writes.any { it.first == old })
            assertTrue(native.allocations.all { it.second == null })
        }
    }

    private suspend fun withLayer(native: RecordingNative = RecordingNative(), body: suspend (ExperienceInteractivePage, RecordingNative) -> Unit) {
        val lane = TestRuntimeLane()
        native.lane = lane
        val file = NuxieRuntimeFile(1, native)
        val descriptor = Json.parseToJsonElement("""{"leg":{"screens":[
            {"id":"a","defaultViewModelName":"A","defaultInstanceId":"alias-a"},
            {"id":"b","defaultViewModelName":"B","defaultInstanceId":"alias-b"},{"id":"c","defaultViewModelName":"C"}]},
            "render":{"screens":[{"id":"a","artboardName":"A"},{"id":"b","artboardName":"B"},{"id":"c","artboardName":"C"}],"layerHost":{"artboardName":"LayerList","list":"screens"},"textInputs":[]}}""").jsonObject
        try {
            val shared = if (native.globals) lane.call { file.newAuthoredViewModel("Experience", 0) } else null
            try {
                val layer = ExperienceInteractivePage.open(lane, NuxieRuntime(native), file, descriptor, shared)
                try { body(layer, native) } finally { layer.close() }
            } finally { lane.call { shared?.close() } }
        } finally { lane.call { file.close() }; lane.shutdown(); assertTrue(lane.awaitQuiescence(5000)) }
    }
    private fun string(value: String) = NuxieViewModelScalarValue.StringValue(value)
    private fun event(name: String, source: Long) = NativeRuntimeEvent(0, 0, name, "", "", 0f, emptyArray(), source)

    internal class RecordingNative : NuxieTypedRuntimeNative {
        lateinit var lane: NuxieRuntimeLane
        val writes = mutableListOf<Pair<Long, NativeViewModelWrite>>()
        val trace = mutableListOf<String>()
        var events = emptyArray<NativeRuntimeEvent>()
        private val listRows = mutableListOf<Long>()
        private var hostedRows = emptyList<Long>()
        val collapsedOwners = mutableSetOf<Long>()
        private fun applyPendingRows() { hostedRows = listRows.toList() }
        private fun requireIncoming(incoming: ByteArray) {
            applyPendingRows()
            val schema = when (incoming.decodeToString()) { "A" -> 1; "B" -> 2; "C" -> 6; else -> error("Unknown incoming") }
            check(hostedRows.any { schemas[it] == schema && it !in collapsedOwners }) { "Incoming must be hosted and expanded" }
        }
        private val pendingCommands = mutableListOf<NativeHostCommand>()
        var players = 0
        var pageAvailable = true
        var kind = 1
        var failStep = false
        var failWrites = false
        val freed = mutableListOf<String>()
        var renders = 0
        var detaches = 0
        var presentation = 4
        var onStep: () -> Unit = {}
        val contentHeights = mutableMapOf<Int, Float>()
        var keepGoing = true
        var contentHeightQueries = 0
        var contentHeightStatus: Int? = null
        private var settledContentHeight: Float? = null
        override fun playerContentHeight(playerHandle: Long): NativeCallResult<Float> {
            contentHeightQueries++
            trace += "height"
            return NativeCallResult(contentHeightStatus ?: if (settledContentHeight == null) 3 else 0, settledContentHeight)
        }
        var globals = false
        val globalBindings = mutableListOf<Pair<String, Long>>()
        var allocated = 0
        var catalogQueries = 0
        val allocations = mutableListOf<Pair<Int, Int?>>()
        private val kept = mutableMapOf<Long, Float>()
        private val labels = mutableMapOf<Long, String>()
        val snapshotOverrides = mutableMapOf<Long, NativeViewModelSnapshot>()
        private val schemas = mutableMapOf(100L to 0)
        private val catalog get() = NativeViewModelCatalog(arrayOf(
            NativeViewModelSchema(0,"\$nuxie.Page",0,1,0,0,-1,false),
            NativeViewModelSchema(1,"A",1,5,0,0,-1,false),
            NativeViewModelSchema(2,"B",6,4,0,0,-1,false),
            NativeViewModelSchema(3,"Screen",10,4,0,0,-1,false),
            NativeViewModelSchema(4,"env",14,2,0,0,-1,globals),
            NativeViewModelSchema(5,"Experience",16,0,0,0,-1,false),
            NativeViewModelSchema(6,"C",16,4,0,0,-1,false)), arrayOf(
            prop(0,0,"screens",8), prop(1,0,"screen",9,3), prop(1,1,"fontScale",2), prop(1,2,"keptValue",2), prop(1,3,"experience",9,5), prop(1,4,"label",1),
            prop(2,0,"screen",9,3), prop(2,1,"fontScale",2), prop(2,2,"keptValue",2), prop(2,3,"experience",9,5),
            prop(3,0,"phase",5,labels=arrayOf("hidden","entering","active","exiting")), prop(3,1,"appearances",2),
            prop(3,2,"transition",1), prop(3,3,"exitReason",5,labels=arrayOf("none","dismissed","navigation")),
            prop(4,0,"reduceMotion",3), prop(4,1,"fontScale",2),
            prop(6,0,"screen",9,3), prop(6,1,"fontScale",2), prop(6,2,"keptValue",2), prop(6,3,"experience",9,5)), emptyArray())
        override fun viewModelCatalog(fileHandle: Long): NativeCallResult<NativeViewModelCatalog> {
            catalogQueries++
            return NativeCallResult(0, catalog)
        }
        override fun newNamedArtboard(fileHandle: Long, name: String) = when (name) { "A" -> 21L; "B" -> 22L; "C" -> 26L; else -> if (pageAvailable) 2L else 0L }
        override fun newDefaultViewModel(artboardHandle: Long): NativeCallResult<Long> {
            val schema = when (artboardHandle) { 21L -> 1; 22L -> 2; 26L -> 6; else -> 0 }
            val id = if (schema == 0) 100L else 1000L + schema
            schemas[id] = schema
            return NativeCallResult(0, id)
        }
        override fun playerStepBegin(playerHandle: Long, incoming: ByteArray, step: ByteArray?): Int {
            requireIncoming(incoming)
            trace += "begin:${incoming.decodeToString()}:${step?.decodeToString()}"
            pendingCommands += NativeHostCommand("takeoff", NativeHostValue(0, false, 0.0, "", emptyArray(), emptyArray()))
            return 0
        }
        override fun playerStepEnd(playerHandle: Long, incoming: ByteArray, step: ByteArray?): Int {
            applyPendingRows()
            trace += "end:${incoming.decodeToString()}:${step?.decodeToString()}"
            pendingCommands += NativeHostCommand("landing", NativeHostValue(0, false, 0.0, "", emptyArray(), emptyArray()))
            return 0
        }
        override fun setPlayerRowCollapsed(playerHandle: Long, list: ByteArray, index: Int, collapsed: Boolean): Int {
            applyPendingRows()
            val owner = hostedRows[index]
            if (collapsed) collapsedOwners.add(owner) else collapsedOwners.remove(owner)
            trace += "collapse:$index:$collapsed"; return 0
        }
        override fun viewModelRootSchemaIndex(viewModelHandle: Long) = NativeCallResult(0,schemas.getValue(viewModelHandle).toLong())
        override fun bindViewModel(artboardHandle: Long, viewModelHandle: Long) = 0
        override fun newDefaultPlayer(artboardHandle: Long): Long { players++; return 10L }
        override fun playerStateMachineName(playerHandle: Long) = NativeCallResult(0, "\$nuxie.page")
        override fun playerKind(playerHandle: Long) = NativeCallResult(0,kind)
        override fun playerFocusState(playerHandle: Long) = NativeCallResult(0,NuxieFocusState(false,false))
        override fun newViewModel(fileHandle: Long, schemaIndex: Int, authoredInstanceIndex: Int?): NativeCallResult<Long> {
            if (schemaIndex in setOf(1, 2, 6)) allocated++
            allocations += schemaIndex to authoredInstanceIndex
            val base = (schemaIndex + 1) * 100L
            val id = if (base !in schemas) base else base + allocations.count { it.first == schemaIndex } * 10000L
            schemas[id] = schemaIndex
            return NativeCallResult(0,id)
        }
        override fun snapshotViewModel(viewModelHandle: Long): NativeCallResult<NativeViewModelSnapshot> {
            check("values:$viewModelHandle" !in freed) { "Read released row" }
            snapshotOverrides[viewModelHandle]?.let { return NativeCallResult(0, it) }
            val schema = schemas.getValue(viewModelHandle).toLong()
            val values = if (schema == 0L) arrayOf(value(100,"screens",8)) else arrayOf(value(viewModelHandle,"screen",9,viewModelHandle+1)) +
                if (schema in setOf(1L, 2L, 6L)) arrayOf(NativeViewModelSnapshotValue(viewModelHandle, 2, "keptValue", 2,
                    byteArrayOf(), 0, numberValue = kept[viewModelHandle] ?: 0f)) else emptyArray()
            val instances = if (schema == 0L) arrayOf(NativeViewModelSnapshotInstance(100,0)) else arrayOf(
                NativeViewModelSnapshotInstance(viewModelHandle,schema),NativeViewModelSnapshotInstance(viewModelHandle+1,3),NativeViewModelSnapshotInstance(999,3))
            return NativeCallResult(0,NativeViewModelSnapshot(viewModelHandle,instances,values))
        }
        var tracePhases = false
        var traceListMutations = false
        override fun mutateViewModel(viewModelHandle: Long, write: NativeViewModelWrite): Int {
            if (failWrites) return 11
            if (viewModelHandle == 100L && write.path == "screens") when (write.kind) {
                NuxieViewModelMutationKind.LIST_INSERT -> listRows.add(write.index.toInt(), write.relatedViewModel)
                NuxieViewModelMutationKind.LIST_MOVE -> listRows.add(write.secondIndex.toInt(), listRows.removeAt(write.index.toInt()))
                NuxieViewModelMutationKind.LIST_REMOVE -> listRows.removeAt(write.index.toInt())
                else -> Unit
            }
            if (tracePhases && write.path == "screen/phase") trace += "phase:$viewModelHandle:${write.integerValue}"
            writes += viewModelHandle to write
            if (write.path == "keptValue" && write.kind == NuxieViewModelMutationKind.SET_NUMBER) kept[viewModelHandle] = write.numberValue
            if (write.path == "label" && write.kind == NuxieViewModelMutationKind.SET_STRING) labels[viewModelHandle] = write.bytesValue.decodeToString()
            if (traceListMutations) when (write.kind) {
                NuxieViewModelMutationKind.LIST_INSERT -> trace += "insert:${write.index}"
                NuxieViewModelMutationKind.LIST_MOVE -> trace += "move:${write.index}:${write.secondIndex}"
                else -> Unit
            }
            if (write.kind == NuxieViewModelMutationKind.LIST_REMOVE) trace += "remove"
            return 0
        }
        override fun stepPlayer(playerHandle: Long, inputs: List<NativePlayerInput>, pointers: List<NativePlayerPointer>, elapsedSeconds: Float, correlationId: Long, textRunNames: List<String>, focusInputs: List<NativeFocusInput>): NativeCallResult<NativePlayerStepOutcome> {
            trace += "step:$elapsedSeconds"
            applyPendingRows()
            onStep()
            settledContentHeight = hostedRows.lastOrNull { it !in collapsedOwners }?.let { contentHeights[schemas[it]]?.plus((labels[it]?.count { char -> char == '\n' } ?: 0) * 80f) }
            if (failStep) return NativeCallResult(11, null)
            val commands = pendingCommands.toTypedArray(); pendingCommands.clear()
            val deliveredEvents = events; events = emptyArray()
            return NativeCallResult(0,NativePlayerStepOutcome(keepGoing,IntArray(pointers.size),deliveredEvents,commands,emptyArray()))
        }
        override fun setPlayerGlobalViewModel(playerHandle: Long, name: ByteArray, viewModelHandle: Long): Int {
            globalBindings += name.decodeToString() to viewModelHandle
            return 0
        }
        override fun resizeRenderer(rendererHandle: Long, width: Int, height: Int) = 0
        override fun attachRendererSurface(rendererHandle: Long, windowHandle: Long) = 0
        override fun detachRendererSurface(rendererHandle: Long): Int { detaches++; return 0 }
        override fun renderAndPresent(rendererHandle: Long, playerHandle: Long, windowHandle: Long, clearColor: Int, layoutScaleFactor: Float): Int { renders++; return presentation }
        override fun releaseWindow(handle: Long) { freed += "window" }
        override fun freeRenderer(handle: Long) { freed += "renderer" }
        override fun freePlayer(handle: Long) { freed += "player" }
        override fun freeArtboard(handle: Long) { freed += if (handle == 2L) "artboard" else "schema-artboard" }
        override fun freeFile(handle: Long) {}
        override fun freeViewModel(handle: Long): Int { freed += "values:$handle"; return 0 }
        private fun prop(schema: Long,index: Long,name: String,kind: Int,ref: Long = -1,labels: Array<String> = emptyArray()) =
            NativeViewModelProperty(schema,index,name,kind,ref,labels)
        private fun value(owner: Long,name: String,kind: Int,ref: Long = 0) =
            NativeViewModelSnapshotValue(owner,0,name,kind,byteArrayOf(),ref)
    }
}
