package ai.nuxie.sdk.experiences

import ai.nuxie.sdk.experiences.ReleaseJson.array
import ai.nuxie.sdk.experiences.ReleaseJson.boolean
import ai.nuxie.sdk.experiences.ReleaseJson.exact
import ai.nuxie.sdk.experiences.ReleaseJson.fail
import ai.nuxie.sdk.experiences.ReleaseJson.hash
import ai.nuxie.sdk.experiences.ReleaseJson.id
import ai.nuxie.sdk.experiences.ReleaseJson.ids
import ai.nuxie.sdk.experiences.ReleaseJson.integer
import ai.nuxie.sdk.experiences.ReleaseJson.number
import ai.nuxie.sdk.experiences.ReleaseJson.oneOf
import ai.nuxie.sdk.experiences.ReleaseJson.record
import ai.nuxie.sdk.experiences.ReleaseJson.sortedUnique
import ai.nuxie.sdk.experiences.ReleaseJson.text
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Non-topological release fields retain the publisher's existing contracts. */
internal object DeviceLegReleaseSchema {
    private val intervals = arrayOf("lifetime", "minute", "hour", "day", "week", "month", "quarter", "semiAnnual", "year")
    private val productTypes = arrayOf("subscription", "consumable", "nonConsumable")

    fun validate(root: JsonObject) {
        val identity = exact(root["identity"], setOf("appId", "environment", "experienceId", "experienceVersionId",
            "versionNumber", "buildId", "publishedAt", "publishedAtSeq"))
        for (key in listOf("appId", "experienceId", "experienceVersionId", "buildId")) releaseId(identity[key])
        oneOf(identity["environment"], "test", "live")
        integer(identity["versionNumber"], 1); integer(identity["publishedAtSeq"])
        ReleaseJson.timestamp(identity["publishedAt"])
        val metadata = exact(root["metadata"], setOf("name", "appDefaultTimezone"), setOf("experienceType", "description"))
        id(metadata["name"]); releaseId(metadata["appDefaultTimezone"])
        metadata["experienceType"]?.let { id(it, 64) }
        metadata["description"]?.let { if (text(it).length > 2048) fail("description") }
        val provenance = exact(root["provenance"], setOf("compilerCommit", "compilerVersion"))
        id(provenance["compilerCommit"], 128); id(provenance["compilerVersion"], 64)
        presentation(root["presentation"])
        for (product in array(root["products"], 256)) product(product)
        for (value in array(root["placements"], 256)) {
            val placement = exact(value, setOf("id", "productId"), setOf("appStore"))
            releaseId(placement["id"]); releaseId(placement["productId"])
            placement["appStore"]?.let {
                val store = exact(it, setOf("introEligibility", "billingPlan"))
                oneOf(store["introEligibility"], "automatic", "alwaysEligible", "alwaysIneligible")
                oneOf(store["billingPlan"], "default", "upFront", "monthly")
            }
        }
        val scripts = mutableMapOf<String, Long>()
        for (value in array(root["screenBehaviors"])) behavior(value)?.let { (sha, bytes) ->
            if (scripts.put(sha, bytes)?.let { it != bytes } == true) fail("conflicting script size")
        }
        if (scripts.values.sum() > ExperienceReleaseLimits.SCRIPT_ARTIFACT_AGGREGATE_BYTES) fail("script byte budget")
        if (root["render"] != JsonNull) {
            DeviceLegRenderSchema.validate(record(root["render"]))
            requirements(root["requirements"])
        }
    }

    private fun presentation(input: JsonElement?) {
        val value = record(input)
        val common = setOf("style", "orientation", "backgroundColor")
        oneOf(value["orientation"], "portrait", "landscape", "any")
        if (!text(value["backgroundColor"]).matches(Regex("^#[a-fA-F0-9]{8}$"))) fail("background color")
        when (text(value["style"])) {
            "full_screen" -> exact(value, common)
            "sheet" -> {
                exact(value, common + "sheet")
                val sheet = exact(value["sheet"], setOf("detent", "dismissible"))
                oneOf(sheet["detent"], "medium", "large"); boolean(sheet["dismissible"])
            }
            "drawer" -> {
                exact(value, common + "drawer")
                val drawer = exact(value["drawer"], setOf("edge", "extentRatio", "cornerRadius", "dismissible"))
                oneOf(drawer["edge"], "bottom", "top", "leading", "trailing")
                if (number(drawer["extentRatio"], 0.0, 1.0) == 0.0) fail("drawer extent")
                number(drawer["cornerRadius"], 0.0, 128.0); boolean(drawer["dismissible"])
            }
            else -> fail("presentation style")
        }
    }

    private fun product(input: JsonElement) {
        val product = exact(input, setOf("id", "type", "providerFeatureAccess", "store", "preview", "entitlements"))
        releaseId(product["id"])
        val type = oneOf(product["type"], *productTypes)
        if (product["providerFeatureAccess"] != JsonNull) {
            oneOf(exact(product["providerFeatureAccess"], setOf("provider"))["provider"], "revenuecat", "superwall")
        }
        val store = exact(product["store"], setOf("platform", "productId", "productType"))
        utf8(store["productId"], 256)
        val platform = oneOf(store["platform"], "apple_app_store", "google_play")
        val storeType = text(store["productType"])
        if (platform == "apple_app_store" && type == "subscription") {
            oneOf(store["productType"], "autoRenewable", "nonRenewing")
        } else if (storeType != type) fail("store product type")
        val previewKeys = mapOf("name" to 512, "description" to 2048, "price" to 128, "period" to 64,
            "periodLabel" to 128, "trialLabel" to 256, "introOfferLabel" to 256, "renewalLabel" to 256)
        val preview = exact(product["preview"], previewKeys.keys + setOf("periodCount", "hasTrial"))
        for ((key, max) in previewKeys) utf8(preview[key], max)
        integer(preview["periodCount"], 0, 10_000); boolean(preview["hasTrial"])
        val entitlements = array(product["entitlements"], 256).map { input ->
            val entitlement = exact(input, setOf("id", "featureId", "featureExternalId", "purchaseUsageFeatureIds",
                "allowanceType", "allowance", "interval"))
            if (entitlement["featureId"] != JsonNull) releaseId(entitlement["featureId"])
            if (entitlement["featureExternalId"] != JsonNull) utf8(entitlement["featureExternalId"], 256)
            val usage = array(entitlement["purchaseUsageFeatureIds"], 256).map { utf8(it, 256) }
            sortedUnique(usage)
            if (entitlement["allowanceType"] != JsonNull) oneOf(entitlement["allowanceType"], "fixed", "unlimited")
            if (entitlement["allowance"] != JsonNull) number(entitlement["allowance"], 0.0)
            if (entitlement["interval"] != JsonNull) oneOf(entitlement["interval"], *intervals)
            releaseId(entitlement["id"])
        }
        sortedUnique(entitlements)
    }

    private fun requirements(input: JsonElement?) {
        val value = exact(input, setOf("minimumSdkVersion", "runtimeRevision", "luau", "sceneFormat", "timezoneData", "requiredCapabilities"))
        if (SemanticVersion.parse(text(value["minimumSdkVersion"])) == null) fail("SDK version")
        releaseId(value["runtimeRevision"])
        val luau = exact(value["luau"], setOf("revision", "bytecodeVersions"))
        releaseId(luau["revision"])
        val bytecode = array(luau["bytecodeVersions"], 256)
        if (bytecode.isEmpty()) fail("bytecode versions")
        bytecode.forEach { integer(it, 0, 65_535) }
        val scene = exact(value["sceneFormat"], setOf("major", "minor"))
        integer(scene["major"], 0, 65_535); integer(scene["minor"], 0, 65_535)
        val timezone = exact(value["timezoneData"], setOf("format", "revision", "sha256"))
        oneOf(timezone["format"], "iana-tzdb"); releaseId(timezone["revision"]); hash(timezone["sha256"])
        sortedUnique(ids(value["requiredCapabilities"], 256))
    }

    private fun behavior(input: JsonElement): Pair<String, Long>? {
        val behavior = exact(input, setOf("screenId", "controls"), setOf("script"))
        id(behavior["screenId"])
        val scripted = mutableListOf<String>()
        val names = array(behavior["controls"]).map { item ->
            val control = exact(item, setOf("actionId", "behavior"))
            val name = id(control["actionId"])
            val binding = record(control["behavior"])
            when (text(binding["kind"])) {
                "script" -> { exact(binding, setOf("kind")); scripted.add(name) }
                "declarative" -> {
                    exact(binding, setOf("kind", "program"))
                    val program = array(binding["program"], 64)
                    if (program.isEmpty()) fail("empty screen program")
                    for (value in program) {
                        val action = record(value)
                        when (text(action["type"])) {
                            "emit" -> {
                                exact(action, setOf("type", "eventName"), setOf("payload"))
                                if (utf8(action["eventName"], 256).startsWith('$')) fail("reserved emission")
                                action["payload"]?.let { payload ->
                                    for ((key, source) in record(payload)) { utf8(JsonPrimitive(key), 256); source(source) }
                                }
                            }
                            "response_set" -> {
                                exact(action, setOf("type", "field", "value")); utf8(action["field"], 256); source(action["value"])
                            }
                            "response_unset" -> { exact(action, setOf("type", "field")); utf8(action["field"], 256) }
                            else -> fail("screen action")
                        }
                    }
                }
                else -> fail("screen binding")
            }
            name
        }
        sortedUnique(names)
        val script = behavior["script"]
        if (scripted.isEmpty() != (script == null)) fail("script presence")
        if (script == null) return null
        val document = exact(script, setOf("protocol", "artifact", "exportedActionIds"))
        oneOf(document["protocol"], "screen-actions")
        if (ids(document["exportedActionIds"]) != scripted) fail("script exports")
        val artifact = exact(document["artifact"], setOf("key", "sha256", "sizeBytes", "contentType"))
        val sha = hash(artifact["sha256"])
        if (text(artifact["key"]) != "screen-behavior/sha256/$sha.bin") fail("script artifact key")
        oneOf(artifact["contentType"], "application/octet-stream")
        return sha to integer(artifact["sizeBytes"], maximum = 4 * 1024 * 1024)
    }

    private fun source(input: JsonElement?) {
        val source = record(input)
        when (text(source["source"])) {
            "literal" -> exact(source, setOf("source", "value"))
            "invocation_value", "component_id", "instance_id" -> exact(source, setOf("source"))
            else -> fail("screen value source")
        }
    }

    internal fun releaseId(input: JsonElement?): String = id(input, 128).also { if ('\u0000' in it) fail("NUL identifier") }
    internal fun utf8(input: JsonElement?, maximum: Int): String = text(input).also {
        if (it.isEmpty() || it.encodeToByteArray().size > maximum) fail("UTF-8 string")
    }
}
