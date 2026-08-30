package ai.nuxie.sdk.commerce

import ai.nuxie.sdk.NuxieEnvironment
import android.util.Base64
import java.math.BigDecimal
import java.nio.file.Files
import java.security.KeyPairGenerator
import java.security.Signature
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
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
                syncAttributionDistinctId = "customer-1",
                ownerDistinctId = "customer-1",
                context = StoredPurchaseContext(
                    "primary",
                    "experience-1",
                    "v1",
                    BigDecimal("9.990000"),
                    "€9.99",
                ),
                acknowledged = false,
                syncAttempts = 1,
                completionAttempts = 2,
                firstSeenMillis = 123L,
                acceptedResponseBody = Json.parseToJsonElement(
                    """{"success":true,"features":[{"id":"pro","type":"boolean","allowed":true}]}""",
                ).jsonObject,
                signatureVerificationRequired = true,
                signatureVerified = true,
                authorityScope = "authority-scope",
                revoked = true,
                backendSyncedAtMillis = 456L,
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
    fun authorityScopedDirectoryDoesNotReplayEvidenceAcrossEnvironments() {
        val filesDirectory = Files.createTempDirectory("nuxie-commerce-authority").toFile()
        val apiKey = "pk_secret_authority"
        try {
            val development = purchaseEvidenceDirectory(
                filesDirectory,
                apiKey,
                NuxieEnvironment.DEVELOPMENT,
            )
            val production = purchaseEvidenceDirectory(
                filesDirectory,
                apiKey,
                NuxieEnvironment.PRODUCTION,
            )
            val evidence = PurchaseEvidence(
                purchaseToken = "token-1",
                packageName = "com.example.app",
                storeProductIds = listOf("pro"),
                purchaseState = StoredPurchaseState.PURCHASED,
                syncAttributionDistinctId = "customer-1",
                ownerDistinctId = "customer-1",
                acknowledged = false,
                firstSeenMillis = 123L,
            )

            assertTrue(FilePurchaseEvidenceStore(development).upsert(evidence))

            assertTrue(FilePurchaseEvidenceStore(production).load().isEmpty())
            assertFalse(development.absolutePath.contains(apiKey))
            assertFalse(production.absolutePath.contains(apiKey))
            assertTrue(development != production)
        } finally {
            filesDirectory.deleteRecursively()
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
                context = StoredPurchaseContext(
                    "primary",
                    "experience-1",
                    "v1",
                    BigDecimal("9.990000"),
                    "€9.99",
                ),
                featureAllowances = listOf(StoredFeatureAllowance("pro", "BOOLEAN", false)),
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
                context = StoredPurchaseContext(
                    "primary",
                    "experience-1",
                    "v1",
                    BigDecimal("9.990000"),
                    "€9.99",
                ),
                featureAllowances = listOf(
                    StoredFeatureAllowance("exports", "METERED", false, allowance = 3.5),
                ),
                licensingPublicKey = "public-key",
            )

            assertTrue(FilePurchaseEvidenceStore(directory).upsertProductMapping(mapping))

            assertEquals(mapping, FilePurchaseEvidenceStore(directory).loadProductMappings().single())
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun fileStoreRetainsBindingsAndMappingsForEveryFullProductIdentity() {
        val directory = Files.createTempDirectory("nuxie-product-identity").toFile()
        try {
            val store = FilePurchaseEvidenceStore(directory)
            val annualBinding = StoredPurchaseBinding(
                obfuscatedAccountId = "account-hash",
                distinctId = "customer-1",
                storeProductId = "play-pro",
                nuxieProductId = "pro-annual",
                basePlanId = "annual",
                offerId = "launch",
                productType = "subs",
                consumable = false,
                featureAllowances = listOf(StoredFeatureAllowance("annual-feature", "BOOLEAN", false)),
                nuxieManaged = true,
            )
            val monthlyBinding = annualBinding.copy(
                nuxieProductId = "pro-monthly",
                basePlanId = "monthly",
                offerId = null,
                featureAllowances = listOf(StoredFeatureAllowance("monthly-feature", "BOOLEAN", false)),
            )
            val annualMapping = StoredProductMapping(
                storeProductId = annualBinding.storeProductId,
                nuxieProductId = annualBinding.nuxieProductId,
                basePlanId = annualBinding.basePlanId,
                offerId = annualBinding.offerId,
                productType = annualBinding.productType,
                consumable = false,
                featureAllowances = annualBinding.featureAllowances,
            )
            val monthlyMapping = annualMapping.copy(
                nuxieProductId = monthlyBinding.nuxieProductId,
                basePlanId = monthlyBinding.basePlanId,
                offerId = monthlyBinding.offerId,
                featureAllowances = monthlyBinding.featureAllowances,
            )

            assertTrue(store.upsertBinding(annualBinding))
            assertTrue(store.upsertBinding(monthlyBinding))
            assertTrue(store.upsertProductMapping(annualMapping))
            assertTrue(store.upsertProductMapping(monthlyMapping))

            assertEquals(
                setOf("pro-annual", "pro-monthly"),
                FilePurchaseEvidenceStore(directory).loadBindings().mapTo(mutableSetOf()) {
                    it.nuxieProductId
                },
            )
            assertEquals(
                setOf("pro-annual", "pro-monthly"),
                FilePurchaseEvidenceStore(directory).loadProductMappings().mapTo(mutableSetOf()) {
                    it.nuxieProductId
                },
            )
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun fileStoreIgnoresRetiredAllowanceKeys() {
        // Pre-GA hard cut: the retired localFeatureGrants key is not migrated;
        // a mapping carrying only the old key decodes with no allowances.
        val directory = Files.createTempDirectory("nuxie-product-mapping-retired").toFile()
        try {
            directory.resolve("purchase-catalog.json").writeText(
                """[{"storeProductId":"play-pro","nuxieProductId":"pro","productType":"inapp","consumable":false,"localFeatureGrants":[{"featureId":"exports","type":"METERED","unlimited":false,"allowance":3.5}]}]""",
            )

            assertEquals(
                emptyList<StoredFeatureAllowance>(),
                FilePurchaseEvidenceStore(directory).loadProductMappings().single().featureAllowances,
            )
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
