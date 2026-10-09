package ai.nuxie.sdk.presentation

import ai.nuxie.sdk.runtime.*
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class ChoicesSetDeviceTest {
    @Test fun publishedScriptReplacesChoicesAtomicallyAndNilKeepsRecords() = runBlocking {
        assertTrue(NuxieRuntime.shared.isAvailable)
        val assets = InstrumentationRegistry.getInstrumentation().context.assets
        fun read(name: String) = assets.open("runtime/choices-set/$name").use { it.readBytes() }
        val release = Json.parseToJsonElement(read("release.json").decodeToString()).jsonObject
        val run = ExperienceRunValues()
        try {
            run.lane.call {
                val native = run.prepare(read("screen.riv"), release, emptyMap())
                val values = checkNotNull(native.values)
                val board = checkNotNull(native.file.newArtboard("first"))
                try {
                    board.bindDefaultViewModel("Runtime first scr_screens_sfirst")
                    assertTrue(board.linkDefaultViewModel("experience", values))
                    val player = native.file.newExperiencePlayer(board, "first")
                    try {
                        player.enableSemantics()
                        assertEquals(0, player.step(0.0))
                        native.renderer.resize(300, 300)
                        assertChoices(values.nativeSnapshot(), listOf(true, false, false), emptyList())
                        // Literal oracles match the independently authored shared fixture.
                        val picks = listOf(listOf(true, false, false), listOf(false, true, false), listOf(false, false, false))
                        for (index in 0..2) {
                            native.renderer.renderToCpuFrame(player, 0xff000000.toInt(), 1f)
                            val capture = player.captureSemantics()
                            val button = try {
                                capture.tree.nodes.first { it.label == "Save" || it.value == "Save" }
                            } finally { capture.close() }
                            assertTrue(button.maxX > button.minX && button.maxY > button.minY)
                            val x = (button.minX + button.maxX) / 2
                            val y = (button.minY + button.maxY) / 2
                            val down = player.stepTyped(elapsedSeconds = 0.0, pointers = listOf(
                                NuxiePlayerPointerEvent(NuxiePlayerPointerKind.DOWN, x, y, 0, 0f)))
                            assertTrue("The published Save control must receive the tap",
                                down.pointerHits.any { it != NuxiePlayerPointerHit.NONE })
                            val up = player.stepTyped(elapsedSeconds = 0.0, pointers = listOf(
                                NuxiePlayerPointerEvent(NuxiePlayerPointerKind.UP, x, y, 0, 0f)))
                            val advance = player.stepTyped(elapsedSeconds = 1.0 / 60.0)
                            val commands = (down.hostCommands + up.hostCommands + advance.hostCommands).filter { it.name == "checked" }
                            assertEquals(1, commands.size)
                            val command = commands.single().value as NuxieHostValue.Object
                            val expected = mutableMapOf<String, NuxieHostValue>("ok" to NuxieHostValue.Bool(index != 0))
                            if (index == 0) expected["rule"] = NuxieHostValue.String("maxItems")
                            assertEquals(expected, command.fields.associate { it.key to it.value })
                            assertChoices(values.nativeSnapshot(), picks[index],
                                if (index == 0) listOf(listOf("maxItems", "Choose fewer options.")) else emptyList())
                        }
                    } finally { player.close() }
                } finally { board.close() }
            }
        } finally { run.retire() }
    }

    private fun assertChoices(snapshot: NativeViewModelSnapshot, picked: List<Boolean>, errors: List<List<String>>) {
        fun value(owner: Long, name: String) = snapshot.values.single { it.ownerInstanceId == owner && it.name == name }
        val answers = value(snapshot.rootInstanceId, "responses:signup").referencedInstanceId
        val records = value(answers, "picks").listItemIds
        assertEquals(3, records.size)
        assertEquals(picked, records.map { value(it, "picked").boolValue })
        assertEquals(listOf("a", "b", "c"), records.map { value(it, "value").bytesValue.decodeToString() })
        val errorOwner = value(answers, "errors").referencedInstanceId
        val rows = value(errorOwner, "picks").listItemIds
        assertEquals(errors, rows.map { listOf(value(it, "rule").bytesValue.decodeToString(), value(it, "message").bytesValue.decodeToString()) })
    }
}
