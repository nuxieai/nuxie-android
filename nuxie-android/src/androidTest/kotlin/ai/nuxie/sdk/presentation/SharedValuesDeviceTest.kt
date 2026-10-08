package ai.nuxie.sdk.presentation

import ai.nuxie.sdk.runtime.NativeViewModelWrite
import ai.nuxie.sdk.runtime.NuxieViewModelMutationKind
import ai.nuxie.sdk.runtime.NuxieRuntimeCallException
import ai.nuxie.sdk.runtime.NuxieViewModelScalarValue
import ai.nuxie.sdk.runtime.NuxieFocusInput
import ai.nuxie.sdk.runtime.NuxieFocusState
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class SharedValuesDeviceTest {
    @Test fun publishedF5InstallsResponseRulesBeforeFirstMutation() = runBlocking {
        assertTrue(ai.nuxie.sdk.runtime.NuxieRuntime.shared.isAvailable)
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val assets = instrumentation.context.assets
        fun read(name: String) = assets.open("runtime/forms-saves/$name").use { it.readBytes() }
        val descriptor = Json.parseToJsonElement(read("release.json").decodeToString()).jsonObject
        ai.nuxie.sdk.experiences.JourneySchemaValidator.validate(descriptor)
        assertEquals(setOf("onboarding", "feedback"), descriptor.getValue("responses").jsonObject.keys)
        assertEquals(2, descriptor.getValue("ruleGroups").jsonArray.size)
        val directory = java.io.File(instrumentation.targetContext.cacheDir, "f5-policy-${java.util.UUID.randomUUID()}").apply { mkdirs() }
        try {
            val artifacts = descriptor.getValue("render").jsonObject.getValue("assets").jsonArray
                .map { it.jsonObject }.filter { it["key"] != null }.associate { asset ->
                    val key = asset.getValue("key").jsonPrimitive.content
                    key to java.io.File(directory, asset.getValue("sha256").jsonPrimitive.content).apply { writeBytes(read(key)) }
                }
            repeat(2) {
                val run = ExperienceRunValues()
                try { run.lane.call {
                    val values = checkNotNull(run.prepare(read("screen.riv"), descriptor, artifacts).values)
                    fun feedback(field: String) = values.snapshot().resolveScalar(listOf("responses:feedback", field))
                    assertEquals(NuxieViewModelScalarValue.NumberValue(0.0), feedback("stars"))
                    assertEquals(NuxieViewModelScalarValue.BooleanValue(false), feedback("isset:stars"))
                    assertEquals(NuxieViewModelScalarValue.BooleanValue(false), feedback("valid"))
                    values.setValue("responses:feedback/stars", NuxieViewModelScalarValue.NumberValue(4.0))
                    assertEquals(NuxieViewModelScalarValue.NumberValue(4.0), feedback("stars"))
                    assertEquals(NuxieViewModelScalarValue.BooleanValue(true), feedback("isset:stars"))
                    assertEquals("Unanswered optional interests do not fail minItems",
                        NuxieViewModelScalarValue.BooleanValue(true), feedback("valid"))
                    values.setValue("responses:feedback/stars", NuxieViewModelScalarValue.NumberValue(6.0))
                    assertEquals(NuxieViewModelScalarValue.NumberValue(4.0), feedback("stars"))
                } } finally { run.retire() }
            }
        } finally { directory.deleteRecursively() }
    }

    @Test fun nativePolicyRejectsModelAbsentFromFile() = runBlocking {
        val runtime = ai.nuxie.sdk.runtime.NuxieRuntime.shared
        assertTrue(runtime.isAvailable)
        val assets = InstrumentationRegistry.getInstrumentation().context.assets
        val bytes = assets.open("runtime/run-values/screen.riv").use { it.readBytes() }
        val fonts = Json.parseToJsonElement(assets.open("runtime/run-values/provenance.json")
            .use { it.readBytes() }.decodeToString()).jsonObject.getValue("fonts").jsonArray
        val descriptor = buildJsonObject { putJsonObject("render") {
            put("assets", JsonArray(fonts.map { JsonObject(it.jsonObject + ("kind" to JsonPrimitive("font"))) }))
        } }
        val lane = ai.nuxie.sdk.runtime.NuxieRuntimeLane()
        try { lane.call {
            val fontCache = ai.nuxie.sdk.experiences.SystemFontCache.shared
            val leases = mutableListOf<ai.nuxie.sdk.experiences.SystemFontCache.Lease>()
            val imports = ai.nuxie.sdk.experiences.ExperienceAssetImportBuilder.build(descriptor, emptyMap(),
                checkNotNull(runtime.inspectFileAssets(bytes)),
                systemFontBytes = { fontCache.prepare(it).also(leases::add).candidate.bytes })
            val renderer = checkNotNull(runtime.newAndroidVulkanRenderer(1, 1))
            val rule = ai.nuxie.sdk.runtime.NuxieValueRule("Experience", "trip_days", 2, 1, 25.0,
                "", emptyList(), "", 0, 0, 0, "max", "At most 25")
            try {
                // First prove these bytes, assets and renderer import normally.
                checkNotNull(runtime.importFile(renderer, bytes, imports.expectedAssets,
                    imports.externalAssets)).close()
                assertThrows(ai.nuxie.sdk.runtime.NuxieRuntimeCallException::class.java) {
                    runtime.importFile(renderer, bytes, imports.expectedAssets, imports.externalAssets,
                        valuePolicy = ai.nuxie.sdk.runtime.NuxieValuePolicy(listOf(rule.copy(model = "MissingModel")), emptyList()))?.close()
                }
                fontCache.didImport(leases)
            } catch (error: Throwable) { fontCache.didFailImport(leases); throw error }
            finally { renderer.close() }
        } } finally { lane.shutdown() }
    }

    @Test fun publishedF5ReadsLiveAnswersWithoutFilteringMarkingFailures() = runBlocking {
        assertTrue(ai.nuxie.sdk.runtime.NuxieRuntime.shared.isAvailable)
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val assets = instrumentation.context.assets
        fun read(name: String) = assets.open("runtime/forms-saves/$name").use { it.readBytes() }
        val descriptor = Json.parseToJsonElement(read("release.json").decodeToString()).jsonObject
        val oracle = Json.parseToJsonElement(assets.open("events/response-native-sheets.json")
            .use { it.readBytes() }.decodeToString()).jsonObject
        val directory = java.io.File(instrumentation.targetContext.cacheDir, "f5-sheet-${java.util.UUID.randomUUID()}").apply { mkdirs() }
        val run = ExperienceRunValues()
        try {
            val artifacts = descriptor.getValue("render").jsonObject.getValue("assets").jsonArray
                .map { it.jsonObject }.filter { it["key"] != null }.associate { asset ->
                    val key = asset.getValue("key").jsonPrimitive.content
                    key to java.io.File(directory, asset.getValue("sha256").jsonPrimitive.content).apply { writeBytes(read(key)) }
                }
            run.lane.call { run.prepare(read("screen.riv"), descriptor, artifacts) }
            assertEquals(oracle.getValue("initial"), run.responseAnswers("feedback", descriptor))
            run.lane.call {
                val values = checkNotNull(run.prepare(read("screen.riv"), descriptor, artifacts).values)
                values.setValue("responses:feedback/stars", NuxieViewModelScalarValue.NumberValue(0.0))
                values.setValue("responses:feedback/email", NuxieViewModelScalarValue.StringValue("invalid"))
                values.setValue("responses:feedback/comment", NuxieViewModelScalarValue.StringValue("hello"))
                values.setValue("responses:onboarding/wants_reminder", NuxieViewModelScalarValue.BooleanValue(false))
                values.setValue("responses:onboarding/italian_level", NuxieViewModelScalarValue.StringValue("basics"))
                val snapshot = values.nativeSnapshot()
                val feedback = snapshot.values.single { it.ownerInstanceId == snapshot.rootInstanceId && it.name == "responses:feedback" }.referencedInstanceId
                val ids = snapshot.values.single { it.ownerInstanceId == feedback && it.name == "interests" }.listItemIds
                val child = values.acquireListItem("responses:feedback/interests", 1, ids[1])
                try { child.setValue("picked", NuxieViewModelScalarValue.BooleanValue(true)) } finally { child.close() }
            }
            val feedback = run.responseAnswers("feedback", descriptor)
            val expected = oracle.getValue("feedback").jsonObject
            // JSON 0 and 0.0 carry the same number; kotlinx compares their spelling.
            assertEquals(expected.keys, feedback.keys)
            assertFalse(feedback.getValue("stars").jsonPrimitive.isString)
            assertEquals(expected.getValue("stars").jsonPrimitive.double,
                feedback.getValue("stars").jsonPrimitive.double, 0.0)
            assertEquals(expected - "stars", feedback - "stars")
            assertEquals(oracle.getValue("onboarding"), run.responseAnswers("onboarding", descriptor))
            run.lane.call {
                val values = checkNotNull(run.prepare(read("screen.riv"), descriptor, artifacts).values)
                values.setValue("responses:feedback/isset:stars", NuxieViewModelScalarValue.BooleanValue(false))
                values.setValue("responses:feedback/email", NuxieViewModelScalarValue.StringValue(""))
                values.setValue("responses:feedback/comment", NuxieViewModelScalarValue.StringValue(""))
                val snapshot = values.nativeSnapshot()
                val feedback = snapshot.values.single { it.ownerInstanceId == snapshot.rootInstanceId && it.name == "responses:feedback" }.referencedInstanceId
                val ids = snapshot.values.single { it.ownerInstanceId == feedback && it.name == "interests" }.listItemIds
                val child = values.acquireListItem("responses:feedback/interests", 1, ids[1])
                try { child.setValue("picked", NuxieViewModelScalarValue.BooleanValue(false)) } finally { child.close() }
            }
            assertEquals(oracle.getValue("cleared"), run.responseAnswers("feedback", descriptor))
        } finally { run.retire(); directory.deleteRecursively() }
    }

    @Test fun publishedListChildAcquisitionKeepsIdentityAndRejectsStaleSlot() = runBlocking {
        assertTrue(ai.nuxie.sdk.runtime.NuxieRuntime.shared.isAvailable)
        val assets = InstrumentationRegistry.getInstrumentation().context.assets
        fun read(name: String) = assets.open("runtime/forms-saves/goals/$name").use { it.readBytes() }
        val fonts = Json.parseToJsonElement(read("provenance.json").decodeToString()).jsonObject.getValue("fonts").jsonArray
        val descriptor = buildJsonObject { put("state", JsonObject(emptyMap())); put("responses", JsonObject(emptyMap())); put("ruleGroups", JsonArray(emptyList())); putJsonObject("render") {
            put("assets", JsonArray(fonts.map { JsonObject(it.jsonObject + ("kind" to JsonPrimitive("font"))) }))
        } }
        val run = ExperienceRunValues()
        try {
            run.lane.call {
                val root = checkNotNull(run.prepare(read("screen.riv"), descriptor, emptyMap()).values)
                fun ids() = root.nativeSnapshot().let { snapshot ->
                    snapshot.values.single { it.ownerInstanceId == snapshot.rootInstanceId && it.name == "goals" }.listItemIds.toList()
                }
                val before = ids()
                assertEquals(2, before.size)
                val child = root.acquireListItem("goals", 0, before[0])
                try {
                    assertEquals(before, ids())
                    child.setValue("title", NuxieViewModelScalarValue.StringValue("Rest"))
                    root.restoreWrites(listOf(NativeViewModelWrite(
                        kind = NuxieViewModelMutationKind.LIST_MOVE, path = "goals", index = 0, secondIndex = 1,
                    )))
                    assertEquals(listOf(before[1], before[0]), ids())
                    assertEquals("Rest", root.nativeSnapshot().values.single {
                        it.ownerInstanceId == before[0] && it.name == "title"
                    }.bytesValue.decodeToString())
                    try {
                        root.acquireListItem("goals", 0, before[0]).close()
                        fail("An old list position must not acquire a different row")
                    } catch (error: NuxieRuntimeCallException) {
                        assertEquals("Identity mismatch returns INVALID_ARGUMENT", 5, error.status)
                    }
                    val retained = root.acquireListItem("goals", 1, before[0])
                    try { assertEquals(child.nativeSnapshot().rootInstanceId, retained.nativeSnapshot().rootInstanceId) }
                    finally { retained.close() }
                    root.restoreWrites(listOf(NativeViewModelWrite(
                        kind = NuxieViewModelMutationKind.LIST_REMOVE, path = "goals", index = 1,
                    )))
                    child.setValue("title", NuxieViewModelScalarValue.StringValue("Still retained"))
                    assertEquals(before[0], child.nativeSnapshot().rootInstanceId)
                    assertEquals("Still retained", child.snapshot().resolveString("title"))
                } finally { child.close() }
            }
        } finally { run.retire() }
    }

    @Test fun publishedGoalsCheckpointCapturesAuthoredRows() = runBlocking {
        assertTrue(ai.nuxie.sdk.runtime.NuxieRuntime.shared.isAvailable)
        val assets = InstrumentationRegistry.getInstrumentation().context.assets
        fun read(name: String) = assets.open("runtime/forms-saves/goals/$name").use { it.readBytes() }
        val oracle = Json.parseToJsonElement(read("expectations.json").decodeToString()).jsonObject
        val fonts = Json.parseToJsonElement(read("provenance.json").decodeToString()).jsonObject.getValue("fonts").jsonArray
        val descriptor = buildJsonObject { put("state", JsonObject(emptyMap())); put("responses", JsonObject(emptyMap())); put("ruleGroups", JsonArray(emptyList())); putJsonObject("render") {
            put("assets", JsonArray(fonts.map { JsonObject(it.jsonObject + ("kind" to JsonPrimitive("font"))) }))
        } }
        val run = ExperienceRunValues()
        try {
            run.lane.call {
                val native = run.prepare(read("screen.riv"), descriptor, emptyMap())
                val live = checkNotNull(native.values).nativeSnapshot()
                val list = live.values.single { it.ownerInstanceId == live.rootInstanceId && it.name == "goals" }
                val titles = list.listItemIds.map { id ->
                    live.values.single { it.ownerInstanceId == id && it.name == "title" }.bytesValue.decodeToString()
                }
                assertEquals(listOf("Read", "Walk"), titles)
            }
            val checkpoint = checkNotNull(run.snapshot())
            val decoded = ExperienceRunSnapshot(Json.parseToJsonElement(checkpoint.fields.toString()).jsonArray,
                checkpoint.lists?.let { ExperienceRunListSnapshot.decode(Json.parseToJsonElement(it.encode().toString())) })
            assertEquals(oracle.getValue("startingValues").jsonObject.getValue("goals"), decoded.journeyValues["goals"])
        } finally { run.retire() }
    }

    @Test fun publishedInputFocusTypingAndGreetingShareTheRun() = runBlocking {
        assertTrue(ai.nuxie.sdk.runtime.NuxieRuntime.shared.isAvailable)
        val assets = InstrumentationRegistry.getInstrumentation().context.assets
        fun read(name: String) = assets.open("runtime/published-input/$name").use { it.readBytes() }
        val expected = Json.parseToJsonElement(read("expectations.json").decodeToString()).jsonObject
        val handlers = expected.getValue("handlers").jsonObject
        val fonts = Json.parseToJsonElement(read("provenance.json").decodeToString()).jsonObject.getValue("fonts").jsonArray
        val descriptor = buildJsonObject { put("state", JsonObject(emptyMap())); put("responses", JsonObject(emptyMap())); put("ruleGroups", JsonArray(emptyList())); putJsonObject("render") {
            put("assets", JsonArray(fonts.map { JsonObject(it.jsonObject + ("kind" to JsonPrimitive("font"))) }))
        } }
        val run = ExperienceRunValues()
        try {
            run.lane.call {
                val native = run.prepare(read("screen.riv"), descriptor, emptyMap())
                val shared = checkNotNull(native.values)
                val input = checkNotNull(native.file.newArtboard("input"))
                try {
                    input.bindDefaultViewModel("Runtime input scr_screens_sinput")
                    assertTrue(input.linkDefaultViewModel("experience", shared))
                    val player = native.file.newExperiencePlayer(input, "input")
                    try {
                        player.step(0.0)
                        fun handler(event: String, phase: String) {
                            val item = handlers.getValue(event).jsonObject
                            assertEquals(NuxieViewModelScalarValue.NumberValue(item.getValue(phase).jsonPrimitive.double),
                                checkNotNull(input.defaultViewModelSnapshot()).resolveScalar(
                                    listOf("state", item.getValue("property").jsonPrimitive.content)))
                        }
                        handlers.keys.forEach { handler(it, "before") }
                        assertEquals(expected.getValue("startingValues").jsonObject.getValue("name").jsonPrimitive.content,
                            shared.snapshot().resolveString("name"))
                        assertEquals(NuxieFocusState(true, true),
                            player.stepTyped(elapsedSeconds = 0.0, focusInputs = listOf(NuxieFocusInput.Next)).focusState)
                        handler("focus", "after")
                        player.stepTyped(elapsedSeconds = 0.0,
                            focusInputs = listOf(NuxieFocusInput.Key(269, 0, true, false)))
                        handler("input", "before")
                        val typing = expected.getValue("typing").jsonObject
                        player.stepTyped(elapsedSeconds = 0.0,
                            focusInputs = listOf(NuxieFocusInput.Text(typing.getValue("append").jsonPrimitive.content)))
                        val after = typing.getValue("after").jsonPrimitive.content
                        assertEquals(after, shared.snapshot().resolveString("name"))
                        handler("input", "before")
                        player.step(0.0)
                        handler("input", "after")
                        assertEquals(false,
                            player.stepTyped(elapsedSeconds = 0.0, focusInputs = listOf(NuxieFocusInput.Next)).focusState?.hasFocus)
                        handler("blur", "after")
                        val greeting = checkNotNull(native.file.newArtboard("greeting"))
                        try {
                            greeting.bindDefaultViewModel("Runtime greeting scr_screens_sgreeting")
                            assertTrue(greeting.linkDefaultViewModel("experience", shared))
                            assertEquals(after, checkNotNull(greeting.defaultViewModelSnapshot()).resolveString("experience/name"))
                        } finally { greeting.close() }
                    } finally { player.close() }
                } finally { input.close() }
            }
        } finally { run.retire() }
    }

    @Test fun publishedInputLocatorReadsFocusedOccurrence() = runBlocking {
        assertTrue(ai.nuxie.sdk.runtime.NuxieRuntime.shared.isAvailable)
        val assets = InstrumentationRegistry.getInstrumentation().context.assets
        fun read(name: String) = assets.open("runtime/published-input/$name").use { it.readBytes() }
        val fonts = Json.parseToJsonElement(read("provenance.json").decodeToString()).jsonObject.getValue("fonts").jsonArray
        val descriptor = buildJsonObject { put("state", JsonObject(emptyMap())); put("responses", JsonObject(emptyMap())); put("ruleGroups", JsonArray(emptyList())); putJsonObject("render") {
            put("assets", JsonArray(fonts.map { JsonObject(it.jsonObject + ("kind" to JsonPrimitive("font"))) }))
        } }
        val run = ExperienceRunValues()
        try {
            run.lane.call {
                val native = run.prepare(read("screen.riv"), descriptor, emptyMap())
                val input = checkNotNull(native.file.newArtboard("input"))
                try {
                    input.bindDefaultViewModel("Runtime input scr_screens_sinput")
                    assertTrue(input.linkDefaultViewModel("experience", checkNotNull(native.values)))
                    val player = native.file.newExperiencePlayer(input, "input")
                    try {
                        player.enableSemantics()
                        player.step(0.0)
                        player.stepTyped(elapsedSeconds = 0.0, focusInputs = listOf(NuxieFocusInput.Next))
                        val locator = Json.parseToJsonElement(read("text-inputs.json").decodeToString())
                            .jsonArray.single().jsonObject.getValue("textInputName").jsonPrimitive.content
                        native.renderer.resize(393, 852)
                        native.renderer.renderToCpuFrame(player, 0xff000000.toInt(), 1f)
                        val capture = player.captureSemantics()
                        try {
                            val occurrence = capture.tree.nodes.single { it.role == ai.nuxie.sdk.runtime.NativeSemanticRole.TEXT_FIELD }
                            assertEquals(0, occurrence.stateFlags and (ai.nuxie.sdk.runtime.NativeSemanticState.HIDDEN or
                                ai.nuxie.sdk.runtime.NativeSemanticState.DISABLED))
                            val seed = capture.readFieldString(player.requireHandle(), occurrence.id, locator).decodeToString()
                            assertEquals("Ada", seed)
                        } finally { capture.close() }
                    } finally { player.close() }
                } finally { input.close() }
            }
        } finally { run.retire() }
    }

    @Test fun publishedInputReplacementPreservesNativeCompositionAndCorrection() = runBlocking {
        assertTrue(ai.nuxie.sdk.runtime.NuxieRuntime.shared.isAvailable)
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val activity = instrumentation.startActivitySync(android.content.Intent(instrumentation.targetContext,
            SurfaceCompatibilityHostActivity::class.java).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
        val run = ExperienceRunValues()
        try {
            lateinit var editor: android.widget.EditText
            lateinit var connection: android.view.inputmethod.InputConnection
            instrumentation.runOnMainSync {
                editor = android.widget.EditText(activity)
                editor.showSoftInputOnFocus = false
                activity.setContentView(editor)
                editor.setText("Ada")
                assertTrue(editor.requestFocus())
                connection = checkNotNull(editor.onCreateInputConnection(android.view.inputmethod.EditorInfo()))
            }
            fun read(name: String) = instrumentation.context.assets.open("runtime/published-input/$name").use { it.readBytes() }
            val fonts = Json.parseToJsonElement(read("provenance.json").decodeToString()).jsonObject.getValue("fonts").jsonArray
            val descriptor = buildJsonObject { put("state", JsonObject(emptyMap())); put("responses", JsonObject(emptyMap())); put("ruleGroups", JsonArray(emptyList())); putJsonObject("render") {
                put("assets", JsonArray(fonts.map { JsonObject(it.jsonObject + ("kind" to JsonPrimitive("font"))) }))
            } }
            run.lane.call {
                val native = run.prepare(read("screen.riv"), descriptor, emptyMap())
                val shared = checkNotNull(native.values)
                val input = checkNotNull(native.file.newArtboard("input"))
                try {
                    input.bindDefaultViewModel("Runtime input scr_screens_sinput")
                    assertTrue(input.linkDefaultViewModel("experience", shared))
                    val player = native.file.newExperiencePlayer(input, "input")
                    try {
                        player.step(0.0)
                        player.stepTyped(elapsedSeconds = 0.0, focusInputs = listOf(NuxieFocusInput.Next))
                        fun replace(expected: String, edit: () -> Unit) {
                            var text = ""
                            instrumentation.runOnMainSync { edit(); text = editor.text.toString() }
                            assertEquals(expected, text)
                            player.step(0.0)
                            input.setDefaultViewModelValue("state/typed", NuxieViewModelScalarValue.NumberValue(0.0))
                            val replacement = if (text.isEmpty()) NuxieFocusInput.Key(259, 0, true, false)
                                else NuxieFocusInput.Text(text)
                            player.stepTyped(elapsedSeconds = 0.0, focusInputs = listOf(
                                NuxieFocusInput.Key(65, 8, true, false), replacement))
                            assertEquals(expected, shared.snapshot().resolveString("name"))
                            assertEquals(NuxieViewModelScalarValue.NumberValue(0.0),
                                checkNotNull(input.defaultViewModelSnapshot()).resolveScalar(listOf("state", "typed")))
                            // F3 delivers its input handler on the next advance.
                            player.step(0.0)
                            assertEquals(NuxieViewModelScalarValue.NumberValue(1.0),
                                checkNotNull(input.defaultViewModelSnapshot()).resolveScalar(listOf("state", "typed")))
                        }
                        replace("に") {
                            assertTrue(connection.setSelection(0, editor.length()))
                            assertTrue(connection.setComposingText("に", 1))
                            assertTrue(android.view.inputmethod.BaseInputConnection.getComposingSpanStart(editor.text) >= 0)
                        }
                        replace("日本") {
                            assertTrue(connection.setComposingText("日本", 1))
                            assertTrue(android.view.inputmethod.BaseInputConnection.getComposingSpanStart(editor.text) >= 0)
                        }
                        replace("teh") {
                            assertTrue(connection.finishComposingText())
                            assertTrue(connection.setSelection(0, editor.length()))
                            assertTrue(connection.commitText("teh", 1))
                        }
                        replace("the") {
                            assertTrue(connection.setSelection(0, editor.length()))
                            assertTrue(connection.commitText("the", 1))
                        }
                        replace("") {
                            assertTrue(connection.setSelection(0, editor.length()))
                            assertTrue(connection.commitText("", 1))
                        }
                    } finally { player.close() }
                } finally { input.close() }
            }
        } finally {
            try { run.retire() } finally { instrumentation.runOnMainSync { activity.finish() } }
        }
    }

    @Test fun retainedScreensExcludeSharedValuesAndOtherRunsStartFresh() = runBlocking {
        assertTrue(ai.nuxie.sdk.runtime.NuxieRuntime.shared.isAvailable)
        val assets = InstrumentationRegistry.getInstrumentation().context.assets
        val bytes = assets.open("runtime/shared-values/screen.riv").use { it.readBytes() }
        val run = ExperienceRunValues()
        val other = ExperienceRunValues()
        try {
            run.lane.call {
                val native = run.prepare(bytes, null, emptyMap())
                val values = checkNotNull(native.values)
                val first = checkNotNull(native.file.newArtboard("first"))
                try {
                    first.bindDefaultViewModel("Runtime first scr_screens_sfirst")
                    assertTrue(first.linkDefaultViewModel("experience", values))
                    first.setDefaultViewModelValue("experience/trip_days", NuxieViewModelScalarValue.NumberValue(30.0))
                    val retained = checkNotNull(first.defaultViewModelSnapshot()).withoutRootProperty("experience")
                    assertNull(retained.resolveScalar(listOf("experience", "trip_days")))
                    val later = checkNotNull(native.file.newArtboard("first"))
                    try {
                        val restored = ai.nuxie.sdk.runtime.NuxieRuntime.shared.restoreViewModel(native.file, later, retained)
                        try {
                            assertTrue(restored.linkViewModel("experience", values))
                            assertEquals(NuxieViewModelScalarValue.NumberValue(30.0), restored.snapshot().resolveScalar(listOf("experience", "trip_days")))
                        } finally { restored.close() }
                    } finally { later.close() }
                } finally { first.close() }
                val later = checkNotNull(run.prepare(bytes.copyOf(), null, emptyMap()).file.newArtboard("short"))
                try {
                    later.bindDefaultViewModel("Runtime short scr_screens_sshort")
                    assertTrue(later.linkDefaultViewModel("experience", values))
                    assertEquals(NuxieViewModelScalarValue.NumberValue(30.0), checkNotNull(later.defaultViewModelSnapshot()).resolveScalar(listOf("experience", "trip_days")))
                } finally { later.close() }
            }
            other.lane.call {
                val values = checkNotNull(other.prepare(bytes, null, emptyMap()).values)
                assertEquals(NuxieViewModelScalarValue.NumberValue(23.0), values.snapshot().resolveScalar(listOf("trip_days")))
            }
        } finally { run.retire(); other.retire() }
    }

    @Test fun retirementReleasesTheRunHandleWhileMountedRootCanClose() = runBlocking {
        assertTrue(ai.nuxie.sdk.runtime.NuxieRuntime.shared.isAvailable)
        val bytes = InstrumentationRegistry.getInstrumentation().context.assets.open("runtime/shared-values/screen.riv").use { it.readBytes() }
        val run = ExperienceRunValues()
        run.retainScreen()
        var root: ai.nuxie.sdk.runtime.NuxieRuntimeArtboard? = null
        try {
            val native = run.lane.call {
                run.prepare(bytes, null, emptyMap()).also { prepared ->
                    root = checkNotNull(prepared.file.newArtboard("first"))
                    checkNotNull(root).bindDefaultViewModel("Runtime first scr_screens_sfirst")
                    assertTrue(checkNotNull(root).linkDefaultViewModel("experience", checkNotNull(prepared.values)))
                }
            }
            run.retire()
            run.lane.call {
                assertThrows(IllegalStateException::class.java) { checkNotNull(native.values).snapshot() }
                assertThrows(IllegalStateException::class.java) { run.prepare(bytes, null, emptyMap()) }
                assertEquals(NuxieViewModelScalarValue.NumberValue(23.0), checkNotNull(checkNotNull(root).defaultViewModelSnapshot()).resolveScalar(listOf("experience", "trip_days")))
            }
        } finally {
            try { run.retire() } finally {
                try { run.lane.call { root?.close() } } finally {
                    val closed = kotlinx.coroutines.CompletableDeferred<Unit>()
                    run.releaseScreen { closed.complete(Unit) }
                    closed.await()
                }
            }
        }
    }

    @Test fun fileWithoutExperienceKeepsAuthoredScreenValues() = runBlocking {
        assertTrue(ai.nuxie.sdk.runtime.NuxieRuntime.shared.isAvailable)
        val bytes = InstrumentationRegistry.getInstrumentation().context.assets.open("runtime/purchase-scopes/screen.riv").use { it.readBytes() }
        val run = ExperienceRunValues()
        try {
            run.lane.call {
                val native = run.prepare(bytes, null, emptyMap())
                assertNull(native.values)
                val root = checkNotNull(native.file.newArtboard("Purchase"))
                try {
                    root.bindDefaultViewModel("PurchaseRoot")
                    val player = native.file.newExperiencePlayer(root, "Purchase")
                    try {
                        assertEquals(0, player.step(0.0))
                        assertEquals("plan:annual", checkNotNull(root.defaultViewModelSnapshot()).resolveString("second/placementId"))
                    } finally { player.close() }
                } finally { root.close() }
            }
        } finally { run.retire() }
    }

    @Test fun componentCopyKeepsItsOwnCounter() = runBlocking {
        assertTrue(ai.nuxie.sdk.runtime.NuxieRuntime.shared.isAvailable)
        val assets = InstrumentationRegistry.getInstrumentation().context.assets
        fun read(name: String) = assets.open("runtime/shared-values/$name").use { it.readBytes() }
        val bytes = read("screen.riv")
        val expected = Json.parseToJsonElement(read("expectations.json").decodeToString()).jsonObject.getValue("component").jsonObject
        val fonts = Json.parseToJsonElement(read("provenance.json").decodeToString()).jsonObject.getValue("fonts").jsonArray
        val descriptor = buildJsonObject { put("state", JsonObject(emptyMap())); put("responses", JsonObject(emptyMap())); put("ruleGroups", JsonArray(emptyList())); putJsonObject("render") {
            put("assets", JsonArray(fonts.map { JsonObject(it.jsonObject + ("kind" to JsonPrimitive("font"))) }))
        } }
        val run = ExperienceRunValues()
        try {
            run.lane.call {
                val native = run.prepare(bytes, descriptor, emptyMap())
                val board = checkNotNull(native.file.newArtboard(expected.getValue("screen").jsonPrimitive.content))
                try {
                    board.bindDefaultViewModel("Runtime long scr_screens_slong")
                    assertTrue(board.linkDefaultViewModel("experience", checkNotNull(native.values)))
                    val player = native.file.newExperiencePlayer(board, "long")
                    try {
                        native.renderer.resize(393, 852)
                        var layoutScaleFactor = 1f
                        fun pixels(value: ai.nuxie.sdk.runtime.NuxieRuntimePlayer) =
                            native.renderer.renderToCpuFrame(value, 0xff112233.toInt(), layoutScaleFactor).rgba
                        player.step(0.0)
                        pixels(player)
                        repeat(20) { player.step(0.016) }
                        val initial = pixels(player)
                        var point: Pair<Float, Float>? = null
                        // Measure ink rendered with Android's font and test the copy's own hit region.
                        search@ for (y in 0 until 852) for (x in 0 until 393) {
                            val offset = (y * 393 + x) * 4
                            val background = (y * 393 + 392) * 4
                            if ((0..2).all { initial[offset + it] == initial[background + it] }) continue
                            for (subpixel in listOf(0.125f, 0.375f, 0.625f, 0.875f)) {
                                val px = x + 0.5f
                                val py = y + subpixel
                                val hit = player.stepTyped(elapsedSeconds = 0.0, pointers = listOf(
                                    ai.nuxie.sdk.runtime.NuxiePlayerPointerEvent(ai.nuxie.sdk.runtime.NuxiePlayerPointerKind.DOWN, px, py, 0, 0f)))
                                player.stepTyped(elapsedSeconds = 0.0, pointers = listOf(
                                    ai.nuxie.sdk.runtime.NuxiePlayerPointerEvent(ai.nuxie.sdk.runtime.NuxiePlayerPointerKind.EXIT, px, py, 0, 0f)))
                                if (hit.pointerHits.any { it != ai.nuxie.sdk.runtime.NuxiePlayerPointerHit.NONE }) {
                                    point = px to py
                                    break@search
                                }
                            }
                        }
                        val ink = checkNotNull(point) { "The rendered copy has interactive ink" }
                        fun hits(x: Float, y: Float): Boolean {
                            val result = player.stepTyped(elapsedSeconds = 0.0, pointers = listOf(
                                ai.nuxie.sdk.runtime.NuxiePlayerPointerEvent(ai.nuxie.sdk.runtime.NuxiePlayerPointerKind.DOWN, x, y, 0, 0f)))
                            player.stepTyped(elapsedSeconds = 0.0, pointers = listOf(
                                ai.nuxie.sdk.runtime.NuxiePlayerPointerEvent(ai.nuxie.sdk.runtime.NuxiePlayerPointerKind.EXIT, x, y, 0, 0f)))
                            return result.pointerHits.any { it != ai.nuxie.sdk.runtime.NuxiePlayerPointerHit.NONE }
                        }
                        fun edge(inside: Float, outside: Float, probe: (Float) -> Boolean): Float {
                            var yes = inside
                            var no = outside
                            repeat(20) {
                                val mid = (yes + no) / 2
                                if (probe(mid)) yes = mid else no = mid
                            }
                            return yes
                        }
                        val left = edge(ink.first, 0f) { hits(it, ink.second) }
                        val right = edge(ink.first, 393f) { hits(it, ink.second) }
                        val x = (left + right) / 2
                        val top = edge(ink.second, 0f) { hits(x, it) }
                        val bottom = edge(ink.second, 852f) { hits(x, it) }
                        assertTrue(right > left && bottom > top)
                        val tap = x to (top + bottom) / 2
                        assertArrayEquals("Probing without releasing a press leaves the count unchanged", initial, pixels(player))
                        // Use the device's physical pixel density for the count readback.
                        val density = InstrumentationRegistry.getInstrumentation().targetContext.resources.displayMetrics.density
                        layoutScaleFactor = density
                        native.renderer.resize((393 * density).toInt(), (852 * density).toInt())
                        var previous = pixels(player)
                        val otherBoard = checkNotNull(native.file.newArtboard("long"))
                        try {
                            otherBoard.bindDefaultViewModel("Runtime long scr_screens_slong")
                            assertTrue(otherBoard.linkDefaultViewModel("experience", checkNotNull(native.values)))
                            val other = native.file.newExperiencePlayer(otherBoard, "long")
                            try {
                                other.step(0.0)
                                pixels(other)
                                repeat(20) { other.step(0.016) }
                                val untouched = pixels(other)
                                val days = checkNotNull(native.values).snapshot().resolveScalar(listOf("trip_days"))
                                repeat(2) {
                                    val down = player.stepTyped(elapsedSeconds = 0.0, pointers = listOf(
                                        ai.nuxie.sdk.runtime.NuxiePlayerPointerEvent(ai.nuxie.sdk.runtime.NuxiePlayerPointerKind.DOWN, tap.first, tap.second, 0, 0f)))
                                    val up = player.stepTyped(elapsedSeconds = 0.0, pointers = listOf(
                                        ai.nuxie.sdk.runtime.NuxiePlayerPointerEvent(ai.nuxie.sdk.runtime.NuxiePlayerPointerKind.UP, tap.first, tap.second, 0, 0f)))
                                    assertTrue(down.pointerHits.any { it != ai.nuxie.sdk.runtime.NuxiePlayerPointerHit.NONE })
                                    assertTrue(up.pointerHits.any { it != ai.nuxie.sdk.runtime.NuxiePlayerPointerHit.NONE })
                                    repeat(3) { player.step(1.0 / 60.0) }
                                    val next = pixels(player)
                                    assertFalse("The private copy redraws its count on tap $it at $tap", previous.contentEquals(next))
                                    assertArrayEquals("Another copy sharing the run keeps its own counter", untouched, pixels(other))
                                    assertEquals(days, checkNotNull(native.values).snapshot().resolveScalar(listOf("trip_days")))
                                    previous = next
                                }
                            } finally { other.close() }
                        } finally { otherBoard.close() }
                    } finally { player.close() }
                } finally { board.close() }
            }
        } finally { run.retire() }
    }

    @Test fun rootsShareTheAuthoredRunInstance() = runBlocking {
        assertTrue(ai.nuxie.sdk.runtime.NuxieRuntime.shared.isAvailable)
        val assets = InstrumentationRegistry.getInstrumentation().context.assets
        val bytes = assets.open("runtime/shared-values/screen.riv").use { it.readBytes() }
        val expected = Json.parseToJsonElement(assets.open("runtime/shared-values/expectations.json").bufferedReader().use { it.readText() }).jsonObject
        val provenance = Json.parseToJsonElement(assets.open("runtime/shared-values/provenance.json").bufferedReader().use { it.readText() }).jsonObject
        val descriptor = buildJsonObject {
            putJsonObject("render") {
                put("assets", JsonArray(provenance.getValue("fonts").jsonArray.map {
                    JsonObject(it.jsonObject + ("kind" to JsonPrimitive("font")))
                }))
            }
        }
        val run = ExperienceRunValues()
        try {
            run.lane.call {
                val native = run.prepare(bytes, descriptor, emptyMap())
                val values = checkNotNull(native.values)
                val defaults = values.snapshot()
                val starts = expected.getValue("startingValues").jsonObject
                assertEquals(starts.getValue("trip_days").jsonPrimitive.double, defaults.resolveScalar(listOf("trip_days"))?.let { (it as NuxieViewModelScalarValue.NumberValue).value })
                assertEquals(NuxieViewModelScalarValue.StringValue(starts.getValue("trip").jsonPrimitive.content), defaults.resolveScalar(listOf("trip")))
                assertEquals(NuxieViewModelScalarValue.BooleanValue(starts.getValue("wants_reminder").jsonPrimitive.boolean), defaults.resolveScalar(listOf("wants_reminder")))
                val first = checkNotNull(native.file.newArtboard("first"))
                val second = checkNotNull(native.file.newArtboard("long"))
                try {
                    first.bindDefaultViewModel("Runtime first scr_screens_sfirst")
                    second.bindDefaultViewModel("Runtime long scr_screens_slong")
                    first.linkDefaultViewModel("experience", values)
                    second.linkDefaultViewModel("experience", values)
                    val firstPlayer = checkNotNull(first.newPlayer())
                    val secondPlayer = checkNotNull(second.newPlayer())
                    try {
                    first.setDefaultViewModelValue("experience/trip_days", NuxieViewModelScalarValue.NumberValue(30.0))
                    assertEquals(0, firstPlayer.step(0.0))
                    assertEquals(0, secondPlayer.step(0.0))
                    val firstSnapshot = checkNotNull(first.defaultViewModelSnapshot())
                    val secondSnapshot = checkNotNull(second.defaultViewModelSnapshot())
                    assertTrue(firstSnapshot.containsInstance(defaults.nativeRootInstanceId))
                    assertTrue(secondSnapshot.containsInstance(defaults.nativeRootInstanceId))
                    assertEquals(NuxieViewModelScalarValue.NumberValue(30.0), firstSnapshot.resolveScalar(listOf("experience", "trip_days")))
                    assertEquals(NuxieViewModelScalarValue.NumberValue(30.0), secondSnapshot.resolveScalar(listOf("experience", "trip_days")))
                    } finally { secondPlayer.close(); firstPlayer.close() }
                } finally { second.close(); first.close() }
            }
        } finally { run.retire() }
    }
}
