package ai.nuxie.sdk.runtime

import org.junit.Assert.*
import org.junit.Test

class VideoResourceCleanupTest {
    @Test fun attemptsAllResourcesAndRetriesOnlyFailures() {
        val calls = IntArray(4)
        val first = IllegalStateException("receiver still registered")
        val third = IllegalStateException("surface still owned")
        val cleanup = VideoResourceCleanup(
            Runnable { if (++calls[0] == 1) throw first },
            Runnable { calls[1]++ },
            Runnable { if (++calls[2] <= 2) throw third },
            Runnable { calls[3]++ },
        )

        val failure = checkNotNull(cleanup.release())
        assertArrayEquals(intArrayOf(1, 1, 1, 1), calls)
        assertArrayEquals(arrayOf(first, third), failure.suppressed)
        val retry = checkNotNull(cleanup.release())
        assertArrayEquals(intArrayOf(2, 1, 2, 1), calls)
        assertArrayEquals(arrayOf(third), retry.suppressed)
        assertNull(cleanup.release())
        assertArrayEquals(intArrayOf(2, 1, 3, 1), calls)
        assertNull(cleanup.release())
        assertArrayEquals(intArrayOf(2, 1, 3, 1), calls)
    }
}
