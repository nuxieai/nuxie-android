package ai.nuxie.sdk.presentation

import ai.nuxie.sdk.runtime.TestRuntimeLane
import android.content.Intent
import android.content.res.Resources
import android.graphics.Matrix
import android.os.Looper
import android.view.animation.AnimationUtils
import android.view.animation.Transformation
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.annotation.RealObject
import org.robolectric.shadow.api.Shadow
import org.robolectric.util.ReflectionHelpers

/**
 * A full-screen Experience opens and closes with Android's own slide for a see-through screen (decision 283).
 *
 * The oracle is the framework itself, as Robolectric's android-all jars ship it for each level: its
 * activity_translucent_open_enter and activity_translucent_close_exit files, and the Animation.Activity style
 * the window manager swaps into them for a translucent Activity. The expected numbers are written from AOSP's
 * files (frameworks/base core/res/res/anim/, identical from android-9.0.0_r1 to android-16.0.0_r1), not from
 * the SDK. A unit test can't show the window manager playing the swap on this Activity; the gate's take shows that.
 */
@RunWith(RobolectricTestRunner::class)
@Config(shadows = [LinkStateRenderCapabilityShadow::class, LinkStateNativeMountShadow::class],
    instrumentedPackages = ["ai.nuxie.sdk.presentation.NuxieExperienceActivity", "ai.nuxie.sdk.presentation.AndroidRenderCapability"])
class ExperienceFullScreenSlideTest {
    private class Presented(val id: String, val activity: NuxieExperienceActivity)

    private val ids = mutableListOf<String>()
    private val scenes = mutableListOf<java.io.File>()
    private val controllers = mutableListOf<ActivityController<NuxieExperienceActivity>>()
    private val failures = mutableListOf<Throwable>()

    @After fun tearDown() {
        controllers.forEach { if (!it.get().isDestroyed) it.destroy() }
        shadowOf(Looper.getMainLooper()).idle()
        ids.forEach { PresentationRegistry.dismiss(it, CloseReason.HostDismissed) }
        scenes.forEach { it.delete() }
    }

    @Config(sdk = [28, 36])
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    @Test fun `Android's own see-through slide rises over 300 ms slowing and leaves over 250 ms speeding up`() {
        val open = frameworkSlide("activity_translucent_open_enter")
        val close = frameworkSlide("activity_translucent_close_exit")
        // Where the Experience's top edge is, as a share of the screen's height below its top, on the 30 fps grid
        // from the animation's first frame: linear_out_slow_in over 300 ms (open), accelerate_quad over 250 ms (close).
        val openTops = listOf(1.000, 0.671, 0.464, 0.315, 0.205, 0.123, 0.066, 0.028, 0.007, 0.000)
        val closeTops = listOf(0.000, 0.018, 0.071, 0.160, 0.284, 0.444, 0.640, 0.871)

        assertEquals("The open starts a full screen below", 1.0, open(0), 0.001)
        openTops.forEachIndexed { frame, top -> assertEquals("Open, frame $frame", top, open(frameMs(frame)), 0.01) }
        assertEquals("The open has settled at 300 ms", 0.0, open(300), 0.001)

        closeTops.forEachIndexed { frame, top -> assertEquals("Close, frame $frame", top, close(frameMs(frame)), 0.01) }
        val steps = closeTops.indices.map { close(frameMs(it)) }.zipWithNext { a, b -> b - a }
        steps.zipWithNext().forEach { (before, after) -> assertTrue("The close speeds up frame over frame: $steps", after > before) }
        assertEquals("The close is fully off the screen at 250 ms", 1.0, close(250), 0.001)
    }

    @Config(sdk = [28, 36])
    @Test fun `a full-screen Experience's window carries the platform style Android turns into its see-through slide`() {
        val activity = present(PresentationShell.fromTransition(null)).activity
        val style = activity.window.attributes.windowAnimations
        assertEquals("The window carries Android's own activity style", android.R.style.Animation_Activity, style)
        // The three inputs of the framework's swap (TransitionAnimation.updateToTranslucentAnimIfNeeded): the style's
        // open and close files, and a see-through window.
        assertEquals(frameworkAnim("activity_open_enter"), styleAnim(activity, style, android.R.attr.activityOpenEnterAnimation))
        assertEquals(frameworkAnim("activity_close_exit"), styleAnim(activity, style, android.R.attr.activityCloseExitAnimation))
        assertTrue("The window is see-through", translucent(activity))
    }

    @Config(sdk = [28, 36])
    @Test fun `a sheet Experience's window gets no window slide`() {
        val activity = present(PresentationShell.fromTransition(buildJsonObject { put("type", "modal") })).activity
        val style = activity.window.attributes.windowAnimations
        assertNotEquals("The sheet's dialog slides itself", android.R.style.Animation_Activity, style)
        assertEquals("The window's style names no close", 0, styleAnim(activity, style, android.R.attr.activityCloseExitAnimation))
        assertEquals("The window's style names no open", 0, styleAnim(activity, style, android.R.attr.activityOpenEnterAnimation))
    }

    /** The oracle is the iPhone's order for the same closes: the Screen's Exit, then the dismissal's slide. */
    @Config(sdk = [28, 36], shadows = [HeldExitShadow::class],
        instrumentedPackages = ["ai.nuxie.sdk.presentation.ExperienceMountedScreen"])
    @Test fun `a full-screen close plays the Screen's Exit before finish, for the person's close and an identity change`() {
        for (identityChange in listOf(false, true)) {
            val close = if (identityChange) "The identity change" else "The person's close"
            val presented = present(PresentationShell.fromTransition(null))
            val activity = presented.activity
            val screen = checkNotNull(ReflectionHelpers.getField<Any?>(activity, "currentScreen"))
            // The real reveal: the Screen's first frame, then the registry admits it, as the service does.
            (screen as ExperienceSurfaceHost.Listener).onFirstFrame()
            assertTrue(runBlocking { PresentationRegistry.reveal(presented.id) })
            assertTrue("$close: the Screen is revealed", ReflectionHelpers.callInstanceMethod<Boolean>(screen, "hasRevealed"))
            // A real mounted Screen on a test lane, whose Exit is held until the test lets it finish.
            val mounted = ExperienceMountedScreen(activity, ReflectionHelpers.getField(screen, "prepared"), screen, {},
                createLane = ::TestRuntimeLane)
            ReflectionHelpers.setField(screen, "mounted", mounted)
            val exit = Shadow.extract<HeldExitShadow>(mounted)

            if (identityChange) PresentationRegistry.dismiss(presented.id, CloseReason.IdentityChanged)
            else ReflectionHelpers.callInstanceMethod<Any?>(activity, "finishTerminal",
                ReflectionHelpers.ClassParameter.from(CloseReason::class.java, CloseReason.UserDismissed))
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals("$close plays the Screen's Exit", listOf(ExperienceScreenLifecycle.ExitReason.DISMISSED), exit.exits)
            assertFalse("$close: the Activity is not finishing while the Exit plays", activity.isFinishing)

            exit.held.complete(Unit)
            shadowOf(Looper.getMainLooper()).idle()
            assertTrue("$close: the Activity finishes once the Exit completes", activity.isFinishing)
            assertEquals("$close: the window still slides with Android's own motion",
                android.R.style.Animation_Activity, activity.window.attributes.windowAnimations)
        }
    }

    @Config(sdk = [36], shadows = [HeldExitShadow::class],
        instrumentedPackages = ["ai.nuxie.sdk.presentation.ExperienceMountedScreen"])
    @Test fun `an identity change during a page step's checkpoint plays the Exit on a running clock`() {
        val presented = present(PresentationShell.fromTransition(null))
        val activity = presented.activity
        val screen = checkNotNull(ReflectionHelpers.getField<Any?>(activity, "currentScreen"))
        (screen as ExperienceSurfaceHost.Listener).onFirstFrame()
        assertTrue(runBlocking { PresentationRegistry.reveal(presented.id) })
        val mounted = ExperienceMountedScreen(activity, ReflectionHelpers.getField(screen, "prepared"), screen, {},
            createLane = ::TestRuntimeLane)
        ReflectionHelpers.setField(screen, "mounted", mounted)
        val exit = Shadow.extract<HeldExitShadow>(mounted)
        // A page step is waiting on its Journey checkpoint, so the page's clock is held. The identity change
        // cancels that wait, and the wait's own release would reach the main thread only after the close.
        mounted.setCheckpointHold(true)

        PresentationRegistry.dismiss(presented.id, CloseReason.IdentityChanged)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals("The identity change plays the Screen's Exit", listOf(ExperienceScreenLifecycle.ExitReason.DISMISSED), exit.exits)
        assertEquals("The Exit starts with the checkpoint hold lifted", listOf(false), exit.heldAtExit)

        exit.held.complete(Unit)
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue("The Activity finishes once the Exit completes", activity.isFinishing)
    }

    @Config(sdk = [23])
    @Test fun `on the lowest level the window gets that version's ordinary screen motion`() {
        val activity = present(PresentationShell.fromTransition(null)).activity
        val style = activity.window.attributes.windowAnimations
        assertEquals(android.R.style.Animation_Activity, style)
        assertEquals(frameworkAnim("activity_close_exit"), styleAnim(activity, style, android.R.attr.activityCloseExitAnimation))
        // Android 6 to 8.1 have no see-through slide, so the window plays the version's own open and close.
        assertEquals(0, Resources.getSystem().getIdentifier("activity_translucent_open_enter", "anim", "android"))
        assertEquals(0, Resources.getSystem().getIdentifier("activity_translucent_close_exit", "anim", "android"))
    }

    /** One Experience presented through the registry and the Activity's own onCreate; only native rendering is faked. */
    private fun present(shell: PresentationShell): Presented {
        val id = java.util.UUID.randomUUID().toString().also(ids::add)
        val scene = java.io.File.createTempFile("full-screen-slide", ".riv").apply { writeBytes(byteArrayOf(1)) }
            .also(scenes::add)
        val descriptor = Json.parseToJsonElement("""{"state":{},"responses":{},"ruleGroups":[],
            "leg":{"screens":[{"id":"a","defaultViewModelName":"A"}]},
            "render":{"assets":[],"screens":[{"id":"a","artboardName":"A"}]}}""").jsonObject
        PresentationRegistry.register(id, PreparedPresentation(scene, "A", 0, shell, "a", descriptor),
            {}, { failures += it }, {}, {})
        val intent = Intent(RuntimeEnvironment.getApplication(), NuxieExperienceActivity::class.java)
            .putExtra(NuxieExperienceActivity.EXTRA_PRESENTATION_ID, id)
        val controller = Robolectric.buildActivity(NuxieExperienceActivity::class.java, intent).setup().also(controllers::add)
        shadowOf(Looper.getMainLooper()).idle()
        val activity = controller.get()
        assertNotNull("The Activity mounted the Screen", ReflectionHelpers.getField<Any?>(activity, "currentScreen"))
        assertTrue("Nothing failed: $failures", failures.isEmpty())
        return Presented(id, activity)
    }

    private fun frameworkAnim(name: String): Int =
        Resources.getSystem().getIdentifier(name, "anim", "android").also { assertNotEquals("The framework ships $name", 0, it) }

    private fun styleAnim(activity: NuxieExperienceActivity, style: Int, attr: Int): Int {
        val values = activity.theme.obtainStyledAttributes(style, intArrayOf(attr))
        return try { values.getResourceId(0, 0) } finally { values.recycle() }
    }

    private fun translucent(activity: NuxieExperienceActivity): Boolean {
        val values = activity.theme.obtainStyledAttributes(intArrayOf(android.R.attr.windowIsTranslucent))
        return try { values.getBoolean(0, false) } finally { values.recycle() }
    }

    /** The framework's own animation, loaded fresh for each sample: the top edge's share of the height below at [ms]. */
    private fun frameworkSlide(name: String): (Long) -> Double {
        val id = frameworkAnim(name)
        return { ms ->
            val animation = AnimationUtils.loadAnimation(RuntimeEnvironment.getApplication(), id)
            animation.initialize(WIDTH, HEIGHT, WIDTH, HEIGHT)
            animation.setStartTime(0)
            val transformation = Transformation()
            animation.getTransformation(ms, transformation)
            val matrix = FloatArray(9).also(transformation.matrix::getValues)
            matrix[Matrix.MTRANS_Y].toDouble() / HEIGHT
        }
    }

    private fun frameMs(frame: Int): Long = Math.round(frame * 1000.0 / 30)

    private companion object {
        // The bench phone's screen, in pixels.
        const val WIDTH = 1206
        const val HEIGHT = 2622
    }
}

/**
 * Holds a mounted Screen's Exit until the test completes [held]. The Exit's own playback has its tests beside
 * ExperienceMountedScreen; this keeps the Activity's close around it real, so the test sees when finish() comes.
 */
@Implements(className = "ai.nuxie.sdk.presentation.ExperienceMountedScreen", isInAndroidSdk = false)
internal class HeldExitShadow {
    @RealObject private lateinit var real: ExperienceMountedScreen
    val exits = mutableListOf<ExperienceScreenLifecycle.ExitReason>()
    /** Whether the page's checkpoint hold was still on when each Exit started. */
    val heldAtExit = mutableListOf<Boolean>()
    val held = CompletableDeferred<Unit>()

    @Implementation suspend fun awaitExit(reason: ExperienceScreenLifecycle.ExitReason) {
        exits += reason
        heldAtExit += ReflectionHelpers.getField<Boolean>(real, "checkpointHold")
        held.await()
    }
}
