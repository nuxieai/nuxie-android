package ai.nuxie.sdk.runtime

import ai.nuxie.sdk.fixtures.FixtureRunner
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class NuxieExperiencePlayerTest {
    @Test fun `shared selections retain scene and avoid duplicate interaction players`() {
        FixtureRunner.run("sdk/interaction-player.json", "runtime-interaction-player") { vector ->
            val body = vector.body
            val native = RecordingNative().apply {
                machines = body.getValue("machines").jsonArray.map { it.jsonPrimitive.content }
                primaryName = body.getValue("primary").jsonPrimitive.content
            }
            val file = NuxieRuntimeFile(1, native)
            val artboard = checkNotNull(file.newArtboard("Paywall"))
            val player = file.newExperiencePlayer(artboard, "Paywall")
            assertEquals(body.getValue("auxiliary").jsonPrimitive.contentOrNull?.let(::listOf) ?: emptyList<String>(), native.namedCreations)
            assertEquals(10L, player.requireHandle())
            player.close(); player.close(); artboard.close(); file.close()
            assertEquals(if (native.namedCreations.isEmpty()) listOf(10L, 2L, 1L) else listOf(11L, 10L, 2L, 1L), native.freed)
        }
    }

    @Test fun `composite frame advances once and routes inputs and ordered results`() {
        val native = RecordingNative()
        val file = NuxieRuntimeFile(1, native)
        val player = file.newExperiencePlayer(checkNotNull(file.newArtboard("Paywall")), "Paywall")
        val initial = player.stepTyped(elapsedSeconds = 0.25, correlationId = 7u)
        assertEquals(listOf(10L to 0.25f, 11L to 0f), native.steps.map { it.handle to it.elapsed })
        assertEquals(listOf("event-10", "event-11"), initial.events.map { it.name })
        assertEquals(listOf("command-10", "command-11"), initial.hostCommands.map { it.name })
        player.stepTyped(elapsedSeconds = 0.25)
        assertEquals(listOf(10L, 11L, 10L), native.steps.map { it.handle })
        val pointer = NuxiePlayerPointerEvent(NuxiePlayerPointerKind.DOWN, 1f, 2f, 3, 0f)
        val hit = player.stepTyped(listOf(NuxiePlayerInput.Trigger("submit")), listOf(pointer), 0.25, 9u)
        val pair = native.steps.takeLast(2)
        assertEquals(emptyList<String>(), pair[0].inputs)
        assertEquals(listOf("submit"), pair[1].inputs)
        assertEquals(listOf(1, 1), pair.map { it.pointers })
        assertEquals(listOf(9L, 9L), pair.map { it.correlation })
        assertEquals(listOf(NuxiePlayerPointerHit.HIT_OPAQUE), hit.pointerHits)
        assertTrue(hit.keepGoing)
        assertEquals(10L, player.requireHandle())
        player.close()
    }

    @Test fun `partial frame failure publishes no result and cannot render or retry`() {
        val native = RecordingNative().apply { failAuxiliaryStep = true }
        val file = NuxieRuntimeFile(1, native)
        val player = file.newExperiencePlayer(checkNotNull(file.newArtboard("Paywall")), "Paywall")
        assertThrows(NuxieRuntimeCallException::class.java) { player.stepTyped(elapsedSeconds = 0.25) }
        assertThrows(IllegalStateException::class.java) { player.requireHandle() }
        assertThrows(IllegalStateException::class.java) { player.stepTyped(elapsedSeconds = 0.25) }
        assertEquals(2, native.steps.size)
        player.close()
        assertEquals(listOf(11L, 10L), native.freed)
    }

    @Test fun `selection errors free primary without falling back to an incomplete scene`() {
        val native = RecordingNative().apply { failAuxiliaryCreation = true }
        val file = NuxieRuntimeFile(1, native)
        assertThrows(IllegalStateException::class.java) {
            file.newExperiencePlayer(checkNotNull(file.newArtboard("Paywall")), "Paywall")
        }
        assertEquals(listOf(10L), native.freed)
    }

    @Test fun `single generated default receives elapsed time and named inputs once`() {
        val native = RecordingNative().apply { primaryName = machines.single() }
        val file = NuxieRuntimeFile(1, native)
        val player = file.newExperiencePlayer(checkNotNull(file.newArtboard("Paywall")), "Paywall")
        player.stepTyped(listOf(NuxiePlayerInput.Trigger("submit")), elapsedSeconds = 0.25)
        assertEquals(listOf(10L to 0.25f), native.steps.map { it.handle to it.elapsed })
        assertEquals(listOf("submit"), native.steps.single().inputs)
        player.close()
    }

    private data class Step(val handle: Long, val elapsed: Float, val inputs: List<String>, val pointers: Int, val correlation: Long)
    private class RecordingNative : NuxieTypedRuntimeNative {
        var machines = listOf("Generated Nuxie Pressable Interaction")
        var primaryName = "Authored"
        var failAuxiliaryCreation = false
        var failAuxiliaryStep = false
        val namedCreations = mutableListOf<String>()
        val freed = mutableListOf<Long>()
        val steps = mutableListOf<Step>()
        override fun newNamedArtboard(fileHandle: Long, name: String) = 2L
        override fun newDefaultPlayer(artboardHandle: Long) = 10L
        override fun newNamedStateMachinePlayer(artboardHandle: Long, name: String): Long {
            namedCreations += name
            return if (failAuxiliaryCreation) 0L else 11L
        }
        override fun stateMachineNames(fileHandle: Long, artboardName: String?) = NativeCallResult(0, machines)
        override fun playerStateMachineName(playerHandle: Long) = NativeCallResult(0, primaryName)
        override fun freePlayer(handle: Long) { freed += handle }
        override fun freeArtboard(handle: Long) { freed += handle }
        override fun freeFile(handle: Long) { freed += handle }
        override fun stepPlayer(playerHandle: Long, inputs: List<NativePlayerInput>, pointers: List<NativePlayerPointer>, elapsedSeconds: Float, correlationId: Long): NativeCallResult<NativePlayerStepOutcome> {
            steps += Step(playerHandle, elapsedSeconds, inputs.map { it.name }, pointers.size, correlationId)
            if (playerHandle == 11L && failAuxiliaryStep) return NativeCallResult(4, null)
            return NativeCallResult(0, NativePlayerStepOutcome(
                playerHandle == 11L,
                IntArray(pointers.size) { if (playerHandle == 11L) 2 else 1 },
                arrayOf(NativeRuntimeEvent(0, 0, "event-$playerHandle", "", "", 0f, emptyArray())),
                arrayOf(NativeHostCommand("command-$playerHandle", NativeHostValue(0, false, 0.0, "", emptyArray(), emptyArray()))),
                emptyArray(),
            ))
        }
    }
}
