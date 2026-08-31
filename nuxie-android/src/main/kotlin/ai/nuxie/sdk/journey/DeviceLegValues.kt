package ai.nuxie.sdk.journey

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Null is the unknown sentinel; JsonNull is an explicitly known value. These
 * operations see only the current event and locally buffered response context. */
internal object DeviceLegValues {
    fun resolve(value: JsonObject, context: JsonObject): JsonElement? {
        return when (value.text("type")) {
            "Null" -> JsonNull
            "Boolean", "Number", "String" -> value["value"]
            "Event.Field" -> (context["event"] as? JsonObject)?.get(value.text("key"))
            "Response.Field" -> (context["responses"] as? JsonObject)?.get(value.text("key"))
            "Array" -> {
                val items = mutableListOf<JsonElement>()
                for (item in value.getValue("items").jsonArray) items += resolve(item.jsonObject, context) ?: return null
                JsonArray(items)
            }
            "Object" -> {
                val fields = linkedMapOf<String, JsonElement>()
                for ((key, item) in value.getValue("fields").jsonObject) fields[key] = resolve(item.jsonObject, context) ?: return null
                JsonObject(fields)
            }
            else -> null
        }

    }

    fun evaluate(condition: JsonObject, context: JsonObject): Boolean? {
        return when (condition.text("type")) {
            "Truthy" -> resolve(condition.getValue("value").jsonObject, context)?.let(::truthy)
            "Not" -> evaluate(condition.getValue("condition").jsonObject, context)?.not()
            "All", "Any" -> {
                val results = condition.getValue("conditions").jsonArray.map { evaluate(it.jsonObject, context) }
                if (condition.text("type") == "All") when {
                    false in results -> false
                    null in results -> null
                    else -> true
                } else when {
                    true in results -> true
                    null in results -> null
                    else -> false
                }
            }
            "Contains" -> {
                val collection = resolve(condition.getValue("collection").jsonObject, context) ?: return null
                val value = resolve(condition.getValue("value").jsonObject, context) ?: return null
                when {
                    collection is JsonArray -> collection.any { equal(it, value) }
                    collection is JsonPrimitive && collection.isString && value is JsonPrimitive && value.isString ->
                        collection.content.contains(value.content)
                    else -> false
                }
            }
            "Compare" -> {
                val left = resolve(condition.getValue("left").jsonObject, context) ?: return null
                val right = resolve(condition.getValue("right").jsonObject, context) ?: return null
                when (condition.text("op")) {
                    "==" -> equal(left, right)
                    "!=" -> !equal(left, right)
                    else -> {
                        if (left !is JsonPrimitive || right !is JsonPrimitive) return null
                        val comparison = if (left.isString && right.isString) left.content.compareTo(right.content)
                        else {
                            if (left.isString || right.isString) return null
                            val a = left.doubleOrNull ?: return null
                            val b = right.doubleOrNull ?: return null
                            if (a == b) 0 else a.compareTo(b)
                        }
                        when (condition.text("op")) {
                            "<" -> comparison < 0
                            "<=" -> comparison <= 0
                            ">" -> comparison > 0
                            ">=" -> comparison >= 0
                            else -> null
                        }
                    }
                }
            }
            else -> null
        }

    }

    private fun truthy(value: JsonElement): Boolean = when (value) {
        JsonNull -> false
        is JsonArray, is JsonObject -> true
        is JsonPrimitive -> when {
            value.isString -> value.content.isNotEmpty()
            value.booleanOrNull != null -> value.booleanOrNull == true
            else -> value.doubleOrNull?.let { it != 0.0 } ?: false
        }
    }

    private fun equal(left: JsonElement, right: JsonElement): Boolean = when {
        left == JsonNull || right == JsonNull -> left == right
        left is JsonArray && right is JsonArray -> left.size == right.size && left.indices.all { equal(left[it], right[it]) }
        left is JsonObject && right is JsonObject -> left.keys == right.keys && left.all { (key, value) -> equal(value, right.getValue(key)) }
        left is JsonPrimitive && right is JsonPrimitive -> when {
            left.isString || right.isString -> left.isString && right.isString && left.content == right.content
            left.booleanOrNull != null || right.booleanOrNull != null -> left.booleanOrNull != null && left.booleanOrNull == right.booleanOrNull
            else -> left.doubleOrNull != null && left.doubleOrNull == right.doubleOrNull
        }
        else -> false
    }

    private fun JsonObject.text(key: String) = getValue(key).jsonPrimitive.content
}
