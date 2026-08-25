package ai.nuxie.sdk.experiences

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class CacheFilesystemLockTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun sameRootLockSerializesCallers() {
        val root = temporaryFolder.newFolder("serialized")
        val first = CacheFilesystemLock(root)
        val second = CacheFilesystemLock(root)
        val enteredFirst = CountDownLatch(1)
        val releaseFirst = CountDownLatch(1)
        val enteredSecond = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)
        try {
            executor.submit {
                first.withLock {
                    enteredFirst.countDown()
                    releaseFirst.await(5, TimeUnit.SECONDS)
                }
            }
            assertTrue(enteredFirst.await(5, TimeUnit.SECONDS))
            executor.submit {
                second.withLock { enteredSecond.countDown() }
            }

            assertFalse(enteredSecond.await(100, TimeUnit.MILLISECONDS))
            releaseFirst.countDown()
            assertTrue(enteredSecond.await(5, TimeUnit.SECONDS))
        } finally {
            releaseFirst.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun lockIsReentrantOnTheOwningThread() {
        val lock = CacheFilesystemLock(temporaryFolder.newFolder("reentrant"))
        var entries = 0

        lock.withLock {
            entries += 1
            lock.withLock { entries += 1 }
        }

        assertEquals(2, entries)
    }

    @Test
    fun deadProcessProtectionMarkerIsReclaimed() {
        val lock = CacheFilesystemLock(temporaryFolder.newFolder("stale-marker"))
        val deadOwner = CacheProtectionRegistry(
            filesystemLock = lock,
            currentProcessId = 123,
            processIdentity = { pid -> ProcessIdentity(pid, 10) },
            processExists = { true },
        )
        val protection = deadOwner.register(setOf("a".repeat(64)))
        val reclaimer = CacheProtectionRegistry(
            filesystemLock = lock,
            currentProcessId = 456,
            processIdentity = { pid -> if (pid == 456) ProcessIdentity(pid, 20) else null },
            processExists = { pid -> pid == 456 },
        )

        assertEquals(emptySet<String>(), reclaimer.protectedDigests())
        assertEquals(0, lock.protectionDirectory.listFiles()?.size ?: 0)
        protection.close()
    }
}
