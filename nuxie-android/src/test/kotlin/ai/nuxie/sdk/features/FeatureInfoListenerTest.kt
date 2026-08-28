package ai.nuxie.sdk.features

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Test

class FeatureInfoListenerTest {
    @Test
    fun changedFeaturesNotifyBeforeTheSameMutationPublishesWithoutCoalescing() = runBlocking {
        val info = FeatureInfo()
        val first = access(allowed = true, balance = 2.0)
        val second = access(allowed = false, balance = 0.0)
        val callbacks = mutableListOf<Callback>()
        info.onFeatureChange = { featureId, oldAccess, newAccess ->
            callbacks += Callback(featureId, oldAccess, newAccess, info.all.value[featureId])
        }

        info.update(mapOf("credits" to first), emptyMap())
        info.update(mapOf("credits" to first), emptyMap())
        info.update(mapOf("credits" to second), emptyMap())

        assertEquals(
            listOf(
                Callback("credits", null, first, null),
                Callback("credits", first, second, first),
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
        info.onFeatureChange = { _, oldAccess, newAccess -> transitions += oldAccess to newAccess }
        val firstCommit = info.stageUpdate("credits", first, null)
        val secondCommit = info.stageUpdate("credits", second, null)

        val laterPublisher = launch { info.publish(secondCommit) }
        info.publish(firstCommit)
        laterPublisher.join()

        assertEquals(listOf(null to first, first to second), transitions)
        assertEquals(second, info.all.value.getValue("credits"))
    }

    @Test
    fun signedZeroBalanceDoesNotEmitOnEitherSurface() = runBlocking {
        val info = FeatureInfo()
        val transitions = mutableListOf<Pair<FeatureAccess?, FeatureAccess>>()
        val flowBalances = mutableListOf<Double>()
        info.onFeatureChange = { _, oldAccess, newAccess -> transitions += oldAccess to newAccess }
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
        info.onFeatureChange = { _, oldAccess, newAccess -> transitions += oldAccess to newAccess }
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
