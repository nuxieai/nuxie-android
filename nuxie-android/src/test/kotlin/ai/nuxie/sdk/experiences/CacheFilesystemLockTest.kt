package ai.nuxie.sdk.experiences

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
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
    fun targetLockScopesAreDroppedAfterTheirLastCallerReleases() {
        val lock = CacheFilesystemLock(temporaryFolder.newFolder("released-targets"))
        val retainedBefore = retainedScopeCount()

        repeat(100) { target ->
            lock.withTargetLock("target-$target") {}
        }

        assertEquals(retainedBefore, retainedScopeCount())
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

    @Test
    fun parkedRunArtifactsRemainProtectedAfterTheirProcessDiesUntilReportQueueCommit() {
        val root = temporaryFolder.newFolder("parked-run")
        val first = CacheProtectionRegistry(CacheFilesystemLock(root), 123, { ProcessIdentity(it, 10) }, { true })
        val digest = "a".repeat(64)
        val lease = first.retainRun("customer/journey/generation", setOf(digest))
        lease.close()
        val restarted = CacheProtectionRegistry(CacheFilesystemLock(root), 456,
            { pid -> if (pid == 456) ProcessIdentity(pid, 20) else null }, { it == 456 })
        assertEquals(setOf(digest), restarted.protectedDigests())
        val resumed = restarted.retainRun("customer/journey/generation", setOf(digest))
        assertEquals(emptySet<String>(), restarted.protectedDigests(excludingOwnerId = resumed.ownerId))
        assertThrows(java.io.IOException::class.java) {
            restarted.retainRun("customer/journey/generation", setOf("b".repeat(64)))
        }
        assertEquals(setOf(digest), restarted.protectedDigests())
        restarted.releaseRun("customer/journey/generation")
        assertEquals(emptySet<String>(), restarted.protectedDigests())
        restarted.releaseRun("customer/journey/generation")
    }

    private fun retainedScopeCount(): Int {
        val type = CacheFilesystemLock::class.java
        val scopesLock = type.getDeclaredField("scopesLock").apply { isAccessible = true }.get(null)!!
        val scopes = type.getDeclaredField("scopes").apply { isAccessible = true }.get(null)
            as Map<*, *>
        return synchronized(scopesLock) { scopes.size }
    }
}
