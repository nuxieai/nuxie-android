package ai.nuxie.sdk.presentation

import ai.nuxie.sdk.runtime.NuxieRuntimeViewModelState
import kotlinx.serialization.json.*

/** Inspect a mounted published Screen on its runtime lane, rather than the page's list root. */
internal fun ExperienceSurfaceHost.fixtureActivePageRowOnLane(): NuxieRuntimeViewModelState {
    val page = ExperienceSurfaceHost::class.java.getDeclaredField("interactiveLayer")
        .apply { isAccessible = true }.get(this) as ExperienceInteractivePage
    return page.valuesOnLane(fixtureActivePageScreenIdOnLane())
}

/** Render declaration order is independent of the Journey's entry Screen. */
internal fun publishedFixtureEntryScreen(descriptor: JsonObject): JsonObject {
    val leg = descriptor.getValue("leg").jsonObject
    val entry = leg.getValue("steps").jsonArray.single {
        it.jsonObject.getValue("id") == leg.getValue("entryStepId")
    }.jsonObject.getValue("action").jsonObject.getValue("screenId")
    return descriptor.getValue("render").jsonObject.getValue("screens").jsonArray.single {
        it.jsonObject.getValue("id") == entry
    }.jsonObject
}

internal fun ExperienceSurfaceHost.fixtureActivePageScreenIdOnLane(): String =
    ExperienceSurfaceHost::class.java.getDeclaredField("activeRowId")
        .apply { isAccessible = true }.get(this) as String
