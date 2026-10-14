package ai.nuxie.sdk.runtime

import ai.nuxie.sdk.experiences.AcquiredJourneyRelease
import ai.nuxie.sdk.experiences.AuthenticatedJourneyRelease
import ai.nuxie.sdk.experiences.JourneyReleaseEnvelope
import ai.nuxie.sdk.experiences.JourneyReleaseIdentity
import ai.nuxie.sdk.identity.IdentityService
import ai.nuxie.sdk.journey.JourneyExecutionFence
import ai.nuxie.sdk.presentation.*
import android.content.ContextWrapper
import android.util.Base64
import androidx.test.platform.app.InstrumentationRegistry
import java.io.Closeable
import java.io.File
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

/** Exercises SDK feedback requests against the published scene's real enum fields. */
class PaywallFeedbackDeviceTest {
    @Test
    fun emptyBuyWritesDeclaredNativeEnumAndInvocation() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val assets = instrumentation.context.assets
        assertTrue(NuxieRuntime.shared.isAvailable)
        val directory = File(context.cacheDir, "feedback-${UUID.randomUUID()}").apply { mkdirs() }
        val scene = File(directory, "screen.riv").apply {
            writeBytes(assets.open("runtime/paywall-status/screen.riv").use { it.readBytes() })
        }
        val corpus = Json.parseToJsonElement(assets.open("journeys/planes/release.json").bufferedReader().use { it.readText() }).jsonObject
        val entry = corpus.getValue("renderedEntry").jsonObject
        val envelope = JourneyReleaseEnvelope.authenticate(entry.getValue("envelope").toString().encodeToByteArray(),
            mapOf("TEST_ONLY_DEV_KEYPAIR" to Base64.decode(corpus.getValue("publicKeyBase64").jsonPrimitive.content, Base64.NO_WRAP)))
        val original = Json.parseToJsonElement(Base64.decode(entry.getValue("envelope").jsonObject.getValue("descriptorBytesBase64").jsonPrimitive.content, Base64.NO_WRAP).decodeToString()).jsonObject
        val rows = Json.parseToJsonElement(assets.open("runtime/paywall-status/view-model-values.json").bufferedReader().use { it.readText() }).jsonArray
        val leg = original.getValue("leg").jsonObject
        val descriptor = JsonObject(original + mapOf(
            "leg" to JsonObject(leg + ("screens" to buildJsonArray { add(buildJsonObject {
                put("id", "screen_welcome"); put("defaultViewModelName", "Runtime"); put("defaultInstanceId", "root-sdk-id"); put("responseCaptures", JsonArray(emptyList()))
            }) })), "viewModelValues" to rows))
        val identity = checkNotNull(JourneyReleaseIdentity.fromJson(entry.getValue("locator").jsonObject, additionalKeys = setOf("legId")))
        val release = AuthenticatedJourneyRelease(envelope, identity, descriptor, null)
        val customer = "feedback-customer"
        val identityService = IdentityService(object : ContextWrapper(context) {
            override fun getApplicationContext() = this
            override fun getFilesDir() = directory
        }).apply { setDistinctId(customer) }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val launched = CompletableDeferred<String>()
        val presentations = ExperiencePresentationService(emit = { _, _, _ -> }, scope = scope,
            runtimeAvailable = { NuxieRuntime.shared.isAvailable }, currentDistinctId = { customer }, isAppForeground = { true },
            launch = { launched.complete(it) })
        val renderer = checkNotNull(NuxieRuntime.shared.newAndroidVulkanRenderer(320, 150))
        var id: String? = null
        var host: PresentationScreenHandle? = null
        try {
            val file = checkNotNull(NuxieRuntime.shared.importFile(renderer, scene.readBytes()))
            try {
                val artboard = checkNotNull(file.newArtboard("Paywall"))
                try {
                    artboard.bindDefaultViewModel("Runtime", "root-sdk-id")
                    val fence = JourneyExecutionFence()
                    val fences = JourneyPresentationFences(identityService, identityService.captureScope(), fence, fence.token())
                    val owner = JourneyPresentationOwner("feedback-journey", customer)
                    val pending = async { presentations.presentJourney(fences, release, "screen_welcome", owner.journeyId, customer,
                        presentations.reserveJourney(customer), acquire = {
                            AcquiredJourneyRelease(identity, mapOf("renders/main.riv" to scene), scene, protection = Closeable {})
                        }, onOutcome = {}) }
                    id = withTimeout(10_000) { launched.await() }
                    PresentationRegistry.reportFirstFrame(checkNotNull(id)); pending.await()
                    val observed = mutableListOf<Long>()
                    host = object : PresentationScreenHandle {
                        override fun requestCloseFromService(reason: CloseReason) = true
                        override fun screenCloseReason(): CloseReason? = null
                        override fun finishAfterServiceClose() = Unit
                        override suspend fun updateRuntimeValues(values: Map<String, NuxieViewModelScalarValue>) {
                            for ((path, value) in values) assertTrue(artboard.setDefaultViewModelValue(path, value))
                            observed += checkNotNull(artboard.defaultViewModelSnapshot()?.resolveEnumOrdinal("paywall/purchase/status"))
                        }
                    }
                    assertTrue(PresentationRegistry.attach(checkNotNull(id), checkNotNull(host)))
                    val action = Json.parseToJsonElement("""{"type":"purchase","placementId":""}""").jsonObject
                    val property = file.viewModelCatalog().let { catalog ->
                        catalog.propertyAtPath(catalog.schemas.single { it.name == "Runtime" }.index, "paywall/purchase/status")
                    }
                    for (index in 1..2) {
                        PresentationRegistry.reportRuntimeStep(checkNotNull(id), NuxiePlayerStepOutcome(true, emptyList(), emptyList(), emptyList(), emptyList()),
                            index.toULong(), checkNotNull(artboard.defaultViewModelSnapshot()))
                        assertEquals(JourneyPresentationActionResult.Advanced("failed"), presentations.dispatchJourneyAction(owner, action, "native-empty-$index"))
                        val snapshot = checkNotNull(artboard.defaultViewModelSnapshot())
                        assertEquals("error", property.enumLabels[checkNotNull(snapshot.resolveEnumOrdinal("paywall/purchase/status")).toInt()])
                        assertEquals("failed", snapshot.resolveString("paywall/purchase/errorCode"))
                        assertEquals("native-empty-$index", snapshot.resolveString("paywall/purchase/invocationId"))
                        assertTrue(presentations.ownsJourney(owner))
                    }
                    assertEquals(listOf("idle", "error", "idle", "error"), observed.map { property.enumLabels[it.toInt()] })
                } finally { artboard.close() }
            } finally { file.close() }
        } finally {
            if (id != null && host != null) PresentationRegistry.detach(checkNotNull(id), checkNotNull(host))
            presentations.shutdownOwnedBy(customer)
            scope.cancel(); renderer.close(); directory.deleteRecursively()
        }
    }
}
