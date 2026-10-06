package ai.nuxie.sdk.runtime

import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class NuxieFocusDeviceTest {
    private val assets get() = InstrumentationRegistry.getInstrumentation().context.assets
    private val native = JniNuxieTypedRuntimeNative

    private fun withPlayer(name: String, model: String, artboardName: String,
        block: (NuxieRuntimePlayer, NativeViewModelSnapshot) -> Unit) {
        val runtime = NuxieRuntime.shared
        assertTrue(runtime.isAvailable)
        val bytes = assets.open("runtime/rive-focus/$name.riv").use { it.readBytes() }
        val renderer = checkNotNull(runtime.newAndroidVulkanRenderer(64, 64))
        try {
            val file = checkNotNull(runtime.importFile(renderer, bytes, checkNotNull(runtime.inspectFileAssets(bytes))))
            try {
                val artboard = checkNotNull(file.newArtboard(artboardName))
                try {
                    val root = checkNotNull(native.newDefaultViewModel(artboard.requireHandle()).value)
                    try {
                        assertEquals(0, native.bindViewModel(artboard.requireHandle(), root))
                        val catalog = checkNotNull(native.viewModelCatalog(file.requireHandle()).value).toViewModelCatalog()
                        val schema = checkNotNull(native.viewModelRootSchemaIndex(root).value)
                        assertEquals(model, catalog.schemas.single { it.index.toLong() == schema }.name)
                        val player = checkNotNull(artboard.newPlayer("State Machine 1"))
                        try {
                            player.stepTyped(elapsedSeconds = 0.0)
                            block(player, checkNotNull(native.snapshotViewModel(root).value))
                        } finally { player.close() }
                    } finally { assertEquals(0, native.freeViewModel(root)) }
                } finally { artboard.close() }
            } finally { file.close() }
        } finally { renderer.close() }
    }

    @Test fun textFocusAndKeyChangesComeBackInTheirStep() =
        withPlayer("text_input_event", "ViewModel1", "Artboard") { player, snapshot ->
            val root = snapshot.rootInstanceId.toULong()
            val properties = snapshot.values.filter { it.ownerInstanceId == snapshot.rootInstanceId }
            val names = listOf("isFocused", "hasKeyed", "hasTexted")
            val indices = names.map { name -> properties.single { it.name == name }.propertyIndex.toInt() }
            val kept = properties.associate { it.propertyIndex.toInt() to it.boolValue }.toMutableMap()
            val oracle = JSONObject(assets.open("runtime/rive-focus/expectations.json").bufferedReader().use { it.readText() })
            val checks = oracle.getJSONObject("text").getJSONArray("checks")
            val inputs = listOf(NuxieFocusInput.Next, NuxieFocusInput.Key(66, 0, true, false),
                NuxieFocusInput.Text("b"), NuxieFocusInput.Key(65, 0, true, false))
            inputs.forEachIndexed { index, input ->
                val step = player.stepTyped(elapsedSeconds = 0.016, focusInputs = listOf(input))
                assertEquals(1, step.focusResults.size)
                assertEquals(NuxieFocusState(true, true), step.focusState)
                step.viewModelChanges.filter { it.ownerInstanceId == root }.forEach {
                    kept[it.propertyIndex] = (it.value as NuxieViewModelValue.Bool).value
                }
                val expected = checks.getJSONArray(index)
                assertEquals((0..2).map { expected.getBoolean(it) }, indices.map { kept.getValue(it) })
            }
            val cleared = player.stepTyped(elapsedSeconds = 0.0, focusInputs = listOf(NuxieFocusInput.Clear))
            assertEquals(listOf(false), cleared.focusResults)
            assertFalse(checkNotNull(cleared.focusState).hasFocus)
            val idle = player.stepTyped(elapsedSeconds = 0.0)
            assertTrue(idle.focusResults.isEmpty())
            assertNull(idle.focusState)
        }
    @Test fun compositePlayersMoveFocusOnlyOncePerInput() {
        val runtime = NuxieRuntime.shared
        assertTrue(runtime.isAvailable)
        val bytes = assets.open("runtime/composite-focus/screen.riv").use { it.readBytes() }
        val oracle = JSONObject(assets.open("runtime/composite-focus/provenance.json")
            .bufferedReader().use { it.readText() }).getJSONObject("expected")
        val renderer = checkNotNull(runtime.newAndroidVulkanRenderer(100, 100))
        try {
            val file = checkNotNull(runtime.importFile(renderer, bytes))
            try {
                val artboard = checkNotNull(file.newArtboard("Composite"))
                try {
                    val player = checkNotNull(artboard.newPlayer())
                    try {
                        assertEquals("Primary", native.playerStateMachineName(player.requireHandle()).value)
                        player.installInteractionPlayer(checkNotNull(artboard.newPlayer("Auxiliary")))
                        player.stepTyped(elapsedSeconds = 0.0)
                        listOf(NuxieFocusInput.Next to "next", NuxieFocusInput.Next to "nextAgain",
                            NuxieFocusInput.Previous to "previous").forEach { (input, name) ->
                            val step = player.stepTyped(elapsedSeconds = 0.0, focusInputs = listOf(input))
                            assertEquals(listOf(true), step.focusResults)
                            val expected = oracle.getJSONArray(name)
                            assertEquals((0 until expected.length()).map { expected.getString(it) },
                                step.events.map { it.name })
                            assertEquals(NuxieFocusState(true, false), step.focusState)
                            assertTrue(player.stepTyped(elapsedSeconds = 0.0).events.isEmpty())
                        }
                    } finally { player.close() }
                } finally { artboard.close() }
            } finally { file.close() }
        } finally { renderer.close() }
    }

    @Test fun exactNativeLimitsAreAcceptedAndOversizedBatchesRefused() =
        withPlayer("text_input_event", "ViewModel1", "Artboard") { player, _ ->
            assertEquals(List(4_096) { false }, player.stepTyped(elapsedSeconds = 0.0,
                focusInputs = List(4_096) { NuxieFocusInput.Clear }).focusResults)
            assertEquals(listOf(false, false, false, false), player.stepTyped(elapsedSeconds = 0.0,
                focusInputs = List(4) { NuxieFocusInput.Text("a".repeat(1_048_576)) }).focusResults)
            assertThrows(IllegalArgumentException::class.java) {
                player.stepTyped(elapsedSeconds = 0.0, focusInputs = List(4_097) { NuxieFocusInput.Next })
            }
            assertThrows(IllegalArgumentException::class.java) {
                player.stepTyped(elapsedSeconds = 0.0,
                    focusInputs = listOf(NuxieFocusInput.Text("a".repeat(1_048_577))))
            }
        }

    @Test fun jniRejectsInvalidFocusInputsBeforeStepping() =
        withPlayer("text_input_event", "ViewModel1", "Artboard") { player, _ ->
            val invalid = listOf(
                List(4_097) { NativeFocusInput(0) },
                listOf(NativeFocusInput(4, text = ByteArray(1_048_577))),
                List(5) { NativeFocusInput(4, text = ByteArray(1_048_576)) },
                listOf(NativeFocusInput(3, code = 65, modifiers = 16)),
                listOf(NativeFocusInput(3, code = 65_536)),
                listOf(NativeFocusInput(5)),
            )
            invalid.forEach { inputs ->
                val result = native.stepPlayer(player.requireHandle(), emptyList(), emptyList(), 0f, 0L,
                    focusInputs = inputs)
                assertEquals(5, result.status)
                assertNull(result.value)
            }
            assertEquals(listOf(true), player.stepTyped(elapsedSeconds = 0.0,
                focusInputs = listOf(NuxieFocusInput.Next)).focusResults)
        }

}
