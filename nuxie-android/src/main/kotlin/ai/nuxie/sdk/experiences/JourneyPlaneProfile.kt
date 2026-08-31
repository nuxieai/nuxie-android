package ai.nuxie.sdk.experiences

import java.net.URI
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull

/** Validated delivery structure, separate from signed program admission. The
 * consumer must authenticate each release before execution or cache promotion. */
internal class JourneyPlaneProfile private constructor(
    val delivery: Delivery,
    val features: JsonArray,
    val facts: JsonObject,
    val armedLegs: List<Arm>,
    val releases: List<Release>,
) {
    data class Arm(val reference: JsonObject, val binding: JsonObject,
        val entryCondition: JsonObject, val context: JsonObject)
    data class Release(val locator: ExperienceReleaseIdentity, val legId: String, val envelope: JsonObject)

    companion object {
        private const val PROFILE_BYTES = 24 * 1024 * 1024
        private val digest = Regex("^[a-f0-9]{64}$")
        private val journeyId = Regex("^[a-fA-F0-9]{8}-[a-fA-F0-9]{4}-7[a-fA-F0-9]{3}-[89abAB][a-fA-F0-9]{3}-[a-fA-F0-9]{12}$")

        fun decode(bytes: ByteArray): JourneyPlaneProfile {
            if (bytes.size > PROFILE_BYTES) fail("profile size")
            val root = exact(SignedReleaseEnvelope.parseObject(bytes), setOf("schemaVersion", "status", "delivery", "features", "facts", "armedLegs", "releases"))
            if (text(root["schemaVersion"]) != "nuxie.journey-plane-profile.v1" || text(root["status"]) != "ok") fail("profile version")
            val delivery = exact(root["delivery"], setOf("renderBaseUrl", "assetBaseUrl"))
            val origins = listOf("renderBaseUrl", "assetBaseUrl").map { key ->
                val value = text(delivery[key])
                val url = try { URI(value) } catch (_: Exception) { fail("delivery URL") }
                if (!url.scheme.equals("https", ignoreCase = true) || url.host.isNullOrEmpty() ||
                    !url.rawUserInfo.isNullOrEmpty() || !url.rawQuery.isNullOrEmpty() || !url.rawFragment.isNullOrEmpty() ||
                    !(url.rawPath.isNullOrEmpty() || url.rawPath.endsWith('/'))
                ) fail("delivery URL")
                value
            }
            val features = array(root["features"])
            for (value in features) validateFeature(value)
            val facts = exact(root["facts"], setOf("properties", "memberships", "assignments"))
            for ((key, value) in record(facts["properties"])) {
                id(JsonPrimitive(key))
                val property = record(value)
                val present = boolean(property["present"])
                exact(property, if (present) setOf("present", "value") else setOf("present"))
            }
            for ((key, value) in record(facts["memberships"])) { id(JsonPrimitive(key)); boolean(value) }
            for ((key, value) in record(facts["assignments"])) {
                id(JsonPrimitive(key))
                if (value == JsonNull) continue
                val assignment = exact(value, setOf("variantId", "isHoldout"))
                id(assignment["variantId"]); boolean(assignment["isHoldout"])
            }
            val releases = array(root["releases"]).also { if (it.size > 1024) fail("release count") }.map { value ->
                val release = exact(value, setOf("locator", "envelope"))
                val locator = exact(release["locator"], setOf("appId", "environment", "experienceId", "experienceVersionId", "versionNumber", "buildId", "publishedAt", "publishedAtSeq", "legId"))
                val legId = hash(locator["legId"])
                for (key in listOf("appId", "experienceId", "experienceVersionId", "buildId")) {
                    val identifier = id(locator[key])
                    if (identifier.length > 128 || '\u0000' in identifier) fail("identity")
                }
                if (text(locator["environment"]) !in setOf("test", "live")) fail("environment")
                integer(locator["versionNumber"], minimum = 1)
                integer(locator["publishedAtSeq"])
                ReleaseJson.timestamp(locator["publishedAt"])
                val identity = ExperienceReleaseIdentity.fromJson(locator) ?: fail("identity")
                val envelope = record(release["envelope"])
                SignedReleaseEnvelope.validateShape(envelope.toString().encodeToByteArray(), SignedReleaseEnvelope.Format.DEVICE_LEG)
                Release(identity, legId, envelope)
            }
            val byDigest = releases.associateBy { hash(it.envelope["descriptorSha256"]) }
            if (byDigest.size != releases.size) fail("duplicate release")
            val seen = mutableSetOf<Pair<JsonObject, JsonObject>>()
            val referenced = mutableSetOf<String>()
            val arms = array(root["armedLegs"]).also { if (it.size > 1024) fail("arm count") }.map { value ->
                val arm = exact(value, setOf("reference", "binding", "entryCondition", "context"))
                val reference = exact(arm["reference"], setOf("experienceId", "versionId", "legId", "descriptorSha256"))
                val experienceId = id(reference["experienceId"])
                val versionId = id(reference["versionId"])
                val legId = hash(reference["legId"])
                val sha = hash(reference["descriptorSha256"])
                val release = byDigest[sha] ?: fail("missing release")
                if (release.legId != legId || release.locator.experienceId != experienceId || release.locator.experienceVersionId != versionId) fail("release binding")
                referenced.add(sha)
                var binding = record(arm["binding"])
                when (text(binding["type"])) {
                    "new" -> exact(binding, setOf("type"))
                    "continue" -> {
                        exact(binding, setOf("type", "journeyId", "generation"))
                        if (!journeyId.matches(text(binding["journeyId"]))) fail("journey id")
                        binding = JsonObject(binding + ("generation" to JsonPrimitive(integer(binding["generation"]))))
                    }
                    else -> fail("binding type")
                }
                if (!seen.add(reference to binding)) fail("duplicate arm")
                val entry = validateEntry(arm["entryCondition"])
                val context = exact(arm["context"], setOf("event", "responses"))
                for (key in listOf("event", "responses")) for (name in record(context[key]).keys) id(JsonPrimitive(name))
                Arm(reference, binding, entry, context)
            }
            if (referenced != byDigest.keys) fail("unreferenced release")
            return JourneyPlaneProfile(Delivery(origins[0], origins[1]), features, facts, arms, releases)
        }

        internal fun validateEntry(value: JsonElement?): JsonObject {
            val entry = record(value)
            val required = when (text(entry["type"])) {
                "app_foregrounded" -> setOf("type")
                "event" -> { id(entry["eventName"]); setOf("type", "eventName") }
                "segment" -> { id(entry["segmentId"]); boolean(entry["member"]); setOf("type", "segmentId", "member") }
                else -> fail("entry type")
            }
            exact(entry, required, setOf("condition"))
            entry["condition"]?.let {
                val condition = exact(it, setOf("ir_version", "expr"), setOf("engine_min", "compiled_at"))
                if (integer(condition["ir_version"]) != 1L) fail("IR version")
                DeviceEntryIrSchema.validate(condition["expr"])
                condition["engine_min"]?.let { minimum ->
                    if ((text(minimum).substringBefore('.').toIntOrNull() ?: 1) > 1) fail("IR engine")
                }
                condition["compiled_at"]?.let(::number)
            }
            return entry
        }

        private fun validateFeature(value: JsonElement) {
            val feature = record(value)
            text(feature["id"])
            if (text(feature["type"]) !in setOf("boolean", "metered", "creditSystem")) fail("feature type")
            for (key in listOf("balance", "nextResetAt")) if (feature[key] != JsonNull) number(feature[key])
            boolean(feature["unlimited"])
            if (feature["interval"] != JsonNull && text(feature["interval"]) !in setOf(
                    "lifetime", "minute", "hour", "day", "week", "month", "quarter", "semiAnnual", "year",
                )) fail("feature interval")
            feature["entities"]?.let { entities ->
                for (entity in record(entities).values) number(record(entity)["balance"])
            }
        }

        private fun record(value: JsonElement?): JsonObject = value as? JsonObject ?: fail("object")
        private fun array(value: JsonElement?): JsonArray = value as? JsonArray ?: fail("array")
        private fun text(value: JsonElement?): String = (value as? JsonPrimitive)?.takeIf { it.isString }?.content ?: fail("string")
        private fun id(value: JsonElement?): String = text(value).also { if (it.isEmpty() || it.length > 256) fail("identifier") }
        private fun hash(value: JsonElement?): String = text(value).also { if (!digest.matches(it)) fail("digest") }
        private fun boolean(value: JsonElement?): Boolean = (value as? JsonPrimitive)?.takeUnless { it.isString }?.booleanOrNull ?: fail("boolean")
        private fun number(value: JsonElement?): Double = (value as? JsonPrimitive)?.takeUnless { it.isString }?.doubleOrNull?.takeIf { it.isFinite() } ?: fail("number")
        private fun integer(value: JsonElement?, minimum: Long = 0): Long = number(value).let {
            if (it != kotlin.math.floor(it) || it < minimum || it > 9_007_199_254_740_991.0) fail("integer")
            it.toLong()
        }
        private fun exact(value: JsonElement?, required: Set<String>, optional: Set<String> = emptySet()): JsonObject = record(value).also {
            if (!it.keys.containsAll(required) || (it.keys - required - optional).isNotEmpty()) fail("unknown or missing fields")
        }
        private fun fail(message: String): Nothing = throw ReleaseAuthenticationException(message)
    }
}
