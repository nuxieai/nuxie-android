package ai.nuxie.sdk.presentation

import ai.nuxie.sdk.core.supportedRuntimeForEmbeddedRuntime
import ai.nuxie.sdk.events.SQLiteEventStore
import ai.nuxie.sdk.experiences.*
import ai.nuxie.sdk.identity.IdentityProvider
import ai.nuxie.sdk.journey.JourneyService
import ai.nuxie.sdk.network.ProfileDeliveryAuthority
import ai.nuxie.sdk.runtime.*
import android.app.Instrumentation
import android.os.SystemClock
import android.util.Base64
import android.view.View
import android.view.ViewGroup
import androidx.test.platform.app.InstrumentationRegistry
import java.io.Closeable
import java.io.File
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.Signature
import java.util.UUID
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class SharedValuesJourneyDeviceTest {
    @Test fun nextPresentedScreenReadsTheRunWrite() = runBlocking {
        assertTrue(NuxieRuntime.shared.isAvailable)
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val owner = "shared-values-${UUID.randomUUID()}"
        val directory = File(context.cacheDir, owner).apply { mkdirs() }
        val fixture = fixture()
        val scene = File(directory, "screen.riv").apply { writeBytes(fixture.scene) }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val store = SQLiteEventStore(context, databaseFile = File(directory, "events.db"))
        val presentations = ExperiencePresentationService(context, { _, _, _ -> }, scope,
            { NuxieRuntime.shared.isAvailable }, currentDistinctId = { owner })
        val monitor = Instrumentation.ActivityMonitor(NuxieExperienceActivity::class.java.name, null, false)
        instrumentation.addMonitor(monitor)
        val request = AtomicReference<JourneyPresentationRequest?>()
        val revealed = LinkedBlockingQueue<String>()
        val catalog = JourneyProfileCatalog(fixture.keys, JourneyReleaseHighWaterStore(context)) { fixture.supported }
        val presenter = object : JourneyPresenting {
            override fun reserve(ownerDistinctId: String) = presentations.reserveJourney(ownerDistinctId)
            override fun owns(owner: JourneyPresentationOwner) = presentations.ownsJourney(owner)
            override fun screenId(owner: JourneyPresentationOwner) = presentations.journeyScreenId(owner)
            override suspend fun openLink(owner: JourneyPresentationOwner, link: JourneyLinkRequest) = presentations.openJourneyLink(owner, link)
            override suspend fun shutdownOwnedBy(ownerDistinctId: String) = presentations.shutdownOwnedBy(ownerDistinctId)
            override suspend fun shutdownPresentation(ownerDistinctId: String, journeyId: String) = presentations.shutdownJourney(ownerDistinctId, journeyId)
            override suspend fun present(value: JourneyPresentationRequest): JourneyPresentationResult {
                request.set(value)
                presentations.presentJourney(value.fences, value.release, value.screenId, value.journeyId,
                    value.ownerDistinctId, value.reservation, value.canPresent,
                    acquire = { AcquiredJourneyRelease(value.release.identity, emptyMap(), scene, protection = Closeable {}) },
                    nextBatchSequence = value.nextBatchSequence, nextEmissionSequence = value.nextEmissionSequence,
                    onScreenChanged = value.onScreenChanged, onScreenDismissed = value.onScreenDismissed,
                    onLinkOpened = value.onLinkOpened, onEmissionBatch = value.onEmissionBatch,
                    onPresentationRevealed = { value.onPresentationRevealed(it); revealed.add(it) },
                    onOutcome = value.onOutcome, runValues = value.runValues)
                return JourneyPresentationResult.Shown
            }
        }
        val identity = object : IdentityProvider {
            override fun distinctId() = owner
            override fun anonymousId() = owner
            override fun rawDistinctId(): String? = null
            override val isIdentified = false
        }
        val service = JourneyService(identity, store, catalog, directory, scope,
            capture = { name, properties, eventId, distinctId ->
                store.insertPendingIfAbsent(ai.nuxie.sdk.events.StoredEvent(eventId, name,
                    ai.nuxie.sdk.events.JsonValueConverter.fromMap(properties), System.currentTimeMillis(), distinctId))
            }, presenter = presenter)
        try {
            withTimeout(30_000) {
                catalog.commit(owner, catalog.prepare(fixture.profile, fixture.authority))
                service.initialize()
                service.onAppWillEnterForeground()
                service.profileDidCommit(checkNotNull(catalog.snapshot(owner)), fixture.authority, owner, 1)
            }
            val activity = checkNotNull(monitor.waitForActivityWithTimeout(10_000))
            assertEquals("first", revealed.poll(20, TimeUnit.SECONDS))
            fun surface(previous: ExperienceSurfaceHost? = null): ExperienceSurfaceHost? {
                var result: ExperienceSurfaceHost? = null
                instrumentation.runOnMainSync {
                    fun find(view: View): ExperienceSurfaceHost? = when (view) {
                        is ExperienceSurfaceHost -> view.takeIf { it !== previous && it.isShown }
                        is ViewGroup -> (0 until view.childCount).firstNotNullOfOrNull { find(view.getChildAt(it)) }
                        else -> null
                    }
                    result = find((monitor.lastActivity ?: activity).window.decorView)
                }
                return result
            }
            fun lane(host: ExperienceSurfaceHost) = ExperienceSurfaceHost::class.java.getDeclaredField("lane").apply { isAccessible = true }.get(host) as NuxieRuntimeLane
            fun artboard(host: ExperienceSurfaceHost) = ExperienceSurfaceHost::class.java.getDeclaredField("artboard").apply { isAccessible = true }.get(host) as NuxieRuntimeArtboard
            val first = checkNotNull(surface())
            val firstId = lane(first).call {
                val root = artboard(first)
                assertTrue(root.setDefaultViewModelValue("experience/trip_days", NuxieViewModelScalarValue.NumberValue(30.0)))
                checkNotNull(root.defaultViewModelSnapshot()).nativeRootInstanceId
            }
            val active = checkNotNull(request.get())
            assertTrue(active.onEmissionBatch(JourneyScreenEmissionBatch(active.journeyId, active.nextBatchSequence,
                "shared-host-continue", JourneyScreenEmissionSource("first", "scr_screens_sfirst::v3::on-click"),
                listOf(JourneyScreenEmission("shared-continue", active.nextEmissionSequence,
                    System.currentTimeMillis(), "continue", JsonObject(emptyMap())))), null))
            withTimeout(20_000) {
                while (request.get()?.screenId != "long") delay(20)
            }
            assertEquals("long", checkNotNull(request.get()).screenId)
            var next: ExperienceSurfaceHost? = null
            val deadline = SystemClock.uptimeMillis() + 20_000
            while (next == null && SystemClock.uptimeMillis() < deadline) {
                val candidate = surface(first)
                val presented = candidate != null && ExperienceSurfaceHost::class.java.getDeclaredField("firstFramePresented").apply { isAccessible = true }.getBoolean(candidate)
                if (presented) next = candidate else delay(20)
            }
            val shown = checkNotNull(next) {
                val states = mutableListOf<String>()
                instrumentation.runOnMainSync {
                    fun inspect(view: View) {
                        if (view is ExperienceSurfaceHost) {
                            val flags = listOf("running", "attached", "firstFramePresented", "firstFrameComposed", "pendingPresentation")
                                .joinToString { name -> "$name=" + ExperienceSurfaceHost::class.java.getDeclaredField(name).apply { isAccessible = true }.get(view) }
                            states += "old=${view === first}, shown=${view.isShown}, $flags"
                        }
                        if (view is ViewGroup) (0 until view.childCount).forEach { inspect(view.getChildAt(it)) }
                    }
                    inspect((monitor.lastActivity ?: activity).window.decorView)
                }
                "The next screen did not present a native frame: $states"
            }
            lane(shown).call {
                val snapshot = checkNotNull(artboard(shown).defaultViewModelSnapshot())
                assertNotEquals(firstId, snapshot.nativeRootInstanceId)
                assertEquals(NuxieViewModelScalarValue.NumberValue(30.0), snapshot.resolveScalar(listOf("experience", "trip_days")))
            }
        } finally {
            service.profileDidClearAll()
            presentations.shutdownOwnedBy(owner)
            store.close()
            scope.cancel()
            instrumentation.removeMonitor(monitor)
            directory.deleteRecursively()
        }
    }

    private data class Fixture(val scene: ByteArray, val profile: JsonObject, val keys: Map<String, ByteArray>,
        val supported: JourneyReleaseSupportedRuntime, val authority: ProfileDeliveryAuthority)

    private fun fixture(): Fixture {
        val assets = InstrumentationRegistry.getInstrumentation().context.assets
        fun read(path: String) = assets.open(path).use { it.readBytes() }
        val entry = Json.parseToJsonElement(read("journeys/rendered-text-input/release-entry.json").decodeToString()).jsonObject
        val envelope = entry.getValue("envelope").jsonObject
        val original = Json.parseToJsonElement(Base64.decode(envelope.getValue("descriptorBytesBase64").jsonPrimitive.content, Base64.NO_WRAP).decodeToString()).jsonObject
        val scene = read("runtime/shared-values/screen.riv")
        val provenance = Json.parseToJsonElement(read("runtime/shared-values/provenance.json").decodeToString()).jsonObject
        fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        val names = listOf("first", "long", "short")
        val supported = checkNotNull(supportedRuntimeForEmbeddedRuntime(nuxieRuntimeSourceRevision()))
        val requirements = JsonObject(original.getValue("requirements").jsonObject + mapOf(
            "runtimeRevision" to JsonPrimitive(supported.supportedRuntimeRevisions.single()),
            "requiredCapabilities" to JsonArray(listOf("nux", "system-fonts").map(::JsonPrimitive))))
        fun navigate(id: String, screen: String) = buildJsonObject {
            put("id", id); put("kind", "action"); putJsonObject("action") { put("type", "navigate"); put("screenId", screen) }; putJsonObject("outlets") {}
        }
        val leg = JsonObject(original.getValue("leg").jsonObject + mapOf(
            "entryStepId" to JsonPrimitive("show"), "outputs" to JsonArray(emptyList()),
            "steps" to JsonArray(listOf(navigate("show", "first"), navigate("next", "long"))),
            "screens" to JsonArray(names.map { buildJsonObject {
                put("id", it); put("defaultViewModelName", "Runtime $it scr_screens_s$it"); put("defaultInstanceId", "$it-root"); putJsonArray("responseCaptures") {}
            } }),
            "routes" to buildJsonArray { addJsonObject { put("entryStepId", "next"); put("eventName", "continue"); putJsonObject("host") { put("kind", "screen"); put("screenId", "first") } } }))
        val descriptor = JsonObject(original + mapOf("leg" to leg, "requirements" to requirements,
            "viewModelValues" to JsonArray(emptyList()),
            "screenBehaviors" to JsonArray(names.map { buildJsonObject {
                put("screenId", it); putJsonArray("controls") {
                    if (it == "first") addJsonObject {
                        put("actionId", "scr_screens_sfirst::v3::on-click")
                        putJsonObject("behavior") { put("kind", "declarative"); putJsonArray("program") {
                            addJsonObject { put("type", "emit"); put("eventName", "continue"); putJsonObject("payload") {} }
                        } }
                    }
                }
            } }),
            "render" to buildJsonObject {
                put("renderer", "nux")
                putJsonObject("nux") { put("key", "renders/sha256/${hash(scene)}.nux"); put("sha256", hash(scene)); put("sizeBytes", scene.size); put("contentType", "application/vnd.nuxie.scene") }
                put("assets", JsonArray(provenance.getValue("fonts").jsonArray.map { JsonObject(it.jsonObject + ("kind" to JsonPrimitive("font"))) }))
                put("screens", JsonArray(names.map { buildJsonObject { put("id", it); put("artboardId", it); put("artboardName", it); put("width", 393); put("height", 852) } }))
                putJsonArray("transitions") {}; putJsonArray("textInputs") {}
            }))
        val bytes = descriptor.toString().encodeToByteArray()
        val alias = "shared-values-test-${UUID.randomUUID()}"
        val pair = KeyPairGenerator.getInstance("Ed25519", "AndroidKeyStore").run {
            initialize(android.security.keystore.KeyGenParameterSpec.Builder(alias,
                android.security.keystore.KeyProperties.PURPOSE_SIGN or android.security.keystore.KeyProperties.PURPOSE_VERIFY)
                .setAlgorithmParameterSpec(java.security.spec.ECGenParameterSpec("ed25519"))
                .setDigests(android.security.keystore.KeyProperties.DIGEST_NONE).build())
            generateKeyPair()
        }
        val signature = try {
            Signature.getInstance("Ed25519").run { initSign(pair.private); update(JourneyReleaseLimits.SIGNATURE_DOMAIN.encodeToByteArray() + bytes); sign() }
        } finally {
            java.security.KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(alias)
        }
        val signed = JsonObject(envelope + mapOf("descriptorSha256" to JsonPrimitive(hash(bytes)),
            "descriptorSizeBytes" to JsonPrimitive(bytes.size), "descriptorBytesBase64" to JsonPrimitive(Base64.encodeToString(bytes, Base64.NO_WRAP)),
            "signature" to buildJsonObject { put("version", 1); put("algorithm", "ed25519"); put("keyId", "TEST_ONLY_SHARED"); put("signatureBase64", Base64.encodeToString(signature, Base64.NO_WRAP)) }))
        val locator = entry.getValue("locator").jsonObject
        val profile = buildJsonObject {
            put("schemaVersion", "nuxie.journey-plane-profile.v2"); put("status", "ok")
            putJsonObject("delivery") { put("renderBaseUrl", "https://renders.example.test/"); put("assetBaseUrl", "https://assets.example.test/") }
            putJsonArray("features") {}; putJsonObject("facts") { putJsonObject("properties") {}; putJsonObject("memberships") {}; putJsonObject("assignments") {} }
            put("releases", JsonArray(listOf(JsonObject(entry + ("envelope" to signed)))))
            putJsonArray("armedLegs") { addJsonObject {
                putJsonObject("reference") { put("experienceId", locator.getValue("experienceId")); put("versionId", locator.getValue("experienceVersionId")); put("legId", locator.getValue("legId")); put("descriptorSha256", hash(bytes)) }
                putJsonObject("binding") { put("type", "new") }; putJsonObject("entryCondition") { put("type", "app_foregrounded") }
                putJsonObject("context") { putJsonObject("event") {}; putJsonObject("responses") {} }
            } }
        }
        return Fixture(scene, profile, mapOf("TEST_ONLY_SHARED" to pair.public.encoded.takeLast(32).toByteArray()), supported,
            ProfileDeliveryAuthority(locator.getValue("appId").jsonPrimitive.content, locator.getValue("environment").jsonPrimitive.content))
    }
}
