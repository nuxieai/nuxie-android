package ai.nuxie.sdk.runtime

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class NuxieRuntimeViewModelsTest {
    @Test
    fun `catalog bind step write and free are confined to the runtime lane`() = runBlocking {
        val native = SessionNative()
        val lane = NuxieRuntimeLane()
        val runtime = NuxieRuntimeViewModels(
            lane = lane,
            fileHandle = 10,
            artboardHandle = 20,
            playerHandle = 30,
            native = native,
        )

        try {
            val catalog = runtime.viewModelCatalog()
            val bound = runtime.bindViewModelToPlayer(
                catalog = catalog,
                schemaIndex = 0,
                authoredInstanceIndex = 0,
            )
            bound.setBoolean("enabled", true)
            val outcome = runtime.step(
                inputs = listOf(NuxiePlayerInput.Trigger("submit")),
                elapsedSeconds = 0.016,
                correlationId = 55uL,
            )
            bound.close()

            assertTrue(outcome.keepGoing)
            assertEquals(listOf("catalog", "new", "bind", "write", "step", "free"), native.calls)
            assertTrue(native.threads.all { it == "com.nuxie.runtime.android.native" })
        } finally {
            lane.shutdown()
        }
    }

    @Test
    fun `native status and failed-bind cleanup status remain observable`() = runBlocking {
        val native = SessionNative()
        val lane = NuxieRuntimeLane()
        val runtime = NuxieRuntimeViewModels(lane, 10, 20, 30, native)

        try {
            native.catalogStatus = 5
            val catalogFailure = assertThrows(NuxieRuntimeCallException::class.java) {
                runBlocking { runtime.viewModelCatalog() }
            }
            assertEquals(5, catalogFailure.status)

            native.catalogStatus = 0
            val catalog = runtime.viewModelCatalog()
            native.bindStatus = 9
            native.freeStatus = 7
            val cleanupFailure = assertThrows(NuxieRuntimeCallException::class.java) {
                runBlocking { runtime.bindViewModelToPlayer(catalog, 0, 0) }
            }
            assertEquals(7, cleanupFailure.status)
            assertEquals(9, (cleanupFailure.suppressed.single() as NuxieRuntimeCallException).status)
        } finally {
            lane.shutdown()
        }
    }

    private class SessionNative : NuxieTypedRuntimeNative {
        val calls = mutableListOf<String>()
        val threads = mutableListOf<String>()
        var catalogStatus = 0
        var bindStatus = 0
        var freeStatus = 0

        override fun viewModelCatalog(fileHandle: Long): NativeCallResult<NativeViewModelCatalog> {
            record("catalog")
            return NativeCallResult(catalogStatus, NativeViewModelCatalog(
                schemas = arrayOf(NativeViewModelSchema(0, "Root", 0, 1, 0, 1, 0, false)),
                properties = arrayOf(
                    NativeViewModelProperty(0, 0, "enabled", 3, -1, emptyArray()),
                ),
                authoredInstances = arrayOf(NativeViewModelAuthoredInstance(0, 0, "Default")),
            ))
        }

        override fun newViewModel(
            fileHandle: Long,
            schemaIndex: Int,
            authoredInstanceIndex: Int?,
        ): NativeCallResult<Long> {
            record("new")
            return NativeCallResult(0, 40)
        }

        override fun bindViewModel(artboardHandle: Long, viewModelHandle: Long): Int {
            record("bind")
            return bindStatus
        }

        override fun mutateViewModel(handle: Long, write: NativeViewModelWrite): Int {
            record("write")
            return 0
        }

        override fun stepPlayer(
            playerHandle: Long,
            inputs: List<NativePlayerInput>,
            elapsedSeconds: Float,
            correlationId: Long,
        ): NativeCallResult<NativePlayerStepOutcome> {
            record("step")
            assertEquals(listOf(NativePlayerInput(2, "submit", false, 0f)), inputs)
            assertEquals(55L, correlationId)
            return NativeCallResult(0, NativePlayerStepOutcome(true, emptyArray(), emptyArray()))
        }

        override fun freeViewModel(handle: Long): Int {
            record("free")
            return freeStatus
        }

        private fun record(call: String) {
            calls += call
            threads += Thread.currentThread().name
        }
    }
}
