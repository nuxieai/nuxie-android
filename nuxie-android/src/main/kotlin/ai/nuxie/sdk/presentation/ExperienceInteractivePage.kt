package ai.nuxie.sdk.presentation

import ai.nuxie.sdk.logging.NuxieLog as Log
import ai.nuxie.sdk.billing.StoreProduct
import ai.nuxie.sdk.experiences.ExperiencePageHostContract
import ai.nuxie.sdk.experiences.ExperienceViewModelBinding
import ai.nuxie.sdk.runtime.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.serialization.json.JsonObject

/** One native page's player. Every native operation stays on its owning lane. */
internal class ExperienceInteractivePage private constructor(
    private val lane: NuxieRuntimeLane,
    private val runtime: NuxieRuntime,
    private val file: NuxieRuntimeFile,
    private val descriptor: JsonObject,
    private val contract: ExperiencePageHostContract.Host,
    val artboard: NuxieRuntimeArtboard,
    val player: NuxieRuntimePlayer,
    internal val environment: NuxieRuntimeViewModelState?,
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
        val selection: ExperiencePageHostContract.RowSelection,
        val values: NuxieRuntimeViewModelState,
        val products: List<StoreProduct>,
        val textInputs: List<ExperienceTextInput>,
        val routing: JourneyRuntimeEmissionCoordinator?,
        var lifecycle: ExperienceScreenLifecycle,
    ) {
        var retained: NuxieCapturedViewModelSnapshot? = null
        val completions = linkedMapOf<CompletableDeferred<Unit>, String>()
    }
    private val rows = linkedMapOf<String, Row>()
    private val mounted = mutableListOf<String>()
    private val removedNativeOwners = mutableMapOf<Long, String>()
    private val loggedRemovedWrites = mutableSetOf<String>()
    private val collapsed = mutableSetOf<String>()
    private var liveStep: Pair<String, String?>? = null
    private var rowsNeedReconciliation = false
    private var closed = false
    private var failure: Throwable? = null

    suspend fun mountedScreenIds(): List<String> = lane.call { mountedScreenIdsOnLane() }

    internal fun mountedScreenIdsOnLane(): List<String> = checked { mounted.toList() }

    suspend fun insertIncoming(screenId: String, style: ExperiencePageHostContract.Style,
        isBack: Boolean, products: List<StoreProduct> = emptyList(),
        routing: JourneyRuntimeEmissionCoordinator? = null, source: String? = null) =
        lane.call { insertIncomingOnLane(screenId, style, isBack, products, routing, source) }

    internal fun insertIncomingOnLane(screenId: String, style: ExperiencePageHostContract.Style,
        isBack: Boolean, products: List<StoreProduct> = emptyList(),
        routing: JourneyRuntimeEmissionCoordinator? = null, source: String? = null) = checked {
        prepareOnLane(screenId, products, routing)
        takeIncomingOnLane(screenId, source, isBack)
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
            if (row.retained != null) {
                rows[screenId] = prepareRow(screenId, row.products, routing ?: row.routing, null,
                    checkNotNull(row.retained).values, row.lifecycle)
            }
            return@checked false
        }
        rows[screenId] = prepareRow(screenId, products, routing, projection, snapshot, lifecycle ?: ExperienceScreenLifecycle())
        true
    }

    internal fun insertOnLane(screenId: String, index: Int) = checked {
        require(screenId !in mounted && index == mounted.size) { "New page rows must append" }
        artboard.insertDefaultListItem(contract.listPath, index, valuesOnLane(screenId))
        mounted.add(screenId)
        rowsNeedReconciliation = true
    }

    suspend fun takeIncoming(screenId: String, source: String, isBack: Boolean) =
        lane.call { takeIncomingOnLane(screenId, source, isBack) }

    internal fun takeIncomingOnLane(screenId: String, source: String? = null, isBack: Boolean = false,
        correlationId: ULong = 0uL, capture: (Step) -> Unit = {}) = checked {
        val isNew = screenId !in mounted
        if (isNew) insertOnLane(screenId, mounted.size)
        // New forward rows stay on top; every Back uses the outgoing row's position.
        if ((!isNew || isBack) && source != null && source != screenId) {
            val from = mounted.indexOf(screenId)
            val remaining = mounted.filter { it != screenId }
            val sourceIndex = remaining.indexOf(source)
            require(sourceIndex >= 0) { "Outgoing row is absent" }
            val to = sourceIndex + if (isBack) 0 else 1
            if (from != to) {
                artboard.moveDefaultListItem(contract.listPath, from, to)
                mounted.removeAt(from)
                mounted.add(to, screenId)
                rowsNeedReconciliation = true
            }
        }
        // Bindings rebuild hosted rows on step, not on the list notification.
        // Stay on this lane through expansion and the caller's phase/begin batch.
        if (rowsNeedReconciliation) capture(stepOnLane(0.0, correlationId = correlationId))
        if (screenId in collapsed) setCollapsedOnLane(screenId, false)
    }

    internal fun setCollapsedOnLane(screenId: String, value: Boolean) = checked {
        check(!rowsNeedReconciliation) { "Row indexes must be reconciled before collapse" }
        val index = mounted.indexOf(screenId)
        require(index >= 0) { "Screen is absent from the page" }
        player.setRowCollapsed(contract.listPath, index, value)
        if (value) collapsed.add(screenId) else collapsed.remove(screenId)
        Unit
    }

    suspend fun setCollapsed(screenId: String, value: Boolean) = lane.call { setCollapsedOnLane(screenId, value) }

    suspend fun isCollapsed(screenId: String): Boolean = lane.call { screenId in collapsed }

    suspend fun begin(screenId: String, transition: String?) = lane.call { beginOnLane(screenId, transition) }
    suspend fun end(screenId: String, transition: String?) = lane.call { endOnLane(screenId, transition) }
    suspend fun retire(reachable: Set<String>, active: String) = lane.call { retireOnLane(reachable, active) }

    internal fun beginOnLane(screenId: String, transition: String?) = checked {
        check(liveStep == null) { "Page step already active" }
        check(screenId in mounted && screenId !in collapsed) { "Incoming row must be expanded" }
        player.stepBegin(ExperiencePageHostContract.screenArtboard(descriptor, screenId), transition)
        liveStep = screenId to transition
    }

    internal fun endOnLane(screenId: String, transition: String?) = checked {
        check(liveStep == screenId to transition) { "Page step does not match" }
        player.stepEnd(ExperiencePageHostContract.screenArtboard(descriptor, screenId), transition)
        liveStep = null
    }

    internal fun cancelStepOnLane(source: String, target: String, correlationId: ULong = 0uL,
        capture: (Step) -> Unit = {}) = checked {
        liveStep?.let { (incoming, transition) -> endOnLane(incoming, transition) }
        // Retirement may have removed the source, even when step_end already ran.
        // Restore its retained values into a fresh instance before taking it back.
        prepareOnLane(source)
        takeIncomingOnLane(source, target.takeIf { it in mounted }, isBack = true,
            correlationId = correlationId, capture = capture)
        beginOnLane(source, null)
        endOnLane(source, null)
    }

    /** The new history determines all reachable rows, including repeated visits. */
    internal fun retireOnLane(reachable: Set<String>, active: String) = checked {
        // Collapse while native indexes still match. Removals reconcile on the next ordinary step.
        mounted.filter { it in reachable && it != active && it !in collapsed }
            .forEach { setCollapsedOnLane(it, true) }
        mounted.filter { it !in reachable }.forEach { removeOnLane(it) }
    }

    suspend fun mountInitial(screenId: String, entering: Boolean) = lane.call { mountInitialOnLane(screenId, entering) }

    internal fun mountInitialOnLane(screenId: String, entering: Boolean) = checked {
        if (screenId !in mounted) insertOnLane(screenId, mounted.size)
        if (entering && row(screenId).lifecycle.phase == ExperienceScreenLifecycle.Phase.HIDDEN)
            setPhasesOnLane(listOf(screenId to ExperienceScreenLifecycle.Phase.ENTERING))
        else applyLifecycleOnLane(screenId)
    }

    internal fun valuesOnLane(screenId: String): NuxieRuntimeViewModelState = checked { requireNotNull(writableRow(screenId)) { "Unknown page row" }.values }

    suspend fun applyRuntimeValues(screenId: String, values: Map<String, NuxieViewModelScalarValue>) =
        lane.call { applyRuntimeValuesOnLane(screenId, values) }

    internal fun applyRuntimeValuesOnLane(screenId: String, values: Map<String, NuxieViewModelScalarValue>): Set<String> = checked {
        val row = writableRow(screenId) ?: return@checked emptySet()
        val accepted = mutableSetOf<String>()
        (values["env/fontScale"] as? NuxieViewModelScalarValue.NumberValue)?.let { row.lifecycle.updateFontScale(it.value.toFloat()) }
        (values["env/reduceMotion"] as? NuxieViewModelScalarValue.BooleanValue)?.let { row.lifecycle.updateReduceMotion(it.value) }
        ExperienceScreenRuntimeValues(row.values.supportsEnumPath("screen/exitReason")).apply(values, { path, value ->
            val envPath = when (path) {
                "env/reduceMotion" -> "reduceMotion"
                "env/fontScale" -> "fontScale"
                "safeArea/top", "safeArea/bottom", "safeArea/left", "safeArea/right" -> path
                else -> null
            }
            if (envPath != null) {
                environment?.setValue(envPath, value)
            } else row.values.setValue(path, value)
            accepted += path
        }, { _, error -> throw error })
        accepted
    }

    /** Omitted model names resolve independently for every destination row. */
    internal fun commandOnLane(screenId: String?, viewModelName: String? = null,
        write: (NuxieRuntimeViewModelState) -> Unit) = checked {
        for ((id, row) in rows) if ((screenId == null || screenId == id) &&
            (viewModelName == null || viewModelName == row.selection.rootName))
            writableRow(id)?.let { write(it.values) }
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
        val screenArtboard = checkNotNull(file.newArtboard(ExperiencePageHostContract.screenArtboard(descriptor, screenId))) {
            "Screen artboard is unavailable"
        }
        val schemaIndex = try { screenArtboard.defaultSchemaIndex() } finally { screenArtboard.close() }
        require(rows.values.none { it.selection.schemaIndex == schemaIndex && it !== rows[screenId] }) { "Page Screens require distinct root schemas" }
        val selection = ExperiencePageHostContract.pageRowSelection(descriptor, screenId, file.viewModelCatalog(), schemaIndex)
        require(snapshot == null || snapshot.retainedGraph.rootSchemaIndex == schemaIndex) {
            "Cached Screen schema differs from its published artboard"
        }
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
            val values = row.lifecycle.move(phase, transition, exitReason)
            if (writableRow(id) != null) writeLifecycle(row, values)
        }
    }

    /** Recreated rows restore their current phase without counting another appearance. */
    internal fun applyLifecycleOnLane(screenId: String) = checked {
        val row = writableRow(screenId) ?: return@checked
        writeLifecycle(row, row.lifecycle.snapshot())
    }

    private fun writeLifecycle(row: Row, values: Map<String, NuxieViewModelScalarValue>) {
        for ((path, value) in values) {
            if (path.startsWith("env/")) continue
            if (path == "screen/exitReason" && !row.values.supportsEnumPath(path)) continue
            row.values.setValue(path, value)
        }
    }

    /** Journey values are root-relative within this Screen; lifecycle remains host-owned. */
    suspend fun applyJourneyValues(screenId: String, values: Map<String, NuxieViewModelScalarValue>) = lane.call { applyJourneyValuesOnLane(screenId, values) }

    internal fun applyJourneyValuesOnLane(screenId: String, values: Map<String, NuxieViewModelScalarValue>) = checked {
        val row = writableRow(screenId) ?: return@checked
        for ((path, value) in journeyValues(values)) row.values.setValue(path, value)
    }

    suspend fun updateEnvironment(values: Map<String, NuxieViewModelScalarValue>) = lane.call { updateEnvironmentOnLane(values) }

    internal fun updateEnvironmentOnLane(values: Map<String, NuxieViewModelScalarValue>) = checked {
        for ((path, value) in values) checkNotNull(environment) { "Page has no env" }.setValue(path, value)
    }

    suspend fun snapshot(screenId: String): NuxieViewModelSnapshot = lane.call { snapshotOnLane(screenId) }

    internal fun snapshotOnLane(screenId: String): NuxieViewModelSnapshot = captureRowOnLane(screenId).values

    internal fun captureRowOnLane(screenId: String): NuxieCapturedViewModelSnapshot = checked {
        val row = row(screenId)
        row.retained ?: row.values.captureSnapshot()
    }
    suspend fun lifecycle(screenId: String): Map<String, NuxieViewModelScalarValue> = lane.call { lifecycleOnLane(screenId) }

    internal fun lifecycleOnLane(screenId: String): Map<String, NuxieViewModelScalarValue> = checked { row(screenId).lifecycle.snapshot() }
    suspend fun textInputs(screenId: String): List<ExperienceTextInput> = lane.call { textInputsOnLane(screenId) }

    internal fun textInputsOnLane(screenId: String): List<ExperienceTextInput> = checked { row(screenId).textInputs }

    suspend fun registerCompletion(screenId: String, completeEventName: String): Deferred<Unit> = lane.call { registerCompletionOnLane(screenId, completeEventName) }

    internal fun registerCompletionOnLane(screenId: String, completeEventName: String,
        completion: CompletableDeferred<Unit> = CompletableDeferred()): Deferred<Unit> = checked {
        require(completeEventName.isNotEmpty()) { "Empty completion event" }
        completion.also { row(screenId).completions[it] = completeEventName }
    }

    /** A watchdog retires its waiters explicitly before removing the outgoing row. */
    suspend fun removeCompletion(screenId: String, completion: Deferred<Unit>) = lane.call { removeCompletionOnLane(screenId, completion) }

    internal fun removeCompletionOnLane(screenId: String, completion: Deferred<Unit>) {
        // Cleanup must not replace the original failure after the player has failed or closed.
        rows[screenId]?.completions?.let { waiters ->
            waiters.keys.firstOrNull { it === completion }?.let { waiters.remove(it); it.cancel() }
        }
    }

    suspend fun remove(screenId: String, correlationId: ULong = 0uL): Step = lane.call { removeOnLane(screenId, correlationId) }

    internal fun removeOnLane(screenId: String, correlationId: ULong = 0uL): Step = checked {
        val index = mounted.indexOf(screenId)
        require(index >= 0) { "Screen is absent from the page" }
        check(row(screenId).completions.isEmpty()) { "Screen still awaits completion" }
        val removed = row(screenId)
        val retained = removed.values.captureSnapshot()
        artboard.removeDefaultListItem(contract.listPath, index)
        removed.retained = retained
        val sharedIds = sharedExperience?.nativeSnapshot()?.instances?.map { it.id }.orEmpty().toSet()
        retained.native.instances.filter { it.id !in sharedIds }
            .forEach { removedNativeOwners[it.id] = screenId }
        removed.values.close()
        // Removal reconciles on the next ordinary step; do not add a settlement advance.
        mounted.removeAt(index)
        collapsed.remove(screenId)
        captureOnLane(NuxiePlayerStepOutcome(false, emptyList(), emptyList(), emptyList(), emptyList()), correlationId)
            .also { rowsNeedReconciliation = true }
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
        rowsNeedReconciliation = false
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
            removedNativeOwners.clear()
            loggedRemovedWrites.clear()
            failure?.let { throw it }
        }
    }

    internal fun canWriteNativeOwnerOnLane(ownerId: Long): Boolean = checked {
        val removed = removedNativeOwners[ownerId]
        if (removed == null) true else { logRemovedWrite(removed); false }
    }

    private fun writableRow(id: String): Row? {
        val row = row(id)
        if (row.retained == null) return row
        logRemovedWrite(id)
        return null
    }

    private fun logRemovedWrite(id: String) {
        if (loggedRemovedWrites.add(id)) Log.d("NuxiePage", "Skipped write to removed Screen row")
    }

    private fun row(id: String): Row = requireNotNull(rows[id]) { "Unknown page row" }
    internal fun fail(error: Throwable) {
        if (failure == null) failure = error
        rows.values.forEach { row ->
            row.completions.keys.forEach { it.completeExceptionally(error) }
            row.completions.clear()
        }
    }

    private suspend fun <T> operation(block: () -> T): T = lane.call { checked(block) }

    private fun <T> checked(block: () -> T): T {
        check(!closed) { "Page is closed" }
        failure?.let { throw IllegalStateException("Page player failed", it) }
        return try { block() } catch (error: NuxieRuntimeCallException) {
            fail(error)
            throw error
        }
    }

    companion object {
        /** The caller retains the file and lane until the page has closed. */
        suspend fun open(lane: NuxieRuntimeLane, runtime: NuxieRuntime, file: NuxieRuntimeFile,
            descriptor: JsonObject, sharedExperience: NuxieRuntimeViewModelState? = null,
            host: ExperiencePageHostContract.Host = ExperiencePageHostContract.Host(),
            initializeFixture: ((NuxieRuntimeArtboard) -> Unit)? = null): ExperienceInteractivePage = lane.call {
            openOnLane(lane, runtime, file, descriptor, sharedExperience, host, initializeFixture)
        }

        internal fun openOnLane(lane: NuxieRuntimeLane, runtime: NuxieRuntime, file: NuxieRuntimeFile,
            descriptor: JsonObject, sharedExperience: NuxieRuntimeViewModelState? = null,
            host: ExperiencePageHostContract.Host = ExperiencePageHostContract.Host(),
            initializeFixture: ((NuxieRuntimeArtboard) -> Unit)? = null): ExperienceInteractivePage {
            val contract = host
            val artboard = checkNotNull(file.newArtboard(contract.artboardName)) { "Page host artboard is unavailable" }
            var player: NuxieRuntimePlayer? = null
            var environment: NuxieRuntimeViewModelState? = null
            try {
                artboard.bindDefaultViewModel(contract.modelName)
                initializeFixture?.invoke(artboard)
                val snapshot = checkNotNull(artboard.captureDefaultSnapshot()).native
                check(snapshot.values.any { it.ownerInstanceId == snapshot.rootInstanceId && it.name == contract.listPath &&
                    it.kind == NuxieViewModelPropertyKind.LIST.nativeValue && it.listItemIds.isEmpty() }) {
                    "Page host requires an empty Screen list"
                }
                player = checkNotNull(artboard.newPlayer()) { "Page host requires a default machine" }
                player.requireStateMachine(contract.machineName)
                environment = file.newGlobalViewModel("env")
                environment?.let { player.bindGlobalViewModel("env", it) }
                return ExperienceInteractivePage(lane, runtime, file, descriptor, contract, artboard, player, environment, sharedExperience)
            } catch (error: Throwable) {
                listOf<() -> Unit>({ player?.close() }, { environment?.close() }, { artboard.close() }).forEach {
                    runCatching(it).exceptionOrNull()?.let(error::addSuppressed)
                }
                throw error
            }
        }

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
