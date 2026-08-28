package ai.nuxie.sdk.journey

import java.io.File
import java.security.MessageDigest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject

/**
 * Per-user file persistence for Journey runs and bounded completion receipts
 * that inform future admissions. Every write is an atomic replacement.
 */
internal class JourneyStore(
    filesDir: File,
    private val json: Json = Json { encodeDefaults = true; ignoreUnknownKeys = false },
) {
    private val root = File(filesDir, "nuxie/journeys")

    init {
        root.mkdirs()
    }

    @Synchronized
    fun save(run: JourneyRun) {
        val directory = runsDirectory(run.distinctId)
        directory.mkdirs()
        atomicWrite(File(directory, "${run.id}.json"), encodeRun(run).toString())
    }

    @Synchronized
    fun load(distinctId: String, journeyId: String): JourneyRun? {
        val directory = runsDirectory(distinctId)
        val interrupted = decodeRun(File(directory, ".$journeyId.json.new"))
            ?.takeIf { it.hasPendingHostDismissal() }
        return interrupted ?: decodeRun(File(directory, "$journeyId.json"))
    }

    @Synchronized
    fun loadActive(distinctId: String): List<JourneyRun> = runsDirectory(distinctId)
        .listFiles()
        ?.asSequence()
        ?.filter { it.extension == "json" }
        ?.mapNotNull(::decodeRun)
        ?.filter { it.state == JourneyRunState.ACTIVE }
        ?.sortedBy { it.id }
        ?.toList()
        ?: emptyList()

    @Synchronized
    fun loadPendingHostDismissals(distinctId: String): List<JourneyRun> = runsDirectory(distinctId)
        .listFiles()
        ?.asSequence()
        ?.filter { it.isRunSnapshot() }
        ?.mapNotNull(::decodeRun)
        ?.filter { it.hasPendingHostDismissal() }
        ?.distinctBy(JourneyRun::id)
        ?.sortedBy { it.id }
        ?.toList()
        ?: emptyList()

    @Synchronized
    fun loadPendingHostDismissals(): List<JourneyRun> = File(root, "runs")
        .listFiles()
        ?.asSequence()
        ?.filter(File::isDirectory)
        ?.flatMap { directory -> directory.listFiles()?.asSequence() ?: emptySequence() }
        ?.filter { it.isRunSnapshot() }
        ?.mapNotNull(::decodeRun)
        ?.filter { it.hasPendingHostDismissal() }
        ?.distinctBy { it.distinctId to it.id }
        ?.sortedWith(compareBy(JourneyRun::distinctId, JourneyRun::id))
        ?.toList()
        ?: emptyList()

    @Synchronized
    fun delete(run: JourneyRun) {
        val directory = runsDirectory(run.distinctId)
        File(directory, "${run.id}.json").delete()
        File(directory, ".${run.id}.json.new").delete()
    }

    @Synchronized
    fun recordCompletion(distinctId: String, completion: JourneyCompletion) {
        val completions = completions(distinctId, completion.experienceId)
            .filterNot { it.journeyId == completion.journeyId }
            .plus(completion)
            .takeLast(MAX_COMPLETIONS_PER_EXPERIENCE)
        val directory = completionsDirectory(distinctId)
        directory.mkdirs()
        atomicWrite(
            File(directory, "${safeFileName(completion.experienceId)}.json"),
            JsonArray(completions.map(::encodeCompletion)).toString(),
        )
    }

    @Synchronized
    fun hasCompleted(distinctId: String, experienceId: String): Boolean =
        completions(distinctId, experienceId).isNotEmpty()

    @Synchronized
    fun lastCompletionAtMillis(distinctId: String, experienceId: String): Long? =
        completions(distinctId, experienceId).maxOfOrNull { it.completedAtMillis }

    @Synchronized
    internal fun completionCount(distinctId: String, experienceId: String): Int =
        completions(distinctId, experienceId).size

    private fun decodeRun(file: File): JourneyRun? = runCatching {
        val value = json.parseToJsonElement(file.readText()).jsonObject
        JourneyRun(
            id = value.string("id") ?: return null,
            distinctId = value.string("distinct_id") ?: return null,
            experienceId = value.string("experience_id") ?: return null,
            experienceVersion = value.string("experience_version") ?: return null,
            epoch = value.long("epoch") ?: return null,
            plane = value.string("plane")?.let(JourneyPlane::valueOf) ?: return null,
            settingsSnapshot = value["settings_snapshot"] as? JsonObject ?: return null,
            state = value.string("state")?.let(JourneyRunState::valueOf) ?: return null,
            resumePoint = (value["resume_point"] as? JsonObject)?.let { point ->
                JourneyResumePoint(
                    nodeId = point.string("node_id") ?: return null,
                    checkpointAtMillis = point.long("checkpoint_at") ?: return null,
                )
            },
            isGhost = value["is_ghost"]?.let { (it as? JsonPrimitive)?.content == "true" } ?: false,
            convertedAtMillis = value.long("converted_at"),
            terminalReason = value.string("terminal_reason"),
            triggerRef = value.string("trigger_ref"),
            completedAtMillis = value.long("completed_at"),
            pendingHostExitCapture = value.boolean("pending_host_exit_capture"),
            pendingHostCompletion = value.boolean("pending_host_completion"),
            pendingHostTriggerCompletion = value.boolean("pending_host_trigger_completion"),
        )
    }.getOrNull()

    private fun completions(distinctId: String, experienceId: String): List<JourneyCompletion> {
        val file = File(completionsDirectory(distinctId), "${safeFileName(experienceId)}.json")
        return runCatching {
            (json.parseToJsonElement(file.readText()) as JsonArray).mapNotNull { element ->
                val value = element as? JsonObject ?: return@mapNotNull null
                val id = value.string("experience_id") ?: return@mapNotNull null
                val completedAt = value.long("completed_at") ?: return@mapNotNull null
                val journeyId = value.string("journey_id")
                    ?: "legacy:$id:$completedAt"
                JourneyCompletion(id, journeyId, completedAt)
            }
        }.getOrDefault(emptyList())
    }

    private fun runsDirectory(distinctId: String): File = File(File(root, "runs"), safeFileName(distinctId))
    private fun completionsDirectory(distinctId: String): File = File(File(root, "completions"), safeFileName(distinctId))

    /** Collision-free filesystem scope for arbitrary distinct and Experience ids. */
    private fun safeFileName(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.encodeToByteArray())
        .joinToString("") { "%02x".format(it) }

    private fun atomicWrite(destination: File, content: String) {
        destination.parentFile?.mkdirs()
        // Same-directory rename maps to the platform's atomic replacement
        // primitive on Android's filesystems and is available from minSdk 23.
        val temporary = File(destination.parentFile, ".${destination.name}.new")
        temporary.outputStream().use { stream -> stream.write(content.encodeToByteArray()) }
        try {
            check(temporary.renameTo(destination)) { "Could not replace ${destination.name}" }
        } catch (failure: Throwable) {
            // A fully written terminal replacement is itself recoverable
            // tombstone evidence. Keep it for the startup/foreground scan.
            throw failure
        }
    }

    private fun File.isRunSnapshot(): Boolean = extension == "json" || name.endsWith(".json.new")

    private fun JourneyRun.hasPendingHostDismissal(): Boolean =
        pendingHostExitCapture || pendingHostCompletion || pendingHostTriggerCompletion

    private fun encodeRun(run: JourneyRun): JsonObject = buildJsonObject {
        put("id", JsonPrimitive(run.id))
        put("distinct_id", JsonPrimitive(run.distinctId))
        put("experience_id", JsonPrimitive(run.experienceId))
        put("experience_version", JsonPrimitive(run.experienceVersion))
        put("epoch", JsonPrimitive(run.epoch))
        put("plane", JsonPrimitive(run.plane.name))
        put("settings_snapshot", run.settingsSnapshot)
        put("state", JsonPrimitive(run.state.name))
        put("resume_point", run.resumePoint?.let { point ->
            buildJsonObject {
                put("node_id", JsonPrimitive(point.nodeId))
                put("checkpoint_at", JsonPrimitive(point.checkpointAtMillis))
            }
        } ?: JsonNull)
        put("is_ghost", JsonPrimitive(run.isGhost))
        put("converted_at", run.convertedAtMillis?.let(::JsonPrimitive) ?: JsonNull)
        put("terminal_reason", run.terminalReason?.let(::JsonPrimitive) ?: JsonNull)
        put("trigger_ref", run.triggerRef?.let(::JsonPrimitive) ?: JsonNull)
        put("completed_at", run.completedAtMillis?.let(::JsonPrimitive) ?: JsonNull)
        put("pending_host_exit_capture", JsonPrimitive(run.pendingHostExitCapture))
        put("pending_host_completion", JsonPrimitive(run.pendingHostCompletion))
        put("pending_host_trigger_completion", JsonPrimitive(run.pendingHostTriggerCompletion))
    }

    private fun encodeCompletion(completion: JourneyCompletion): JsonObject = buildJsonObject {
        put("experience_id", JsonPrimitive(completion.experienceId))
        put("journey_id", JsonPrimitive(completion.journeyId))
        put("completed_at", JsonPrimitive(completion.completedAtMillis))
    }

    private fun JsonObject.string(key: String): String? =
        (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull

    private fun JsonObject.long(key: String): Long? =
        (this[key] as? JsonPrimitive)?.takeIf { !it.isString }?.content?.toLongOrNull()

    private fun JsonObject.boolean(key: String): Boolean =
        (this[key] as? JsonPrimitive)?.content?.toBooleanStrictOrNull() ?: false

    private companion object {
        const val MAX_COMPLETIONS_PER_EXPERIENCE = 10
    }
}
