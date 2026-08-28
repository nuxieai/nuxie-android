package ai.nuxie.sdk

import java.math.BigDecimal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class PurchaseInfoEqualityTest {
    @Test
    fun equalityAndHashCodeUseAllFieldsWithIosDecimalSemantics() {
        val info = purchaseInfo()
        val equalInfo = purchaseInfo(price = BigDecimal("9.990"))

        assertEquals(info, equalInfo)
        assertEquals(info.hashCode(), equalInfo.hashCode())

        listOf(
            purchaseInfo(productId = "product-2"),
            purchaseInfo(storeProductId = "store-product-2"),
            purchaseInfo(placementId = "placement-2"),
            purchaseInfo(experience = ExperienceRef("experience-2", "version-1", "journey-1")),
            purchaseInfo(price = BigDecimal("10.00")),
            purchaseInfo(displayPrice = "$10.00"),
            purchaseInfo(transactionId = "transaction-2"),
            purchaseInfo(isTestStore = false),
        ).forEach { different -> assertNotEquals(info, different) }

        val negativeZero = purchaseInfo(price = BigDecimal("-0.00"))
        val positiveZero = purchaseInfo(price = BigDecimal("0.0"))
        assertEquals(negativeZero, positiveZero)
        assertEquals(negativeZero.hashCode(), positiveZero.hashCode())
    }

    private fun purchaseInfo(
        productId: String? = "product-1",
        storeProductId: String? = "store-product-1",
        placementId: String? = "placement-1",
        experience: ExperienceRef? = ExperienceRef("experience-1", "version-1", "journey-1"),
        price: BigDecimal? = BigDecimal("9.99"),
        displayPrice: String? = "$9.99",
        transactionId: String? = "transaction-1",
        isTestStore: Boolean = true,
    ) = PurchaseInfo(
        productId = productId,
        storeProductId = storeProductId,
        placementId = placementId,
        experience = experience,
        price = price,
        displayPrice = displayPrice,
        transactionId = transactionId,
        isTestStore = isTestStore,
    )
}
