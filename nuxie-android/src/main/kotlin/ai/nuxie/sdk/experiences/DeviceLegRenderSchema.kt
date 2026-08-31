package ai.nuxie.sdk.experiences

import ai.nuxie.sdk.experiences.DeviceLegReleaseSchema.releaseId
import ai.nuxie.sdk.experiences.ReleaseJson.array
import ai.nuxie.sdk.experiences.ReleaseJson.boolean
import ai.nuxie.sdk.experiences.ReleaseJson.exact
import ai.nuxie.sdk.experiences.ReleaseJson.fail
import ai.nuxie.sdk.experiences.ReleaseJson.hash
import ai.nuxie.sdk.experiences.ReleaseJson.id
import ai.nuxie.sdk.experiences.ReleaseJson.integer
import ai.nuxie.sdk.experiences.ReleaseJson.number
import ai.nuxie.sdk.experiences.ReleaseJson.oneOf
import ai.nuxie.sdk.experiences.ReleaseJson.record
import ai.nuxie.sdk.experiences.ReleaseJson.sortedUnique
import ai.nuxie.sdk.experiences.ReleaseJson.text
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

internal object DeviceLegRenderSchema {
    fun validate(input: JsonObject) {
        val render = exact(input, setOf("renderer", "riv", "screens", "transitions", "textInputs", "assets"))
        oneOf(render["renderer"], "rive")
        val riv = exact(render["riv"], setOf("key", "sha256", "sizeBytes", "contentType"))
        val sha = hash(riv["sha256"])
        if (text(riv["key"]) != "renders/sha256/$sha.riv") fail("render artifact key")
        oneOf(riv["contentType"], "application/vnd.rive")
        integer(riv["sizeBytes"], maximum = ExperienceReleaseLimits.RIV_ARTIFACT_BYTES.toLong())
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
    }

    private fun asset(input: JsonElement): String {
        val asset = record(input)
        val common = setOf("kind", "key", "sha256", "sizeBytes", "contentType", "required")
        val digest = hash(asset["sha256"])
        integer(asset["sizeBytes"], maximum = ExperienceReleaseLimits.EXTERNAL_ASSET_BYTES.toLong())
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
            "font" -> {
                exact(asset, common + setOf("riveAssetId", "riveUniqueName", "family", "weight", "style", "format"))
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
        positive(style["fontSize"], 2048.0); positive(style["lineHeight"], 8192.0)
        number(style["letterSpacing"], -2048.0, 2048.0); integer(style["color"], maximum = 0xffffffffL)
        releaseId(style["fontAssetRiveUniqueName"]); style["textAlign"]?.let { id(it, 32) }
    }

    private fun positive(value: JsonElement?, max: Double) {
        if (number(value, 0.0, max) == 0.0) fail("positive number")
    }
}
