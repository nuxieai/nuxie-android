package ai.nuxie.sdk.journey

import ai.nuxie.sdk.experiences.JourneyReleaseEnvelope
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.boolean

internal data class JourneyResponseSave(
    val distinctId: String,
    val journeyId: String,
    val experienceId: String,
    val experienceVersionId: String,
    val formName: String,
    val sequence: Long,
    val answers: JsonObject,
) {
    fun toJson(): JsonObject = buildJsonObject {
        put("distinct_id", JsonPrimitive(distinctId))
        put("journey_id", JsonPrimitive(journeyId))
        put("experience_id", JsonPrimitive(experienceId))
        put("experience_version_id", JsonPrimitive(experienceVersionId))
        put("form_name", JsonPrimitive(formName))
        put("sequence", JsonPrimitive(sequence))
        put("answers", answers)
    }

    companion object {
        const val MAXIMUM_SEQUENCE = 9_007_199_254_740_991L
        fun fromJson(value: JsonObject) = JourneyResponseSave(
            value.getValue("distinct_id").jsonPrimitive.content,
            value.getValue("journey_id").jsonPrimitive.content,
            value.getValue("experience_id").jsonPrimitive.content,
            value.getValue("experience_version_id").jsonPrimitive.content,
            value.getValue("form_name").jsonPrimitive.content,
            value.getValue("sequence").jsonPrimitive.long,
            value.getValue("answers").jsonObject,
        )
    }
}

internal data class JourneyResponseSaveLane(
    var sequence: Long = 0,
    var pending: JourneyResponseSave? = null,
    var retry: JourneyResponseSaveRetry? = null,
    var display: JourneyResponseSaveDisplay? = null,
) {
    fun toJson() = buildJsonObject {
        put("sequence", JsonPrimitive(sequence))
        pending?.let { put("pending", it.toJson()) }
        retry?.let { put("retry", it.toJson()) }
        display?.let { put("display", it.toJson()) }
    }
    companion object {
        fun fromJson(value: JsonObject) = JourneyResponseSaveLane(
            value.getValue("sequence").jsonPrimitive.long,
            value["pending"]?.jsonObject?.let(JourneyResponseSave::fromJson),
            value["retry"]?.jsonObject?.let(JourneyResponseSaveRetry::fromJson),
            value["display"]?.jsonObject?.let(JourneyResponseSaveDisplay::fromJson),
        )
    }
}

/** Durable display metadata; answers remain in the run's native values. */
internal data class JourneyResponseSaveDisplay(
    val sequence: Long,
    val saving: Boolean,
    val saved: Boolean,
    val saveError: String,
) {
    fun finish(reply: JourneyResponseSaveReply) = copy(saving = false, saved = reply.confirmed,
        saveError = if (reply.confirmed) "" else reply.code.wire)
    fun toJson() = buildJsonObject {
        put("sequence", JsonPrimitive(sequence))
        put("saving", JsonPrimitive(saving))
        put("saved", JsonPrimitive(saved))
        put("saveError", JsonPrimitive(saveError))
    }
    companion object {
        fun saving(sequence: Long) = JourneyResponseSaveDisplay(sequence, true, false, "")
        fun fromJson(value: JsonObject) = JourneyResponseSaveDisplay(
            value.getValue("sequence").jsonPrimitive.long,
            value.getValue("saving").jsonPrimitive.boolean,
            value.getValue("saved").jsonPrimitive.boolean,
            value.getValue("saveError").jsonPrimitive.content,
        )
    }
}

internal data class JourneyResponseSaveState(
    val namespace: String,
    val distinctId: String,
    val journeys: MutableMap<String, MutableMap<String, JourneyResponseSaveLane>> = linkedMapOf(),
) {
    fun toJson() = buildJsonObject {
        put("namespace", JsonPrimitive(namespace))
        put("distinctId", JsonPrimitive(distinctId))
        put("journeys", JsonObject(journeys.mapValues { (_, forms) -> JsonObject(forms.mapValues { it.value.toJson() }) }))
    }
    companion object {
        fun fromJson(value: JsonObject) = JourneyResponseSaveState(
            value.getValue("namespace").jsonPrimitive.content,
            value.getValue("distinctId").jsonPrimitive.content,
            value.getValue("journeys").jsonObject.mapValues { (_, forms) ->
                forms.jsonObject.mapValues { JourneyResponseSaveLane.fromJson(it.value.jsonObject) }.toMutableMap()
            }.toMutableMap(),
        )
    }
}

internal data class JourneyResponseSaveRetry(
    var attempts: Int,
    var observedAt: Long,
    var nextAttemptAt: Long,
    var unknownFormSince: Long?,
    var lastCode: JourneyResponseSaveReply.Code,
) {
    fun normalizeClock(now: Long) {
        if (now >= observedAt) return
        val shift = now - observedAt
        nextAttemptAt += shift
        unknownFormSince = unknownFormSince?.plus(shift)
        observedAt = now
    }
    fun toJson() = buildJsonObject {
        put("attempts", JsonPrimitive(attempts))
        put("observedAt", JsonPrimitive(observedAt))
        put("nextAttemptAt", JsonPrimitive(nextAttemptAt))
        unknownFormSince?.let { put("unknownFormSince", JsonPrimitive(it)) }
        put("lastCode", JsonPrimitive(lastCode.wire))
    }
    companion object {
        fun fromJson(value: JsonObject) = JourneyResponseSaveRetry(
            value.getValue("attempts").jsonPrimitive.long.toInt(),
            value.getValue("observedAt").jsonPrimitive.long,
            value.getValue("nextAttemptAt").jsonPrimitive.long,
            value["unknownFormSince"]?.jsonPrimitive?.long,
            JourneyResponseSaveReply.Code.entries.first { it.wire == value.getValue("lastCode").jsonPrimitive.content },
        )
    }
}

internal data class JourneyResponseSaveAttempt(val sheet: JourneyResponseSave, val retry: JourneyResponseSaveRetry?) {
    fun delay(now: Long): Long = maxOf(0, (retry?.nextAttemptAt ?: now) - now)
    fun unknownFormExpired(now: Long): Boolean = retry?.lastCode == JourneyResponseSaveReply.Code.UNKNOWN_FORM &&
        retry.unknownFormSince?.let { now - it >= 600_000 } == true
}

internal data class JourneyResponseSaveReply(val code: Code, val sequence: Long? = null) {
    enum class Code(val wire: String) {
        SAVED("saved"), REPLAYED("replayed"), STALE("stale"),
        MERGE_IN_PROGRESS("merge_in_progress"), CUSTOMER_UNAVAILABLE("customer_unavailable"),
        CUSTOMER_REDIRECT_LOOP("customer_redirect_loop"), SAVE_UNAVAILABLE("save_unavailable"), UNKNOWN_FORM("unknown_form"),
        INVALID_REQUEST("invalid_request"), AUTHENTICATION_FAILED("authentication_failed"),
        UNKNOWN_EXPERIENCE_VERSION("unknown_experience_version"), PINNED_VERSION_MISMATCH("pinned_version_mismatch"),
        SEQUENCE_CONFLICT("sequence_conflict"), CUSTOMER_DELETED("customer_deleted"), NO_ANSWER("no_answer"),
    }
    val confirmed get() = code in setOf(Code.SAVED, Code.REPLAYED, Code.STALE)
    val terminal get() = code in setOf(Code.INVALID_REQUEST, Code.AUTHENTICATION_FAILED, Code.UNKNOWN_EXPERIENCE_VERSION,
        Code.PINNED_VERSION_MISMATCH, Code.SEQUENCE_CONFLICT, Code.CUSTOMER_DELETED)
    companion object {
        val noAnswer = JourneyResponseSaveReply(Code.NO_ANSWER)
        fun decode(bytes: ByteArray, attemptedSequence: Long): JourneyResponseSaveReply {
            return try {
                val value = JourneyReleaseEnvelope.parseObject(bytes)
                val status = value.getValue("status").jsonPrimitive
                if (!status.isString) return noAnswer
                if (status.content == "error") {
                    val rawCode = value["code"]?.jsonPrimitive ?: return noAnswer
                    if (!rawCode.isString) return noAnswer
                    val code = Code.entries.firstOrNull { it.wire == rawCode.content } ?: return noAnswer
                    if (code in setOf(Code.SAVED, Code.REPLAYED, Code.STALE, Code.NO_ANSWER)) return noAnswer
                    return JourneyResponseSaveReply(code)
                }
                val code = Code.entries.firstOrNull { it.wire == status.content } ?: return noAnswer
                if (code !in setOf(Code.SAVED, Code.REPLAYED, Code.STALE)) return noAnswer
                val sequenceValue = value["sequence"]?.jsonPrimitive
                val sequence = sequenceValue?.takeUnless { it.isString || it == JsonNull }?.doubleOrNull
                    ?.takeIf { it.isFinite() && it == kotlin.math.floor(it) &&
                        it in 1.0..JourneyResponseSave.MAXIMUM_SEQUENCE.toDouble() }
                    ?.toLong()
                if (sequence == null || sequence < attemptedSequence || sequence <= 0 ||
                    sequence > JourneyResponseSave.MAXIMUM_SEQUENCE) noAnswer else JourneyResponseSaveReply(code, sequence)
            } catch (_: Exception) { noAnswer }
        }
    }
}

internal fun interface JourneyResponseSaveTransport {
    suspend fun sendResponseSave(sheet: JourneyResponseSave): JourneyResponseSaveReply
}

internal data class JourneyResponseSaveRecovery(val owners: List<String>, val needsRetry: Boolean)
