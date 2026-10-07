package ai.nuxie.sdk.runtime

import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class PresentationHeightDeviceTest {
    @Test fun boundHeightRestoresFixedAndControlNeverHugs() = runBlocking {
        val lane = NuxieRuntimeLane()
        try { lane.call {
            val runtime = NuxieRuntime.shared
            assertTrue(runtime.isAvailable)
            val renderer = checkNotNull(runtime.newAndroidVulkanRenderer(500, 700))
            try {
                for (control in listOf(false, true)) {
                    val name = if (control) "root-height-no-binding" else "root-height-binding"
                    val bytes = InstrumentationRegistry.getInstrumentation().context.assets
                        .open("runtime/fit-content/$name.riv").use { it.readBytes() }
                    val file = checkNotNull(runtime.importFile(renderer, bytes))
                    try {
                        val artboard = checkNotNull(file.newArtboard("Sheet"))
                        try {
                            artboard.bindDefaultViewModel("Root")
                            val player = checkNotNull(artboard.newPlayer("Main"))
                            val height = NuxiePresentationHeight()
                            try {
                                for ((width, available, hug, expected) in listOf(
                                    listOf(300f, 600f, 0f, 600f), listOf(300f, 600f, 1f, if (control) 600f else 152f),
                                    listOf(300f, 700f, 0f, 700f), listOf(500f, 600f, 1f, if (control) 600f else 152f))) {
                                    height.apply(file, player, hug == 1f, width, available)
                                    player.stepTyped(elapsedSeconds = 0.0)
                                    assertEquals(width to expected, player.layoutSize())
                                }
                            } finally { height.close(); player.close() }
                        } finally { artboard.close() }
                    } finally { file.close() }
                }
            } finally { renderer.close() }
        } } finally { lane.shutdown(); assertTrue(lane.awaitQuiescence(2000)) }
    }
    @Test fun contentMutationWrappingAndFillUseUpstreamProofNumbers() = runBlocking {
        val lane = NuxieRuntimeLane()
        try { lane.call {
            val runtime = NuxieRuntime.shared
            assertTrue(runtime.isAvailable)
            val renderer = checkNotNull(runtime.newAndroidVulkanRenderer(500, 700))
            try {
                for ((name, expected) in listOf("dynamic-child" to 152f, "wrapping-children" to 392f,
                    "fill-child" to 32f, "tall-child" to 1232f)) {
                    val bytes = InstrumentationRegistry.getInstrumentation().context.assets
                        .open("runtime/fit-content/$name.riv").use { it.readBytes() }
                    val file = checkNotNull(runtime.importFile(renderer, bytes))
                    try {
                        val artboard = checkNotNull(file.newArtboard("Sheet"))
                        val otherArtboard = checkNotNull(file.newArtboard("Sheet"))
                        try {
                            artboard.bindDefaultViewModel("Root")
                            otherArtboard.bindDefaultViewModel("Root")
                            val player = checkNotNull(artboard.newPlayer("Main"))
                            val other = checkNotNull(otherArtboard.newPlayer("Main"))
                            val height = NuxiePresentationHeight()
                            val otherHeight = NuxiePresentationHeight()
                            try {
                                height.apply(file, player, true, 300f, 600f)
                                otherHeight.apply(file, other, false, 300f, 700f)
                                player.stepTyped(elapsedSeconds = 0.0)
                                other.stepTyped(elapsedSeconds = 0.0)
                                assertEquals(300f to expected, player.layoutSize())
                                assertEquals(300f to 700f, other.layoutSize())
                                if (name == "dynamic-child") {
                                    assertTrue(artboard.setDefaultViewModelValue("contentHeight", NuxieViewModelScalarValue.NumberValue(240.0)))
                                    player.stepTyped(elapsedSeconds = 0.0)
                                    assertEquals(300f to 272f, player.layoutSize())
                                }
                                if (name == "wrapping-children") {
                                    height.apply(file, player, true, 500f, 600f)
                                    player.stepTyped(elapsedSeconds = 0.0)
                                    assertEquals(500f to 272f, player.layoutSize())
                                }
                            } finally { height.close(); otherHeight.close(); player.close(); other.close() }
                        } finally { artboard.close(); otherArtboard.close() }
                    } finally { file.close() }
                }
            } finally { renderer.close() }
        } } finally { lane.shutdown(); assertTrue(lane.awaitQuiescence(2000)) }
    }

    @Test fun nativeSheetUsesMeasuredWidthAndCapsAtLarge() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val activity = instrumentation.startActivitySync(android.content.Intent(instrumentation.context,
            ai.nuxie.sdk.presentation.SurfaceCompatibilityHostActivity::class.java)
            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
        var window: ai.nuxie.sdk.presentation.ExperienceNativeLayerWindow? = null
        try {
            val laidOut = java.util.concurrent.CountDownLatch(1)
            var expectedWidth = 0
            instrumentation.runOnMainSync {
                val shell = ai.nuxie.sdk.presentation.PresentationShell.Sheet(
                    ai.nuxie.sdk.presentation.PresentationShell.Sheet.Detent.FIT_CONTENT, true)
                val size = ai.nuxie.sdk.presentation.ExperienceNativeLayerWindow.availableSize(activity, shell)
                expectedWidth = (size.width * activity.resources.displayMetrics.density).toInt()
                val content = android.widget.FrameLayout(activity)
                content.addOnLayoutChangeListener { _, l, t, r, b, _, _, _, _ -> if (r > l && b > t) laidOut.countDown() }
                window = ai.nuxie.sdk.presentation.ExperienceNativeLayerWindow(activity, content, shell) {}
                window!!.show()
                assertFalse(window!!.dialog.isShowing)
                window!!.updateContentHeight(152f)
            }
            assertTrue("Native sheet laid out", laidOut.await(10, java.util.concurrent.TimeUnit.SECONDS))
            instrumentation.runOnMainSync {
                val sheet = window!!.dialog.findViewById<android.view.View>(com.google.android.material.R.id.design_bottom_sheet)
                assertEquals(expectedWidth, sheet.width)
                assertEquals(kotlin.math.ceil(152f * activity.resources.displayMetrics.density).toInt(), sheet.height)
                window!!.updateContentHeight(10000f)
                assertEquals(ai.nuxie.sdk.presentation.ExperienceNativeLayerWindow.windowBounds(activity).height(), sheet.layoutParams.height)
                window!!.retire()
                window!!.updateContentHeight(272f)
                assertFalse(window!!.dialog.isShowing)
            }
        } finally { instrumentation.runOnMainSync { window?.retire(); activity.finish() } }
    }

    @Test fun olderFileWithoutPresentationGlobalKeepsAvailableSize() = runBlocking {
        val lane = NuxieRuntimeLane()
        try { lane.call {
            val runtime = NuxieRuntime.shared
            assertTrue(runtime.isAvailable)
            val renderer = checkNotNull(runtime.newAndroidVulkanRenderer(400, 700))
            try {
                val bytes = InstrumentationRegistry.getInstrumentation().context.assets
                    .open("runtime/global-values/globals.riv").use { it.readBytes() }
                val file = checkNotNull(runtime.importFile(renderer, bytes))
                try {
                    val artboard = checkNotNull(file.newArtboard("GlobalValuesScreen"))
                    try {
                        artboard.bindDefaultViewModel("Root")
                        val player = checkNotNull(artboard.newPlayer("Main"))
                        val height = NuxiePresentationHeight()
                        try {
                            assertFalse(height.apply(file, player, true, 400f, 700f))
                            assertFalse(height.measuresContent)
                            player.stepTyped(elapsedSeconds = 0.0)
                            assertEquals(400f to 700f, player.layoutSize())
                        } finally { height.close(); player.close() }
                    } finally { artboard.close() }
                } finally { file.close() }
            } finally { renderer.close() }
        } } finally { lane.shutdown(); assertTrue(lane.awaitQuiescence(2000)) }
    }

}
