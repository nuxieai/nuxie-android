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
    fun identityInvalidationDuringAnEmissionLeavesTheNewCustomerSnapshot() = runBlocking {
        val info = FeatureInfo()
        val emissionEntered = CountDownLatch(1)
        val releaseEmission = CountDownLatch(1)
        val checks = AtomicInteger()
        val oldMutation = info.stageUpdate(
            features = mapOf("credits" to access(allowed = true, balance = 2.0)),
            entities = emptyMap(),
            isCurrent = {
                if (checks.incrementAndGet() == 2) {
                    emissionEntered.countDown()
                    check(releaseEmission.await(5, TimeUnit.SECONDS))
                }
                true
            },
        )
        val oldPublisher = async(Dispatchers.Default) { info.publish(oldMutation) }
        assertTrue(emissionEntered.await(5, TimeUnit.SECONDS))

        // Invalidation never waits for an in-progress emission: emissions
        // hold no lock across the store (that lock is what deadlocked
        // reentrant identify), and the identity snapshot simply wins the
        // CAS-committed container.
        val stagedIdentity =
            info.stageIdentityChange(emptyMap(), emptyMap(), FeatureInfo.State.Unknown)
        releaseEmission.countDown()
        oldPublisher.await()
        info.publish(stagedIdentity)

        assertFalse(info.isAllowed("credits"))
        assertEquals(emptyMap<String, FeatureAccess>(), info.all.value)
        assertEquals(FeatureInfo.State.Unknown, info.state.value)
    }

    @Test
    fun staleWriterStandsDownAfterAFieldIdenticalIdentityPublicationCompletes() = runBlocking {
        val info = FeatureInfo()
        val original = access(allowed = true, balance = 2.0)
        info.update(mapOf("credits" to original), emptyMap(), FeatureInfo.State.Ready)

        val emissionEntered = CountDownLatch(1)
        val releaseEmission = CountDownLatch(1)
        val checks = AtomicInteger()
        val staleMutation = info.stageUpdate(
            features = mapOf("credits" to access(allowed = false, balance = 0.0)),
            entities = emptyMap(),
            // Check 2 is the commit-loop fence. Pausing INSIDE the check
            // (after the generation conjunct already read as current) models
            // the fence-then-CAS gap: the writer resumes believing it is
            // current after a replacement generation has fully published.
            isCurrent = {
                if (checks.incrementAndGet() == 2) {
                    emissionEntered.countDown()
                    check(releaseEmission.await(5, TimeUnit.SECONDS))
                }
                true
            },
        )
        val stalePublisher = async(Dispatchers.Default) { info.publish(staleMutation) }
        assertTrue(emissionEntered.await(5, TimeUnit.SECONDS))

        // The identity publication is field-identical to the standing
        // snapshot: it must still commit a fresh, newer-generation container
        // (never dedupe across generations) so the resumed stale writer
        // cannot CAS its old-customer values over the completed swap.
        info.publish(
            info.stageIdentityChange(
                mapOf("credits" to original),
                emptyMap(),
                FeatureInfo.State.Ready,
            ),
        )
        releaseEmission.countDown()
        stalePublisher.await()

        assertEquals(original, info.all.value.getValue("credits"))
        assertEquals(FeatureInfo.State.Ready, info.state.value)
    }

    @Test
    fun fieldEqualFeaturesKeepTheVisibleMapWhenReadinessChanges() = runBlocking {
        val info = FeatureInfo()
        val flowMaps = mutableListOf<Map<String, FeatureAccess>>()
        val collector = launch(start = CoroutineStart.UNDISPATCHED) {
            info.all.collect(flowMaps::add)
        }

        info.update(mapOf("credits" to access(allowed = false, balance = -0.0)), emptyMap())
        yield()
        // +0.0 is field-equal to -0.0, so collectors stay silent — and the
        // snapshot must keep the PRIOR map so value/replayCache/new
        // subscribers agree with what collectors last saw, even though the
        // readiness change commits a new container.
        info.update(
            mapOf("credits" to access(allowed = false, balance = 0.0)),
            emptyMap(),
            FeatureInfo.State.Ready,
        )
        yield()
        collector.cancelAndJoin()

        assertEquals(2, flowMaps.size)
        assertEquals(FeatureInfo.State.Ready, info.state.value)
        val visible = info.all.value.getValue("credits").balance
        assertEquals((-0.0).toRawBits(), visible?.toRawBits())
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
    fun scopeChangeCannotSplitReadinessFromItsStagedValues() = runBlocking {
        val info = FeatureInfo()
        val emissionChecks = AtomicInteger()
        val granted = access(allowed = true, balance = 2.0)
        val mutation = info.stageUpdate(
            features = mapOf("credits" to granted),
            entities = emptyMap(),
            state = FeatureInfo.State.Ready,
            // The fourth check is the post-emission fence. A scope change
            // there must not leave readiness committed without its values.
            isCurrent = { emissionChecks.incrementAndGet() < 4 },
        )

        info.publish(mutation)

        assertEquals(FeatureInfo.State.Ready, info.state.value)
        assertEquals(granted, info.all.value["credits"])
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
