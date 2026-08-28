package ai.nuxie.sdk.features

import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
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
