package ai.nuxie.sdk.presentation

import ai.nuxie.sdk.fixtures.FixtureRunner
import android.os.Looper
import android.view.View
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
class ExperienceScreenViewTransitionTest {
    private val contract = Json.parseToJsonElement(FixtureRunner.fixturesRoot()
        .resolve("journeys/planes/screen-transition-plan-android.json").readText()).jsonObject
        .getValue("standardViewAnimation").jsonObject

    private fun screen(): View = View(RuntimeEnvironment.getApplication()).apply {
        val size = contract.getValue("size").jsonPrimitive.int
        layout(0, 0, size, size)
    }

    @Test
    fun `standard animation transforms match the shared contract`() {
        for (value in contract.getValue("cases").jsonArray) {
            val case = value.jsonObject
            val source = screen()
            val target = screen()
            val transition = ExperienceScreenViewTransition(source, target,
                ExperienceScreenTransitionPlan.Kind.valueOf(case.getValue("kind").jsonPrimitive.content))
            transition.applyProgress(contract.getValue("progress").jsonPrimitive.float)
            assertEquals(case.getValue("sourceAlpha").jsonPrimitive.float, source.alpha, 0.0001f)
            assertEquals(case.getValue("targetAlpha").jsonPrimitive.float, target.alpha, 0.0001f)
            assertEquals(case.getValue("sourceX").jsonPrimitive.float, source.translationX, 0.0001f)
            assertEquals(case.getValue("targetX").jsonPrimitive.float, target.translationX, 0.0001f)
            assertEquals(case.getValue("targetY").jsonPrimitive.float, target.translationY, 0.0001f)
        }
    }

    @Test
    fun `push follows Android layout direction`() {
        val application = RuntimeEnvironment.getApplication()
        application.applicationInfo.flags = application.applicationInfo.flags or android.content.pm.ApplicationInfo.FLAG_SUPPORTS_RTL
        val source = screen()
        val target = screen().apply { layoutDirection = View.LAYOUT_DIRECTION_RTL }
        ExperienceScreenViewTransition(source, target, ExperienceScreenTransitionPlan.Kind.PUSH).applyProgress(0.25f)
        assertEquals(50f, source.translationX, 0f)
        assertEquals(-150f, target.translationX, 0f)
    }

    @Test fun `page pop reverses the native push slide and reveals the retained lower view`() {
        val source = screen()
        val target = screen()
        val pop = ExperienceScreenViewTransition(source, target, ExperienceScreenTransitionPlan.Kind.POP)
        pop.applyProgress(0f)
        assertEquals(0f, source.translationX, 0f)
        assertEquals(-200f, target.translationX, 0f)
        pop.applyProgress(1f)
        assertEquals(200f, source.translationX, 0f)
        assertEquals(0f, target.translationX, 0f)
        assertEquals(1f, target.alpha, 0f)
    }

    @Test fun `page pop keeps native sheet chrome above the page roots`() {
        val parent = android.widget.FrameLayout(RuntimeEnvironment.getApplication())
        val lower = screen()
        val upper = screen()
        val chrome = screen()
        parent.addView(lower); parent.addView(upper); parent.addView(chrome)
        val pop = ExperienceScreenViewTransition(upper, lower, ExperienceScreenTransitionPlan.Kind.POP)
        val job = CoroutineScope(Dispatchers.Unconfined).launch { pop.play(false) }
        try { assertSame(chrome, parent.getChildAt(parent.childCount - 1)) }
        finally { pop.cancel() }
        assertTrue(job.isCancelled)
    }

    @Test fun `native page pop pauses while hidden and completes after resuming`() {
        val pop = ExperienceScreenViewTransition(screen(), screen(), ExperienceScreenTransitionPlan.Kind.POP)
        val job = CoroutineScope(Dispatchers.Unconfined).launch { pop.play(false) }
        shadowOf(Looper.getMainLooper()).idleFor(500, TimeUnit.MILLISECONDS)
        assertFalse(job.isCompleted)
        pop.setVisible(true)
        shadowOf(Looper.getMainLooper()).idleFor(500, TimeUnit.MILLISECONDS)
        assertTrue(job.isCompleted)
        assertFalse(job.isCancelled)
    }

    @Test
    fun `native animator completes and cancellation releases its waiter`() {
        val source = screen()
        val target = screen()
        val transition = ExperienceScreenViewTransition(source, target, ExperienceScreenTransitionPlan.Kind.FADE)
        val job = CoroutineScope(Dispatchers.Unconfined).launch { transition.play() }
        shadowOf(Looper.getMainLooper()).idleFor(contract.getValue("durationMs").jsonPrimitive.long + 100, TimeUnit.MILLISECONDS)
        assertTrue(job.isCompleted)
        assertFalse(job.isCancelled)
        assertEquals(0f, source.alpha, 0.0001f)
        assertEquals(1f, target.alpha, 0.0001f)
        val pending = ExperienceScreenViewTransition(screen(), screen(), ExperienceScreenTransitionPlan.Kind.MODAL)
        val cancelled = CoroutineScope(Dispatchers.Unconfined).launch { pending.play(false) }
        pending.cancel()
        assertTrue(cancelled.isCancelled)
    }
}
