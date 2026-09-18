package ai.nuxie.sdk.presentation

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ExperienceScreenRetirementTest {
    @Test fun `file lease completion waits for media after the native lane ends`() {
        var completions = 0
        val retirement = ExperienceScreenRetirement { completions++ }
        retirement.nativeReleased()
        retirement.nativeReleased()
        assertEquals(0, completions)
        retirement.mediaReleased()
        retirement.mediaReleased()
        assertEquals(1, completions)
    }

    @Test fun `media completion cannot precede native retirement`() {
        var completions = 0
        val retirement = ExperienceScreenRetirement { completions++ }
        retirement.mediaReleased()
        assertEquals(0, completions)
        retirement.nativeReleased()
        assertEquals(1, completions)
    }

    @Test fun `simultaneous native and media completion runs once`() {
        val count = AtomicInteger()
        val retirement = ExperienceScreenRetirement { count.incrementAndGet() }
        val start = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)
        try {
            val first = executor.submit { assertTrue(start.await(5, TimeUnit.SECONDS)); retirement.nativeReleased() }
            val second = executor.submit { assertTrue(start.await(5, TimeUnit.SECONDS)); retirement.mediaReleased() }
            start.countDown()
            first.get(5, TimeUnit.SECONDS)
            second.get(5, TimeUnit.SECONDS)
            assertEquals(1, count.get())
        } finally { executor.shutdownNow() }
    }
}
