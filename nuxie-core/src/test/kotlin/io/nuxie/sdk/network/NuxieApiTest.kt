package io.nuxie.sdk.network

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.jsonObject
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import io.nuxie.sdk.network.models.PlayStorePurchaseRequest
import io.nuxie.sdk.purchases.PlayStoreProductType
import java.io.ByteArrayInputStream
import java.util.zip.GZIPInputStream

class NuxieApiTest {
  private val json = Json { ignoreUnknownKeys = true }

  @Test
  fun fetch_profile_includes_api_key_in_body() = runBlocking {
    val server = MockWebServer()
    server.enqueue(
      MockResponse()
        .setResponseCode(200)
        .setHeader("Content-Type", "application/json")
        .setBody("{\"campaigns\":[],\"segments\":[],\"flows\":[]}"),
    )
    server.start()
    try {
      val api = NuxieApi(
        apiKey = "test_key",
        baseUrl = server.url("/").toString().removeSuffix("/"),
      )
      val res = api.fetchProfile(distinctId = "d1", locale = "en_US")
      assertEquals(0, res.campaigns.size)

      val req = server.takeRequest()
      assertEquals("POST", req.method)
      assertEquals("/profile", req.path)

      val body = req.body.readUtf8()
      val obj = json.parseToJsonElement(body).jsonObject
      assertEquals("test_key", obj["apiKey"]?.toString()?.trim('"'))
      assertEquals("d1", obj["distinct_id"]?.toString()?.trim('"'))
      assertEquals("en_US", obj["locale"]?.toString()?.trim('"'))
    } finally {
      server.shutdown()
    }
  }

  @Test
  fun fetch_flow_puts_api_key_in_query() = runBlocking {
    val server = MockWebServer()
    server.enqueue(
      MockResponse()
        .setResponseCode(200)
        .setHeader("Content-Type", "application/json")
        .setBody(
          """
          {
            "id": "flow_1",
            "bundle": {
              "url": "https://example.com/flows/flow_1/",
              "manifest": {
                "totalFiles": 1,
                "totalSize": 10,
                "contentHash": "sha256:abc",
                "files": [
                  { "path": "index.html", "size": 10, "contentType": "text/html" }
                ]
              }
            },
            "screens": [{ "id": "screen_1" }],
            "interactions": {},
            "viewModels": []
          }
          """.trimIndent()
        ),
    )
    server.start()
    try {
      val api = NuxieApi(
        apiKey = "k",
        baseUrl = server.url("/").toString().removeSuffix("/"),
      )
      val flow = api.fetchFlow("flow_1")
      assertEquals("flow_1", flow.id)

      val req = server.takeRequest()
      assertEquals("GET", req.method)
      assertTrue(req.path!!.startsWith("/flows/flow_1?"))
      assertTrue(req.path!!.contains("apiKey=k"))
    } finally {
      server.shutdown()
    }
  }

  @Test
  fun check_feature_includes_api_key_in_body() = runBlocking {
    val server = MockWebServer()
    server.enqueue(
      MockResponse()
        .setResponseCode(200)
        .setHeader("Content-Type", "application/json")
        .setBody(
          """
          {
            "customerId":"d1",
            "featureId":"f1",
            "requiredBalance":1,
            "code":"feature_found",
            "allowed":true,
            "unlimited":false,
            "balance":1,
            "type":"boolean"
          }
          """.trimIndent()
        ),
    )
    server.start()
    try {
      val api = NuxieApi(
        apiKey = "k",
        baseUrl = server.url("/").toString().removeSuffix("/"),
      )
      val res = api.checkFeature(customerId = "d1", featureId = "f1", requiredBalance = 1, entityId = null)
      assertTrue(res.allowed)

      val req = server.takeRequest()
      assertEquals("POST", req.method)
      assertEquals("/entitled", req.path)

      val body = req.body.readUtf8()
      val obj = json.parseToJsonElement(body).jsonObject
      assertEquals("k", obj["apiKey"]?.toString()?.trim('"'))
      assertEquals("d1", obj["customerId"]?.toString()?.trim('"'))
      assertEquals("f1", obj["featureId"]?.toString()?.trim('"'))
      assertEquals("1", obj["requiredBalance"]?.toString()?.trim('"'))
    } finally {
      server.shutdown()
    }
  }

  @Test
  fun sync_play_store_purchase_posts_google_play_contract() = runBlocking {
    val server = MockWebServer()
    server.enqueue(
      MockResponse()
        .setResponseCode(200)
        .setHeader("Content-Type", "application/json")
        .setBody(
          """
          {
            "success": true,
            "customer_id": "cus_123",
            "features": [
              {
                "id": "pro",
                "ext_id": "pro",
                "type": "boolean",
                "allowed": true,
                "balance": null,
                "unlimited": false
              }
            ]
          }
          """.trimIndent()
        ),
    )
    server.start()
    try {
      val api = NuxieApi(
        apiKey = "k",
        baseUrl = server.url("/").toString().removeSuffix("/"),
      )
      val res = api.syncPlayStorePurchase(
        PlayStorePurchaseRequest(
          type = "playstore",
          purchaseToken = "token_abc",
          productId = "pro_monthly",
          packageName = "io.nuxie.example",
          basePlanId = "monthly",
          distinctId = "user_123",
          productType = PlayStoreProductType.SUBSCRIPTION,
          consumePurchase = null,
        )
      )
      assertTrue(res.success)
      assertEquals("cus_123", res.customerId)

      val req = server.takeRequest()
      assertEquals("POST", req.method)
      assertEquals("/purchase", req.path)

      val body = req.body.readUtf8()
      val obj = json.parseToJsonElement(body).jsonObject
      assertEquals("k", obj["apiKey"]?.jsonPrimitive?.content)
      assertEquals("playstore", obj["type"]?.jsonPrimitive?.content)
      assertEquals("token_abc", obj["purchase_token"]?.jsonPrimitive?.content)
      assertEquals("pro_monthly", obj["product_id"]?.jsonPrimitive?.content)
      assertEquals("io.nuxie.example", obj["package_name"]?.jsonPrimitive?.content)
      assertEquals("monthly", obj["base_plan_id"]?.jsonPrimitive?.content)
      assertEquals("user_123", obj["distinct_id"]?.jsonPrimitive?.content)
      assertEquals("subscription", obj["product_type"]?.jsonPrimitive?.content)
      assertEquals(null, obj["consume_purchase"]?.jsonPrimitive?.booleanOrNull)
    } finally {
      server.shutdown()
    }
  }

  @Test
  fun gzip_enabled_compresses_post_body() = runBlocking {
    val server = MockWebServer()
    server.enqueue(
      MockResponse()
        .setResponseCode(200)
        .setHeader("Content-Type", "application/json")
        .setBody("{\"status\":\"ok\"}"),
    )
    server.start()
    try {
      val api = NuxieApi(
        apiKey = "k",
        baseUrl = server.url("/").toString().removeSuffix("/"),
        useGzipCompression = true,
      )
      api.trackEvent(event = "test", distinctId = "d1")

      val req = server.takeRequest()
      assertEquals("gzip", req.getHeader("Content-Encoding"))

      val gzBytes = req.body.readByteArray()
      val decompressed = GZIPInputStream(ByteArrayInputStream(gzBytes)).readBytes()
        .toString(Charsets.UTF_8)
      val obj = json.parseToJsonElement(decompressed).jsonObject
      assertEquals("k", obj["apiKey"]?.toString()?.trim('"'))
      assertEquals("test", obj["event"]?.toString()?.trim('"'))
      assertEquals("d1", obj["distinct_id"]?.toString()?.trim('"'))
    } finally {
      server.shutdown()
    }
  }

  @Test
  fun http_error_throws_http_error_with_status_code() = runBlocking {
    val server = MockWebServer()
    server.enqueue(
      MockResponse()
        .setResponseCode(400)
        .setHeader("Content-Type", "application/json")
        .setBody("{\"message\":\"bad\"}"),
    )
    server.start()
    try {
      val api = NuxieApi(
        apiKey = "k",
        baseUrl = server.url("/").toString().removeSuffix("/"),
      )
      try {
        api.fetchProfile(distinctId = "d1")
        throw AssertionError("Expected exception")
      } catch (e: NuxieNetworkError.HttpError) {
        assertEquals(400, e.statusCode)
      }
    } finally {
      server.shutdown()
    }
  }
}
