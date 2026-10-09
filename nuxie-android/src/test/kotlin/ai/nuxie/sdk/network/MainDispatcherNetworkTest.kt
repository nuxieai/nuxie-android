package ai.nuxie.sdk.network

import ai.nuxie.sdk.NuxieEnvironment
import ai.nuxie.sdk.billing.NuxieApiPurchaseSynchronizer
import ai.nuxie.sdk.billing.PurchaseEvidence
import ai.nuxie.sdk.billing.PurchaseSyncOutcome
import ai.nuxie.sdk.billing.StoredPurchaseState
import ai.nuxie.sdk.journey.JourneyResponseSave
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertThrows
import java.net.ServerSocket
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class MainDispatcherNetworkTest {
    @Test fun everyApiEndpointMovesTransportOffTheCallerMainDispatcher() = runBlocking {
        val calls: List<Pair<String, suspend NuxieApi.() -> Any?>> = listOf(
            "/profile" to { fetchProfile("customer", null) },
            "/batch" to { postBatch(listOf("{}")) },
            "/event" to { postEvent("{}") },
            "/feature/consume" to { consumeFeature(JsonObject(emptyMap())) },
            "/entitled" to { checkFeature("customer", "feature", null, null) },
            "/purchase" to { postPurchase(NuxieApi.PlayPurchaseReport("product", "token", null,
                offerId = null, obfuscatedAccountId = null, distinctId = "customer")) },
            "/feature/consume" to { useFeatureWithPurchase(NuxieApi.PurchaseBackedFeatureUseReport(
                "customer", "feature", 1.0, NuxieApi.FeatureUseEventData(1.0, null), null,
                NuxieApi.PlayPurchaseUseReport("test.package", "product", "token", null,
                    offerId = null, obfuscatedAccountId = null, eventId = "operation"))) },
            "/responses/save" to { sendResponseSave(JourneyResponseSave("customer", "journey", "experience",
                "version", "form", 1L, JsonObject(emptyMap()))) },
        )
        val mainThread = AtomicReference<Thread>()
        Executors.newSingleThreadExecutor { runnable -> Thread(runnable, "host-main").also(mainThread::set) }
            .asCoroutineDispatcher().use { main ->
                Dispatchers.setMain(main)
                try {
                    for ((path, call) in calls) {
                        val failure = IOException("controlled transport failure")
                        val api = NuxieApi("test-key", NuxieEnvironment.DEVELOPMENT, HttpTransport { request ->
                            assertTrue("$path must not perform blocking HTTP on Main", Thread.currentThread() !== mainThread.get())
                            assertEquals(path, request.url.path)
                            throw failure
                        })
                        val caught = try { withContext(Dispatchers.Main.immediate) { api.call() }; null }
                            catch (error: IOException) { error }
                        assertEquals("$path must preserve the transport failure", "controlled transport failure", caught?.message)
                    }
                } finally { Dispatchers.resetMain() }
            }
    }

    @Test fun purchaseSyncDoesNotSwallowCancellation() {
        val cancellation = CancellationException("controlled cancellation")
        val api = NuxieApi("test-key", NuxieEnvironment.DEVELOPMENT, HttpTransport { throw cancellation })
        val caught = assertThrows(CancellationException::class.java) {
            runBlocking { NuxieApiPurchaseSynchronizer(api).sync(PurchaseEvidence(
                purchaseToken = "token", packageName = "test.package", storeProductIds = listOf("product"),
                purchaseState = StoredPurchaseState.PURCHASED, syncAttributionDistinctId = "customer",
                acknowledged = true, synced = true, firstSeenMillis = 1L,
            )) }
        }
        assertEquals("controlled cancellation", caught.message)
    }

    @Test fun syncedPurchaseRefreshFromMainUsesIoForTheRealHttpRequest() = runBlocking {
        val mainThread = AtomicReference<Thread>()
        val requestLine = AtomicReference<String>()
        val serverFailure = AtomicReference<Throwable>()
        val socket = ServerSocket(0)
        val server = Thread {
            try {
                socket.accept().use { connection ->
                    connection.soTimeout = 5_000
                    val input = connection.getInputStream().bufferedReader()
                    requestLine.set(input.readLine())
                    var length = 0
                    while (true) {
                        val line = input.readLine() ?: break
                        if (line.isEmpty()) break
                        if (line.startsWith("Content-Length:", ignoreCase = true)) length = line.substringAfter(':').trim().toInt()
                    }
                    repeat(length) { check(input.read() >= 0) }
                    val body = """{"success":true,"customer_id":"customer","catalog_product":{"id":"product","store_product_id":"play-pro","store_product_type":"subscription"}}"""
                    connection.getOutputStream().write(("HTTP/1.1 200 OK\r\nContent-Length: ${body.length}\r\nConnection: close\r\n\r\n" + body).encodeToByteArray())
                }
            } catch (error: Throwable) { if (!socket.isClosed) serverFailure.set(error) }
        }.apply { isDaemon = true; start() }
        Executors.newSingleThreadExecutor { runnable -> Thread(runnable, "host-main").also(mainThread::set) }
            .asCoroutineDispatcher().use { main ->
                Dispatchers.setMain(main)
                try {
                    val realTransport = HttpUrlConnectionTransport()
                    val api = NuxieApi("test-key", NuxieEnvironment.DEVELOPMENT,
                        transport = HttpTransport { request ->
                            // JVM equivalent of Android's main-thread network prohibition.
                            check(Thread.currentThread() !== mainThread.get()) { "Network on host Main dispatcher" }
                            realTransport.execute(request)
                        }, baseUrlOverride = URL("http://127.0.0.1:${socket.localPort}"))
                    val evidence = PurchaseEvidence(
                        purchaseToken = "test-token", packageName = "test.package", storeProductIds = listOf("play-pro"),
                        productType = "subs", purchaseState = StoredPurchaseState.PURCHASED,
                        syncAttributionDistinctId = "customer", ownerDistinctId = "customer", nuxieManaged = true,
                        acknowledged = true, synced = true, firstSeenMillis = 1L, authorityScope = "test",
                    )
                    val result = withContext(Dispatchers.Main.immediate) {
                        NuxieApiPurchaseSynchronizer(api).sync(evidence)
                    }
                    assertTrue("A retained purchase refresh must reach the backend from a Main caller", result is PurchaseSyncOutcome.Accepted)
                    assertEquals("POST /purchase HTTP/1.1", requestLine.get())
                    assertEquals(null, serverFailure.get())
                } finally {
                    Dispatchers.resetMain()
                    socket.close()
                    server.join(5_000)
                }
            }
    }
}
