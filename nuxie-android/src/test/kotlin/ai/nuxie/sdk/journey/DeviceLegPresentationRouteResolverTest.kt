package ai.nuxie.sdk.journey

import ai.nuxie.sdk.events.SystemEventNames
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.junit.Assert.assertEquals
import org.junit.Test

class DeviceLegPresentationRouteResolverTest {
    @Test
    fun `screen lifecycle route prefers its exact screen before Journey fallback`() {
        val leg = buildJsonObject {
            putJsonArray("routes") {
                add(buildJsonObject {
                    put("eventName", SystemEventNames.SCREEN_SHOWN)
                    put("entryStepId", "journey-shown")
                    putJsonObject("host") { put("kind", "journey") }
                })
                add(buildJsonObject {
                    put("eventName", SystemEventNames.SCREEN_SHOWN)
                    put("entryStepId", "survey-shown")
                    putJsonObject("host") {
                        put("kind", "screen")
                        put("screenId", "survey")
                    }
                })
                add(buildJsonObject {
                    put("eventName", SystemEventNames.SCREEN_DISMISSED)
                    put("entryStepId", "survey-dismissed")
                    putJsonObject("host") {
                        put("kind", "screen")
                        put("screenId", "survey")
                    }
                })
            }
        }

        assertEquals(
            "survey-shown",
            DeviceLegPresentationRouteResolver.resolve(
                leg,
                SystemEventNames.SCREEN_SHOWN,
                "survey",
            ),
        )
        assertEquals(
            "journey-shown",
            DeviceLegPresentationRouteResolver.resolve(
                leg,
                SystemEventNames.SCREEN_SHOWN,
                "other",
            ),
        )
        assertEquals(
            "survey-dismissed",
            DeviceLegPresentationRouteResolver.resolve(
                leg,
                SystemEventNames.SCREEN_DISMISSED,
                "survey",
            ),
        )
    }
}
