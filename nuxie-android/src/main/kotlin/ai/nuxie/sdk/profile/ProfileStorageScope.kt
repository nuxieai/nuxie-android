package ai.nuxie.sdk.profile

import ai.nuxie.sdk.NuxieEnvironment
import java.io.File
import java.security.MessageDigest

/**
 * Opaque profile-cache namespace for one configured credential and endpoint.
 * The credential scopes bootstrap data only; durable device runs use the
 * transport-authenticated app/environment authority instead.
 */
internal class ProfileStorageScope(apiKey: String, environment: NuxieEnvironment) {
    private val digest = MessageDigest.getInstance("SHA-256")
        .digest("$DOMAIN${environment.name}\u0000$apiKey".encodeToByteArray())
        .joinToString("") { "%02x".format(it) }

    val cacheSubdirectory: String = "profiles-v2-$digest"
    val authorityBindingFilename: String = "$digest.json"

    fun cacheDirectory(cacheDirectory: File): File =
        File(File(cacheDirectory, "nuxie"), cacheSubdirectory)

    private companion object {
        const val DOMAIN = "nuxie.profile-storage.v2\u0000"
    }
}
