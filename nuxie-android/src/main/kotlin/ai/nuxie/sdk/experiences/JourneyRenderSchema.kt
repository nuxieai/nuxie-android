package ai.nuxie.sdk.experiences

import ai.nuxie.sdk.experiences.JourneyReleaseSchema.releaseId
import ai.nuxie.sdk.experiences.JourneyReleaseJson.array
import ai.nuxie.sdk.experiences.JourneyReleaseJson.boolean
import ai.nuxie.sdk.experiences.JourneyReleaseJson.exact
import ai.nuxie.sdk.experiences.JourneyReleaseJson.fail
import ai.nuxie.sdk.experiences.JourneyReleaseJson.hash
import ai.nuxie.sdk.experiences.JourneyReleaseJson.id
import ai.nuxie.sdk.experiences.JourneyReleaseJson.integer
import ai.nuxie.sdk.experiences.JourneyReleaseJson.number
import ai.nuxie.sdk.experiences.JourneyReleaseJson.oneOf
import ai.nuxie.sdk.experiences.JourneyReleaseJson.record
import ai.nuxie.sdk.experiences.JourneyReleaseJson.sortedUnique
import ai.nuxie.sdk.experiences.JourneyReleaseJson.text
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonNull

internal object JourneyRenderSchema {
    fun videoElements(render: JsonObject): List<ExperienceVideoElement> {
        val value = render["videoElements"] ?: return emptyList()
        val targets = mutableSetOf<Pair<String, String>>()
        val slots = mutableSetOf<Pair<Long, Long>>()
        return array(value, 4096).map { entry ->
            if (text(render["renderer"]) != "nux") fail("video element requires nux renderer")
            val element = exact(entry, setOf("sourceArtboardIndex", "artboardId", "viewNodeId", "renderedNodeId", "componentId", "readinessTimeoutSeconds", "optional"))
            val artboard = releaseId(element["artboardId"])
            val viewNode = releaseId(element["viewNodeId"])
            val renderedNode = releaseId(element["renderedNodeId"])
            val sourceArtboard = integer(element["sourceArtboardIndex"], 0, 0xffff_ffffL)
            val component = integer(element["componentId"], 1, 0xffff_ffffL)
            val timeout = number(element["readinessTimeoutSeconds"], 0.0, 60.0)
            val optional = boolean(element["optional"])
            if (!targets.add(artboard to renderedNode) || !slots.add(sourceArtboard to component)) {
                fail("duplicate video element target")
            }
            ExperienceVideoElement(sourceArtboard, artboard, viewNode, renderedNode, component, timeout, optional)
        }
    }

    fun validate(input: JsonObject) {
        val renderer = oneOf(input["renderer"], "nux")
        val sceneField = "nux"
        val render = exact(input, setOf("renderer", sceneField, "screens", "transitions", "textInputs", "assets"), setOf("videoElements"))
        videoElements(render)
        val scene = exact(render[sceneField], setOf("key", "sha256", "sizeBytes", "contentType"))
        val sha = hash(scene["sha256"])
        if (text(scene["key"]) != "renders/sha256/$sha.$sceneField") fail("render artifact key")
        oneOf(scene["contentType"], "application/vnd.nuxie.scene")
        integer(scene["sizeBytes"], 1, JourneyReleaseLimits.SCENE_ARTIFACT_BYTES.toLong())
        val screens = array(render["screens"], 256).map { input ->
            val screen = exact(input, setOf("id", "artboardId", "artboardName", "width", "height"), setOf("exit"))
            releaseId(screen["artboardId"]); id(screen["artboardName"])
            positive(screen["width"], 16_384.0); positive(screen["height"], 16_384.0)
            screen["exit"]?.let {
                val exit = exact(it, setOf("completeEventName", "durationMs"))
                releaseId(exit["completeEventName"]); integer(exit["durationMs"], maximum = 60_000)
            }
            releaseId(screen["id"])
        }.toSet()
        if (screens.isEmpty() || screens.size != array(render["screens"]).size) fail("render screens")
        for (input in array(render["transitions"], 1024)) {
            val transition = exact(input, setOf("id", "kind", "sourceScreenId", "destinationScreenId", "durationMs",
                "incomingOnTop", "source", "destination"), setOf("reverse"))
            releaseId(transition["id"]); oneOf(transition["kind"], "choreographed")
            for (key in listOf("sourceScreenId", "destinationScreenId")) if (releaseId(transition[key]) !in screens) fail("transition screen")
            integer(transition["durationMs"], maximum = 60_000); boolean(transition["incomingOnTop"])
            endpoints(transition)
            transition["reverse"]?.let { input ->
                val reverse = exact(input, setOf("source", "destination"), setOf("durationMs", "incomingOnTop"))
                reverse["durationMs"]?.let { integer(it, maximum = 60_000) }
                reverse["incomingOnTop"]?.let { boolean(it) }
                endpoints(reverse)
            }
        }
        for (input in array(render["textInputs"], 1024)) textInput(input, screens)
        val assets = array(render["assets"], 1024)
        val keys = assets.map(::asset)
        if (keys != keys.sorted()) fail("asset ordering")
        assets.zip(keys).groupBy { it.second }.values.filter { it.size > 1 }.forEach { group ->
            val bindings = group.map { record(it.first) }
            if (bindings.any { text(it["kind"]) != "video" }) fail("duplicate asset key")
            val sources = bindings.map { text(it["sourceAssetKey"]) }
            sortedUnique(sources)
            val identityFields = setOf("sourceAssetKey", "authoredAssetId", "assetUniqueName", "required")
            val metadata = bindings.first().filterKeys { it !in identityFields }
            if (bindings.any { it.filterKeys { field -> field !in identityFields } != metadata }) {
                fail("conflicting video asset metadata")
            }
        }
        val nativeAssets = assets.map(::record).filter { text(it["kind"]) in setOf("image", "font", "video") }
        for (field in listOf("authoredAssetId", "assetUniqueName")) {
            if (nativeAssets.map { it[field] }.toSet().size != nativeAssets.size) fail("duplicate native asset identity")
        }
        val videos = nativeAssets.filter { text(it["kind"]) == "video" }
        if (videos.isNotEmpty() && renderer != "nux") fail("video requires nux renderer")
        if (videos.map { it["sourceAssetKey"] }.toSet().size != videos.size) fail("duplicate video source")
    }

    private fun asset(input: JsonElement): String {
        val asset = record(input)
        if (text(asset["kind"]) == "font" && asset["location"]?.let(::text) == "system") {
            exact(asset, setOf("kind", "location", "authoredAssetId", "assetUniqueName", "family", "weight", "style", "required"))
            integer(asset["authoredAssetId"])
            oneOf(asset["family"], "System")
            oneOf(asset["weight"], "100", "200", "300", "400", "500", "600", "700", "800", "900")
            oneOf(asset["style"], "normal")
            if (!boolean(asset["required"])) fail("System font is required")
            return "system-font:${releaseId(asset["assetUniqueName"])}"
        }
        val common = setOf("kind", "key", "sha256", "sizeBytes", "contentType", "required")
        val digest = hash(asset["sha256"])
        integer(asset["sizeBytes"], maximum = JourneyReleaseLimits.EXTERNAL_ASSET_BYTES.toLong())
        boolean(asset["required"])
        val extension = when (text(asset["kind"])) {
            "image" -> {
                exact(asset, common + setOf("authoredAssetId", "assetUniqueName", "width", "height"))
                integer(asset["authoredAssetId"]); releaseId(asset["assetUniqueName"])
                integer(asset["width"], 1, 65_535); integer(asset["height"], 1, 65_535)
                when (oneOf(asset["contentType"], "image/png", "image/jpeg", "image/webp")) {
                    "image/png" -> "png"
                    "image/jpeg" -> "jpg"
                    else -> "webp"
                }
            }
            "video" -> {
                exact(asset, common + setOf("sourceAssetKey", "authoredAssetId", "assetUniqueName", "width", "height",
                    "durationMs", "videoCodec", "audioCodec", "captionTracks"))
                integer(asset["sizeBytes"], 1, JourneyReleaseLimits.EXTERNAL_ASSET_BYTES.toLong())
                integer(asset["authoredAssetId"]); releaseId(asset["assetUniqueName"])
                val source = id(asset["sourceAssetKey"], 128)
                if (!source.matches(Regex("^asset:[A-Za-z0-9_-]+$"))) fail("video source identity")
                integer(asset["width"], 1, 8192); integer(asset["height"], 1, 8192)
                integer(asset["durationMs"], 1)
                if (!text(asset["videoCodec"]).matches(Regex("^avc[13]\\.[0-9a-fA-F]{6}$"))) fail("video codec")
                if (asset["audioCodec"] != JsonNull) oneOf(asset["audioCodec"], "mp4a.40.2")
                val streams = array(asset["captionTracks"], 16).map { input ->
                    val track = exact(input, setOf("streamIndex", "codec", "language", "title"))
                    oneOf(track["codec"], "mov_text")
                    if (track["language"] != JsonNull && text(track["language"]).length > 128) fail("caption language")
                    if (track["title"] != JsonNull && text(track["title"]).length > 512) fail("caption title")
                    integer(track["streamIndex"])
                }
                if (streams != streams.sorted() || streams.toSet().size != streams.size) fail("caption ordering or duplicate")
                oneOf(asset["contentType"], "video/mp4")
                "mp4"
            }
            "font" -> {
                exact(asset, common + setOf("location", "authoredAssetId", "assetUniqueName", "family", "weight", "style", "format"))
                oneOf(asset["location"], "cdn")
                if (text(asset["family"]).trim().lowercase() == "system") fail("System font must use system location")
                integer(asset["authoredAssetId"]); releaseId(asset["assetUniqueName"])
                id(asset["family"]); id(asset["weight"], 32); oneOf(asset["style"], "normal", "italic")
                val format = oneOf(asset["format"], "ttf", "otf")
                when (oneOf(asset["contentType"], "font/ttf", "font/otf", "application/octet-stream")) {
                    "font/ttf" -> "ttf"
                    "font/otf" -> "otf"
                    else -> "bin"
                }.also { if (it != "bin" && it != format) fail("font format") }
            }
            "script", "shader" -> { exact(asset, common); oneOf(asset["contentType"], "application/octet-stream"); "bin" }
            else -> fail("asset kind")
        }
        return text(asset["key"]).also { if (it != "assets/sha256/$digest.$extension") fail("asset key") }
    }

    private fun endpoints(value: JsonObject) {
        for (key in listOf("source", "destination")) {
            releaseId(exact(value[key], setOf("completeEventName"))["completeEventName"])
        }
    }

    private fun textInput(input: JsonElement, screens: Set<String>) {
        val ids = setOf("id", "screenId", "artboardId", "viewNodeId", "renderedNodeId", "textObjectKey", "textRunObjectKey")
        val value = exact(input, ids + setOf("textName", "textRunName", "value", "editable", "geometry", "style", "secureTextEntry", "multiline"),
            setOf("responseFieldKey", "responseCapture", "placeholder", "keyboardType", "maxLength"))
        for (key in ids) releaseId(value[key])
        if (text(value["screenId"]) !in screens) fail("text input screen")
        id(value["textName"]); id(value["textRunName"])
        if (text(value["value"]).length > 1_000_000) fail("text input value")
        value["responseFieldKey"]?.let { releaseId(it) }
        value["responseCapture"]?.let {
            val mode = oneOf(it, "text", "binding")
            if (value["responseFieldKey"] == null) fail("response capture field")
            if (mode == "binding" && boolean(value["secureTextEntry"])) fail("secure binding response capture")
        }
        value["placeholder"]?.let { if (text(it).length > 1024) fail("placeholder") }
        value["keyboardType"]?.let { id(it, 64) }
        value["maxLength"]?.let { integer(it, 1, 1_000_000) }
        for (key in listOf("editable", "secureTextEntry", "multiline")) boolean(value[key])
        val paths = setOf("xPath", "yPath", "widthPath", "heightPath", "rotationPath", "scaleXPath", "scaleYPath")
        val geometry = exact(value["geometry"], paths)
        for (path in paths) id(geometry[path], 512)
        val style = exact(value["style"], setOf("fontFamily", "fontWeight", "fontStyle", "fontSize", "lineHeight", "letterSpacing", "color", "fontAssetUniqueName"), setOf("textAlign"))
        id(style["fontFamily"]); id(style["fontWeight"], 32); oneOf(style["fontStyle"], "normal", "italic")
        positive(style["fontSize"], 2048.0)
        val lineHeight = number(style["lineHeight"], -1.0, 8192.0)
        if (lineHeight != -1.0 && lineHeight <= 0.0) fail("natural or positive line height")
        number(style["letterSpacing"], -2048.0, 2048.0); integer(style["color"], maximum = 0xffffffffL)
        releaseId(style["fontAssetUniqueName"]); style["textAlign"]?.let { id(it, 32) }
    }

    private fun positive(value: JsonElement?, max: Double) {
        if (number(value, 0.0, max) == 0.0) fail("positive number")
    }
}
