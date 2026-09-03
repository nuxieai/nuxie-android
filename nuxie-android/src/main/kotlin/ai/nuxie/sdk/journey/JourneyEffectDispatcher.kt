package ai.nuxie.sdk.journey

import ai.nuxie.sdk.AppAction
import ai.nuxie.sdk.AppActionValueResolver
import ai.nuxie.sdk.ExperienceRef
import ai.nuxie.sdk.events.JsonValueConverter
import ai.nuxie.sdk.events.StableEventCommitAdmission
import ai.nuxie.sdk.identity.IdentityService
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

/** Executes renderless authenticated effects after their stable id is durable. */
internal class JourneyEffectDispatcher(
    private val identity: IdentityService,
    private val capture: suspend (
        String,
        Map<String, Any?>,
        String,
        String,
        StableEventCommitAdmission,
    ) -> Boolean,
    private val deliverAppAction: suspend (
        AppAction,
        ((() -> Unit) -> Boolean),
    ) -> Boolean,
) : JourneyDispatching {
    override suspend fun dispatch(request: JourneyDispatchRequest): JourneyDispatchResult {
        if (!requestIsCurrent(request)) return JourneyDispatchResult.Failed
        return when (request.action.text("type")) {
            "send_event" -> sendEvent(request)
            "update_customer" -> updateCustomer(request)
            "milestone" -> milestone(request)
            "submit_response" -> JourneyDispatchResult.Outlet("next")
            "app_action" -> appAction(request)
            "exit" -> {
                val reason = request.action.text("reason")
                JourneyDispatchResult.Complete(reason?.takeIf(String::isNotEmpty) ?: "completed")
            }
            else -> JourneyDispatchResult.Unsupported
        }
    }

    private suspend fun sendEvent(request: JourneyDispatchRequest): JourneyDispatchResult {
        val name = request.action.text("eventName") ?: return JourneyDispatchResult.Failed
        val payload = (request.action["payload"] as? JsonObject)?.let {
            resolve(it, request.run.context)
        } ?: if (request.action.containsKey("payload")) return JourneyDispatchResult.Failed
        else JsonObject(emptyMap())
        val properties = JsonValueConverter.toNativeMap(payload).toMutableMap()
        properties.putAll(attribution(request))
        return captureThenAdvance(name, properties, request)
    }

    private suspend fun updateCustomer(request: JourneyDispatchRequest): JourneyDispatchResult {
        val authored = request.action["attributes"] as? JsonObject
            ?: return JourneyDispatchResult.Failed
        val attributes = resolve(authored, request.run.context)
            ?: return JourneyDispatchResult.Failed
        val native = JsonValueConverter.toNativeMap(attributes)
        if (!publishIfCurrent(request) { identity.setUserProperties(native) }) {
            return JourneyDispatchResult.Failed
        }
        val properties = attribution(request).toMutableMap()
        properties["attributes_updated"] = native.keys.sorted()
        return captureThenAdvance(CUSTOMER_UPDATED, properties, request)
    }

    private suspend fun milestone(request: JourneyDispatchRequest): JourneyDispatchResult {
        val milestoneId = request.action.text("milestoneId")
            ?: return JourneyDispatchResult.Failed
        val properties = attribution(request).toMutableMap()
        properties["milestone_id"] = milestoneId
        return captureThenAdvance(JourneyEventNames.MILESTONE, properties, request)
    }

    private suspend fun appAction(request: JourneyDispatchRequest): JourneyDispatchResult {
        val name = request.action.text("name") ?: return JourneyDispatchResult.Failed
        val authored = request.action["payload"] as? JsonObject
        val payload = authored?.let { resolve(it, request.run.context) }
        if (authored != null && payload == null) return JourneyDispatchResult.Failed
        val nativePayload = payload?.let(JsonValueConverter::toNativeMap)
        val action = AppAction(
            name,
            nativePayload?.let(AppActionValueResolver::resolvedRecord),
            ExperienceRef(
                request.run.reference.text("experienceId") ?: return JourneyDispatchResult.Failed,
                request.run.reference.text("versionId"),
                request.run.journeyId,
            ),
        )
        val delivered = deliverAppAction(action) { publication ->
            publishIfCurrent(request, publication)
        }
        if (!delivered) return JourneyDispatchResult.Failed
        val properties = attribution(request).toMutableMap()
        properties["name"] = name
        nativePayload?.let { properties["payload"] = it }
        return captureThenAdvance(APP_ACTION_REQUESTED, properties, request)
    }

    private suspend fun captureThenAdvance(
        name: String,
        properties: Map<String, Any?>,
        request: JourneyDispatchRequest,
    ): JourneyDispatchResult {
        if (!requestIsCurrent(request)) return JourneyDispatchResult.Failed
        if (!capture(
                name,
                properties,
                request.effectId,
                request.distinctId,
                eventCommitAdmission(request),
            )
        ) {
            return JourneyDispatchResult.Failed
        }
        return if (requestIsCurrent(request)) JourneyDispatchResult.Outlet("next")
        else JourneyDispatchResult.Failed
    }

    private fun requestIsCurrent(request: JourneyDispatchRequest): Boolean =
        request.executionFence.performIfCurrent(request.executionFenceToken) {
            identity.withCurrentScope(request.identityScope) { true }
        } == true

    private fun publishIfCurrent(
        request: JourneyDispatchRequest,
        publication: () -> Unit,
    ): Boolean = request.executionFence.performIfCurrent(request.executionFenceToken) {
        identity.withCurrentScope(request.identityScope) {
            publication()
            true
        }
    } == true

    private fun eventCommitAdmission(
        request: JourneyDispatchRequest,
    ): StableEventCommitAdmission = StableEventCommitAdmission { commit ->
        request.executionFence.performIfCurrent(request.executionFenceToken) {
            identity.withCurrentScope(request.identityScope, commit)
        }
    }

    private fun resolve(values: JsonObject, context: JsonObject): JsonObject? {
        val result = linkedMapOf<String, JsonElement>()
        for ((key, value) in values) {
            val expression = value as? JsonObject ?: return null
            result[key] = JourneyValues.resolve(expression, context) ?: return null
        }
        return JsonObject(result)
    }

    private fun attribution(request: JourneyDispatchRequest): Map<String, Any?> = mapOf(
        "journey_id" to request.run.journeyId,
        "experience_id" to request.run.experienceId,
        "experience_version_id" to request.run.reference.text("versionId"),
        "leg_id" to request.run.reference.text("legId"),
        "leg_generation" to request.run.generation,
    )

    private companion object {
        const val CUSTOMER_UPDATED = "\$customer_updated"
        const val APP_ACTION_REQUESTED = "\$app_action_requested"
    }
}

private fun JsonObject.text(key: String): String? =
    (this[key] as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.content
