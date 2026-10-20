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
    data class RowStep(val outcome: NuxiePlayerStepOutcome, val snapshot: NuxieViewModelSnapshot)
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
    ) {
        var lifecycle = ExperienceScreenLifecycle()
        val completions = linkedMapOf<CompletableDeferred<Unit>, String>()
    }
    private val rows = linkedMapOf<String, Row>()
    private val mounted = mutableListOf<String>()
    private var closed = false
    private var failure: Throwable? = null

    suspend fun mountedScreenIds(): List<String> = operation { mounted.toList() }

    suspend fun insertIncoming(screenId: String, style: ExperienceLayerHostContract.Style,
        isBack: Boolean, products: List<StoreProduct> = emptyList(),
        routing: JourneyRuntimeEmissionCoordinator? = null) = operation {
        require(screenId !in mounted && mounted.size < 2) { "Invalid layer row insertion" }
        val row = rows[screenId] ?: prepareRow(screenId, products, routing).also { rows[screenId] = it }
        artboard.insertDefaultListItem(contract.listPath, incomingIndex(style, isBack, mounted.size), row.values)
        mounted.add(incomingIndex(style, isBack, mounted.size), screenId)
    }

    private fun prepareRow(screenId: String, products: List<StoreProduct>, routing: JourneyRuntimeEmissionCoordinator?): Row {
        val selection = ExperienceLayerHostContract.rowSelection(descriptor, screenId, file.viewModelCatalog())
        val projection = GooglePlayProductViewModelProjection.prepare(descriptor, products, screenId)
        val values = runtime.bindRowViewModel(file, selection.schemaIndex, selection.authoredInstanceIndex,
            selection.defaultInstanceId, ExperienceViewModelBinding.instanceBindings(descriptor), projection)
        try {
            sharedExperience?.let { check(values.linkViewModel("experience", it)) { "Screen lacks its shared Experience link" } }
            return Row(selection, values, products.toList(), ExperienceTextInput.forScreen(descriptor, screenId), routing)
        } catch (error: Throwable) {
            runCatching { values.close() }.exceptionOrNull()?.let(error::addSuppressed)
            throw error
        }
    }

    /** Both rows' phase writes complete before another operation may advance the player. */
    suspend fun setPhases(phases: List<Pair<String, ExperienceScreenLifecycle.Phase>>, transition: String = "",
        exitReason: ExperienceScreenLifecycle.ExitReason = ExperienceScreenLifecycle.ExitReason.NAVIGATION) = operation {
        require(phases.map { it.first }.distinct().size == phases.size) { "Duplicate Screen phase" }
        phases.forEach { row(it.first) }
        for ((id, phase) in phases) {
            val row = row(id)
            val values = row.lifecycle.move(phase, transition, exitReason)
            for ((path, value) in values) {
                if (path.startsWith("env/")) continue
                if (path == "fontScale" && !row.values.snapshot().containsValue(path)) continue
                if (path == "screen/exitReason" && !row.values.supportsEnumPath(path)) continue
                row.values.setValue(path, value)
            }
        }
    }

    /** Journey values are root-relative within this Screen; lifecycle remains host-owned. */
    suspend fun applyJourneyValues(screenId: String, values: Map<String, NuxieViewModelScalarValue>) = operation {
        val row = row(screenId)
        for ((path, value) in journeyValues(values)) row.values.setValue(path, value)
    }

    suspend fun updateEnvironment(values: Map<String, NuxieViewModelScalarValue>) = operation {
        for ((path, value) in values) checkNotNull(environment) { "Layer has no env" }.setValue(path, value)
    }

    suspend fun snapshot(screenId: String): NuxieViewModelSnapshot = operation { row(screenId).values.snapshot() }
    suspend fun lifecycle(screenId: String): Map<String, NuxieViewModelScalarValue> = operation { row(screenId).lifecycle.snapshot() }
    suspend fun textInputs(screenId: String): List<ExperienceTextInput> = operation { row(screenId).textInputs }

    suspend fun registerCompletion(screenId: String, completeEventName: String): Deferred<Unit> = operation {
        require(completeEventName.isNotEmpty()) { "Empty completion event" }
        CompletableDeferred<Unit>().also { row(screenId).completions[it] = completeEventName }
    }

    /** A watchdog retires its waiters explicitly before removing the outgoing row. */
    suspend fun removeCompletion(screenId: String, completion: Deferred<Unit>) = operation {
        row(screenId).completions.keys.firstOrNull { it === completion }?.let {
            row(screenId).completions.remove(it)
            it.cancel()
        }
    }

    suspend fun remove(screenId: String, correlationId: ULong = 0uL): Step = operation {
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
        correlationId: ULong = 0uL): Step = operation { stepLocked(elapsedSeconds, pointers, correlationId) }

    private fun stepLocked(elapsedSeconds: Double, pointers: List<NuxiePlayerPointerEvent>, correlationId: ULong): Step = try {
        projectStep(elapsedSeconds, pointers, correlationId)
    } catch (error: Throwable) {
        fail(error)
        throw error
    }

    private fun projectStep(elapsedSeconds: Double, pointers: List<NuxiePlayerPointerEvent>, correlationId: ULong): Step {
        val outcome = player.stepTyped(elapsedSeconds = elapsedSeconds, pointers = pointers, correlationId = correlationId)
        val snapshots = mounted.associateWith { row(it).values.captureSnapshot(outcome.viewModelChanges).values }
        val owners = outcome.events.map { owner(it, snapshots) }
        val projected = snapshots.mapValues { (id, snapshot) ->
            val events = outcome.events.filterIndexed { index, _ -> owners[index] == id }
            val row = row(id)
            resolveCompletions(row.completions, events)
            RowStep(outcome.copy(events = events, hostCommands = emptyList(),
                viewModelChanges = outcome.viewModelChanges.filter { snapshot.containsInstance(it.ownerInstanceId.toLong()) }), snapshot)
        }
        return Step(outcome, projected, outcome.events.filterIndexed { index, _ -> owners[index] == null }, correlationId)
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

    suspend fun setLayoutSize(width: Float, height: Float) = operation { player.setLayoutSize(width, height) }
    suspend fun layoutSize(): Pair<Float, Float> = operation { player.layoutSize() }

    suspend fun close() = lane.call {
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
    private fun fail(error: Throwable) {
        if (failure == null) failure = error
        rows.values.forEach { row ->
            row.completions.keys.forEach { it.completeExceptionally(error) }
            row.completions.clear()
        }
    }

    private suspend fun <T> operation(block: () -> T): T = lane.call {
        check(!closed) { "Layer is closed" }
        failure?.let { throw IllegalStateException("Layer player failed", it) }
        try { block() } catch (error: NuxieRuntimeCallException) {
            fail(error)
            throw error
        }
    }

    companion object {
        /** The caller retains the file and lane until the layer has closed. */
        suspend fun open(lane: NuxieRuntimeLane, runtime: NuxieRuntime, file: NuxieRuntimeFile,
            descriptor: JsonObject, sharedExperience: NuxieRuntimeViewModelState? = null): ExperienceInteractiveLayer = lane.call {
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
                ExperienceInteractiveLayer(lane, runtime, file, descriptor, contract, artboard, player, environment, sharedExperience)
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
