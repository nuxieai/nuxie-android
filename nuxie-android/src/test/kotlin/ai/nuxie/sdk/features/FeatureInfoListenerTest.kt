package ai.nuxie.sdk.features

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FeatureInfoListenerTest {
    @Test
    fun changedFeaturesNotifyAfterTheSameMutationPublishesWithoutCoalescing() = runBlocking {
        val info = FeatureInfo()
        val first = access(allowed = true, balance = 2.0)
        val second = access(allowed = false, balance = 0.0)
        val callbacks = mutableListOf<Callback>()
        info.onFeatureChange = { featureId, oldAccess, newAccess, _ ->
            callbacks += Callback(featureId, oldAccess, newAccess, info.all.value[featureId])
        }

        info.update(mapOf("credits" to first), emptyMap())
        info.update(mapOf("credits" to first), emptyMap())
        info.update(mapOf("credits" to second), emptyMap())

        assertEquals(
            listOf(
                Callback("credits", null, first, first),
                Callback("credits", first, second, second),
            ),
            callbacks,
        )
        assertEquals(second, info.all.value.getValue("credits"))
    }

    @Test
    fun stagedEngineCommitsPublishInReservationOrderEvenWhenAwaitedInReverse() = runBlocking {
        val info = FeatureInfo()
        val first = access(allowed = true, balance = 2.0)
        val second = access(allowed = false, balance = 0.0)
        val transitions = mutableListOf<Pair<FeatureAccess?, FeatureAccess>>()
        info.onFeatureChange = { _, oldAccess, newAccess, _ ->
            transitions += oldAccess to newAccess
        }
        val firstCommit = info.stageUpdate("credits", first, null)
        val secondCommit = info.stageUpdate("credits", second, null)

        val laterPublisher = launch { info.publish(secondCommit) }
        info.publish(firstCommit)
        laterPublisher.join()

        assertEquals(listOf(null to first, first to second), transitions)
        assertEquals(second, info.all.value.getValue("credits"))
    }

    @Test
    fun immediateResetInvalidatesAnOlderPublicationWaitingInAListener() = runBlocking {
        val info = FeatureInfo()
        val listenerStarted = CompletableDeferred<Unit>()
        val releaseListener = CompletableDeferred<Unit>()
        info.onFeatureChange = { _, _, newAccess, _ ->
            if (newAccess.allowed) {
                listenerStarted.complete(Unit)
                releaseListener.await()
            }
        }
        val update = async {
            info.update(mapOf("credits" to access(allowed = true, balance = 2.0)), emptyMap())
        }
        listenerStarted.await()

        info.publish(
            info.stageIdentityChange(emptyMap(), emptyMap(), FeatureInfo.State.Unknown),
        )

        assertFalse(info.isAllowed("credits"))
        releaseListener.complete(Unit)
        update.await()
        assertFalse(info.isAllowed("credits"))
    }

    @Test
    fun identityInvalidationWaitsForAnInProgressEmissionCommit() = runBlocking {
        val info = FeatureInfo()
        val emissionCheckCount = AtomicInteger()
        val oldEmissionHasLock = CountDownLatch(1)
        val releaseOldEmission = CountDownLatch(1)
        val identityThreadStarted = CountDownLatch(1)
        val stagedIdentity = AtomicReference<FeatureInfo.Mutation>()
        val oldMutation = info.stageUpdate(
            features = mapOf("credits" to access(allowed = true, balance = 2.0)),
            entities = emptyMap(),
            isCurrent = {
                if (emissionCheckCount.incrementAndGet() == 3) {
                    oldEmissionHasLock.countDown()
                    check(releaseOldEmission.await(5, TimeUnit.SECONDS))
                }
                true
            },
        )
        val oldPublisher = async(Dispatchers.Default) { info.publish(oldMutation) }
        assertTrue(oldEmissionHasLock.await(5, TimeUnit.SECONDS))
        val identityThread = Thread {
            identityThreadStarted.countDown()
            stagedIdentity.set(
                info.stageIdentityChange(emptyMap(), emptyMap(), FeatureInfo.State.Unknown),
            )
        }

        try {
            identityThread.start()
            assertTrue(identityThreadStarted.await(5, TimeUnit.SECONDS))
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (identityThread.isAlive &&
                identityThread.state != Thread.State.BLOCKED &&
                System.nanoTime() < deadline
            ) {
                Thread.yield()
            }

            assertEquals(Thread.State.BLOCKED, identityThread.state)
        } finally {
            releaseOldEmission.countDown()
            identityThread.join(5_000)
        }

        oldPublisher.await()
        info.publish(checkNotNull(stagedIdentity.get()))
        assertFalse(info.isAllowed("credits"))
    }

    @Test
    fun signedZeroBalanceDoesNotEmitOnEitherSurface() = runBlocking {
        val info = FeatureInfo()
        val transitions = mutableListOf<Pair<FeatureAccess?, FeatureAccess>>()
        val flowBalances = mutableListOf<Double>()
        info.onFeatureChange = { _, oldAccess, newAccess, _ ->
            transitions += oldAccess to newAccess
        }
        val collector = launch(start = CoroutineStart.UNDISPATCHED) {
            info.all.collect { access -> access["credits"]?.balance?.let(flowBalances::add) }
        }

        info.update(mapOf("credits" to access(allowed = false, balance = -0.0)), emptyMap())
        yield()
        info.update(mapOf("credits" to access(allowed = false, balance = 0.0)), emptyMap())
        yield()
        collector.cancelAndJoin()

        assertEquals(1, transitions.size)
        assertEquals(1, flowBalances.size)
    }

    @Test
    fun nanBalanceEmitsOnBothSurfacesLikeSwift() = runBlocking {
        val info = FeatureInfo()
        val transitions = mutableListOf<Pair<FeatureAccess?, FeatureAccess>>()
        val flowBalances = mutableListOf<Double>()
        info.onFeatureChange = { _, oldAccess, newAccess, _ ->
            transitions += oldAccess to newAccess
        }
        val collector = launch(start = CoroutineStart.UNDISPATCHED) {
            info.all.collect { access -> access["credits"]?.balance?.let(flowBalances::add) }
        }

        info.update(mapOf("credits" to access(allowed = false, balance = Double.NaN)), emptyMap())
        yield()
        info.update(mapOf("credits" to access(allowed = false, balance = Double.NaN)), emptyMap())
        yield()
        collector.cancelAndJoin()

        assertEquals(2, transitions.size)
        assertEquals(2, flowBalances.size)
    }

    @Test
    fun bulkRemovalEmitsANotFoundEquivalentTransition() = runBlocking {
        val info = FeatureInfo()
        val granted = access(allowed = true, balance = 2.0)
        val transitions = mutableListOf<Pair<FeatureAccess?, FeatureAccess>>()
        info.update(mapOf("credits" to granted), emptyMap())
        info.onFeatureChange = { _, oldAccess, newAccess, _ ->
            transitions += oldAccess to newAccess
        }

        info.update(emptyMap(), emptyMap())

        assertEquals(
            listOf(
                granted to FeatureAccess(
                    allowed = false,
                    unlimited = false,
                    balance = null,
                    type = FeatureType.BOOLEAN,
                ),
            ),
            transitions,
        )
        assertEquals(emptyMap<String, FeatureAccess>(), info.all.value)
    }

    @Test
    fun readinessPublishesBeforeTheValuesItDescribes() = runBlocking {
        val info = FeatureInfo()
        val observedState = CompletableDeferred<FeatureInfo.State>()
        val collector = launch(start = CoroutineStart.UNDISPATCHED) {
            info.all.collect { features ->
                if (features.containsKey("credits")) {
                    observedState.complete(info.state.value)
                }
            }
        }

        info.update(
            mapOf("credits" to access(allowed = true, balance = 2.0)),
            emptyMap(),
            FeatureInfo.State.Reconciling,
        )

        assertEquals(FeatureInfo.State.Reconciling, observedState.await())
        collector.cancelAndJoin()
    }

    @Test
    fun callbackIdentityChangeStopsTheSupersededCallbackBatch() = runBlocking {
        val info = FeatureInfo()
        val granted = access(allowed = true, balance = 2.0)
        val grantedCallbacks = mutableListOf<String>()
        info.onFeatureChange = { featureId, _, newAccess, _ ->
            if (newAccess.allowed) {
                grantedCallbacks += featureId
                if (featureId == "alpha") {
                    info.publish(
                        info.stageIdentityChange(
                            emptyMap(),
                            emptyMap(),
                            FeatureInfo.State.Unknown,
                        ),
                    )
                }
            }
        }

        info.update(
            linkedMapOf("alpha" to granted, "beta" to granted),
            emptyMap(),
            FeatureInfo.State.Reconciling,
        )

        assertEquals(listOf("alpha"), grantedCallbacks)
        assertEquals(emptyMap<String, FeatureAccess>(), info.all.value)
        assertEquals(FeatureInfo.State.Unknown, info.state.value)
    }

    private fun access(allowed: Boolean, balance: Double) = FeatureAccess(
        allowed = allowed,
        unlimited = false,
        balance = balance,
        type = FeatureType.CREDIT_SYSTEM,
    )

    private data class Callback(
        val featureId: String,
        val oldAccess: FeatureAccess?,
        val newAccess: FeatureAccess,
        val flowAccessDuringCallback: FeatureAccess?,
    )
}
