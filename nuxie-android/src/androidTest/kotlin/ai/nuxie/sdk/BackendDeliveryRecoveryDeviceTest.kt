package ai.nuxie.sdk

import ai.nuxie.sdk.core.NuxieCore
import ai.nuxie.sdk.events.SystemEventNames
import ai.nuxie.sdk.identity.IdentityService
import ai.nuxie.sdk.network.HttpTransport
import ai.nuxie.sdk.network.HttpUrlConnectionTransport
import ai.nuxie.sdk.presentation.NuxieExperienceActivity
import ai.nuxie.sdk.presentation.SurfaceCompatibilityHostActivity
import android.app.Instrumentation
import android.content.Intent
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.net.URL
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Opt-in local operator proof; the controller publishes policy and forwards real profiles. */
class BackendDeliveryRecoveryDeviceTest {
    @Test fun backendPolicyWithdrawsDeliveryAndRestoresNativePresentation() = runBlocking {
        val address = InstrumentationRegistry.getArguments().getString("nuxie_delivery_probe_url")
        assumeTrue("Requires the local backend delivery controller", address != null)
        val base = URL(checkNotNull(address))
        require(base.protocol == "http" && base.host == "127.0.0.1" && base.port > 0)
        val http = HttpUrlConnectionTransport(connectTimeoutMillis = 15_000, readTimeoutMillis = 15_000)
        fun control(path: String, body: String? = null): HttpTransport.Response {
            val response = http.execute(HttpTransport.Request(
                URL(base.toExternalForm().trimEnd('/') + path), mapOf("Content-Type" to "application/json"),
                body?.encodeToByteArray() ?: ByteArray(0), if (body == null) "GET" else "POST",
            ))
            check(response.statusCode == 200) { "Local controller returned ${response.statusCode}" }
            return response
        }
        val config = Json.parseToJsonElement(control("/config").body.decodeToString()).jsonObject
        val apiKey = config.getValue("apiKey").jsonPrimitive.content
        val invoiceFixture = config["localInvoiceFixture"]?.jsonPrimitive?.booleanOrNull == true
        require(apiKey.startsWith(if (invoiceFixture) "pk_live_" else "pk_test_"))
        val platformId = config.getValue("appPlatformId").jsonPrimitive.content
        val artifactKeys = config.getValue("artifactKeys").jsonArray.map { it.jsonPrimitive.content }.toSet()
        val online = AtomicBoolean(true)
        val blocked = AtomicInteger()
        val transport = HttpTransport { request ->
            if (!online.get()) {
                blocked.incrementAndGet()
                throw java.io.IOException("Qualification SDK transport offline")
            }
            val path = request.url.path
            if (path != "/profile" && path.trimStart('/') !in artifactKeys) {
                HttpTransport.Response(503, ByteArray(0)) // Retain SDK events for assertions.
            } else {
                val response = http.execute(HttpTransport.Request(
                    URL(base.toExternalForm().trimEnd('/') + path), request.headers, request.body, request.method,
                ))
                HttpTransport.Response(response.statusCode, response.body, response.headers, request.url)
            }
        }
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val directory = File(context.cacheDir, "backend-delivery-${UUID.randomUUID()}").apply { mkdirs() }
        val identity = IdentityService(context).apply { setDistinctId(directory.name) }
        val monitor = Instrumentation.ActivityMonitor(NuxieExperienceActivity::class.java.name, null, false)
        instrumentation.addMonitor(monitor)
        val host = instrumentation.startActivitySync(Intent(context, SurfaceCompatibilityHostActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        check(Nuxie.core == null)
        Nuxie.overridesForTesting = NuxieCore.Overrides(identity = identity, transport = transport,
            requestInitialProfileRefresh = false, eventDatabaseFile = File(directory, "events.db"),
            profileCacheDirectory = File(directory, "profiles"))
        try {
            control("/policy", "{\"suspended\":false}")
            instrumentation.runOnMainSync {
                Nuxie.setup(host, NuxieConfiguration(apiKey).apply {
                    environment = NuxieEnvironment.DEVELOPMENT
                    logLevel = LogLevel.NONE
                })
            }
            val core = checkNotNull(Nuxie.core)
            fun snapshot() = checkNotNull(core.journeyProfiles.snapshot(identity.distinctId()))
            assertTrue(core.profile.refreshAndWait())
            assertEquals(1, snapshot().profile.armedLegs.size)
            val release = snapshot().releasesByDigest.values.single()
            assertEquals(platformId, release.identity.appId)
            assertEquals(if (invoiceFixture) "live" else "test", release.identity.environment)
            control("/policy", "{\"suspended\":true}")
            online.set(false)
            assertFalse(core.profile.refreshAndWait())
            assertEquals(1, snapshot().profile.armedLegs.size)
            online.set(true)
            assertTrue(core.profile.refreshAndWait())
            assertTrue(snapshot().profile.armedLegs.isEmpty())
            assertTrue(snapshot().releasesByDigest.isEmpty())
            Nuxie.trigger("mar_delivery_recovered")
            core.eventLog.awaitBarrier()
            assertEquals("Suspended delivery must not launch an Experience", 0, monitor.hits)
            if (invoiceFixture) {
                control("/settle-one", "{}")
                assertTrue(core.profile.refreshAndWait())
                assertTrue("One paid invoice must not restore delivery", snapshot().profile.armedLegs.isEmpty())
                control("/settle-partial", "{}")
                assertTrue(core.profile.refreshAndWait())
                assertTrue("Partial credit must not restore delivery", snapshot().profile.armedLegs.isEmpty())
                assertEquals(0, monitor.hits)
            }
            control("/policy", "{\"suspended\":false}")
            assertTrue(core.profile.refreshAndWait())
            assertEquals(1, snapshot().profile.armedLegs.size)
            assertEquals(release.identity, snapshot().releasesByDigest.values.single().identity)
            Nuxie.trigger("mar_delivery_recovered")
            assertNotNull("Restored backend delivery must render", monitor.waitForActivityWithTimeout(15_000))
            withTimeout(15_000) {
                while (core.store.pendingBatch(200).none { it.name == SystemEventNames.EXPERIENCE_SHOWN }) delay(25)
            }
            assertTrue(blocked.get() >= 1)
        } finally {
            online.set(true)
            try { control("/policy", "{\"suspended\":false}") }
            finally {
                try { Nuxie.shutdownAndAwait() }
                finally {
                    Nuxie.overridesForTesting = null
                    instrumentation.runOnMainSync { monitor.lastActivity?.finish(); host.finish() }
                    instrumentation.removeMonitor(monitor)
                    directory.deleteRecursively()
                }
            }
        }
    }
}
