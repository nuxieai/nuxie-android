package ai.nuxie.example

import android.app.Activity
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ExampleApplicationTest {
  @Test fun outgoingActivityCannotClearTheIncomingCheckoutHost() {
    val app = RuntimeEnvironment.getApplication() as ExampleApplication
    val first = Robolectric.buildActivity(Activity::class.java).get()
    val second = Robolectric.buildActivity(Activity::class.java).get()
    app.onActivityResumed(first)
    app.onActivityResumed(second)
    app.onActivityPaused(first)
    app.onActivityDestroyed(first)
    assertSame(second, app.currentActivity)
    app.onActivityPaused(second)
    assertNull(app.currentActivity)
  }

  @Test fun destroyedActivityIsNotReturnedForCheckout() {
    val app = RuntimeEnvironment.getApplication() as ExampleApplication
    val activity = Robolectric.buildActivity(Activity::class.java).get()
    app.onActivityResumed(activity)
    assertSame(activity, app.currentActivity)
    app.onActivityDestroyed(activity)
    assertNull(app.currentActivity)
  }
}
