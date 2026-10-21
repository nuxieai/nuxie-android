package ai.nuxie.sdk.billing

import ai.nuxie.sdk.fixtures.FixtureRunner
import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class NuxieTestStoreTest {
    /** The server grants every purchase. */
    private val granted = TestStoreGranting { _, _, _ ->
        kotlinx.serialization.json.buildJsonObject { put("success", kotlinx.serialization.json.JsonPrimitive(true)) }
    }

    private class Choices : TestStoreChoices {
        var purchaseChoice = TestStorePurchaseChoice.PURCHASED
        var restoreChoice = TestStoreRestoreChoice.RESTORED
        var pending: CompletableDeferred<TestStorePurchaseChoice>? = null
        override suspend fun purchase(product: StoreProduct) = pending?.await() ?: purchaseChoice
        override suspend fun restore() = restoreChoice
    }

    private fun product(id: String, storeId: String = id) = StoreProduct(
        productId = id, storeProductId = storeId, basePlanId = null, offerId = null,
        placementId = null, rawProduct = null, offerToken = null,
        isOfferPersonalized = false, productType = "inapp",
    )

    @Test
    fun `matches shared local Test Store outcome vectors`() = runTest {
        val fixture = Json.parseToJsonElement(
            File(FixtureRunner.fixturesRoot(), "purchases/test-store.json").readText(),
        ).jsonObject
        for (scenario in fixture.getValue("cases").jsonArray) {
            val choices = Choices()
            val store = NuxieTestStore(choices, granted)
            val transactions = mutableSetOf<String>()
            for (element in scenario.jsonObject.getValue("actions").jsonArray) {
                val action = element.jsonObject
                fun value(key: String) = action.getValue(key).jsonPrimitive.content
                val customer = value("customer")
                val outcome: String
                if (value("operation") == "purchase") {
                    choices.purchaseChoice = TestStorePurchaseChoice.valueOf(value("choice").uppercase())
                    val response = store.purchase(product(value("product"), value("storeProduct")), customer)
                    outcome = when (response.result) {
                        PurchaseResult.Purchased -> "purchased"
                        PurchaseResult.Pending -> "pending"
                        PurchaseResult.Cancelled -> "cancelled"
                        is PurchaseResult.Failed -> "failed"
                    }
                    assertEquals(action.getValue("expectedTransaction").jsonPrimitive.boolean, response.transactionId != null)
                    response.transactionId?.let {
                        assertTrue(it.startsWith("nuxie-test-"))
                        assertTrue(transactions.add(it))
                    }
                } else {
                    assertEquals("restore", value("operation"))
                    choices.restoreChoice = TestStoreRestoreChoice.valueOf(value("choice").uppercase())
                    val response = store.restorePurchases(customer)
                    outcome = when (response.result) {
                        RestoreResult.Restored -> "restored"
                        RestoreResult.NoPurchases -> "no_purchases"
                        is RestoreResult.Failed -> "failed"
                    }
                    assertEquals(
                        action.getValue("expectedProducts").jsonArray.map { it.jsonPrimitive.content },
                        response.products.map { it.storeProductId }.sorted(),
                    )
                }
                assertEquals(value("expectedOutcome"), outcome)
            }
        }
    }

    @Test
    fun `purchase choices preserve iOS outcomes and only purchased records a product`() = runTest {
        for (choice in TestStorePurchaseChoice.entries) {
            val choices = Choices().apply { purchaseChoice = choice }
            val store = NuxieTestStore(choices, granted)
            val response = store.purchase(product("credits"), "alice")
            when (choice) {
                TestStorePurchaseChoice.PURCHASED -> {
                    assertEquals(PurchaseResult.Purchased, response.result)
                    assertTrue(response.transactionId!!.startsWith("nuxie-test-"))
                }
                TestStorePurchaseChoice.PENDING -> assertEquals(PurchaseResult.Pending, response.result)
                TestStorePurchaseChoice.CANCELLED -> assertEquals(PurchaseResult.Cancelled, response.result)
                TestStorePurchaseChoice.FAILED -> assertTrue(response.result is PurchaseResult.Failed)
            }
            val restored = store.restorePurchases("alice")
            assertEquals(RestoreResult.Restored, restored.result)
            if (choice == TestStorePurchaseChoice.PURCHASED) {
                assertEquals(listOf("credits"), restored.products.map { it.productId })
            } else {
                assertNull(response.transactionId)
                assertTrue(restored.products.isEmpty())
            }
        }
    }

    @Test
    fun `a purchase is granted by the server and carries the access it answers with`() = runTest {
        val grants = mutableListOf<Triple<String, String, String>>()
        val answer = kotlinx.serialization.json.buildJsonObject {
            put("success", kotlinx.serialization.json.JsonPrimitive(true))
            put("customer_id", kotlinx.serialization.json.JsonPrimitive("customer-1"))
        }
        val store = NuxieTestStore(Choices()) { catalogProductId, transactionId, distinctId ->
            grants += Triple(catalogProductId, transactionId, distinctId)
            answer
        }

        val response = store.purchase(product("prd_annual", "cadence_pro:annual"), "alice")

        assertEquals(PurchaseResult.Purchased, response.result)
        assertEquals(listOf(Triple("prd_annual", response.transactionId!!, "alice")), grants)
        assertEquals(answer, response.access)
    }

    @Test
    fun `a purchase the server does not grant fails and leaves nothing to restore`() = runTest {
        val store = NuxieTestStore(Choices()) { _, _, _ -> throw java.io.IOException("not granted") }

        val response = store.purchase(product("prd_annual"), "alice")

        assertTrue(response.result is PurchaseResult.Failed)
        assertNull(response.transactionId)
        assertNull(response.access)
        assertTrue(store.restorePurchases("alice").products.isEmpty())
    }

    @Test
    fun `an unanswered grant is replayed by the next purchase of the same product`() = runTest {
        val transactionIds = mutableListOf<String>()
        val answers = ArrayDeque<() -> kotlinx.serialization.json.JsonObject>(listOf(
            { throw java.io.IOException("timeout") },
            { throw java.io.IOException("Test Store grant answered HTTP 503") },
            { kotlinx.serialization.json.buildJsonObject { put("success", kotlinx.serialization.json.JsonPrimitive(true)) } },
        ))
        val store = NuxieTestStore(Choices()) { _, transactionId, _ ->
            transactionIds += transactionId
            answers.removeFirst()()
        }

        assertTrue(store.purchase(product("prd_annual"), "alice").result is PurchaseResult.Failed)
        assertTrue(store.purchase(product("prd_annual"), "alice").result is PurchaseResult.Failed)
        val granted = store.purchase(product("prd_annual"), "alice")

        assertEquals(PurchaseResult.Purchased, granted.result)
        assertEquals(3, transactionIds.size)
        assertEquals("every attempt replays the first transaction", 1, transactionIds.toSet().size)
        assertEquals(transactionIds.first(), granted.transactionId)
    }

    @Test
    fun `a refused or settled grant starts a new transaction next time`() = runTest {
        val transactionIds = mutableListOf<String>()
        var calls = 0
        val store = NuxieTestStore(Choices()) { _, transactionId, _ ->
            transactionIds += transactionId
            calls += 1
            if (calls == 1) throw ai.nuxie.sdk.network.NuxieApi.TestStoreGrantRefusedException(403)
            kotlinx.serialization.json.buildJsonObject { put("success", kotlinx.serialization.json.JsonPrimitive(true)) }
        }

        repeat(3) { store.purchase(product("prd_annual"), "alice") }

        assertEquals(3, transactionIds.toSet().size)
    }

    @Test
    fun `a purchase that overlaps one in flight is a purchase of its own`() = runTest {
        val transactionIds = mutableListOf<String>()
        val firstAnswer = CompletableDeferred<Unit>()
        val store = NuxieTestStore(Choices()) { _, transactionId, _ ->
            transactionIds += transactionId
            if (transactionIds.size == 1) firstAnswer.await()
            kotlinx.serialization.json.buildJsonObject { put("success", kotlinx.serialization.json.JsonPrimitive(true)) }
        }

        val first = async { store.purchase(product("prd_credits"), "alice") }
        runCurrent()
        val second = store.purchase(product("prd_credits"), "alice")
        firstAnswer.complete(Unit)

        assertEquals(PurchaseResult.Purchased, second.result)
        assertEquals(PurchaseResult.Purchased, first.await().result)
        assertEquals(2, transactionIds.toSet().size)
    }

    @Test
    fun `an unanswered grant is kept for its own customer and product`() = runTest {
        val transactionIds = mutableListOf<String>()
        val store = NuxieTestStore(Choices()) { _, transactionId, distinctId ->
            transactionIds += transactionId
            if (distinctId == "alice") throw java.io.IOException("timeout")
            kotlinx.serialization.json.buildJsonObject { put("success", kotlinx.serialization.json.JsonPrimitive(true)) }
        }

        store.purchase(product("prd_annual"), "alice")
        store.purchase(product("prd_annual"), "bob")
        store.purchase(product("prd_monthly"), "alice")

        assertEquals(3, transactionIds.toSet().size)
    }

    @Test
    fun `only a purchased choice reaches the server`() = runTest {
        var grants = 0
        for (choice in TestStorePurchaseChoice.entries - TestStorePurchaseChoice.PURCHASED) {
            val store = NuxieTestStore(Choices().apply { purchaseChoice = choice }) { _, _, _ ->
                grants += 1
                error("granted a $choice purchase")
            }
            store.purchase(product("prd_annual"), "alice")
        }
        assertEquals(0, grants)
    }

    @Test
    fun `restores isolate customers deduplicate products and retain latest product metadata`() = runTest {
        val store = NuxieTestStore(Choices(), granted)
        val first = store.purchase(product("credits", "old"), "alice")
        val second = store.purchase(product("credits", "new"), "alice")
        store.purchase(product("subscription"), "bob")
        assertNotEquals(first.transactionId, second.transactionId)
        assertEquals(listOf("new"), store.restorePurchases("alice").products.map { it.storeProductId })
        assertEquals(listOf("subscription"), store.restorePurchases("bob").products.map { it.productId })
        assertTrue(store.restorePurchases("carol").products.isEmpty())
    }

    @Test
    fun `restore choice is independent of local history and does not erase it`() = runTest {
        val choices = Choices()
        val store = NuxieTestStore(choices, granted)
        assertEquals(TestStoreRestoreResponse(RestoreResult.Restored), store.restorePurchases("alice"))
        store.purchase(product("credits"), "alice")
        choices.restoreChoice = TestStoreRestoreChoice.NO_PURCHASES
        assertEquals(TestStoreRestoreResponse(RestoreResult.NoPurchases), store.restorePurchases("alice"))
        choices.restoreChoice = TestStoreRestoreChoice.FAILED
        val failure = store.restorePurchases("alice")
        assertTrue(failure.result is RestoreResult.Failed)
        assertTrue(failure.products.isEmpty())
        choices.restoreChoice = TestStoreRestoreChoice.RESTORED
        assertEquals(1, store.restorePurchases("alice").products.size)
        assertTrue(NuxieTestStore(choices, granted).restorePurchases("alice").products.isEmpty())
    }

    @Test
    fun `pending choice retains initiating customer and cancellation creates no purchase`() = runTest {
        val choices = Choices()
        val store = NuxieTestStore(choices, granted)
        val decision = CompletableDeferred<TestStorePurchaseChoice>()
        choices.pending = decision
        val alice = async { store.purchase(product("credits"), "alice") }
        runCurrent()
        choices.pending = null
        store.purchase(product("subscription"), "bob")
        decision.complete(TestStorePurchaseChoice.PURCHASED)
        alice.await()
        assertEquals(listOf("credits"), store.restorePurchases("alice").products.map { it.productId })
        assertEquals(listOf("subscription"), store.restorePurchases("bob").products.map { it.productId })
        choices.pending = CompletableDeferred()
        val cancelled = async { store.purchase(product("cancelled"), "alice") }
        runCurrent()
        cancelled.cancel()
        cancelled.join()
        assertEquals(listOf("credits"), store.restorePurchases("alice").products.map { it.productId })
    }
}
