package ai.nuxie.sdk.presentation

import ai.nuxie.sdk.experiences.ExperienceLayerHostContract.Style
import ai.nuxie.sdk.runtime.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class ExperienceInteractiveLayerTest {
    @Test fun `incoming is last except push back and duplicate or third rows are refused`() = runBlocking {
        for (style in Style.entries) for (back in listOf(false, true)) withLayer { layer, native ->
            layer.insertIncoming("a", style, false)
            layer.insertIncoming("b", style, back)
            val expected = if (style == Style.PUSH && back) listOf("b", "a") else listOf("a", "b")
            assertEquals(expected, layer.mountedScreenIds())
            assertEquals(if (style == Style.PUSH && back) 0L else 1L, native.writes.last().second.index)
            assertTrue(runCatching { layer.insertIncoming("a", style, back) }.isFailure)
            assertTrue(runCatching { layer.insertIncoming("c", style, back) }.isFailure)
            assertEquals(1, native.players)
            assertFalse(native.writes.any { it.second.kind == NuxieViewModelMutationKind.LIST_SET })
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
    @Test fun `events use unique graph owner and exact names before removal settles and returns effects`() = runBlocking {
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
            assertEquals(listOf("done"), frame.rows.getValue("a").outcome.events.map { it.name })
            assertEquals(listOf("done"), frame.rows.getValue("b").outcome.events.map { it.name })
            assertEquals(listOf("shared", "missing", "zero"), frame.unattributedEvents.map { it.name })
            assertEquals(setOf("alias-a"), frame.rows.getValue("a").snapshot.instanceAliases(200))
            assertTrue(runCatching { layer.remove("a") }.isFailure)
            layer.removeCompletion("a", suffix)
            native.trace.clear()
            native.events = arrayOf(event("detach", 200))
            val removal = layer.remove("a", 77u)
            assertEquals(listOf("remove", "step:0.0"), native.trace)
            assertEquals(listOf("detach"), removal.rows.getValue("a").outcome.events.map { it.name })
            assertEquals(77uL, removal.correlationId)
            layer.step(0.5)
            layer.insertIncoming("a", Style.PUSH, true)
            assertEquals(200L, layer.snapshot("a").nativeRootInstanceId)
            assertEquals(2, native.allocated)
        }
    }
    @Test fun `each row routes with its own tables including the removal settlement`() = runBlocking {
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
                assertEquals("clicked-a", batches.last().emissions.single().name)
                assertEquals("a", batches.last().source.screenId)
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
        assertEquals(ordinary, ExperienceInteractiveLayer.journeyValues(ordinary + reserved))
    }

    @Test fun `completion resolution never uses prefixes`() {
        val completion = CompletableDeferred<Unit>()
        val waiters = mutableMapOf(completion to "complete")
        ExperienceInteractiveLayer.resolveCompletions(waiters, listOf(NuxieRuntimeEvent(0, 0, "complete.reverse", "", "", 0f, emptyList(), 1)))
        assertFalse(completion.isCompleted)
        ExperienceInteractiveLayer.resolveCompletions(waiters, listOf(NuxieRuntimeEvent(0, 0, "complete", "", "", 0f, emptyList(), 1)))
        assertTrue(completion.isCompleted); assertTrue(waiters.isEmpty())
    }

    private suspend fun withLayer(native: RecordingNative = RecordingNative(), body: suspend (ExperienceInteractiveLayer, RecordingNative) -> Unit) {
        val lane = NuxieRuntimeLane()
        val file = NuxieRuntimeFile(1, native)
        val descriptor = Json.parseToJsonElement("""{"leg":{"screens":[
            {"id":"a","defaultViewModelName":"A","defaultInstanceId":"alias-a"},
            {"id":"b","defaultViewModelName":"B","defaultInstanceId":"alias-b"}]},
            "render":{"layerHost":{"artboardName":"LayerList","list":"screens"},"textInputs":[]}}""").jsonObject
        try {
            val shared = if (native.globals) lane.call { file.newAuthoredViewModel("Experience", 0) } else null
            try {
                val layer = ExperienceInteractiveLayer.open(lane, NuxieRuntime(native), file, descriptor, shared)
                try { body(layer, native) } finally { layer.close() }
            } finally { lane.call { shared?.close() } }
        } finally { lane.call { file.close() }; lane.shutdown(); assertTrue(lane.awaitQuiescence(5000)) }
    }
    private fun string(value: String) = NuxieViewModelScalarValue.StringValue(value)
    private fun event(name: String, source: Long) = NativeRuntimeEvent(0, 0, name, "", "", 0f, emptyArray(), source)

    private class RecordingNative : NuxieTypedRuntimeNative {
        val writes = mutableListOf<Pair<Long, NativeViewModelWrite>>()
        val trace = mutableListOf<String>()
        var events = emptyArray<NativeRuntimeEvent>()
        var players = 0
        var kind = 1
        var failStep = false
        var globals = false
        val globalBindings = mutableListOf<Pair<String, Long>>()
        var allocated = 0
        private val schemas = mutableMapOf(100L to 0)
        private val catalog get() = NativeViewModelCatalog(arrayOf(
            NativeViewModelSchema(0,"Host",0,1,0,0,-1,false),
            NativeViewModelSchema(1,"A",1,4,0,0,-1,false),
            NativeViewModelSchema(2,"B",5,4,0,0,-1,false),
            NativeViewModelSchema(3,"Screen",9,4,0,0,-1,false),
            NativeViewModelSchema(4,"env",13,1,0,0,-1,globals),
            NativeViewModelSchema(5,"Experience",14,0,0,0,-1,false)), arrayOf(
            prop(0,0,"screens",8), prop(1,0,"screen",9,3), prop(1,1,"fontScale",2), prop(1,2,"keptValue",2), prop(1,3,"experience",9,5),
            prop(2,0,"screen",9,3), prop(2,1,"fontScale",2), prop(2,2,"keptValue",2), prop(2,3,"experience",9,5),
            prop(3,0,"phase",5,labels=arrayOf("hidden","entering","active","exiting")), prop(3,1,"appearances",2),
            prop(3,2,"transition",1), prop(3,3,"exitReason",5,labels=arrayOf("none","dismissed","navigation")),
            prop(4,0,"reduceMotion",3)), emptyArray())
        override fun viewModelCatalog(fileHandle: Long) = NativeCallResult(0, catalog)
        override fun newNamedArtboard(fileHandle: Long, name: String) = 2L
        override fun newDefaultViewModel(artboardHandle: Long) = NativeCallResult(0,100L)
        override fun viewModelRootSchemaIndex(viewModelHandle: Long) = NativeCallResult(0,schemas.getValue(viewModelHandle).toLong())
        override fun bindViewModel(artboardHandle: Long, viewModelHandle: Long) = 0
        override fun newDefaultPlayer(artboardHandle: Long): Long { players++; return 10L }
        override fun playerKind(playerHandle: Long) = NativeCallResult(0,kind)
        override fun playerFocusState(playerHandle: Long) = NativeCallResult(0,NuxieFocusState(false,false))
        override fun newViewModel(fileHandle: Long, schemaIndex: Int, authoredInstanceIndex: Int?): NativeCallResult<Long> {
            if (schemaIndex in 1..2) allocated++
            val id = (schemaIndex + 1) * 100L; schemas[id] = schemaIndex
            return NativeCallResult(0,id)
        }
        override fun snapshotViewModel(viewModelHandle: Long): NativeCallResult<NativeViewModelSnapshot> {
            val schema = schemas.getValue(viewModelHandle).toLong()
            val values = if (schema == 0L) arrayOf(value(100,"screens",8)) else arrayOf(value(viewModelHandle,"screen",9,viewModelHandle+1))
            val instances = if (schema == 0L) arrayOf(NativeViewModelSnapshotInstance(100,0)) else arrayOf(
                NativeViewModelSnapshotInstance(viewModelHandle,schema),NativeViewModelSnapshotInstance(viewModelHandle+1,3),NativeViewModelSnapshotInstance(999,3))
            return NativeCallResult(0,NativeViewModelSnapshot(viewModelHandle,instances,values))
        }
        override fun mutateViewModel(viewModelHandle: Long, write: NativeViewModelWrite): Int {
            writes += viewModelHandle to write
            if (write.kind == NuxieViewModelMutationKind.LIST_REMOVE) trace += "remove"
            return 0
        }
        override fun stepPlayer(playerHandle: Long, inputs: List<NativePlayerInput>, pointers: List<NativePlayerPointer>, elapsedSeconds: Float, correlationId: Long, textRunNames: List<String>, focusInputs: List<NativeFocusInput>): NativeCallResult<NativePlayerStepOutcome> {
            trace += "step:$elapsedSeconds"
            if (failStep) return NativeCallResult(11, null)
            return NativeCallResult(0,NativePlayerStepOutcome(true,IntArray(pointers.size),events,emptyArray(),emptyArray()))
        }
        override fun setPlayerGlobalViewModel(playerHandle: Long, name: ByteArray, viewModelHandle: Long): Int {
            globalBindings += name.decodeToString() to viewModelHandle
            return 0
        }
        override fun freePlayer(handle: Long) {}
        override fun freeArtboard(handle: Long) {}
        override fun freeFile(handle: Long) {}
        override fun freeViewModel(handle: Long) = 0
        private fun prop(schema: Long,index: Long,name: String,kind: Int,ref: Long = -1,labels: Array<String> = emptyArray()) =
            NativeViewModelProperty(schema,index,name,kind,ref,labels)
        private fun value(owner: Long,name: String,kind: Int,ref: Long = 0) =
            NativeViewModelSnapshotValue(owner,0,name,kind,byteArrayOf(),ref)
    }
}
