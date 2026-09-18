package ai.nuxie.sdk.runtime

import android.content.BroadcastReceiver
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.os.Handler
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

/** Real decoder; injects the system route notification at its registered callback. */
class AndroidVideoRouteDeviceTest {
    private class RouteContext(context: Context) : ContextWrapper(context) {
        @Volatile var receiver: BroadcastReceiver? = null
        @Volatile var scheduler: Handler? = null
        @Volatile var failNextUnregister = false
        override fun getApplicationContext(): Context = this
        override fun registerReceiver(receiver: BroadcastReceiver?, filter: IntentFilter?, permission: String?, scheduler: Handler?): Intent? {
            val result = super.registerReceiver(receiver, filter, permission, scheduler)
            if (filter?.hasAction(AudioManager.ACTION_AUDIO_BECOMING_NOISY) == true) {
                this.receiver = receiver
                this.scheduler = scheduler
            }
            return result
        }
        override fun unregisterReceiver(receiver: BroadcastReceiver?) {
            if (failNextUnregister) {
                failNextUnregister = false
                throw IllegalStateException("injected unregister failure")
            }
            super.unregisterReceiver(receiver)
            if (this.receiver === receiver) this.receiver = null
        }
        fun disconnect() {
            val delivered = CountDownLatch(1)
            checkNotNull(scheduler).post {
                try { checkNotNull(receiver).onReceive(this, Intent(AudioManager.ACTION_AUDIO_BECOMING_NOISY)) }
                finally { delivered.countDown() }
            }
            assertTrue("Route callback completes on decoder thread", delivered.await(3, TimeUnit.SECONDS))
        }
    }

    @Test fun failedCloseRetainsReleaseCallbacksUntilRetryCompletes() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val source = File.createTempFile("cleanup-video-", ".mp4", instrumentation.targetContext.cacheDir)
        instrumentation.context.assets.open("video/greeting.mp4").use { input -> source.outputStream().use { input.copyTo(it) } }
        val context = RouteContext(instrumentation.targetContext)
        val decoder = AndroidVideoDecoder(context, source, 1, 64 * 1024 * 1024, 1)
        val callbacks = java.util.concurrent.atomic.AtomicInteger()
        try {
            val deadline = SystemClock.elapsedRealtime() + 10_000
            while (!decoder.ready() && SystemClock.elapsedRealtime() < deadline) Thread.sleep(5)
            assertTrue("Decoder prepared before teardown fault", decoder.ready())
            decoder.whenReleased { callbacks.incrementAndGet() }
            context.failNextUnregister = true
            assertThrows(IllegalStateException::class.java) { decoder.close() }
            assertEquals("Lease and pool callbacks remain held", 0, callbacks.get())
            assertNotNull(context.receiver)
            decoder.close()
            assertEquals(1, callbacks.get())
            assertNull(context.receiver)
            decoder.close()
            assertEquals("Repeated close must not release ownership twice", 1, callbacks.get())
            decoder.whenReleased { callbacks.incrementAndGet() }
            assertEquals("Late observer sees completed teardown", 2, callbacks.get())
        } finally {
            decoder.close()
            source.delete()
        }
    }

    @Test fun noisyRoutePausesAudibleVideoButLeavesSilentMotionPlaying() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val source = File.createTempFile("route-video-", ".mp4", instrumentation.targetContext.cacheDir)
        instrumentation.context.assets.open("video/greeting.mp4").use { input -> source.outputStream().use { input.copyTo(it) } }
        val audibleContext = RouteContext(instrumentation.targetContext)
        val silentContext = RouteContext(instrumentation.targetContext)
        val audible = AndroidVideoDecoder(audibleContext, source, 1, 64 * 1024 * 1024, 1)
        val silent = AndroidVideoDecoder(silentContext, source, 1, 64 * 1024 * 1024, 1)
        fun await(message: String, condition: () -> Boolean) {
            val deadline = SystemClock.elapsedRealtime() + 10_000
            while (!condition() && SystemClock.elapsedRealtime() < deadline) Thread.sleep(5)
            assertTrue(message, condition())
        }
        try {
            await("Both decoders prepared") { audible.ready() && silent.ready() }
            audible.action(4, 1.0, 1)
            audible.action(0, 0.0, 1)
            silent.action(0, 0.0, 1)
            await("Both decoders started") { audible.playing() && silent.playing() }
            audibleContext.disconnect()
            silentContext.disconnect()
            assertFalse(audible.playing())
            assertTrue("Runtime must receive a pause intent", audible.takePermanentLoss())
            assertFalse(audible.takePermanentLoss())
            assertTrue(silent.playing())
            assertFalse(silent.takePermanentLoss())
            audible.action(0, 0.0, 1)
            await("Explicit play resumes after route loss") { audible.playing() }
            assertNull(audible.failure())
            assertNull(silent.failure())
        } finally {
            try { audible.close() } finally { silent.close(); source.delete() }
        }
        assertNull(audibleContext.receiver)
        assertNull(silentContext.receiver)
    }
}
