package ai.nuxie.sdk.features

import ai.nuxie.sdk.LogLevel
import ai.nuxie.sdk.Nuxie
import ai.nuxie.sdk.NuxieConfiguration
import ai.nuxie.sdk.NuxieEnvironment
import ai.nuxie.sdk.core.NuxieCore
import ai.nuxie.sdk.fixtures.FixtureRunner
import ai.nuxie.sdk.network.HttpTransport
import ai.nuxie.sdk.testsupport.FakeTransport
import ai.nuxie.sdk.testsupport.canonicalJourneyProfileText
import ai.nuxie.sdk.testsupport.canonicalJourneyProfileResponseBody
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.double
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
class FeatureUsageEncodingConformanceTest {
    @After
    fun tearDown() {
        Nuxie.resetForTesting()
        Nuxie.overridesForTesting = null
    }

    @Test
    fun everyFeatureUsageAndAccessVectorEncodesToTheExpectedWireValue() {
        val coveredPolicies = mutableSetOf<String>()
        val coveredFeatureTypes = mutableSetOf<String>()
        var preservesFractionalAmounts = false
        var preservesUnlimitedAccess = false
        var preservesPresentUsageWithNullBounds = false

        FixtureRunner.run(
            relativePath = "encodings/feature-usage.json",
            expectedSuite = "encodings/feature-usage",
        ) { vector ->
            val actual = when (val kind = vector.body.string("kind")) {
                "featureUsage" -> {
                    val result = featureUsageResult(vector.body.objectValue("result"))
                    preservesFractionalAmounts = preservesFractionalAmounts ||
                        result.amountUsed.isFractional() ||
                        result.usage?.let {
                            it.current.isFractional() ||
                                it.limit?.isFractional() == true ||
                                it.remaining?.isFractional() == true
                        } == true
                    preservesPresentUsageWithNullBounds =
                        preservesPresentUsageWithNullBounds ||
                        result.usage?.let { it.limit == null && it.remaining == null } == true
                    result.authoritativeAccess?.let {
                        coveredFeatureTypes += wireFeatureType(it.type)
                    }
                    FeatureWireEncoder.encode(result)
                }
                "featureAccess" -> {
                    val policyName = vector.body.string("policy")
                    val policy = parsePolicy(policyName)
                    assertEquals(
                        "[${vector.name}] policy",
                        policyName,
                        FeatureWireEncoder.wireValue(policy),
                    )
                    coveredPolicies += policyName

                    val access = featureAccessThroughPublicFacade(
                        vectorName = vector.name,
                        expected = vector.body.objectValue("access"),
                        policy = policy,
                    )
                    preservesFractionalAmounts = preservesFractionalAmounts ||
                        access.balance?.isFractional() == true
                    preservesUnlimitedAccess = preservesUnlimitedAccess || access.unlimited
                    coveredFeatureTypes += wireFeatureType(access.type)
                    FeatureWireEncoder.encode(access)
                }
                else -> error("Unknown vector kind: $kind")
            }

            assertEquals("[${vector.name}]", vector.body.objectValue("expect"), actual)
        }

        assertEquals(setOf("cacheFirst", "remote"), coveredPolicies)
        assertEquals(setOf("boolean", "metered", "creditSystem"), coveredFeatureTypes)
        assertTrue("fixture must exercise non-integral Double values", preservesFractionalAmounts)
        assertTrue("fixture must exercise unlimited=true", preservesUnlimitedAccess)
        assertTrue(
            "fixture must exercise present usage with null limit and remaining",
            preservesPresentUsageWithNullBounds,
        )
    }

    private fun featureUsageResult(value: JsonObject): FeatureUsageResult = FeatureUsageResult(
        success = value.boolean("success"),
        featureId = value.string("featureId"),
        amountUsed = value.double("amountUsed"),
        message = value.optionalString("message"),
        usage = value.optionalObject("usage")?.let {
            FeatureUsageResult.UsageInfo(
                current = it.double("current"),
                limit = it.optionalDouble("limit"),
                remaining = it.optionalDouble("remaining"),
            )
        },
        authoritativeAccess = value.optionalObject("authoritativeAccess")?.let(::featureAccess),
    )

    private fun featureAccess(value: JsonObject): FeatureAccess = FeatureAccess(
        allowed = value.boolean("allowed"),
        unlimited = value.boolean("unlimited"),
        balance = value.optionalDouble("balance"),
        type = parseFeatureType(value.string("type")),
    )

    private fun featureAccessThroughPublicFacade(
        vectorName: String,
        expected: JsonObject,
        policy: FeatureCheckPolicy,
    ): FeatureAccess = runBlocking {
        Nuxie.resetForTesting()
        val featureId = "fixture-${vectorName.hashCode()}"
        val transport = FakeTransport().apply {
            respond = { request ->
                when (request.url.path) {
                    "/profile" -> canonicalJourneyProfileResponseBody(
                        profileResponse(featureId, expected, policy),
                    )
                    "/entitled" -> HttpTransport.Response(
                        200,
                        remoteResponse(
                            customerId = requireNotNull(Nuxie.core).identity.distinctId(),
                            featureId = featureId,
                            access = expected,
                        ).encodeToByteArray(),
                    )
                    else -> error("[$vectorName] unexpected request: ${request.url.path}")
                }
            }
        }
        Nuxie.overridesForTesting = NuxieCore.Overrides(
            transport = transport,
            registerLifecycle = false,
        )
        Nuxie.setup(
            RuntimeEnvironment.getApplication(),
            NuxieConfiguration("pk_test_feature_usage_conformance_${vectorName.hashCode()}").apply {
                environment = NuxieEnvironment.DEVELOPMENT
                logLevel = LogLevel.NONE
            },
        )
        assertTrue("[$vectorName] profile refresh", requireNotNull(Nuxie.core).profile.refreshAndWait())

        val access = Nuxie.hasFeature(
            featureId = featureId,
            requiredBalance = 1.0,
            policy = policy,
        )
        if (policy == FeatureCheckPolicy.CACHE_FIRST) {
            assertTrue(
                "[$vectorName] cacheFirst must not request fresh usable access",
                transport.requests.none { it.url.path == "/entitled" },
            )
        }
        access
    }

    private fun profileResponse(
        featureId: String,
        access: JsonObject,
        policy: FeatureCheckPolicy,
    ): String {
        val features = if (policy == FeatureCheckPolicy.CACHE_FIRST) {
            """[{"id":"$featureId","type":"${access.string("type")}","unlimited":${access.boolean("unlimited")},"balance":${access.getValue("balance")}}]"""
        } else {
            "[]"
        }
        return canonicalJourneyProfileText(features)
    }

    private fun remoteResponse(
        customerId: String,
        featureId: String,
        access: JsonObject,
    ): String = """{"customerId":"$customerId","featureId":"$featureId","requiredBalance":1.0,"code":"fixture","allowed":${access.boolean("allowed")},"unlimited":${access.boolean("unlimited")},"balance":${access.getValue("balance")},"type":"${access.string("type")}"}"""

    private fun parsePolicy(value: String): FeatureCheckPolicy = when (value) {
        "cacheFirst" -> FeatureCheckPolicy.CACHE_FIRST
        "remote" -> FeatureCheckPolicy.REMOTE
        else -> error("Unknown Feature check policy: $value")
    }

    private fun parseFeatureType(value: String): FeatureType = when (value) {
        "boolean" -> FeatureType.BOOLEAN
        "metered" -> FeatureType.METERED
        "creditSystem" -> FeatureType.CREDIT_SYSTEM
        else -> error("Unknown Feature type: $value")
    }

    private fun wireFeatureType(type: FeatureType): String = when (type) {
        FeatureType.BOOLEAN -> "boolean"
        FeatureType.METERED -> "metered"
        FeatureType.CREDIT_SYSTEM -> "creditSystem"
    }

    private fun Double.isFractional(): Boolean = rem(1.0) != 0.0

    private fun JsonObject.boolean(key: String): Boolean = getValue(key).jsonPrimitive.boolean

    private fun JsonObject.double(key: String): Double = getValue(key).jsonPrimitive.double

    private fun JsonObject.string(key: String): String = getValue(key).jsonPrimitive.content

    private fun JsonObject.optionalString(key: String): String? =
        getValue(key).takeUnless { it is JsonNull }?.jsonPrimitive?.content

    private fun JsonObject.optionalDouble(key: String): Double? =
        getValue(key).takeUnless { it is JsonNull }?.jsonPrimitive?.double

    private fun JsonObject.objectValue(key: String): JsonObject = getValue(key).jsonObject

    private fun JsonObject.optionalObject(key: String): JsonObject? =
        getValue(key).takeUnless { it is JsonNull }?.jsonObject
}
