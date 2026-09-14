package ai.nuxie.sdk.presentation

import ai.nuxie.sdk.core.supportedRuntimeForEmbeddedRuntime
import ai.nuxie.sdk.experiences.AuthenticatedJourneyRelease
import ai.nuxie.sdk.experiences.AcquiredJourneyRelease
import java.io.Closeable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import ai.nuxie.sdk.experiences.JourneyReleaseIdentity
import ai.nuxie.sdk.experiences.JourneyReleaseReplayPolicy
import ai.nuxie.sdk.experiences.JourneyReleaseVerifier
import ai.nuxie.sdk.runtime.nuxieRuntimeSourceRevision
import ai.nuxie.sdk.runtime.NuxieRuntime
import ai.nuxie.sdk.events.SQLiteEventStore
import ai.nuxie.sdk.events.StoredEvent
import ai.nuxie.sdk.events.JsonValueConverter
import ai.nuxie.sdk.experiences.JourneyProfileCatalog
import ai.nuxie.sdk.experiences.JourneyReleaseHighWaterStore
import ai.nuxie.sdk.identity.IdentityProvider
import ai.nuxie.sdk.journey.JourneyService
import ai.nuxie.sdk.journey.JourneyRunJournal
import ai.nuxie.sdk.journey.JourneyStorageScope
import ai.nuxie.sdk.network.ProfileDeliveryAuthority
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import androidx.test.filters.SdkSuppress
import android.os.SystemClock
import android.util.Base64
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.float
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Real publisher bytes, signed defaults, runtime geometry and runtime text writer. */
class PublishedTextInputDeviceTest {
    @Test
    @SdkSuppress(minSdkVersion = 26)
    fun drawerClipsNativeContentAlongWithItsRenderedSurface() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        assertTrue(NuxieRuntime.shared.isAvailable)
        val fixture = loadPublishedFixture(instrumentation)
        val screen = fixture.release.descriptor.getValue("render").jsonObject
            .getValue("screens").jsonArray.first().jsonObject
        val cases = instrumentation.context.assets.open("journeys/planes/drawer-content-clipping.json")
            .bufferedReader().use { Json.parseToJsonElement(it.readText()).jsonObject.getValue("cases").jsonArray }
        for (item in cases.map { it.jsonObject }) {
            val radius = item.getValue("cornerRadius").jsonPrimitive.float
            val presentationId = UUID.randomUUID().toString()
            val firstFrame = CountDownLatch(1)
            val failure = AtomicReference<Throwable?>()
            val monitor = Instrumentation.ActivityMonitor(NuxieExperienceActivity::class.java.name, null, false)
            instrumentation.addMonitor(monitor)
            var activity: Activity? = null
            PresentationRegistry.register(presentationId, PreparedPresentation(
                fixture.riv, screen.getValue("artboardName").jsonPrimitive.content, 0xff000000.toInt(),
                PresentationShell.Drawer(PresentationShell.Drawer.Edge.BOTTOM,
                    item.getValue("extentRatio").jsonPrimitive.float, radius, true),
                screen.getValue("id").jsonPrimitive.content, fixture.release.descriptor, fixture.assets,
                ExperienceArtboardSize(screen.getValue("width").jsonPrimitive.float, screen.getValue("height").jsonPrimitive.float),
            ), onFirstFrame = { firstFrame.countDown() }, onFailure = { failure.set(it) },
                onDismissed = {}, onOutcome = {})
            try {
                context.startActivity(Intent(context, NuxieExperienceActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    putExtra(NuxieExperienceActivity.EXTRA_PRESENTATION_ID, presentationId)
                })
                activity = checkNotNull(monitor.waitForActivityWithTimeout(15_000))
                assertTrue("Runtime frame must arrive: ${failure.get()}", firstFrame.await(30, TimeUnit.SECONDS))
                val content = checkNotNull(findSurface(activity!!.window.decorView)).parent as ViewGroup
                val rect = Rect()
                instrumentation.runOnMainSync {
                    // A solid native child makes clipping observable independently of glyph shape.
                    // It occupies the same content parent as the real editable overlay.
                    content.addView(View(activity).apply { setBackgroundColor(android.graphics.Color.MAGENTA) },
                        ViewGroup.LayoutParams(-1, -1))
                    val position = IntArray(2)
                    content.getLocationInWindow(position)
                    rect.set(position[0], position[1], position[0] + content.width, position[1] + content.height)
                }
                instrumentation.waitForIdleSync()
                SystemClock.sleep(150)
                val bitmap = Bitmap.createBitmap(rect.width(), rect.height(), Bitmap.Config.ARGB_8888)
                val copied = CountDownLatch(1)
                var status = -1
                PixelCopy.request(activity!!.window, rect, bitmap,
                    { result -> status = result; copied.countDown() }, Handler(Looper.getMainLooper()))
                assertTrue(copied.await(5, TimeUnit.SECONDS))
                assertEquals(PixelCopy.SUCCESS, status)
                assertEquals("Native content must actually be drawn", android.graphics.Color.MAGENTA,
                    bitmap.getPixel(bitmap.width / 2, bitmap.height / 2))
                val cornerContainsContent = bitmap.getPixel(1, 1) == android.graphics.Color.MAGENTA
                assertEquals(item.getValue("name").jsonPrimitive.content,
                    item.getValue("cornerContainsContent").jsonPrimitive.content.toBooleanStrict(), cornerContainsContent)
                bitmap.recycle()
                assertEquals(null, failure.get())
            } finally {
                instrumentation.runOnMainSync { activity?.finish() }
                instrumentation.removeMonitor(monitor)
                PresentationRegistry.clearForTesting()
            }
        }
    }

    @Test
    @SdkSuppress(minSdkVersion = 26)
    fun publishedFieldReceivesNativeEditsThroughTheRuntimeHost() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        assertTrue("Embedded native runtime must load", NuxieRuntime.shared.isAvailable)
        val fixture = loadPublishedFixture(instrumentation)
        val release = fixture.release
        val riv = fixture.riv
        val assets = fixture.assets
        val render = release.descriptor.getValue("render").jsonObject
        val screen = render.getValue("screens").jsonArray.first().jsonObject
        val input = render.getValue("textInputs").jsonArray.single().jsonObject
        val inputId = input.getValue("id").jsonPrimitive.content
        val firstFrame = CountDownLatch(1)
        val failure = AtomicReference<Throwable?>()
        val commits = LinkedBlockingQueue<Pair<String, String>>()
        val presentationId = UUID.randomUUID().toString()
        val monitor = Instrumentation.ActivityMonitor(NuxieExperienceActivity::class.java.name, null, false)
        instrumentation.addMonitor(monitor)
        var activity: Activity? = null
        PresentationRegistry.register(presentationId, PreparedPresentation(
            riv, screen.getValue("artboardName").jsonPrimitive.content, 0xff000000.toInt(),
            PresentationShell.FullScreen, screen.getValue("id").jsonPrimitive.content,
            release.descriptor, assets,
            ExperienceArtboardSize(screen.getValue("width").jsonPrimitive.float, screen.getValue("height").jsonPrimitive.float),
        ), onFirstFrame = { firstFrame.countDown() }, onFailure = { failure.set(it) },
            onDismissed = {}, onOutcome = { if (it is CloseReason.Error) failure.set(it.cause) }, onTextCommitted = { id, text -> commits.add(id to text) })
        try {
            context.startActivity(Intent(context, NuxieExperienceActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                putExtra(NuxieExperienceActivity.EXTRA_PRESENTATION_ID, presentationId)
            })
            activity = checkNotNull(monitor.waitForActivityWithTimeout(15_000))
            assertTrue("Actual runtime frame must arrive: ${failure.get()}", firstFrame.await(30, TimeUnit.SECONDS))
            var editor: EditText? = null
            val deadline = SystemClock.elapsedRealtime() + 10_000
            while (editor == null && SystemClock.elapsedRealtime() < deadline) {
                instrumentation.runOnMainSync {
                    editor = activity!!.window.decorView.findViewWithTag<EditText>("nuxie-text-input-$inputId")
                        ?.takeIf { it.isShown && it.width > 1 && it.height > 1 }
                }
                if (editor == null) SystemClock.sleep(50)
            }
            val field = checkNotNull(editor) { "Signed default model must expose live field geometry: ${failure.get()}" }
            val surface = checkNotNull(findSurface(activity!!.window.decorView))
            instrumentation.runOnMainSync { field.clearFocus() }
            SystemClock.sleep(100)
            val original = copySurface(surface)
            val region = Rect()
            instrumentation.runOnMainSync {
                val fieldOrigin = IntArray(2)
                val surfaceOrigin = IntArray(2)
                field.getLocationOnScreen(fieldOrigin)
                surface.getLocationOnScreen(surfaceOrigin)
                region.set(fieldOrigin[0] - surfaceOrigin[0], fieldOrigin[1] - surfaceOrigin[1],
                    fieldOrigin[0] - surfaceOrigin[0] + field.width,
                    fieldOrigin[1] - surfaceOrigin[1] + field.height)
            }
            assertTrue("Geometry must place the native field inside the rendered surface", region.intersect(0, 0, original.width, original.height))
            commits.clear()
            edit(instrumentation, field, "")
            assertEquals(inputId to "", commits.poll(10, TimeUnit.SECONDS))
            var cleared = copySurface(surface)
            val clearedDeadline = SystemClock.elapsedRealtime() + 5_000
            while (changedPixels(original, cleared, region) < 20 && SystemClock.elapsedRealtime() < clearedDeadline) {
                cleared.recycle()
                SystemClock.sleep(50)
                cleared = copySurface(surface)
            }
            File(context.filesDir, "published-input-original.png").outputStream().use { original.compress(Bitmap.CompressFormat.PNG, 100, it) }
            File(context.filesDir, "published-input-cleared.png").outputStream().use { cleared.compress(Bitmap.CompressFormat.PNG, 100, it) }
            assertTrue("Clearing must remove Rive glyphs: region=$region changed=${changedPixels(original, cleared, region)}, full=${changedPixels(original, cleared, Rect(0, 0, original.width, original.height))}",
                changedPixels(original, cleared, region) >= 20)
            commits.clear()
            edit(instrumentation, field, input.getValue("value").jsonPrimitive.content)
            assertEquals(inputId to input.getValue("value").jsonPrimitive.content, commits.poll(10, TimeUnit.SECONDS))
            var restored = copySurface(surface)
            val restoreDeadline = SystemClock.elapsedRealtime() + 5_000
            while (changedPixels(original, restored, region) > 0 && SystemClock.elapsedRealtime() < restoreDeadline) {
                restored.recycle()
                SystemClock.sleep(50)
                restored = copySurface(surface)
            }
            assertEquals("Restoring the original value must restore the Rive glyph pixels", 0,
                changedPixels(original, restored, region))
            File(context.filesDir, "published-input-original.png").outputStream().use { original.compress(Bitmap.CompressFormat.PNG, 100, it) }
            File(context.filesDir, "published-input-cleared.png").outputStream().use { cleared.compress(Bitmap.CompressFormat.PNG, 100, it) }
            original.recycle()
            cleared.recycle()
            restored.recycle()
            commits.clear()
            val replacement = "native@example.com"
            instrumentation.runOnMainSync {
                assertEquals(input.getValue("value").jsonPrimitive.content, field.text.toString())
                assertTrue(field.requestFocus())
                field.selectAll()
                val connection = checkNotNull(field.onCreateInputConnection(EditorInfo()))
                assertTrue(connection.commitText(replacement, 1))
                field.clearFocus()
            }
            assertEquals(inputId to replacement, commits.poll(10, TimeUnit.SECONDS))
            assertEquals(null, failure.get())
        } finally {
            instrumentation.runOnMainSync { activity?.finish() }
            instrumentation.removeMonitor(monitor)
            PresentationRegistry.clearForTesting()
        }
    }

    @Test
    @SdkSuppress(minSdkVersion = 26)
    fun publishedResponseSurvivesPresentationNavigationWithoutDuplicateCommits() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        assertTrue(NuxieRuntime.shared.isAvailable)
        val fixture = loadPublishedFixture(instrumentation)
        val inputId = "text-input/screen_1/email_input"
        val owner = "published-input-owner"
        val journey = "published-input-journey"
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val batches = LinkedBlockingQueue<JourneyScreenEmissionBatch>()
        val screens = LinkedBlockingQueue<String>()
        val service = ExperiencePresentationService(instrumentation.targetContext, { _, _, _ -> }, scope,
            { NuxieRuntime.shared.isAvailable })
        val monitor = Instrumentation.ActivityMonitor(NuxieExperienceActivity::class.java.name, null, false)
        instrumentation.addMonitor(monitor)
        var nextBatch = 0L
        var nextEmission = 0L
        fun present(screenId: String): Activity {
            runBlocking {
                service.presentJourney(fixture.release, screenId, journey, owner, service.reserveJourney(owner),
                    acquire = { AcquiredJourneyRelease(fixture.release.identity, fixture.assets, fixture.riv,
                        protection = Closeable {}) },
                    nextBatchSequence = nextBatch, nextEmissionSequence = nextEmission,
                    onScreenChanged = { screens.add(it); true },
                    onEmissionBatch = { batch ->
                        batches.add(batch)
                        true
                    }, onOutcome = {})
            }
            assertEquals(screenId, screens.poll(5, TimeUnit.SECONDS))
            return checkNotNull(monitor.waitForActivityWithTimeout(10_000))
        }
        fun response(value: String) {
            val batch = checkNotNull(batches.poll(10, TimeUnit.SECONDS)) { "Native commit must reach the response coordinator" }
            assertEquals(journey, batch.journeyId)
            assertEquals(nextBatch++, batch.batchSequence)
            assertEquals(JourneyScreenEmissionSource("screen_1", "text_input:$inputId", inputId, null), batch.source)
            val emission = batch.emissions.single()
            assertEquals(nextEmission++, emission.sequence)
            assertEquals("\$response_set", emission.name)
            assertEquals("email", emission.payload.getValue("field").jsonPrimitive.content)
            assertEquals(value, emission.payload.getValue("value").jsonPrimitive.content)
        }
        try {
            val first = present("screen_1")
            val field = awaitEditor(instrumentation, first, inputId)
            edit(instrumentation, field, "saved@example.com")
            response("saved@example.com")
            instrumentation.runOnMainSync { field.setSelection(2, 7) }
            val second = present("screen_2")
            assertTrue("Navigation must replace the previous activity", first !== second)
            val returned = present("screen_1")
            val retained = awaitEditor(instrumentation, returned, inputId)
            instrumentation.runOnMainSync {
                assertEquals("saved@example.com", retained.text.toString())
                assertEquals(2, retained.selectionStart)
                assertEquals(7, retained.selectionEnd)
                assertTrue(retained.requestFocus())
                retained.clearFocus()
            }
            assertEquals("Unchanged retained text must not emit a second response", null, batches.poll(300, TimeUnit.MILLISECONDS))
            edit(instrumentation, retained, "next@example.com")
            response("next@example.com")
            runBlocking { service.shutdownOwnedBy(owner) }
            assertEquals(null, service.journeyScreenId(JourneyPresentationOwner(journey, owner)))
        } finally {
            runBlocking { service.shutdownOwnedBy(owner) }
            scope.cancel()
            instrumentation.removeMonitor(monitor)
            PresentationRegistry.clearForTesting()
        }
    }

    @Test
    @SdkSuppress(minSdkVersion = 26)
    fun nativeResponseIsDurableBeforeItsAuthoredJourneyNavigation() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        assertTrue(NuxieRuntime.shared.isAvailable)
        val fixture = loadPublishedFixture(instrumentation)
        val owner = "published-durable-${UUID.randomUUID()}"
        val directory = File(context.cacheDir, owner).apply { mkdirs() }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val store = SQLiteEventStore(context, databaseFile = File(directory, "events.db"))
        val presentations = ExperiencePresentationService(context, { _, _, _ -> }, scope, { NuxieRuntime.shared.isAvailable })
        val monitor = Instrumentation.ActivityMonitor(NuxieExperienceActivity::class.java.name, null, false)
        instrumentation.addMonitor(monitor)
        val accepted = LinkedBlockingQueue<JourneyScreenEmissionBatch>()
        val activeRequest = AtomicReference<JourneyPresentationRequest?>()
        val authority = ProfileDeliveryAuthority(fixture.release.identity.appId, fixture.release.identity.environment)
        val catalog = JourneyProfileCatalog(fixture.trustedKeys, JourneyReleaseHighWaterStore(context)) {
            supportedRuntimeForEmbeddedRuntime(nuxieRuntimeSourceRevision())
        }
        val presenter = object : JourneyPresenting {
            override fun reserve(ownerDistinctId: String) = presentations.reserveJourney(ownerDistinctId)
            override fun owns(owner: JourneyPresentationOwner) = presentations.ownsJourney(owner)
            override fun screenId(owner: JourneyPresentationOwner) = presentations.journeyScreenId(owner)
            override fun resolveAction(owner: JourneyPresentationOwner, action: JsonObject, source: JourneyScreenEmissionSource?) =
                presentations.resolveJourneyAction(owner, action, source)
            override suspend fun dispatchAction(owner: JourneyPresentationOwner, action: JsonObject, effectId: String) =
                presentations.dispatchJourneyAction(owner, action, effectId)
            override fun cancelBackNavigation(owner: JourneyPresentationOwner) = presentations.cancelBackNavigation(owner)
            override suspend fun shutdownOwnedBy(ownerDistinctId: String) = presentations.shutdownOwnedBy(ownerDistinctId)
            override suspend fun shutdownPresentation(ownerDistinctId: String, journeyId: String) = presentations.shutdownJourney(ownerDistinctId, journeyId)
            override suspend fun present(request: JourneyPresentationRequest): JourneyPresentationResult {
                activeRequest.set(request)
                presentations.presentJourney(request.release, request.screenId, request.journeyId,
                    request.ownerDistinctId, request.reservation, request.canPresent,
                    acquire = { AcquiredJourneyRelease(fixture.release.identity, fixture.assets, fixture.riv, protection = Closeable {}) },
                    nextBatchSequence = request.nextBatchSequence, nextEmissionSequence = request.nextEmissionSequence,
                    onScreenChanged = request.onScreenChanged, onScreenDismissed = request.onScreenDismissed,
                    onEmissionBatch = { batch ->
                        val committed = request.onEmissionBatch(batch)
                        if (committed) accepted.add(batch)
                        committed
                    }, onPresentationRevealed = request.onPresentationRevealed, onOutcome = request.onOutcome)
                return JourneyPresentationResult.Shown
            }
        }
        val identity = object : IdentityProvider {
            override fun distinctId() = owner
            override fun anonymousId() = owner
            override fun rawDistinctId(): String? = null
            override val isIdentified = false
        }
        val journeys = JourneyService(identity, store, catalog, directory, scope,
            capture = { name, properties, eventId, distinctId ->
                store.insertPendingIfAbsent(StoredEvent(eventId, name, JsonValueConverter.fromMap(properties),
                    System.currentTimeMillis(), distinctId))
            }, presenter = presenter)
        fun journalRun() = JourneyRunJournal(directory, owner, JourneyStorageScope(authority)).runs().single()
        try {
            runBlocking {
                catalog.commit(owner, catalog.prepare(profileFor(fixture.entry), authority))
                journeys.initialize()
                journeys.onAppWillEnterForeground()
                journeys.profileDidCommit(checkNotNull(catalog.snapshot(owner)), authority, owner, 1)
            }
            val first = checkNotNull(monitor.waitForActivityWithTimeout(10_000))
            val field = awaitEditor(instrumentation, first, "text-input/screen_1/email_input")
            edit(instrumentation, field, "durable@example.com")
            val batch = checkNotNull(accepted.poll(10, TimeUnit.SECONDS)) { "Journey service must durably accept native input" }
            val reopened = journalRun()
            assertEquals("durable@example.com", reopened.context.getValue("responses").jsonObject.getValue("email").jsonPrimitive.content)
            assertEquals(batch.batchSequence + 1, reopened.nextPresentationBatchSequence)
            assertEquals(batch.emissions.last().sequence + 1, reopened.nextPresentationEmissionSequence)
            // A screen-scoped route is activated by that screen's emission
            // callback, never by an unrelated global customer event.
            val screenRequest = checkNotNull(activeRequest.get())
            runBlocking {
                assertTrue(screenRequest.onEmissionBatch(JourneyScreenEmissionBatch(
                    reopened.journeyId, reopened.nextPresentationBatchSequence,
                    UUID.randomUUID().toString(), JourneyScreenEmissionSource("screen_1", "corpus-continue"),
                    listOf(JourneyScreenEmission(UUID.randomUUID().toString(), reopened.nextPresentationEmissionSequence,
                        System.currentTimeMillis(), "corpus_next_0", JsonObject(emptyMap()))),
                )))
            }
            val second = checkNotNull(monitor.waitForActivityWithTimeout(10_000))
            assertTrue(first !== second)
            assertEquals("screen_2", presentations.journeyScreenId(JourneyPresentationOwner(reopened.journeyId, owner)))
            assertEquals("durable@example.com", journalRun().context.getValue("responses").jsonObject.getValue("email").jsonPrimitive.content)
        } finally {
            runBlocking {
                presentations.shutdownOwnedBy(owner)
                scope.coroutineContext[Job]?.cancelAndJoin()
                store.close()
            }
            instrumentation.removeMonitor(monitor)
            PresentationRegistry.clearForTesting()
        }
    }

    private fun profileFor(entry: JsonObject): JsonObject {
        val locator = entry.getValue("locator").jsonObject
        val envelope = entry.getValue("envelope").jsonObject
        fun obj(vararg fields: Pair<String, kotlinx.serialization.json.JsonElement>) = JsonObject(mapOf(*fields))
        val empty = JsonObject(emptyMap())
        return obj(
            "schemaVersion" to JsonPrimitive("nuxie.journey-plane-profile.v1"), "status" to JsonPrimitive("ok"),
            "delivery" to obj("renderBaseUrl" to JsonPrimitive("https://renders.example.com/"), "assetBaseUrl" to JsonPrimitive("https://assets.example.com/")),
            "features" to JsonArray(emptyList()), "facts" to obj("properties" to empty, "memberships" to empty, "assignments" to empty),
            "releases" to JsonArray(listOf(entry)),
            "armedLegs" to JsonArray(listOf(obj(
                "reference" to obj("experienceId" to locator.getValue("experienceId"), "versionId" to locator.getValue("experienceVersionId"),
                    "legId" to locator.getValue("legId"), "descriptorSha256" to envelope.getValue("descriptorSha256")),
                "binding" to obj("type" to JsonPrimitive("new")),
                "entryCondition" to obj("type" to JsonPrimitive("app_foregrounded")),
                "context" to obj("event" to empty, "responses" to empty),
            ))),
        )
    }

    private fun awaitEditor(instrumentation: Instrumentation, activity: Activity, inputId: String): EditText {
        var editor: EditText? = null
        val deadline = SystemClock.elapsedRealtime() + 10_000
        while (editor == null && SystemClock.elapsedRealtime() < deadline) {
            instrumentation.runOnMainSync {
                editor = activity.window.decorView.findViewWithTag<EditText>("nuxie-text-input-$inputId")
                    ?.takeIf { it.isShown && it.width > 1 && it.height > 1 }
            }
            if (editor == null) SystemClock.sleep(50)
        }
        return checkNotNull(editor) { "Published input must obtain visible live geometry" }
    }

    private data class PublishedFixture(
        val release: AuthenticatedJourneyRelease,
        val riv: File,
        val assets: Map<String, File>,
        val entry: JsonObject,
        val trustedKeys: Map<String, ByteArray>,
    )

    private fun loadPublishedFixture(instrumentation: Instrumentation): PublishedFixture {
        val context = instrumentation.targetContext
        val fixture = "journeys/rendered-text-input"
        fun read(path: String) = instrumentation.context.assets.open("$fixture/$path").use { it.readBytes() }
        fun objectAt(path: String) = Json.parseToJsonElement(read(path).decodeToString()).jsonObject
        val entry = objectAt("release-entry.json")
        val envelope = entry.getValue("envelope").jsonObject
        val locator = entry.getValue("locator").jsonObject
        val provenance = objectAt("provenance.json")
        val keyId = envelope.getValue("signature").jsonObject.getValue("keyId").jsonPrimitive.content
        val trustedKeys = mapOf(keyId to Base64.decode(provenance.getValue("publicKeyBase64").jsonPrimitive.content, Base64.NO_WRAP))
        val release = JourneyReleaseVerifier.authenticate(
            envelope.toString().toByteArray(),
            trustedKeys,
            checkNotNull(JourneyReleaseIdentity.fromJson(locator, setOf("legId"))),
            locator.getValue("legId").jsonPrimitive.content,
            checkNotNull(supportedRuntimeForEmbeddedRuntime(nuxieRuntimeSourceRevision())),
            JourneyReleaseReplayPolicy.Active(0),
        )
        assertEquals(provenance.getValue("descriptorSha256").jsonPrimitive.content, release.descriptorSha256)
        val directory = File(context.cacheDir, "published-input-${UUID.randomUUID()}").apply { mkdirs() }
        fun stage(artifact: JsonObject): Pair<String, File> {
            val key = artifact.getValue("key").jsonPrimitive.content
            val bytes = read(key)
            assertEquals(artifact.getValue("sizeBytes").jsonPrimitive.long, bytes.size.toLong())
            assertEquals(artifact.getValue("sha256").jsonPrimitive.content,
                MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) })
            val file = File(directory, key).apply { parentFile!!.mkdirs(); writeBytes(bytes) }
            return key to file
        }
        val render = release.descriptor.getValue("render").jsonObject
        val riv = stage(render.getValue("riv").jsonObject).second
        val assets = render.getValue("assets").jsonArray.associate { stage(it.jsonObject) }
        return PublishedFixture(release, riv, assets, entry, trustedKeys)
    }

    private fun edit(instrumentation: Instrumentation, field: EditText, value: String) {
        instrumentation.runOnMainSync {
            assertTrue(field.requestFocus())
            field.selectAll()
            assertTrue(checkNotNull(field.onCreateInputConnection(EditorInfo())).commitText(value, 1))
            field.clearFocus()
        }
    }

    private fun findSurface(view: View): SurfaceView? {
        if (view is SurfaceView) return view
        if (view is ViewGroup) for (index in 0 until view.childCount) {
            findSurface(view.getChildAt(index))?.let { return it }
        }
        return null
    }

    private fun copySurface(surface: SurfaceView): Bitmap {
        val bitmap = Bitmap.createBitmap(surface.width, surface.height, Bitmap.Config.ARGB_8888)
        val done = CountDownLatch(1)
        var status = -1
        PixelCopy.request(surface, bitmap, { result -> status = result; done.countDown() }, Handler(Looper.getMainLooper()))
        assertTrue("Surface pixel copy must finish", done.await(5, TimeUnit.SECONDS))
        assertEquals("Surface pixel copy must succeed", PixelCopy.SUCCESS, status)
        return bitmap
    }

    private fun changedPixels(before: Bitmap, after: Bitmap, region: Rect): Int {
        var changed = 0
        for (y in region.top until region.bottom) for (x in region.left until region.right) {
            if (before.getPixel(x, y) != after.getPixel(x, y)) changed++
        }
        return changed
    }

}
