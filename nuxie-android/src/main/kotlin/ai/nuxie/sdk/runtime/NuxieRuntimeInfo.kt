package ai.nuxie.sdk.runtime

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Keeps raw bridge compatibility facts inside the enforced runtime package. */
internal fun nuxieRuntimeSourceRevision(): String? = runCatching {
    Json.parseToJsonElement(NuxieRuntime.shared.info()).jsonObject
        .getValue("sourceRevision").jsonPrimitive.content
}.getOrNull()?.takeIf { it.isNotBlank() }
