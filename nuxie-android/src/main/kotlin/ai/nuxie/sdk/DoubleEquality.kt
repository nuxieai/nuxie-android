package ai.nuxie.sdk

/** Swift field equality for optional Double values: signed zero is equal and NaN is unequal. */
internal fun kotlin.Double?.hasSameDoubleValueAs(other: kotlin.Double?): Boolean {
    if (this == null || other == null) return this == null && other == null
    return toDouble() == other.toDouble()
}

/** Hash compatible with [hasSameDoubleValueAs], including normalized signed zero. */
internal fun kotlin.Double.doubleValueHashCode(): Int = if (this == 0.0) 0 else hashCode()
