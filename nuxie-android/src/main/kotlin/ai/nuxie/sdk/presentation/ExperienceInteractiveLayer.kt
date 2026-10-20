package ai.nuxie.sdk.presentation

import ai.nuxie.sdk.billing.StoreProduct
import ai.nuxie.sdk.experiences.ExperienceLayerHostContract
import ai.nuxie.sdk.experiences.ExperienceViewModelBinding
import ai.nuxie.sdk.runtime.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.serialization.json.JsonObject

/** One layer's player. All native work, including detach settlement, stays on its owning lane. */
internal class ExperienceInteractiveLayer private constructor(
    private val lane: NuxieRuntimeLane,
    private val runtime: NuxieRuntime,
    private val file: NuxieRuntimeFile,
    private val descriptor: JsonObject,
    private val contract: ExperienceLayerHostContract.Host,
    val artboard: NuxieRuntimeArtboard,
    val player: NuxieRuntimePlayer,
    private val environment: NuxieRuntimeViewModelState?,
    private val sharedExperience: NuxieRuntimeViewModelState?,
) {
    data class RowStep(val outcome: NuxiePlayerStepOutcome, val snapshot: NuxieViewModelSnapshot,
        val nativeSnapshot: NativeViewModelSnapshot)
    data class Step(val native: NuxiePlayerStepOutcome, val rows: Map<String, RowStep>,
        val unattributedEvents: List<NuxieRuntimeEvent>, val correlationId: ULong) {
        // The released ABI has no source instance for script host commands.
        val unattributedHostCommands: List<NuxieHostCommand> get() = native.hostCommands
    }
    private class Row(
        val selection: ExperienceLayerHostContract.RowSelection,
        val values: NuxieRuntimeViewModelState,
        val products: List<StoreProduct>,
        val textInputs: List<ExperienceTextInput>,
        val routing: JourneyRuntimeEmissionCoordinator?,
        var lifecycle: ExperienceScreenLifecycle,
    ) {
        val completions = linkedMapOf<CompletableDeferred<Unit>, String>()
    }
    private val rows = linkedMapOf<String, Row>()
    private val mounted = mutableListOf<String>()
    private var closed = false
    private var failure: Throwable? = null

    suspend fun mountedScreenIds(): List<String> = lane.call { mountedScreenIdsOnLane() }

    internal fun mountedScreenIdsOnLane(): List<String> = checked { mounted.toList() }

    suspend fun insertIncoming(screenId: String, style: ExperienceLayerHostContract.Style,
        isBack: Boolean, products: List<StoreProduct> = emptyList(),
        routing: JourneyRuntimeEmissionCoordinator? = null) = lane.call { insertIncomingOnLane(screenId, style, isBack, products, routing) }

    internal fun insertIncomingOnLane(screenId: String, style: ExperienceLayerHostContract.Style,
        isBack: Boolean, products: List<StoreProduct> = emptyList(),
        routing: JourneyRuntimeEmissionCoordinator? = null) = checked {
        require(screenId !in mounted && mounted.size < 2) { "Invalid layer row insertion" }
        prepareOnLane(screenId, products, routing)
        insertOnLane(screenId, incomingIndex(style, isBack, mounted.size))
    }

    suspend fun prepare(screenId: String, products: List<StoreProduct> = emptyList(),
        routing: JourneyRuntimeEmissionCoordinator? = null): Boolean = lane.call { prepareOnLane(screenId, products, routing) }

    internal fun prepareOnLane(screenId: String, products: List<StoreProduct> = emptyList(),
        routing: JourneyRuntimeEmissionCoordinator? = null,
        projection: NuxieViewModelListProjection? = null, snapshot: NuxieViewModelSnapshot? = null,
        lifecycle: ExperienceScreenLifecycle? = null): Boolean = checked {
        rows[screenId]?.let { row ->
            lifecycle?.let {
                it.restoreRetainedState(row.lifecycle)
                row.lifecycle = it
            }
            return@checked false
        }
        rows[screenId] = prepareRow(screenId, products, routing, projection, snapshot, lifecycle ?: ExperienceScreenLifecycle())
        true
    }

    internal fun insertOnLane(screenId: String, index: Int) = checked {
        require(screenId !in mounted && mounted.size < 2 && index in 0..mounted.size) { "Invalid layer row insertion" }
        artboard.insertDefaultListItem(contract.listPath, index, row(screenId).values)
        mounted.add(index, screenId)
    }

    internal fun valuesOnLane(screenId: String): NuxieRuntimeViewModelState = checked { row(screenId).values }

    internal fun applyRuntimeValuesOnLane(screenId: String, values: Map<String, NuxieViewModelScalarValue>): Set<String> = checked {
        val row = row(screenId)
        val accepted = mutableSetOf<String>()
        (values["fontScale"] as? NuxieViewModelScalarValue.NumberValue)?.let { row.lifecycle.updateFontScale(it.value.toFloat()) }
        (values["env/reduceMotion"] as? NuxieViewModelScalarValue.BooleanValue)?.let { row.lifecycle.updateReduceMotion(it.value) }
        ExperienceScreenRuntimeValues(row.values.supportsEnumPath("screen/exitReason")).apply(values, { path, value ->
            if (path == "fontScale" && !row.values.snapshot().containsValue(path)) return@apply
            val envPath = when (path) {
                "env/reduceMotion" -> "reduceMotion"
                "safeArea/top", "safeArea/bottom", "safeArea/left", "safeArea/right" -> path
                else -> null
            }
            if (envPath != null) {
                environment?.setValue(envPath, value)
                if (path == "env/reduceMotion" && row.values.snapshot().containsValue(path)) row.values.setValue(path, value)
            } else row.values.setValue(path, value)
            accepted += path
        }, { _, error -> throw error })
        accepted
    }

    /** Omitted model names resolve independently for every destination row. */
    internal fun commandOnLane(screenId: String?, viewModelName: String? = null,
        write: (NuxieRuntimeViewModelState) -> Unit) = checked {
        for ((id, row) in rows) if ((screenId == null || screenId == id) &&
            (viewModelName == null || viewModelName == row.selection.rootName)) write(row.values)
    }

    suspend fun applyValues(screenId: String?, values: Map<String, NuxieViewModelScalarValue>, viewModelName: String? = null) = operation {
        commandOnLane(screenId, viewModelName) { row -> journeyValues(values).forEach { (path, value) -> row.setValue(path, value) } }
    }

    suspend fun applyListOperation(screenId: String?, write: NativeViewModelWrite, viewModelName: String? = null) = operation {
        require(write.kind in setOf(NuxieViewModelMutationKind.LIST_INSERT, NuxieViewModelMutationKind.LIST_REMOVE,
            NuxieViewModelMutationKind.LIST_CLEAR, NuxieViewModelMutationKind.LIST_MOVE))
        if (write.path.substringBefore('/') !in setOf("screen", "env")) commandOnLane(screenId, viewModelName) { it.restoreWrites(listOf(write)) }
    }

    suspend fun fireTrigger(screenId: String?, path: String, viewModelName: String? = null) = operation {
        if (path.substringBefore('/') !in setOf("screen", "env")) commandOnLane(screenId, viewModelName) { it.fireTrigger(path) }
    }

    suspend fun stepFromRow(screenId: String, correlationId: ULong = 0uL): Step = operation {
        row(screenId)
        stepLocked(0.0, emptyList(), correlationId)
    }

    private fun prepareRow(screenId: String, products: List<StoreProduct>, routing: JourneyRuntimeEmissionCoordinator?,
        preparedProjection: NuxieViewModelListProjection?, snapshot: NuxieViewModelSnapshot?, lifecycle: ExperienceScreenLifecycle): Row {
        val selection = ExperienceLayerHostContract.rowSelection(descriptor, screenId, file.viewModelCatalog())
        val projection = preparedProjection ?: GooglePlayProductViewModelProjection.prepare(descriptor, products, screenId)
        val values = snapshot?.let { runtime.restoreRowViewModel(file, it.withoutRootProperty("experience")) } ?: runtime.bindRowViewModel(file, selection.schemaIndex, selection.authoredInstanceIndex,
            selection.defaultInstanceId, ExperienceViewModelBinding.instanceBindings(descriptor), projection)
        try {
            sharedExperience?.let { check(values.linkViewModel("experience", it)) { "Screen lacks its shared Experience link" } }
            return Row(selection, values, products.toList(), ExperienceTextInput.forScreen(descriptor, screenId), routing, lifecycle)
        } catch (error: Throwable) {
            runCatching { values.close() }.exceptionOrNull()?.let(error::addSuppressed)
            throw error
        }
    }

    /** Both rows' phase writes complete before another operation may advance the player. */
    suspend fun setPhases(phases: List<Pair<String, ExperienceScreenLifecycle.Phase>>, transition: String = "",
        exitReason: ExperienceScreenLifecycle.ExitReason = ExperienceScreenLifecycle.ExitReason.NAVIGATION) = lane.call { setPhasesOnLane(phases, transition, exitReason) }

    internal fun setPhasesOnLane(phases: List<Pair<String, ExperienceScreenLifecycle.Phase>>, transition: String = "",
        exitReason: ExperienceScreenLifecycle.ExitReason = ExperienceScreenLifecycle.ExitReason.NAVIGATION) = checked {
        require(phases.map { it.first }.distinct().size == phases.size) { "Duplicate Screen phase" }
        phases.forEach { row(it.first) }
        for ((id, phase) in phases) {
            val row = row(id)
            writeLifecycle(row, row.lifecycle.move(phase, transition, exitReason))
        }
    }

    /** Recreated rows restore their current phase without counting another appearance. */
    internal fun applyLifecycleOnLane(screenId: String) = checked {
        val row = row(screenId)
        writeLifecycle(row, row.lifecycle.snapshot())
    }

    private fun writeLifecycle(row: Row, values: Map<String, NuxieViewModelScalarValue>) {
        for ((path, value) in values) {
            if (path.startsWith("env/")) continue
            if (path == "fontScale" && !row.values.snapshot().containsValue(path)) continue
            if (path == "screen/exitReason" && !row.values.supportsEnumPath(path)) continue
            row.values.setValue(path, value)
        }
    }

    /** Journey values are root-relative within this Screen; lifecycle remains host-owned. */
    suspend fun applyJourneyValues(screenId: String, values: Map<String, NuxieViewModelScalarValue>) = lane.call { applyJourneyValuesOnLane(screenId, values) }

    internal fun applyJourneyValuesOnLane(screenId: String, values: Map<String, NuxieViewModelScalarValue>) = checked {
        val row = row(screenId)
        for ((path, value) in journeyValues(values)) row.values.setValue(path, value)
    }

    suspend fun updateEnvironment(values: Map<String, NuxieViewModelScalarValue>) = lane.call { updateEnvironmentOnLane(values) }

    internal fun updateEnvironmentOnLane(values: Map<String, NuxieViewModelScalarValue>) = checked {
        for ((path, value) in values) checkNotNull(environment) { "Layer has no env" }.setValue(path, value)
    }

    suspend fun snapshot(screenId: String): NuxieViewModelSnapshot = lane.call { snapshotOnLane(screenId) }

    internal fun snapshotOnLane(screenId: String): NuxieViewModelSnapshot = checked { row(screenId).values.snapshot() }
    suspend fun lifecycle(screenId: String): Map<String, NuxieViewModelScalarValue> = lane.call { lifecycleOnLane(screenId) }

    internal fun lifecycleOnLane(screenId: String): Map<String, NuxieViewModelScalarValue> = checked { row(screenId).lifecycle.snapshot() }
    suspend fun textInputs(screenId: String): List<ExperienceTextInput> = lane.call { textInputsOnLane(screenId) }

    internal fun textInputsOnLane(screenId: String): List<ExperienceTextInput> = checked { row(screenId).textInputs }

    suspend fun registerCompletion(screenId: String, completeEventName: String): Deferred<Unit> = lane.call { registerCompletionOnLane(screenId, completeEventName) }

    internal fun registerCompletionOnLane(screenId: String, completeEventName: String): Deferred<Unit> = checked {
        require(completeEventName.isNotEmpty()) { "Empty completion event" }
        CompletableDeferred<Unit>().also { row(screenId).completions[it] = completeEventName }
    }

    /** A watchdog retires its waiters explicitly before removing the outgoing row. */
    suspend fun removeCompletion(screenId: String, completion: Deferred<Unit>) = lane.call { removeCompletionOnLane(screenId, completion) }

    internal fun removeCompletionOnLane(screenId: String, completion: Deferred<Unit>) = checked {
        row(screenId).completions.keys.firstOrNull { it === completion }?.let {
            row(screenId).completions.remove(it)
            it.cancel()
        }
    }

    suspend fun remove(screenId: String, correlationId: ULong = 0uL): Step = lane.call { removeOnLane(screenId, correlationId) }

    internal fun removeOnLane(screenId: String, correlationId: ULong = 0uL): Step = checked {
        val index = mounted.indexOf(screenId)
        require(index >= 0) { "Screen is absent from the layer" }
        check(row(screenId).completions.isEmpty()) { "Screen still awaits completion" }
        artboard.removeDefaultListItem(contract.listPath, index)
        // https://universe.basis.dev/issue/UNIV-5186 requires settling removal before a timed advance.
        val settled = stepLocked(0.0, emptyList(), correlationId)
        mounted.removeAt(index)
        settled
    }

    suspend fun step(elapsedSeconds: Double, pointers: List<NuxiePlayerPointerEvent> = emptyList(),
        correlationId: ULong = 0uL, focusInputs: List<NuxieFocusInput> = emptyList()): Step = lane.call { stepOnLane(elapsedSeconds, pointers, correlationId, focusInputs) }

    internal fun stepOnLane(elapsedSeconds: Double, pointers: List<NuxiePlayerPointerEvent> = emptyList(),
        correlationId: ULong = 0uL, focusInputs: List<NuxieFocusInput> = emptyList()): Step = checked {
        stepLocked(elapsedSeconds, pointers, correlationId, focusInputs) }

    private fun stepLocked(elapsedSeconds: Double, pointers: List<NuxiePlayerPointerEvent>, correlationId: ULong,
        focusInputs: List<NuxieFocusInput> = emptyList()): Step = try {
        projectStep(elapsedSeconds, pointers, correlationId, focusInputs)
    } catch (error: Throwable) {
        fail(error)
        throw error
    }

    private fun projectStep(elapsedSeconds: Double, pointers: List<NuxiePlayerPointerEvent>, correlationId: ULong,
        focusInputs: List<NuxieFocusInput>): Step {
        val outcome = player.stepTyped(elapsedSeconds = elapsedSeconds, pointers = pointers, correlationId = correlationId,
            focusInputs = focusInputs)
        return captureOnLane(outcome, correlationId)
    }

    internal fun captureOnLane(outcome: NuxiePlayerStepOutcome, correlationId: ULong): Step = checked {
        val captures = mounted.associateWith { row(it).values.captureSnapshot(outcome.viewModelChanges) }
        val snapshots = captures.mapValues { it.value.values }
        val owners = outcome.events.map { owner(it, snapshots) }
        val projected = snapshots.mapValues { (id, snapshot) ->
            val events = outcome.events.filterIndexed { index, _ -> owners[index] == id }
            val row = row(id)
            resolveCompletions(row.completions, events)
            RowStep(outcome.copy(events = events, hostCommands = emptyList(),
                viewModelChanges = outcome.viewModelChanges.filter { snapshot.containsInstance(it.ownerInstanceId.toLong()) }), snapshot, captures.getValue(id).native)
        }
        Step(outcome, projected, outcome.events.filterIndexed { index, _ -> owners[index] == null }, correlationId)
    }

    /** Called after presentation; each existing coordinator owns its Screen's routing tables. */
    suspend fun deliver(step: Step, lifetime: RendererEffectLifetime? = null): Boolean {
        val routing = operation { step.rows.mapValues { (id, _) -> row(id).routing } }
        var accepted = true
        for ((id, result) in step.rows) {
            if (routing[id]?.publish(result.outcome, step.correlationId, lifetime, result.snapshot) == false) accepted = false
        }
        return accepted
    }

    suspend fun setLayoutSize(width: Float, height: Float) = lane.call { player.setLayoutSize(width, height) }
    suspend fun layoutSize(): Pair<Float, Float> = lane.call { layoutSizeOnLane() }

    internal fun layoutSizeOnLane(): Pair<Float, Float> = checked { player.layoutSize() }

    suspend fun close() = lane.call { closeOnLane() }

    internal fun closeOnLane() {
        if (!closed) {
            closed = true
            rows.values.forEach { row -> row.completions.keys.forEach { it.cancel() } }
            var failure: Throwable? = null
            val releases = listOf<() -> Unit>({ player.close() }, { artboard.close() }) +
                rows.values.map { row -> { row.values.close() } } + listOf({ environment?.close(); Unit })
            for (release in releases) try { release() } catch (error: Throwable) {
                if (failure == null) failure = error else failure?.addSuppressed(error)
            }
            rows.clear()
            mounted.clear()
            failure?.let { throw it }
        }
    }

    private fun row(id: String): Row = requireNotNull(rows[id]) { "Unknown layer row" }
    internal fun fail(error: Throwable) {
        if (failure == null) failure = error
        rows.values.forEach { row ->
            row.completions.keys.forEach { it.completeExceptionally(error) }
            row.completions.clear()
        }
    }

    private suspend fun <T> operation(block: () -> T): T = lane.call { checked(block) }

    private fun <T> checked(block: () -> T): T {
        check(!closed) { "Layer is closed" }
        failure?.let { throw IllegalStateException("Layer player failed", it) }
        return try { block() } catch (error: NuxieRuntimeCallException) {
            fail(error)
            throw error
        }
    }

    companion object {
        /** The caller retains the file and lane until the layer has closed. */
        suspend fun open(lane: NuxieRuntimeLane, runtime: NuxieRuntime, file: NuxieRuntimeFile,
            descriptor: JsonObject, sharedExperience: NuxieRuntimeViewModelState? = null): ExperienceInteractiveLayer = lane.call {
            openOnLane(lane, runtime, file, descriptor, sharedExperience)
        }

        internal fun openOnLane(lane: NuxieRuntimeLane, runtime: NuxieRuntime, file: NuxieRuntimeFile,
            descriptor: JsonObject, sharedExperience: NuxieRuntimeViewModelState? = null): ExperienceInteractiveLayer {
            val contract = requireNotNull(ExperienceLayerHostContract.host(descriptor.getValue("render") as JsonObject)) {
                "Release has no layer host"
            }
            val artboard = checkNotNull(file.newArtboard(contract.artboardName)) { "Layer host artboard is unavailable" }
            var player: NuxieRuntimePlayer? = null
            var environment: NuxieRuntimeViewModelState? = null
            try {
                artboard.bindDefaultViewModel()
                val snapshot = checkNotNull(artboard.captureDefaultSnapshot()).native
                check(snapshot.values.any { it.ownerInstanceId == snapshot.rootInstanceId && it.name == contract.listPath &&
                    it.kind == NuxieViewModelPropertyKind.LIST.nativeValue && it.listItemIds.isEmpty() }) {
                    "Layer host requires an empty Screen list"
                }
                player = checkNotNull(artboard.newPlayer()) { "Layer host requires a default machine" }
                player.requireStateMachine()
                environment = file.newGlobalViewModel("env")
                environment?.let { player.bindGlobalViewModel("env", it) }
                return ExperienceInteractiveLayer(lane, runtime, file, descriptor, contract, artboard, player, environment, sharedExperience)
            } catch (error: Throwable) {
                listOf<() -> Unit>({ player?.close() }, { environment?.close() }, { artboard.close() }).forEach {
                    runCatching(it).exceptionOrNull()?.let(error::addSuppressed)
                }
                throw error
            }
        }

        internal fun incomingIndex(style: ExperienceLayerHostContract.Style, isBack: Boolean, count: Int): Int =
            if (style == ExperienceLayerHostContract.Style.PUSH && isBack) 0 else count

        internal fun journeyValues(values: Map<String, NuxieViewModelScalarValue>): Map<String, NuxieViewModelScalarValue> =
            values.filterKeys { it.substringBefore('/') !in setOf("screen", "env") }

        internal fun owner(event: NuxieRuntimeEvent, snapshots: Map<String, NuxieViewModelSnapshot>): String? =
            snapshots.keys.singleOrNull { event.sourceViewModelInstanceId != 0L &&
                snapshots.getValue(it).containsInstance(event.sourceViewModelInstanceId) }

        internal fun resolveCompletions(waiters: MutableMap<CompletableDeferred<Unit>, String>, events: List<NuxieRuntimeEvent>) {
            val names = events.map { it.name }.toSet()
            val completed = waiters.filterValues { it in names }.keys.toList()
            completed.forEach { waiters.remove(it); it.complete(Unit) }
        }
    }
}
