package ai.nuxie.sdk.presentation

import ai.nuxie.sdk.fixtures.FixtureRunner
import android.app.Activity
import android.content.Intent
import androidx.browser.customtabs.CustomTabsIntent
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf

@org.junit.runner.RunWith(org.robolectric.RobolectricTestRunner::class)
class JourneyLinkRoutingTest {
    @Test fun `shared targets use the platform opener`() {
        val fixture = Json.parseToJsonElement(FixtureRunner.fixturesRoot().resolve("events/runtime-link-targets.json").readText()).jsonObject
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        for (entry in fixture.getValue("cases").jsonArray) {
            val vector = entry.jsonObject
            val url = vector.getValue("url").jsonPrimitive.content
            val expected = vector["destination"]?.jsonPrimitive?.contentOrNull
            val route = JourneyLinkRouting.destination(url, vector["target"]?.jsonPrimitive?.contentOrNull)
            if (expected == null) { assertNull(url, route); continue }
            assertNotNull(url, route)
            assertTrue(openActivityLink(activity, requireNotNull(route), activity))
            val intent = shadowOf(activity).nextStartedActivity
            assertEquals(Intent.ACTION_VIEW, intent.action)
            assertEquals(0, intent.flags and Intent.FLAG_ACTIVITY_NEW_TASK)
            assertEquals(expected == "in_app", intent.hasExtra(CustomTabsIntent.EXTRA_SESSION))
            assertEquals(route.uri.scheme?.lowercase(), intent.data?.scheme)
        }
    }

    class CancellingActivity : Activity() {
        override fun startActivity(intent: Intent) { throw kotlinx.coroutines.CancellationException("cancelled") }
    }

    @Test fun `platform handoff propagates cancellation`() {
        val activity = Robolectric.buildActivity(CancellingActivity::class.java).setup().get()
        val route = requireNotNull(JourneyLinkRouting.destination("https://example.test", "external"))
        org.junit.Assert.assertThrows(kotlinx.coroutines.CancellationException::class.java) { openActivityLink(activity, route, activity) }
    }

    @Test fun `application fallback adds new task`() {
        val context = org.robolectric.RuntimeEnvironment.getApplication()
        val route = requireNotNull(JourneyLinkRouting.destination("https://example.test", "_self"))
        assertTrue(openActivityLink(context, route, null))
        assertNotEquals(0, shadowOf(context).nextStartedActivity.flags and Intent.FLAG_ACTIVITY_NEW_TASK)
    }
}
