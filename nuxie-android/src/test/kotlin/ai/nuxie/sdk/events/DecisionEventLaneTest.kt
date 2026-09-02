package ai.nuxie.sdk.events

import ai.nuxie.sdk.LogLevel
import ai.nuxie.sdk.NuxieEnvironment
import ai.nuxie.sdk.identity.IdentityProvider
import ai.nuxie.sdk.journey.JourneyEventNames
import ai.nuxie.sdk.network.HttpTransport
import ai.nuxie.sdk.network.NuxieApi
import java.util.zip.GZIPInputStream
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class DecisionEventLaneTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val stores = mutableListOf<SQLiteEventStore>()
    private val logs = mutableListOf<EventLog>()

    private class FakeIdentity : IdentityProvider {
        override fun distinctId(): String = "current-customer"
        override fun anonymousId(): String = "anonymous-customer"
        override fun rawDistinctId(): String? = "current-customer"
        override val isIdentified: Boolean = true
    }

    private class RecordingTransport : HttpTransport {
        val requests = mutableListOf<HttpTransport.Request>()

        override fun execute(request: HttpTransport.Request): HttpTransport.Response {
            synchronized(requests) { requests += request }
            val body = when (request.url.path) {
                "/event" -> """{"status":"ok","facts":[]}"""
                "/batch" -> """{"status":"ok"}"""
                else -> error("Unexpected request path ${request.url.path}")
            }
            return HttpTransport.Response(200, body.encodeToByteArray())
        }
    }

    private class RejectingEventTransport(
        var statusCode: Int,
    ) : HttpTransport {
        val requests = mutableListOf<HttpTransport.Request>()

        override fun execute(request: HttpTransport.Request): HttpTransport.Response {
            synchronized(requests) { requests += request }
            return HttpTransport.Response(statusCode, "{\"error\":\"rejected\"}".encodeToByteArray())
        }
    }

    private fun store(): SQLiteEventStore =
        SQLiteEventStore(RuntimeEnvironment.getApplication()).also(stores::add)

    private fun log(store: EventStore): EventLog = EventLog(
        store = store,
        contextBuilder = NuxieContextBuilder(
            RuntimeEnvironment.getApplication(),
            NuxieEnvironment.DEVELOPMENT,
            LogLevel.NONE,
            FakeIdentity(),
        ),
        identity = FakeIdentity(),
        beforeSend = null,
        scope = scope,
        nowMillis = { 1_784_462_400_000L },
    ).also(logs::add)

    @After
    fun tearDown() = runBlocking {
        logs.asReversed().forEach { runCatching { it.close() } }
        scope.coroutineContext[Job]?.cancelAndJoin()
        stores.clear()
        logs.clear()
    }

    @Test
    fun directCaptureDrainsPredecessorsWithoutRacingItsCommittedKick() = runBlocking {
        val store = store()
        val eventLog = log(store)
        val transport = RecordingTransport()
        val api = NuxieApi("pk_test", NuxieEnvironment.DEVELOPMENT, transport)
        val applied = mutableListOf<String>()
        val delivery = EventDeliveryWorker(
            store = store,
            eventLog = eventLog,
            api = api,
            scope = scope,
            onDecisionResponse = { event, _ -> applied += event.id },
        )
        eventLog.subscribeCommitted { delivery.kick() }
        store.insertPending(
            StoredEvent(
                "ordinary-before",
                "ordinary",
                timestampMillis = 1_784_462_399_000L,
                distinctId = "owner-customer",
            ),
        )

        val captured = delivery.capture(
            JourneyEventNames.MILESTONE,
            mapOf("journey_id" to "journey-1", "epoch" to 0, "milestone_id" to "converted"),
            distinctId = "owner-customer",
        )

        assertEquals(listOf("/batch", "/event"), transport.requests.map { it.url.path })
        val eventRequest = transport.requests.single { it.url.path == "/event" }
        val body = Json.parseToJsonElement(eventRequest.body.decodeToString()).jsonObject
        assertEquals(JourneyEventNames.MILESTONE, body.getValue("event").jsonPrimitive.content)
        assertEquals("owner-customer", body.getValue("distinct_id").jsonPrimitive.content)
        assertEquals("ok", captured?.response?.getValue("status")?.jsonPrimitive?.content)
        assertEquals(listOf(captured?.event?.id), applied)
        assertTrue(store.pendingBatch(10).isEmpty())
        delivery.close()
    }

    @Test
    fun recoveryPreservesOrderWhileKeepingDecisionEventsOutOfBatch() = runBlocking {
        val store = store()
        val eventLog = log(store)
        val transport = RecordingTransport()
        val api = NuxieApi("pk_test", NuxieEnvironment.DEVELOPMENT, transport)
        val responses = mutableListOf<String>()
        val worker = EventDeliveryWorker(
            store = store,
            eventLog = eventLog,
            api = api,
            scope = scope,
            onDecisionResponse = { event, _ -> responses += event.id },
        )
        store.insertPending(StoredEvent("ordinary-before", "ordinary", distinctId = "owner-customer"))
        store.insertPending(
            StoredEvent(
                "decision",
                JourneyEventNames.MILESTONE,
                distinctId = "owner-customer",
            ),
        )
        store.insertPending(StoredEvent("ordinary-after", "ordinary", distinctId = "owner-customer"))

        assertTrue(worker.flushAll())

        assertEquals(listOf("/batch", "/event", "/batch"), transport.requests.map { it.url.path })
        val batchIds = transport.requests
            .filter { it.url.path == "/batch" }
            .map { request ->
                val decoded = GZIPInputStream(request.body.inputStream()).reader().readText()
                Json.parseToJsonElement(decoded).jsonObject
                    .getValue("batch").jsonArray
                    .map { item -> (item as JsonObject).getValue("idempotency_key").jsonPrimitive.content }
            }
        assertEquals(listOf(listOf("ordinary-before"), listOf("ordinary-after")), batchIds)
        assertEquals(listOf("decision"), responses)
        assertTrue(store.pendingBatch(10).isEmpty())
        worker.close()
    }

    @Test
    fun directCaptureKeepsDecisionPendingWhenApplyingItsResponseFails() = runBlocking {
        val store = store()
        val eventLog = log(store)
        val transport = RecordingTransport()
        val api = NuxieApi("pk_test", NuxieEnvironment.DEVELOPMENT, transport)
        var responseApplications = 0
        val worker = EventDeliveryWorker(
            store = store,
            eventLog = eventLog,
            api = api,
            scope = scope,
            onDecisionResponse = { _, _ ->
                responseApplications += 1
                if (responseApplications == 1) error("response application failed")
            },
        )
        val captured = worker.capture(
            JourneyEventNames.MILESTONE,
            mapOf("journey_id" to "journey-1", "epoch" to 0, "milestone_id" to "converted"),
            distinctId = "owner-customer",
        )

        assertEquals(listOf("/event"), transport.requests.map { it.url.path })
        assertEquals(null, captured?.response)
        assertEquals(listOf(captured?.event?.id), store.pendingBatch(10).map { it.id })

        assertTrue(worker.flushAll())
        val eventIds = transport.requests.map { request ->
            Json.parseToJsonElement(request.body.decodeToString()).jsonObject
                .getValue("idempotency_key").jsonPrimitive.content
        }
        assertEquals(listOf(captured?.event?.id, captured?.event?.id), eventIds)
        assertEquals(2, responseApplications)
        assertTrue(store.pendingBatch(10).isEmpty())
        worker.close()
    }

    @Test
    fun permanentEventRejectionDoesNotReportAcceptanceOrPoisonRecovery() = runBlocking {
        val store = store()
        val eventLog = log(store)
        val transport = RejectingEventTransport(statusCode = 409)
        val api = NuxieApi("pk_test", NuxieEnvironment.DEVELOPMENT, transport)
        var rejectionObservedWhilePending = false
        val worker = EventDeliveryWorker(
            store = store,
            eventLog = eventLog,
            api = api,
            scope = scope,
            onDecisionRejected = { event, failure ->
                assertEquals(409, (failure as NuxieApi.RequestRejectedException).statusCode)
                assertEquals(listOf(event.id), store.pendingBatch(10).map { it.id })
                rejectionObservedWhilePending = true
            },
        )

        val captured = worker.capture(
            JourneyEventNames.ENROLLED,
            mapOf("journey_id" to "journey-1", "epoch" to 0),
            distinctId = "owner-customer",
        )

        assertEquals(null, captured?.response)
        assertTrue(rejectionObservedWhilePending)
        assertTrue(store.pendingBatch(10).isEmpty())
        assertEquals(1, transport.requests.size)
        assertTrue(worker.flushAll())
        assertEquals(1, transport.requests.size)
        worker.close()
    }

    @Test
    fun retryableEventRejectionRetainsAndReplaysTheSameIdentity() = runBlocking {
        val store = store()
        val eventLog = log(store)
        val transport = RejectingEventTransport(statusCode = 429)
        val api = NuxieApi("pk_test", NuxieEnvironment.DEVELOPMENT, transport)
        val worker = EventDeliveryWorker(
            store = store,
            eventLog = eventLog,
            api = api,
            scope = scope,
        )

        val captured = worker.capture(
            JourneyEventNames.ENROLLED,
            mapOf("journey_id" to "journey-1", "epoch" to 0),
            distinctId = "owner-customer",
        )
        val eventId = requireNotNull(captured?.event?.id)

        assertEquals(listOf(eventId), store.pendingBatch(10).map { it.id })
        transport.statusCode = 200
        assertTrue(worker.flushAll())
        val sentIds = transport.requests.map { request ->
            Json.parseToJsonElement(request.body.decodeToString()).jsonObject
                .getValue("idempotency_key").jsonPrimitive.content
        }
        assertEquals(listOf(eventId, eventId), sentIds)
        assertTrue(store.pendingBatch(10).isEmpty())
        worker.close()
    }
}
