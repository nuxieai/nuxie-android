package ai.nuxie.sdk.experiences

import ai.nuxie.sdk.experiences.JourneyReleaseJson.boolean
import ai.nuxie.sdk.experiences.JourneyReleaseJson.exact
import ai.nuxie.sdk.experiences.JourneyReleaseJson.fail
import ai.nuxie.sdk.experiences.JourneyReleaseJson.journeyId
import ai.nuxie.sdk.experiences.JourneyReleaseJson.number
import ai.nuxie.sdk.experiences.JourneyReleaseJson.oneOf
import ai.nuxie.sdk.experiences.JourneyReleaseJson.record
import ai.nuxie.sdk.experiences.JourneyReleaseJson.text
import kotlinx.serialization.json.JsonElement

/** An authenticated authored target and its generic runtime command. */
internal data class JourneyVideoAction(
    val artboardId: String,
    val viewNodeId: String,
    val commandKind: Int,
    val commandValue: Double,
) {
    companion object {
        fun parse(input: JsonElement?): JourneyVideoAction {
            val action = exact(input, setOf("type", "target", "command"))
            oneOf(action["type"], "video")
            val target = exact(action["target"], setOf("artboardId", "viewNodeId"))
            val command = record(action["command"])
            val kind: Int
            val value: Double
            when (text(command["type"])) {
                "play", "pause" -> {
                    exact(command, setOf("type"))
                    kind = if (text(command["type"]) == "play") 0 else 1
                    value = 0.0
                }
                "seek" -> {
                    exact(command, setOf("type", "seconds"))
                    kind = 2; value = number(command["seconds"], 0.0)
                }
                "rate" -> {
                    exact(command, setOf("type", "rate"))
                    kind = 3; value = number(command["rate"], 0.0, Float.MAX_VALUE.toDouble())
                    if (value == 0.0) fail("video rate must be positive")
                }
                "volume" -> {
                    exact(command, setOf("type", "volume"))
                    kind = 4; value = number(command["volume"], 0.0, 1.0)
                }
                "mute", "loop" -> {
                    val mute = text(command["type"]) == "mute"
                    val field = if (mute) "muted" else "enabled"
                    exact(command, setOf("type", field))
                    kind = if (mute) 5 else 9
                    value = if (boolean(command[field])) 1.0 else 0.0
                }
                else -> fail("unsupported video command")
            }
            return JourneyVideoAction(journeyId(target["artboardId"]), journeyId(target["viewNodeId"]), kind, value)
        }
    }
}
