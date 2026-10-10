package ai.nuxie.sdk.presentation

import android.graphics.Color
import android.os.Looper
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@LooperMode(LooperMode.Mode.PAUSED)
class ExperienceRecoveryTraversalTest {
    @Test fun firstFrameRetiresRecoveryAfterTraversal() {
        val controller = Robolectric.buildActivity(NuxieExperienceActivity::class.java)
        val activity = controller.get()
        val root = ExperienceFocusRoot(activity)
        val recovery = ExperienceRecoveryView(activity, Color.BLACK,
            AcquisitionProgress.Phase.FAILED, retry = { false }, onClose = {})
        root.addView(recovery)
        activity.setContentView(root)
        for ((name, value) in listOf("contentRoot" to root, "recoveryView" to recovery)) {
            NuxieExperienceActivity::class.java.getDeclaredField(name).apply { isAccessible = true }.set(activity, value)
        }
        NuxieExperienceActivity::class.java.getDeclaredMethod("removeRecoveryView").apply {
            isAccessible = true
        }.invoke(activity)
        assertSame("First-frame callbacks must not mutate the traversed child list", root, recovery.parent)
        controller.visible()
        shadowOf(Looper.getMainLooper()).idle()
        assertNull(recovery.parent)
    }
}
