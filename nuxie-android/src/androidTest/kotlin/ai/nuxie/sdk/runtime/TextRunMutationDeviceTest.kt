package ai.nuxie.sdk.runtime

import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TextRunMutationDeviceTest {
    @Test
    fun snapshotPreservesNumericValuesAcrossJNI() {
        val bytes = InstrumentationRegistry.getInstrumentation().context.assets
            .open("data_binding_test.riv").use { it.readBytes() }
        val runtime = NuxieRuntime.shared
        assertTrue(runtime.isAvailable)
        val renderer = checkNotNull(runtime.newAndroidVulkanRenderer(16, 16))
        try {
            val file = checkNotNull(runtime.importFile(renderer, bytes))
            try {
                val artboard = checkNotNull(file.newArtboard())
                try {
                    val native = JniNuxieTypedRuntimeNative
                    val root = native.newDefaultViewModel(artboard.requireHandle())
                    assertEquals(0, root.status)
                    val handle = checkNotNull(root.value)
                    try {
                        val before = checkNotNull(native.snapshotViewModel(handle).value)
                        val number = before.values.first {
                            it.ownerInstanceId == before.rootInstanceId &&
                                it.kind == NuxieViewModelPropertyKind.NUMBER.nativeValue
                        }
                        assertEquals(0, native.mutateViewModel(handle, NativeViewModelWrite(
                            kind = NuxieViewModelMutationKind.SET_NUMBER,
                            path = number.name,
                            numberValue = 37.25f,
                        )))
                        val after = native.snapshotViewModel(handle)
                        assertEquals(0, after.status)
                        assertEquals(37.25f, NuxieViewModelSnapshot.fromNative(checkNotNull(after.value))
                            .resolveGeometryNumber(number.name))
                    } finally { assertEquals(0, native.freeViewModel(handle)) }
                } finally { artboard.close() }
            } finally { file.close() }
        } finally { renderer.close() }
    }

}
