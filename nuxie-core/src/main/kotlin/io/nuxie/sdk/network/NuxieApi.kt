package io.nuxie.sdk.network

import io.nuxie.sdk.NuxieVersion
import io.nuxie.sdk.features.FeatureCheckResult
import io.nuxie.sdk.flows.RemoteFlow
import io.nuxie.sdk.network.models.ApiErrorResponse
import io.nuxie.sdk.network.models.BatchRequest
import io.nuxie.sdk.network.models.BatchResponse
import io.nuxie.sdk.network.models.EventRequest
import io.nuxie.sdk.network.models.EventResponse
import io.nuxie.sdk.network.models.FeatureCheckRequest
import io.nuxie.sdk.network.models.PlayStorePurchaseRequest
import io.nuxie.sdk.network.models.ProfileRequest
import io.nuxie.sdk.network.models.ProfileResponse
import io.nuxie.sdk.network.models.ResponseAbandonRequest
import io.nuxie.sdk.network.models.ResponseAbandonResponse
import io.nuxie.sdk.network.models.ResponseFieldRequest
import io.nuxie.sdk.network.models.ResponseSubmitRequest
import io.nuxie.sdk.network.models.ResponseSubmitResponse
import io.nuxie.sdk.network.models.ResponseWriteResponse
import io.nuxie.sdk.features.PurchaseResponse
import io.nuxie.sdk.util.Iso8601
import io.nuxie.sdk.util.UuidV7
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okio.Buffer
import okio.GzipSink
import okio.buffer
import java.io.IOException
import java.util.concurrent.TimeUnit

class NuxieApi(
  private val apiKey: String,
  baseUrl: String = "https://i.nuxie.io",
  private val useGzipCompression: Boolean = false,
  private val json: Json = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
  },
  okHttpClient: OkHttpClient? = null,
) : NuxieApiProtocol {
  private val baseHttpUrl: HttpUrl =
    baseUrl.toHttpUrlOrNull() ?: throw NuxieNetworkError.InvalidUrl(baseUrl)

  private val client: OkHttpClient = okHttpClient ?: OkHttpClient.Builder()
    .callTimeout(30, TimeUnit.SECONDS)
    .connectTimeout(30, TimeUnit.SECONDS)
    .readTimeout(60, TimeUnit.SECONDS)
    .writeTimeout(60, TimeUnit.SECONDS)
    .build()

  override suspend fun fetchProfile(distinctId: String, locale: String?): ProfileResponse {
    val req = ProfileRequest(distinctId = distinctId, locale = locale)
    val body = json.encodeToJsonElement(ProfileRequest.serializer(), req)
    return request(
      path = "/profile",
      method = "POST",
      auth = AuthMethod.API_KEY_IN_BODY,
      body = body,
    )
  }

  suspend fun fetchProfile(distinctId: String): ProfileResponse = fetchProfile(distinctId, locale = null)

  override suspend fun trackEvent(
    event: String,
    distinctId: String,
    anonDistinctId: String?,
    properties: JsonObject?,
    uuid: String,
    value: Double?,
    entityId: String?,
    timestamp: String,
  ): EventResponse {
    val req = EventRequest(
      event = event,
      distinctId = distinctId,
      anonDistinctId = anonDistinctId,
      timestamp = timestamp,
      properties = properties,
      uuid = uuid,
      value = value,
      entityId = entityId,
    )
    val body = json.encodeToJsonElement(EventRequest.serializer(), req)
    return request(
      path = "/event",
      method = "POST",
      auth = AuthMethod.API_KEY_IN_BODY,
      body = body,
    )
  }

  suspend fun trackEvent(
    event: String,
    distinctId: String,
    anonDistinctId: String? = null,
    properties: JsonObject? = null,
    value: Double? = null,
    entityId: String? = null,
  ): EventResponse {
    return trackEvent(
      event = event,
      distinctId = distinctId,
      anonDistinctId = anonDistinctId,
      properties = properties,
      uuid = UuidV7.generateString(),
      value = value,
      entityId = entityId,
      timestamp = Iso8601.now(),
    )
  }

  override suspend fun sendBatch(batch: BatchRequest): BatchResponse {
    val body = json.encodeToJsonElement(BatchRequest.serializer(), batch)
    return request(
      path = "/batch",
      method = "POST",
      auth = AuthMethod.API_KEY_IN_BODY,
      body = body,
    )
  }

  /**
   * Fetch a flow JSON payload.
   *
   * Mirrors iOS `NuxieApi.fetchFlow(...)` (returns a `RemoteFlow`).
   */
  override suspend fun fetchFlow(flowId: String): RemoteFlow {
    return request(
      path = "/flows/$flowId",
      method = "GET",
      auth = AuthMethod.API_KEY_IN_QUERY,
      body = null,
    )
  }

  override suspend fun checkFeature(
    customerId: String,
    featureId: String,
    requiredBalance: Int?,
    entityId: String?,
  ): FeatureCheckResult {
    val req = FeatureCheckRequest(
      customerId = customerId,
      featureId = featureId,
      requiredBalance = requiredBalance,
      entityId = entityId,
    )
    val body = json.encodeToJsonElement(FeatureCheckRequest.serializer(), req)
    return request(
      path = "/entitled",
      method = "POST",
      auth = AuthMethod.API_KEY_IN_BODY,
      body = body,
    )
  }

  override suspend fun syncPlayStorePurchase(request: PlayStorePurchaseRequest): PurchaseResponse {
    val body = json.encodeToJsonElement(PlayStorePurchaseRequest.serializer(), request)
    return request(
      path = "/purchase",
      method = "POST",
      auth = AuthMethod.API_KEY_IN_BODY,
      body = body,
    )
  }

  override suspend fun setResponseField(
    distinctId: String,
    journeySessionId: String,
    responseSchemaId: String,
    schemaVersion: Int?,
    key: String,
    value: JsonElement,
  ): ResponseWriteResponse {
    val req = ResponseFieldRequest(
      distinctId = distinctId,
      journeySessionId = journeySessionId,
      responseSchemaId = responseSchemaId,
      schemaVersion = schemaVersion,
      key = key,
      value = value,
    )
    val body = json.encodeToJsonElement(ResponseFieldRequest.serializer(), req)
    return request(
      path = "/response/field",
      method = "POST",
      auth = AuthMethod.API_KEY_IN_BODY,
      body = body,
    )
  }

  override suspend fun submitResponse(
    distinctId: String,
    journeySessionId: String,
    responseSchemaId: String,
    schemaVersion: Int?,
  ): ResponseSubmitResponse {
    val req = ResponseSubmitRequest(
      distinctId = distinctId,
      journeySessionId = journeySessionId,
      responseSchemaId = responseSchemaId,
      schemaVersion = schemaVersion,
    )
    val body = json.encodeToJsonElement(ResponseSubmitRequest.serializer(), req)
    return request(
      path = "/response/submit",
      method = "POST",
      auth = AuthMethod.API_KEY_IN_BODY,
      body = body,
    )
  }

  override suspend fun abandonResponses(
    distinctId: String,
    journeySessionId: String,
  ): ResponseAbandonResponse {
    val req = ResponseAbandonRequest(
      distinctId = distinctId,
      journeySessionId = journeySessionId,
    )
    val body = json.encodeToJsonElement(ResponseAbandonRequest.serializer(), req)
    return request(
      path = "/response/abandon",
      method = "POST",
      auth = AuthMethod.API_KEY_IN_BODY,
      body = body,
    )
  }

  private enum class AuthMethod {
    API_KEY_IN_BODY,
    API_KEY_IN_QUERY,
  }

  private suspend inline fun <reified T> request(
    path: String,
    method: String,
    auth: AuthMethod,
    body: JsonElement?,
  ): T = withContext(Dispatchers.IO) {
    val url = when (auth) {
      AuthMethod.API_KEY_IN_BODY -> baseHttpUrl.newBuilder().addPathSegments(path.trimStart('/')).build()
      AuthMethod.API_KEY_IN_QUERY -> baseHttpUrl.newBuilder()
        .addPathSegments(path.trimStart('/'))
        .addQueryParameter("apiKey", apiKey)
        .build()
    }

    val requestBodyBytes: ByteArray? = when {
      method == "GET" -> null
      auth == AuthMethod.API_KEY_IN_BODY -> {
        val element = injectApiKey(body)
        val raw = try {
          json.encodeToString(JsonElement.serializer(), element)
        } catch (e: Exception) {
          throw NuxieNetworkError.EncodingError(e)
        }
        raw.toByteArray(Charsets.UTF_8)
      }
      else -> null
    }

    val mediaType = "application/json".toMediaType()
    val requestBody = requestBodyBytes?.let { bytes ->
      val finalBytes = if (useGzipCompression) gzip(bytes) else bytes
      finalBytes.toRequestBody(mediaType)
    }

    val req = Request.Builder()
      .url(url)
      .method(method, requestBody)
      .header("Content-Type", "application/json")
      .header("User-Agent", "Nuxie-Android-SDK/${NuxieVersion.current}")
      .apply {
        if (useGzipCompression && requestBodyBytes != null) {
          header("Content-Encoding", "gzip")
        }
      }
      .build()

    val call = client.newCall(req)
    val response = try {
      call.await()
    } catch (e: IOException) {
      throw NuxieNetworkError.NetworkUnavailable.also { it.initCause(e) }
    }

    response.use { res ->
      val bodyString = res.body?.string() ?: ""
      if (!res.isSuccessful) {
        val parsed = try {
          if (bodyString.isNotBlank()) json.decodeFromString(ApiErrorResponse.serializer(), bodyString) else null
        } catch (_: Exception) {
          null
        }
        throw NuxieNetworkError.HttpError(res.code, parsed?.message ?: "Unknown error")
      }

      try {
        return@withContext json.decodeFromString<T>(bodyString)
      } catch (e: Exception) {
        throw NuxieNetworkError.DecodingError(e)
      }
    }
  }

  private fun injectApiKey(body: JsonElement?): JsonElement {
    if (body == null) {
      return buildJsonObject { put("apiKey", JsonPrimitive(apiKey)) }
    }
    val obj = try {
      body.jsonObject
    } catch (e: Exception) {
      throw NuxieNetworkError.EncodingError(e)
    }
    val merged = obj.toMutableMap()
    merged["apiKey"] = JsonPrimitive(apiKey)
    return JsonObject(merged)
  }

  private fun gzip(bytes: ByteArray): ByteArray {
    val buffer = Buffer()
    GzipSink(buffer).buffer().use { sink -> sink.write(bytes) }
    return buffer.readByteArray()
  }
}
