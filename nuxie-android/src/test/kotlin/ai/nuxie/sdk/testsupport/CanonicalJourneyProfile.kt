package ai.nuxie.sdk.testsupport

import ai.nuxie.sdk.network.HttpTransport
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

internal fun canonicalJourneyProfile(features: String = "[]"): ByteArray =
    canonicalJourneyProfileText(features).encodeToByteArray()

internal fun canonicalJourneyProfileResponse(
    features: String = "[]",
    etag: String = "\"test-profile\"",
): HttpTransport.Response = HttpTransport.Response(
    statusCode = 200,
    body = canonicalJourneyProfile(features),
    headers = canonicalJourneyProfileHeaders(etag),
)

internal fun canonicalJourneyProfileResponseBody(
    body: String,
    etag: String = "\"test-profile\"",
): HttpTransport.Response = HttpTransport.Response(
    statusCode = 200,
    body = body.encodeToByteArray(),
    headers = canonicalJourneyProfileHeaders(etag),
)

private fun canonicalJourneyProfileHeaders(etag: String): Map<String, String> =
    mapOf(
        "ETag" to etag,
        "Nuxie-App-Id" to "app_test",
        "Nuxie-App-Environment" to "test",
    )

internal fun canonicalJourneyProfileText(features: String = "[]"): String {
    val normalizedFeatures = Json.parseToJsonElement(features).jsonArray.map { value ->
        val feature = value.jsonObject
        JsonObject(
            mapOf(
                "balance" to JsonNull,
                "nextResetAt" to JsonNull,
                "interval" to JsonNull,
            ) + feature,
        )
    }
    return buildJsonObject {
        put("schemaVersion", JsonPrimitive("nuxie.journey-plane-profile.v1"))
        put("status", JsonPrimitive("ok"))
        put("delivery", buildJsonObject {
            put("renderBaseUrl", JsonPrimitive("https://render.example/"))
            put("assetBaseUrl", JsonPrimitive("https://assets.example/"))
        })
        put("features", JsonArray(normalizedFeatures))
        put("facts", buildJsonObject {
            put("properties", buildJsonObject {})
            put("memberships", buildJsonObject {})
            put("assignments", buildJsonObject {})
        })
        put("armedLegs", JsonArray(emptyList()))
        put("releases", JsonArray(emptyList()))
    }.toString()
}
