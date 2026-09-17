package ai.nuxie.sdk.runtime

import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test

class ViewModelArchiveDeviceTest {
    @Test
    fun listOrderAndSharedIdentitySurviveRendererReplacement() = restoreGraph(removeFirst = false)

    @Test
    fun removedInstanceDoesNotAcquireItsReplacementsAlias() = restoreGraph(removeFirst = true)

    @Test
    fun publishedScalarsAndImageSelectionSurviveRendererReplacement() {
        val assets = InstrumentationRegistry.getInstrumentation().context.assets
        val directory = "runtime/view-model-retention"
        val bytes = assets.open("$directory/screen.riv").use { it.readBytes() }
        val imagePath = checkNotNull(assets.list("$directory/assets/sha256")).single()
        val imageBytes = assets.open("$directory/assets/sha256/$imagePath").use { it.readBytes() }
        val runtime = NuxieRuntime.shared
        assertTrue(runtime.isAvailable)
        val native = JniNuxieTypedRuntimeNative
        val expectedAssets = checkNotNull(runtime.inspectFileAssets(bytes))
        assertEquals(2, expectedAssets.size)
        val externalAssets = expectedAssets.associate { it.ordinal to imageBytes }
        var archive: NuxieViewModelSnapshot? = null
        var selectedImage = -1L
        repeat(2) { phase ->
            val renderer = checkNotNull(runtime.newAndroidVulkanRenderer(320, 100))
            try {
                val file = checkNotNull(runtime.importFile(renderer, bytes, expectedAssets, externalAssets))
                try {
                    val artboard = checkNotNull(file.newArtboard("Purchase"))
                    try {
                        if (phase == 0) {
                            val root = checkNotNull(native.newDefaultViewModel(artboard.requireHandle()).value)
                            try {
                                val before = checkNotNull(native.snapshotViewModel(root).value)
                                val childId = before.values.single { it.ownerInstanceId == before.rootInstanceId && it.name == "first" }.referencedInstanceId
                                val values = before.values.filter { it.ownerInstanceId == childId }.associateBy { it.name }
                                selectedImage = values.getValue("alternative").integerValue
                                assertNotEquals("Fixture must offer two different file asset identities",
                                    values.getValue("poster").integerValue, selectedImage)
                                val changes = listOf(
                                    NativeViewModelWrite(NuxieViewModelMutationKind.SET_IMAGE, "first/poster", integerValue = selectedImage),
                                    NativeViewModelWrite(NuxieViewModelMutationKind.SET_COLOR, "first/tint", integerValue = 0xff123456L),
                                    NativeViewModelWrite(NuxieViewModelMutationKind.SET_ENUM, "first/status", integerValue = 1),
                                    NativeViewModelWrite(NuxieViewModelMutationKind.SET_LIST_INDEX, "first/selected", integerValue = 7),
                                    NativeViewModelWrite(NuxieViewModelMutationKind.SET_NUMBER, "first/amount", numberValue = 3.25f),
                                    NativeViewModelWrite(NuxieViewModelMutationKind.SET_BOOLEAN, "first/enabled", boolValue = true),
                                )
                                changes.forEach { assertEquals(it.path, 0, native.mutateViewModel(root, it)) }
                                val catalog = checkNotNull(native.viewModelCatalog(file.requireHandle()).value).toViewModelCatalog()
                                archive = NuxieViewModelSnapshot.fromNative(checkNotNull(native.snapshotViewModel(root).value),
                                    schemaNames = catalog.schemas.associate { it.index.toLong() to it.name },
                                    instanceIds = emptyMap(), defaultInstanceId = "purchase.root")
                            } finally { assertEquals(0, native.freeViewModel(root)) }
                        } else {
                            val restored = runtime.restoreViewModel(file, artboard, checkNotNull(archive))
                            try {
                                val root = NuxieRuntimeViewModelState::class.java.getDeclaredField("root").apply { isAccessible = true }.get(restored) as Long
                                val raw = checkNotNull(native.snapshotViewModel(root).value)
                                val childId = raw.values.single { it.ownerInstanceId == raw.rootInstanceId && it.name == "first" }.referencedInstanceId
                                val values = raw.values.filter { it.ownerInstanceId == childId }.associateBy { it.name }
                                assertEquals(selectedImage, values.getValue("poster").integerValue)
                                assertEquals(0xff123456L, values.getValue("tint").integerValue)
                                assertEquals(1L, values.getValue("status").integerValue)
                                assertEquals(7L, values.getValue("selected").integerValue)
                                assertEquals(3.25f, values.getValue("amount").numberValue, 0f)
                                assertTrue(values.getValue("enabled").boolValue)
                                val player = file.newExperiencePlayer(artboard, "Purchase")
                                try {
                                    player.stepTyped(elapsedSeconds = 0.016)
                                    renderer.renderToCpuFrame(player, 0xff000000.toInt(), true)
                                } finally { player.close() }
                            } finally { restored.close() }
                        }
                    } finally { artboard.close() }
                } finally { file.close() }
            } finally { renderer.close() }
        }
    }

    private fun restoreGraph(removeFirst: Boolean) {
        val bytes = InstrumentationRegistry.getInstrumentation().context.assets
            .open("runtime/purchase-navigation/screen.riv").use { it.readBytes() }
        val runtime = NuxieRuntime.shared
        assertTrue("Pinned runtime must load", runtime.isAvailable)
        val native = JniNuxieTypedRuntimeNative
        val originalRenderer = checkNotNull(runtime.newAndroidVulkanRenderer(320, 150))
        val archived: NuxieViewModelSnapshot
        try {
            val file = checkNotNull(runtime.importFile(originalRenderer, bytes))
            try {
                val artboard = checkNotNull(file.newArtboard("Purchase"))
                try {
                    val catalog = checkNotNull(native.viewModelCatalog(file.requireHandle()).value).toViewModelCatalog()
                    val plan = catalog.schemas.single { it.name == "Plan" }.index
                    val root = checkNotNull(native.newDefaultViewModel(artboard.requireHandle()).value)
                    val first = checkNotNull(native.newViewModel(file.requireHandle(), plan, null).value)
                    val second = checkNotNull(native.newViewModel(file.requireHandle(), plan, null).value)
                    try {
                        fun write(handle: Long, mutation: NativeViewModelWrite) = assertEquals(0, native.mutateViewModel(handle, mutation))
                        write(first, NativeViewModelWrite(NuxieViewModelMutationKind.SET_STRING, "placementId", bytesValue = "changed:first".encodeToByteArray()))
                        write(second, NativeViewModelWrite(NuxieViewModelMutationKind.SET_STRING, "placementId", bytesValue = "changed:second".encodeToByteArray()))
                        write(root, NativeViewModelWrite(NuxieViewModelMutationKind.SET_VIEW_MODEL, "first", relatedViewModel = if (removeFirst) second else first))
                        write(root, NativeViewModelWrite(NuxieViewModelMutationKind.SET_VIEW_MODEL, "second", relatedViewModel = second))
                        write(root, NativeViewModelWrite(NuxieViewModelMutationKind.LIST_CLEAR, "plans"))
                        val order = if (removeFirst) listOf(second) else listOf(second, first)
                        order.forEachIndexed { index, handle ->
                            write(root, NativeViewModelWrite(NuxieViewModelMutationKind.LIST_INSERT, "plans", relatedViewModel = handle, index = index.toLong()))
                        }
                        val raw = checkNotNull(native.snapshotViewModel(root).value)
                        val firstId = checkNotNull(native.snapshotViewModel(first).value).rootInstanceId
                        val secondId = checkNotNull(native.snapshotViewModel(second).value).rootInstanceId
                        assertEquals(order.size, raw.values.single { it.ownerInstanceId == raw.rootInstanceId && it.name == "plans" }.listItemIds.size)
                        archived = NuxieViewModelSnapshot.fromNative(raw,
                            schemaNames = catalog.schemas.associate { it.index.toLong() to it.name },
                            instanceIds = mapOf("plan.first" to firstId, "plan.second" to secondId), defaultInstanceId = "purchase.root")
                    } finally {
                        assertEquals(0, native.freeViewModel(root))
                        assertEquals(0, native.freeViewModel(first))
                        assertEquals(0, native.freeViewModel(second))
                    }
                } finally { artboard.close() }
            } finally { file.close() }
        } finally { originalRenderer.close() }
        // The old renderer, file, graph and every native handle have been released.
        val renderer = checkNotNull(runtime.newAndroidVulkanRenderer(320, 150))
        try {
            val file = checkNotNull(runtime.importFile(renderer, bytes))
            try {
                val artboard = checkNotNull(file.newArtboard("Purchase"))
                try {
                    val restored = runtime.restoreViewModel(file, artboard, archived)
                    try {
                        val snapshot = restored.snapshot()
                        assertEquals("changed:second", snapshot.resolveScopedString("placementId", "Plan", "plan.second"))
                        assertEquals(if (removeFirst) null else "changed:first", snapshot.resolveScopedString("placementId", "Plan", "plan.first"))
                        assertEquals(if (removeFirst) "changed:second" else "changed:first", snapshot.resolveString("first.placementId"))
                        val rootHandle = NuxieRuntimeViewModelState::class.java.getDeclaredField("root").apply { isAccessible = true }.get(restored) as Long
                        val raw = checkNotNull(native.snapshotViewModel(rootHandle).value)
                        val values = raw.values.filter { it.ownerInstanceId == raw.rootInstanceId }
                        val firstId = values.single { it.name == "first" }.referencedInstanceId
                        val secondId = values.single { it.name == "second" }.referencedInstanceId
                        assertArrayEquals(if (removeFirst) longArrayOf(secondId) else longArrayOf(secondId, firstId),
                            values.single { it.name == "plans" }.listItemIds)
                        val player = file.newExperiencePlayer(artboard, "Purchase")
                        try {
                            repeat(3) { player.stepTyped(elapsedSeconds = 0.016) }
                            renderer.renderToCpuFrame(player, 0xff000000.toInt(), true)
                        } finally { player.close() }
                    } finally { restored.close() }
                } finally { artboard.close() }
            } finally { file.close() }
        } finally { renderer.close() }
    }
}
