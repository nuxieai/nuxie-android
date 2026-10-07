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
                        native.renderer.resize(393, 852)
                        var layoutScaleFactor = 1f
                        fun pixels(value: ai.nuxie.sdk.runtime.NuxieRuntimePlayer) =
                            native.renderer.renderToCpuFrame(value, 0xff112233.toInt(), layoutScaleFactor).rgba
                        player.step(0.0)
                        pixels(player)
                        repeat(20) { player.step(0.016) }
                        val initial = pixels(player)
                        var point: Pair<Float, Float>? = null
                        // Measure ink rendered with Android's font and test the copy's own hit region.
                        search@ for (y in 0 until 852) for (x in 0 until 393) {
                            val offset = (y * 393 + x) * 4
                            val background = (y * 393 + 392) * 4
                            if ((0..2).all { initial[offset + it] == initial[background + it] }) continue
                            for (subpixel in listOf(0.125f, 0.375f, 0.625f, 0.875f)) {
                                val px = x + 0.5f
                                val py = y + subpixel
                                val hit = player.stepTyped(elapsedSeconds = 0.0, pointers = listOf(
                                    ai.nuxie.sdk.runtime.NuxiePlayerPointerEvent(ai.nuxie.sdk.runtime.NuxiePlayerPointerKind.DOWN, px, py, 0, 0f)))
                                player.stepTyped(elapsedSeconds = 0.0, pointers = listOf(
                                    ai.nuxie.sdk.runtime.NuxiePlayerPointerEvent(ai.nuxie.sdk.runtime.NuxiePlayerPointerKind.EXIT, px, py, 0, 0f)))
                                if (hit.pointerHits.any { it != ai.nuxie.sdk.runtime.NuxiePlayerPointerHit.NONE }) {
                                    point = px to py
                                    break@search
                                }
                            }
                        }
                        val ink = checkNotNull(point) { "The rendered copy has interactive ink" }
                        fun hits(x: Float, y: Float): Boolean {
                            val result = player.stepTyped(elapsedSeconds = 0.0, pointers = listOf(
                                ai.nuxie.sdk.runtime.NuxiePlayerPointerEvent(ai.nuxie.sdk.runtime.NuxiePlayerPointerKind.DOWN, x, y, 0, 0f)))
                            player.stepTyped(elapsedSeconds = 0.0, pointers = listOf(
                                ai.nuxie.sdk.runtime.NuxiePlayerPointerEvent(ai.nuxie.sdk.runtime.NuxiePlayerPointerKind.EXIT, x, y, 0, 0f)))
                            return result.pointerHits.any { it != ai.nuxie.sdk.runtime.NuxiePlayerPointerHit.NONE }
                        }
                        fun edge(inside: Float, outside: Float, probe: (Float) -> Boolean): Float {
                            var yes = inside
                            var no = outside
                            repeat(20) {
                                val mid = (yes + no) / 2
                                if (probe(mid)) yes = mid else no = mid
                            }
                            return yes
                        }
                        val left = edge(ink.first, 0f) { hits(it, ink.second) }
                        val right = edge(ink.first, 393f) { hits(it, ink.second) }
                        val x = (left + right) / 2
                        val top = edge(ink.second, 0f) { hits(x, it) }
                        val bottom = edge(ink.second, 852f) { hits(x, it) }
                        assertTrue(right > left && bottom > top)
                        val tap = x to (top + bottom) / 2
                        assertArrayEquals("Probing without releasing a press leaves the count unchanged", initial, pixels(player))
                        // Use the device's physical pixel density for the count readback.
                        val density = InstrumentationRegistry.getInstrumentation().targetContext.resources.displayMetrics.density
                        layoutScaleFactor = density
                        native.renderer.resize((393 * density).toInt(), (852 * density).toInt())
                        var previous = pixels(player)
                        val otherBoard = checkNotNull(native.file.newArtboard("long"))
                        try {
                            otherBoard.bindDefaultViewModel("Runtime long scr_screens_slong")
                            assertTrue(otherBoard.linkDefaultViewModel("experience", checkNotNull(native.values)))
                            val other = native.file.newExperiencePlayer(otherBoard, "long")
                            try {
                                other.step(0.0)
                                pixels(other)
                                repeat(20) { other.step(0.016) }
                                val untouched = pixels(other)
                                val days = checkNotNull(native.values).snapshot().resolveScalar(listOf("trip_days"))
                                repeat(2) {
                                    val down = player.stepTyped(elapsedSeconds = 0.0, pointers = listOf(
                                        ai.nuxie.sdk.runtime.NuxiePlayerPointerEvent(ai.nuxie.sdk.runtime.NuxiePlayerPointerKind.DOWN, tap.first, tap.second, 0, 0f)))
                                    val up = player.stepTyped(elapsedSeconds = 0.0, pointers = listOf(
                                        ai.nuxie.sdk.runtime.NuxiePlayerPointerEvent(ai.nuxie.sdk.runtime.NuxiePlayerPointerKind.UP, tap.first, tap.second, 0, 0f)))
                                    assertTrue(down.pointerHits.any { it != ai.nuxie.sdk.runtime.NuxiePlayerPointerHit.NONE })
                                    assertTrue(up.pointerHits.any { it != ai.nuxie.sdk.runtime.NuxiePlayerPointerHit.NONE })
                                    repeat(3) { player.step(1.0 / 60.0) }
                                    val next = pixels(player)
                                    assertFalse("The private copy redraws its count on tap $it at $tap", previous.contentEquals(next))
                                    assertArrayEquals("Another copy sharing the run keeps its own counter", untouched, pixels(other))
                                    assertEquals(days, checkNotNull(native.values).snapshot().resolveScalar(listOf("trip_days")))
                                    previous = next
                                }
                            } finally { other.close() }
                        } finally { otherBoard.close() }
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
