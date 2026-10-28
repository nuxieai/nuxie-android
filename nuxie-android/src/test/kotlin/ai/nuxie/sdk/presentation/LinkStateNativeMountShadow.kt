package ai.nuxie.sdk.presentation

import android.app.Activity
import android.view.View
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.annotation.RealObject
import org.robolectric.util.ReflectionHelpers

/** Native rendering is outside Robolectric; Activity, Screen, and dismissal code stay real. */
@Implements(className = "ai.nuxie.sdk.presentation.NuxieExperienceActivity\$Screen", isInAndroidSdk = false)
internal class LinkStateNativeMountShadow {
    @RealObject lateinit var screen: Any
    @Implementation fun mount(): View {
        val activity = ReflectionHelpers.getField<Activity>(screen, "this\$0")
        return View(activity).also { ReflectionHelpers.setField(screen, "view", it) }
    }
}

@Implements(className = "ai.nuxie.sdk.presentation.AndroidRenderCapability", isInAndroidSdk = false)
internal class LinkStateRenderCapabilityShadow {
    @Implementation fun isAvailable(): Boolean = true
}
