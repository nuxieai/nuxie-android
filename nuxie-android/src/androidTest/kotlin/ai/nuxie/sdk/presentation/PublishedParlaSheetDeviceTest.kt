package ai.nuxie.sdk.presentation

import ai.nuxie.sdk.NuxieEnvironment
import ai.nuxie.sdk.core.supportedRuntimeForEmbeddedRuntime
import ai.nuxie.sdk.experiences.AuthenticatedJourneyRelease
import ai.nuxie.sdk.experiences.JourneyReleaseIdentity
import ai.nuxie.sdk.experiences.JourneyReleaseReplayPolicy
import ai.nuxie.sdk.experiences.JourneyReleaseVerifier
import ai.nuxie.sdk.experiences.JourneyTrustRoots
import ai.nuxie.sdk.runtime.nuxieRuntimeSourceRevision
import android.app.Instrumentation
import android.content.Intent
import android.graphics.Rect
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import androidx.test.platform.app.InstrumentationRegistry
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

/** Signed Journey facts and the native window's real drag policy. */
class PublishedParlaSheetDeviceTest {
    @Test fun publishedParlaVariantsKeepSourceSizesAndLifecycleDefaults() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        for (large in listOf(false, true)) {
            val release = authenticate(instrumentation, large)
            val descriptor = release.descriptor
            val screens = descriptor.getValue("render").jsonObject.getValue("screens").jsonArray
            assertEquals(3, screens.size)
            for (screen in screens) {
                // All source frame.json files select iphone-17-pro, whose
                // compiler preset is 402 x 874 points, independent of the host.
                assertEquals(402, screen.jsonObject.getValue("width").jsonPrimitive.int)
                assertEquals(874, screen.jsonObject.getValue("height").jsonPrimitive.int)
            }
            val offer = transition(release, "offer")
            assertEquals("modal", offer.getValue("type").jsonPrimitive.content)
            assertEquals("sheet", offer.getValue("present").jsonPrimitive.content)
            assertEquals(if (large) "large" else "medium", offer.getValue("height").jsonPrimitive.content)
            assertEquals(if (large) "none" else "any", offer.getValue("closedby").jsonPrimitive.content)
            val saved = transition(release, "save")
            assertEquals("fit-content", saved.getValue("height").jsonPrimitive.content)
            assertEquals("any", saved.getValue("closedby").jsonPrimitive.content)
            val initial = release.leg.getValue("steps").jsonArray.single {
                it.jsonObject.getValue("id") == release.leg.getValue("entryStepId")
            }.jsonObject.getValue("action").jsonObject
            assertEquals("navigate", initial.getValue("type").jsonPrimitive.content)
            assertFalse(initial.containsKey("transition"))
            val values = descriptor.getValue("viewModelValues").jsonArray
            for (root in release.leg.getValue("screens").jsonArray) {
                val instance = root.jsonObject.getValue("defaultInstanceId")
                val defaults = values.filter { it.jsonObject["instanceId"] == instance }.map { it.jsonObject }
                fun value(path: String) = defaults.single { it.getValue("path").jsonPrimitive.content == path }.getValue("value").jsonPrimitive
                // Independent initial values from the shared lifecycle contract.
                assertEquals("hidden", value("screen/phase").content)
                assertEquals(0, value("screen/appearances").int)
                assertEquals("", value("screen/transition").content)
                assertFalse(value("env/reduceMotion").boolean)
            }
        }
    }

    @Test fun publishedLargeSheetBlocksTheSwipeThatDismissesTheMediumControl() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        for (large in listOf(false, true)) {
            val release = authenticate(instrumentation, large)
            val shell = PresentationShell.fromTransition(transition(release, "offer"))
            val activity = instrumentation.startActivitySync(Intent(instrumentation.context,
                SurfaceCompatibilityHostActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            val laidOut = CountDownLatch(1)
            val dismissed = CountDownLatch(1)
            val closes = AtomicInteger()
            var window: ExperienceNativeLayerWindow? = null
            val bounds = Rect()
            try {
                instrumentation.runOnMainSync {
                    val content = FrameLayout(activity)
                    content.addOnLayoutChangeListener { _, l, t, r, b, _, _, _, _ ->
                        if (r > l && b > t) laidOut.countDown()
                    }
                    window = ExperienceNativeLayerWindow(activity, content, shell) {
                        closes.incrementAndGet()
                        dismissed.countDown()
                    }
                    window!!.setInputEnabled(true)
                    window!!.show()
                }
                assertTrue("Native sheet laid out", laidOut.await(10, TimeUnit.SECONDS))
                instrumentation.waitForIdleSync()
                instrumentation.runOnMainSync {
                    val sheet = window!!.dialog.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)
                    val parent = sheet.parent as View
                    val visibleArea = Rect()
                    assertTrue(parent.getGlobalVisibleRect(visibleArea))
                    assertTrue(sheet.getGlobalVisibleRect(bounds))
                    assertEquals("The entire authored surface is reachable above system bars", sheet.height, bounds.height())
                    assertTrue(visibleArea.contains(bounds))
                    assertEquals(if (large) parent.height else parent.height / 2, sheet.height)
                }
                swipeDown(instrumentation, bounds, activity.resources.displayMetrics.density)
                if (large) {
                    SystemClock.sleep(750)
                    instrumentation.runOnMainSync {
                        assertTrue("closedby none retains the native large sheet", window!!.dialog.isShowing)
                        val sheet = window!!.dialog.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)
                        val visible = Rect()
                        assertTrue(sheet.getGlobalVisibleRect(visible))
                        assertEquals(sheet.height, visible.height())
                    }
                    assertEquals(0, closes.get())
                } else {
                    assertTrue("The same real drag dismisses the closedby any control", dismissed.await(10, TimeUnit.SECONDS))
                    assertEquals(1, closes.get())
                }
            } finally {
                instrumentation.runOnMainSync { window?.retire(); activity.finish() }
            }
        }
    }

    @Test fun fitContentFrameUpdatesDoNotCancelTheNativeDrag() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val activity = instrumentation.startActivitySync(Intent(instrumentation.context,
            SurfaceCompatibilityHostActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        val dismissed = CountDownLatch(1)
        val laidOut = CountDownLatch(1)
        val bounds = Rect()
        var window: ExperienceNativeLayerWindow? = null
        try {
            instrumentation.runOnMainSync {
                val content = FrameLayout(activity)
                content.addOnLayoutChangeListener { _, _, _, r, b, _, _, _, _ -> if (r > 0 && b > 0) laidOut.countDown() }
                window = ExperienceNativeLayerWindow(activity, content,
                    PresentationShell.fromTransition(transition(authenticate(instrumentation, false), "save"))) { dismissed.countDown() }
                // Source arithmetic from published-fit-content: 16 + 120 + 16.
                window!!.updateContentHeight(152f)
                window!!.setInputEnabled(true)
                window!!.show()
            }
            assertTrue(laidOut.await(10, TimeUnit.SECONDS))
            instrumentation.runOnMainSync {
                assertTrue(window!!.dialog.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet).getGlobalVisibleRect(bounds))
            }
            swipeDown(instrumentation, bounds, activity.resources.displayMetrics.density) {
                instrumentation.runOnMainSync { window!!.updateContentHeight(152f) }
            }
            assertTrue("Frame measurements must not settle a dragging sheet back to expanded", dismissed.await(10, TimeUnit.SECONDS))
        } finally { instrumentation.runOnMainSync { window?.retire(); activity.finish() } }
    }

    private fun authenticate(instrumentation: Instrumentation, large: Boolean): AuthenticatedJourneyRelease {
        val prefix = "journeys/parla-sheets/" + if (large) "variants/large/" else ""
        fun read(path: String) = instrumentation.context.assets.open(prefix + path).use { it.readBytes() }
        val entry = Json.parseToJsonElement(read("release-entry.json").decodeToString()).jsonObject
        val locator = entry.getValue("locator").jsonObject
        val release = JourneyReleaseVerifier.authenticate(
            entry.getValue("envelope").toString().encodeToByteArray(),
            JourneyTrustRoots.keys(NuxieEnvironment.DEVELOPMENT),
            checkNotNull(JourneyReleaseIdentity.fromJson(locator, setOf("legId"))),
            locator.getValue("legId").jsonPrimitive.content,
            checkNotNull(supportedRuntimeForEmbeddedRuntime(nuxieRuntimeSourceRevision())),
            JourneyReleaseReplayPolicy.Active(0),
        )
        assertArrayEquals(read("descriptor.json"), release.descriptorBytes)
        val render = release.descriptor.getValue("render").jsonObject
        for (artifact in listOf(render.getValue("nux")) + render.getValue("assets").jsonArray) {
            val declaration = artifact.jsonObject
            val bytes = read(declaration.getValue("key").jsonPrimitive.content)
            assertEquals(declaration.getValue("sizeBytes").jsonPrimitive.long, bytes.size.toLong())
            assertEquals(declaration.getValue("sha256").jsonPrimitive.content,
                MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) })
        }
        return release
    }

    private fun transition(release: AuthenticatedJourneyRelease, event: String): JsonObject {
        val route = release.leg.getValue("routes").jsonArray.single {
            it.jsonObject.getValue("eventName").jsonPrimitive.content == event
        }.jsonObject
        return release.leg.getValue("steps").jsonArray.single {
            it.jsonObject.getValue("id") == route.getValue("entryStepId")
        }.jsonObject.getValue("action").jsonObject.getValue("transition").jsonObject
    }

    private fun swipeDown(instrumentation: Instrumentation, bounds: Rect, density: Float, onFrame: () -> Unit = {}) {
        val x = bounds.exactCenterX()
        val startY = bounds.top + minOf(bounds.height() * 0.15f, 64f * density)
        val endY = bounds.bottom - 24f * density
        assertTrue(endY > startY)
        val down = SystemClock.uptimeMillis()
        fun send(action: Int, y: Float) {
            val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, x, y, 0)
            event.source = android.view.InputDevice.SOURCE_TOUCHSCREEN
            try { instrumentation.sendPointerSync(event) } finally { event.recycle() }
        }
        send(MotionEvent.ACTION_DOWN, startY)
        for (step in 1..20) {
            SystemClock.sleep(25)
            onFrame()
            send(MotionEvent.ACTION_MOVE, startY + (endY - startY) * step / 20f)
        }
        send(MotionEvent.ACTION_UP, endY)
        instrumentation.waitForIdleSync()
    }
}
