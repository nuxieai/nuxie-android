package ai.nuxie.sdk.presentation

import ai.nuxie.sdk.runtime.NuxieViewModelScalarValue
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class SharedValuesDeviceTest {
    @Test fun retainedScreensExcludeSharedValuesAndOtherRunsStartFresh() = runBlocking {
        assertTrue(ai.nuxie.sdk.runtime.NuxieRuntime.shared.isAvailable)
        val assets = InstrumentationRegistry.getInstrumentation().context.assets
        val bytes = assets.open("runtime/shared-values/screen.riv").use { it.readBytes() }
        val run = ExperienceRunValues()
        val other = ExperienceRunValues()
        try {
            run.lane.call {
                val native = run.prepare(bytes, null, emptyMap())
                val values = checkNotNull(native.values)
                val first = checkNotNull(native.file.newArtboard("first"))
                try {
                    first.bindDefaultViewModel("Runtime first scr_screens_sfirst")
                    assertTrue(first.linkDefaultViewModel("experience", values))
                    first.setDefaultViewModelValue("experience/trip_days", NuxieViewModelScalarValue.NumberValue(30.0))
                    val retained = checkNotNull(first.defaultViewModelSnapshot()).withoutRootProperty("experience")
                    assertNull(retained.resolveScalar(listOf("experience", "trip_days")))
                    val later = checkNotNull(native.file.newArtboard("first"))
                    try {
                        val restored = ai.nuxie.sdk.runtime.NuxieRuntime.shared.restoreViewModel(native.file, later, retained)
                        try {
                            assertTrue(restored.linkViewModel("experience", values))
                            assertEquals(NuxieViewModelScalarValue.NumberValue(30.0), restored.snapshot().resolveScalar(listOf("experience", "trip_days")))
                        } finally { restored.close() }
                    } finally { later.close() }
                } finally { first.close() }
                val later = checkNotNull(run.prepare(bytes.copyOf(), null, emptyMap()).file.newArtboard("short"))
                try {
                    later.bindDefaultViewModel("Runtime short scr_screens_sshort")
                    assertTrue(later.linkDefaultViewModel("experience", values))
                    assertEquals(NuxieViewModelScalarValue.NumberValue(30.0), checkNotNull(later.defaultViewModelSnapshot()).resolveScalar(listOf("experience", "trip_days")))
                } finally { later.close() }
            }
            other.lane.call {
                val values = checkNotNull(other.prepare(bytes, null, emptyMap()).values)
                assertEquals(NuxieViewModelScalarValue.NumberValue(23.0), values.snapshot().resolveScalar(listOf("trip_days")))
            }
        } finally { run.retire(); other.retire() }
    }

    @Test fun retirementReleasesTheRunHandleWhileMountedRootCanClose() = runBlocking {
        assertTrue(ai.nuxie.sdk.runtime.NuxieRuntime.shared.isAvailable)
        val bytes = InstrumentationRegistry.getInstrumentation().context.assets.open("runtime/shared-values/screen.riv").use { it.readBytes() }
        val run = ExperienceRunValues()
        run.retainScreen()
        var root: ai.nuxie.sdk.runtime.NuxieRuntimeArtboard? = null
        try {
            val native = run.lane.call {
                run.prepare(bytes, null, emptyMap()).also { prepared ->
                    root = checkNotNull(prepared.file.newArtboard("first"))
                    checkNotNull(root).bindDefaultViewModel("Runtime first scr_screens_sfirst")
                    assertTrue(checkNotNull(root).linkDefaultViewModel("experience", checkNotNull(prepared.values)))
                }
            }
            run.retire()
            run.lane.call {
                assertThrows(IllegalStateException::class.java) { checkNotNull(native.values).snapshot() }
                assertThrows(IllegalStateException::class.java) { run.prepare(bytes, null, emptyMap()) }
                assertEquals(NuxieViewModelScalarValue.NumberValue(23.0), checkNotNull(checkNotNull(root).defaultViewModelSnapshot()).resolveScalar(listOf("experience", "trip_days")))
            }
        } finally {
            try { run.retire() } finally {
                try { run.lane.call { root?.close() } } finally {
                    val closed = kotlinx.coroutines.CompletableDeferred<Unit>()
                    run.releaseScreen { closed.complete(Unit) }
                    closed.await()
                }
            }
        }
    }

    @Test fun fileWithoutExperienceKeepsAuthoredScreenValues() = runBlocking {
        assertTrue(ai.nuxie.sdk.runtime.NuxieRuntime.shared.isAvailable)
        val bytes = InstrumentationRegistry.getInstrumentation().context.assets.open("runtime/purchase-scopes/screen.riv").use { it.readBytes() }
        val run = ExperienceRunValues()
        try {
            run.lane.call {
                val native = run.prepare(bytes, null, emptyMap())
                assertNull(native.values)
                val root = checkNotNull(native.file.newArtboard("Purchase"))
                try {
                    root.bindDefaultViewModel("PurchaseRoot")
                    val player = native.file.newExperiencePlayer(root, "Purchase")
                    try {
                        assertEquals(0, player.step(0.0))
                        assertEquals("plan:annual", checkNotNull(root.defaultViewModelSnapshot()).resolveString("second/placementId"))
                    } finally { player.close() }
                } finally { root.close() }
            }
        } finally { run.retire() }
    }

    @Test fun componentCopyKeepsItsOwnCounter() = runBlocking {
        assertTrue(ai.nuxie.sdk.runtime.NuxieRuntime.shared.isAvailable)
        val assets = InstrumentationRegistry.getInstrumentation().context.assets
        fun read(name: String) = assets.open("runtime/shared-values/$name").use { it.readBytes() }
        val bytes = read("screen.riv")
        val expected = Json.parseToJsonElement(read("expectations.json").decodeToString()).jsonObject.getValue("component").jsonObject
        val fonts = Json.parseToJsonElement(read("provenance.json").decodeToString()).jsonObject.getValue("fonts").jsonArray
        val descriptor = buildJsonObject { putJsonObject("render") {
            put("assets", JsonArray(fonts.map { JsonObject(it.jsonObject + ("kind" to JsonPrimitive("font"))) }))
        } }
        val run = ExperienceRunValues()
        try {
            run.lane.call {
                val native = run.prepare(bytes, descriptor, emptyMap())
                val board = checkNotNull(native.file.newArtboard(expected.getValue("screen").jsonPrimitive.content))
                try {
                    board.bindDefaultViewModel("Runtime long scr_screens_slong")
                    assertTrue(board.linkDefaultViewModel("experience", checkNotNull(native.values)))
                    val player = native.file.newExperiencePlayer(board, "long")
                    try {
                        val catalog = native.file.viewModelCatalog()
                        val schema = catalog.schemas.single { it.name == "Untitled" }
                        val property = catalog.properties.single { it.schemaIndex == schema.index && it.name == "state:" + expected.getValue("privateValue").jsonPrimitive.content }
                        native.renderer.resize(393, 852)
                        player.step(0.0)
                        native.renderer.renderToCpuFrame(player, 0xff000000.toInt(), true)
                        repeat(20) { player.step(0.016) }
                        for (key in listOf("afterOneTap", "afterTwoTaps")) {
                            val down = player.stepTyped(elapsedSeconds = 0.0, pointers = listOf(ai.nuxie.sdk.runtime.NuxiePlayerPointerEvent(ai.nuxie.sdk.runtime.NuxiePlayerPointerKind.DOWN, 100f, 60f, 1, 0f)))
                            val up = player.stepTyped(elapsedSeconds = 0.0, pointers = listOf(ai.nuxie.sdk.runtime.NuxiePlayerPointerEvent(ai.nuxie.sdk.runtime.NuxiePlayerPointerKind.UP, 100f, 60f, 1, 0.1f)))
                            val numbers = (down.viewModelChanges + up.viewModelChanges).filter { it.propertyIndex == property.index }
                                .mapNotNull { (it.value as? ai.nuxie.sdk.runtime.NuxieViewModelValue.Number)?.value }
                            assertTrue("Counter changes=$numbers, hits=${down.pointerHits}, ${up.pointerHits}", numbers.contains(expected.getValue(key).jsonPrimitive.float))
                        }
                    } finally { player.close() }
                } finally { board.close() }
            }
        } finally { run.retire() }
    }

    @Test fun rootsShareTheAuthoredRunInstance() = runBlocking {
        assertTrue(ai.nuxie.sdk.runtime.NuxieRuntime.shared.isAvailable)
        val assets = InstrumentationRegistry.getInstrumentation().context.assets
        val bytes = assets.open("runtime/shared-values/screen.riv").use { it.readBytes() }
        val expected = Json.parseToJsonElement(assets.open("runtime/shared-values/expectations.json").bufferedReader().use { it.readText() }).jsonObject
        val provenance = Json.parseToJsonElement(assets.open("runtime/shared-values/provenance.json").bufferedReader().use { it.readText() }).jsonObject
        val descriptor = buildJsonObject {
            putJsonObject("render") {
                put("assets", JsonArray(provenance.getValue("fonts").jsonArray.map {
                    JsonObject(it.jsonObject + ("kind" to JsonPrimitive("font")))
                }))
            }
        }
        val run = ExperienceRunValues()
        try {
            run.lane.call {
                val native = run.prepare(bytes, descriptor, emptyMap())
                val values = checkNotNull(native.values)
                val defaults = values.snapshot()
                val starts = expected.getValue("startingValues").jsonObject
                assertEquals(starts.getValue("trip_days").jsonPrimitive.double, defaults.resolveScalar(listOf("trip_days"))?.let { (it as NuxieViewModelScalarValue.NumberValue).value })
                assertEquals(NuxieViewModelScalarValue.StringValue(starts.getValue("trip").jsonPrimitive.content), defaults.resolveScalar(listOf("trip")))
                assertEquals(NuxieViewModelScalarValue.BooleanValue(starts.getValue("wants_reminder").jsonPrimitive.boolean), defaults.resolveScalar(listOf("wants_reminder")))
                val first = checkNotNull(native.file.newArtboard("first"))
                val second = checkNotNull(native.file.newArtboard("long"))
                try {
                    first.bindDefaultViewModel("Runtime first scr_screens_sfirst")
                    second.bindDefaultViewModel("Runtime long scr_screens_slong")
                    first.linkDefaultViewModel("experience", values)
                    second.linkDefaultViewModel("experience", values)
                    val firstPlayer = checkNotNull(first.newPlayer())
                    val secondPlayer = checkNotNull(second.newPlayer())
                    try {
                    first.setDefaultViewModelValue("experience/trip_days", NuxieViewModelScalarValue.NumberValue(30.0))
                    assertEquals(0, firstPlayer.step(0.0))
                    assertEquals(0, secondPlayer.step(0.0))
                    val firstSnapshot = checkNotNull(first.defaultViewModelSnapshot())
                    val secondSnapshot = checkNotNull(second.defaultViewModelSnapshot())
                    assertTrue(firstSnapshot.containsInstance(defaults.nativeRootInstanceId))
                    assertTrue(secondSnapshot.containsInstance(defaults.nativeRootInstanceId))
                    assertEquals(NuxieViewModelScalarValue.NumberValue(30.0), firstSnapshot.resolveScalar(listOf("experience", "trip_days")))
                    assertEquals(NuxieViewModelScalarValue.NumberValue(30.0), secondSnapshot.resolveScalar(listOf("experience", "trip_days")))
                    } finally { secondPlayer.close(); firstPlayer.close() }
                } finally { second.close(); first.close() }
            }
        } finally { run.retire() }
    }
}
