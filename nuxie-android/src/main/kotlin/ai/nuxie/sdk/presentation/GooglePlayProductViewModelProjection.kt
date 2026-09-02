package ai.nuxie.sdk.presentation

import ai.nuxie.sdk.billing.StoreProduct
import ai.nuxie.sdk.runtime.NuxieViewModelListProjection
import ai.nuxie.sdk.runtime.NuxieViewModelScalarValue
import android.icu.text.MeasureFormat
import android.icu.util.Measure
import android.icu.util.MeasureUnit
import android.os.Build
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.ProductDetails
import java.util.Locale
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull

/**
 * Replaces publish-time catalog previews with the exact ProductDetails that
 * checkout retained for this presentation. The signed descriptor remains the
 * authority for which products, instances, and scalar paths may exist.
 */
internal object GooglePlayProductViewModelProjection {
    fun prepare(
        descriptor: JsonObject,
        products: List<StoreProduct>,
        screenId: String?,
        locale: Locale = Locale.getDefault(),
    ): NuxieViewModelListProjection? {
        val values = ((descriptor["journey"] as? JsonObject)?.get("viewModelValues") as? JsonArray)
            ?.mapIndexed { index, element -> parseValue(element, index) }
            .orEmpty()
        val productGroups = values
            .filter { it.viewModelName == PRODUCT_VIEW_MODEL }
            .groupBy { value ->
                value.instanceId ?: invalid("PaywallProduct value has no instanceId")
            }
        if (productGroups.isEmpty()) return null

        val hasVisibleClaim = productGroups.values.any { group ->
            group.any { it.path in COMMERCE_VALUE_PATHS && it.value.isVisibleClaim() }
        }
        // A text-only CTA can render without a commerce graph. Whenever the
        // exact products are available, still project them so blank authored
        // placeholders never become the production source of truth.
        if (!hasVisibleClaim && products.isEmpty()) return null

        val rootViewModelName = rootViewModelName(descriptor, screenId)
            ?: invalid("Paywall commerce claims have no default root view model")
        val productsByPlacement = products.groupBy(StoreProduct::placementId)
        val projected = productGroups.values.map { group ->
            val placementId = group.requiredString("placementId")
            val resolved = productsByPlacement[placementId]
                ?.singleOrNull()
                ?: invalid("No unique live Play ProductDetails for Placement '$placementId'")
            val facts = resolved.liveFacts(locale)
            val authoredName = group.mapNotNull(Value::instanceName).distinct().singleOrNull()
                ?: invalid("Paywall product '$placementId' has no unique authored instance name")
            val listIndex = group.requiredNumber("list_index").toIntExact("list_index")
            val declaredPaths = group.mapTo(linkedSetOf(), Value::path)
            val liveValues = facts.filterKeys { it in declaredPaths }.toMutableMap()
            if ("placementId" in declaredPaths) {
                liveValues["placementId"] = string(placementId)
            }
            val missingClaims = declaredPaths
                .filter { it in COMMERCE_VALUE_PATHS && group.value(it).isVisibleClaim() }
                .filterNot(liveValues::containsKey)
            if (missingClaims.isNotEmpty()) {
                invalid(
                    "Live Play ProductDetails cannot resolve signed commerce values " +
                        missingClaims.joinToString(),
                )
            }
            NuxieViewModelListProjection.Item(
                authoredInstanceName = authoredName,
                listIndex = listIndex,
                selected = group.optionalBoolean("isSelected") == true,
                values = liveValues,
            )
        }.sortedBy(NuxieViewModelListProjection.Item::listIndex)

        return NuxieViewModelListProjection(
            rootSchemaName = rootViewModelName,
            listPath = PRODUCT_LIST_PATH,
            selectedItemPath = SELECTED_PRODUCT_PATH,
            itemSchemaName = PRODUCT_VIEW_MODEL,
            items = projected,
        )
    }

    private fun StoreProduct.liveFacts(
        locale: Locale,
    ): Map<String, NuxieViewModelScalarValue> {
        val details = rawProduct ?: invalid(
            "Play returned no ProductDetails for Placement '${placementId.orEmpty()}'",
        )
        val facts = when (productType) {
            BillingClient.ProductType.INAPP -> oneTimeFacts(details)
            BillingClient.ProductType.SUBS -> subscriptionFacts(details, locale)
            else -> invalid("Unsupported Play Product type '$productType'")
        }.toMutableMap()
        facts["name"] = string(details.name)
        facts["description"] = string(details.description)
        return facts
    }

    private fun StoreProduct.oneTimeFacts(
        details: ProductDetails,
    ): Map<String, NuxieViewModelScalarValue> {
        val offer = if (offerToken == null) {
            details.oneTimePurchaseOfferDetails
        } else {
            details.oneTimePurchaseOfferDetailsList
                ?.singleOrNull { it.offerToken == offerToken }
        } ?: invalid("Exact Play one-time purchase option is unavailable")
        return commerceFacts(
            price = offer.formattedPrice,
            period = PeriodFacts("lifetime", 1, ""),
            intro = null,
            trial = null,
            renews = false,
        )
    }

    private fun StoreProduct.subscriptionFacts(
        details: ProductDetails,
        locale: Locale,
    ): Map<String, NuxieViewModelScalarValue> {
        val offer = details.subscriptionOfferDetails
            ?.singleOrNull { it.offerToken == offerToken }
            ?: invalid("Exact Play base plan or offer is unavailable")
        if (offer.basePlanId != basePlanId || offer.offerId != offerId) {
            invalid("Play ProductDetails no longer match the selected base plan and offer")
        }
        val phases = offer.pricingPhases.pricingPhaseList
        val renewal = phases.lastOrNull()
            ?.takeIf {
                it.recurrenceMode == ProductDetails.RecurrenceMode.INFINITE_RECURRING &&
                    phases.dropLast(1).none { phase ->
                        phase.recurrenceMode == ProductDetails.RecurrenceMode.INFINITE_RECURRING
                    }
            }
            ?: invalid("Play subscription pricing phases are not in billing order")
        // Billing Library guarantees pricingPhaseList is time ordered. Keep
        // that order so a free trial followed by a paid introductory phase is
        // projected as two truthful facts rather than rejected or collapsed.
        val introductory = phases.dropLast(1).map { it.introductoryFacts(locale) }
        val trials = introductory.filter(IntroductoryFacts::free)
        val paidIntroductory = introductory.filterNot(IntroductoryFacts::free)
        if (trials.size > 1 || paidIntroductory.size > 1) {
            invalid("Play offer has unsupported repeated introductory pricing phases")
        }
        val trial = trials.singleOrNull()
        val intro = paidIntroductory.singleOrNull() ?: trial
        return commerceFacts(
            price = renewal.formattedPrice,
            period = parsePeriod(renewal.billingPeriod, locale),
            intro = intro,
            trial = trial,
            renews = true,
        )
    }

    private fun ProductDetails.PricingPhase.introductoryFacts(
        locale: Locale,
    ): IntroductoryFacts {
        val introductoryPeriod = parsePeriod(billingPeriod, locale)
        val cycles = billingCycleCount.coerceAtLeast(1)
        return IntroductoryFacts(
            price = formattedPrice,
            period = introductoryPeriod,
            cycles = cycles,
            paymentMode = when (recurrenceMode) {
                ProductDetails.RecurrenceMode.FINITE_RECURRING -> {
                    if (priceAmountMicros == 0L) "freeTrial" else "payAsYouGo"
                }
                ProductDetails.RecurrenceMode.NON_RECURRING -> "payUpFront"
                else -> invalid("Recurring phase cannot be an introductory offer")
            },
            free = priceAmountMicros == 0L,
            totalPeriodLabel = formatPeriod(
                introductoryPeriod.count * cycles,
                PeriodUnit(introductoryPeriod.unit),
                locale,
            ),
        )
    }

    private fun commerceFacts(
        price: String,
        period: PeriodFacts?,
        intro: IntroductoryFacts?,
        trial: IntroductoryFacts?,
        renews: Boolean,
    ): Map<String, NuxieViewModelScalarValue> {
        val hasIntro = intro != null
        val hasFreeTrial = trial != null
        val trialText = trial?.totalPeriodLabel.orEmpty()
        val introText = when {
            intro == null -> ""
            intro.free -> intro.totalPeriodLabel
            intro.paymentMode == "payUpFront" -> "${intro.price} for ${intro.totalPeriodLabel}"
            else -> "${intro.price}/${intro.period.label} for ${intro.totalPeriodLabel}"
        }
        val renewalPeriod = period?.label.orEmpty()
        return linkedMapOf(
            "price" to string(price),
            "period" to string(period?.unit.orEmpty()),
            "periodCount" to number(period?.count ?: 0),
            "periodLabel" to string(renewalPeriod),
            "hasTrial" to boolean(hasFreeTrial),
            "trialLabel" to string(if (hasFreeTrial) trialText else ""),
            "introOfferLabel" to string(introText),
            "renewalLabel" to string(
                if (!renews) "" else if (renewalPeriod.isEmpty()) price else "$price/$renewalPeriod",
            ),
            "renewalPrice" to string(if (renews) price else ""),
            "renewalPeriod" to string(if (renews) renewalPeriod else ""),
            "hasIntroductoryOffer" to boolean(hasIntro),
            "hasFreeTrial" to boolean(hasFreeTrial),
            "introductoryPrice" to string(intro?.price.orEmpty()),
            "introductoryPeriod" to string(intro?.period?.unit.orEmpty()),
            "introductoryPeriodCount" to number(intro?.period?.count ?: 0),
            "introductoryCycles" to number(intro?.cycles ?: 0),
            "introductoryPaymentMode" to string(intro?.paymentMode.orEmpty()),
            "trialPeriodText" to string(if (hasFreeTrial) trialText else ""),
        )
    }

    private fun parsePeriod(raw: String, locale: Locale): PeriodFacts {
        val match = PERIOD_REGEX.matchEntire(raw)
            ?: invalid("Unsupported Play billing period '$raw'")
        val count = match.groupValues[1].toIntOrNull()
            ?: invalid("Unsupported Play billing period '$raw'")
        val unit = when (match.groupValues[2]) {
            "D" -> PeriodUnit("day")
            "W" -> PeriodUnit("week")
            "M" -> PeriodUnit("month")
            "Y" -> PeriodUnit("year")
            else -> invalid("Unsupported Play billing period '$raw'")
        }
        return PeriodFacts(unit.name, count, formatPeriod(count, unit, locale))
    }

    private fun formatPeriod(count: Int, unit: PeriodUnit, locale: Locale): String =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            Api24PeriodFormatter.format(count, unit.name, locale)
        } else {
            "$count ${unit.name}${if (count == 1) "" else "s"}"
        }

    private fun rootViewModelName(descriptor: JsonObject, screenId: String?): String? {
        val screens = ((descriptor["journey"] as? JsonObject)?.get("screens") as? JsonArray)
            ?.mapNotNull { it as? JsonObject }
            .orEmpty()
        val screen = screenId?.let { id ->
            screens.singleOrNull { it.string("id") == id }
        } ?: screens.firstOrNull()
        return screen?.string("defaultViewModelName")
    }

    private fun parseValue(element: JsonElement, index: Int): Value {
        val raw = element as? JsonObject ?: invalid("View-model value[$index] is not an object")
        return Value(
            viewModelName = raw.string("viewModelName")
                ?: invalid("View-model value[$index] has no viewModelName"),
            instanceId = raw.string("instanceId"),
            instanceName = raw.string("instanceName"),
            path = raw.string("path") ?: invalid("View-model value[$index] has no path"),
            value = raw["value"],
        )
    }

    private fun List<Value>.requiredString(path: String): String =
        (value(path) as? JsonPrimitive)
            ?.takeIf(JsonPrimitive::isString)
            ?.content
            ?.takeIf(String::isNotBlank)
            ?: invalid("PaywallProduct has no '$path'")

    private fun List<Value>.requiredNumber(path: String): Double =
        (value(path) as? JsonPrimitive)?.doubleOrNull
            ?: invalid("PaywallProduct has no numeric '$path'")

    private fun List<Value>.optionalBoolean(path: String): Boolean? =
        (value(path) as? JsonPrimitive)?.booleanOrNull

    private fun List<Value>.value(path: String): JsonElement? {
        val matches = filter { it.path == path }
        if (matches.size > 1) invalid("PaywallProduct has duplicate '$path' values")
        return matches.singleOrNull()?.value
    }

    private fun JsonElement?.isVisibleClaim(): Boolean = when (this) {
        is JsonPrimitive -> when {
            isString -> content.isNotBlank() && content != "—"
            booleanOrNull != null -> booleanOrNull == true
            doubleOrNull != null -> doubleOrNull != 0.0
            else -> false
        }
        else -> false
    }

    private fun JsonObject.string(key: String): String? =
        (this[key] as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.content

    private fun Double.toIntExact(label: String): Int {
        if (!isFinite() || this < 0 || this > Int.MAX_VALUE || this % 1.0 != 0.0) {
            invalid("PaywallProduct '$label' is not a nonnegative integer")
        }
        return toInt()
    }

    private fun string(value: String) = NuxieViewModelScalarValue.StringValue(value)
    private fun number(value: Int) = NuxieViewModelScalarValue.NumberValue(value.toDouble())
    private fun boolean(value: Boolean) = NuxieViewModelScalarValue.BooleanValue(value)

    private fun invalid(message: String): Nothing = throw IllegalStateException(message)

    private data class Value(
        val viewModelName: String,
        val instanceId: String?,
        val instanceName: String?,
        val path: String,
        val value: JsonElement?,
    )

    private data class PeriodUnit(val name: String)
    private data class PeriodFacts(val unit: String, val count: Int, val label: String)
    private data class IntroductoryFacts(
        val price: String,
        val period: PeriodFacts,
        val cycles: Int,
        val paymentMode: String,
        val free: Boolean,
        val totalPeriodLabel: String,
    )

    private val PERIOD_REGEX = Regex("^P([1-9][0-9]*)([DWMY])$")
    private const val PRODUCT_VIEW_MODEL = "PaywallProduct"
    private const val PRODUCT_LIST_PATH = "paywall/products"
    private const val SELECTED_PRODUCT_PATH = "paywall/selectedProduct"
    private val COMMERCE_VALUE_PATHS = setOf(
        "price",
        "period",
        "periodCount",
        "periodLabel",
        "hasTrial",
        "trialLabel",
        "introOfferLabel",
        "renewalLabel",
        "renewalPrice",
        "renewalPeriod",
        "hasIntroductoryOffer",
        "hasFreeTrial",
        "introductoryPrice",
        "introductoryPeriod",
        "introductoryPeriodCount",
        "introductoryCycles",
        "introductoryPaymentMode",
        "trialPeriodText",
    )

    /** Kept behind a separate class-load boundary for the SDK's API 23 floor. */
    @android.annotation.TargetApi(Build.VERSION_CODES.N)
    private object Api24PeriodFormatter {
        fun format(count: Int, unitName: String, locale: Locale): String {
            val unit = when (unitName) {
                "day" -> MeasureUnit.DAY
                "week" -> MeasureUnit.WEEK
                "month" -> MeasureUnit.MONTH
                "year" -> MeasureUnit.YEAR
                else -> error("Unsupported period unit '$unitName'")
            }
            return MeasureFormat.getInstance(locale, MeasureFormat.FormatWidth.WIDE)
                .formatMeasures(Measure(count, unit))
        }
    }
}
