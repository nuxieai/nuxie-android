package ai.nuxie.sdk.journey

import ai.nuxie.sdk.events.CanonicalJson
import ai.nuxie.sdk.events.StoredEvent
import ai.nuxie.sdk.events.SystemEventNames
import ai.nuxie.sdk.experiences.ExperiencePolicySchema
import ai.nuxie.sdk.experiences.JourneyPlaneProfile
import java.security.MessageDigest
import kotlinx.serialization.json.*

/** Retained measurement owns no scene, release pin, or execution cursor. */
internal data class JourneyConversionWatch(
    val journeyId: String,
    val experienceId: String,
    val versionId: String,
    val policyHash: String,
    val goal: JsonObject,
    val startedAt: Long,
    val basis: JourneyPlaneProfile.ConversionOccurrence? = null,
    val conversion: JourneyPlaneProfile.ConversionOccurrence? = null,
    val serverRevision: Long? = null,
    val legCompletedAt: Long? = null,
) {
    private val attribution get() = goal.getValue("attribution").jsonObject
    val windowMillis get() = ExperiencePolicySchema.durationMillis(attribution["window"])
    private val entryBasis get() = attribution.getValue("basis").jsonPrimitive.content == "entry"

    init {
        require(startedAt in 0..MAX_SAFE_MILLIS)
        require(windowMillis in 1..MAX_WINDOW_MILLIS)
    }

    fun reconcile(delivery: JourneyPlaneProfile.Conversion): JourneyConversionWatch {
        require(delivery.startedAt == startedAt)
        if (serverRevision != null && delivery.revision <= serverRevision) return this
        val incoming = delivery.basis
        require(incoming == null || incoming.occurredAt >= startedAt)
        if (entryBasis) require(incoming?.occurredAt == startedAt && incoming.eventId == journeyId)
        delivery.conversion?.let {
            require(incoming != null && it.occurredAt in incoming.occurredAt..(incoming.occurredAt + windowMillis))
        }
        return copy(basis = incoming, conversion = delivery.conversion, serverRevision = delivery.revision)
    }

    suspend fun matches(event: StoredEvent): Boolean {
        // Purchase telemetry routes locally; commercial measurement is verified
        // by the server and arrives through its conversion projection.
        if (event.name == "\$purchase_completed" || event.name == "\$purchase_synced") return false
        val criterion = goal.getValue("criterion").jsonObject
        when (criterion.getValue("type").jsonPrimitive.content) {
            "segment_enter" -> return event.name == "\$segment_entered" && event.properties["segment_id"] == criterion["segmentId"]
            "segment_leave" -> return event.name == "\$segment_exited" && event.properties["segment_id"] == criterion["segmentId"]
            "event" -> if (JsonPrimitive(event.name) != criterion["eventName"]) return false
            else -> return false
        }
        if (!criterion.containsKey("condition")) return true
        return JourneyEntryEvaluator.matches(
            entry = criterion,
            facts = buildJsonObject { put("properties", JsonObject(emptyMap())); put("memberships", JsonObject(emptyMap())) },
            references = buildJsonObject { put("propertyKeys", JsonArray(emptyList())); put("segmentIds", JsonArray(emptyList())) },
            foreground = false,
            event = buildJsonObject { put("name", event.name); put("properties", event.properties); put("distinct_id", event.distinctId) },
            nowMillis = event.timestampMillis,
            journeyId = journeyId,
        )
    }

    fun shouldRetain(now: Long, executing: Boolean): Boolean {
        val expiry = basis?.let { it.occurredAt + windowMillis } ?: legCompletedAt ?: (startedAt + MAX_WINDOW_MILLIS)
        return executing || now <= expiry + BACKDATE_MILLIS
    }

    fun toJson(): JsonObject = buildJsonObject {
        put("journeyId", journeyId); put("experienceId", experienceId); put("versionId", versionId)
        put("policyHash", policyHash); put("goal", goal); put("startedAt", startedAt)
        basis?.let { put("basis", occurrenceJson(it)) }
        conversion?.let { put("conversion", occurrenceJson(it)) }
        serverRevision?.let { put("serverRevision", it) }
        legCompletedAt?.let { put("legCompletedAt", it) }
    }

    companion object {
        const val BACKDATE_MILLIS = 30L * 86_400_000
        const val MAX_WINDOW_MILLIS = 90L * 86_400_000
        const val MAX_SAFE_MILLIS = 9_007_199_254_740_991L

        fun create(run: JourneyRun, policy: JsonObject, delivery: JourneyPlaneProfile.Conversion?): JourneyConversionWatch {
            ExperiencePolicySchema.validate(policy)
            require(run.isEnrollment || delivery != null)
            val goal = policy.getValue("goal").jsonObject
            val start = delivery?.startedAt ?: run.startedAtMillis
            val watch = JourneyConversionWatch(
                journeyId = run.journeyId,
                experienceId = run.reference.getValue("experienceId").jsonPrimitive.content,
                versionId = run.reference.getValue("versionId").jsonPrimitive.content,
                policyHash = MessageDigest.getInstance("SHA-256").digest(CanonicalJson.encodeToByteArray(policy))
                    .joinToString("") { "%02x".format(it) },
                goal = goal, startedAt = start,
                basis = if (goal.getValue("attribution").jsonObject["basis"] == JsonPrimitive("entry"))
                    JourneyPlaneProfile.ConversionOccurrence(run.journeyId, start) else null,
            )
            return delivery?.let(watch::reconcile) ?: watch
        }

        fun fromJson(value: JsonObject): JourneyConversionWatch = JourneyConversionWatch(
            journeyId = value.getValue("journeyId").jsonPrimitive.content,
            experienceId = value.getValue("experienceId").jsonPrimitive.content,
            versionId = value.getValue("versionId").jsonPrimitive.content,
            policyHash = value.getValue("policyHash").jsonPrimitive.content,
            goal = value.getValue("goal").jsonObject,
            startedAt = value.getValue("startedAt").jsonPrimitive.long,
            basis = value["basis"]?.jsonObject?.let(::occurrence),
            conversion = value["conversion"]?.jsonObject?.let(::occurrence),
            serverRevision = value["serverRevision"]?.jsonPrimitive?.long,
            legCompletedAt = value["legCompletedAt"]?.jsonPrimitive?.long,
        )

        fun normalized(event: StoredEvent, acceptedAt: Long): StoredEvent? {
            if (event.timestampMillis !in 0..MAX_SAFE_MILLIS || acceptedAt !in 0..MAX_SAFE_MILLIS ||
                event.timestampMillis < acceptedAt - BACKDATE_MILLIS) return null
            return StoredEvent(id = event.id, name = event.name, properties = event.properties,
                distinctId = event.distinctId, timestampMillis = minOf(event.timestampMillis, acceptedAt), journeyOrigin = event.journeyOrigin)
        }

        fun apply(event: StoredEvent, acceptedAt: Long, matching: Set<String>, watches: MutableMap<String, JourneyConversionWatch>) {
            if (event.name == "\$purchase_completed" || event.name == "\$purchase_synced") return
            val occurrence = normalized(event, acceptedAt) ?: return
            val time = occurrence.timestampMillis
            val reportedJourney = (event.properties["journey_id"] as? JsonPrimitive)?.takeIf { it.isString }?.content
            val hasContext = listOf("journey_id", "experience_id", "experience_version_id").any(event.properties::containsKey)
            if (hasContext) {
                val watch = watches[reportedJourney] ?: return
                if (event.properties["experience_id"] != JsonPrimitive(watch.experienceId) ||
                    event.properties["experience_version_id"] != JsonPrimitive(watch.versionId)) return
            }
            if (event.name == SystemEventNames.EXPERIENCE_SHOWN && reportedJourney != null) {
                val watch = watches.getValue(reportedJourney)
                if (!watch.entryBasis && time >= watch.startedAt && watch.basis == null) {
                    watches[reportedJourney] = watch.copy(basis = JourneyPlaneProfile.ConversionOccurrence(event.id, time))
                }
            }
            val direct = event.journeyOrigin?.journeyId
            if (event.journeyOrigin != null) {
                val origin = event.journeyOrigin
                val watch = watches[origin.journeyId] ?: return
                if (origin.occurrenceId != event.id || origin.experienceId != watch.experienceId ||
                    origin.versionId != watch.versionId || (hasContext && reportedJourney != origin.journeyId)) return
            } else if (hasContext) return
            val selected = watches.values.filter { watch ->
                val basis = watch.basis
                matching.contains(watch.journeyId) && basis != null &&
                    time in basis.occurredAt..(basis.occurredAt + watch.windowMillis) &&
                    (time != basis.occurredAt || event.id > basis.eventId) &&
                    (direct == null || watch.journeyId == direct)
            }.sortedWith(compareByDescending<JourneyConversionWatch> { it.basis!!.occurredAt }.thenBy { it.journeyId }).firstOrNull() ?: return
            if (selected.conversion != null) return
            watches[selected.journeyId] = selected.copy(conversion = JourneyPlaneProfile.ConversionOccurrence(event.id, time))
        }

        private fun occurrence(value: JsonObject) = JourneyPlaneProfile.ConversionOccurrence(
            value.getValue("eventId").jsonPrimitive.content, value.getValue("occurredAt").jsonPrimitive.long)
        private fun occurrenceJson(value: JourneyPlaneProfile.ConversionOccurrence) = buildJsonObject {
            put("eventId", value.eventId); put("occurredAt", value.occurredAt)
        }
    }
}
