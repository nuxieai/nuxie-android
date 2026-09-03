package ai.nuxie.sdk.journey

import ai.nuxie.sdk.events.TimeBasedEpochGenerator
import ai.nuxie.sdk.experiences.CacheFilesystemLock
import ai.nuxie.sdk.experiences.JourneyPlaneProfile
import ai.nuxie.sdk.experiences.SignedReleaseEnvelope
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long

internal data class DeviceLegRun(
    val journeyId: String,
    val generation: Long,
    val reference: JsonObject,
    val startedAtMillis: Long,
    val isEnrollment: Boolean,
    val startedEventId: String,
    val completedEventId: String,
    val startedQueued: Boolean = false,
    val stepId: String,
    val park: Park? = null,
    val context: JsonObject,
    val outputs: JsonObject = emptyOutputs(),
    val completion: Completion? = null,
    val effectReceipts: Map<String, String> = emptyMap(),
    val reentry: JourneyReentry? = null,
    val requiresReleasePin: Boolean = false,
) {
    data class Park(val wakeAtMillis: Long?, val anchorAtMillis: Long? = null)
    data class Completion(val outcome: String, val atMillis: Long)
    val id get() = "$journeyId:$generation"
    val experienceId get() = reference.text("experienceId")
}

internal data class DeviceLegCheckmark(
    val journeyId: String,
    val generation: Long,
    val outcome: String,
    val completedAtMillis: Long,
    val lastEnrollmentAtMillis: Long?,
    val reentry: JourneyReentry? = null,
    val lastSeenLiveAtMillis: Long? = null,
)

/** Atomic run/checklist persistence, separate from the ordinary event delivery
 * database. No history or commerce state is read or reset by this journal. */
internal class DeviceLegRunJournal(directory: File, val distinctId: String,
    storageScope: DeviceLegStorageScope = DeviceLegStorageScope.testFixture,
    private val ids: TimeBasedEpochGenerator = TimeBasedEpochGenerator.shared,
    private val maximumRunCount: Int = MAX_RUN_COUNT,
) {
    private val root = File(directory, "device-leg-state-v1")
    private val journals = File(root, "journals")
    private val customerDigest = storageScope.customerDigest(distinctId)
    private val file = File(journals, "$customerDigest.json")
    private val releasePinRoot = File(root, "release-pins")
    private val releasePinDirectory = File(releasePinRoot, customerDigest)
    private val revocationFile = File(root, "$customerDigest.revoked")
    private val lock = CacheFilesystemLock(root)

    private data class Snapshot(
        val runs: MutableMap<String, DeviceLegRun> = linkedMapOf(),
        val checklist: MutableMap<String, DeviceLegCheckmark> = linkedMapOf(),
        val stateArmReceipts: MutableSet<String> = linkedSetOf(),
    )

    /** The caller authenticates the arm's release before admitting it here. */
    fun admit(
        arm: JourneyPlaneProfile.Arm,
        reentry: JourneyReentry,
        entryStepId: String,
        atMillis: Long,
        release: JourneyPlaneProfile.Release? = null,
        stateArmReceipt: String? = null,
    ): DeviceLegRun? = update { state ->
        if (revocationFile.exists()) return@update null
        if (stateArmReceipt != null && stateArmReceipt in state.stateArmReceipts) {
            return@update null
        }
        val experienceId = arm.reference.text("experienceId")
        val previous = state.checklist[experienceId]
        val enrollment = arm.binding.text("type") == "new"
        val journeyId: String
        val generation: Long
        if (enrollment) {
            val latest = state.runs.values.filter { it.experienceId == experienceId && it.isEnrollment }.maxOfOrNull { it.startedAtMillis }
            val last = listOfNotNull(latest, previous?.lastEnrollmentAtMillis).maxOrNull()
            if (last != null) when (reentry) {
                JourneyReentry.OneTime -> return@update null
                JourneyReentry.EveryTime -> Unit
                is JourneyReentry.OncePerWindow -> {
                    check(reentry.windowMillis > 0)
                    if (atMillis < last || atMillis - last < reentry.windowMillis) return@update null
                }
            }
            journeyId = ids.next()
            generation = 0
        } else {
            check(arm.binding.text("type") == "continue")
            journeyId = arm.binding.text("journeyId")
            generation = arm.binding.number("generation")
            if (previous?.journeyId == journeyId && previous.generation >= generation) return@update null
        }
        if (state.runs.size >= maximumRunCount) {
            throw IOException("Device leg run limit exceeded")
        }
        val run = DeviceLegRun(
            journeyId,
            generation,
            arm.reference,
            atMillis,
            enrollment,
            ids.next(),
            ids.next(),
            stepId = entryStepId,
            context = arm.context,
            reentry = reentry,
            requiresReleasePin = release != null,
        )
        if (state.runs.containsKey(run.id)) return@update null
        if (release != null) retainReleasePin(release, arm, state)
        state.runs[run.id] = run
        stateArmReceipt?.let(state.stateArmReceipts::add)
        run
    }

    fun runs(): List<DeviceLegRun> = read { it.runs.values.sortedBy(DeviceLegRun::startedEventId) }
    fun checkmark(experienceId: String): DeviceLegCheckmark? = read { it.checklist[experienceId] }

    fun releasePin(descriptorSha256: String): JsonObject? = read { state ->
        if (state.runs.values.none {
                it.requiresReleasePin && it.reference.text("descriptorSha256") == descriptorSha256
            }
        ) {
            return@read null
        }
        readReleasePin(descriptorSha256)
    }

    fun retainStateArmReceipts(allowed: Set<String>) = update { state ->
        state.stateArmReceipts.retainAll(allowed)
    }

    fun clearStateArmReceipts(entryKind: String) = update { state ->
        state.stateArmReceipts.removeAll { it.startsWith("$entryKind:") }
    }

    /** Retains consumed enrollment state only for currently live policy. */
    fun retainCheckmarks(
        liveExperiences: Map<String, JourneyReentry>,
        atMillis: Long,
    ) = update { state ->
        for ((experienceId, checkmark) in state.checklist.toMap()) {
            val live = liveExperiences[experienceId]
            if (live != null) {
                state.checklist[experienceId] = checkmark.copy(
                    reentry = live,
                    lastSeenLiveAtMillis = atMillis,
                )
                continue
            }
            val retainedPolicy = checkmark.reentry ?: continue
            val lastSeen = checkmark.lastSeenLiveAtMillis ?: continue
            when (retainedPolicy) {
                is JourneyReentry.OncePerWindow -> if (
                    atMillis >= lastSeen &&
                    atMillis - lastSeen >= retainedPolicy.windowMillis
                ) {
                    state.checklist.remove(experienceId)
                }
                JourneyReentry.OneTime, JourneyReentry.EveryTime ->
                    state.checklist.remove(experienceId)
            }
        }
    }

    fun recordResponses(id: String, values: JsonObject) = update { state ->
        val run = checkNotNull(state.runs[id])
        check(run.completion == null)
        state.runs[id] = run.copy(
            context = JsonObject(run.context + ("responses" to JsonObject(run.context.getValue("responses").jsonObject + values))),
        )
    }

    /** Persist one executor transition before another step or effect runs. */
    fun transition(id: String, stepId: String, context: JsonObject,
        checkpoint: DeviceLegControlExecutor.Checkpoint? = null,
    ) = update { state ->
        val run = checkNotNull(state.runs[id])
        check(run.startedQueued && run.completion == null)
        state.runs[id] = run.copy(stepId = stepId, context = context,
            park = checkpoint?.let { DeviceLegRun.Park(it.wakeAtMillis, it.anchorAtMillis) },
            effectReceipts = run.effectReceipts - run.stepId)
    }

    fun park(id: String, stepId: String, untilMillis: Long?) = update { state ->
        val run = checkNotNull(state.runs[id])
        check(run.startedQueued && run.completion == null)
        state.runs[id] = run.copy(stepId = stepId, park = DeviceLegRun.Park(untilMillis))
    }

    /** Stable identity for one visit to an effect cursor. */
    fun claimEffect(id: String, stepId: String): String = update { state ->
        val run = checkNotNull(state.runs[id])
        check(run.startedQueued && run.completion == null && run.stepId == stepId)
        run.effectReceipts[stepId]?.also { existing ->
            state.runs[id] = run.copy(park = null)
            return@update existing
        }
        val effectId = ids.next()
        state.runs[id] = run.copy(
            park = null,
            effectReceipts = run.effectReceipts + (stepId to effectId),
        )
        effectId
    }

    /** Launch recovery preserves expired parks for current-fact evaluation. */
    fun recover(atMillis: Long): List<DeviceLegRun> = update { state ->
        val revoked = revocationFile.exists()
        for ((id, run) in state.runs.toMap()) if (run.completion == null) {
            val validPin = !run.requiresReleasePin || runCatching {
                readReleasePin(run.reference.text("descriptorSha256"))
                    ?.let { releasePinMatches(it, run.reference) } == true
            }.getOrDefault(false)
            if (revoked || run.park == null || !validPin) {
                state.runs[id] = finish(
                    run,
                    outcome = "abandoned",
                    atMillis = atMillis,
                    eventOutputs = run.outputs.getValue("event").jsonObject,
                    responseOutputs = run.context.getValue("responses").jsonObject,
                )
            }
        }
        state.runs.values.filter { it.park != null && it.completion == null }.sortedBy(DeviceLegRun::startedEventId)
    }

    /** Consume durably before executing a continuation; only parks resume. */
    fun resumeParked(id: String): DeviceLegRun = update { state ->
        val run = checkNotNull(state.runs[id])
        check(run.startedQueued && run.park != null && run.completion == null)
        run.copy(park = null).also { state.runs[id] = it }
    }

    /** Identity teardown blocks admission and retires parked work too. */
    fun abandonAll(atMillis: Long) {
        lock.withLock {
            publishRevocationMarker()
            val state = load()
            for ((id, run) in state.runs.toMap()) if (run.completion == null) {
                state.runs[id] = finish(
                    run,
                    outcome = "abandoned",
                    atMillis = atMillis,
                    eventOutputs = run.outputs.getValue("event").jsonObject,
                    responseOutputs = run.context.getValue("responses").jsonObject,
                )
            }
            state.stateArmReceipts.clear()
            persist(state)
        }
    }

    fun finalizeRevocation(): Boolean = lock.withLock {
        if (!revocationFile.exists()) return@withLock true
        if (load().runs.isNotEmpty()) return@withLock false
        if (!revocationFile.delete()) throw IOException("Could not clear device leg revocation")
        true
    }

    fun complete(
        id: String,
        outcome: String,
        atMillis: Long,
        eventOutputs: JsonObject = JsonObject(emptyMap()),
        responseOutputs: JsonObject = JsonObject(emptyMap()),
    ) = update { state ->
        val run = state.runs[id] ?: error("Unknown device leg run")
        if (run.completion == null) {
            check(outcome.isNotEmpty() && outcome.length <= 256)
            // Boundary event fields and outcome become durable together.
            state.runs[id] = finish(
                run,
                outcome,
                atMillis,
                eventOutputs,
                responseOutputs,
            )
        }
    }

    fun markStartedQueued(run: DeviceLegRun) = update { state ->
        val current = state.runs[run.id] ?: return@update
        if (current.startedEventId == run.startedEventId) state.runs[run.id] = current.copy(startedQueued = true)
    }

    fun markCompletionQueued(run: DeviceLegRun) = update { state ->
        val current = state.runs[run.id] ?: return@update
        if (current.completedEventId != run.completedEventId) return@update
        check(current.startedQueued)
        val completion = checkNotNull(current.completion)
        val previous = state.checklist[current.experienceId]
        val newer = previous?.takeIf { it.journeyId == current.journeyId && it.generation > current.generation }
        val enrollmentAt = listOfNotNull(previous?.lastEnrollmentAtMillis,
            current.startedAtMillis.takeIf { current.isEnrollment }).maxOrNull()
        state.checklist[current.experienceId] = DeviceLegCheckmark(
            current.journeyId,
            newer?.generation ?: current.generation,
            newer?.outcome ?: completion.outcome,
            newer?.completedAtMillis ?: completion.atMillis,
            enrollmentAt,
            newer?.reentry ?: current.reentry,
            newer?.lastSeenLiveAtMillis ?: completion.atMillis,
        )
        state.runs.remove(current.id)
        Unit
    }

    // One root transaction covers the journal and retained release inventory.
    private fun <T> read(operation: (Snapshot) -> T): T = lock.withLock { operation(load()) }

    private fun <T> update(operation: (Snapshot) -> T): T = lock.withLock {
        val state = load()
        try {
            val result = operation(state)
            persist(state)
            removeUnreferencedReleasePins(state)
            result
        } catch (error: Throwable) {
            // A release pin precedes its journal reference. Restore the pin
            // inventory to the last durable snapshot after any failed update,
            // including an admission that exits before persistence begins.
            runCatching { removeUnreferencedReleasePins(load()) }
            throw error
        }
    }

    private fun persist(state: Snapshot) {
        val bytes = buildJsonObject {
            put("schemaVersion", JsonPrimitive(VERSION))
            put("runs", JsonObject(state.runs.mapValues { encodeRun(it.value) }))
            put("checklist", JsonObject(state.checklist.mapValues { encodeCheckmark(it.value) }))
            put("stateArmReceipts", JsonArray(state.stateArmReceipts.sorted().map(::JsonPrimitive)))
        }.toString().encodeToByteArray()
        if (bytes.size > MAX_BYTES) throw IOException("Device leg journal exceeds byte limit")
        // Reject non-finite JSON before replacing the last readable snapshot.
        SignedReleaseEnvelope.parseObject(bytes)
        if (!journals.isDirectory && !journals.mkdirs()) throw IOException("Could not create device leg journal directory")
        val temporary = File.createTempFile("journal-", ".tmp", journals)
        try {
            FileOutputStream(temporary).use { stream -> stream.write(bytes); stream.fd.sync() }
            if (!temporary.renameTo(file)) throw IOException("Could not publish device leg journal")
        } finally { temporary.delete() }
    }

    private fun publishRevocationMarker() {
        if (revocationFile.exists()) return
        if (!root.isDirectory && !root.mkdirs()) {
            throw IOException("Could not create device leg state directory")
        }
        val temporary = File.createTempFile("revocation-", ".tmp", root)
        try {
            FileOutputStream(temporary).use { stream ->
                stream.write("revoked\n".encodeToByteArray())
                stream.fd.sync()
            }
            if (!temporary.renameTo(revocationFile)) {
                throw IOException("Could not publish device leg revocation")
            }
        } finally {
            temporary.delete()
        }
    }

    private fun load(source: File = file): Snapshot {
        if (!source.exists()) return Snapshot()
        // InputStream.readNBytes requires a newer Android API than minSdk 23.
        val bytes = source.inputStream().use { input ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (output.size() <= MAX_BYTES) {
                val count = input.read(buffer, 0, minOf(buffer.size, MAX_BYTES + 1 - output.size()))
                if (count < 0) break
                output.write(buffer, 0, count)
            }
            output.toByteArray()
        }
        if (bytes.size > MAX_BYTES) throw IOException("Device leg journal exceeds byte limit")
        val value = SignedReleaseEnvelope.parseObject(bytes)
        check(value.text("schemaVersion") == VERSION) { "Unsupported device leg journal version" }
        val runs = value.getValue("runs").jsonObject.mapValues { decodeRun(it.value.jsonObject) }.toMutableMap()
        check(runs.all { (id, run) -> id == run.id }) { "Invalid device leg run identity" }
        val checklist = value.getValue("checklist").jsonObject.mapValues { (_, entry) ->
            val mark = entry.jsonObject
            DeviceLegCheckmark(
                mark.text("journeyId"),
                mark.number("generation"),
                mark.text("outcome"),
                mark.number("completedAtMillis"),
                mark["lastEnrollmentAtMillis"]?.jsonPrimitive?.long,
                mark["reentry"]?.jsonObject?.let(::decodeReentry),
                mark["lastSeenLiveAtMillis"]?.jsonPrimitive?.long,
            )
        }.toMutableMap()
        val receipts = (value["stateArmReceipts"] as? JsonArray).orEmpty().mapTo(linkedSetOf()) {
            it.jsonPrimitive.content
        }
        return Snapshot(runs, checklist, receipts)
    }

    private fun encodeRun(run: DeviceLegRun) = buildJsonObject {
        put("journeyId", JsonPrimitive(run.journeyId)); put("generation", JsonPrimitive(run.generation))
        put("reference", run.reference); put("startedAtMillis", JsonPrimitive(run.startedAtMillis))
        put("isEnrollment", JsonPrimitive(run.isEnrollment)); put("startedEventId", JsonPrimitive(run.startedEventId))
        put("completedEventId", JsonPrimitive(run.completedEventId)); put("startedQueued", JsonPrimitive(run.startedQueued))
        put("stepId", JsonPrimitive(run.stepId)); put("context", run.context); put("outputs", run.outputs)
        put("effectReceipts", JsonObject(run.effectReceipts.mapValues { JsonPrimitive(it.value) }))
        run.reentry?.let { put("reentry", encodeReentry(it)) }
        put("requiresReleasePin", JsonPrimitive(run.requiresReleasePin))
        run.park?.let { park -> put("park", buildJsonObject {
            park.wakeAtMillis?.let { put("wakeAtMillis", JsonPrimitive(it)) }
            park.anchorAtMillis?.let { put("anchorAtMillis", JsonPrimitive(it)) }
        }) }
        run.completion?.let { completion -> put("completion", buildJsonObject {
            put("outcome", JsonPrimitive(completion.outcome)); put("atMillis", JsonPrimitive(completion.atMillis))
        }) }
    }

    private fun decodeRun(value: JsonObject) = DeviceLegRun(
        journeyId = value.text("journeyId"), generation = value.number("generation"), reference = value.getValue("reference").jsonObject,
        startedAtMillis = value.number("startedAtMillis"), isEnrollment = value.getValue("isEnrollment").jsonPrimitive.boolean,
        startedEventId = value.text("startedEventId"), completedEventId = value.text("completedEventId"),
        startedQueued = value.getValue("startedQueued").jsonPrimitive.boolean, stepId = value.text("stepId"),
        context = value.getValue("context").jsonObject, outputs = value.getValue("outputs").jsonObject,
        park = value["park"]?.jsonObject?.let { DeviceLegRun.Park(it["wakeAtMillis"]?.jsonPrimitive?.long,
            it["anchorAtMillis"]?.jsonPrimitive?.long) },
        completion = value["completion"]?.jsonObject?.let { DeviceLegRun.Completion(it.text("outcome"), it.number("atMillis")) },
        effectReceipts = (value["effectReceipts"] as? JsonObject).orEmpty().mapValues {
            it.value.jsonPrimitive.content
        },
        reentry = value["reentry"]?.jsonObject?.let(::decodeReentry),
        requiresReleasePin = value["requiresReleasePin"]?.jsonPrimitive?.boolean ?: false,
    )

    private fun retainReleasePin(
        release: JourneyPlaneProfile.Release,
        arm: JourneyPlaneProfile.Arm,
        state: Snapshot,
    ) {
        val digest = arm.reference.text("descriptorSha256")
        if (release.envelope.text("descriptorSha256") != digest ||
            release.locator.experienceId != arm.reference.text("experienceId") ||
            release.locator.experienceVersionId != arm.reference.text("versionId") ||
            release.legId != arm.reference.text("legId")
        ) {
            throw IOException("Device leg release pin does not match arm")
        }
        val bytes = encodeRelease(release).toString().encodeToByteArray()
        if (bytes.size > MAX_RELEASE_PIN_BYTES) throw IOException("Device leg release pin exceeds byte limit")
        removeUnreferencedReleasePins(state)
        removeGlobalOrphanReleasePins()
        if (!releasePinDirectory.isDirectory && !releasePinDirectory.mkdirs()) {
            throw IOException("Could not create device leg release pin directory")
        }
        val destination = releasePinFile(digest)
        if (destination.exists()) {
            if (!destination.readBytes().contentEquals(bytes)) {
                throw IOException("Device leg release pin cannot change")
            }
            return
        }
        val inventory = releasePinRoot.walkTopDown().filter { it.isFile && it.extension == "json" }.toList()
        val totalBytes = inventory.fold(0L) { total, item -> Math.addExact(total, item.length()) }
        if (inventory.size >= MAX_RELEASE_PIN_COUNT ||
            Math.addExact(totalBytes, bytes.size.toLong()) > MAX_RELEASE_PIN_TOTAL_BYTES
        ) {
            throw IOException("Device leg release pin budget exceeded")
        }
        val temporary = File.createTempFile("release-", ".tmp", releasePinDirectory)
        try {
            FileOutputStream(temporary).use { stream -> stream.write(bytes); stream.fd.sync() }
            if (!temporary.renameTo(destination)) throw IOException("Could not publish device leg release pin")
        } finally {
            temporary.delete()
        }
    }

    private fun readReleasePin(descriptorSha256: String): JsonObject? {
        if (!DIGEST.matches(descriptorSha256)) throw IOException("Invalid release pin digest")
        val pin = releasePinFile(descriptorSha256)
        if (!pin.exists()) return null
        if (!pin.isFile || pin.length() > MAX_RELEASE_PIN_BYTES) {
            throw IOException("Invalid device leg release pin")
        }
        val entry = SignedReleaseEnvelope.parseObject(pin.readBytes())
        if (entry.keys != setOf("locator", "envelope") ||
            entry.getValue("envelope").jsonObject.text("descriptorSha256") != descriptorSha256
        ) {
            throw IOException("Invalid device leg release pin")
        }
        return entry
    }

    private fun releasePinMatches(entry: JsonObject, reference: JsonObject): Boolean =
        runCatching {
            val locator = entry.getValue("locator").jsonObject
            val envelope = entry.getValue("envelope").jsonObject
            envelope.text("descriptorSha256") == reference.text("descriptorSha256") &&
                locator.text("experienceId") == reference.text("experienceId") &&
                locator.text("experienceVersionId") == reference.text("versionId") &&
                locator.text("legId") == reference.text("legId")
        }.getOrDefault(false)

    private fun removeUnreferencedReleasePins(state: Snapshot) {
        removeUnreferencedReleasePins(releasePinDirectory, state)
    }

    private fun removeUnreferencedReleasePins(directory: File, state: Snapshot) {
        if (!directory.isDirectory) return
        val retained = state.runs.values.filter(DeviceLegRun::requiresReleasePin)
            .map { it.reference.text("descriptorSha256") }.toSet()
        directory.listFiles()?.forEach { item ->
            val validPin = item.isFile && item.extension == "json" &&
                DIGEST.matches(item.nameWithoutExtension)
            if (!validPin || item.nameWithoutExtension !in retained) {
                if (item.isDirectory) item.deleteRecursively() else item.delete()
            }
        }
        if (directory.list().isNullOrEmpty()) directory.delete()
    }

    private fun removeGlobalOrphanReleasePins() {
        if (!releasePinRoot.isDirectory) return
        releasePinRoot.listFiles()?.forEach { customerDirectory ->
            if (customerDirectory == releasePinDirectory) return@forEach
            val digest = customerDirectory.name
            if (!customerDirectory.isDirectory || !DIGEST.matches(digest)) {
                customerDirectory.deleteRecursively()
                return@forEach
            }
            val journalFile = File(journals, "$digest.json")
            if (!journalFile.exists()) {
                customerDirectory.deleteRecursively()
                return@forEach
            }
            runCatching { load(journalFile) }.getOrNull()?.let { state ->
                removeUnreferencedReleasePins(customerDirectory, state)
            }
        }
    }

    private fun releasePinFile(descriptorSha256: String): File {
        if (!DIGEST.matches(descriptorSha256)) throw IOException("Invalid release pin digest")
        return File(releasePinDirectory, "$descriptorSha256.json")
    }

    private fun encodeRelease(release: JourneyPlaneProfile.Release): JsonObject = buildJsonObject {
        put("locator", buildJsonObject {
            put("appId", JsonPrimitive(release.locator.appId))
            put("environment", JsonPrimitive(release.locator.environment))
            put("experienceId", JsonPrimitive(release.locator.experienceId))
            put("experienceVersionId", JsonPrimitive(release.locator.experienceVersionId))
            put("versionNumber", JsonPrimitive(release.locator.versionNumber))
            put("buildId", JsonPrimitive(release.locator.buildId))
            put("publishedAt", JsonPrimitive(release.locator.publishedAt))
            put("publishedAtSeq", JsonPrimitive(release.locator.publishedAtSeq))
            put("legId", JsonPrimitive(release.legId))
        })
        put("envelope", release.envelope)
    }

    private fun encodeCheckmark(mark: DeviceLegCheckmark) = buildJsonObject {
        put("journeyId", JsonPrimitive(mark.journeyId)); put("generation", JsonPrimitive(mark.generation))
        put("outcome", JsonPrimitive(mark.outcome)); put("completedAtMillis", JsonPrimitive(mark.completedAtMillis))
        mark.lastEnrollmentAtMillis?.let { put("lastEnrollmentAtMillis", JsonPrimitive(it)) }
        mark.reentry?.let { put("reentry", encodeReentry(it)) }
        mark.lastSeenLiveAtMillis?.let { put("lastSeenLiveAtMillis", JsonPrimitive(it)) }
    }

    private fun encodeReentry(reentry: JourneyReentry): JsonObject = buildJsonObject {
        when (reentry) {
            JourneyReentry.OneTime -> put("type", JsonPrimitive("one_time"))
            JourneyReentry.EveryTime -> put("type", JsonPrimitive("every_time"))
            is JourneyReentry.OncePerWindow -> {
                put("type", JsonPrimitive("once_per_window"))
                put("windowMillis", JsonPrimitive(reentry.windowMillis))
            }
        }
    }

    private fun decodeReentry(value: JsonObject): JourneyReentry = when (value.text("type")) {
        "one_time" -> JourneyReentry.OneTime
        "every_time" -> JourneyReentry.EveryTime
        "once_per_window" -> JourneyReentry.OncePerWindow(value.number("windowMillis"))
        else -> throw IOException("Invalid retained reentry policy")
    }

    private fun finish(
        run: DeviceLegRun,
        outcome: String,
        atMillis: Long,
        eventOutputs: JsonObject,
        responseOutputs: JsonObject,
    ) = run.copy(
        context = emptyOutputs(),
        outputs = JsonObject(
            mapOf(
                "event" to eventOutputs,
                "responses" to responseOutputs,
            ),
        ),
        completion = DeviceLegRun.Completion(outcome, atMillis),
    )

    private companion object {
        const val VERSION = "nuxie.device-leg-journal.v1"
        // A canonical profile may contribute up to 24 MiB of admitted context.
        // Preserve headroom for cursors, responses, receipts, and checkmarks.
        const val MAX_BYTES = 40 * 1024 * 1024
        const val MAX_RELEASE_PIN_BYTES = 6L * 1024L * 1024L
        const val MAX_RELEASE_PIN_TOTAL_BYTES = 256L * 1024L * 1024L
        const val MAX_RELEASE_PIN_COUNT = 1_024
        const val MAX_RUN_COUNT = 1_024
        val DIGEST = Regex("^[a-f0-9]{64}$")
    }
}

private fun emptyOutputs() = JsonObject(mapOf("event" to JsonObject(emptyMap()), "responses" to JsonObject(emptyMap())))
internal fun deviceLegStateArmReceipt(arm: JourneyPlaneProfile.Arm): String {
    val kind = arm.entryCondition.text("type")
    val bindingType = arm.binding.text("type")
    val fields = buildList {
        add("nuxie.device-leg-state-arm-receipt.v1")
        add(kind)
        add(arm.reference.text("experienceId"))
        add(arm.reference.text("versionId"))
        add(arm.reference.text("legId"))
        add(arm.reference.text("descriptorSha256"))
        add(bindingType)
        if (bindingType == "continue") {
            add(arm.binding.text("journeyId"))
            add(arm.binding.number("generation").toString())
        }
    }
    val digest = MessageDigest.getInstance("SHA-256").apply {
        fields.forEach { field ->
            val bytes = field.encodeToByteArray()
            update(
                byteArrayOf(
                    (bytes.size ushr 24).toByte(),
                    (bytes.size ushr 16).toByte(),
                    (bytes.size ushr 8).toByte(),
                    bytes.size.toByte(),
                ),
            )
            update(bytes)
        }
    }.digest().joinToString("") { "%02x".format(it) }
    return "$kind:$digest"
}
private fun JsonObject.text(key: String) = getValue(key).jsonPrimitive.content
private fun JsonObject.number(key: String) = getValue(key).jsonPrimitive.long
