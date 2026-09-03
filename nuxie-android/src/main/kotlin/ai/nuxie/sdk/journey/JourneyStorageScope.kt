package ai.nuxie.sdk.journey

import ai.nuxie.sdk.network.ProfileDeliveryAuthority
import java.security.MessageDigest

/** Durable namespace for one transport-authenticated app environment. */
internal class JourneyStorageScope private constructor(
    private val namespaceHash: String,
) {
    constructor(authority: ProfileDeliveryAuthority) : this(
        digest("$DOMAIN${authority.environment}\u0000${authority.appId}"),
    ) {
        require(authority.isValid) { "Invalid journey storage authority" }
    }

    fun customerDigest(distinctId: String): String =
        digest("$DOMAIN$namespaceHash\u0000$distinctId")

    override fun equals(other: Any?): Boolean =
        other is JourneyStorageScope && namespaceHash == other.namespaceHash

    override fun hashCode(): Int = namespaceHash.hashCode()

    companion object {
        private const val DOMAIN = "nuxie.journey-storage.v1\u0000"
        val testFixture = JourneyStorageScope("test-fixture")

        private fun digest(value: String): String =
            MessageDigest.getInstance("SHA-256")
                .digest(value.encodeToByteArray())
                .joinToString("") { "%02x".format(it) }
    }
}
