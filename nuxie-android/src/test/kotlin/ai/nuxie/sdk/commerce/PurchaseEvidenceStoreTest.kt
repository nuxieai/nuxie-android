package ai.nuxie.sdk.commerce

import android.util.Base64
import java.nio.file.Files
import java.security.KeyPairGenerator
import java.security.Signature
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class PurchaseEvidenceStoreTest {
    @Test
    fun fileStoreSurvivesAProcessStyleReconstruction() {
        val directory = Files.createTempDirectory("nuxie-purchase-evidence").toFile()
        try {
            val evidence = PurchaseEvidence(
                purchaseToken = "token-1",
                packageName = "com.example.app",
                storeProductIds = listOf("pro"),
                purchaseState = StoredPurchaseState.PURCHASED,
                distinctId = "customer-1",
                acknowledged = false,
                syncAttempts = 1,
                firstSeenMillis = 123L,
            )
            assertTrue(FilePurchaseEvidenceStore(directory).upsert(evidence))
            assertTrue(FilePurchaseEvidenceStore(directory).upsert(evidence.copy(acknowledged = true)))

            assertEquals(
                evidence.copy(acknowledged = true),
                FilePurchaseEvidenceStore(directory).load().getValue("token-1"),
            )
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun fileStoreRetainsCustomerScopedCatalogBindings() {
        val directory = Files.createTempDirectory("nuxie-purchase-bindings").toFile()
        try {
            val binding = StoredPurchaseBinding(
                obfuscatedAccountId = "account-hash",
                distinctId = "customer-1",
                storeProductId = "play-pro",
                nuxieProductId = "pro",
                basePlanId = "annual",
                offerId = "launch",
                productType = "subs",
                consumable = false,
                context = StoredPurchaseContext("primary", "experience-1", "v1"),
                localFeatureGrants = listOf(StoredLocalPurchaseGrant("pro", "BOOLEAN", false)),
                licensingPublicKey = "public-key",
                nuxieManaged = true,
            )

            assertTrue(FilePurchaseEvidenceStore(directory).upsertBinding(binding))

            assertEquals(binding, FilePurchaseEvidenceStore(directory).loadBindings().single())
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun fileStoreRetainsAppScopedProductMappings() {
        val directory = Files.createTempDirectory("nuxie-product-mappings").toFile()
        try {
            val mapping = StoredProductMapping(
                storeProductId = "play-pro",
                nuxieProductId = "pro",
                basePlanId = "annual",
                offerId = "launch",
                productType = "subs",
                consumable = false,
                context = StoredPurchaseContext("primary", "experience-1", "v1"),
                localFeatureGrants = listOf(StoredLocalPurchaseGrant("pro", "BOOLEAN", false)),
                licensingPublicKey = "public-key",
            )

            assertTrue(FilePurchaseEvidenceStore(directory).upsertProductMapping(mapping))

            assertEquals(mapping, FilePurchaseEvidenceStore(directory).loadProductMappings().single())
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun licensingKeyVerifierAcceptsOnlyMatchingPlayEvidence() {
        val pair = KeyPairGenerator.getInstance("RSA").apply { initialize(1024) }.generateKeyPair()
        val originalJson = """{"purchaseToken":"token-1"}"""
        val signature = Signature.getInstance("SHA1withRSA").run {
            initSign(pair.private)
            update(originalJson.encodeToByteArray())
            Base64.encodeToString(sign(), Base64.NO_WRAP)
        }
        val publicKey = Base64.encodeToString(pair.public.encoded, Base64.NO_WRAP)

        assertTrue(PlayPurchaseSignatureVerifier.verify(publicKey, originalJson, signature))
        assertFalse(PlayPurchaseSignatureVerifier.verify(publicKey, "$originalJson ", signature))
    }
}
