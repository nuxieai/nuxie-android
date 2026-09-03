package ai.nuxie.sdk.network

/** App authority established by authenticated profile response metadata. */
internal data class ProfileDeliveryAuthority(
    val appId: String,
    val environment: String,
) {
    val isValid: Boolean
        get() = valid(appId, maximumBytes = 256) &&
            valid(environment, maximumBytes = 16) &&
            environment in setOf("live", "test")

    private fun valid(value: String, maximumBytes: Int): Boolean =
        value.isNotEmpty() &&
            value == value.trim() &&
            value.encodeToByteArray().size <= maximumBytes &&
            value.none(Char::isISOControl)
}
