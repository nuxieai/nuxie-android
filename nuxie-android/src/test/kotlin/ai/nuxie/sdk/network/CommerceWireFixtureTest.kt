package ai.nuxie.sdk.network

import ai.nuxie.sdk.LogLevel
import ai.nuxie.sdk.NuxieEnvironment
import ai.nuxie.sdk.commerce.ActivePurchasesResult
import ai.nuxie.sdk.commerce.CheckoutRequest
import ai.nuxie.sdk.commerce.InMemoryPurchaseEvidenceStore
import ai.nuxie.sdk.commerce.NuxieApiPurchaseSynchronizer
import ai.nuxie.sdk.commerce.PlayBillingGateway
import ai.nuxie.sdk.commerce.PurchaseEvidence
import ai.nuxie.sdk.commerce.PurchaseHandlingMode
import ai.nuxie.sdk.commerce.PurchaseService
import ai.nuxie.sdk.commerce.PurchaseSettings
import ai.nuxie.sdk.commerce.PurchaseSynchronizer
import ai.nuxie.sdk.commerce.PurchaseSyncOutcome
import ai.nuxie.sdk.commerce.StoredLocalPurchaseGrant
import ai.nuxie.sdk.commerce.StoredPurchaseState
import ai.nuxie.sdk.core.NuxieCore
import ai.nuxie.sdk.features.FeatureType
import ai.nuxie.sdk.fixtures.FixtureRunner
import android.app.Activity
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingResult
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
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
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeFalse
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class CommerceWireFixtureTest {
    @Test
    fun committedRequestFixtureSetIsComplete() {
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
    }

    @Test
    fun committedAndroidEncoderRequestsMatchTheSdkWireByteForByte() {
        CommerceWireFixtures.requests()
            .filterNot { it.name == APP_STORE_SERVER_CONTRACT_CASE }
            .forEach(::assertCommittedRequest)
    }

    /**
     * Server-contract coverage for the iOS CodingKeys canon used by parent
     * worker replay; this is deliberately not Android encoder coverage.
     */
    @Test
    fun entitledAppStoreUntouchedPinsIosCodingKeysForParentWorkerReplayNotAndroidEncoding() {
        val fixture = CommerceWireFixtures.requests()
            .single { it.name == APP_STORE_SERVER_CONTRACT_CASE }

        assertEquals("/entitled", fixture.endpoint)
        val body = Json.parseToJsonElement(fixture.bodyText).jsonObject
        assertEquals("fixture.header.payload.signature", body.getValue("purchase").jsonObject
            .getValue("transaction_jwt").jsonPrimitive.content)
        assertEquals("fixture-entitled-appstore-event", body.getValue("purchase").jsonObject
            .getValue("event_id").jsonPrimitive.content)
        assertCommittedRequest(fixture)
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
        assumeFalse(
            "Parent-worker commerce response fixtures are pending while responses/PENDING exists.",
            CommerceWireResponses.isPending(),
        )
        val responseFiles = CommerceWireResponses.files()
        assertEquals(
            "Every commerce request needs TypeScript and Rust worker response fixtures.",
            CommerceWireResponses.requiredFileNames(),
            responseFiles.mapTo(sortedSetOf()) { it.fileName.toString() },
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

    @Test
    fun responseFixtureContentMustMatchItsFileName() {
        assertResponseFixtureFileRejected(
            name = "purchase-subscription-full.rs",
            request = "purchase-one-time",
            lane = "ts",
            expectedMessage = "must carry its <case>.<lane> name",
        )
        assertResponseFixtureFileRejected(
            name = "purchase-one-time.ts",
            request = "purchase-subscription-full",
            lane = "ts",
            expectedMessage = "must carry its case name",
        )
        assertResponseFixtureFileRejected(
            name = "purchase-one-time.ts",
            request = "purchase-one-time",
            lane = "rs",
            expectedMessage = "must carry its lane",
        )
    }

    @Test
    fun jsonErrorResponseRequiresMatchingParsedBody() {
        val fixture = CommerceWireResponses.ResponseFixture(
            name = "purchase-one-time.ts",
            lane = "ts",
            request = "purchase-one-time",
            endpoint = "/purchase",
            statusCode = 422,
            body = null,
            bodyText = """{"error":"invalid purchase"}""",
        )

        val failure = assertThrows(AssertionError::class.java) {
            CommerceWireResponses.assertParses(fixture)
        }
        assertTrue(failure.message.orEmpty().contains("must contain body equal to its parsed bodyText"))
    }

    @Test
    fun nonJsonErrorResponseRejectsParsedBody() {
        val fixture = CommerceWireResponses.ResponseFixture(
            name = "purchase-one-time.ts",
            lane = "ts",
            request = "purchase-one-time",
            endpoint = "/purchase",
            statusCode = 502,
            body = buildJsonObject { put("error", "upstream failure") },
            bodyText = "upstream failure",
        )

        val failure = assertThrows(AssertionError::class.java) {
            CommerceWireResponses.assertParses(fixture)
        }
        assertTrue(failure.message.orEmpty().contains("must omit body when bodyText is not JSON"))
    }

    private fun assertResponseFixtureFileRejected(
        name: String,
        request: String,
        lane: String,
        expectedMessage: String,
    ) {
        val directory = Files.createTempDirectory("commerce-wire-response")
        val path = directory.resolve("purchase-one-time.ts.json")
        val bodyText = """{"error":"invalid purchase"}"""
        val fixtureText = buildJsonObject {
            put("name", name)
            put("lane", lane)
            put("request", request)
            put("endpoint", "/purchase")
            put("statusCode", 422)
            put("body", Json.parseToJsonElement(bodyText))
            put("bodyText", bodyText)
        }.toString()
        Files.write(path, fixtureText.toByteArray(StandardCharsets.UTF_8))

        try {
            val failure = assertThrows(AssertionError::class.java) {
                CommerceWireResponses.assertParses(path)
            }
            assertTrue(failure.message.orEmpty().contains(expectedMessage))
        } finally {
            Files.deleteIfExists(path)
            Files.deleteIfExists(directory)
        }
    }

    private fun response(
        name: String,
        request: String,
        endpoint: String,
        statusCode: Int,
        bodyText: String,
    ): CommerceWireResponses.ResponseFixture = CommerceWireResponses.ResponseFixture(
        name = name,
        lane = "scaffold",
        request = request,
        endpoint = endpoint,
        statusCode = statusCode,
        body = runCatching { Json.parseToJsonElement(bodyText) }.getOrNull(),
        bodyText = bodyText,
    )

    private fun assertCommittedRequest(fixture: CommerceWireFixtures.RequestFixture) {
        val committed = String(
            Files.readAllBytes(
                CommerceWireFixtures.requestDirectory().resolve("${fixture.name}.json"),
            ),
            StandardCharsets.UTF_8,
        )
        assertEquals(
            "Regenerate commerce wire fixtures after an intentional wire change.",
            fixture.fileText(),
            committed,
        )

        val wrapper = Json.parseToJsonElement(committed).jsonObject
        assertEquals(fixture.name, wrapper.getValue("name").asString())
        assertEquals(fixture.endpoint, wrapper.getValue("endpoint").asString())
        assertEquals(fixture.bodyText, wrapper.getValue("bodyText").asString())
        assertEquals(Json.parseToJsonElement(fixture.bodyText), wrapper.getValue("body"))
    }

    private companion object {
        const val APP_STORE_SERVER_CONTRACT_CASE = "entitled-appstore-untouched"
    }
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

    fun requests(): List<RequestFixture> = capturedRequests

    private val capturedRequests: List<RequestFixture> by lazy {
        val atomicReplay = captureAtomicReplay()
        (listOf(
            capturePurchase(
                name = "purchase-subscription-full",
                report = NuxieApi.PlayPurchaseReport(
                    packageName = "ai.nuxie.fixture",
                    productId = "fixture-subscription",
                    purchaseToken = "fixture-subscription-token",
                    basePlanId = "annual",
                    offerId = "introductory",
                    obfuscatedAccountId = sha256(SUBSCRIPTION_CUSTOMER_ID),
                    distinctId = SUBSCRIPTION_CUSTOMER_ID,
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
                    obfuscatedAccountId = sha256(ONE_TIME_CUSTOMER_ID),
                    distinctId = ONE_TIME_CUSTOMER_ID,
                ),
            ),
            capturePurchase(
                name = "purchase-invalid-token",
                report = NuxieApi.PlayPurchaseReport(
                    packageName = "ai.nuxie.fixture",
                    productId = "fixture-invalid-product",
                    purchaseToken = "fixture-invalid-token",
                    basePlanId = null,
                    offerId = null,
                    obfuscatedAccountId = null,
                    distinctId = "fixture-customer-invalid-token",
                ),
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
            appStoreServerContractFixture(),
        ) + atomicReplay).sortedBy { it.name }
    }

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

    /** Server-contract vector; this body is not produced by an Android encoder. */
    private fun appStoreServerContractFixture(): RequestFixture {
        val body = buildJsonObject {
            put("apiKey", API_KEY)
            put("customerId", "fixture-customer-appstore")
            put("featureId", "fixture-credits-appstore")
            put("requiredBalance", 2.5)
            put("eventData", buildJsonObject {
                put("value", 2.5)
                put("properties", buildJsonObject {
                    put("source", "commerce-wire-fixture")
                })
            })
            put("entityId", "fixture-workspace")
            put("purchase", buildJsonObject {
                put("transaction_jwt", "fixture.header.payload.signature")
                put("event_id", "fixture-entitled-appstore-event")
            })
        }.toString()
        return RequestFixture("entitled-appstore-untouched", "/entitled", body)
    }

    private fun captureAtomicReplay(): List<RequestFixture> {
        val transport = ReplayCapturingTransport()
        val core = NuxieCore(
            context = RuntimeEnvironment.getApplication(),
            apiKey = API_KEY,
            environment = NuxieEnvironment.DEVELOPMENT,
            logLevel = LogLevel.NONE,
            beforeSend = null,
            overrides = NuxieCore.Overrides(transport = transport, registerLifecycle = false),
        ).also { it.identity.setDistinctId(FULL_CUSTOMER_ID) }
        val store = InMemoryPurchaseEvidenceStore().also { evidenceStore ->
            evidenceStore.upsert(
                PurchaseEvidence(
                    purchaseToken = "fixture-entitled-full-token",
                    authorityScope = FULL_PURCHASE_SCOPE,
                    packageName = "ai.nuxie.fixture",
                    storeProductIds = listOf("fixture-credit-pack-full"),
                    nuxieProductId = "fixture-credit-pack",
                    basePlanId = "annual",
                    offerId = "introductory",
                    purchaseState = StoredPurchaseState.PURCHASED,
                    obfuscatedAccountId = sha256(FULL_CUSTOMER_ID),
                    syncAttributionDistinctId = FULL_CUSTOMER_ID,
                    ownerDistinctId = FULL_CUSTOMER_ID,
                    acknowledged = false,
                    firstSeenMillis = 1L,
                    localFeatureGrants = listOf(
                        StoredLocalPurchaseGrant(FULL_FEATURE_ID, FeatureType.CREDIT_SYSTEM.name, false),
                    ),
                    catalogResolved = true,
                    nuxieManaged = true,
                ),
            )
        }
        val service = PurchaseService(
            billing = SuccessfulBilling,
            evidenceStore = store,
            synchronizer = PurchaseSynchronizer {
                PurchaseSyncOutcome.Rejected(permanent = false)
            },
            features = core.features,
            distinctId = core.identity::distinctId,
            emit = { _, _ -> },
            settings = PurchaseSettings(null, PurchaseHandlingMode.NUXIE_MANAGED),
            scope = core.scope,
            nowMillis = { 1L },
            api = core.api,
            purchaseStorageScope = FULL_PURCHASE_SCOPE,
            capturePurchaseSynced = { _, _, _, _ -> true },
        )

        try {
            runBlocking {
                val firstAttempt = runCatching {
                    service.useFeatureWithPendingPurchase(
                        distinctId = FULL_CUSTOMER_ID,
                        featureId = FULL_FEATURE_ID,
                        amount = 2.5,
                        entityId = "fixture-workspace",
                        metadata = FULL_METADATA,
                    )
                }
                assertTrue("The first atomic Feature-use attempt must fail.", firstAttempt.isFailure)
                assertNotNull(
                    service.useFeatureWithPendingPurchase(
                        distinctId = FULL_CUSTOMER_ID,
                        featureId = FULL_FEATURE_ID,
                        amount = 2.5,
                        entityId = "fixture-workspace",
                        metadata = FULL_METADATA,
                    ),
                )
            }
        } finally {
            core.stop()
        }

        val requests = transport.entitledRequests()
        assertEquals("The real replay path must issue exactly two /entitled requests.", 2, requests.size)
        assertArrayEquals(
            "The failed request and retry must be byte-identical.",
            requests[0],
            requests[1],
        )
        return listOf(
            RequestFixture("entitled-atomic-use-full", "/entitled", requests[0].decodeToString()),
            RequestFixture("entitled-atomic-replay", "/entitled", requests[1].decodeToString()),
        )
    }

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

    private class ReplayCapturingTransport : HttpTransport {
        private val requestBodies = mutableListOf<ByteArray>()

        override fun execute(request: HttpTransport.Request): HttpTransport.Response {
            if (request.url.path != "/entitled") {
                return HttpTransport.Response(200, """{"segments":[]}""".encodeToByteArray())
            }
            requestBodies += request.body.copyOf()
            if (requestBodies.size == 1) return HttpTransport.Response(503, ByteArray(0))
            return HttpTransport.Response(200, FULL_ENTITLED_RESPONSE.encodeToByteArray())
        }

        fun entitledRequests(): List<ByteArray> = requestBodies.toList()
    }

    private object SuccessfulBilling : PlayBillingGateway {
        override suspend fun launch(activity: Activity, request: CheckoutRequest): BillingResult = success()
        override suspend fun queryActive(productType: String): ActivePurchasesResult =
            ActivePurchasesResult.Success(emptyList())
        override suspend fun acknowledge(purchaseToken: String): BillingResult = success()
        override suspend fun consume(purchaseToken: String): BillingResult = success()

        private fun success(): BillingResult = BillingResult.newBuilder()
            .setResponseCode(BillingClient.BillingResponseCode.OK)
            .build()
    }

    private const val PURCHASE_RESPONSE =
        """{"success":true,"customer_id":"fixture-customer","features":[]}"""
    private const val ENTITLED_RESPONSE =
        """{"customerId":"fixture-customer","featureId":"fixture-feature","code":"allowed","allowed":true,"unlimited":false,"balance":8.0,"type":"metered"}"""
    private const val SUBSCRIPTION_CUSTOMER_ID = "fixture-customer-subscription"
    private const val ONE_TIME_CUSTOMER_ID = "fixture-customer-one-time"
    private const val FULL_CUSTOMER_ID = "fixture-customer-full"
    private const val FULL_FEATURE_ID = "fixture-credits-full"
    private const val FULL_PURCHASE_SCOPE = "fixture-commerce-wire-scope"
    private val FULL_METADATA = mapOf(
        "attempt" to 2,
        "source" to "commerce-wire-fixture",
        "verified" to true,
    )
    private const val FULL_ENTITLED_RESPONSE =
        """{"customerId":"fixture-customer-full","featureId":"fixture-credits-full","code":"allowed","allowed":true,"unlimited":false,"balance":8.0,"type":"creditSystem"}"""

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(StandardCharsets.UTF_8))
        .joinToString("") { byte -> "%02x".format(byte) }
}

private object CommerceWireResponses {
    data class ResponseFixture(
        val name: String,
        val lane: String,
        val request: String,
        val endpoint: String,
        val statusCode: Int,
        val body: JsonElement?,
        val bodyText: String,
    )

    private fun responseDirectory(): Path =
        FixtureRunner.fixturesRoot().toPath().resolve("commerce-wire/responses")

    fun isPending(): Boolean = Files.exists(responseDirectory().resolve("PENDING"))

    fun requiredFileNames(): Set<String> = CommerceWireFixtures.requests()
        .flatMap { fixture -> listOf("${fixture.name}.ts.json", "${fixture.name}.rs.json") }
        .toSortedSet()

    fun files(): List<Path> {
        val directory = responseDirectory()
        if (!Files.isDirectory(directory)) return emptyList()
        val files = Files.walk(directory).use { paths ->
            paths
                .filter { Files.isRegularFile(it) && it.fileName.toString().endsWith(".json") }
                .sorted()
                .toList()
        }
        // Response fixtures live flat in responses/; a nested or duplicate
        // basename would let a stale copy satisfy the completeness matrix.
        files.forEach { file ->
            check(file.parent == directory) {
                "Response fixture is not directly in responses/: $file"
            }
        }
        return files
    }

    fun assertParses(path: Path) {
        val text = String(Files.readAllBytes(path), StandardCharsets.UTF_8)
        val wrapper = Json.parseToJsonElement(text).jsonObject
        val fixture = ResponseFixture(
            name = wrapper.requiredString("name"),
            lane = wrapper.requiredString("lane"),
            request = wrapper.requiredString("request"),
            endpoint = wrapper.requiredString("endpoint"),
            statusCode = wrapper.getValue("statusCode").jsonPrimitive.int,
            body = wrapper["body"],
            bodyText = wrapper.requiredString("bodyText"),
        )
        try {
            assertMatchesFileName(path, fixture)
            assertParses(fixture)
        } catch (failure: AssertionError) {
            throw AssertionError(
                "Commerce response fixture '${path.fileName}' failed: ${failure.message}",
                failure,
            )
        }
    }

    fun assertParses(fixture: ResponseFixture) {
        assertTrue(
            "${fixture.name} must reference a committed request fixture.",
            CommerceWireFixtures.requests().any {
                it.name == fixture.request && it.endpoint == fixture.endpoint
            },
        )
        val parsedBody = runCatching { Json.parseToJsonElement(fixture.bodyText) }.getOrNull()
        if (parsedBody != null) {
            assertEquals(
                "${fixture.name} must contain body equal to its parsed bodyText.",
                parsedBody,
                fixture.body,
            )
        } else {
            assertTrue(
                "${fixture.name} must omit body when bodyText is not JSON.",
                fixture.body == null || fixture.body is JsonNull,
            )
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

    private fun assertMatchesFileName(path: Path, fixture: ResponseFixture) {
        val fileName = path.fileName.toString()
        val fixtureName = fileName.removeSuffix(".json")
        val caseName = fixtureName.substringBeforeLast('.')
        val lane = fixtureName.substringAfterLast('.')
        assertEquals("$fileName must carry its <case>.<lane> name.", fixtureName, fixture.name)
        assertEquals("$fileName must carry its case name.", caseName, fixture.request)
        assertEquals("$fileName must carry its lane.", lane, fixture.lane)
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
