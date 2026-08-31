package ai.nuxie.sdk.journey

import ai.nuxie.sdk.events.TimeBasedEpochGenerator
import ai.nuxie.sdk.experiences.CacheFilesystemLock
import ai.nuxie.sdk.experiences.JourneyPlaneProfile
import ai.nuxie.sdk.experiences.SignedReleaseEnvelope
import java.io.File
import java.io.ByteArrayOutputStream
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest
import kotlinx.serialization.json.JsonObject
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
) {
    data class Park(val wakeAtMillis: Long?)
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
)

/** Atomic run/checklist persistence, separate from the ordinary event delivery
 * database. No history or commerce state is read or reset by this journal. */
internal class DeviceLegRunJournal(directory: File, val distinctId: String,
    private val ids: TimeBasedEpochGenerator = TimeBasedEpochGenerator.shared,
) {
    private val root = File(directory, "device-leg-journal-v1")
    private val file = File(root, MessageDigest.getInstance("SHA-256").digest(distinctId.encodeToByteArray())
        .joinToString("") { "%02x".format(it) } + ".json")
    private val lock = CacheFilesystemLock(root)

    private data class Snapshot(
        val runs: MutableMap<String, DeviceLegRun> = linkedMapOf(),
        val checklist: MutableMap<String, DeviceLegCheckmark> = linkedMapOf(),
    )

    /** The caller authenticates the arm's release before admitting it here. */
    fun admit(arm: JourneyPlaneProfile.Arm, reentry: JourneyReentry, entryStepId: String, atMillis: Long): DeviceLegRun? = update { state ->
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
        val run = DeviceLegRun(journeyId, generation, arm.reference, atMillis, enrollment, ids.next(), ids.next(),
            stepId = entryStepId, context = arm.context)
        if (state.runs.containsKey(run.id)) return@update null
        if (state.runs.size >= 1024) throw IOException("Device leg run limit exceeded")
        state.runs[run.id] = run
        run
    }

    fun runs(): List<DeviceLegRun> = read { it.runs.values.sortedBy(DeviceLegRun::startedEventId) }
    fun checkmark(experienceId: String): DeviceLegCheckmark? = read { it.checklist[experienceId] }

    fun recordResponses(id: String, values: JsonObject) = update { state ->
        val run = checkNotNull(state.runs[id])
        check(run.completion == null)
        state.runs[id] = run.copy(
            context = JsonObject(run.context + ("responses" to JsonObject(run.context.getValue("responses").jsonObject + values))),
            outputs = JsonObject(run.outputs + ("responses" to JsonObject(run.outputs.getValue("responses").jsonObject + values))),
        )
    }

    fun park(id: String, stepId: String, untilMillis: Long?) = update { state ->
        val run = checkNotNull(state.runs[id])
        check(run.startedQueued && run.completion == null)
        state.runs[id] = run.copy(stepId = stepId, park = DeviceLegRun.Park(untilMillis))
    }

    /** Launch recovery preserves expired parks for current-fact evaluation. */
    fun recover(atMillis: Long): List<DeviceLegRun> = update { state ->
        for ((id, run) in state.runs.toMap()) if (run.park == null && run.completion == null) {
            state.runs[id] = run.copy(completion = DeviceLegRun.Completion("abandoned", atMillis))
        }
        state.runs.values.filter { it.park != null && it.completion == null }.sortedBy(DeviceLegRun::startedEventId)
    }

    /** Consume durably before executing a continuation; only parks resume. */
    fun resumeParked(id: String): DeviceLegRun = update { state ->
        val run = checkNotNull(state.runs[id])
        check(run.startedQueued && run.park != null && run.completion == null)
        run.copy(park = null).also { state.runs[id] = it }
    }

    fun complete(id: String, outcome: String, atMillis: Long, eventOutputs: JsonObject = JsonObject(emptyMap())) = update { state ->
        val run = state.runs[id] ?: error("Unknown device leg run")
        if (run.completion == null) {
            check(outcome.isNotEmpty() && outcome.length <= 256)
            // Boundary event fields and outcome become durable together.
            state.runs[id] = run.copy(completion = DeviceLegRun.Completion(outcome, atMillis),
                outputs = JsonObject(run.outputs + ("event" to eventOutputs)))
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
        state.checklist[current.experienceId] = DeviceLegCheckmark(current.journeyId, newer?.generation ?: current.generation,
            newer?.outcome ?: completion.outcome, newer?.completedAtMillis ?: completion.atMillis, enrollmentAt)
        state.runs.remove(current.id)
        Unit
    }

    private fun <T> read(operation: (Snapshot) -> T): T = lock.withTargetLock(file.path) { operation(load()) }

    private fun <T> update(operation: (Snapshot) -> T): T = lock.withTargetLock(file.path) {
        val state = load()
        val result = operation(state)
        val bytes = buildJsonObject {
            put("schemaVersion", JsonPrimitive(VERSION))
            put("runs", JsonObject(state.runs.mapValues { encodeRun(it.value) }))
            put("checklist", JsonObject(state.checklist.mapValues { encodeCheckmark(it.value) }))
        }.toString().encodeToByteArray()
        if (bytes.size > MAX_BYTES) throw IOException("Device leg journal exceeds byte limit")
        // Reject non-finite JSON before replacing the last readable snapshot.
        SignedReleaseEnvelope.parseObject(bytes)
        if (!root.isDirectory && !root.mkdirs()) throw IOException("Could not create device leg journal directory")
        val temporary = File.createTempFile("journal-", ".tmp", root)
        try {
            FileOutputStream(temporary).use { stream -> stream.write(bytes); stream.fd.sync() }
            if (!temporary.renameTo(file)) throw IOException("Could not publish device leg journal")
        } finally { temporary.delete() }
        result
    }

    private fun load(): Snapshot {
        if (!file.exists()) return Snapshot()
        // InputStream.readNBytes requires a newer Android API than minSdk 23.
        val bytes = file.inputStream().use { input ->
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
            DeviceLegCheckmark(mark.text("journeyId"), mark.number("generation"), mark.text("outcome"), mark.number("completedAtMillis"),
                mark["lastEnrollmentAtMillis"]?.jsonPrimitive?.long)
        }.toMutableMap()
        return Snapshot(runs, checklist)
    }

    private fun encodeRun(run: DeviceLegRun) = buildJsonObject {
        put("journeyId", JsonPrimitive(run.journeyId)); put("generation", JsonPrimitive(run.generation))
        put("reference", run.reference); put("startedAtMillis", JsonPrimitive(run.startedAtMillis))
        put("isEnrollment", JsonPrimitive(run.isEnrollment)); put("startedEventId", JsonPrimitive(run.startedEventId))
        put("completedEventId", JsonPrimitive(run.completedEventId)); put("startedQueued", JsonPrimitive(run.startedQueued))
        put("stepId", JsonPrimitive(run.stepId)); put("context", run.context); put("outputs", run.outputs)
        run.park?.let { park -> put("park", buildJsonObject {
            park.wakeAtMillis?.let { put("wakeAtMillis", JsonPrimitive(it)) }
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
        park = value["park"]?.jsonObject?.let { DeviceLegRun.Park(it["wakeAtMillis"]?.jsonPrimitive?.long) },
        completion = value["completion"]?.jsonObject?.let { DeviceLegRun.Completion(it.text("outcome"), it.number("atMillis")) },
    )

    private fun encodeCheckmark(mark: DeviceLegCheckmark) = buildJsonObject {
        put("journeyId", JsonPrimitive(mark.journeyId)); put("generation", JsonPrimitive(mark.generation))
        put("outcome", JsonPrimitive(mark.outcome)); put("completedAtMillis", JsonPrimitive(mark.completedAtMillis))
        mark.lastEnrollmentAtMillis?.let { put("lastEnrollmentAtMillis", JsonPrimitive(it)) }
    }

    private companion object {
        const val VERSION = "nuxie.device-leg-journal.v1"
        const val MAX_BYTES = 16 * 1024 * 1024
    }
}

private fun emptyOutputs() = JsonObject(mapOf("event" to JsonObject(emptyMap()), "responses" to JsonObject(emptyMap())))
private fun JsonObject.text(key: String) = getValue(key).jsonPrimitive.content
private fun JsonObject.number(key: String) = getValue(key).jsonPrimitive.long
