package ai.nuxie.sdk.runtime

import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class GlobalInstallRuntimeDeviceTest {
    @Test fun globalSetRefusalMutationAndClearReachScreenCopyAndListRow() = runBlocking {
        val runtime = NuxieRuntime.shared
        assertTrue(runtime.isAvailable)
        val bytes = InstrumentationRegistry.getInstrumentation().context.assets
            .open("runtime/global-values/globals.riv").use { it.readBytes() }
        val expected = InstrumentationRegistry.getInstrumentation().context.assets
            .open("runtime/global-values/expectations.json").bufferedReader().use { Json.parseToJsonElement(it.readText()).jsonObject }
        fun labelsFor(key: String) = expected.getValue(key).jsonArray.map { it.jsonPrimitive.content }
        fun statusFor(key: String) = expected.getValue(key).jsonPrimitive.int
        val lane = NuxieRuntimeLane()
        try {
            lane.call {
                val renderer = checkNotNull(runtime.newAndroidVulkanRenderer(400, 100))
                try {
                    val file = checkNotNull(runtime.importFile(renderer, bytes))
                    try {
                        val artboard = checkNotNull(file.newArtboard("GlobalValuesScreen"))
                        try {
                            artboard.bindDefaultViewModel("Root")
                            val player = checkNotNull(artboard.newPlayer("Main"))
                            val flags = checkNotNull(file.newAuthoredViewModel("Flags", 0))
                            val wrong = checkNotNull(file.newAuthoredViewModel("Root", 0))
                            try {
                                player.enableSemantics()
                                fun labels(): List<String> {
                                    player.stepTyped(elapsedSeconds = 0.0)
                                    renderer.renderToCpuFrame(player, 0xff000000.toInt(), 1f)
                                    val capture = player.captureSemantics()
                                    try { return capture.tree.nodes.map { it.label }.sorted() }
                                    finally { capture.close() }
                                }
                                fun rejected(status: Int, call: () -> Unit) {
                                    val error = runCatching(call).exceptionOrNull()
                                    assertTrue("Expected native refusal, got $error", error is NuxieRuntimeCallException)
                                    assertEquals(status, (error as NuxieRuntimeCallException).status)
                                }
                                flags.setValue("enabled", NuxieViewModelScalarValue.BooleanValue(true))
                                player.bindGlobalViewModel("Flags", flags)
                                assertEquals(labelsFor("enabledLabels"), labels())
                                rejected(statusFor("notFound")) { player.bindGlobalViewModel("missing", flags) }
                                rejected(statusFor("notFound")) { player.bindGlobalViewModel("Root", wrong) }
                                rejected(statusFor("handleMismatch")) { player.bindGlobalViewModel("Flags", wrong) }
                                assertEquals(labelsFor("enabledLabels"), labels())
                                flags.setValue("enabled", NuxieViewModelScalarValue.BooleanValue(false))
                                assertEquals(labelsFor("disabledLabels"), labels())
                            } finally { wrong.close(); flags.close(); player.close() }
                        } finally { artboard.close() }
                    } finally { file.close() }
                } finally { renderer.close() }
            }
        } finally { lane.shutdown(); assertTrue(lane.awaitQuiescence(2000)) }
    }
}
