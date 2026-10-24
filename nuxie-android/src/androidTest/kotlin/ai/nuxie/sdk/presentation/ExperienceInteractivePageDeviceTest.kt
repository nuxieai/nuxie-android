package ai.nuxie.sdk.presentation

import ai.nuxie.sdk.experiences.ExperiencePageHostContract.Style
import ai.nuxie.sdk.runtime.*
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

/** Interim file contract shared with the iOS native layer tests. */
class ExperienceInteractivePageDeviceTest {
    private val descriptor = Json.parseToJsonElement("""{"leg":{"screens":[
        {"id":"screen_1","defaultViewModelName":"Runtime","defaultInstanceId":"row-a"},
        {"id":"screen_2","defaultViewModelName":"Runtime_Upsell","defaultInstanceId":"row-b"}]},
        "render":{"screens":[{"id":"screen_1","artboardName":"Paywall"},{"id":"screen_2","artboardName":"Upsell"}],"layerHost":{"artboardName":"LayerList","list":"screens"},"textInputs":[]}}""").jsonObject
    private fun bytes(name: String) = InstrumentationRegistry.getInstrumentation().context.assets
        .open("runtime/layer-host-interim/$name").use { it.readBytes() }
    private suspend fun withLayer(body: suspend (ExperienceInteractivePage) -> Unit) {
        val lane = NuxieRuntimeLane()
        val runtime = NuxieRuntime.shared
        assertTrue(runtime.isAvailable)
        val renderer = lane.call { checkNotNull(runtime.newAndroidVulkanRenderer(390,844)) }
        try {
            val file = lane.call {
                val bytes = bytes("layer-host.riv")
                checkNotNull(runtime.importFile(renderer, bytes, checkNotNull(runtime.inspectFileAssets(bytes))))
            }
            try {
                val layer = ExperienceInteractivePage.open(lane,runtime,file,descriptor, host = ai.nuxie.sdk.experiences.ExperiencePageHostContract.Host("LayerList", "screens", null))
                try { body(layer) } finally { layer.close() }
            } finally { lane.call { file.close() } }
        } finally { lane.call { renderer.close() }; lane.shutdown(); assertTrue(lane.awaitQuiescence(5000)) }
    }
    @Test fun rowsRunDefaultMachinesAndRevisitKeepsValuesWithFreshMount() = runBlocking {
        withLayer { layer ->
            layer.insertIncoming("screen_1",Style.MAGIC_MOVE,false)
            layer.step(0.0); layer.step(0.25)
            val a = layer.snapshot("screen_1")
            assertTrue(number(a,"markerX") > 0f)
            layer.insertIncoming("screen_2",Style.MAGIC_MOVE,false)
            layer.step(0.0)
            val b = layer.snapshot("screen_2")
            assertNotEquals(a.nativeRootInstanceId,b.nativeRootInstanceId)
            assertEquals(listOf("screen_1","screen_2"),layer.mountedScreenIds())
            assertEquals(setOf("row-b"),b.instanceAliases(b.nativeRootInstanceId))
            layer.applyJourneyValues("screen_2",mapOf("keptValue" to NuxieViewModelScalarValue.NumberValue(42.0)))
            val removed = layer.remove("screen_1")
            assertEquals(setOf("screen_2"),removed.rows.keys)
            layer.step(0.5)
            assertEquals(42f,number(layer.snapshot("screen_2"),"keptValue"),0.01f)
            assertTrue(number(layer.snapshot("screen_2"),"markerX") > 100f)
            layer.remove("screen_2"); layer.step(0.5)
            layer.insertIncoming("screen_2",Style.PUSH,true)
            layer.step(0.0)
            val revisited = layer.snapshot("screen_2")
            assertNotEquals(b.nativeRootInstanceId,revisited.nativeRootInstanceId)
            assertEquals(42f,number(revisited,"keptValue"),0.01f)
            assertEquals(0f,number(revisited,"markerX"),0.01f)
        }
    }
    @Test fun forwardAndReverseCompletionsResolveOnlyTheirOwnRowsBeforeRemoval() = runBlocking {
        withLayer { layer ->
            layer.insertIncoming("screen_1",Style.MAGIC_MOVE,false)
            layer.step(0.0)
            layer.insertIncoming("screen_2",Style.MAGIC_MOVE,false)
            for (reverse in listOf(false,true)) {
                val outgoing = if (reverse) "screen_2" else "screen_1"
                val incoming = if (reverse) "screen_1" else "screen_2"
                val suffix = if (reverse) ".reverse" else ""
                val exit = "nx_exit_done:transition.native-contract$suffix"
                val enter = "nx_enter_done:transition.native-contract$suffix"
                val outWaiter = layer.registerCompletion(outgoing,exit)
                val inWaiter = layer.registerCompletion(incoming,enter)
                layer.setPhases(listOf(outgoing to ExperienceScreenLifecycle.Phase.EXITING,
                    incoming to ExperienceScreenLifecycle.Phase.ENTERING),"transition.native-contract")
                val found = mutableMapOf<String,String>()
                repeat(42) {
                    val step = layer.step(1.0/60)
                    assertTrue(step.unattributedEvents.isEmpty())
                    for ((id,row) in step.rows) for (event in row.outcome.events) {
                        if (event.name == exit || event.name == enter) {
                            assertEquals(layer.snapshot(id).nativeRootInstanceId,event.sourceViewModelInstanceId)
                            found[event.name] = id
                        }
                    }
                }
                assertEquals(mapOf(exit to outgoing,enter to incoming),found)
                assertTrue(outWaiter.isCompleted); assertTrue(inWaiter.isCompleted)
                layer.setPhases(listOf(outgoing to ExperienceScreenLifecycle.Phase.HIDDEN,
                    incoming to ExperienceScreenLifecycle.Phase.ACTIVE))
                layer.step(0.0)
            }
            layer.remove("screen_2")
            layer.step(1.0/60)
        }
    }
    @Test fun topRowTapUsesItsOwnSourceAndReservedJourneyWritesStayStripped() = runBlocking {
        withLayer { layer ->
            layer.insertIncoming("screen_1",Style.MAGIC_MOVE,false)
            layer.insertIncoming("screen_2",Style.MAGIC_MOVE,false)
            for (id in listOf("screen_1","screen_2")) {
                layer.setPhases(listOf(id to ExperienceScreenLifecycle.Phase.ENTERING))
                layer.applyJourneyValues(id,mapOf("screen/phase" to NuxieViewModelScalarValue.StringValue("hidden"),
                    "screen/appearances" to NuxieViewModelScalarValue.NumberValue(99.0),
                    "screen/transition" to NuxieViewModelScalarValue.StringValue("wrong"),
                    "screen/exitReason" to NuxieViewModelScalarValue.StringValue("dismissed"),
                    "keptValue" to NuxieViewModelScalarValue.NumberValue(7.0)))
                val snapshot = layer.snapshot(id)
                assertEquals(1L,snapshot.resolveEnumOrdinal("screen/phase"))
                assertEquals(7f,number(snapshot,"keptValue"),0.01f)
            }
            layer.step(0.0)
            fun pointer(kind: NuxiePlayerPointerKind) = NuxiePlayerPointerEvent(kind,60f,40f,1,0f)
            layer.step(0.0,listOf(pointer(NuxiePlayerPointerKind.DOWN)))
            val up = layer.step(0.0,listOf(pointer(NuxiePlayerPointerKind.UP)))
            val events = up.native.events.filter { it.name == "Nuxie Interaction" }
            assertEquals(1,events.size)
            assertEquals(layer.snapshot("screen_2").nativeRootInstanceId,events.single().sourceViewModelInstanceId)
            assertTrue(up.rows.getValue("screen_1").outcome.events.isEmpty())
            assertEquals(events,up.rows.getValue("screen_2").outcome.events)
        }
    }
    @Test fun newRowsAppendAndKeepAuthoredSizeWhenRootResizes() = runBlocking {
        for ((style,back,expected) in listOf(Triple(Style.PUSH,true,listOf("screen_1","screen_2")),
            Triple(Style.PUSH,false,listOf("screen_1","screen_2")),
            Triple(Style.MAGIC_MOVE,true,listOf("screen_1","screen_2")),
            Triple(Style.FADE,true,listOf("screen_1","screen_2")))) {
            withLayer { layer ->
                layer.insertIncoming("screen_1",style,false)
                layer.insertIncoming("screen_2",style,back)
                layer.setLayoutSize(510f,710f)
                layer.step(0.0)
                assertEquals(510f to 710f,layer.layoutSize())
                assertEquals(expected,layer.mountedScreenIds())
                // UNIV-5113 must supply layouts that fill the root; this interim file keeps authored sizes.
                for ((id,width,height) in listOf(Triple("screen_1",390f,844f),Triple("screen_2",430f,932f))) {
                    val snapshot = layer.snapshot(id)
                    assertEquals(width,number(snapshot,"rowWidth"),0.01f)
                    assertEquals(height,number(snapshot,"rowHeight"),0.01f)
                    assertEquals(0f,number(snapshot,"rowX"),0.01f)
                    assertEquals(0f,number(snapshot,"rowY"),0.01f)
                }
            }
        }
    }
    @Test fun removingOnlyRowSettlesBeforeNextTimedStep() = runBlocking {
        withLayer { layer ->
            layer.insertIncoming("screen_1",Style.NONE,false); layer.step(0.0)
            val removed = layer.remove("screen_1")
            assertTrue(removed.rows.isEmpty())
            layer.step(0.5)
            assertTrue(layer.mountedScreenIds().isEmpty())
        }
    }
    @Test fun rawRemoveThenTimedStepPassesWithRuntimeFix() = runBlocking {
        val lane = NuxieRuntimeLane()
        try { lane.call {
            val runtime = NuxieRuntime.shared
            assertTrue(runtime.isAvailable)
            val renderer = checkNotNull(runtime.newAndroidVulkanRenderer(100,100))
            try {
                val file = checkNotNull(runtime.importFile(renderer,bytes("removal-probe-marker.riv")))
                try {
                    val artboard = checkNotNull(file.newArtboard("LayerList"))
                    try {
                        artboard.bindDefaultViewModel()
                        val row = file.newSchemaViewModel(0)
                        try {
                            val player = checkNotNull(artboard.newPlayer("Host"))
                            try {
                                artboard.insertDefaultListItem("screens",0,row)
                                player.stepTyped(elapsedSeconds=0.0)
                                artboard.removeDefaultListItem("screens",0)
                                player.stepTyped(elapsedSeconds=0.5)
                                Unit
                            } finally { player.close() }
                        } finally { row.close() }
                    } finally { artboard.close() }
                } finally { file.close() }
            } finally { renderer.close() }
        } } finally { lane.shutdown(); assertTrue(lane.awaitQuiescence(5000)) }
    }
    private fun number(snapshot: NuxieViewModelSnapshot,path: String): Float = checkNotNull(snapshot.resolveGeometryNumber(path)) { "Missing $path" }
}
