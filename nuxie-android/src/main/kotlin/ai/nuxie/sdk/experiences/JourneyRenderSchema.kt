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
        val renderer = oneOf(input["renderer"], "rive", "nux")
        val sceneField = if (renderer == "nux") "nux" else "riv"
        val render = exact(input, setOf("renderer", sceneField, "screens", "transitions", "textInputs", "assets"), setOf("videoElements"))
        videoElements(render)
        val scene = exact(render[sceneField], setOf("key", "sha256", "sizeBytes", "contentType"))
        val sha = hash(scene["sha256"])
        if (text(scene["key"]) != "renders/sha256/$sha.$sceneField") fail("render artifact key")
        oneOf(scene["contentType"], if (renderer == "nux") "application/vnd.nuxie.scene" else "application/vnd.rive")
        integer(scene["sizeBytes"], if (renderer == "nux") 1 else 0, JourneyReleaseLimits.RIV_ARTIFACT_BYTES.toLong())
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
        sortedUnique(keys)
        val nativeAssets = assets.map(::record).filter { text(it["kind"]) in setOf("image", "font", "video") }
        for (field in listOf("riveAssetId", "riveUniqueName")) {
            if (nativeAssets.map { it[field] }.toSet().size != nativeAssets.size) fail("duplicate native asset identity")
        }
        val videos = nativeAssets.filter { text(it["kind"]) == "video" }
        if (videos.isNotEmpty() && renderer != "nux") fail("video requires nux renderer")
        if (videos.map { it["sourceAssetKey"] }.toSet().size != videos.size) fail("duplicate video source")
    }

    private fun asset(input: JsonElement): String {
        val asset = record(input)
        if (text(asset["kind"]) == "font" && asset["location"]?.let(::text) == "system") {
            exact(asset, setOf("kind", "location", "riveAssetId", "riveUniqueName", "family", "weight", "style", "required"))
            integer(asset["riveAssetId"])
            oneOf(asset["family"], "System")
            oneOf(asset["weight"], "100", "200", "300", "400", "500", "600", "700", "800", "900")
            oneOf(asset["style"], "normal")
            if (!boolean(asset["required"])) fail("System font is required")
            return "system-font:${releaseId(asset["riveUniqueName"])}"
        }
        val common = setOf("kind", "key", "sha256", "sizeBytes", "contentType", "required")
        val digest = hash(asset["sha256"])
        integer(asset["sizeBytes"], maximum = JourneyReleaseLimits.EXTERNAL_ASSET_BYTES.toLong())
        boolean(asset["required"])
        val extension = when (text(asset["kind"])) {
            "image" -> {
                exact(asset, common + setOf("riveAssetId", "riveUniqueName", "width", "height"))
                integer(asset["riveAssetId"]); releaseId(asset["riveUniqueName"])
                integer(asset["width"], 1, 65_535); integer(asset["height"], 1, 65_535)
                when (oneOf(asset["contentType"], "image/png", "image/jpeg", "image/webp")) {
                    "image/png" -> "png"
                    "image/jpeg" -> "jpg"
                    else -> "webp"
                }
            }
            "video" -> {
                exact(asset, common + setOf("sourceAssetKey", "riveAssetId", "riveUniqueName", "width", "height",
                    "durationMs", "videoCodec", "audioCodec", "captionTracks"))
                integer(asset["sizeBytes"], 1, JourneyReleaseLimits.EXTERNAL_ASSET_BYTES.toLong())
                integer(asset["riveAssetId"]); releaseId(asset["riveUniqueName"])
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
                exact(asset, common + setOf("location", "riveAssetId", "riveUniqueName", "family", "weight", "style", "format"))
                oneOf(asset["location"], "cdn")
                if (text(asset["family"]).trim().lowercase() == "system") fail("System font must use system location")
                integer(asset["riveAssetId"]); releaseId(asset["riveUniqueName"])
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
        val ids = setOf("id", "screenId", "artboardId", "viewNodeId", "renderedNodeId", "riveTextObjectKey", "riveTextRunObjectKey")
        val value = exact(input, ids + setOf("riveTextName", "riveTextRunName", "value", "editable", "geometry", "style", "secureTextEntry", "multiline"),
            setOf("responseFieldKey", "placeholder", "keyboardType", "maxLength"))
        for (key in ids) releaseId(value[key])
        if (text(value["screenId"]) !in screens) fail("text input screen")
        id(value["riveTextName"]); id(value["riveTextRunName"])
        if (text(value["value"]).length > 1_000_000) fail("text input value")
        value["responseFieldKey"]?.let { releaseId(it) }
        value["placeholder"]?.let { if (text(it).length > 1024) fail("placeholder") }
        value["keyboardType"]?.let { id(it, 64) }
        value["maxLength"]?.let { integer(it, 1, 1_000_000) }
        for (key in listOf("editable", "secureTextEntry", "multiline")) boolean(value[key])
        val paths = setOf("xPath", "yPath", "widthPath", "heightPath", "rotationPath", "scaleXPath", "scaleYPath")
        val geometry = exact(value["geometry"], paths)
        for (path in paths) id(geometry[path], 512)
        val style = exact(value["style"], setOf("fontFamily", "fontWeight", "fontStyle", "fontSize", "lineHeight", "letterSpacing", "color", "fontAssetRiveUniqueName"), setOf("textAlign"))
        id(style["fontFamily"]); id(style["fontWeight"], 32); oneOf(style["fontStyle"], "normal", "italic")
        positive(style["fontSize"], 2048.0)
        val lineHeight = number(style["lineHeight"], -1.0, 8192.0)
        if (lineHeight != -1.0 && lineHeight <= 0.0) fail("natural or positive line height")
        number(style["letterSpacing"], -2048.0, 2048.0); integer(style["color"], maximum = 0xffffffffL)
        releaseId(style["fontAssetRiveUniqueName"]); style["textAlign"]?.let { id(it, 32) }
    }

    private fun positive(value: JsonElement?, max: Double) {
        if (number(value, 0.0, max) == 0.0) fail("positive number")
    }
}
