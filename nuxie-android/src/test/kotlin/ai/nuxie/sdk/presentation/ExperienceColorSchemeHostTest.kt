package ai.nuxie.sdk.presentation

import ai.nuxie.sdk.runtime.*
import android.content.res.Configuration
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.LEGACY)
class ExperienceColorSchemeHostTest {
    @Test fun `light and dark mount before the first measurement advance`() {
        for (scheme in ExperienceColorScheme.entries) withHost(scheme) { host, lane, native ->
            load(host, lane)
            assertEquals(listOf("env", "colorScheme:${scheme.ordinal}", "Theme:${scheme.ordinal}", "step:${scheme.ordinal}"), native.order)
        }
    }

    @Test fun `configuration changes rebind both values and reuse retained instances`() {
        withHost(ExperienceColorScheme.LIGHT) { host, lane, native ->
            load(host, lane)
            val light = native.boundTheme
            change(host, lane, ExperienceColorScheme.DARK)
            val dark = native.boundTheme
            assertNotEquals(light, dark)
            assertEquals(1L, native.enumValue)
            change(host, lane, ExperienceColorScheme.LIGHT)
            assertEquals(light, native.boundTheme)
            assertEquals(0L, native.enumValue)
            val count = native.order.size
            change(host, lane, ExperienceColorScheme.LIGHT)
            assertEquals("The same appearance needs no write", count, native.order.size)
            assertEquals(listOf(0, 1), native.createdThemes)
        }
    }

    @Test fun `env-only appearance changes wake the auxiliary on the next frame without input`() {
        withHost(ExperienceColorScheme.LIGHT, declaresTheme = false, hasAuxiliary = true) { host, lane, native ->
            load(host, lane)
            assertEquals("Initialization has advanced both players", listOf(3L, 5L), native.steppedPlayers)
            native.steppedPlayers.clear()
            advanceFrame(host, lane)
            assertEquals("The auxiliary is idle after initialization", listOf(3L), native.steppedPlayers)

            for (scheme in listOf(ExperienceColorScheme.DARK, ExperienceColorScheme.LIGHT)) {
                native.steppedPlayers.clear()
                change(host, lane, scheme)
                assertEquals(scheme.ordinal.toLong(), native.enumValue)
                assertTrue("There is no Theme to rebind", native.createdThemes.isEmpty())
                assertTrue("The change schedules an advance rather than stepping immediately", native.steppedPlayers.isEmpty())
                advanceFrame(host, lane)
                assertEquals("An env-only change must advance the auxiliary on the next frame",
                    listOf(3L, 5L), native.steppedPlayers)
                native.steppedPlayers.clear()
                advanceFrame(host, lane)
                assertEquals("One appearance write wakes the auxiliary once", listOf(3L), native.steppedPlayers)
            }
        }
    }

    @Test fun `a submitted frame keeps its appearance and the next hidden step uses only the latest change`() {
        withHost(ExperienceColorScheme.LIGHT) { host, lane, native ->
            load(host, lane)
            val pending = ExperienceSurfaceHost::class.java.getDeclaredField("pendingPresentation").apply { isAccessible = true }
            runBlocking { lane.call { pending.setBoolean(host, true) } }
            val before = native.order.toList()
            change(host, lane, ExperienceColorScheme.DARK)
            change(host, lane, ExperienceColorScheme.LIGHT)
            change(host, lane, ExperienceColorScheme.DARK)
            assertEquals("No binding changes underneath submitted pixels", before, native.order)
            runBlocking {
                lane.call { pending.setBoolean(host, false) }
                withTimeout(5_000) { host.applyTransitionValues(emptyMap()) }
            }
            assertEquals(listOf("colorScheme:1", "Theme:1", "step:1"), native.order.takeLast(3))
            assertEquals(listOf(0, 1), native.createdThemes)
        }
    }

    @Test fun `a covered screen takes current configuration when shown again`() {
        withHost(ExperienceColorScheme.LIGHT) { host, lane, native ->
            load(host, lane)
            host.setPresentationVisible(false)
            // A detached view can miss configuration dispatch, as an iOS cached screen can miss traits.
            RuntimeEnvironment.setQualifiers("+night")
            host.setPresentationVisible(true)
            drain(lane)
            assertEquals(1L, native.enumValue)
            assertEquals(1, native.themeIndex.getValue(native.boundTheme))
        }
    }

    @Test fun `legacy files receive no appearance mutations or Theme allocations`() {
        withHost(ExperienceColorScheme.DARK, declaresAppearance = false) { host, lane, native ->
            load(host, lane)
            change(host, lane, ExperienceColorScheme.LIGHT)
            assertEquals(listOf("step:null"), native.order)
            assertTrue(native.createdThemes.isEmpty())
        }
    }

    private fun load(host: ExperienceSurfaceHost, lane: NuxieRuntimeLane) {
        var loaded: Boolean? = null
        host.loadArtboard(byteArrayOf(1), null) { loaded = it }
        drain(lane)
        assertEquals(true, loaded)
    }

    private fun change(host: ExperienceSurfaceHost, lane: NuxieRuntimeLane, scheme: ExperienceColorScheme) {
        host.dispatchConfigurationChanged(Configuration(host.resources.configuration).apply { uiMode = mode(scheme) })
        drain(lane)
    }

    private fun advanceFrame(host: ExperienceSurfaceHost, lane: NuxieRuntimeLane) = runBlocking {
        lane.call {
            val player = ExperienceSurfaceHost::class.java.getDeclaredField("player")
                .apply { isAccessible = true }.get(host) as NuxieRuntimePlayer
            player.stepTyped(elapsedSeconds = 0.016)
        }
    }

    private fun withHost(
        scheme: ExperienceColorScheme,
        declaresAppearance: Boolean = true,
        declaresTheme: Boolean = true,
        hasAuxiliary: Boolean = false,
        block: (ExperienceSurfaceHost, NuxieRuntimeLane, RecordingNative) -> Unit,
    ) {
        RuntimeEnvironment.setQualifiers(if (scheme == ExperienceColorScheme.DARK) "+night" else "+notnight")
        val native = RecordingNative(declaresAppearance, declaresTheme, hasAuxiliary)
        val lane = NuxieRuntimeLane()
        val failures = mutableListOf<ExperiencePresentationException>()
        val host = ExperienceSurfaceHost(RuntimeEnvironment.getApplication(), lane, runtime = NuxieRuntime(native),
            initialPresentationSize = ExperienceArtboardSize(393f, 852f), usesSystemFrameCallbacks = false,
            listener = object : ExperienceSurfaceHost.Listener {
                override fun onFirstFrame() = Unit
                override fun onFailure(error: ExperiencePresentationException) { failures += error }
            })
        try {
            block(host, lane, native)
            assertTrue("Appearance must not fail presentation: $failures", failures.isEmpty())
        } finally {
            host.release()
            lane.shutdown()
            assertTrue(lane.awaitQuiescence(5_000))
            assertEquals("Every screen-owned instance is freed once", native.allocated.sorted(), native.freed.sorted())
        }
    }

    private fun drain(lane: NuxieRuntimeLane) = runBlocking { lane.call {} }

    private fun mode(scheme: ExperienceColorScheme) =
        if (scheme == ExperienceColorScheme.DARK) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO

    private class RecordingNative(
        private val declaresAppearance: Boolean,
        private val declaresTheme: Boolean,
        private val hasAuxiliary: Boolean,
    ) : NuxieTypedRuntimeNative {
        override val isAvailable = true
        val order = mutableListOf<String>()
        val allocated = mutableListOf<Long>()
        val freed = mutableListOf<Long>()
        val createdThemes = mutableListOf<Int?>()
        val themeIndex = mutableMapOf<Long, Int?>()
        val steppedPlayers = mutableListOf<Long>()
        var boundTheme = 0L
        var enumValue: Long? = null
        override fun inspectFileAssets(bytes: ByteArray) = emptyList<ExpectedFileAsset>()
        override fun newAndroidVulkanRenderer(pixelWidth: Int, pixelHeight: Int) = 4L
        override fun freeRenderer(handle: Long) = Unit
        override fun newFile(rendererHandle: Long, bytes: ByteArray, expectedAssets: List<ExpectedFileAsset>,
            externalAssets: Map<Int, ByteArray>, imageDecoder: NuxImageDecoder, videoEnabled: Boolean) = 1L
        override fun freeFile(handle: Long) = Unit
        override fun newDefaultArtboard(fileHandle: Long) = 2L
        override fun freeArtboard(handle: Long) = Unit
        override fun viewModelCatalog(fileHandle: Long) = NativeCallResult(0, if (declaresAppearance) NativeViewModelCatalog(
            arrayOf(NativeViewModelSchema(0, "env", 0, 1, 0, 0, -1, true)) +
                if (declaresTheme) arrayOf(NativeViewModelSchema(1, "Theme", 1, 0, 0, 2, 0, true)) else emptyArray(),
            arrayOf(NativeViewModelProperty(0, 0, "colorScheme", 5, -1, arrayOf("light", "dark"))),
            if (declaresTheme) arrayOf(NativeViewModelAuthoredInstance(1, 0, "light"),
                NativeViewModelAuthoredInstance(1, 1, "dark")) else emptyArray(),
        ) else NativeViewModelCatalog(emptyArray(), emptyArray(), emptyArray()))
        override fun newViewModel(fileHandle: Long, schemaIndex: Int, authoredInstanceIndex: Int?): NativeCallResult<Long> {
            val handle = 10L + allocated.size
            allocated += handle
            if (schemaIndex == 1) { createdThemes += authoredInstanceIndex; themeIndex[handle] = authoredInstanceIndex }
            return NativeCallResult(0, handle)
        }
        override fun freeViewModel(handle: Long): Int { freed += handle; return 0 }
        override fun mutateViewModel(handle: Long, write: NativeViewModelWrite): Int {
            assertEquals(NuxieViewModelMutationKind.SET_ENUM, write.kind)
            assertEquals("colorScheme", write.path)
            enumValue = write.integerValue
            order += "colorScheme:${write.integerValue}"
            return 0
        }
        override fun stateMachineNames(fileHandle: Long, artboardName: String?) =
            NativeCallResult(0, if (hasAuxiliary) listOf("Generated Nuxie Interaction") else emptyList())
        override fun playerStateMachineName(playerHandle: Long) = NativeCallResult(0, "Authored")
        override fun newNamedStateMachinePlayer(artboardHandle: Long, name: String) = 5L
        override fun newDefaultPlayer(artboardHandle: Long) = 3L
        override fun playerKind(playerHandle: Long) = NativeCallResult(0, 1)
        override fun freePlayer(handle: Long) = Unit
        override fun setPlayerGlobalViewModel(playerHandle: Long, name: ByteArray, viewModelHandle: Long): Int {
            when (name.decodeToString()) {
                "env" -> order += "env"
                "Theme" -> { boundTheme = viewModelHandle; order += "Theme:${themeIndex[viewModelHandle]}" }
                else -> error("Unexpected global")
            }
            return 0
        }
        override fun setPlayerLayoutSize(playerHandle: Long, width: Float, height: Float) = 0
        override fun playerLayoutSize(playerHandle: Long) = NativeCallResult(0, floatArrayOf(393f, 852f))
        override fun playerFocusState(playerHandle: Long) = NativeCallResult(0, NuxieFocusState(false, false))
        override fun stepPlayer(playerHandle: Long, inputs: List<NativePlayerInput>, pointers: List<NativePlayerPointer>,
            elapsedSeconds: Float, correlationId: Long, textRunNames: List<String>, focusInputs: List<NativeFocusInput>): NativeCallResult<NativePlayerStepOutcome> {
            if (declaresAppearance && declaresTheme) assertEquals(enumValue?.toInt(), themeIndex.getValue(boundTheme))
            steppedPlayers += playerHandle
            order += "step:$enumValue"
            return NativeCallResult(0, NativePlayerStepOutcome(false, intArrayOf(), emptyArray(), emptyArray(), emptyArray()))
        }
    }
}
