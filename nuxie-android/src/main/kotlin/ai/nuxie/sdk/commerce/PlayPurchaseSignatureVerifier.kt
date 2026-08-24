package ai.nuxie.sdk.commerce

import android.util.Base64
import java.security.KeyFactory
import java.security.Signature
import java.security.spec.X509EncodedKeySpec

/** Optional Play licensing-key verification; callers invoke it only when catalog authority exists. */
internal object PlayPurchaseSignatureVerifier {
    fun verify(publicKey: String, originalJson: String, signature: String): Boolean = runCatching {
        val decodedKey = Base64.decode(publicKey, Base64.DEFAULT)
        val key = KeyFactory.getInstance("RSA").generatePublic(X509EncodedKeySpec(decodedKey))
        Signature.getInstance("SHA1withRSA").run {
            initVerify(key)
            update(originalJson.encodeToByteArray())
            verify(Base64.decode(signature, Base64.DEFAULT))
        }
    }.getOrDefault(false)
}
