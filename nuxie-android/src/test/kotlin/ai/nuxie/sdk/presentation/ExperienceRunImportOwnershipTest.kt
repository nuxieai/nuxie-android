package ai.nuxie.sdk.presentation

import ai.nuxie.sdk.runtime.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class ExperienceRunImportOwnershipTest {
    @Test fun `Experience model keeps one import until the run retires`() = runBlocking {
        var imports = 0
        var closes = 0
        val native = object : NuxieTypedRuntimeNative {
            override fun newAndroidVulkanRenderer(pixelWidth: Int, pixelHeight: Int) = 1L
            override fun newFile(rendererHandle: Long, bytes: ByteArray, expectedAssets: List<ExpectedFileAsset>,
                externalAssets: Map<Int, ByteArray>, imageDecoder: NuxImageDecoder, videoEnabled: Boolean): Long { imports++; return 2L }
            override fun viewModelCatalog(fileHandle: Long) = NativeCallResult(0,
                NativeViewModelCatalog(arrayOf(NativeViewModelSchema(0, "Experience", 0, 0, 0, 1, 0, false)),
                    emptyArray(), arrayOf(NativeViewModelAuthoredInstance(0, 0, "initial"))))
            override fun newViewModel(fileHandle: Long, schemaIndex: Int, authoredInstanceIndex: Int?) = NativeCallResult(0, 3L)
            override fun snapshotViewModel(viewModelHandle: Long) = NativeCallResult(0,
                NativeViewModelSnapshot(3L, arrayOf(NativeViewModelSnapshotInstance(3L, 0)), emptyArray()))
            override fun freeViewModel(handle: Long) = 0
            override fun freeFile(handle: Long) { closes++ }
            override fun freeRenderer(handle: Long) = Unit
        }
        val run = ExperienceRunValues()
        try {
            run.lane.call {
                val first = run.prepare(byteArrayOf(1), null, emptyMap(), NuxieRuntime(native))
                val second = run.prepare(byteArrayOf(1), null, emptyMap(), NuxieRuntime(native))
                assertSame(first.file, second.file)
                assertSame(first.values, second.values)
                assertEquals(1, imports)
                assertEquals(0, closes)
            }
            assertTrue(run.isPrepared())
        } finally { run.retire() }
        assertEquals(1, closes)
    }

    @Test fun `files without an Experience model belong to individual screens`() = runBlocking {
        var imports = 0
        var renderers = 0
        val closedFiles = mutableListOf<Long>()
        val closedRenderers = mutableListOf<Long>()
        val native = object : NuxieTypedRuntimeNative {
            override fun newAndroidVulkanRenderer(pixelWidth: Int, pixelHeight: Int) = (++renderers).toLong()
            override fun newFile(rendererHandle: Long, bytes: ByteArray, expectedAssets: List<ExpectedFileAsset>,
                externalAssets: Map<Int, ByteArray>, imageDecoder: NuxImageDecoder, videoEnabled: Boolean) = (++imports).toLong()
            override fun viewModelCatalog(fileHandle: Long) = NativeCallResult(0,
                NativeViewModelCatalog(emptyArray(), emptyArray(), emptyArray()))
            override fun freeFile(handle: Long) { closedFiles += handle }
            override fun freeRenderer(handle: Long) { closedRenderers += handle }
        }
        val run = ExperienceRunValues()
        val screens = mutableListOf<ExperienceRunValues.Native>()
        try {
            run.lane.call {
                run.prepareForRun(byteArrayOf(1), null, emptyMap(), NuxieRuntime(native))
                assertEquals("Screenless preparation releases the unshared import", listOf(1L), closedFiles)
                assertEquals(listOf(1L), closedRenderers)
                repeat(2) { screens += run.prepare(byteArrayOf(1), null, emptyMap(), NuxieRuntime(native)) }
                assertNotSame("Each screen must import its own file without an Experience model", screens[0].file, screens[1].file)
                assertEquals(3, imports)
                assertEquals(3, renderers)
            }
            assertFalse("No run-owned native state exists", run.isPrepared())
        } finally {
            run.lane.call { screens.forEach { it.file.close(); it.renderer.close() } }
            run.retire()
        }
        assertEquals(listOf(1L, 2L, 3L), closedFiles)
        assertEquals(listOf(1L, 2L, 3L), closedRenderers)
    }
}
