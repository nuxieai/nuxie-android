package ai.nuxie.sdk.commerce

import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.ProductDetails
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class ProductResolverTest {
    @Test
    fun noneSelectsTheConfiguredBasePlan() = runBlocking {
        val query = FakeProductDetailsQuery(
            products = listOf(
                PlayProductDetails(
                    productId = "play-pro",
                    productType = BillingClient.ProductType.SUBS,
                    rawProduct = null,
                    subscriptionOffers = listOf(
                        offer(basePlanId = "monthly", offerId = null, token = "monthly-base"),
                        offer(basePlanId = "annual", offerId = null, token = "annual-base"),
                    ),
                ),
            ),
        )
        val resolver = ProductResolver(query)

        val product = resolver.resolve(
            listOf(
                CatalogProductRequest(
                    productId = "nuxie-pro",
                    storeProductId = "play-pro",
                    productType = BillingClient.ProductType.SUBS,
                    basePlanId = "annual",
                    offerSelection = OfferSelection.None,
                    placementId = "primary",
                ),
            ),
        ).single()

        assertEquals("nuxie-pro", product.productId)
        assertEquals("play-pro", product.storeProductId)
        assertEquals("annual", product.basePlanId)
        assertNull(product.offerId)
        assertEquals("primary", product.placementId)
        assertEquals("annual-base", product.offerToken)
    }

    @Test
    fun exactSelectsOnlyTheConfiguredOfferInsideTheConfiguredBasePlan() = runBlocking {
        val resolver = resolverWith(
            offer(basePlanId = "annual", offerId = null, token = "annual-base"),
            offer(basePlanId = "monthly", offerId = "launch", token = "wrong-plan"),
            offer(basePlanId = "annual", offerId = "launch", token = "annual-launch"),
        )

        val product = resolver.resolve(
            listOf(request(OfferSelection.Exact("launch"))),
        ).single()

        assertEquals("annual", product.basePlanId)
        assertEquals("launch", product.offerId)
        assertEquals("annual-launch", product.offerToken)
    }

    @Test
    fun exactMissingFallsBackToTheConfiguredBasePlan() = runBlocking {
        val product = resolverWith(
            offer(basePlanId = "annual", offerId = null, token = "annual-base"),
            offer(basePlanId = "annual", offerId = "other", token = "other-token"),
        ).resolve(listOf(request(OfferSelection.Exact("missing")))).single()

        assertNull(product.offerId)
        assertEquals("annual-base", product.offerToken)
    }

    @Test
    fun exactMultiIntroPhaseOfferDowngradesToBasePlan() = runBlocking {
        val product = resolverWith(
            offer(basePlanId = "annual", offerId = null, token = "annual-base"),
            offer(
                basePlanId = "annual",
                offerId = "complex",
                token = "complex-token",
                introductoryPhases = listOf(
                    phase(priceMicros = 0, period = "P1W", cycles = 1),
                    phase(priceMicros = 990_000, period = "P1M", cycles = 2),
                ),
            ),
        ).resolve(listOf(request(OfferSelection.Exact("complex")))).single()

        assertNull(product.offerId)
        assertEquals("annual-base", product.offerToken)
    }

    @Test
    fun automaticSelectsTheLongestFreeTrial() = runBlocking {
        val product = resolverWith(
            offer(basePlanId = "annual", offerId = null, token = "annual-base"),
            offer(
                basePlanId = "annual",
                offerId = "one-week",
                token = "one-week-token",
                introductoryPhases = listOf(phase(priceMicros = 0, period = "P1W", cycles = 1)),
            ),
            offer(
                basePlanId = "annual",
                offerId = "two-weeks",
                token = "two-weeks-token",
                introductoryPhases = listOf(phase(priceMicros = 0, period = "P1W", cycles = 2)),
            ),
            offer(
                basePlanId = "annual",
                offerId = "cheap",
                token = "cheap-token",
                introductoryPhases = listOf(phase(priceMicros = 1, period = "P1M", cycles = 1)),
            ),
        ).resolve(listOf(request(OfferSelection.Automatic))).single()

        assertEquals("two-weeks", product.offerId)
        assertEquals("two-weeks-token", product.offerToken)
    }

    @Test
    fun automaticSelectsTheLowestFirstIntroductoryPriceWhenThereIsNoTrial() = runBlocking {
        val product = resolverWith(
            offer(basePlanId = "annual", offerId = null, token = "annual-base"),
            offer(
                basePlanId = "annual",
                offerId = "five-dollars",
                token = "five-token",
                introductoryPhases = listOf(phase(5_000_000, "P1M", 1)),
            ),
            offer(
                basePlanId = "annual",
                offerId = "one-dollar",
                token = "one-token",
                introductoryPhases = listOf(phase(1_000_000, "P1M", 1)),
            ),
        ).resolve(listOf(request(OfferSelection.Automatic))).single()

        assertEquals("one-dollar", product.offerId)
        assertEquals("one-token", product.offerToken)
    }

    @Test
    fun automaticExcludesIgnoreTaggedOffers() = runBlocking {
        val product = resolverWith(
            offer(basePlanId = "annual", offerId = null, token = "annual-base"),
            offer(
                basePlanId = "annual",
                offerId = "ignored-long-trial",
                token = "ignored-token",
                tags = listOf("nuxie-ignore-offer"),
                introductoryPhases = listOf(phase(0, "P1M", 3)),
            ),
            offer(
                basePlanId = "annual",
                offerId = "available-trial",
                token = "available-token",
                introductoryPhases = listOf(phase(0, "P1W", 1)),
            ),
        ).resolve(listOf(request(OfferSelection.Automatic))).single()

        assertEquals("available-trial", product.offerId)
        assertEquals("available-token", product.offerToken)
    }

    @Test
    fun automaticIgnoresMultiIntroPhaseOffers() = runBlocking {
        val product = resolverWith(
            offer(basePlanId = "annual", offerId = null, token = "annual-base"),
            offer(
                basePlanId = "annual",
                offerId = "complex",
                token = "complex-token",
                introductoryPhases = listOf(
                    phase(0, "P1M", 3),
                    phase(1_000_000, "P1M", 2),
                ),
            ),
            offer(
                basePlanId = "annual",
                offerId = "simple",
                token = "simple-token",
                introductoryPhases = listOf(phase(2_000_000, "P1M", 1)),
            ),
        ).resolve(listOf(request(OfferSelection.Automatic))).single()

        assertEquals("simple", product.offerId)
        assertEquals("simple-token", product.offerToken)
    }

    @Test
    fun automaticFallsBackToBasePlanWhenNoOfferIsEligible() = runBlocking {
        val product = resolverWith(
            offer(basePlanId = "annual", offerId = null, token = "annual-base"),
            offer(
                basePlanId = "annual",
                offerId = "ignored",
                token = "ignored-token",
                tags = listOf("nuxie-ignore-offer"),
                introductoryPhases = listOf(phase(0, "P1M", 1)),
            ),
        ).resolve(listOf(request(OfferSelection.Automatic))).single()

        assertNull(product.offerId)
        assertEquals("annual-base", product.offerToken)
    }

    @Test
    fun selectedPlanOfferTokenAndPersonalizationStayTogether() = runBlocking {
        val rawProduct = nativeProductDetailsIdentity()
        val resolver = ProductResolver(
            FakeProductDetailsQuery(
                products = listOf(
                    PlayProductDetails(
                        productId = "play-pro",
                        productType = BillingClient.ProductType.SUBS,
                        rawProduct = rawProduct,
                        subscriptionOffers = listOf(
                            offer(basePlanId = "monthly", offerId = null, token = "monthly-base"),
                            offer(basePlanId = "monthly", offerId = "launch", token = "monthly-launch"),
                            offer(basePlanId = "annual", offerId = null, token = "annual-base"),
                            offer(basePlanId = "annual", offerId = "launch", token = "annual-launch"),
                        ),
                    ),
                ),
            ),
        )

        val product = resolver.resolve(
            listOf(
                request(OfferSelection.Exact("launch")).copy(isOfferPersonalized = true),
            ),
        ).single()

        assertEquals("annual", product.basePlanId)
        assertEquals("launch", product.offerId)
        assertEquals("annual-launch", product.offerToken)
        assertEquals(true, product.isOfferPersonalized)
        assertSame(rawProduct, product.rawProduct)
    }

    @Test
    fun subscriptionWithoutExplicitBasePlanDoesNotChooseTheFirstPlan() = runBlocking {
        val products = resolverWith(
            offer(basePlanId = "monthly", offerId = null, token = "monthly-base"),
        ).resolve(
            listOf(request(OfferSelection.None).copy(basePlanId = null)),
        )

        assertEquals(emptyList<StoreProduct>(), products)
    }

    @Test
    fun oneTimeProductKeepsTheNativeOfferToken() = runBlocking {
        val resolver = ProductResolver(
            FakeProductDetailsQuery(
                listOf(
                    PlayProductDetails(
                        productId = "lifetime",
                        productType = BillingClient.ProductType.INAPP,
                        rawProduct = null,
                        subscriptionOffers = emptyList(),
                        oneTimePurchaseOfferToken = "one-time-token",
                    ),
                ),
            ),
        )

        val product = resolver.resolve(
            listOf(
                CatalogProductRequest(
                    productId = "nuxie-lifetime",
                    storeProductId = "lifetime",
                    productType = BillingClient.ProductType.INAPP,
                ),
            ),
        ).single()

        assertNull(product.basePlanId)
        assertNull(product.offerId)
        assertEquals("one-time-token", product.offerToken)
    }

    private fun resolverWith(vararg offers: PlaySubscriptionOffer): ProductResolver =
        ProductResolver(
            FakeProductDetailsQuery(
                products = listOf(
                    PlayProductDetails(
                        productId = "play-pro",
                        productType = BillingClient.ProductType.SUBS,
                        rawProduct = null,
                        subscriptionOffers = offers.toList(),
                    ),
                ),
            ),
        )

    private fun request(selection: OfferSelection) = CatalogProductRequest(
        productId = "nuxie-pro",
        storeProductId = "play-pro",
        productType = BillingClient.ProductType.SUBS,
        basePlanId = "annual",
        offerSelection = selection,
        placementId = "primary",
    )

    private fun offer(
        basePlanId: String,
        offerId: String?,
        token: String,
        tags: List<String> = emptyList(),
        introductoryPhases: List<PlayPricingPhase> = emptyList(),
    ) = PlaySubscriptionOffer(
        basePlanId = basePlanId,
        offerId = offerId,
        offerToken = token,
        offerTags = tags,
        pricingPhases = introductoryPhases +
            PlayPricingPhase(
                priceAmountMicros = 9_990_000,
                billingPeriod = "P1M",
                billingCycleCount = 0,
                recurrenceMode = PlayRecurrenceMode.INFINITE,
            ),
    )

    private fun phase(
        priceMicros: Long,
        period: String,
        cycles: Int,
    ) = PlayPricingPhase(
        priceAmountMicros = priceMicros,
        billingPeriod = period,
        billingCycleCount = cycles,
        recurrenceMode = PlayRecurrenceMode.FINITE,
    )

    private fun nativeProductDetailsIdentity(): ProductDetails {
        val unsafeClass = Class.forName("sun.misc.Unsafe")
        val singleton = unsafeClass.getDeclaredField("theUnsafe").apply {
            isAccessible = true
        }.get(null)
        return unsafeClass.getMethod("allocateInstance", Class::class.java)
            .invoke(singleton, ProductDetails::class.java) as ProductDetails
    }

    private class FakeProductDetailsQuery(
        private val products: List<PlayProductDetails>,
    ) : ProductDetailsQuery {
        override suspend fun query(products: List<ProductQuery>): ProductDetailsQueryResult =
            ProductDetailsQueryResult.Success(this.products)
    }
}
