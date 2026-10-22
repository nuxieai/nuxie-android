package ai.nuxie.sdk.presentation

import ai.nuxie.sdk.experiences.SystemFontProvider
import ai.nuxie.sdk.experiences.SystemFontRequirement
import ai.nuxie.sdk.runtime.*
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

/** Port of iOS ExperienceTapOnceTests, including production player selection and event routing. */
class ExperienceTapOnceDeviceTest {
    @Test
    @SdkSuppress(minSdkVersion = 31)
    fun eachTapOnARowOrACopyReportsItsEventOnce(): Unit = runBlocking {
        val assets = InstrumentationRegistry.getInstrumentation().context.assets
        fun read(name: String) = assets.open("runtime/tap-once/$name").use { it.readBytes() }
        val bytes = read("screen.riv")
        val fonts = Json.parseToJsonElement(read("provenance.json").decodeToString()).jsonObject
            .getValue("fonts").jsonArray.map { it.jsonObject }
        // As on iOS, native objects are used serially on their owning lane.
        val lane = NuxieRuntimeLane()
        try {
            for (together in listOf(false, true)) {
                val names = mutableListOf<String>()
                val coordinator = JourneyRuntimeEmissionCoordinator("tap-once", "plans", JsonObject(emptyMap()), 0, 0,
                    onEmissionBatch = { batch, _ ->
                        names += batch.emissions.map { it.name }
                        JourneyEmissionBatchResult.ACCEPTED
                    }, onPresentationRevealed = {})
                assertTrue(coordinator.reveal())
                val runtime = NuxieRuntime.shared
                val renderer = lane.call { checkNotNull(runtime.newAndroidVulkanRenderer(390, 640)) }
                try {
                    val file = lane.call {
                        assertTrue(runtime.isAvailable)
                        val catalog = checkNotNull(runtime.inspectFileAssets(bytes))
                        val external = fonts.associate { font ->
                            val name = font.getValue("assetUniqueName").jsonPrimitive.content
                            val asset = catalog.single { "${it.name}-${it.authoredId}" == name &&
                                it.authoredId == font.getValue("authoredAssetId").jsonPrimitive.long }
                            asset.ordinal to SystemFontProvider.prepare(SystemFontRequirement(name,
                                font.getValue("weight").jsonPrimitive.int,
                                font.getValue("style").jsonPrimitive.content)).bytes
                        }
                        checkNotNull(runtime.importFile(renderer, bytes, catalog, external))
                    }
                    try {
                        val artboard = lane.call {
                            checkNotNull(file.newArtboard("plans")).also {
                                it.bindDefaultViewModel("vm_runtime_root_scr_screens_splans", "vmi_runtime_scr_screens_splans")
                            }
                        }
                        try {
                            val player = lane.call { file.newExperiencePlayer(artboard, "plans") }
                            try {
                                suspend fun step(pointers: List<NuxiePlayerPointerEvent> = emptyList(), elapsed: Double = 0.0) {
                                    val (outcome, snapshot) = lane.call {
                                        player.stepTyped(elapsedSeconds = elapsed, pointers = pointers) to
                                            checkNotNull(artboard.defaultViewModelSnapshot())
                                    }
                                    assertTrue(coordinator.publish(outcome, 0uL, snapshot = snapshot))
                                }
                                suspend fun chosen() = lane.call {
                                    checkNotNull(artboard.defaultViewModelSnapshot()).resolveString("state/chosen")
                                }
                                suspend fun tap(y: Float): List<String> {
                                    names.clear()
                                    val down = NuxiePlayerPointerEvent(NuxiePlayerPointerKind.DOWN, 195f, y, 0, 0f)
                                    val up = down.copy(kind = NuxiePlayerPointerKind.UP)
                                    if (together) step(listOf(down, up)) else {
                                        step(listOf(down)); step(listOf(up))
                                    }
                                    repeat(3) { step(elapsed = 1.0 / 60.0) }
                                    return names.toList()
                                }
                                repeat(4) { step(elapsed = 1.0 / 60.0) }
                                assertEquals("monthly", chosen())
                                for ((y, value) in listOf(168f to "annual", 256f to "lifetime",
                                    84f to "monthly", 168f to "annual")) {
                                    assertEquals("y=$y together=$together", listOf("picked"), tap(y))
                                    assertEquals(value, chosen())
                                }
                                repeat(3) { assertEquals("together=$together", listOf("purchase"), tap(336f)) }
                                assertEquals("together=$together", listOf("restore"), tap(404f))
                            } finally { lane.call { player.close() } }
                        } finally { lane.call { artboard.close() } }
                    } finally { lane.call { file.close() } }
                } finally {
                    lane.call { renderer.close() }
                    coordinator.close()
                }
            }
        } finally {
            lane.shutdown()
            assertTrue("The native lane drains", lane.awaitQuiescence(5_000))
        }
    }
}
