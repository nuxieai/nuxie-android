package ai.nuxie.sdk.presentation

import ai.nuxie.sdk.experiences.ExperiencePageHostContract
import ai.nuxie.sdk.experiences.ExperiencePageHostContract.Style
import ai.nuxie.sdk.runtime.*
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

/** Real live-step fixtures, driven through the production page actor and ordering algorithm. */
class ExperiencePageLiveMovesDeviceTest {
    private fun bytes(name: String) = InstrumentationRegistry.getInstrumentation().context.assets
        .open("page-host-live-moves/$name").use { it.readBytes() }
    private fun screen(index: Int) = when (index) { 0 -> "A"; 1 -> "B"; else -> "R$index" }
    private suspend fun withPage(count: Int, preloadRows: Boolean = false, test: suspend (ExperienceInteractivePage, NuxieRuntimeLane) -> Unit) {
        val lane = NuxieRuntimeLane()
        val runtime = NuxieRuntime.shared
        assertTrue(runtime.isAvailable)
        val renderer = lane.call { checkNotNull(runtime.newAndroidVulkanRenderer(800, 600)) }
        try {
            val file = lane.call {
                val scene = bytes("page$count.riv")
                checkNotNull(runtime.importFile(renderer, scene, checkNotNull(runtime.inspectFileAssets(scene)), videoEnabled = true))
            }
            try {
                val descriptor = buildJsonObject {
                    put("leg", buildJsonObject { put("screens", buildJsonArray {
                        repeat(count) { add(buildJsonObject { put("id", screen(it)) }) }
                    }) })
                    put("render", buildJsonObject {
                        put("screens", buildJsonArray { repeat(count) { add(buildJsonObject {
                            put("id", screen(it)); put("artboardName", screen(it))
                        }) } }); put("textInputs", JsonArray(emptyList()))
                    })
                }
                // The throwaway exporter prepopulates Page. Clear only that fixture default,
                // before player creation, then let the page actor create schema-default rows.
                val page = ExperienceInteractivePage.open(lane, runtime, file, descriptor,
                    host = ExperiencePageHostContract.Host("Page", "screens", "Page"),
                    initializeFixture = { artboard ->
                        val snapshot = checkNotNull(artboard.captureDefaultSnapshot()).native
                        val rows = snapshot.values.single { it.ownerInstanceId == snapshot.rootInstanceId && it.name == "screens" }.listItemIds
                        for (index in rows.indices.reversed()) artboard.removeDefaultListItem("screens", index)
                    })
                try {
                    page.setLayoutSize(800f, 600f)
                    page.insertIncoming("A", Style.NONE, false)
                    page.setPhases(listOf("A" to ExperienceScreenLifecycle.Phase.ENTERING))
                    if (preloadRows) repeat(count - 1) { page.insertIncoming(screen(it + 1), Style.NONE, false) }
                    page.step(0.0)
                    if (preloadRows) repeat(count - 1) { page.setCollapsed(screen(it + 1), true) }
                    page.setPhases(listOf("A" to ExperienceScreenLifecycle.Phase.ACTIVE))
                    test(page, lane)
                } finally { page.close() }
            } finally { lane.call { file.close() } }
        } finally { lane.call { renderer.close() }; lane.shutdown(); assertTrue(lane.awaitQuiescence(5_000)) }
    }

    private suspend fun move(page: ExperienceInteractivePage, lane: NuxieRuntimeLane, source: String, target: String,
        transition: String?, reachable: Set<String>) {
        page.prepare(target)
        val lifecycle = Json.parseToJsonElement(bytes("lifecycle2.json").decodeToString()).jsonObject
        val step = transition?.let { lifecycle.getValue("steps").jsonObject.getValue(it).jsonObject }
        val incoming = step?.getValue("incoming")?.jsonObject?.getValue("completeEventName")?.jsonPrimitive?.content
        val outgoing = step?.getValue("outgoing")?.jsonObject?.get("completeEventName")?.takeUnless { it is JsonNull }?.jsonPrimitive?.content
        val plan = transition?.let { ExperienceScreenTransitionPlan.Custom(it,
            step!!.getValue("durationMs").jsonPrimitive.long, true, outgoing.orEmpty(), incoming.orEmpty()) }
        val mutations = mutableListOf<(ExperienceInteractivePage) -> Unit>()
        val waiters = mutableListOf<Pair<String, Deferred<Unit>>>()
        var inputEnabled = true
        try {
            ExperiencePageStep.perform(source, target, if (transition == null) Style.NONE else Style.MAGIC_MOVE,
                transition == "back", plan, ExperiencePageStep.Exit(null, null), false,
                object : ExperiencePageStep.Operations {
                    override fun input(enabled: Boolean) { inputEnabled = enabled }
                    override suspend fun prepare() = Unit
                    override suspend fun register(screenId: String, event: String): Deferred<Unit> =
                        if (event.isEmpty()) CompletableDeferred(Unit) else page.registerCompletion(screenId, event).also { waiters += screenId to it }
                    override suspend fun batch(present: Boolean, watchdogMs: Long, writes: suspend () -> Unit) {
                        writes()
                        val batch = mutations.toList(); mutations.clear()
                        lane.call { batch.forEach { it(page) } }
                        if (present) page.step(0.0)
                    }
                    override suspend fun takeIncoming() { mutations += { it.takeIncomingOnLane(target, source, transition == "back") } }
                    override suspend fun phases(values: List<Pair<String, ExperienceScreenLifecycle.Phase>>, transition: String) {
                        mutations += { it.setPhasesOnLane(values, transition) }
                    }
                    override suspend fun begin(transition: String?) { mutations += { it.beginOnLane(target, transition) } }
                    override suspend fun end(transition: String?) { mutations += { it.endOnLane(target, transition) } }
                    override suspend fun retire() { mutations += { it.retireOnLane(reachable, target) } }
                    override suspend fun present() = Unit
                    override suspend fun wait(completions: List<Deferred<Unit>>, milliseconds: Long) {
                        repeat((milliseconds * 60 / 1000).toInt()) {
                            assertFalse(inputEnabled)
                            page.step(1.0 / 60)
                            if (completions.all { it.isCompleted }) return
                        }
                        fail("Published completion event did not arrive for $transition")
                    }
                    override suspend fun hidden() = Unit
                    override suspend fun active() = Unit
                })
            assertTrue(inputEnabled)
        } catch (error: Throwable) {
            throw AssertionError("Page actor $source to $target with step $transition failed", error)
        } finally { waiters.forEach { (id, waiter) -> page.removeCompletion(id, waiter) } }
    }

    @Test fun forwardCutSecondForwardAndBackKeepTheInstanceAndRemoveOnlyAfterLanding() = runBlocking {
        withPage(2, preloadRows = true) { page, lane ->
            val identity = page.snapshot("A").nativeRootInstanceId
            move(page, lane, "A", "B", "t1", setOf("A", "B"))
            assertEquals(listOf("A", "B"), page.mountedScreenIds()); assertTrue(page.isCollapsed("A"))
            move(page, lane, "B", "A", null, setOf("A", "B"))
            assertEquals(identity, page.snapshot("A").nativeRootInstanceId)
            assertTrue(page.isCollapsed("B")); assertFalse(page.isCollapsed("A"))
            move(page, lane, "A", "B", "t1", setOf("A", "B"))
            move(page, lane, "B", "A", "back", setOf("A"))
            assertEquals(listOf("A"), page.mountedScreenIds())
            assertEquals(identity, page.snapshot("A").nativeRootInstanceId)
            try { page.step(1.0 / 60) }
            catch (error: Throwable) { throw AssertionError("Timed step after Back removed B", error) }
        }
    }

    @Test fun forwardThenBackRemovalAllowsTheNextOrdinaryStep() = runBlocking {
        withPage(2) { page, lane ->
            move(page, lane, "A", "B", "t1", setOf("A", "B"))
            move(page, lane, "B", "A", "back", setOf("A"))
            page.step(1.0 / 60)
        }
    }

    @Test fun freshlyAppendedIncomingRowBeginsAfterMountStepWithoutDrawingBetween() = runBlocking {
        withPage(2) { page, lane ->
            page.prepare("B")
            lane.call {
                page.takeIncomingOnLane("B", "A", false)
                page.setPhasesOnLane(listOf("A" to ExperienceScreenLifecycle.Phase.EXITING,
                    "B" to ExperienceScreenLifecycle.Phase.ENTERING), "t1")
                page.beginOnLane("B", "t1")
                page.endOnLane("B", "t1")
            }
        }
    }

    @Test fun fortyRowsWithThirtyNineCollapsedStepWithoutError() = runBlocking {
        withPage(40, preloadRows = true) { page, lane ->
            assertEquals(40, page.mountedScreenIds().size)
            repeat(39) { assertTrue(page.isCollapsed(screen(it + 1))) }
            repeat(60) { page.step(1.0 / 60) }
            move(page, lane, "A", "B", "t1", (0 until 40).map(::screen).toSet())
            assertTrue(page.isCollapsed("A")); assertFalse(page.isCollapsed("B"))
        }
    }
}
