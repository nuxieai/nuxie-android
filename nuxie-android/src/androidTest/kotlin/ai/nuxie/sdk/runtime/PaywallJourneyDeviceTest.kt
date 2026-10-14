package ai.nuxie.sdk.runtime

import ai.nuxie.sdk.events.SQLiteEventStore
import ai.nuxie.sdk.experiences.*
import ai.nuxie.sdk.identity.IdentityService
import ai.nuxie.sdk.journey.*
import ai.nuxie.sdk.network.ProfileDeliveryAuthority
import ai.nuxie.sdk.presentation.*
import android.content.ContextWrapper
import android.util.Base64
import androidx.test.platform.app.InstrumentationRegistry
import java.io.Closeable
import java.io.File
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

/** Published native controls and program, real presentation service and durable Journal. */
class PaywallJourneyDeviceTest {
    @Test
    fun nativeBuySettlesJourneyAndAcceptsRetry() {
        val nativeLane = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        try {
            runBlocking(nativeLane) {
            assertTrue("Pinned native runtime must load", NuxieRuntime.shared.isAvailable)
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            val assets = instrumentation.context.assets
            val directory = File(instrumentation.targetContext.cacheDir, "paywall-journey-${UUID.randomUUID()}").apply { mkdirs() }
            val context = object : ContextWrapper(instrumentation.targetContext) {
                override fun getApplicationContext() = this
                override fun getFilesDir() = directory
            }
            fun asset(path: String) = assets.open(path).bufferedReader().use { Json.parseToJsonElement(it.readText()) }
            val corpus = asset("journeys/planes/release.json").jsonObject
            val entry = corpus.getValue("renderedEntry").jsonObject
            val locator = entry.getValue("locator").jsonObject
            val envelope = entry.getValue("envelope").jsonObject
            val original = Json.parseToJsonElement(Base64.decode(envelope.getValue("descriptorBytesBase64").jsonPrimitive.content, Base64.NO_WRAP).decodeToString()).jsonObject
            val requirements = original.getValue("requirements").jsonObject
            val luau = requirements.getValue("luau").jsonObject
            val sceneFormat = requirements.getValue("sceneFormat").jsonObject
            val timezone = requirements.getValue("timezoneData").jsonObject
            val runtime = JourneyReleaseSupportedRuntime(requirements.getValue("minimumSdkVersion").jsonPrimitive.content,
                setOf(requirements.getValue("runtimeRevision").jsonPrimitive.content),
                mapOf(luau.getValue("revision").jsonPrimitive.content to luau.getValue("bytecodeVersions").jsonArray.map { it.jsonPrimitive.int }.toSet()),
                sceneFormat.getValue("major").jsonPrimitive.int, sceneFormat.getValue("minor").jsonPrimitive.int,
                timezone.getValue("revision").jsonPrimitive.content, timezone.getValue("sha256").jsonPrimitive.content,
                requirements.getValue("requiredCapabilities").jsonArray.map { it.jsonPrimitive.content }.toSet())
            val keys = mapOf("TEST_ONLY_DEV_KEYPAIR" to Base64.decode(corpus.getValue("publicKeyBase64").jsonPrimitive.content, Base64.NO_WRAP))
            val catalog = JourneyProfileCatalog(keys, JourneyReleaseHighWaterStore(context)) { runtime }
            val authority = ProfileDeliveryAuthority(locator.getValue("appId").jsonPrimitive.content, locator.getValue("environment").jsonPrimitive.content)
            val profile = buildJsonObject {
                put("schemaVersion", "nuxie.journey-plane-profile.v2"); put("status", "ok")
                putJsonObject("delivery") { put("renderBaseUrl", "https://renders.example.com/"); put("assetBaseUrl", "https://assets.example.com/") }
                put("features", JsonArray(emptyList()))
                putJsonObject("facts") { put("properties", JsonObject(emptyMap())); put("memberships", JsonObject(emptyMap())); put("assignments", JsonObject(emptyMap())) }
                put("releases", JsonArray(listOf(entry)))
                putJsonArray("armedLegs") { addJsonObject {
                    putJsonObject("reference") { put("experienceId", locator.getValue("experienceId")); put("versionId", locator.getValue("experienceVersionId")); put("legId", locator.getValue("legId")); put("descriptorSha256", envelope.getValue("descriptorSha256")) }
                    putJsonObject("binding") { put("type", "new") }
                    put("entryCondition", original.getValue("leg").jsonObject.getValue("entryCondition"))
                    putJsonObject("context") { put("event", JsonObject(emptyMap())); put("responses", JsonObject(emptyMap())) }
                } }
            }
            catalog.commit("native-customer", catalog.prepare(profile, authority))
            val baseline = checkNotNull(catalog.snapshot("native-customer"))
            val authenticated = baseline.releasesByDigest.values.single()
            // The envelope is the authenticated SDK harness. Program and scene are
            // canonical publisher products; this does not qualify release signing.
            val compiledLeg = asset("runtime/paywall-status/empty-leg.json").jsonObject
            val leg = JsonObject(compiledLeg + ("id" to original.getValue("leg").jsonObject.getValue("id")))
            val render = original.getValue("render").jsonObject
            val renderedScreen = render.getValue("screens").jsonArray.first().jsonObject
            val descriptor = JsonObject(original + mapOf("leg" to leg,
                "viewModelValues" to asset("runtime/paywall-status/view-model-values.json"),
                // This is the published snapshot's buy.controlAction. The host
                // harness supplies its manifest, rather than qualifying signing.
                "screenBehaviors" to Json.parseToJsonElement("""[{"screenId":"state-screen","controls":[{"actionId":"buy","behavior":{"kind":"declarative","program":[{"type":"emit","eventName":"purchase"}]}}]}]"""),
                "render" to JsonObject(render + ("screens" to JsonArray(listOf(JsonObject(renderedScreen + mapOf(
                    "id" to JsonPrimitive("state-screen"), "artboardName" to JsonPrimitive("Paywall"), "width" to JsonPrimitive(320), "height" to JsonPrimitive(150)))))))))
            val release = AuthenticatedJourneyRelease(JourneyReleaseEnvelope.authenticate(envelope.toString().encodeToByteArray(), keys), authenticated.identity, descriptor, authenticated.publishedAtSeqToPromote)
            val snapshot = baseline.copy(releasesByDigest = mapOf(release.descriptorSha256 to release))
            val scene = File(directory, "screen.riv").apply { writeBytes(assets.open("runtime/paywall-status/screen.riv").use { it.readBytes() }) }
            val identity = IdentityService(context).apply { setDistinctId("native-customer") }
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val launched = CompletableDeferred<String>()
            val request = AtomicReference<JourneyPresentationRequest>()
            val captures = CopyOnWriteArrayList<String>()
            val presentations = ExperiencePresentationService(emit = { _, _, _ -> }, scope = scope,
                runtimeAvailable = { NuxieRuntime.shared.isAvailable }, currentDistinctId = identity::distinctId, isAppForeground = { true }, launch = { launched.complete(it) })
            val presenter = object : JourneyPresenting {
                override suspend fun openLink(owner: JourneyPresentationOwner, link: JourneyLinkRequest) = presentations.openJourneyLink(owner, link)
                override fun reserve(ownerDistinctId: String) = presentations.reserveJourney(ownerDistinctId)
                override fun owns(owner: JourneyPresentationOwner) = presentations.ownsJourney(owner)
                override fun screenId(owner: JourneyPresentationOwner) = presentations.journeyScreenId(owner)
                override fun resolveAction(owner: JourneyPresentationOwner, action: JsonObject, source: JourneyScreenEmissionSource?, eventSource: JourneyRuntimeEventSource?) = presentations.resolveJourneyAction(owner, action, source, eventSource)
                override suspend fun dispatchAction(owner: JourneyPresentationOwner, action: JsonObject, effectId: String) = presentations.dispatchJourneyAction(owner, action, effectId)
                override suspend fun projectCommerceOutcome(owner: JourneyPresentationOwner, type: JourneyActionType, outlet: String, errorCode: String, effectId: String) = presentations.projectJourneyCommerceOutcome(owner, type, outlet, errorCode, effectId)
                override suspend fun shutdownOwnedBy(ownerDistinctId: String) = presentations.shutdownOwnedBy(ownerDistinctId)
                override suspend fun present(value: JourneyPresentationRequest): JourneyPresentationResult {
                    request.set(value)
                    presentations.presentJourney(value.fences, value.release, value.screenId, value.journeyId, value.ownerDistinctId, value.reservation,
                        canPresent = value.canPresent, nextBatchSequence = value.nextBatchSequence, nextEmissionSequence = value.nextEmissionSequence,
                        acquire = { AcquiredJourneyRelease(release.identity, mapOf("renders/main.riv" to scene), scene, protection = Closeable {}) },
                        onScreenChanged = value.onScreenChanged, onScreenDismissed = value.onScreenDismissed, onPresentationRevealed = value.onPresentationRevealed,
                        onEmissionBatch = value.onEmissionBatch, onOutcome = value.onOutcome)
                    return JourneyPresentationResult.Shown
                }
            }
            val store = SQLiteEventStore(context, databaseFile = File(directory, "events.db"))
            val journeys = JourneyService(identity, store, catalog, File(directory, "journal"), scope,
                capture = { name, _, _, _ -> captures.add(name); true }, presenter = presenter, nowMillis = { 100_000L })
            val renderer = checkNotNull(NuxieRuntime.shared.newAndroidVulkanRenderer(320, 150))
            var presentationId: String? = null
            var host: PresentationScreenHandle? = null
            try {
                val file = checkNotNull(NuxieRuntime.shared.importFile(renderer, scene.readBytes()))
                try {
                    val artboard = checkNotNull(file.newArtboard("Paywall"))
                    try {
                        artboard.bindDefaultViewModel("Runtime", "root-sdk-id")
                        val player = file.newExperiencePlayer(artboard, "Paywall")
                        try {
                            repeat(20) { player.stepTyped(elapsedSeconds = 0.016) }
                            val errors = CopyOnWriteArrayList<String>()
                            host = object : PresentationScreenHandle {
                                override fun requestCloseFromService(reason: CloseReason) = true
                                override fun screenCloseReason(): CloseReason? = null
                                override fun finishAfterServiceClose() = Unit
                                override suspend fun updateRuntimeValues(values: Map<String, NuxieViewModelScalarValue>): Unit = withContext(nativeLane) {
                                    synchronized(artboard) {
                                        for ((path, value) in values) assertTrue(artboard.setDefaultViewModelValue(path, value))
                                        val current = checkNotNull(artboard.defaultViewModelSnapshot())
                                        if (current.resolveString("paywall/purchase/errorCode") == "failed") errors += checkNotNull(current.resolveString("paywall/purchase/invocationId"))
                                    }
                                }
                            }
                            val admission = async { journeys.initialize(); journeys.onAppWillEnterForeground(); journeys.profileDidCommit(snapshot, authority, "native-customer", 1) }
                            presentationId = withTimeout(15_000) { launched.await() }
                            withTimeout(15_000) { checkNotNull(PresentationRegistry.observe(checkNotNull(presentationId))).first { it is PresentationContentState.Ready } }
                            assertTrue(PresentationRegistry.attach(checkNotNull(presentationId), checkNotNull(host)))
                            PresentationRegistry.reportFirstFrame(checkNotNull(presentationId)); withTimeout(15_000) { admission.await() }
                            val journal = JourneyRunJournal(File(directory, "journal"), "native-customer", JourneyStorageScope(authority))
                            var correlation = 0UL
                            for (index in 1..2) {
                                synchronized(artboard) {
                                    assertTrue(artboard.setDefaultViewModelValue("paywall/selectedProduct/placementId", NuxieViewModelScalarValue.StringValue("")))
                                    assertTrue(artboard.setDefaultViewModelValue("paywall/selectedPlacementId", NuxieViewModelScalarValue.StringValue("")))
                                    assertTrue(artboard.setDefaultViewModelValue("paywall/selectedProductId", NuxieViewModelScalarValue.StringValue("")))
                                }
                                val steps = synchronized(artboard) { listOf(
                                    player.stepTyped(elapsedSeconds = 0.0, pointers = listOf(NuxiePlayerPointerEvent(NuxiePlayerPointerKind.DOWN, 160f, 120f, 1, 0f))),
                                    player.stepTyped(elapsedSeconds = 0.0, pointers = listOf(NuxiePlayerPointerEvent(NuxiePlayerPointerKind.UP, 160f, 120f, 1, 0.1f))),
                                    player.stepTyped(elapsedSeconds = 0.016)) }
                                assertTrue("Buy comes from a native pointer", steps.any { it.hostCommands.isNotEmpty() || it.events.isNotEmpty() })
                                for (step in steps) PresentationRegistry.reportRuntimeStep(checkNotNull(presentationId), step, ++correlation,
                                    synchronized(artboard) { checkNotNull(artboard.defaultViewModelSnapshot()) })
                                withTimeout(10_000) { while (errors.distinct().size < index || journal.runs().singleOrNull()?.effectReceipts?.isNotEmpty() != false) delay(10) }
                                val run = journal.runs().single()
                                assertNull(run.pendingCommerce); assertTrue(run.effectReceipts.isEmpty())
                                assertTrue(presentations.ownsJourney(JourneyPresentationOwner(request.get().journeyId, "native-customer")))
                            }
                            assertEquals(2, errors.distinct().size)
                            assertFalse(captures.contains(JourneyEventNames.LEG_COMPLETED))
                        } finally { player.close() }
                    } finally { artboard.close() }
                } finally { file.close() }
            } finally {
                if (presentationId != null && host != null) PresentationRegistry.detach(checkNotNull(presentationId), checkNotNull(host))
                scope.cancel(); withTimeout(5_000) { presentations.shutdownOwnedBy("native-customer") }; store.close(); renderer.close(); directory.deleteRecursively()
            }
            }
        } finally {
            nativeLane.close()
        }
    }
}
