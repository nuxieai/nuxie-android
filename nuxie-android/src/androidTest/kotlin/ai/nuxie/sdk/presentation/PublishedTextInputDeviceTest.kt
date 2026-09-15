package ai.nuxie.sdk.presentation

import ai.nuxie.sdk.experiences.ExperienceAssetImportBuilder
import ai.nuxie.sdk.experiences.ExperienceViewModelBinding
import ai.nuxie.sdk.runtime.NuxieViewModelSnapshot
import ai.nuxie.sdk.runtime.NuxieViewModelScalarValue
import ai.nuxie.sdk.runtime.NuxieRuntimeLane
import ai.nuxie.sdk.runtime.NativeCallResult
import ai.nuxie.sdk.runtime.JniNuxieTypedRuntimeNative
import ai.nuxie.sdk.runtime.NuxieTypedRuntimeNative
import ai.nuxie.sdk.core.supportedRuntimeForEmbeddedRuntime
import ai.nuxie.sdk.experiences.AuthenticatedJourneyRelease
import ai.nuxie.sdk.experiences.AcquiredJourneyRelease
import java.io.Closeable
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
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
import android.app.Application
import android.accessibilityservice.AccessibilityService
import android.os.Bundle
import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import android.view.TextureView
import android.view.View
import android.view.MotionEvent
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
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.float
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.assertSame
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNull
import org.junit.Test

/** Real publisher bytes, signed defaults, runtime geometry and runtime text writer. */
class PublishedTextInputDeviceTest {
    @Test
    @SdkSuppress(minSdkVersion = 26)
    fun signedPublishedCustomTransitionEmitsForwardAndReverseEvents() {
        assertTrue(NuxieRuntime.shared.isAvailable)
        val fixture = loadPublishedFixture(InstrumentationRegistry.getInstrumentation(), "journeys/rendered-custom-transition")
        val render = fixture.release.descriptor.getValue("render").jsonObject
        val screens = render.getValue("screens").jsonArray.map { it.jsonObject }
        val declaration = render.getValue("transitions").jsonArray.single().jsonObject
        val transitionId = declaration.getValue("id").jsonPrimitive.content
        val runtime = NuxieRuntime.shared
        val lane = NuxieRuntimeLane()
        try {
            runBlocking { lane.call {
                val renderer = checkNotNull(runtime.newAndroidVulkanRenderer(390, 844))
                try {
                    val bytes = fixture.riv.readBytes()
                    val assets = ExperienceAssetImportBuilder.build(fixture.release.descriptor, fixture.assets,
                        checkNotNull(runtime.inspectFileAssets(bytes)))
                    val file = checkNotNull(runtime.importFile(renderer, bytes, assets.expectedAssets, assets.externalAssets))
                    try {
                        for ((index, screen) in screens.withIndex()) {
                            val name = screen.getValue("artboardName").jsonPrimitive.content
                            val artboard = checkNotNull(file.newArtboard(name))
                            try {
                                artboard.bindDefaultViewModel(checkNotNull(ExperienceViewModelBinding.defaultSchemaName(fixture.release.descriptor, name)))
                                fun write(path: String, value: String) {
                                    assertTrue("Native transition state must accept $path=$value",
                                        artboard.setDefaultViewModelValue(path, NuxieViewModelScalarValue.StringValue(value)))
                                }
                                write("screen/phase", "entering")
                                val player = checkNotNull(artboard.newPlayer())
                                try {
                                    repeat(10) { player.stepWithEvents(0.05) }
                                    for (reverse in listOf(true, false, true, false)) {
                                        val outgoing = if (reverse) index == 1 else index == 0
                                        write("screen/transition", "")
                                        write("screen/phase", if (outgoing) "active" else "entering")
                                        player.stepWithEvents(0.0)
                                        write("screen/phase", if (outgoing) "exiting" else "entering")
                                        write("screen/transition", transitionId)
                                        val edge = if (reverse) declaration.getValue("reverse").jsonObject else declaration
                                        val endpoint = if (outgoing) "source" else "destination"
                                        val expected = edge.getValue(endpoint).jsonObject.getValue("completeEventName").jsonPrimitive.content
                                        val events = mutableListOf<String>()
                                        var steps = 0
                                        while (expected !in events && steps < 14) {
                                            events += player.stepWithEvents(0.05).events.map { it.name }
                                            steps++
                                        }
                                        assertEquals("Native $name reverse=$reverse steps=$steps events=$events", 1, events.count { it == expected })
                                        assertTrue("Native completion must precede the 700ms watchdog", steps < 14)
                                        val held = mutableListOf<String>()
                                        repeat(20) { held += player.stepWithEvents(0.05).events.map { it.name } }
                                        assertFalse("Completion must remain latched while its start conditions hold: $held", expected in held)
                                        write("screen/phase", if (outgoing) "hidden" else "active")
                                        write("screen/transition", "")
                                        val idle = mutableListOf<String>()
                                        repeat(20) { idle += player.stepWithEvents(0.05).events.map { it.name } }
                                        assertFalse("Completion must not replay after the SDK leaves its transition phase: $idle", expected in idle)

                                        write("screen/phase", if (outgoing) "exiting" else "entering")
                                        write("screen/transition", transitionId)
                                        val interrupted = mutableListOf<String>()
                                        repeat(2) { interrupted += player.stepWithEvents(0.05).events.map { it.name } }
                                        write("screen/phase", if (outgoing) "active" else "hidden")
                                        write("screen/transition", "")
                                        repeat(20) { interrupted += player.stepWithEvents(0.05).events.map { it.name } }
                                        assertFalse("An interrupted transition must not emit completion: $interrupted", expected in interrupted)
                                    }
                                } finally { player.close() }
                            } finally { artboard.close() }
                        }
                    } finally { file.close() }
                } finally { renderer.close() }
            } }
        } finally { lane.shutdown(); assertTrue(lane.awaitQuiescence(5_000)) }
    }

    @Test
    @SdkSuppress(minSdkVersion = 26)
    fun renderedResourceDestructionCanOverlapRendererCreation() = exerciseRendererOverlap(false)

    @Test
    @SdkSuppress(minSdkVersion = 26)
    fun activeRenderingCanOverlapRendererCreation() = exerciseRendererOverlap(true)

    @Test
    @SdkSuppress(minSdkVersion = 26)
    fun activeRenderingCanOverlapRendererCreationAndResize() = exerciseRendererOverlap(true, true)

    private fun exerciseRendererOverlap(keepRendering: Boolean, startSmall: Boolean = false) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val runtime = NuxieRuntime.shared
        assertTrue(runtime.isAvailable)
        val fixture = loadPublishedFixture(instrumentation)
        val screen = fixture.release.descriptor.getValue("render").jsonObject
            .getValue("screens").jsonArray.first().jsonObject
        val artboardName = screen.getValue("artboardName").jsonPrimitive.content
        val schema = checkNotNull(ExperienceViewModelBinding.defaultSchemaName(fixture.release.descriptor, artboardName))
        val bytes = fixture.riv.readBytes()
        val assets = ExperienceAssetImportBuilder.build(fixture.release.descriptor, fixture.assets,
            checkNotNull(runtime.inspectFileAssets(bytes)))
        class RenderedResources(val render: () -> Unit, val cleanup: () -> Unit) : Closeable {
            override fun close() = cleanup()
        }
        fun createRenderedResources(): RenderedResources {
            val cleanup = mutableListOf<() -> Unit>()
            try {
                val renderer = checkNotNull(runtime.newAndroidVulkanRenderer(
                    if (startSmall) 1 else 1080, if (startSmall) 1 else 2400,
                ))
                cleanup += renderer::close
                val file = checkNotNull(runtime.importFile(renderer, bytes, assets.expectedAssets, assets.externalAssets))
                cleanup += file::close
                val artboard = checkNotNull(file.newArtboard(artboardName))
                cleanup += artboard::close
                artboard.bindDefaultViewModel(schema)
                val player = checkNotNull(artboard.newPlayer())
                cleanup += player::close
                if (startSmall) assertEquals(0, renderer.resize(1080, 2400))
                player.stepWithEvents(0.0)
                val frame = renderer.renderToCpuFrame(player, 0xff000000.toInt(), true)
                assertEquals(1080 * 2400 * 4, frame.rgba.size)
                assertTrue("Published content must produce non-background pixels", frame.rgba.indices.any {
                    it % 4 != 3 && frame.rgba[it] != 0.toByte()
                })
                return RenderedResources(
                    render = {
                        player.stepWithEvents(1.0 / 60.0)
                        val next = renderer.renderToCpuFrame(player, 0xff000000.toInt(), true)
                        assertEquals(1080 * 2400 * 4, next.rgba.size)
                    },
                    cleanup = { cleanup.asReversed().forEach { it() } },
                )
            } catch (error: Throwable) {
                cleanup.asReversed().forEach { close -> runCatching(close).exceptionOrNull()?.let(error::addSuppressed) }
                throw error
            }
        }
        val lanes = List(2) { NuxieRuntimeLane() }
        val rendezvous = java.util.concurrent.CyclicBarrier(2)
        val finished = CountDownLatch(2)
        val failure = AtomicReference<Throwable?>()
        val created = java.util.concurrent.atomic.AtomicInteger()
        lanes.forEachIndexed { index, lane ->
            lane.enqueue {
                var resources: RenderedResources? = null
                try {
                    if (index == 0) { resources = createRenderedResources(); created.incrementAndGet() }
                    repeat(if (keepRendering) 100 else 20) {
                        rendezvous.await(30, TimeUnit.SECONDS)
                        if (keepRendering && index == 0) checkNotNull(resources).render()
                        else if (resources == null) { resources = createRenderedResources(); created.incrementAndGet() }
                        else { resources?.close(); resources = null }
                        rendezvous.await(30, TimeUnit.SECONDS)
                    }
                } catch (error: Throwable) { failure.compareAndSet(null, error) }
                finally {
                    try { resources?.close() } finally { finished.countDown() }
                }
            }
        }
        try {
            val completed = finished.await(45, TimeUnit.SECONDS)
            if (!completed) android.os.Process.sendSignal(android.os.Process.myPid(), 3)
            assertTrue("Rendered resource lifetimes must drain; created=${created.get()} failure=${failure.get()}", completed)
            assertEquals(null, failure.get())
            assertEquals(if (keepRendering) 51 else 21, created.get())
        } finally { lanes.forEach { it.shutdown() } }
    }

    @Test
    fun publishedDefaultAcceptsLifecycleAndSafeAreaStateInNativeRuntime() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        assertTrue(NuxieRuntime.shared.isAvailable)
        val fixture = loadPublishedFixture(instrumentation)
        val screen = fixture.release.descriptor.getValue("render").jsonObject
            .getValue("screens").jsonArray.first().jsonObject
        val artboardName = screen.getValue("artboardName").jsonPrimitive.content
        val schema = checkNotNull(ExperienceViewModelBinding
            .defaultSchemaName(fixture.release.descriptor, artboardName))
        var root = 0L
        var defaultsCreated = 0
        val native = object : NuxieTypedRuntimeNative by JniNuxieTypedRuntimeNative {
            override fun newDefaultViewModel(artboardHandle: Long): NativeCallResult<Long> {
                defaultsCreated++
                return JniNuxieTypedRuntimeNative.newDefaultViewModel(artboardHandle)
                    .also { root = checkNotNull(it.value) }
            }
        }
        val runtime = NuxieRuntime(native)
        val lane = NuxieRuntimeLane()
        try {
            runBlocking { lane.call {
                val renderer = checkNotNull(runtime.newAndroidVulkanRenderer(390, 844))
                try {
                    val bytes = fixture.riv.readBytes()
                    val assets = ExperienceAssetImportBuilder.build(fixture.release.descriptor,
                        fixture.assets, checkNotNull(runtime.inspectFileAssets(bytes)))
                    val file = checkNotNull(runtime.importFile(renderer, bytes, assets.expectedAssets, assets.externalAssets))
                    try {
                        val artboard = checkNotNull(file.newArtboard(artboardName))
                        try {
                            artboard.bindDefaultViewModel(schema)
                            val player = checkNotNull(artboard.newPlayer())
                            try {
                                fun verify(values: Map<String, NuxieViewModelScalarValue>) {
                                    values.forEach { (path, value) -> assertTrue(artboard.setDefaultViewModelValue(path, value)) }
                                    val result = native.snapshotViewModel(root)
                                    assertEquals(0, result.status)
                                    val snapshot = checkNotNull(result.value)
                                    for ((path, expected) in values) {
                                        var owner = snapshot.rootInstanceId
                                        val segments = path.split('/')
                                        for (segment in segments.dropLast(1)) {
                                            owner = snapshot.values.single { it.ownerInstanceId == owner && it.name == segment }.referencedInstanceId
                                        }
                                        val actual = snapshot.values.single { it.ownerInstanceId == owner && it.name == segments.last() }
                                        when (expected) {
                                            is NuxieViewModelScalarValue.StringValue -> {
                                                if (actual.kind == 5) {
                                                    val catalog = checkNotNull(native.viewModelCatalog(file.requireHandle()).value)
                                                    val instance = snapshot.instances.single { it.id == owner }
                                                    val property = catalog.properties.single { it.schemaIndex == instance.schemaIndex && it.index == actual.propertyIndex }
                                                    assertEquals(path, expected.value, property.enumLabels[actual.integerValue.toInt()])
                                                    assertEquals(actual.integerValue, NuxieViewModelSnapshot.fromNative(snapshot).resolveEnumOrdinal(path))
                                                } else {
                                                    assertEquals(1, actual.kind)
                                                    assertEquals(path, expected.value, actual.bytesValue.decodeToString())
                                                }
                                            }
                                            is NuxieViewModelScalarValue.NumberValue -> assertEquals(path, expected.value.toFloat(), actual.numberValue, 0.00001f)
                                            is NuxieViewModelScalarValue.BooleanValue -> {
                                                assertEquals(3, actual.kind)
                                                assertEquals(path, expected.value, actual.boolValue)
                                                assertEquals(expected.value, NuxieViewModelSnapshot.fromNative(snapshot).resolveBoolean(path))
                                            }
                                        }
                                    }
                                }
                                val lifecycle = ExperienceScreenLifecycle()
                                verify(lifecycle.move(ExperienceScreenLifecycle.Phase.ENTERING))
                                verify(lifecycle.move(ExperienceScreenLifecycle.Phase.ACTIVE))
                                verify(lifecycle.updateReduceMotion(true))
                                verify(ExperienceSafeAreaInsets(24.5, 12.0, 3.0, 7.0).stateValues())
                                verify(lifecycle.move(ExperienceScreenLifecycle.Phase.EXITING, "checkout"))
                                verify(lifecycle.move(ExperienceScreenLifecycle.Phase.HIDDEN))
                                verify(lifecycle.move(ExperienceScreenLifecycle.Phase.ENTERING, "return"))
                                assertEquals(1, defaultsCreated)
                            } finally { player.close() }
                        } finally { artboard.close() }
                    } finally { file.close() }
                } finally { renderer.close() }
            } }
        } finally {
            lane.shutdown()
            assertTrue(lane.awaitQuiescence(5_000))
        }
    }

    @Test
    @SdkSuppress(minSdkVersion = 26)
    fun closingBeforeFirstFrameDrainsWithoutActivatingScreen() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val closeContract = instrumentation.context.assets.open("journeys/planes/presentation-reveal-android.json")
            .bufferedReader().use { Json.parseToJsonElement(it.readText()).jsonObject.getValue("unseenClose").jsonObject }
        assertTrue(NuxieRuntime.shared.isAvailable)
        val fixture = loadPublishedFixture(instrumentation)
        val screen = fixture.release.descriptor.getValue("render").jsonObject
            .getValue("screens").jsonArray.first().jsonObject
        val id = UUID.randomUUID().toString()
        val closed = CountDownLatch(1)
        val created = CountDownLatch(1)
        val frames = java.util.concurrent.atomic.AtomicInteger()
        val failures = AtomicReference<Throwable?>()
        val target = AtomicReference<Activity?>()
        val prepared = PreparedPresentation(fixture.riv, screen.getValue("artboardName").jsonPrimitive.content,
            0xff000000.toInt(), PresentationShell.Sheet(PresentationShell.Sheet.Detent.MEDIUM, false), screen.getValue("id").jsonPrimitive.content,
            fixture.release.descriptor, fixture.assets,
            ExperienceArtboardSize(screen.getValue("width").jsonPrimitive.float, screen.getValue("height").jsonPrimitive.float))
        val application = context.applicationContext as Application
        val callbacks = object : Application.ActivityLifecycleCallbacks {
            override fun onActivityStarted(activity: Activity) {
                if (activity.intent.getStringExtra(NuxieExperienceActivity.EXTRA_PRESENTATION_ID) != id) return
                target.set(activity)
                assertEquals(ExperienceScreenLifecycle.Phase.ENTERING, prepared.screenLifecycle.phase)
                created.countDown()
                @Suppress("DEPRECATION")
                activity.onBackPressed()
                assertEquals(closeContract.getValue("loadingAllowsBack").jsonPrimitive.boolean, activity.isFinishing)
            }
            override fun onActivityCreated(activity: Activity, state: Bundle?) = Unit
            override fun onActivityResumed(activity: Activity) = Unit
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivityStopped(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        }
        application.registerActivityLifecycleCallbacks(callbacks)
        PresentationRegistry.register(id, prepared, onFirstFrame = { frames.incrementAndGet() },
            onFailure = { failures.set(it); closed.countDown() }, onDismissed = { closed.countDown() }, onOutcome = {})
        try {
            context.startActivity(Intent(context, NuxieExperienceActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                putExtra(NuxieExperienceActivity.EXTRA_PRESENTATION_ID, id)
            })
            assertTrue(created.await(15, TimeUnit.SECONDS))
            assertTrue("Close must join native cleanup", closed.await(15, TimeUnit.SECONDS))
            instrumentation.runOnMainSync {
                assertEquals(ExperienceScreenLifecycle.Phase.HIDDEN, prepared.screenLifecycle.phase)
                assertEquals(1uL, prepared.screenLifecycle.appearances)
            }
            assertEquals(0, frames.get())
            assertEquals(null, failures.get())
            assertEquals(null, PresentationRegistry.resolve(id))
        } finally {
            application.unregisterActivityLifecycleCallbacks(callbacks)
            instrumentation.runOnMainSync { target.get()?.finish() }
            PresentationRegistry.clearForTesting()
        }
    }

    @Test
    @SdkSuppress(minSdkVersion = 26)
    fun backgroundAndRecreationPreserveScreenAppearanceAndRenderedContent() {
        repeat(5) { iteration ->
            try {
                exerciseBackgroundAndRecreation()
            } catch (error: AssertionError) {
                throw AssertionError("Recreation cycle ${iteration + 1}: ${error.message}", error)
            }
        }
    }

    @Test
    @SdkSuppress(minSdkVersion = 26)
    fun rotationRestoresPublishedContentWithoutAnotherAppearance() {
        exerciseBackgroundAndRecreation(rotate = true)
    }

    private fun exerciseBackgroundAndRecreation(rotate: Boolean = false) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        assertTrue(NuxieRuntime.shared.isAvailable)
        val fixture = loadPublishedFixture(instrumentation)
        val screen = fixture.release.descriptor.getValue("render").jsonObject
            .getValue("screens").jsonArray.first().jsonObject
        val id = UUID.randomUUID().toString()
        val firstFrame = CountDownLatch(1)
        val dismissed = CountDownLatch(1)
        val failure = AtomicReference<Throwable?>()
        val prepared = PreparedPresentation(fixture.riv, screen.getValue("artboardName").jsonPrimitive.content,
            0xff000000.toInt(), PresentationShell.FullScreen, screen.getValue("id").jsonPrimitive.content,
            fixture.release.descriptor, fixture.assets,
            ExperienceArtboardSize(screen.getValue("width").jsonPrimitive.float, screen.getValue("height").jsonPrimitive.float))
        var monitor = Instrumentation.ActivityMonitor(NuxieExperienceActivity::class.java.name, null, false)
        instrumentation.addMonitor(monitor)
        var activity: Activity? = null
        var before: Bitmap? = null
        var after: Bitmap? = null
        PresentationRegistry.register(id, prepared, onFirstFrame = { approveFixtureFrame(id); firstFrame.countDown() },
            onFailure = { failure.set(it) }, onDismissed = { dismissed.countDown() }, onOutcome = {})
        try {
            context.startActivity(Intent(context, NuxieExperienceActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                putExtra(NuxieExperienceActivity.EXTRA_PRESENTATION_ID, id)
            })
            val original = checkNotNull(monitor.waitForActivityWithTimeout(15_000))
            activity = original
            instrumentation.runOnMainSync {
                // This corpus compares identical portrait extents before/after Home.
                // Do not inherit another app's transient display orientation.
                original.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            }
            assertTrue("Initial frame: ${failure.get()}", firstFrame.await(30, TimeUnit.SECONDS))
            instrumentation.waitForIdleSync()
            SystemClock.sleep(150)
            val portraitDeadline = SystemClock.elapsedRealtime() + 10_000
            var initial = copySurface(checkNotNull(findSurface(original.window.decorView)))
            while (initial.width >= initial.height && SystemClock.elapsedRealtime() < portraitDeadline) {
                initial.recycle()
                SystemClock.sleep(50)
                initial = copySurface(checkNotNull(findSurface(original.window.decorView)))
            }
            assertTrue("Initial portrait frame must be established", initial.height > initial.width)
            before = initial
            val stopped = CountDownLatch(1)
            val resumed = CountDownLatch(1)
            val application = original.application
            val callbacks = object : Application.ActivityLifecycleCallbacks {
                override fun onActivityStopped(activity: Activity) { if (activity === original) stopped.countDown() }
                override fun onActivityResumed(activity: Activity) { if (activity === original) resumed.countDown() }
                override fun onActivityCreated(activity: Activity, state: Bundle?) = Unit
                override fun onActivityStarted(activity: Activity) = Unit
                override fun onActivityPaused(activity: Activity) = Unit
                override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) = Unit
                override fun onActivityDestroyed(activity: Activity) = Unit
            }
            application.registerActivityLifecycleCallbacks(callbacks)
            try {
                assertTrue(instrumentation.uiAutomation.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME))
                assertTrue("System Home must stop the original Activity", stopped.await(10, TimeUnit.SECONDS))
                instrumentation.runOnMainSync {
                    assertEquals(ExperienceScreenLifecycle.Phase.ACTIVE, prepared.screenLifecycle.phase)
                    assertEquals(1uL, prepared.screenLifecycle.appearances)
                }
                context.startActivity(Intent(context, NuxieExperienceActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    putExtra(NuxieExperienceActivity.EXTRA_PRESENTATION_ID, id)
                })
                assertTrue("Return must resume the same Activity", resumed.await(10, TimeUnit.SECONDS))
                instrumentation.waitForIdleSync()
                SystemClock.sleep(150)
                val returned = copySurfaceAtSize(checkNotNull(findSurface(original.window.decorView)), before.width, before.height)
                try {
                    assertEquals(0, changedPixels(before, returned, Rect(0, 0, before.width, before.height)))
                } finally { returned.recycle() }
            } finally { application.unregisterActivityLifecycleCallbacks(callbacks) }
            instrumentation.removeMonitor(monitor)
            monitor = Instrumentation.ActivityMonitor(NuxieExperienceActivity::class.java.name, null, false)
            instrumentation.addMonitor(monitor)
            if (rotate) {
                assertTrue("Rotation probe starts in portrait", before.height > before.width)
                instrumentation.runOnMainSync {
                    original.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
                }
                val landscape = original
                activity = landscape
                instrumentation.waitForIdleSync()
                val landscapeSurface = checkNotNull(findSurface(landscape.window.decorView))
                val landscapeDeadline = SystemClock.uptimeMillis() + 15_000
                var landscapeRendered = false
                while (!landscapeRendered && SystemClock.uptimeMillis() < landscapeDeadline) {
                    val pixels = copySurface(landscapeSurface)
                    try {
                        val colors = IntArray(pixels.width * pixels.height)
                        pixels.getPixels(colors, 0, pixels.width, 0, 0, pixels.width, pixels.height)
                        landscapeRendered = pixels.width > pixels.height && colors.toSet().size > 8
                    } finally { pixels.recycle() }
                    if (!landscapeRendered) SystemClock.sleep(50)
                }
                assertTrue("Landscape must render published content: ${failure.get()}", landscapeRendered)
                instrumentation.removeMonitor(monitor)
                monitor = Instrumentation.ActivityMonitor(NuxieExperienceActivity::class.java.name, null, false)
                instrumentation.addMonitor(monitor)
                instrumentation.runOnMainSync {
                    assertEquals(1uL, prepared.screenLifecycle.appearances)
                    landscape.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                }
            } else {
                instrumentation.runOnMainSync {
                    assertEquals(ExperienceScreenLifecycle.Phase.ACTIVE, prepared.screenLifecycle.phase)
                    assertEquals(1uL, prepared.screenLifecycle.appearances)
                    original.recreate()
                }
            }
            val replacement = if (rotate) original else checkNotNull(monitor.waitForActivityWithTimeout(15_000))
            activity = replacement
            assertEquals("Only explicit recreation replaces the Activity", !rotate, replacement !== original)
            instrumentation.waitForIdleSync()
            assertEquals(!rotate, original.isDestroyed)
            if (rotate) {
                val portraitDeadline = SystemClock.uptimeMillis() + 15_000
                var portraitSized = false
                while (!portraitSized && SystemClock.uptimeMillis() < portraitDeadline) {
                    instrumentation.runOnMainSync {
                        val view = checkNotNull(findSurface(replacement.window.decorView))
                        portraitSized = view.width == before.width && view.height == before.height
                    }
                    if (!portraitSized) SystemClock.sleep(50)
                }
                assertTrue("Portrait surface extent must be restored", portraitSized)
            }
            val replacementSurface = checkNotNull(findSurface(replacement.window.decorView))
            // Activity creation does not mean its asynchronous native frame is ready.
            val renderDeadline = SystemClock.uptimeMillis() + 10_000
            var rendered = copySurfaceAtSize(replacementSurface, before.width, before.height)
            after = rendered
            while (changedPixels(before, rendered, Rect(0, 0, before.width, before.height)) != 0 &&
                SystemClock.uptimeMillis() < renderDeadline) {
                rendered.recycle()
                SystemClock.sleep(50)
                rendered = copySurfaceAtSize(replacementSurface, before.width, before.height)
                after = rendered
            }
            assertEquals(before.width, after.width)
            assertEquals(before.height, after.height)
            File(instrumentation.targetContext.filesDir, "recreation-before.png").outputStream().use {
                before.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
            File(instrumentation.targetContext.filesDir, "recreation-after.png").outputStream().use {
                after.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
            if (changedPixels(before, after, Rect(0, 0, before.width, before.height)) != 0) {
                var viewState = ""
                instrumentation.runOnMainSync {
                    viewState = "available=${replacementSurface.isAvailable} attached=${replacementSurface.isAttachedToWindow} " +
                        "shown=${replacementSurface.isShown} alpha=${replacementSurface.alpha} " +
                        "size=${replacementSurface.width}x${replacementSurface.height} " +
                        "registered=${PresentationRegistry.currentScreen(id)?.purchaseActivity() === replacement} " +
                        "phase=${prepared.screenLifecycle.phase} failure=${failure.get()}"
                }
                val threads = Thread.getAllStackTraces().entries.joinToString("\n\n") { (thread, frames) ->
                    "${thread.name} ${thread.state}\n${frames.joinToString("\n")}"
                }
                File(instrumentation.targetContext.filesDir, "recreation-failure.txt").writeText("$viewState\n$threads")
                android.os.Process.sendSignal(android.os.Process.myPid(), 3)
                // Let ART persist the failure-time native stacks before instrumentation exits.
                SystemClock.sleep(500)
            }
            assertEquals("Recreated renderer must preserve the published surface; failure=${failure.get()}", 0,
                changedPixels(before, after, Rect(0, 0, before.width, before.height)))
            instrumentation.runOnMainSync {
                assertEquals(ExperienceScreenLifecycle.Phase.ACTIVE, prepared.screenLifecycle.phase)
                assertEquals(1uL, prepared.screenLifecycle.appearances)
                replacement.finish()
            }
            assertTrue("Teardown must drain", dismissed.await(15, TimeUnit.SECONDS))
            instrumentation.runOnMainSync {
                assertEquals(ExperienceScreenLifecycle.Phase.HIDDEN, prepared.screenLifecycle.phase)
                assertEquals(1uL, prepared.screenLifecycle.appearances)
            }
            assertEquals(null, failure.get())
        } finally {
            before?.recycle()
            after?.recycle()
            instrumentation.runOnMainSync { activity?.finish() }
            instrumentation.removeMonitor(monitor)
            PresentationRegistry.clearForTesting()
        }
    }

    @Test
    @SdkSuppress(minSdkVersion = 26)
    fun terminalTeardownDrainsPreparedNativeScreensAndCheckpoint() {
        assertTrue(NuxieRuntime.shared.isAvailable)
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val fixture = loadPublishedFixture(instrumentation)
        val contract = instrumentation.context.assets.open("journeys/planes/persistent-navigation-android.json")
            .bufferedReader().use { Json.parseToJsonElement(it.readText()).jsonObject
                .getValue("terminalCheckpoint").jsonObject }
        val nativeContract = contract.getValue("nativeTeardown").jsonObject
        for (item in contract.getValue("cases").jsonArray.map { it.jsonObject }) {
            val identityChange = item.getValue("identityChange").jsonPrimitive.content.toBooleanStrict()
            val cancelWaiter = item.getValue("cancelWaiter").jsonPrimitive.content.toBooleanStrict()
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val service = ExperiencePresentationService(instrumentation.targetContext, { _, _, _ -> }, scope,
                { NuxieRuntime.shared.isAvailable })
            val checkpointEntered = CountDownLatch(1)
            val checkpoint = CompletableDeferred<JourneyScreenDismissalResult>()
            val sourceReleased = java.util.concurrent.atomic.AtomicBoolean(false)
            val destinationReleased = java.util.concurrent.atomic.AtomicBoolean(false)
            val calls = java.util.concurrent.atomic.AtomicInteger()
            val monitor = Instrumentation.ActivityMonitor(NuxieExperienceActivity::class.java.name, null, false)
            instrumentation.addMonitor(monitor)
            var activity: Activity? = null
            var navigation: Deferred<Result<*>>? = null
            var teardown: Deferred<Unit>? = null
            try {
                runBlocking {
                    kotlinx.coroutines.withTimeout(30_000) {
                        service.presentJourney(fixture.release, "screen_1", "terminal-device", "terminal-owner",
                            service.reserveJourney("terminal-owner"),
                            acquire = { AcquiredJourneyRelease(fixture.release.identity, fixture.assets, fixture.riv,
                                protection = Closeable { sourceReleased.set(true) }) },
                            onScreenDismissed = { _, _, _ ->
                                calls.incrementAndGet()
                                checkpointEntered.countDown()
                                checkpoint.await()
                            }, onOutcome = {})
                    }
                }
                activity = checkNotNull(monitor.waitForActivityWithTimeout(10_000))
                navigation = scope.async {
                    runCatching {
                        service.presentJourney(fixture.release, "screen_2", "terminal-device", "terminal-owner", null,
                            acquire = { AcquiredJourneyRelease(fixture.release.identity, fixture.assets, fixture.riv,
                                protection = Closeable { destinationReleased.set(true) }) }, onOutcome = {})
                    }
                }
                assertTrue("Native destination must prepare before checkpoint", checkpointEntered.await(15, TimeUnit.SECONDS))
                if (cancelWaiter) runBlocking { kotlinx.coroutines.withTimeout(15_000) { navigation.cancelAndJoin() } }
                teardown = scope.async {
                    if (identityChange) service.shutdownOwnedBy("terminal-owner")
                    else service.dismissFromHost("terminal-owner")
                }
                val deadline = SystemClock.uptimeMillis() + 10_000
                var destroyed = false
                while (!destroyed && SystemClock.uptimeMillis() < deadline) {
                    instrumentation.runOnMainSync { destroyed = activity!!.isDestroyed }
                    if (!destroyed) SystemClock.sleep(25)
                }
                assertEquals("Activity teardown must not wait for the checkpoint",
                    nativeContract.getValue("activityDestroyedBeforeCheckpoint").jsonPrimitive.content.toBooleanStrict(), destroyed)
                assertFalse("Terminal completion must drain the pending checkpoint", teardown.isCompleted)
                checkpoint.complete(JourneyScreenDismissalResult.HANDLED)
                runBlocking { kotlinx.coroutines.withTimeout(15_000) { teardown.await() } }
                assertEquals("Source lease must drain before terminal completion",
                    nativeContract.getValue("sourceLeaseReleasedOnCompletion").jsonPrimitive.content.toBooleanStrict(), sourceReleased.get())
                assertEquals("Prepared destination lease must drain before terminal completion",
                    nativeContract.getValue("destinationLeaseReleasedOnCompletion").jsonPrimitive.content.toBooleanStrict(), destinationReleased.get())
                runBlocking { kotlinx.coroutines.withTimeout(15_000) { navigation.join() } }
                assertEquals(contract.getValue("checkpointCalls").jsonPrimitive.content.toInt(), calls.get())
                assertEquals(contract.getValue("activityLaunches").jsonPrimitive.content.toInt(), monitor.hits)
            } finally {
                checkpoint.complete(JourneyScreenDismissalResult.HANDLED)
                runBlocking {
                    kotlinx.coroutines.withTimeout(15_000) {
                        navigation?.cancelAndJoin()
                        teardown?.await()
                        service.shutdownOwnedBy("terminal-owner")
                    }
                }
                instrumentation.runOnMainSync { activity?.finish() }
                scope.cancel()
                instrumentation.removeMonitor(monitor)
                PresentationRegistry.clearForTesting()
            }
        }
    }

    @Test
    @SdkSuppress(minSdkVersion = 26)
    fun transparentOutgoingScreenDoesNotRevealPreparedDestination() = exerciseTransparentPreparation()

    @Test
    @SdkSuppress(minSdkVersion = 26)
    fun standardViewTransitionsRevealDestinationAndRestoreSourceOnAbort() {
        for (kind in listOf("fade", "push", "modal")) exerciseTransparentPreparation(kind)
    }

    @Test
    @SdkSuppress(minSdkVersion = 26)
    fun customTransitionWatchdogPreservesPreparedAppearanceAndRollback() = exerciseTransparentPreparation("custom")

    @Test
    @SdkSuppress(minSdkVersion = 26)
    fun signedCustomTransitionsComposeAndRestoreSourceOnAbort() {
        exerciseTransparentPreparation("custom", signedCustom = true)
        exerciseTransparentPreparation("custom", signedCustom = true, reverse = true)
    }

    private fun exerciseTransparentPreparation(
        transitionKind: String? = null,
        signedCustom: Boolean = false,
        reverse: Boolean = false,
    ) {
        assertTrue(NuxieRuntime.shared.isAvailable)
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val contract = instrumentation.context.assets.open("journeys/planes/persistent-navigation-android.json")
            .bufferedReader().use { Json.parseToJsonElement(it.readText()).jsonObject
                .getValue("transparentPreparation").jsonObject }
        val fixture = loadPublishedFixture(instrumentation,
            if (signedCustom) "journeys/rendered-custom-transition" else "journeys/rendered-text-input")
        val screens = fixture.release.descriptor.getValue("render").jsonObject
            .getValue("screens").jsonArray.map { it.jsonObject }
        val sourceIndex = if (reverse) 1 else 0
        val destinationIndex = if (reverse) 0 else 1
        val signedDeclaration = if (signedCustom) fixture.release.descriptor.getValue("render").jsonObject
            .getValue("transitions").jsonArray.single().jsonObject else null
        fun content(index: Int, background: Int): PreparedPresentation {
            val screen = screens[index]
            return PreparedPresentation(fixture.riv, screen.getValue("artboardName").jsonPrimitive.content,
                background, PresentationShell.FullScreen, screen.getValue("id").jsonPrimitive.content,
                fixture.release.descriptor, fixture.assets,
                ExperienceArtboardSize(screen.getValue("width").jsonPrimitive.float,
                    screen.getValue("height").jsonPrimitive.float))
        }
        val id = UUID.randomUUID().toString()
        val destinationId = UUID.randomUUID().toString()
        val transitionContract = instrumentation.context.assets.open("journeys/planes/screen-transition-plan-android.json")
            .bufferedReader().use { Json.parseToJsonElement(it.readText()).jsonObject }
        val custom = transitionContract.getValue("customExecution").jsonObject
        val descriptor = if (transitionKind == "custom" && !signedCustom) {
            val declaration = JsonObject(transitionContract.getValue("declaration").jsonObject + mapOf(
                "sourceScreenId" to screens[0].getValue("id"), "destinationScreenId" to screens[1].getValue("id"),
            ))
            JsonObject(fixture.release.descriptor + ("render" to JsonObject(
                fixture.release.descriptor.getValue("render").jsonObject +
                    ("transitions" to kotlinx.serialization.json.JsonArray(listOf(declaration))))))
        } else fixture.release.descriptor
        val destination = content(destinationIndex, contract.getValue("destinationBackgroundArgb").jsonPrimitive.long.toInt()).copy(
            descriptor = descriptor,
            transition = transitionKind?.let { JsonObject(mapOf(
                "type" to kotlinx.serialization.json.JsonPrimitive(it),
                "transitionId" to (signedDeclaration?.getValue("id") ?: custom.getValue("transitionId")),
            )) },
        )
        val firstFrame = CountDownLatch(1)
        val failure = AtomicReference<Throwable?>()
        val monitor = Instrumentation.ActivityMonitor(NuxieExperienceActivity::class.java.name, null, false)
        instrumentation.addMonitor(monitor)
        var activity: Activity? = null
        var pending: PreparedScreenNavigation? = null
        PresentationRegistry.register(id, content(sourceIndex, contract.getValue("sourceBackgroundArgb").jsonPrimitive.long.toInt()), onFirstFrame = { approveFixtureFrame(id); firstFrame.countDown() },
            onFailure = { failure.set(it) }, onDismissed = {}, onOutcome = {})
        try {
            instrumentation.targetContext.startActivity(Intent(instrumentation.targetContext,
                NuxieExperienceActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                putExtra(NuxieExperienceActivity.EXTRA_PRESENTATION_ID, id)
            })
            activity = checkNotNull(monitor.waitForActivityWithTimeout(15_000))
            assertTrue("Outgoing frame must arrive: ${failure.get()}", firstFrame.await(30, TimeUnit.SECONDS))
            val surface = checkNotNull(findSurface(activity!!.window.decorView))
            stableSurface(surface).recycle()
            val captureBounds = Rect()
            instrumentation.runOnMainSync { assertTrue(surface.getGlobalVisibleRect(captureBounds)) }
            val before = composedSurface(instrumentation, surface, captureBounds)
            pending = runBlocking {
                kotlinx.coroutines.withTimeout(15_000) {
                    checkNotNull(PresentationRegistry.currentScreen(id)).prepareNavigation(
                        destinationId, destination)
                }
            }
            assertTrue("Destination must finish native preparation", pending != null)
            val during = composedSurface(instrumentation, surface, captureBounds)
            try {
                assertEquals("Transparent source must not expose a provisional destination",
                    contract.getValue("composedPixelsChanged").jsonPrimitive.long.toInt(),
                    changedPixels(before, during, Rect(0, 0, before.width, before.height)))
                if (transitionKind != null) {
                    val started = SystemClock.uptimeMillis()
                    runBlocking { kotlinx.coroutines.withTimeout(10_000) { checkNotNull(pending).awaitExit() } }
                    if (transitionKind == "custom") {
                        val elapsed = SystemClock.uptimeMillis() - started
                        if (signedCustom) {
                            val watchdog = checkNotNull(signedDeclaration).getValue("durationMs").jsonPrimitive.long + 250
                            assertTrue("Both signed native completion events must finish before watchdog: reverse=$reverse elapsed=$elapsed", elapsed < watchdog)
                        } else {
                            assertTrue("Missing native completion events must wait for the authored watchdog",
                                elapsed >= custom.getValue("watchdogMs").jsonPrimitive.long)
                        }
                        assertEquals(custom.getValue("preparedAppearances").jsonPrimitive.long.toULong(),
                            destination.screenLifecycle.appearances)
                        assertEquals(ExperienceScreenLifecycle.Phase.ENTERING, destination.screenLifecycle.phase)
                    }
                    val transitioned = composedSurface(instrumentation, surface, captureBounds)
                    try {
                        assertTrue("$transitionKind must reveal the destination before activation",
                            changedPixels(before, transitioned, Rect(0, 0, before.width, before.height)) > 0)
                    } finally { transitioned.recycle() }
                    runBlocking { checkNotNull(pending).abort() }
                    pending = null
                    var restored = composedSurface(instrumentation, surface, captureBounds)
                    val restoreDeadline = SystemClock.uptimeMillis() + 5_000
                    while (changedPixels(before, restored, Rect(0, 0, before.width, before.height)) != 0 &&
                        SystemClock.uptimeMillis() < restoreDeadline) {
                        restored.recycle()
                        SystemClock.sleep(50)
                        restored = composedSurface(instrumentation, surface, captureBounds)
                    }
                    try {
                        if (signedCustom && changedPixels(before, restored, Rect(0, 0, before.width, before.height)) != 0) {
                            File(instrumentation.targetContext.cacheDir, "custom-rollback-before.png").outputStream().use {
                                before.compress(Bitmap.CompressFormat.PNG, 100, it)
                            }
                            File(instrumentation.targetContext.cacheDir, "custom-rollback-after.png").outputStream().use {
                                restored.compress(Bitmap.CompressFormat.PNG, 100, it)
                            }
                        }
                        assertEquals("$transitionKind rollback must restore composed source pixels", 0,
                            changedPixels(before, restored, Rect(0, 0, before.width, before.height)))
                    } finally { restored.recycle() }
                    assertSame(activity, PresentationRegistry.currentScreen(id)?.purchaseActivity())
                    return
                }
                val activated = CountDownLatch(1)
                PresentationRegistry.register(destinationId, destination,
                    onFirstFrame = { approveFixtureFrame(destinationId); activated.countDown() }, onFailure = { failure.set(it) },
                    onDismissed = {}, onOutcome = {})
                checkNotNull(pending).activate()
                pending = null
                assertTrue("Destination activation must complete: ${failure.get()}", activated.await(10, TimeUnit.SECONDS))
                val destinationSurface = checkNotNull(findSurface(activity!!.window.decorView))
                val deadline = SystemClock.uptimeMillis() + 5_000
                var changed = 0
                while (changed == 0 && SystemClock.uptimeMillis() < deadline) {
                    val after = composedSurface(instrumentation, destinationSurface)
                    changed = changedPixels(before, after, Rect(0, 0, before.width, before.height))
                    after.recycle()
                    if (changed == 0) SystemClock.sleep(50)
                }
                assertTrue("Activation must reveal the prepared destination", changed > 0)
            } finally {
                before.recycle()
                during.recycle()
            }
        } finally {
            runBlocking { pending?.abort() }
            instrumentation.runOnMainSync { activity?.finish() }
            instrumentation.removeMonitor(monitor)
            PresentationRegistry.clearForTesting()
        }
    }

    @Test
    @SdkSuppress(minSdkVersion = 26)
    fun sameScreenPreparationPreservesLateEditsAndRestoresInputOnAbort() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val fixture = loadPublishedFixture(instrumentation)
        val contract = instrumentation.context.assets.open("journeys/planes/navigation-input-handoff-android.json")
            .bufferedReader().use { Json.parseToJsonElement(it.readText()).jsonObject }
        fun value(key: String) = contract.getValue(key).jsonPrimitive.content
        val screen = fixture.release.descriptor.getValue("render").jsonObject
            .getValue("screens").jsonArray.first().jsonObject
        val source = PreparedPresentation(fixture.riv, screen.getValue("artboardName").jsonPrimitive.content,
            android.graphics.Color.WHITE, PresentationShell.FullScreen, screen.getValue("id").jsonPrimitive.content,
            fixture.release.descriptor, fixture.assets,
            ExperienceArtboardSize(screen.getValue("width").jsonPrimitive.float, screen.getValue("height").jsonPrimitive.float))
        val id = UUID.randomUUID().toString()
        val firstFrame = CountDownLatch(1)
        val failure = AtomicReference<Throwable?>()
        val monitor = Instrumentation.ActivityMonitor(NuxieExperienceActivity::class.java.name, null, false)
        instrumentation.addMonitor(monitor)
        var activity: Activity? = null
        var pending: PreparedScreenNavigation? = null
        var expectedPixels: Bitmap? = null
        PresentationRegistry.register(id, source,
            onFirstFrame = { approveFixtureFrame(id); firstFrame.countDown() }, onFailure = { failure.set(it) },
            onDismissed = {}, onOutcome = {})
        try {
            instrumentation.targetContext.startActivity(Intent(instrumentation.targetContext, NuxieExperienceActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                putExtra(NuxieExperienceActivity.EXTRA_PRESENTATION_ID, id)
            })
            val host = checkNotNull(monitor.waitForActivityWithTimeout(15_000)).also { activity = it }
            assertTrue("Source frame: ${failure.get()}", firstFrame.await(30, TimeUnit.SECONDS))
            val field = awaitEditor(instrumentation, host, "text-input/screen_1/email_input")
            for (abort in listOf(true, false)) {
                edit(instrumentation, field, value("initialDraft"))
                val outgoingSurface = checkNotNull(findSurface(host.window.decorView))
                val initialPixels = stableSurface(outgoingSurface)
                val destinationId = UUID.randomUUID().toString()
                // This is the service's speculative copy, before the Activity receives the handoff.
                val destination = source.copy(textInputState = source.textInputState.copyForPreparation(),
                    screenLifecycle = source.screenLifecycle.copyForPreparation())
                edit(instrumentation, field, value("latestDraft"))
                expectedPixels?.recycle()
                expectedPixels = stableSurface(outgoingSurface)
                try {
                    assertTrue("The late edit must change native glyphs", changedPixels(initialPixels,
                        checkNotNull(expectedPixels), Rect(0, 0, initialPixels.width, initialPixels.height)) > 20)
                } finally { initialPixels.recycle() }
                var connection: android.view.inputmethod.InputConnection? = null
                instrumentation.runOnMainSync {
                    field.setSelection(1, 3)
                    connection = field.onCreateInputConnection(android.view.inputmethod.EditorInfo())
                }
                pending = runBlocking { kotlinx.coroutines.withTimeout(15_000) {
                    checkNotNull(PresentationRegistry.currentScreen(id)).prepareNavigation(destinationId, destination)
                } }
                instrumentation.runOnMainSync {
                    assertFalse("Outgoing editor must be frozen through native preparation", field.isEnabled)
                    checkNotNull(connection).commitText(value("staleImeDraft"), 1)
                    fun editors(view: View): List<EditText> = (if (view is EditText) listOf(view) else emptyList()) +
                        if (view is ViewGroup) (0 until view.childCount).flatMap { editors(view.getChildAt(it)) } else emptyList()
                    val incoming = editors(host.window.decorView).single { it !== field && it.tag == field.tag }
                    assertEquals(value("latestDraft"), incoming.text.toString())
                    assertEquals(1, incoming.selectionStart)
                    assertEquals(3, incoming.selectionEnd)
                }
                assertEquals(contract.getValue("destinationAppearances").jsonPrimitive.long.toULong(),
                    destination.screenLifecycle.appearances)
                assertEquals(contract.getValue("initialAppearances").jsonPrimitive.long.toULong(), source.screenLifecycle.appearances)
                if (abort) {
                    runBlocking { checkNotNull(pending).abort() }
                    pending = null
                    instrumentation.runOnMainSync {
                        assertTrue(field.isEnabled)
                        assertEquals(value("latestDraft"), field.text.toString())
                        assertEquals(1, field.selectionStart)
                        assertEquals(3, field.selectionEnd)
                    }
                    edit(instrumentation, field, "Resumed")
                } else {
                    runBlocking { checkNotNull(pending).awaitExit() }
                    val activated = CountDownLatch(1)
                    PresentationRegistry.register(destinationId, destination,
                        onFirstFrame = { approveFixtureFrame(destinationId); activated.countDown() },
                        onFailure = { failure.set(it) }, onDismissed = {}, onOutcome = {})
                    checkNotNull(pending).activate()
                    pending = null
                    assertTrue("Destination activation: ${failure.get()}", activated.await(10, TimeUnit.SECONDS))
                    val incoming = awaitEditor(instrumentation, host, "text-input/screen_1/email_input")
                    instrumentation.runOnMainSync {
                        assertTrue(incoming.isEnabled)
                        assertEquals(value("latestDraft"), incoming.text.toString())
                    }
                    val actual = stableSurface(checkNotNull(findSurface(host.window.decorView)))
                    try {
                        assertEquals("The native destination must render the late draft exactly", 0,
                            changedPixels(checkNotNull(expectedPixels), actual, Rect(0, 0, actual.width, actual.height)))
                    } finally { actual.recycle() }
                    assertSame(host, PresentationRegistry.currentScreen(destinationId)?.purchaseActivity())
                }
            }
        } finally {
            expectedPixels?.recycle()
            runBlocking { pending?.abort() }
            instrumentation.runOnMainSync { activity?.finish() }
            instrumentation.removeMonitor(monitor)
            PresentationRegistry.clearForTesting()
        }
    }

    @Test
    @SdkSuppress(minSdkVersion = 26)
    fun drawerClipsNativeContentAlongWithItsRenderedSurface() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val closeContract = instrumentation.context.assets.open("journeys/planes/presentation-reveal-android.json")
            .bufferedReader().use { Json.parseToJsonElement(it.readText()).jsonObject.getValue("unseenClose").jsonObject }
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
                    item.getValue("extentRatio").jsonPrimitive.float, radius, false),
                screen.getValue("id").jsonPrimitive.content, fixture.release.descriptor, fixture.assets,
                ExperienceArtboardSize(screen.getValue("width").jsonPrimitive.float, screen.getValue("height").jsonPrimitive.float),
            ), onFirstFrame = { approveFixtureFrame(presentationId); firstFrame.countDown() }, onFailure = { failure.set(it) },
                onDismissed = {}, onOutcome = {})
            try {
                context.startActivity(Intent(context, NuxieExperienceActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    putExtra(NuxieExperienceActivity.EXTRA_PRESENTATION_ID, presentationId)
                })
                activity = checkNotNull(monitor.waitForActivityWithTimeout(15_000))
                assertTrue("Runtime frame must arrive: ${failure.get()}", firstFrame.await(30, TimeUnit.SECONDS))
                instrumentation.runOnMainSync {
                    @Suppress("DEPRECATION")
                    activity!!.onBackPressed()
                    assertEquals("Revealed content must honor authored nondismissibility",
                        closeContract.getValue("authoredDismissibilityAppliesAfterReveal").jsonPrimitive.boolean, !activity!!.isFinishing)
                }
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
    fun unsupportedSurfaceCopiesPublishedPixelsAndTextAndDrainsNativeOwnership() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val fixture = loadPublishedFixture(instrumentation)
        assertTrue(NuxieRuntime.shared.isAvailable)
        val screen = fixture.release.descriptor.getValue("render").jsonObject
            .getValue("screens").jsonArray.first().jsonObject
        val inputs = ExperienceTextInput.forScreen(fixture.release.descriptor, screen.getValue("id").jsonPrimitive.content)
        val activity = instrumentation.startActivitySync(Intent(instrumentation.targetContext,
            SurfaceCompatibilityHostActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        val lane = NuxieRuntimeLane()
        val copied = java.util.concurrent.atomic.AtomicInteger()
        val attachments = java.util.concurrent.atomic.AtomicInteger()
        val freed = java.util.concurrent.atomic.AtomicInteger()
        val firstFrame = CountDownLatch(1)
        val commits = LinkedBlockingQueue<Pair<String, String>>()
        val failure = AtomicReference<Throwable?>()
        val releaseNative = CountDownLatch(1)
        val native = object : ai.nuxie.sdk.runtime.NuxieTypedRuntimeNative by ai.nuxie.sdk.runtime.JniNuxieTypedRuntimeNative {
            override fun attachRendererSurface(rendererHandle: Long, windowHandle: Long): Int {
                attachments.incrementAndGet()
                return -1
            }
            override fun renderAndPresent(rendererHandle: Long, playerHandle: Long, windowHandle: Long,
                clearColor: Int, fitContainCenter: Boolean): Int = error("Unsupported attachment must never use GPU surface presentation")
            override fun copyPlayerToWindow(rendererHandle: Long, playerHandle: Long, windowHandle: Long,
                clearColor: Int, fitContainCenter: Boolean): Int {
                copied.incrementAndGet()
                return ai.nuxie.sdk.runtime.JniNuxieTypedRuntimeNative.copyPlayerToWindow(
                    rendererHandle, playerHandle, windowHandle, clearColor, fitContainCenter)
            }
            override fun freeRenderer(handle: Long) {
                freed.incrementAndGet()
                ai.nuxie.sdk.runtime.JniNuxieTypedRuntimeNative.freeRenderer(handle)
            }
        }
        var surface: ExperienceSurfaceHost? = null
        try {
            instrumentation.runOnMainSync {
                surface = ExperienceSurfaceHost(activity, lane, clearColor = 0,
                    artboardSize = ExperienceArtboardSize(screen.getValue("width").jsonPrimitive.float,
                        screen.getValue("height").jsonPrimitive.float), runtime = NuxieRuntime(native),
                    listener = object : ExperienceSurfaceHost.Listener {
                        override fun onFirstFrame() { firstFrame.countDown() }
                        override fun onFailure(error: ExperiencePresentationException) { failure.set(error) }
                        override fun onTextCommitted(inputId: String, text: String) { commits.add(inputId to text) }
                    })
                checkNotNull(surface).loadArtboard(fixture.riv.readBytes(), screen.getValue("artboardName").jsonPrimitive.content,
                    fixture.release.descriptor, fixture.assets, textInputs = inputs)
                activity.setContentView(checkNotNull(surface))
            }
            assertTrue("Window-copy first frame must compose: ${failure.get()}", firstFrame.await(30, TimeUnit.SECONDS))
            val original = copySurface(checkNotNull(surface))
            val region = Rect(0, 0, original.width, original.height)
            fun write(text: String) {
                val finished = CountDownLatch(1)
                val result = AtomicReference<Result<Unit>>()
                instrumentation.runOnMainSync {
                    checkNotNull(surface).writeText(inputs.single().id, text, true) { result.set(it); finished.countDown() }
                }
                assertTrue(finished.await(10, TimeUnit.SECONDS))
                checkNotNull(result.get()).getOrThrow()
                assertEquals(inputs.single().id to text, commits.poll(10, TimeUnit.SECONDS))
            }
            write("")
            var cleared = copySurface(checkNotNull(surface))
            val deadline = SystemClock.elapsedRealtime() + 10_000
            while (changedPixels(original, cleared, region) < 20 && SystemClock.elapsedRealtime() < deadline) {
                cleared.recycle()
                SystemClock.sleep(25)
                cleared = copySurface(checkNotNull(surface))
            }
            assertTrue("Native window copy must expose actual rendered glyph changes", changedPixels(original, cleared, region) >= 20)
            cleared.recycle()
            write(inputs.single().value)
            var restored = copySurface(checkNotNull(surface))
            val restoreDeadline = SystemClock.elapsedRealtime() + 10_000
            while (changedPixels(original, restored, region) != 0 && SystemClock.elapsedRealtime() < restoreDeadline) {
                restored.recycle()
                SystemClock.sleep(25)
                restored = copySurface(checkNotNull(surface))
            }
            assertEquals(0, changedPixels(original, restored, region))
            restored.recycle()
            instrumentation.runOnMainSync {
                checkNotNull(surface).layoutParams = android.widget.FrameLayout.LayoutParams(original.width - 80, original.height - 120)
            }
            val resized = copySurfaceAtSize(checkNotNull(surface), original.width - 80, original.height - 120)
            resized.recycle()
            val copiedBeforeResize = copied.get()
            val resizeDeadline = SystemClock.elapsedRealtime() + 10_000
            while (copied.get() <= copiedBeforeResize && SystemClock.elapsedRealtime() < resizeDeadline) SystemClock.sleep(25)
            assertTrue(copied.get() > copiedBeforeResize)
            instrumentation.runOnMainSync {
                checkNotNull(surface).layoutParams = android.widget.FrameLayout.LayoutParams(original.width, original.height)
            }
            var returned = copySurfaceAtSize(checkNotNull(surface), original.width, original.height)
            val returnDeadline = SystemClock.elapsedRealtime() + 10_000
            while (changedPixels(original, returned, region) != 0 && SystemClock.elapsedRealtime() < returnDeadline) {
                returned.recycle()
                SystemClock.sleep(25)
                returned = copySurfaceAtSize(checkNotNull(surface), original.width, original.height)
            }
            assertEquals("Window-copy resize must preserve authored content", 0, changedPixels(original, returned, region))
            assertEquals("Resize must not switch a connected CPU producer to Vulkan", 1, attachments.get())
            original.recycle()
            returned.recycle()
            assertTrue(copied.get() > 0)
            assertNull(failure.get())
            val held = CountDownLatch(1)
            assertTrue(lane.enqueue { held.countDown(); check(releaseNative.await(15, TimeUnit.SECONDS)) })
            assertTrue(held.await(10, TimeUnit.SECONDS))
            val drained = CountDownLatch(1)
            instrumentation.runOnMainSync { checkNotNull(surface).release(); lane.shutdown { drained.countDown() } }
            assertFalse(drained.await(100, TimeUnit.MILLISECONDS))
            assertEquals(0, freed.get())
            releaseNative.countDown()
            assertTrue(drained.await(15, TimeUnit.SECONDS))
            assertEquals(1, freed.get())
        } finally {
            releaseNative.countDown()
            instrumentation.runOnMainSync { surface?.release(); activity.finish() }
            lane.shutdown()
            assertTrue(lane.awaitQuiescence(30_000))
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
        ), onFirstFrame = { approveFixtureFrame(presentationId); firstFrame.countDown() }, onFailure = { failure.set(it) },
            onDismissed = {}, onOutcome = { if (it is CloseReason.Error) failure.set(it.cause) }, onTextCommitted = { id, text, _ -> commits.add(id to text) })
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
    fun authenticatedShellPrecedesAcquisitionAndReusesActivityForNativeReveal() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        assertTrue(NuxieRuntime.shared.isAvailable)
        val fixture = loadPublishedFixture(instrumentation)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val releaseAcquisition = CompletableDeferred<Unit>()
        val releaseReveal = CompletableDeferred<Unit>()
        val revealStarted = CountDownLatch(1)
        val started = CountDownLatch(1)
        val shown = java.util.concurrent.atomic.AtomicInteger()
        val service = ExperiencePresentationService(instrumentation.targetContext, { name, _, _ ->
            if (name == ai.nuxie.sdk.events.SystemEventNames.EXPERIENCE_SHOWN) shown.incrementAndGet()
        }, scope, { NuxieRuntime.shared.isAvailable })
        val monitor = Instrumentation.ActivityMonitor(NuxieExperienceActivity::class.java.name, null, false)
        instrumentation.addMonitor(monitor)
        val before = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
        val pending = scope.async {
            service.presentJourney(fixture.release, "screen_1", "early-shell", "early-owner",
                service.reserveJourney("early-owner"), acquire = {
                    started.countDown()
                    releaseAcquisition.await()
                    AcquiredJourneyRelease(fixture.release.identity, fixture.assets, fixture.riv, protection = Closeable {})
                }, onPresentationRevealed = { revealStarted.countDown(); releaseReveal.await() }, onOutcome = {})
        }
        try {
            assertTrue(started.await(10, TimeUnit.SECONDS))
            val activity = checkNotNull(monitor.waitForActivityWithTimeout(10_000))
            instrumentation.waitForIdleSync()
            var root: View? = null
            val bounds = Rect()
            instrumentation.runOnMainSync {
                root = activity.findViewById<ViewGroup>(android.R.id.content).getChildAt(0)
                assertTrue(checkNotNull(root).getGlobalVisibleRect(bounds))
            }
            val rgba = fixture.release.descriptor.getValue("presentation").jsonObject
                .getValue("backgroundColor").jsonPrimitive.content.removePrefix("#")
            assertEquals("Published fixture uses transparent signed background", "00000000", rgba)
            instrumentation.runOnMainSync {
                assertEquals(0, (checkNotNull(root).background as android.graphics.drawable.ColorDrawable).color)
                val container = checkNotNull(root) as ViewGroup
                assertEquals(1, container.childCount)
                assertTrue(container.getChildAt(0) is ExperienceLoadingView)
                assertEquals("Experience loading", container.getChildAt(0).contentDescription)
            }
            val expected = before.getPixel(bounds.centerX(), bounds.centerY())
            var observed = 0
            val deadline = SystemClock.elapsedRealtime() + 5_000
            do {
                val screenshot = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
                observed = screenshot.getPixel(bounds.centerX(), bounds.centerY())
                screenshot.recycle()
                if (observed != expected) SystemClock.sleep(30)
            } while (observed != expected && SystemClock.elapsedRealtime() < deadline)
            assertEquals("Composed loading shell must use signed background", expected, observed)
            assertEquals(0, shown.get())
            assertFalse(pending.isCompleted)
            releaseAcquisition.complete(Unit)
            assertTrue(revealStarted.await(15, TimeUnit.SECONDS))
            assertFalse(pending.isCompleted)
            assertEquals(0, shown.get())
            instrumentation.runOnMainSync {
                val container = checkNotNull(root) as ViewGroup
                assertEquals(2, container.childCount)
                assertEquals(0f, container.getChildAt(0).alpha)
                assertTrue(container.getChildAt(1) is ExperienceLoadingView)
                assertEquals(0, (container.background as android.graphics.drawable.ColorDrawable).color)
            }
            releaseReveal.complete(Unit)
            runBlocking { kotlinx.coroutines.withTimeout(30_000) { pending.await() } }
            assertHostedScreen(instrumentation, activity, "screen_1")
            assertEquals(1, monitor.hits)
            assertEquals(1, shown.get())
            instrumentation.runOnMainSync {
                assertSame(root, activity.findViewById<ViewGroup>(android.R.id.content).getChildAt(0))
                val container = checkNotNull(root) as ViewGroup
                assertEquals(1, container.childCount)
                assertFalse(container.getChildAt(0) is ExperienceLoadingView)
                assertEquals(1f, container.getChildAt(0).alpha)
                assertNull(container.background)
            }
        } finally {
            before.recycle()
            releaseReveal.complete(Unit)
            releaseAcquisition.complete(Unit)
            runBlocking { service.shutdownOwnedBy("early-owner"); scope.coroutineContext[Job]?.cancelAndJoin() }
            instrumentation.removeMonitor(monitor)
            PresentationRegistry.clearForTesting()
        }
    }

    @Test
    @SdkSuppress(minSdkVersion = 26)
    fun failedAcquisitionRetriesAndRevealsInsideTheSameActivity() = verifyAcquisitionRecovery(false)

    @Test
    @SdkSuppress(minSdkVersion = 26)
    fun slowAcquisitionShowsRecoveryAndCloseDrainsWithoutReveal() = verifyAcquisitionRecovery(true)

    private fun verifyAcquisitionRecovery(closeWhileSlow: Boolean) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        assertTrue(NuxieRuntime.shared.isAvailable)
        val fixture = loadPublishedFixture(instrumentation)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val release = CompletableDeferred<Unit>()
        val attempts = java.util.concurrent.atomic.AtomicInteger()
        val closed = java.util.concurrent.atomic.AtomicInteger()
        val shown = java.util.concurrent.atomic.AtomicInteger()
        val service = ExperiencePresentationService(instrumentation.targetContext, { name, _, _ ->
            if (name == ai.nuxie.sdk.events.SystemEventNames.EXPERIENCE_SHOWN) shown.incrementAndGet()
        }, scope, { NuxieRuntime.shared.isAvailable })
        val monitor = Instrumentation.ActivityMonitor(NuxieExperienceActivity::class.java.name, null, false)
        instrumentation.addMonitor(monitor)
        val pending = scope.async {
            runCatching {
                service.presentJourney(fixture.release, "screen_1", "recovery-device", "recovery-owner",
                    service.reserveJourney("recovery-owner"), acquire = {
                        if (attempts.incrementAndGet() == 1 && !closeWhileSlow) throw java.io.IOException("Fixture transport failure")
                        kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) { release.await() }
                        AcquiredJourneyRelease(fixture.release.identity, fixture.assets, fixture.riv,
                            protection = Closeable { closed.incrementAndGet() })
                    }, onOutcome = {})
            }
        }
        try {
            val activity = checkNotNull(monitor.waitForActivityWithTimeout(10_000))
            var originalRoot: View? = null
            instrumentation.runOnMainSync {
                originalRoot = activity.findViewById<ViewGroup>(android.R.id.content).getChildAt(0)
            }
            fun descendants(view: View): List<View> = listOf(view) + if (view is ViewGroup)
                (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList()
            var button: android.widget.Button? = null
            val label = if (closeWhileSlow) "Close" else "Retry"
            val deadline = SystemClock.elapsedRealtime() + 10_000
            while (button == null && SystemClock.elapsedRealtime() < deadline) {
                instrumentation.runOnMainSync {
                    button = descendants(checkNotNull(originalRoot)).filterIsInstance<android.widget.Button>()
                        .singleOrNull { it.text == label }
                }
                if (button == null) SystemClock.sleep(25)
            }
            assertTrue("Recovery must expose $label", button != null)
            assertEquals(0, shown.get())
            assertFalse(pending.isCompleted)
            instrumentation.runOnMainSync { assertTrue(checkNotNull(button).performClick()) }
            if (closeWhileSlow) {
                instrumentation.waitForIdleSync()
                assertFalse("Close must still drain cancellation-resistant acquisition", pending.isCompleted)
            } else {
                val retryDeadline = SystemClock.elapsedRealtime() + 5_000
                while (attempts.get() < 2 && SystemClock.elapsedRealtime() < retryDeadline) SystemClock.sleep(20)
                assertEquals(2, attempts.get())
                instrumentation.runOnMainSync {
                    assertSame(originalRoot, activity.findViewById<ViewGroup>(android.R.id.content).getChildAt(0))
                }
            }
            release.complete(Unit)
            val result = runBlocking { kotlinx.coroutines.withTimeout(30_000) { pending.await() } }
            assertEquals(1, monitor.hits)
            if (closeWhileSlow) {
                assertTrue(result.isFailure)
                assertEquals(1, closed.get())
                assertEquals(0, shown.get())
            } else {
                result.getOrThrow()
                assertHostedScreen(instrumentation, activity, "screen_1")
                assertEquals(1, shown.get())
                assertEquals(0, closed.get())
            }
        } finally {
            release.complete(Unit)
            runBlocking { service.shutdownOwnedBy("recovery-owner"); scope.coroutineContext[Job]?.cancelAndJoin() }
            instrumentation.removeMonitor(monitor)
            PresentationRegistry.clearForTesting()
        }
        assertEquals(1, closed.get())
    }

    @Test
    @SdkSuppress(minSdkVersion = 26)
    fun nativeLoadFailureRetriesWithinTheAuthenticatedShell() = verifyNativeRecovery(false)

    @Test
    @SdkSuppress(minSdkVersion = 26)
    fun nativeFailureRecoverySurvivesActivityRecreation() = verifyNativeRecovery(true)

    @Test
    @SdkSuppress(minSdkVersion = 26)
    fun nativeRetryRecreationWaitsForTheOriginalRuntimeLane() = verifyNativeRecovery(false, "recreate")

    @Test
    @SdkSuppress(minSdkVersion = 26)
    fun nativeRetryUserCloseWaitsForTheOriginalRuntimeLane() = verifyNativeRecovery(false, "close")

    @Test
    @SdkSuppress(minSdkVersion = 26)
    fun nativeRetryIdentityWithdrawalWaitsForTheOriginalRuntimeLane() = verifyNativeRecovery(false, "identity")

    @Test
    @SdkSuppress(minSdkVersion = 26)
    fun nativeRetryHostDismissalWaitsForTheOriginalRuntimeLane() = verifyNativeRecovery(false, "host")

    private fun verifyNativeRecovery(recreate: Boolean, drainAction: String? = null) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        assertTrue(NuxieRuntime.shared.isAvailable)
        val fixture = loadPublishedFixture(instrumentation)
        val validBytes = fixture.riv.readBytes()
        val releaseNative = CountDownLatch(1)
        fixture.riv.writeText("invalid native fixture")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val closed = java.util.concurrent.atomic.AtomicInteger()
        val shown = java.util.concurrent.atomic.AtomicInteger()
        val terminalStarted = CountDownLatch(1)
        val outcomes = java.util.concurrent.atomic.AtomicInteger()
        val checkpoints = java.util.concurrent.atomic.AtomicInteger()
        val service = ExperiencePresentationService(instrumentation.targetContext, { name, _, _ ->
            if (name == ai.nuxie.sdk.events.SystemEventNames.EXPERIENCE_SHOWN) shown.incrementAndGet()
        }, scope, { NuxieRuntime.shared.isAvailable })
        val monitor = Instrumentation.ActivityMonitor(NuxieExperienceActivity::class.java.name, null, false)
        instrumentation.addMonitor(monitor)
        val pending = scope.async {
            service.presentJourney(fixture.release, "screen_1", "native-recovery-device", "native-recovery-owner",
                service.reserveJourney("native-recovery-owner"), acquire = {
                    AcquiredJourneyRelease(fixture.release.identity, fixture.assets, fixture.riv,
                        protection = Closeable { closed.incrementAndGet() })
                }, onScreenDismissed = { _, _, _ ->
                    checkpoints.incrementAndGet()
                    JourneyScreenDismissalResult.HANDLED
                }, onOutcome = { outcomes.incrementAndGet(); terminalStarted.countDown() })
        }
        try {
            var activity = checkNotNull(monitor.waitForActivityWithTimeout(10_000))
            var root: View? = null
            instrumentation.runOnMainSync { root = activity.findViewById<ViewGroup>(android.R.id.content).getChildAt(0) }
            fun descendants(view: View): List<View> = listOf(view) + if (view is ViewGroup)
                (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList()
            fun awaitRetry(): android.widget.Button {
                var retry: android.widget.Button? = null
                val deadline = SystemClock.elapsedRealtime() + 10_000
                while (retry == null && SystemClock.elapsedRealtime() < deadline) {
                    instrumentation.runOnMainSync {
                        retry = descendants(checkNotNull(root)).filterIsInstance<android.widget.Button>().singleOrNull { it.text == "Retry" }
                    }
                    if (retry == null) SystemClock.sleep(25)
                }
                return checkNotNull(retry) { "Native failure must retain an actionable recovery shell" }
            }
            var retry = awaitRetry()
            if (recreate) {
                val original = activity
                instrumentation.runOnMainSync { original.recreate() }
                val replacementDeadline = SystemClock.elapsedRealtime() + 10_000
                var replacement: Activity? = null
                while (replacement == null && SystemClock.elapsedRealtime() < replacementDeadline) {
                    replacement = monitor.waitForActivityWithTimeout(500)?.takeUnless { it === original }
                }
                activity = checkNotNull(replacement) { "Recreation must provide a distinct Activity" }
                instrumentation.waitForIdleSync()
                instrumentation.runOnMainSync {
                    root = activity.findViewById<ViewGroup>(android.R.id.content).getChildAt(0)
                }
                retry = awaitRetry()
                val id = checkNotNull(activity.intent.getStringExtra(NuxieExperienceActivity.EXTRA_PRESENTATION_ID))
                assertEquals(1L, PresentationRegistry.nativeProgress(id)?.generation)
            }
            assertFalse(pending.isCompleted)
            assertEquals(0, shown.get())
            assertEquals(0, closed.get())
            if (drainAction != null) {
                var surface: ExperienceSurfaceHost? = null
                instrumentation.runOnMainSync {
                    surface = descendants(checkNotNull(root)).filterIsInstance<ExperienceSurfaceHost>().single()
                }
                // Hold the real pinned lane ahead of release; do not replace native resources with doubles.
                val lane = ExperienceSurfaceHost::class.java.getDeclaredField("lane").apply { isAccessible = true }
                    .get(checkNotNull(surface)) as NuxieRuntimeLane
                val nativeHeld = CountDownLatch(1)
                val ownsRenderer = java.util.concurrent.atomic.AtomicBoolean()
                assertTrue(lane.enqueue {
                    ownsRenderer.set(ExperienceSurfaceHost::class.java.getDeclaredField("renderer")
                        .apply { isAccessible = true }.get(surface) != null)
                    nativeHeld.countDown()
                    check(releaseNative.await(30, TimeUnit.SECONDS)) { "Native drain test barrier timed out" }
                })
                assertTrue(nativeHeld.await(10, TimeUnit.SECONDS))
                assertTrue("Failure must still own a real native renderer", ownsRenderer.get())
            }
            fixture.riv.writeBytes(validBytes)
            instrumentation.runOnMainSync { assertTrue(checkNotNull(retry).performClick()) }
            if (drainAction != null) {
                val id = checkNotNull(activity.intent.getStringExtra(NuxieExperienceActivity.EXTRA_PRESENTATION_ID))
                instrumentation.waitForIdleSync()
                assertEquals(AcquisitionProgress.Phase.RETRYING, PresentationRegistry.nativeProgress(id)?.phase)
                assertEquals(2L, PresentationRegistry.nativeProgress(id)?.generation)
                assertFalse(pending.isCompleted)
                assertEquals(0, closed.get())
                if (drainAction == "recreate") {
                    val original = activity
                    instrumentation.runOnMainSync { original.recreate() }
                    val deadline = SystemClock.elapsedRealtime() + 10_000
                    var replacement: Activity? = null
                    while (replacement == null && SystemClock.elapsedRealtime() < deadline) {
                        replacement = monitor.waitForActivityWithTimeout(500)?.takeUnless { it === original }
                    }
                    activity = checkNotNull(replacement)
                    instrumentation.waitForIdleSync()
                    instrumentation.runOnMainSync {
                        root = activity.findViewById<ViewGroup>(android.R.id.content).getChildAt(0)
                        assertTrue("Replacement must not mount before original native drain",
                            descendants(checkNotNull(root)).none { it is ExperienceSurfaceHost })
                    }
                    assertEquals(AcquisitionProgress.Phase.RETRYING, PresentationRegistry.nativeProgress(id)?.phase)
                    assertFalse(pending.isCompleted)
                    assertEquals(0, shown.get())
                    assertEquals(0, closed.get())
                } else {
                    val terminal = if (drainAction in listOf("identity", "host")) scope.async {
                        if (drainAction == "identity") service.shutdownOwnedBy("native-recovery-owner")
                        else service.dismissFromHost("native-recovery-owner")
                    } else {
                        instrumentation.runOnMainSync {
                            val close = descendants(checkNotNull(root)).filterIsInstance<android.widget.Button>()
                                .single { it.text == "Close" }
                            assertTrue(close.performClick())
                        }
                        null
                    }
                    assertTrue(terminalStarted.await(10, TimeUnit.SECONDS))
                    instrumentation.waitForIdleSync()
                    assertFalse(pending.isCompleted)
                    assertFalse(terminal?.isCompleted == true)
                    assertEquals(0, closed.get())
                    releaseNative.countDown()
                    runBlocking { kotlinx.coroutines.withTimeout(30_000) {
                        terminal?.await()
                        pending.join()
                    } }
                    assertTrue(runBlocking { runCatching { pending.await() }.isFailure })
                    assertEquals(0, shown.get())
                    assertEquals(0, checkpoints.get())
                    assertEquals(1, outcomes.get())
                    assertEquals(1, closed.get())
                    assertEquals(1, monitor.hits)
                    return
                }
                releaseNative.countDown()
            }
            runBlocking { kotlinx.coroutines.withTimeout(30_000) { pending.await() } }
            assertHostedScreen(instrumentation, activity, "screen_1")
            assertEquals("Recreation and Retry must not launch another presentation Intent", 1, monitor.hits)
            assertEquals(1, shown.get())
            instrumentation.runOnMainSync {
                assertSame(root, activity.findViewById<ViewGroup>(android.R.id.content).getChildAt(0))
                assertTrue(descendants(checkNotNull(root)).none { it is ExperienceRecoveryView })
                val id = checkNotNull(activity.intent.getStringExtra(NuxieExperienceActivity.EXTRA_PRESENTATION_ID))
                assertEquals(1uL, checkNotNull(PresentationRegistry.resolve(id)).screenLifecycle.appearances)
                assertEquals(2L, PresentationRegistry.nativeProgress(id)?.generation)
                assertFalse(PresentationRegistry.retryNative(id, 2))
            }
        } finally {
            releaseNative.countDown()
            fixture.riv.writeBytes(validBytes)
            runBlocking { service.shutdownOwnedBy("native-recovery-owner"); scope.coroutineContext[Job]?.cancelAndJoin() }
            instrumentation.removeMonitor(monitor)
            PresentationRegistry.clearForTesting()
        }
        assertEquals(1, closed.get())
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
        val navigationContract = instrumentation.context.assets.open("journeys/planes/persistent-navigation-android.json")
            .bufferedReader().use { Json.parseToJsonElement(it.readText()).jsonObject }
        val navigationScreens = navigationContract.getValue("screens").jsonArray.map { it.jsonPrimitive.content }
        var hostActivity: Activity? = null
        var dismissalCheckpoints = 0
        val checkpointEntered = CountDownLatch(1)
        val releaseCheckpoint = CompletableDeferred<Unit>()
        var holdCheckpoint = false
        var pendingNavigation: Deferred<Activity>? = null
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
                    }, onScreenDismissed = { _, _, _ ->
                        dismissalCheckpoints++
                        if (holdCheckpoint) {
                            checkpointEntered.countDown()
                            releaseCheckpoint.await()
                        }
                        JourneyScreenDismissalResult.HANDLED
                    }, onOutcome = {})
            }
            assertEquals(screenId, screens.poll(5, TimeUnit.SECONDS))
            val host = hostActivity ?: checkNotNull(monitor.waitForActivityWithTimeout(10_000)).also { hostActivity = it }
            assertHostedScreen(instrumentation, host, screenId)
            return host
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
            val first = present(navigationScreens[0])
            val field = awaitEditor(instrumentation, first, inputId)
            edit(instrumentation, field, "saved@example.com")
            response("saved@example.com")
            instrumentation.runOnMainSync { field.setSelection(2, 7) }
            val outgoingSurface = checkNotNull(findSurface(first.window.decorView))
            val beforeFailure = stableSurface(outgoingSurface)
            val invalidRiv = File.createTempFile("invalid-navigation", ".riv", instrumentation.targetContext.cacheDir)
            invalidRiv.writeText("invalid native content")
            val unusedLeaseClosed = java.util.concurrent.atomic.AtomicBoolean(false)
            val failedPreparation = scope.async {
                runCatching {
                    service.presentJourney(fixture.release, navigationScreens[1], journey, owner, null,
                        acquire = { AcquiredJourneyRelease(fixture.release.identity, fixture.assets, invalidRiv,
                            protection = Closeable { unusedLeaseClosed.set(true) }) }, onOutcome = {})
                }
            }
            val sourceId = checkNotNull(first.intent.getStringExtra(NuxieExperienceActivity.EXTRA_PRESENTATION_ID))
            runBlocking {
                kotlinx.coroutines.withTimeout(15_000) {
                    while ((PresentationRegistry.observe(sourceId)?.value as? PresentationContentState.Ready)
                            ?.navigationRecovery?.progress?.phase != AcquisitionProgress.Phase.FAILED) kotlinx.coroutines.delay(20)
                }
                assertFalse("Native preparation failure should wait for recovery", failedPreparation.isCompleted)
                failedPreparation.cancelAndJoin()
            }
            invalidRiv.delete()
            assertHostedScreen(instrumentation, first, navigationScreens[0])
            val failedContract = navigationContract.getValue("failedPreparation").jsonObject
            assertEquals(failedContract.getValue("dismissalCheckpoints").jsonPrimitive.content.toInt(), dismissalCheckpoints)
            assertEquals(failedContract.getValue("destinationLeaseReleased").jsonPrimitive.content.toBoolean(), unusedLeaseClosed.get())
            val afterFailure = copySurface(outgoingSurface)
            assertEquals(failedContract.getValue("outgoingPixelsChanged").jsonPrimitive.content.toInt(),
                changedPixels(beforeFailure, afterFailure, Rect(0, 0, beforeFailure.width, beforeFailure.height)))
            beforeFailure.recycle()
            afterFailure.recycle()
            val beforePending = composedSurface(instrumentation, outgoingSurface)
            holdCheckpoint = true
            pendingNavigation = scope.async { present(navigationScreens[1]) }
            assertTrue("Prepared destination must reach the outgoing checkpoint", checkpointEntered.await(15, TimeUnit.SECONDS))
            assertHostedScreen(instrumentation, first, navigationScreens[0])
            val duringPending = composedSurface(instrumentation, outgoingSurface)
            File(instrumentation.targetContext.filesDir, "navigation-before-pending.png").outputStream().use {
                beforePending.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
            File(instrumentation.targetContext.filesDir, "navigation-during-pending.png").outputStream().use {
                duringPending.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
            assertEquals("A prepared destination must not cover the outgoing screen before its checkpoint completes",
                navigationContract.getValue("pendingCheckpointComposedPixelsChanged").jsonPrimitive.content.toInt(),
                changedPixels(beforePending, duringPending, Rect(0, 0, beforePending.width, beforePending.height)))
            beforePending.recycle()
            duringPending.recycle()
            holdCheckpoint = false
            releaseCheckpoint.complete(Unit)
            val second = runBlocking { pendingNavigation.await() }
            assertTrue("Navigation must retain the Activity", first === second)
            val returned = present(navigationScreens[2])
            assertEquals(navigationContract.getValue("activityLaunches").jsonPrimitive.content.toInt(), monitor.hits)
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
            releaseCheckpoint.complete(Unit)
            runBlocking {
                pendingNavigation?.cancelAndJoin()
                service.shutdownOwnedBy(owner)
            }
            scope.cancel()
            instrumentation.removeMonitor(monitor)
            PresentationRegistry.clearForTesting()
        }
    }

    @Test
    @SdkSuppress(minSdkVersion = 26)
    fun navigationRecoveryRetriesAcquisitionAndNativeFailureOnTheOutgoingScreen() {
        exerciseOutgoingRecovery(closeAfterNativeFailure = false)
    }

    @Test
    @SdkSuppress(minSdkVersion = 26)
    fun navigationRecoveryCloseCancelsTheDestinationWithoutJourneyCallbacks() {
        exerciseOutgoingRecovery(closeAfterNativeFailure = true)
    }

    private fun exerciseOutgoingRecovery(closeAfterNativeFailure: Boolean) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        assertTrue(NuxieRuntime.shared.isAvailable)
        val fixture = loadPublishedFixture(instrumentation)
        val screenIds = instrumentation.context.assets.open("journeys/planes/persistent-navigation-android.json")
            .bufferedReader().use { Json.parseToJsonElement(it.readText()).jsonObject }
            .getValue("screens").jsonArray.map { it.jsonPrimitive.content }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val attempts = java.util.concurrent.atomic.AtomicInteger()
        val destinationCloses = java.util.concurrent.atomic.AtomicInteger()
        val checkpoints = java.util.concurrent.atomic.AtomicInteger()
        val service = ExperiencePresentationService(instrumentation.targetContext, { _, _, _ -> }, scope,
            { NuxieRuntime.shared.isAvailable })
        val monitor = Instrumentation.ActivityMonitor(NuxieExperienceActivity::class.java.name, null, false)
        instrumentation.addMonitor(monitor)
        val invalid = File.createTempFile("navigation-recovery", ".riv", instrumentation.targetContext.cacheDir).apply { writeText("invalid native fixture") }
        var next: Deferred<ai.nuxie.sdk.ExperienceRef>? = null
        try {
            val initial = scope.async { service.presentJourney(fixture.release, screenIds[0], "recover-navigation", "recover-owner",
                service.reserveJourney("recover-owner"), acquire = { AcquiredJourneyRelease(fixture.release.identity,
                    fixture.assets, fixture.riv, protection = Closeable {}) },
                onScreenDismissed = { _, _, _ -> checkpoints.incrementAndGet(); JourneyScreenDismissalResult.HANDLED }, onOutcome = {}) }
            val activity = checkNotNull(monitor.waitForActivityWithTimeout(10_000))
            runBlocking { kotlinx.coroutines.withTimeout(30_000) { initial.await() } }
            val sourceId = checkNotNull(activity.intent.getStringExtra(NuxieExperienceActivity.EXTRA_PRESENTATION_ID))
            next = scope.async { service.presentJourney(fixture.release, screenIds[1], "recover-navigation", "recover-owner", null,
                acquire = {
                    val attempt = attempts.incrementAndGet()
                    if (attempt == 1) throw java.io.IOException("Fixture acquisition failure")
                    AcquiredJourneyRelease(fixture.release.identity, fixture.assets, if (attempt == 2) invalid else fixture.riv,
                        protection = Closeable { destinationCloses.incrementAndGet() })
                }, onOutcome = {}) }
            fun descendants(view: View): List<View> = listOf(view) + if (view is ViewGroup)
                (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList()
            for (generation in 1L..2L) {
                runBlocking { kotlinx.coroutines.withTimeout(15_000) {
                    while ((PresentationRegistry.observe(sourceId)?.value as? PresentationContentState.Ready)?.navigationRecovery
                            ?.let { it.progress.generation == generation && it.progress.phase == AcquisitionProgress.Phase.FAILED } != true) {
                        kotlinx.coroutines.delay(20)
                    }
                } }
                assertHostedScreen(instrumentation, activity, screenIds[0])
                assertFalse(checkNotNull(next).isCompleted)
                assertEquals(0, checkpoints.get())
                assertEquals((generation - 1).toInt(), destinationCloses.get())
                val closing = closeAfterNativeFailure && generation == 2L
                var retry: android.widget.Button? = null
                val deadline = SystemClock.elapsedRealtime() + 5_000
                while (retry == null && SystemClock.elapsedRealtime() < deadline) {
                    instrumentation.runOnMainSync {
                        retry = descendants(activity.window.decorView).filterIsInstance<android.widget.Button>()
                            .singleOrNull { it.text == (if (closing) "Close" else "Retry") && it.isEnabled }
                    }
                    if (retry == null) SystemClock.sleep(20)
                }
                instrumentation.runOnMainSync { assertTrue(checkNotNull(retry).performClick()) }
                if (closing) {
                    runBlocking { kotlinx.coroutines.withTimeout(30_000) { checkNotNull(next).join() } }
                    assertTrue(checkNotNull(next).isCancelled)
                    assertEquals(2, attempts.get())
                    assertEquals(1, checkpoints.get())
                    assertEquals(1, monitor.hits)
                    assertEquals(1, destinationCloses.get())
                    return
                }
            }
            runBlocking { kotlinx.coroutines.withTimeout(30_000) { checkNotNull(next).await() } }
            assertHostedScreen(instrumentation, activity, screenIds[1])
            assertEquals(3, attempts.get())
            assertEquals(1, checkpoints.get())
            assertEquals(1, monitor.hits)
            assertEquals(1, destinationCloses.get())
        } finally {
            runBlocking { next?.cancelAndJoin(); service.shutdownOwnedBy("recover-owner"); scope.coroutineContext[Job]?.cancelAndJoin() }
            invalid.delete()
            instrumentation.removeMonitor(monitor)
            PresentationRegistry.clearForTesting()
        }
        assertEquals(2, destinationCloses.get())
    }

    @Test
    @SdkSuppress(minSdkVersion = 26)
    fun nativeResponseIsDurableBeforeItsAuthoredJourneyNavigation() = exerciseDurableNativeEmission(false)

    @Test
    @SdkSuppress(minSdkVersion = 26)
    fun compiledScriptResponseIsDurableBeforeItsAuthoredJourneyNavigation() = exerciseDurableNativeEmission(true)

    @Test
    @SdkSuppress(minSdkVersion = 26)
    fun failedCompiledScriptClosesWithoutCommittingPartialResponses() = exerciseDurableNativeEmission(true, true)

    @Test
    @SdkSuppress(minSdkVersion = 26)
    fun accessibilityTextEditIsDurableBeforeAuthoredJourneyNavigation() =
        exerciseDurableNativeEmission(false, accessibilityEdit = true)

    @Test
    @SdkSuppress(minSdkVersion = 26)
    fun backgroundCancelsPressedCompiledControlBeforeFreshGesture() =
        exerciseDurableNativeEmission(true, interruptPress = true)

    private fun exerciseDurableNativeEmission(scripted: Boolean, failScript: Boolean = false, accessibilityEdit: Boolean = false, interruptPress: Boolean = false) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        assertTrue(NuxieRuntime.shared.isAvailable)
        val fixture = loadPublishedFixture(instrumentation, if (failScript) "journeys/rendered-screen-control-error" else if (scripted) "journeys/rendered-screen-control" else "journeys/rendered-text-input")
        val owner = "published-durable-${UUID.randomUUID()}"
        val directory = File(context.cacheDir, owner).apply { mkdirs() }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val store = SQLiteEventStore(context, databaseFile = File(directory, "events.db"))
        val presentations = ExperiencePresentationService(context, { _, _, _ -> }, scope, { NuxieRuntime.shared.isAvailable })
        val monitor = Instrumentation.ActivityMonitor(NuxieExperienceActivity::class.java.name, null, false)
        instrumentation.addMonitor(monitor)
        val accepted = LinkedBlockingQueue<JourneyScreenEmissionBatch>()
        val activeRequest = AtomicReference<JourneyPresentationRequest?>()
        val presentationCount = java.util.concurrent.atomic.AtomicInteger()
        val navigationPresented = CountDownLatch(1)
        val initiallyRevealed = CountDownLatch(1)
        val terminalOutcomes = LinkedBlockingQueue<JourneySurfaceOutcome>()
        val errorDismissals = LinkedBlockingQueue<JourneyScreenDismissalResult>()
        val failureCheckpointResponses = AtomicReference<JsonObject?>()
        val responsesBeforeNavigation = AtomicReference<JsonObject?>()
        val downloaded = java.util.concurrent.CopyOnWriteArrayList<String>()
        val renderKey = fixture.release.descriptor.getValue("render").jsonObject.getValue("riv").jsonObject.getValue("key").jsonPrimitive.content
        val artifactFiles = fixture.assets + (renderKey to fixture.riv)
        val artifactAcquirer = ai.nuxie.sdk.experiences.JourneyReleaseArtifactAcquirer(
            ai.nuxie.sdk.experiences.JourneyReleaseArtifactCache(context,
                ai.nuxie.sdk.network.HttpTransport { request ->
                    val key = request.url.path.trimStart('/')
                    val file = checkNotNull(artifactFiles[key]) { "Unexpected artifact request: $key" }
                    downloaded += key
                    ai.nuxie.sdk.network.HttpTransport.Response(200, file.readBytes(),
                        mapOf("Content-Type" to if (key.endsWith(".riv")) "application/vnd.rive" else "application/octet-stream"))
                }, cacheDirectory = File(directory, "artifacts")))
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
                val presentationNumber = presentationCount.incrementAndGet()
                if (presentationNumber > 1) {
                    responsesBeforeNavigation.set(JourneyRunJournal(directory, owner, JourneyStorageScope(authority))
                        .runs().single().context.getValue("responses").jsonObject)
                }
                presentations.presentJourney(request.release, request.screenId, request.journeyId,
                    request.ownerDistinctId, request.reservation, request.canPresent,
                    acquire = {
                        if (scripted) artifactAcquirer.acquire(request.release, checkNotNull(catalog.snapshot(owner)).profile.delivery)
                        else AcquiredJourneyRelease(fixture.release.identity, fixture.assets, fixture.riv, protection = Closeable {})
                    },
                    nextBatchSequence = request.nextBatchSequence, nextEmissionSequence = request.nextEmissionSequence,
                    onScreenChanged = request.onScreenChanged, onScreenDismissed = { screenId, nextScreen, method ->
                        if (method == "error") failureCheckpointResponses.set(
                            JourneyRunJournal(directory, owner, JourneyStorageScope(authority)).runs().single()
                                .context.getValue("responses").jsonObject)
                        request.onScreenDismissed(screenId, nextScreen, method).also {
                            if (method == "error") errorDismissals.add(it)
                        }
                    },
                    onEmissionBatch = { batch ->
                        val committed = request.onEmissionBatch(batch)
                        if (committed) accepted.add(batch)
                        committed
                    }, onPresentationRevealed = { id ->
                        request.onPresentationRevealed(id)
                        initiallyRevealed.countDown()
                    }, onOutcome = { outcome ->
                        request.onOutcome(outcome)
                        terminalOutcomes.add(outcome)
                    })
                if (presentationNumber > 1) navigationPresented.countDown()
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
            if (scripted) {
                assertTrue("Script gesture requires the revealed presentation", initiallyRevealed.await(10, TimeUnit.SECONDS))
                var surface: ExperienceSurfaceHost? = null
                val deadline = SystemClock.uptimeMillis() + 10_000
                while (surface == null && SystemClock.uptimeMillis() < deadline) {
                    instrumentation.runOnMainSync {
                        fun find(view: View): ExperienceSurfaceHost? = if (view is ExperienceSurfaceHost) view
                            else if (view is ViewGroup) (0 until view.childCount).firstNotNullOfOrNull { find(view.getChildAt(it)) } else null
                        surface = find(first.window.decorView)?.takeIf { it.isShown && it.width > 1 && it.height > 1 && it.isAvailable }
                    }
                    if (surface == null) SystemClock.sleep(20)
                }
                val target = checkNotNull(surface)
                var downTime = SystemClock.uptimeMillis()
                fun dispatch(action: Int) = instrumentation.runOnMainSync {
                    if (action == MotionEvent.ACTION_DOWN) downTime = SystemClock.uptimeMillis()
                    val scale = minOf(target.width / 390f, target.height / 844f)
                    val x = (target.width - 390f * scale) / 2f + 100f * scale
                    val y = (target.height - 844f * scale) / 2f + 728f * scale
                    val event = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action, x, y, 0)
                    try { assertTrue(target.dispatchTouchEvent(event)) } finally { event.recycle() }
                }
                if (interruptPress) {
                    dispatch(MotionEvent.ACTION_DOWN)
                    awaitDeliveredPointer(target)
                    val stopped = CountDownLatch(1)
                    val resumed = CountDownLatch(1)
                    val callbacks = object : Application.ActivityLifecycleCallbacks {
                        override fun onActivityStopped(activity: Activity) { if (activity === first) stopped.countDown() }
                        override fun onActivityResumed(activity: Activity) { if (activity === first) resumed.countDown() }
                        override fun onActivityCreated(activity: Activity, state: Bundle?) = Unit
                        override fun onActivityStarted(activity: Activity) = Unit
                        override fun onActivityPaused(activity: Activity) = Unit
                        override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) = Unit
                        override fun onActivityDestroyed(activity: Activity) = Unit
                    }
                    first.application.registerActivityLifecycleCallbacks(callbacks)
                    try {
                        assertTrue(instrumentation.uiAutomation.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME))
                        assertTrue("Home must stop the pressed control's Activity", stopped.await(10, TimeUnit.SECONDS))
                        context.startActivity(Intent(first.intent).apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                        })
                        assertTrue("Return must resume the original Activity", resumed.await(10, TimeUnit.SECONDS))
                        instrumentation.waitForIdleSync()
                        val deadline = SystemClock.uptimeMillis() + 10_000
                        var ready = false
                        while (!ready && SystemClock.uptimeMillis() < deadline) {
                            instrumentation.runOnMainSync { ready = target.isShown && target.isAvailable }
                            if (!ready) SystemClock.sleep(20)
                        }
                        assertTrue("Retained control surface must return", ready)
                        dispatch(MotionEvent.ACTION_UP)
                        assertEquals("Stale release must not publish an action", null, accepted.poll(500, TimeUnit.MILLISECONDS))
                        assertFalse(journalRun().context.getValue("responses").jsonObject.containsKey("selection"))
                        assertEquals(1, presentationCount.get())
                    } finally { first.application.unregisterActivityLifecycleCallbacks(callbacks) }
                }
                dispatch(MotionEvent.ACTION_DOWN)
                dispatch(MotionEvent.ACTION_UP)
            } else {
                val field = awaitEditor(instrumentation, first, "text-input/screen_1/email_input")
                if (accessibilityEdit) editUsingAccessibility(instrumentation, "durable@example.com")
                else edit(instrumentation, field, "durable@example.com")
            }
            if (failScript) {
                assertEquals(JourneyScreenDismissalResult.COMPLETED, errorDismissals.poll(10, TimeUnit.SECONDS))
                val completedJournal = JourneyRunJournal(directory, owner, JourneyStorageScope(authority))
                assertEquals("abandoned", checkNotNull(completedJournal.checkmark(fixture.release.identity.experienceId)).outcome)
                assertTrue(completedJournal.runs().isEmpty())
                assertTrue(terminalOutcomes.isEmpty())
                assertFalse(checkNotNull(failureCheckpointResponses.get()).containsKey("selection"))
                assertEquals(null, accepted.poll(300, TimeUnit.MILLISECONDS))
                assertEquals(1, presentationCount.get())
                assertEquals(artifactFiles.keys, downloaded.toSet())
                val deadline = SystemClock.uptimeMillis() + 10_000
                var destroyed = false
                while (!destroyed && SystemClock.uptimeMillis() < deadline) {
                    instrumentation.runOnMainSync { destroyed = first.isDestroyed }
                    if (!destroyed) SystemClock.sleep(20)
                }
                assertTrue("Failed script must drain and destroy its Activity", destroyed)
                return
            }
            val batch = checkNotNull(accepted.poll(10, TimeUnit.SECONDS)) { "Journey service must durably accept native input" }
            val reopened = journalRun()
            val responseKey = if (scripted) "selection" else "email"
            val expectedValue = if (scripted) "pro" else "durable@example.com"
            assertEquals(expectedValue, reopened.context.getValue("responses").jsonObject.getValue(responseKey).jsonPrimitive.content)
            assertEquals(batch.batchSequence + 1, reopened.nextPresentationBatchSequence)
            assertEquals(batch.emissions.last().sequence + 1, reopened.nextPresentationEmissionSequence)
            if (scripted) {
                runBlocking { kotlinx.coroutines.withTimeout(10_000) {
                    while (responsesBeforeNavigation.get() == null) kotlinx.coroutines.delay(20)
                } }
                assertEquals("pro", checkNotNull(responsesBeforeNavigation.get()).getValue("selection").jsonPrimitive.content)
                assertEquals(listOf("\$response_set", "script_control_activated"), batch.emissions.map { it.name })
                assertEquals("compiled", batch.emissions.last().payload.getValue("source").jsonPrimitive.content)
                assertEquals(batch.emissions.first().sequence + 1, batch.emissions.last().sequence)
                val captured = runBlocking { checkNotNull(store.stableEvent(batch.emissions.last().id)) }
                assertEquals("script_control_activated", captured.name)
                assertEquals(owner, captured.distinctId)
                assertTrue("Authored navigation must finish presenting", navigationPresented.await(10, TimeUnit.SECONDS))
                assertEquals(2, presentationCount.get())
                assertEquals(if (interruptPress) 2 else 1, monitor.hits)
                assertEquals(artifactFiles.keys, downloaded.toSet())
                assertEquals(2, downloaded.size)
                assertEquals(null, accepted.poll(300, TimeUnit.MILLISECONDS))
                return
            }
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
            assertHostedScreen(instrumentation, first, "screen_2")
            assertEquals(1, monitor.hits)
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

    private fun composedSurface(instrumentation: Instrumentation, surface: TextureView, fixedBounds: Rect? = null): Bitmap {
        val deadline = SystemClock.elapsedRealtime() + 5_000
        do {
            val bounds = fixedBounds?.let { Rect(it) } ?: Rect()
            if (fixedBounds == null) instrumentation.runOnMainSync { surface.getGlobalVisibleRect(bounds) }
            val display = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
            try {
                val after = Rect()
                if (fixedBounds == null) instrumentation.runOnMainSync { surface.getGlobalVisibleRect(after) }
                // Capture and layout are asynchronous during window/rotation changes.
                // Retry the whole observation; never clamp away content under test.
                if ((fixedBounds != null || bounds == after) && bounds.width() > 0 && bounds.height() > 0 &&
                    bounds.left >= 0 && bounds.top >= 0 && bounds.right <= display.width && bounds.bottom <= display.height) {
                    val cropped = Bitmap.createBitmap(display, bounds.left, bounds.top, bounds.width(), bounds.height())
                    try { return checkNotNull(cropped.copy(Bitmap.Config.ARGB_8888, false)) }
                    finally { if (cropped !== display) cropped.recycle() }
                }
            } finally { display.recycle() }
            SystemClock.sleep(50)
        } while (SystemClock.elapsedRealtime() < deadline)
        error("Surface bounds did not stabilize inside the composed display")
    }

    private fun copySurfaceAtSize(surface: TextureView, width: Int, height: Int): Bitmap {
        val deadline = SystemClock.elapsedRealtime() + 10_000
        do {
            val bitmap = copySurface(surface)
            if (bitmap.width == width && bitmap.height == height) return bitmap
            bitmap.recycle()
            SystemClock.sleep(50)
        } while (SystemClock.elapsedRealtime() < deadline)
        error("Rendered surface did not restore its expected extent ${width}x${height}")
    }

    // Direct renderer fixtures stand in for the Journey's durable admission owner.
    // They must explicitly acknowledge the new host handoff before inspecting pixels.
    private fun approveFixtureFrame(id: String) = runBlocking {
        assertTrue("Fixture host must accept its admitted frame", PresentationRegistry.reveal(id))
    }

    private fun stableSurface(surface: TextureView): Bitmap {
        var previous = copySurface(surface)
        var unchanged = 0
        val deadline = SystemClock.elapsedRealtime() + 5_000
        while (unchanged < 3 && SystemClock.elapsedRealtime() < deadline) {
            SystemClock.sleep(50)
            val next = copySurface(surface)
            unchanged = if (previous.width == next.width && previous.height == next.height &&
                changedPixels(previous, next, Rect(0, 0, previous.width, previous.height)) == 0) unchanged + 1 else 0
            previous.recycle()
            previous = next
        }
        assertEquals("Text edit must settle in the rendered surface before testing preservation", 3, unchanged)
        return previous
    }

    private fun assertHostedScreen(instrumentation: Instrumentation, host: Activity, screenId: String) {
        val deadline = SystemClock.elapsedRealtime() + 10_000
        var observed: String? = null
        while (observed != screenId && SystemClock.elapsedRealtime() < deadline) {
            instrumentation.runOnMainSync {
                observed = host.intent.getStringExtra(NuxieExperienceActivity.EXTRA_PRESENTATION_ID)
                    ?.let(PresentationRegistry::resolve)?.screenId
            }
            if (observed != screenId) SystemClock.sleep(20)
        }
        instrumentation.runOnMainSync {
            assertFalse(host.isDestroyed)
            assertFalse(host.isFinishing)
            val id = checkNotNull(host.intent.getStringExtra(NuxieExperienceActivity.EXTRA_PRESENTATION_ID))
            assertEquals(screenId, checkNotNull(PresentationRegistry.resolve(id)).screenId)
            assertTrue(PresentationRegistry.currentScreen(id)?.purchaseActivity() === host)
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

    private fun loadPublishedFixture(
        instrumentation: Instrumentation,
        fixture: String = "journeys/rendered-text-input",
    ): PublishedFixture {
        check(NuxieRuntime.shared.isAvailable) { "Published fixture requires the native runtime" }
        val context = instrumentation.targetContext
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
        val scripts = (release.descriptor["screenBehaviors"] as? JsonArray).orEmpty().mapNotNull {
            (it.jsonObject["script"] as? JsonObject)?.get("artifact")?.jsonObject
        }
        val assets = (render.getValue("assets").jsonArray.map { it.jsonObject } + scripts).associate { stage(it) }
        return PublishedFixture(release, riv, assets, entry, trustedKeys)
    }

    private fun awaitDeliveredPointer(surface: ExperienceSurfaceHost) {
        // Observe only the precondition on its owning lane. Durable output is the oracle.
        val lane = ExperienceSurfaceHost::class.java.getDeclaredField("lane").apply { isAccessible = true }
            .get(surface) as NuxieRuntimeLane
        val input = ExperienceSurfaceHost::class.java.getDeclaredField("pointerInput").apply { isAccessible = true }.get(surface)
        val queue = input.javaClass.getDeclaredField("queue").apply { isAccessible = true }.get(input)
        val delivered = queue.javaClass.getDeclaredField("deliveredPointers").apply { isAccessible = true }
        val deadline = SystemClock.uptimeMillis() + 10_000
        while (SystemClock.uptimeMillis() < deadline) {
            val result = java.util.concurrent.atomic.AtomicBoolean()
            val read = CountDownLatch(1)
            assertTrue(lane.enqueue {
                result.set((delivered.get(queue) as Map<*, *>).isNotEmpty())
                read.countDown()
            })
            assertTrue("Pointer precondition must settle on native lane", read.await(10, TimeUnit.SECONDS))
            if (result.get()) return
            SystemClock.sleep(20)
        }
        throw AssertionError("DOWN must be delivered before lifecycle interruption")
    }

    @Suppress("DEPRECATION")
    private fun editUsingAccessibility(instrumentation: Instrumentation, value: String) {
        // Query the system's exposed tree, rather than manufacturing a node from the View.
        val deadline = SystemClock.uptimeMillis() + 10_000
        var editor: android.view.accessibility.AccessibilityNodeInfo? = null
        while (editor == null && SystemClock.uptimeMillis() < deadline) {
            val root = instrumentation.uiAutomation.rootInActiveWindow
            if (root != null) {
                try {
                    val matches = root.findAccessibilityNodeInfosByText("levi@nuxie.dev")
                    editor = matches.firstOrNull { it.isEditable && it.isVisibleToUser }
                    matches.filter { it !== editor }.forEach { it.recycle() }
                } finally { root.recycle() }
            }
            if (editor == null) SystemClock.sleep(20)
        }
        val node = checkNotNull(editor) { "Published editor must be discoverable in the accessibility tree" }
        try {
            assertEquals("android.widget.EditText", node.className.toString())
            assertEquals("you@example.com", node.hintText.toString())
            assertFalse(node.isPassword)
            assertTrue(node.isEnabled)
            assertTrue(node.actionList.any { it.id == android.view.accessibility.AccessibilityNodeInfo.ACTION_SET_TEXT })
            assertTrue(node.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_FOCUS))
            val arguments = android.os.Bundle().apply {
                putCharSequence(android.view.accessibility.AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value)
            }
            assertTrue(node.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_SET_TEXT, arguments))
            assertTrue(node.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLEAR_FOCUS))
        } finally { node.recycle() }
    }

    private fun edit(instrumentation: Instrumentation, field: EditText, value: String) {
        instrumentation.runOnMainSync {
            assertTrue(field.requestFocus())
            field.selectAll()
            assertTrue(checkNotNull(field.onCreateInputConnection(EditorInfo())).commitText(value, 1))
            field.clearFocus()
        }
    }

    private fun findSurface(view: View): TextureView? {
        if (view is TextureView) return view
        if (view is ViewGroup) for (index in 0 until view.childCount) {
            findSurface(view.getChildAt(index))?.let { return it }
        }
        return null
    }

    private fun copySurface(surface: TextureView): Bitmap {
        var bitmap: Bitmap? = null
        InstrumentationRegistry.getInstrumentation().runOnMainSync { bitmap = surface.bitmap }
        return checkNotNull(bitmap) { "Runtime texture must contain a composed frame" }
    }

    private fun changedPixels(before: Bitmap, after: Bitmap, region: Rect): Int {
        var changed = 0
        for (y in region.top until region.bottom) for (x in region.left until region.right) {
            if (before.getPixel(x, y) != after.getPixel(x, y)) changed++
        }
        return changed
    }

}
