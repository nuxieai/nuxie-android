package ai.nuxie.sdk.runtime

import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PurchaseScopeDeviceTest {
    @Test
    fun publishedRepeatedComponentsKeepDistinctPurchaseSelections() {
        val bytes = InstrumentationRegistry.getInstrumentation().context.assets
            .open("runtime/purchase-scopes/screen.riv").use { it.readBytes() }
        val runtime = NuxieRuntime.shared
        assertTrue("Pinned public runtime must load", runtime.isAvailable)
        val renderer = checkNotNull(runtime.newAndroidVulkanRenderer(320, 100))
        try {
            val file = checkNotNull(runtime.importFile(renderer, bytes))
            try {
                val artboard = checkNotNull(file.newArtboard("Purchase"))
                try {
                    artboard.bindDefaultViewModel("PurchaseRoot", "purchase.root", listOf(
                        NuxieViewModelInstanceBinding("PurchaseRoot", "purchase.root", "first", "plan.first", "Plan"),
                        NuxieViewModelInstanceBinding("PurchaseRoot", "purchase.root", "second", "plan.second", "Plan"),
                    ))
                    val player = file.newExperiencePlayer(artboard, "Purchase")
                    try {
                        player.stepTyped(elapsedSeconds = 0.0)
                        for (x in listOf(80f, 240f)) {
                            player.stepTyped(elapsedSeconds = 0.0, pointers = listOf(
                                NuxiePlayerPointerEvent(NuxiePlayerPointerKind.DOWN, x, 50f, 1, 0f)))
                            val tapped = player.stepTyped(elapsedSeconds = 0.0, pointers = listOf(
                                NuxiePlayerPointerEvent(NuxiePlayerPointerKind.UP, x, 50f, 1, 0.1f)))
                            val details = tapped.events.map { event -> event.name to event.properties.map { property ->
                                property.name to when (val value = property.value) {
                                    is NuxieRuntimeEventPropertyValue.Bytes -> value.value.decodeToString()
                                    else -> value.toString()
                                }
                            } }
                            assertTrue("A purchase button tap at x=$x must emit an interaction: $details", tapped.events.isNotEmpty())
                        }
                        val before = checkNotNull(artboard.defaultViewModelSnapshot())
                        assertEquals("plan:monthly", before.resolveString("first.placementId"))
                        assertEquals("plan:annual", before.resolveString("second.placementId"))
                        assertEquals("plan:monthly", before.resolveScopedString("placementId", "Plan", "plan.first"))
                        assertEquals("plan:annual", before.resolveScopedString("placementId", "Plan", "plan.second"))
                        assertEquals("root:yearly", before.resolveScopedString("placementId", "PurchaseRoot", "purchase.root"))
                        assertNull(before.resolveScopedString("placementId", "MissingModel", null))
                        assertNull(before.resolveScopedString("placementId", "Plan", null))
                        assertTrue(artboard.setDefaultViewModelValue("second/placementId",
                            NuxieViewModelScalarValue.StringValue("plan:lifetime")))
                        player.stepTyped(elapsedSeconds = 0.0)
                        val after = checkNotNull(artboard.defaultViewModelSnapshot())
                        assertEquals("plan:monthly", after.resolveString("first.placementId"))
                        assertEquals("plan:lifetime", after.resolveString("second.placementId"))
                        assertEquals("plan:lifetime", after.resolveScopedString("placementId", "Plan", "plan.second"))
                        assertEquals("plan:annual", before.resolveString("second.placementId"))
                        assertEquals("plan:monthly", before.resolveScopedString("placementId", "Plan", "plan.first"))
                        assertEquals("plan:annual", before.resolveScopedString("placementId", "Plan", "plan.second"))
                        renderer.renderToCpuFrame(player, 0xff000000.toInt(), true)
                    } finally { player.close() }
                } finally { artboard.close() }
            } finally { file.close() }
        } finally { renderer.close() }
    }
}
