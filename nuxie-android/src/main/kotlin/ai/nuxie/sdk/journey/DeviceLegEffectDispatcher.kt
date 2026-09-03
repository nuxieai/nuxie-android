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
internal class DeviceLegEffectDispatcher(
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
) : DeviceLegDispatching {
    override suspend fun dispatch(request: DeviceLegDispatchRequest): DeviceLegDispatchResult {
        if (!requestIsCurrent(request)) return DeviceLegDispatchResult.Failed
        return when (request.action.text("type")) {
            "send_event" -> sendEvent(request)
            "update_customer" -> updateCustomer(request)
            "milestone" -> milestone(request)
            "submit_response" -> DeviceLegDispatchResult.Outlet("next")
            "app_action" -> appAction(request)
            "exit" -> {
                val reason = request.action.text("reason")
                DeviceLegDispatchResult.Complete(reason?.takeIf(String::isNotEmpty) ?: "completed")
            }
            else -> DeviceLegDispatchResult.Unsupported
        }
    }

    private suspend fun sendEvent(request: DeviceLegDispatchRequest): DeviceLegDispatchResult {
        val name = request.action.text("eventName") ?: return DeviceLegDispatchResult.Failed
        val payload = (request.action["payload"] as? JsonObject)?.let {
            resolve(it, request.run.context)
        } ?: if (request.action.containsKey("payload")) return DeviceLegDispatchResult.Failed
        else JsonObject(emptyMap())
        val properties = JsonValueConverter.toNativeMap(payload).toMutableMap()
        properties.putAll(attribution(request))
        return captureThenAdvance(name, properties, request)
    }

    private suspend fun updateCustomer(request: DeviceLegDispatchRequest): DeviceLegDispatchResult {
        val authored = request.action["attributes"] as? JsonObject
            ?: return DeviceLegDispatchResult.Failed
        val attributes = resolve(authored, request.run.context)
            ?: return DeviceLegDispatchResult.Failed
        val native = JsonValueConverter.toNativeMap(attributes)
        if (!publishIfCurrent(request) { identity.setUserProperties(native) }) {
            return DeviceLegDispatchResult.Failed
        }
        val properties = attribution(request).toMutableMap()
        properties["attributes_updated"] = native.keys.sorted()
        return captureThenAdvance(CUSTOMER_UPDATED, properties, request)
    }

    private suspend fun milestone(request: DeviceLegDispatchRequest): DeviceLegDispatchResult {
        val milestoneId = request.action.text("milestoneId")
            ?: return DeviceLegDispatchResult.Failed
        val properties = attribution(request).toMutableMap()
        properties["milestone_id"] = milestoneId
        return captureThenAdvance(JourneyEventNames.MILESTONE, properties, request)
    }

    private suspend fun appAction(request: DeviceLegDispatchRequest): DeviceLegDispatchResult {
        val name = request.action.text("name") ?: return DeviceLegDispatchResult.Failed
        val authored = request.action["payload"] as? JsonObject
        val payload = authored?.let { resolve(it, request.run.context) }
        if (authored != null && payload == null) return DeviceLegDispatchResult.Failed
        val nativePayload = payload?.let(JsonValueConverter::toNativeMap)
        val action = AppAction(
            name,
            nativePayload?.let(AppActionValueResolver::resolvedRecord),
            ExperienceRef(
                request.run.reference.text("experienceId") ?: return DeviceLegDispatchResult.Failed,
                request.run.reference.text("versionId"),
                request.run.journeyId,
            ),
        )
        val delivered = deliverAppAction(action) { publication ->
            publishIfCurrent(request, publication)
        }
        if (!delivered) return DeviceLegDispatchResult.Failed
        val properties = attribution(request).toMutableMap()
        properties["name"] = name
        nativePayload?.let { properties["payload"] = it }
        return captureThenAdvance(APP_ACTION_REQUESTED, properties, request)
    }

    private suspend fun captureThenAdvance(
        name: String,
        properties: Map<String, Any?>,
        request: DeviceLegDispatchRequest,
    ): DeviceLegDispatchResult {
        if (!requestIsCurrent(request)) return DeviceLegDispatchResult.Failed
        if (!capture(
                name,
                properties,
                request.effectId,
                request.distinctId,
                eventCommitAdmission(request),
            )
        ) {
            return DeviceLegDispatchResult.Failed
        }
        return if (requestIsCurrent(request)) DeviceLegDispatchResult.Outlet("next")
        else DeviceLegDispatchResult.Failed
    }

    private fun requestIsCurrent(request: DeviceLegDispatchRequest): Boolean =
        request.executionFence.performIfCurrent(request.executionFenceToken) {
            identity.withCurrentScope(request.identityScope) { true }
        } == true

    private fun publishIfCurrent(
        request: DeviceLegDispatchRequest,
        publication: () -> Unit,
    ): Boolean = request.executionFence.performIfCurrent(request.executionFenceToken) {
        identity.withCurrentScope(request.identityScope) {
            publication()
            true
        }
    } == true

    private fun eventCommitAdmission(
        request: DeviceLegDispatchRequest,
    ): StableEventCommitAdmission = StableEventCommitAdmission { commit ->
        request.executionFence.performIfCurrent(request.executionFenceToken) {
            identity.withCurrentScope(request.identityScope, commit)
        }
    }

    private fun resolve(values: JsonObject, context: JsonObject): JsonObject? {
        val result = linkedMapOf<String, JsonElement>()
        for ((key, value) in values) {
            val expression = value as? JsonObject ?: return null
            result[key] = DeviceLegValues.resolve(expression, context) ?: return null
        }
        return JsonObject(result)
    }

    private fun attribution(request: DeviceLegDispatchRequest): Map<String, Any?> = mapOf(
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
