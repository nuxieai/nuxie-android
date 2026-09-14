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
            val store = NuxieTestStore(choices)
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
            val store = NuxieTestStore(choices)
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
    fun `restores isolate customers deduplicate products and retain latest product metadata`() = runTest {
        val store = NuxieTestStore(Choices())
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
        val store = NuxieTestStore(choices)
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
        assertTrue(NuxieTestStore(choices).restorePurchases("alice").products.isEmpty())
    }

    @Test
    fun `pending choice retains initiating customer and cancellation creates no purchase`() = runTest {
        val choices = Choices()
        val store = NuxieTestStore(choices)
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
