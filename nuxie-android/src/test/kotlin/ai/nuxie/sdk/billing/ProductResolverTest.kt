package ai.nuxie.sdk.billing

import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.ProductDetails
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class ProductResolverTest {
    @Test
    fun resolutionCachesTheSignedCatalogMappingForLaterRecovery() = runBlocking {
        val store = InMemoryPurchaseEvidenceStore()
        val resolver = ProductResolver(
            FakeProductDetailsQuery(
                products = listOf(
                    PlayProductDetails(
                        productId = "play-pro",
                        productType = BillingClient.ProductType.SUBS,
                        rawProduct = null,
                        subscriptionOffers = listOf(
                            offer(basePlanId = "annual", offerId = null, token = "annual-base"),
                        ),
                    ),
                ),
            ),
            store,
        )

        resolver.resolve(listOf(request(OfferSelection.None)))

        assertEquals("nuxie-pro", store.loadProductMappings().single().nuxieProductId)
    }

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
        val resolver = resolver(query)

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
        val resolver = resolver(
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
    fun subscriptionWithoutExplicitBasePlanFailsResolution() = runBlocking {
        val failure = resolutionFailure {
            resolverWith(
                offer(basePlanId = "monthly", offerId = null, token = "monthly-base"),
            ).resolve(
                listOf(request(OfferSelection.None).copy(basePlanId = null)),
            )
        }

        assertTrue(failure.message.orEmpty().contains("nuxie-pro"))
        assertTrue(failure.message.orEmpty().contains("no configured base plan"))
    }

    @Test
    fun productMissingFromQueryResultFailsResolution() = runBlocking {
        val failure = resolutionFailure {
            resolver(FakeProductDetailsQuery()).resolve(
                listOf(request(OfferSelection.None)),
            )
        }

        assertTrue(failure.message.orEmpty().contains("nuxie-pro"))
        assertTrue(failure.message.orEmpty().contains("play-pro"))
        assertTrue(failure.message.orEmpty().contains("missing from Play query result"))
    }

    @Test
    fun unfetchedProductFailsResolutionWithPlayReasonCode() = runBlocking {
        val failure = resolutionFailure {
            resolver(
                FakeProductDetailsQuery(
                    unfetchedProducts = listOf(
                        PlayUnfetchedProduct(
                            productId = "play-pro",
                            productType = BillingClient.ProductType.SUBS,
                            statusCode = 7,
                        ),
                    ),
                ),
            ).resolve(listOf(request(OfferSelection.None)))
        }

        assertTrue(failure.message.orEmpty().contains("nuxie-pro"))
        assertTrue(failure.message.orEmpty().contains("unfetched by Play (status code 7)"))
    }

    @Test
    fun configuredBasePlanMissingFromProductDetailsFailsResolution() = runBlocking {
        val failure = resolutionFailure {
            resolverWith(
                offer(basePlanId = "monthly", offerId = null, token = "monthly-base"),
            ).resolve(listOf(request(OfferSelection.None)))
        }

        assertTrue(failure.message.orEmpty().contains("nuxie-pro"))
        assertTrue(failure.message.orEmpty().contains("base plan 'annual' is absent"))
    }

    @Test
    fun resolutionFailureNamesEveryUnresolvableProduct() = runBlocking {
        val failure = resolutionFailure {
            resolver(
                FakeProductDetailsQuery(
                    products = listOf(
                        PlayProductDetails(
                            productId = "missing-plan-store",
                            productType = BillingClient.ProductType.SUBS,
                            rawProduct = null,
                            subscriptionOffers = emptyList(),
                        ),
                    ),
                    unfetchedProducts = listOf(
                        PlayUnfetchedProduct(
                            productId = "unfetched-store",
                            productType = BillingClient.ProductType.INAPP,
                            statusCode = 5,
                        ),
                    ),
                ),
            ).resolve(
                listOf(
                    CatalogProductRequest(
                        productId = "missing-product",
                        storeProductId = "missing-store",
                        productType = BillingClient.ProductType.INAPP,
                    ),
                    CatalogProductRequest(
                        productId = "unfetched-product",
                        storeProductId = "unfetched-store",
                        productType = BillingClient.ProductType.INAPP,
                    ),
                    CatalogProductRequest(
                        productId = "missing-plan-product",
                        storeProductId = "missing-plan-store",
                        productType = BillingClient.ProductType.SUBS,
                        basePlanId = "annual",
                    ),
                ),
            )
        }

        assertTrue(failure.message.orEmpty().contains("missing-product"))
        assertTrue(failure.message.orEmpty().contains("unfetched-product"))
        assertTrue(failure.message.orEmpty().contains("missing-plan-product"))
    }

    @Test
    fun oneTimeProductKeepsTheNativeOfferToken() = runBlocking {
        val resolver = resolver(
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
        resolver(
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

    private fun resolver(query: ProductDetailsQuery): ProductResolver = ProductResolver(
        query,
        InMemoryPurchaseEvidenceStore(),
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

    private suspend fun resolutionFailure(block: suspend () -> Unit): ProductResolutionException =
        try {
            block()
            fail("Expected ProductResolutionException")
            error("unreachable")
        } catch (failure: ProductResolutionException) {
            failure
        }

    private class FakeProductDetailsQuery(
        private val products: List<PlayProductDetails> = emptyList(),
        private val unfetchedProducts: List<PlayUnfetchedProduct> = emptyList(),
    ) : ProductDetailsQuery {
        override suspend fun query(products: List<ProductQuery>): ProductDetailsQueryResult =
            ProductDetailsQueryResult.Success(this.products, unfetchedProducts)
    }
}
