package ai.nuxie.sdk.core

import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class AppLifecycleTrackerTest {
    private data class Emitted(val name: String, val properties: Map<String, Any?>)

    private fun tracker(
        version: String,
        emitted: MutableList<Emitted>,
        preferencesName: String,
    ): AppLifecycleTracker = AppLifecycleTracker(
        preferences = RuntimeEnvironment.getApplication()
            .getSharedPreferences(preferencesName, Context.MODE_PRIVATE),
        appVersionProvider = { version },
        nowMillis = { 1_784_462_400_000L },
        emit = { name, properties -> emitted.add(Emitted(name, properties)) },
    )

    @Test
    fun firstLaunchEmitsInstalledThenOpened() {
        val emitted = mutableListOf<Emitted>()
        tracker("1.0 (1)", emitted, "t1").trackAppLaunchEvents()

        assertEquals(listOf("\$app_installed", "\$app_opened"), emitted.map { it.name })
        val install = emitted.first()
        assertEquals("app_lifecycle", install.properties["source"])
        assertEquals("1.0 (1)", install.properties["app_version"])
        assertEquals(1_784_462_400.0, install.properties["install_date"])
        // iOS parity: first-launch $app_opened also carries install_date.
        assertTrue(emitted.last().properties.containsKey("open_date"))
        assertEquals(1_784_462_400.0, emitted.last().properties["install_date"])
    }

    @Test
    fun secondLaunchSameVersionEmitsOnlyOpened() {
        val emitted = mutableListOf<Emitted>()
        tracker("1.0 (1)", emitted, "t2").trackAppLaunchEvents()
        emitted.clear()

        tracker("1.0 (1)", emitted, "t2").trackAppLaunchEvents()
        assertEquals(listOf("\$app_opened"), emitted.map { it.name })
    }

    @Test
    fun versionChangeEmitsUpdatedWithPreviousVersion() {
        val emitted = mutableListOf<Emitted>()
        tracker("1.0 (1)", emitted, "t3").trackAppLaunchEvents()
        emitted.clear()

        tracker("2.0 (2)", emitted, "t3").trackAppLaunchEvents()
        assertEquals(listOf("\$app_updated", "\$app_opened"), emitted.map { it.name })
        assertEquals("1.0 (1)", emitted.first().properties["previous_version"])
        assertEquals("2.0 (2)", emitted.first().properties["app_version"])
        // iOS parity: post-update $app_opened carries the update context too.
        assertEquals("1.0 (1)", emitted.last().properties["previous_version"])
    }

    @Test
    fun backgroundAndForegroundEmitTheirMoments() {
        val emitted = mutableListOf<Emitted>()
        val tracker = tracker("1.0 (1)", emitted, "t4")

        tracker.trackAppBackgrounded()
        tracker.trackAppForegrounded()

        assertEquals(listOf("\$app_backgrounded", "\$app_opened"), emitted.map { it.name })
        assertTrue(emitted.first().properties.containsKey("background_date"))
        assertTrue(emitted.last().properties.containsKey("foreground_date"))
    }
}
