package ai.nuxie.sdk.experiences

import ai.nuxie.sdk.NuxieEnvironment
import android.util.Base64

/** Environment-scoped Journey release trust roots, identical to iOS. */
internal object JourneyTrustRoots {
    fun keys(environment: NuxieEnvironment): Map<String, ByteArray> = when (environment) {
        NuxieEnvironment.DEVELOPMENT -> mapOf(
            "TEST_ONLY_DEV_KEYPAIR" to decode("IVL40Zt5HSRFMkLhXy6rbLfP+ntqXtMAl5YOBpiB2xI="),
        )
        NuxieEnvironment.PRODUCTION -> mapOf(
            "nuxie-experience-2026-07" to decode("tcoCFOAFJLj7A5LJ+T/jWfnvpgvmP7vhDoaHZitBpiY="),
        )
    }

    private fun decode(value: String): ByteArray = Base64.decode(value, Base64.NO_WRAP)
        .takeIf { it.size == 32 } ?: error("Malformed Nuxie Journey trust root")
}
