package ai.nuxie.sdk

import ai.nuxie.sdk.events.CanonicalJson
import java.math.BigDecimal
import java.math.BigInteger
import java.math.MathContext
import java.math.RoundingMode
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** A JSON-safe scalar delivered in an App Action payload. */
sealed interface AppActionValue {
    /** A string value. */
    data class String(val value: kotlin.String) : AppActionValue

    /** A signed 64-bit integer value. */
    data class Int(val value: Long) : AppActionValue

    /** A floating-point value. Resolved authored values are always finite. */
    data class Double(val value: kotlin.Double) : AppActionValue

    /** A Boolean value. */
    data class Bool(val value: Boolean) : AppActionValue
}

/** iOS `AppActionValue.resolved` parity kept off the public wrapper surface. */
internal object AppActionValueResolver {
    /** Converts a resolved authored value into the public scalar vocabulary. */
    fun resolved(value: Any?): AppActionValue? = when (value) {
        null -> null
        is Boolean -> AppActionValue.Bool(value)
        is Byte -> AppActionValue.Int(value.toLong())
        is Short -> AppActionValue.Int(value.toLong())
        is kotlin.Int -> AppActionValue.Int(value.toLong())
        is Long -> AppActionValue.Int(value)
        is UByte -> AppActionValue.Int(value.toLong())
        is UShort -> AppActionValue.Int(value.toLong())
        is UInt -> AppActionValue.Int(value.toLong())
        is ULong -> if (value <= Long.MAX_VALUE.toULong()) {
            AppActionValue.Int(value.toLong())
        } else {
            value.toDouble().takeIf(kotlin.Double::isFinite)?.let(AppActionValue::Double)
        }
        is Float -> value.takeIf(Float::isFinite)?.toDouble()?.let(AppActionValue::Double)
        is kotlin.Double -> value.takeIf(kotlin.Double::isFinite)?.let(AppActionValue::Double)
        // Swift Decimal intentionally projects through a finite Double,
        // even when its authored spelling is integral.
        is BigDecimal -> value.toDouble().takeIf(kotlin.Double::isFinite)?.let(AppActionValue::Double)
        is BigInteger -> value.toLongExactOrNull()?.let(AppActionValue::Int)
            ?: value.toDouble().takeIf(kotlin.Double::isFinite)?.let(AppActionValue::Double)
        is kotlin.String -> AppActionValue.String(value)
        is Number -> resolveNumber(value)
        else -> resolvedContainer(value)?.let(AppActionValue::String)
    }

    /** Applies [resolved] independently and omits null or invalid fields. */
    fun resolvedRecord(values: Map<kotlin.String, Any?>): Map<kotlin.String, AppActionValue> =
        buildMap {
            values.forEach { (key, value) -> resolved(value)?.let { put(key, it) } }
        }

    private fun resolveNumber(value: Number): AppActionValue? {
        val double = value.toDouble()
        if (!double.isFinite()) return null
        val candidate = value.toLong()
        val exactInteger = runCatching {
            BigDecimal(value.toString()).compareTo(BigDecimal.valueOf(candidate)) == 0
        }.getOrDefault(false)
        return if (exactInteger) AppActionValue.Int(candidate) else AppActionValue.Double(double)
    }

    private fun resolvedContainer(value: Any): kotlin.String? {
        val json = strictJsonValue(value) ?: return null
        if (json !is JsonObject && json !is JsonArray) return null
        return CanonicalJson.encode(json)
    }

    private fun strictJsonValue(value: Any?): JsonElement? = when (value) {
        null, JsonNull -> JsonNull
        is JsonObject -> strictJsonObject(value)
        is JsonArray -> strictJsonArray(value)
        is JsonPrimitive -> when {
            value.isString -> value
            value.content == "true" || value.content == "false" -> value
            // JsonPrimitive erases whether a number began as Float, Double, or Decimal.
            // Preserve its already-authored JSON token rather than guess at Foundation's type.
            STRICT_NUMBER.matches(value.content) -> value
            else -> null
        }
        is Boolean -> JsonPrimitive(value)
        is kotlin.String -> JsonPrimitive(value)
        is Byte -> JsonPrimitive(value)
        is Short -> JsonPrimitive(value)
        is kotlin.Int -> JsonPrimitive(value)
        is Long -> JsonPrimitive(value)
        is UByte -> strictJsonNumber(value.toString())
        is UShort -> strictJsonNumber(value.toString())
        is UInt -> strictJsonNumber(value.toString())
        is ULong -> strictJsonNumber(value.toString())
        is Float -> value.takeIf(Float::isFinite)
            ?.toDouble()
            ?.let(::foundationDoubleNumber)
            ?.let(::strictJsonNumber)
        is kotlin.Double -> value.takeIf(kotlin.Double::isFinite)
            ?.let(::foundationDoubleNumber)
            ?.let(::strictJsonNumber)
        is BigDecimal -> strictJsonNumber(foundationDecimalNumber(value))
        is BigInteger -> strictJsonNumber(value.toString())
        is Number -> value.toDouble().takeIf(kotlin.Double::isFinite)
            ?.let { strictJsonNumber(value.toString()) }
        is Map<*, *> -> strictJsonObject(value)
        is List<*> -> strictJsonArray(value)
        is Array<*> -> strictJsonArray(value.asList())
        is BooleanArray -> strictJsonArray(value.toList())
        is ByteArray -> strictJsonArray(value.toList())
        is ShortArray -> strictJsonArray(value.toList())
        is IntArray -> strictJsonArray(value.toList())
        is LongArray -> strictJsonArray(value.toList())
        is FloatArray -> strictJsonArray(value.toList())
        is DoubleArray -> strictJsonArray(value.toList())
        else -> null
    }

    private fun strictJsonObject(value: Map<*, *>): JsonObject? {
        val entries = linkedMapOf<kotlin.String, JsonElement>()
        value.forEach { (key, child) ->
            if (key !is kotlin.String) return null
            entries[key] = strictJsonValue(child) ?: return null
        }
        return JsonObject(entries)
    }

    private fun strictJsonArray(value: List<*>): JsonArray? {
        val elements = ArrayList<JsonElement>(value.size)
        value.forEach { child -> elements += strictJsonValue(child) ?: return null }
        return JsonArray(elements)
    }

    private fun strictJsonNumber(value: kotlin.String): JsonElement? =
        value.takeIf(STRICT_NUMBER::matches)?.let(Json::parseToJsonElement)

    /** Darwin JSONSerialization's finite Double spelling: C `%.17g` round-trip precision. */
    private fun foundationDoubleNumber(value: kotlin.Double): kotlin.String {
        if (value == 0.0) {
            return if (value.toRawBits() < 0) "-0" else "0"
        }

        val rounded = BigDecimal(value)
            .round(FOUNDATION_DOUBLE_CONTEXT)
            .stripTrailingZeros()
        val exponent = rounded.precision() - rounded.scale() - 1
        if (exponent >= -4 && exponent < FOUNDATION_DOUBLE_CONTEXT.precision) {
            return rounded.toPlainString()
        }

        val digits = rounded.unscaledValue().abs().toString()
        val mantissa = if (digits.length == 1) digits else "${digits.first()}.${digits.drop(1)}"
        val exponentSign = if (exponent < 0) '-' else '+'
        val exponentDigits = kotlin.math.abs(exponent).toString().padStart(2, '0')
        val numberSign = if (rounded.signum() < 0) "-" else ""
        return "$numberSign$mantissa" + "e$exponentSign$exponentDigits"
    }

    /** Swift Decimal/NSDecimalNumber drops authored scale before JSONSerialization writes it. */
    private fun foundationDecimalNumber(value: BigDecimal): kotlin.String =
        if (value.signum() == 0) "0" else value.stripTrailingZeros().toPlainString()

    private fun BigInteger.toLongExactOrNull(): Long? =
        takeIf { it >= MIN_SIGNED_64 && it <= MAX_SIGNED_64 }?.toLong()

    private val MIN_SIGNED_64 = BigInteger.valueOf(Long.MIN_VALUE)
    private val MAX_SIGNED_64 = BigInteger.valueOf(Long.MAX_VALUE)
    private val FOUNDATION_DOUBLE_CONTEXT = MathContext(17, RoundingMode.HALF_EVEN)
    private val STRICT_NUMBER = Regex("-?(0|[1-9][0-9]*)(\\.[0-9]+)?([eE][+-]?[0-9]+)?")
}

/** A named action requested by an Experience for the host app to perform. */
data class AppAction(
    /** The designer-authored action name. */
    val name: String,
    /** The fully resolved action payload, when one was authored. */
    val payload: Map<String, AppActionValue>?,
    /** The Experience and Journey that requested the action. */
    val experience: ExperienceRef,
)
