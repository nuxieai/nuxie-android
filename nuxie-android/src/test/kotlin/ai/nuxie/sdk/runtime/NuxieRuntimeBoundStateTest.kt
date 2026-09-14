package ai.nuxie.sdk.runtime

import ai.nuxie.sdk.fixtures.FixtureRunner
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class NuxieRuntimeBoundStateTest {
    @Test
    fun `shared scalar updates use the already bound root in both presentation paths`() {
        val fixture = Json.parseToJsonElement(FixtureRunner.fixturesRoot()
            .resolve("journeys/planes/runtime-bound-state.json").readText()).jsonObject
        for (commerce in listOf(false, true)) {
            val native = RecordingNative()
            val file = NuxieRuntimeFile(10, native)
            val artboard = checkNotNull(file.newArtboard())
            val projected = if (commerce) NuxieRuntime(native).bindViewModelList(
                file, artboard, NuxieViewModelListProjection("Root", "products", null, "Product", emptyList()),
            ) else null
            if (!commerce) artboard.bindDefaultViewModel("Root")
            val player = checkNotNull(artboard.newPlayer())
            fun write(path: String, value: NuxieViewModelScalarValue) {
                if (projected != null) projected.setValue(path, value)
                else assertTrue(artboard.setDefaultViewModelValue(path, value))
            }
            for (raw in fixture.getValue("writes").jsonArray) {
                val item = raw.jsonObject
                val path = item.getValue("path").jsonPrimitive.content
                val value = item.getValue("value").jsonPrimitive
                val kind = item.getValue("kind").jsonPrimitive.content
                write(path, when (kind) {
                    "string" -> NuxieViewModelScalarValue.StringValue(value.content)
                    "number" -> NuxieViewModelScalarValue.NumberValue(value.double)
                    "boolean" -> NuxieViewModelScalarValue.BooleanValue(value.boolean)
                    else -> error("Unknown fixture kind")
                })
                val mutation = native.writes.last()
                assertEquals(path, mutation.path)
                when (kind) {
                    "string" -> {
                        assertEquals(NuxieViewModelMutationKind.SET_STRING, mutation.kind)
                        assertEquals(value.content, mutation.bytesValue.decodeToString())
                    }
                    "number" -> {
                        assertEquals(NuxieViewModelMutationKind.SET_NUMBER, mutation.kind)
                        assertEquals(value.double.toFloat(), mutation.numberValue)
                    }
                    "boolean" -> {
                        assertEquals(NuxieViewModelMutationKind.SET_BOOLEAN, mutation.kind)
                        assertEquals(value.boolean, mutation.boolValue)
                    }
                }
            }
            val count = native.writes.size
            assertThrows(IllegalArgumentException::class.java) { write("missing", NuxieViewModelScalarValue.StringValue("x")) }
            assertThrows(IllegalArgumentException::class.java) { write("screen/phase", NuxieViewModelScalarValue.BooleanValue(true)) }
            for (invalid in listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.MAX_VALUE)) {
                assertThrows(IllegalArgumentException::class.java) { write("safeArea/top", NuxieViewModelScalarValue.NumberValue(invalid)) }
            }
            assertEquals(count, native.writes.size)
            native.writeStatus = 4
            assertThrows(NuxieRuntimeCallException::class.java) { write("screen/phase", NuxieViewModelScalarValue.StringValue("active")) }
            assertEquals(1, native.defaultsCreated)
            assertEquals(1, native.binds)
            assertEquals(1, native.playersCreated)
            player.close()
            projected?.close()
            artboard.close()
            file.close()
            assertThrows(IllegalStateException::class.java) { write("screen/phase", NuxieViewModelScalarValue.StringValue("hidden")) }
            assertEquals(listOf(40L), native.freed)
        }
    }

    @Test
    fun `undeclared screen state is not instantiated by a write`() {
        val native = RecordingNative()
        val artboard = checkNotNull(NuxieRuntimeFile(10, native).newArtboard())
        assertFalse(artboard.setDefaultViewModelValue("screen/phase", NuxieViewModelScalarValue.StringValue("active")))
        assertEquals(0, native.defaultsCreated)
        assertTrue(native.writes.isEmpty())
        artboard.close()
    }

    private class RecordingNative : NuxieTypedRuntimeNative {
        var defaultsCreated = 0
        var binds = 0
        var playersCreated = 0
        var writeStatus = 0
        val writes = mutableListOf<NativeViewModelWrite>()
        val freed = mutableListOf<Long>()
        override fun newDefaultArtboard(fileHandle: Long) = 20L
        override fun newDefaultPlayer(artboardHandle: Long): Long { playersCreated++; return 30L }
        override fun newDefaultViewModel(artboardHandle: Long): NativeCallResult<Long> {
            defaultsCreated++
            return NativeCallResult(0, 40L)
        }
        override fun viewModelRootSchemaIndex(viewModelHandle: Long) = NativeCallResult(0, 0L)
        override fun viewModelCatalog(fileHandle: Long) = NativeCallResult(0, NativeViewModelCatalog(
            arrayOf(NativeViewModelSchema(0, "Root", 0, 4, 0, 0, -1, false),
                NativeViewModelSchema(1, "Screen", 4, 2, 0, 0, -1, false),
                NativeViewModelSchema(2, "Product", 6, 0, 0, 0, -1, false)),
            arrayOf(NativeViewModelProperty(0, 0, "screen", 9, 1, emptyArray()),
                NativeViewModelProperty(0, 1, "env/reduceMotion", 3, -1, emptyArray()),
                NativeViewModelProperty(0, 2, "safeArea/top", 2, -1, emptyArray()),
                NativeViewModelProperty(0, 3, "products", 8, 2, emptyArray()),
                NativeViewModelProperty(1, 4, "phase", 1, -1, emptyArray()),
                NativeViewModelProperty(1, 5, "appearances", 2, -1, emptyArray())), emptyArray(),
        ))
        override fun bindViewModel(artboardHandle: Long, viewModelHandle: Long): Int {
            assertEquals(40L, viewModelHandle); binds++; return 0
        }
        override fun mutateViewModel(handle: Long, write: NativeViewModelWrite): Int {
            assertEquals(40L, handle); writes += write; return writeStatus
        }
        override fun freeViewModel(handle: Long): Int { freed += handle; return 0 }
        override fun freePlayer(handle: Long) = Unit
        override fun freeArtboard(handle: Long) = Unit
        override fun freeFile(handle: Long) = Unit
    }
}
