package ai.nuxie.sdk.network

import ai.nuxie.sdk.NuxieEnvironment
import ai.nuxie.sdk.commerce.NuxieApiPurchaseSynchronizer
import ai.nuxie.sdk.commerce.PurchaseEvidence
import ai.nuxie.sdk.commerce.PurchaseSyncOutcome
import ai.nuxie.sdk.commerce.StoredPurchaseState
import ai.nuxie.sdk.features.FeatureType
import ai.nuxie.sdk.fixtures.FixtureRunner
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

class CommerceWireFixtureTest {
    @Test
    fun committedRequestsMatchTheSdkWireByteForByte() {
        val expected = CommerceWireFixtures.requests()
        val requestDirectory = CommerceWireFixtures.requestDirectory()
        val committedFiles = Files.list(requestDirectory).use { paths ->
            paths
                .filter { Files.isRegularFile(it) && it.fileName.toString().endsWith(".json") }
                .sorted()
                .toList()
        }

        assertEquals(
            expected.map { "${it.name}.json" },
            committedFiles.map { it.fileName.toString() },
        )
        expected.forEach { fixture ->
            val committed = String(
                Files.readAllBytes(requestDirectory.resolve("${fixture.name}.json")),
                StandardCharsets.UTF_8,
            )
            assertEquals(
                "Regenerate commerce wire fixtures after an intentional encoder change.",
                fixture.fileText(),
                committed,
            )

            val wrapper = Json.parseToJsonElement(committed).jsonObject
            assertEquals(fixture.name, wrapper.getValue("name").asString())
            assertEquals(fixture.endpoint, wrapper.getValue("endpoint").asString())
            assertEquals(fixture.bodyText, wrapper.getValue("bodyText").asString())
            assertEquals(Json.parseToJsonElement(fixture.bodyText), wrapper.getValue("body"))
        }

        val full = expected.single { it.name == "entitled-atomic-use-full" }
        val replay = expected.single { it.name == "entitled-atomic-replay" }
        assertEquals(full.bodyText, replay.bodyText)
    }

    @Test
    fun generateCommerceWireFixtures() {
        assumeTrue(
            "Set NUXIE_GENERATE_COMMERCE_WIRE_FIXTURES=1 to regenerate committed request fixtures.",
            System.getenv("NUXIE_GENERATE_COMMERCE_WIRE_FIXTURES") == "1",
        )
        CommerceWireFixtures.writeRequests()
    }

    @Test
    fun everyCommittedWorkerResponseParsesThroughTheSdkResponsePath() {
        val responseFiles = CommerceWireResponses.files()
        assumeTrue(
            "Parent-worker commerce response fixtures have not been committed yet.",
            responseFiles.isNotEmpty(),
        )
        responseFiles.forEach(CommerceWireResponses::assertParses)
    }

    @Test
    fun responseScaffoldingCoversSuccessAndEndpointErrorMapping() {
        CommerceWireResponses.assertParses(
            response(
                name = "scaffold-purchase-success",
                request = "purchase-one-time",
                endpoint = "/purchase",
                statusCode = 200,
                bodyText =
                    """{"success":true,"customer_id":"fixture-response-customer","features":[]}""",
            ),
        )
        CommerceWireResponses.assertParses(
            response(
                name = "scaffold-entitled-success",
                request = "entitled-atomic-use-full",
                endpoint = "/entitled",
                statusCode = 200,
                bodyText =
                    """{"customerId":"fixture-response-customer","featureId":"fixture-response-feature","code":"allowed","allowed":true,"unlimited":false,"balance":6.5,"type":"creditSystem"}""",
            ),
        )
        CommerceWireResponses.assertParses(
            response(
                name = "scaffold-purchase-error",
                request = "purchase-subscription-full",
                endpoint = "/purchase",
                statusCode = 422,
                bodyText = """{"error":"invalid purchase"}""",
            ),
        )
        CommerceWireResponses.assertParses(
            response(
                name = "scaffold-entitled-error",
                request = "entitled-atomic-use-minimal",
                endpoint = "/entitled",
                statusCode = 503,
                bodyText = """{"error":"temporarily unavailable"}""",
            ),
        )
        CommerceWireResponses.assertParses(
            response(
                name = "scaffold-purchase-empty-retryable-error",
                request = "purchase-token-first",
                endpoint = "/purchase",
                statusCode = 429,
                bodyText = "",
            ),
        )
    }

    private fun response(
        name: String,
        request: String,
        endpoint: String,
        statusCode: Int,
        bodyText: String,
    ): CommerceWireResponses.ResponseFixture = CommerceWireResponses.ResponseFixture(
        name = name,
        request = request,
        endpoint = endpoint,
        statusCode = statusCode,
        body = runCatching { Json.parseToJsonElement(bodyText) }.getOrNull(),
        bodyText = bodyText,
    )
}

private object CommerceWireFixtures {
    private const val API_KEY = "pk_fixture"

    data class RequestFixture(
        val name: String,
        val endpoint: String,
        val bodyText: String,
    ) {
        fun fileText(): String = buildJsonObject {
            put("name", name)
            put("endpoint", endpoint)
            put("body", Json.parseToJsonElement(bodyText))
            put("bodyText", bodyText)
        }.toString() + "\n"
    }

    fun requestDirectory(): Path =
        FixtureRunner.fixturesRoot().toPath().resolve("commerce-wire/requests")

    fun requests(): List<RequestFixture> = listOf(
        capturePurchase(
            name = "purchase-subscription-full",
            report = NuxieApi.PlayPurchaseReport(
                packageName = "ai.nuxie.fixture",
                productId = "fixture-subscription",
                purchaseToken = "fixture-subscription-token",
                basePlanId = "annual",
                offerId = "introductory",
                obfuscatedAccountId = "fixture-account-hash",
                distinctId = "fixture-customer-subscription",
            ),
        ),
        captureTokenFirstPurchase(),
        capturePurchase(
            name = "purchase-one-time",
            report = NuxieApi.PlayPurchaseReport(
                packageName = "ai.nuxie.fixture",
                productId = "fixture-credit-pack",
                purchaseToken = "fixture-one-time-token",
                basePlanId = null,
                offerId = null,
                obfuscatedAccountId = "fixture-one-time-account-hash",
                distinctId = "fixture-customer-one-time",
            ),
        ),
        captureEntitled(
            name = "entitled-atomic-use-full",
            report = fullFeatureUseReport(),
        ),
        captureEntitled(
            name = "entitled-atomic-use-minimal",
            report = NuxieApi.PurchaseBackedFeatureUseReport(
                customerId = "fixture-customer-minimal",
                featureId = "fixture-credits-minimal",
                requiredBalance = 1.0,
                eventData = NuxieApi.FeatureUseEventData(value = 1.0, properties = null),
                entityId = null,
                purchase = NuxieApi.PlayPurchaseUseReport(
                    packageName = "ai.nuxie.fixture",
                    productId = "fixture-credit-pack-minimal",
                    purchaseToken = "fixture-entitled-minimal-token",
                    basePlanId = null,
                    offerId = null,
                    obfuscatedAccountId = null,
                    eventId = "fixture-entitled-minimal-event",
                ),
            ),
        ),
        captureEntitled(
            name = "entitled-atomic-replay",
            report = fullFeatureUseReport(),
        ),
        appStoreCompatibilityFixture(),
    ).sortedBy { it.name }

    fun writeRequests() {
        val directory = requestDirectory()
        Files.createDirectories(directory)
        val fixtures = requests()
        val expectedNames = fixtures.mapTo(mutableSetOf()) { "${it.name}.json" }
        Files.list(directory).use { paths ->
            paths
                .filter {
                    Files.isRegularFile(it) &&
                        it.fileName.toString().endsWith(".json") &&
                        it.fileName.toString() !in expectedNames
                }
                .forEach(Files::delete)
        }
        fixtures.forEach { fixture ->
            Files.write(
                directory.resolve("${fixture.name}.json"),
                fixture.fileText().toByteArray(StandardCharsets.UTF_8),
            )
        }
    }

    private fun capturePurchase(
        name: String,
        report: NuxieApi.PlayPurchaseReport,
    ): RequestFixture {
        val transport = CapturingTransport(PURCHASE_RESPONSE)
        NuxieApi(API_KEY, NuxieEnvironment.DEVELOPMENT, transport).postPurchase(report)
        return transport.fixture(name, "/purchase")
    }

    private fun captureEntitled(
        name: String,
        report: NuxieApi.PurchaseBackedFeatureUseReport,
    ): RequestFixture {
        val transport = CapturingTransport(ENTITLED_RESPONSE)
        NuxieApi(API_KEY, NuxieEnvironment.DEVELOPMENT, transport)
            .useFeatureWithPurchase(report)
        return transport.fixture(name, "/entitled")
    }

    private fun captureTokenFirstPurchase(): RequestFixture {
        val transport = CapturingTransport(PURCHASE_RESPONSE)
        val outcome = runBlocking {
            NuxieApiPurchaseSynchronizer(
                NuxieApi(API_KEY, NuxieEnvironment.DEVELOPMENT, transport),
            ).sync(
                PurchaseEvidence(
                    purchaseToken = "fixture-token-first",
                    packageName = " ",
                    storeProductIds = listOf(""),
                    purchaseState = StoredPurchaseState.PURCHASED,
                    syncAttributionDistinctId = "fixture-customer-token-first",
                    acknowledged = false,
                    firstSeenMillis = 1L,
                ),
            )
        }
        assertTrue(outcome is PurchaseSyncOutcome.Accepted)
        return transport.fixture("purchase-token-first", "/purchase")
    }

    /**
     * Android never emits App Store evidence. This test-only compatibility
     * fixture pins the pre-existing iOS arm while the worker request becomes
     * a Play/App Store union.
     */
    private fun appStoreCompatibilityFixture(): RequestFixture {
        val body = buildJsonObject {
            put("apiKey", API_KEY)
            put("type", "appstore")
            put("transaction_jwt", "fixture.header.payload.signature")
            put("distinct_id", "fixture-customer-appstore")
        }.toString()
        return RequestFixture("entitled-appstore-untouched", "/purchase", body)
    }

    private fun fullFeatureUseReport() = NuxieApi.PurchaseBackedFeatureUseReport(
        customerId = "fixture-customer-full",
        featureId = "fixture-credits-full",
        requiredBalance = 2.5,
        eventData = NuxieApi.FeatureUseEventData(
            value = 2.5,
            properties = mapOf(
                "attempt" to 2,
                "source" to "commerce-wire-fixture",
                "verified" to true,
            ),
        ),
        entityId = "fixture-workspace",
        purchase = NuxieApi.PlayPurchaseUseReport(
            packageName = "ai.nuxie.fixture",
            productId = "fixture-credit-pack-full",
            purchaseToken = "fixture-entitled-full-token",
            basePlanId = "annual",
            offerId = "introductory",
            obfuscatedAccountId = "fixture-entitled-account-hash",
            eventId = "fixture-entitled-replay-event",
        ),
    )

    private class CapturingTransport(private val responseBody: String) : HttpTransport {
        private val requests = mutableListOf<HttpTransport.Request>()

        override fun execute(request: HttpTransport.Request): HttpTransport.Response {
            requests += request
            return HttpTransport.Response(200, responseBody.encodeToByteArray())
        }

        fun fixture(name: String, endpoint: String): RequestFixture {
            val request = requests.single()
            assertEquals(endpoint, request.url.path)
            assertEquals("application/json", request.headers["Content-Type"])
            assertTrue(request.headers.getValue("User-Agent").startsWith("Nuxie-Android-SDK/"))
            return RequestFixture(name, endpoint, request.body.decodeToString())
        }
    }

    private const val PURCHASE_RESPONSE =
        """{"success":true,"customer_id":"fixture-customer","features":[]}"""
    private const val ENTITLED_RESPONSE =
        """{"customerId":"fixture-customer","featureId":"fixture-feature","code":"allowed","allowed":true,"unlimited":false,"balance":8.0,"type":"metered"}"""
}

private object CommerceWireResponses {
    data class ResponseFixture(
        val name: String,
        val request: String,
        val endpoint: String,
        val statusCode: Int,
        val body: JsonElement?,
        val bodyText: String,
    )

    private fun responseDirectory(): Path =
        FixtureRunner.fixturesRoot().toPath().resolve("commerce-wire/responses")

    fun files(): List<Path> {
        val directory = responseDirectory()
        if (!Files.isDirectory(directory)) return emptyList()
        return Files.walk(directory).use { paths ->
            paths
                .filter { Files.isRegularFile(it) && it.fileName.toString().endsWith(".json") }
                .sorted()
                .toList()
        }
    }

    fun assertParses(path: Path) {
        val text = String(Files.readAllBytes(path), StandardCharsets.UTF_8)
        val wrapper = Json.parseToJsonElement(text).jsonObject
        val fixture = ResponseFixture(
            name = wrapper.requiredString("name"),
            request = wrapper.requiredString("request"),
            endpoint = wrapper.requiredString("endpoint"),
            statusCode = wrapper.getValue("statusCode").jsonPrimitive.int,
            body = wrapper["body"]?.takeUnless { it is JsonNull },
            bodyText = wrapper.requiredString("bodyText"),
        )
        try {
            assertParses(fixture)
        } catch (failure: AssertionError) {
            throw AssertionError("Commerce response fixture '${fixture.name}' failed: ${failure.message}", failure)
        }
    }

    fun assertParses(fixture: ResponseFixture) {
        assertTrue(
            "${fixture.name} must reference a committed request fixture.",
            CommerceWireFixtures.requests().any {
                it.name == fixture.request && it.endpoint == fixture.endpoint
            },
        )
        fixture.body?.let { body ->
            assertEquals(body, Json.parseToJsonElement(fixture.bodyText))
        }

        val transport = HttpTransport {
            HttpTransport.Response(fixture.statusCode, fixture.bodyText.encodeToByteArray())
        }
        val api = NuxieApi("pk_fixture", NuxieEnvironment.DEVELOPMENT, transport)
        if (fixture.statusCode in 200..299) {
            when (fixture.endpoint) {
                "/purchase" -> assertPurchaseResponse(fixture, api)
                "/entitled" -> assertFeatureCheckResponse(fixture, api)
                else -> throw AssertionError("Unsupported commerce response endpoint ${fixture.endpoint}")
            }
        } else {
            assertErrorMapping(fixture, api)
        }
    }

    private fun assertPurchaseResponse(fixture: ResponseFixture, api: NuxieApi) {
        val result = api.postPurchase(responsePathPurchaseReport())
        val body = fixture.successBody()
        assertEquals(body, result.body)
        assertEquals(body.getValue("success").jsonPrimitive.boolean, result.success)
        val customerId = (body["customer_id"] ?: body["customerId"])
            ?.takeUnless { it is JsonNull }
            ?.jsonPrimitive
            ?.content
        assertEquals(customerId, result.customerId)
    }

    private fun assertFeatureCheckResponse(fixture: ResponseFixture, api: NuxieApi) {
        val result = api.useFeatureWithPurchase(responsePathFeatureUseReport())
        val body = fixture.successBody()
        assertEquals(body.requiredString("customerId"), result.customerId)
        assertEquals(body.requiredString("featureId"), result.featureId)
        assertEquals(body.requiredString("code"), result.code)
        assertEquals(body.getValue("allowed").jsonPrimitive.boolean, result.allowed)
        assertEquals(body.getValue("unlimited").jsonPrimitive.boolean, result.unlimited)
        val balance = body["balance"]
            ?.takeUnless { it is JsonNull }
            ?.jsonPrimitive
            ?.double
        if (balance == null) assertNull(result.balance) else assertEquals(balance, result.balance!!, 0.0)
        assertEquals(body.requiredString("type").toFeatureType(), result.type)
    }

    private fun assertErrorMapping(fixture: ResponseFixture, api: NuxieApi) {
        when (fixture.endpoint) {
            "/purchase" -> {
                val error = assertThrows(NuxieApi.PurchaseRejectedException::class.java) {
                    api.postPurchase(responsePathPurchaseReport())
                }
                assertEquals(fixture.statusCode, error.statusCode)
                assertEquals(
                    fixture.statusCode in 400..499 && fixture.statusCode !in setOf(408, 429),
                    error.permanent,
                )
            }

            "/entitled" -> {
                val error = assertThrows(NuxieApi.RequestRejectedException::class.java) {
                    api.useFeatureWithPurchase(responsePathFeatureUseReport())
                }
                assertEquals(fixture.statusCode, error.statusCode)
            }

            else -> throw AssertionError("Unsupported commerce response endpoint ${fixture.endpoint}")
        }
    }

    private fun responsePathPurchaseReport() = NuxieApi.PlayPurchaseReport(
        packageName = "ai.nuxie.fixture",
        productId = "fixture-response-product",
        purchaseToken = "fixture-response-token",
        basePlanId = null,
        offerId = null,
        obfuscatedAccountId = null,
        distinctId = "fixture-response-customer",
    )

    private fun responsePathFeatureUseReport() = NuxieApi.PurchaseBackedFeatureUseReport(
        customerId = "fixture-response-customer",
        featureId = "fixture-response-feature",
        requiredBalance = 2.5,
        eventData = NuxieApi.FeatureUseEventData(value = 2.5, properties = null),
        entityId = null,
        purchase = NuxieApi.PlayPurchaseUseReport(
            packageName = "ai.nuxie.fixture",
            productId = "fixture-response-product",
            purchaseToken = "fixture-response-token",
            basePlanId = null,
            offerId = null,
            obfuscatedAccountId = null,
            eventId = "fixture-response-event",
        ),
    )

    private fun String.toFeatureType(): FeatureType = when (this) {
        "boolean" -> FeatureType.BOOLEAN
        "metered" -> FeatureType.METERED
        "creditSystem" -> FeatureType.CREDIT_SYSTEM
        else -> throw IOException("Unknown fixture Feature type '$this'.")
    }

    private fun ResponseFixture.successBody(): JsonObject = body as? JsonObject
        ?: throw AssertionError("Successful response fixture '$name' must contain an object body.")
}

private fun JsonElement.asString(): String =
    (this as JsonPrimitive).content

private fun JsonObject.requiredString(key: String): String =
    getValue(key).jsonPrimitive.content
