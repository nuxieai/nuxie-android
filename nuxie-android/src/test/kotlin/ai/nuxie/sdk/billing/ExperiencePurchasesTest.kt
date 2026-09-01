package ai.nuxie.sdk.billing

import ai.nuxie.sdk.JourneyExitReason
import ai.nuxie.sdk.experiences.AuthenticatedRelease
import ai.nuxie.sdk.experiences.ExperienceReleaseIdentity
import ai.nuxie.sdk.features.FeatureType
import ai.nuxie.sdk.runtime.NuxieRuntimeEvent
import ai.nuxie.sdk.runtime.NuxieRuntimeEventProperty
import ai.nuxie.sdk.runtime.NuxieRuntimeEventPropertyValue
import android.app.Activity
import com.android.billingclient.api.BillingClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ExperiencePurchaseTest {
    @Test
    fun authenticatedCatalogMapsGoogleProductsAndPlacementsToExactPlayRequests() {
        val catalog = AuthenticatedExperiencePurchaseCatalog.parse(
            release(
                products = buildJsonArray {
                    add(product("pro", "subscription", "play.pro", basePlanId = "annual"))
                    add(
                        product(
                            "coins",
                            "consumable",
                            "play.coins",
                            purchaseOptionId = "standard",
                        ),
                    )
                },
                placements = buildJsonArray {
                    add(placement("primary", "pro", offerId = "trial-7d"))
                    add(placement("coin-pack", "coins"))
                },
            ),
        )

        assertEquals(2, catalog.requests.size)
        val subscription = catalog.requests.single { it.placementId == "primary" }
        assertEquals("pro", subscription.productId)
        assertEquals("play.pro", subscription.storeProductId)
        assertEquals(BillingClient.ProductType.SUBS, subscription.productType)
        assertEquals("annual", subscription.basePlanId)
        assertEquals("experience-1", subscription.experienceId)
        assertEquals("version-1", subscription.experienceVersion)
        assertEquals(OfferSelection.Exact("trial-7d"), subscription.offerSelection)
        assertEquals(
            listOf(
                "pro" to FeatureType.BOOLEAN,
                "metered" to FeatureType.METERED,
                "unlimited" to FeatureType.METERED,
            ),
            subscription.featureAllowances.map { it.featureId to it.type },
        )
        assertEquals(true, subscription.featureAllowances.last().unlimited)

        val consumable = catalog.requests.single { it.placementId == "coin-pack" }
        assertEquals(BillingClient.ProductType.INAPP, consumable.productType)
        assertEquals("standard", consumable.purchaseOptionId)
        assertEquals(true, consumable.consumable)
        assertEquals(OfferSelection.None, consumable.offerSelection)
    }

    @Test
    fun AppleProductsDoNotReachThePlayResolver() {
        val catalog = AuthenticatedExperiencePurchaseCatalog.parse(
            release(
                products = buildJsonArray {
                    add(product("ios-pro", "subscription", "ios.pro", platform = "apple_app_store"))
                    add(product("android-pro", "subscription", "play.pro", basePlanId = "annual"))
                },
                placements = buildJsonArray {
                    add(placement("ios", "ios-pro"))
                    add(placement("android", "android-pro"))
                },
            ),
        )

        assertEquals(listOf("android"), catalog.requests.map { it.placementId })
    }

    @Test
    fun malformedSignedPlacementFailsBeforeAnyPlayQueryCanBeBuilt() {
        val error = assertThrows(ExperiencePurchaseException::class.java) {
            AuthenticatedExperiencePurchaseCatalog.parse(
                release(
                    products = buildJsonArray {
                        add(product("pro", "subscription", "play.pro", basePlanId = "annual"))
                    },
                    placements = buildJsonArray { add(placement("primary", "missing")) },
                ),
            )
        }

        assertEquals(
            "Placement 'primary' references unknown Product 'missing'",
            error.message,
        )
    }

    @Test
    fun signedGoogleOfferOnAnAppleProductFailsClosed() {
        val error = assertThrows(ExperiencePurchaseException::class.java) {
            AuthenticatedExperiencePurchaseCatalog.parse(
                release(
                    products = buildJsonArray {
                        add(
                            product(
                                "ios-pro",
                                "subscription",
                                "ios.pro",
                                platform = "apple_app_store",
                            ),
                        )
                    },
                    placements = buildJsonArray {
                        add(placement("primary", "ios-pro", offerId = "trial-7d"))
                    },
                ),
            )
        }

        assertEquals(
            "Placement 'primary' has a Google Play offer for a non-Google Product",
            error.message,
        )
    }

    @Test
    fun signedOfferSelectionReachesCheckoutWhileAbsenceUsesTheBaseToken() = runBlocking {
        val executor = RecordingExecutor()
        val resolver = ProductResolver(
            ProductDetailsQuery {
                ProductDetailsQueryResult.Success(
                    listOf(
                        PlayProductDetails(
                            productId = "play.pro",
                            productType = BillingClient.ProductType.SUBS,
                            rawProduct = null,
                            subscriptionOffers = listOf(
                                subscriptionOffer(null, "annual-base"),
                                subscriptionOffer("trial-7d", "annual-trial"),
                            ),
                        ),
                    ),
                )
            },
            InMemoryPurchaseEvidenceStore(),
        )
        val preparer = AuthenticatedExperiencePurchasePreparer(
            resolver,
            executor,
            RecordingProgramExecutor(),
            kotlinx.coroutines.CoroutineScope(Dispatchers.Unconfined),
        ) { "customer-1" }
        val activity = Robolectric.buildActivity(Activity::class.java).get()

        val exact = checkNotNull(
            preparer.prepare(
                release(
                    products = buildJsonArray {
                        add(product("pro", "subscription", "play.pro", basePlanId = "annual"))
                    },
                    placements = buildJsonArray {
                        add(placement("primary", "pro", offerId = "trial-7d"))
                    },
                    routes = purchaseRoutes(),
                ),
                journeyId = null,
                ownerDistinctId = "customer-1",
            ),
        )
        exact.handle(activity, "paywall", event("purchase_tapped"))
        assertEquals("annual-trial", executor.purchased.single().offerToken)

        executor.purchased.clear()
        val base = checkNotNull(
            preparer.prepare(
                release(
                    products = buildJsonArray {
                        add(product("pro", "subscription", "play.pro", basePlanId = "annual"))
                    },
                    placements = buildJsonArray { add(placement("primary", "pro")) },
                    routes = purchaseRoutes(),
                ),
                journeyId = null,
                ownerDistinctId = "customer-1",
            ),
        )
        base.handle(activity, "paywall", event("purchase_tapped"))
        assertEquals("annual-base", executor.purchased.single().offerToken)
        assertEquals(null, executor.purchased.single().offerId)
    }

    @Test
    fun reportedRuntimeEventUsesSignedPurchaseRouteAndResolvedPlacement() = runBlocking {
        val executor = RecordingExecutor()
        val product = storeProduct("primary")
        val session = ExperiencePurchaseSession(
            products = listOf(product),
            routes = listOf(
                ExperiencePurchaseRoute(
                    "paywall",
                    "purchase_tapped",
                    ExperiencePurchaseAction.Purchase(
                        ExperiencePlacementValue.Literal("primary"),
                    ),
                ),
            ),
            executor = executor,
            programExecutor = RecordingProgramExecutor(),
            scope = kotlinx.coroutines.CoroutineScope(Dispatchers.Unconfined),
            context = CONTEXT,
        )
        val activity = Robolectric.buildActivity(Activity::class.java).get()

        session.handle(activity, "paywall", event("purchase_tapped"))

        assertEquals(listOf(product), executor.purchased)
    }

    @Test
    fun runtimePayloadCannotOverrideTheHostOwnedScreenIdentity() = runBlocking {
        val executor = RecordingExecutor()
        val product = storeProduct("annual")
        val session = ExperiencePurchaseSession(
            products = listOf(product),
            routes = listOf(
                ExperiencePurchaseRoute(
                    "paywall",
                    "purchase_tapped",
                    ExperiencePurchaseAction.Purchase(ExperiencePlacementValue.Literal("annual")),
                ),
            ),
            executor = executor,
            programExecutor = RecordingProgramExecutor(),
            scope = kotlinx.coroutines.CoroutineScope(Dispatchers.Unconfined),
            context = CONTEXT,
        )
        val activity = Robolectric.buildActivity(Activity::class.java).get()

        session.handle(
            activity,
            "paywall",
            event(
                "purchase_tapped",
                "screenId" to "attacker-controlled-screen",
                "placementId" to "attacker-controlled-placement",
            ),
        )

        assertEquals(listOf(product), executor.purchased)
    }

    @Test
    fun ambiguousScreenRouteNeverGuessesWhichCommercialActionToRun() = runBlocking {
        val executor = RecordingExecutor()
        val product = storeProduct("primary")
        val session = ExperiencePurchaseSession(
            products = listOf(product),
            routes = listOf(
                ExperiencePurchaseRoute(
                    "first",
                    "purchase_tapped",
                    ExperiencePurchaseAction.Purchase(ExperiencePlacementValue.Literal("primary")),
                ),
                ExperiencePurchaseRoute(
                    "second",
                    "purchase_tapped",
                    ExperiencePurchaseAction.Purchase(ExperiencePlacementValue.Literal("primary")),
                ),
            ),
            executor = executor,
            programExecutor = RecordingProgramExecutor(),
            scope = kotlinx.coroutines.CoroutineScope(Dispatchers.Unconfined),
            context = CONTEXT,
        )

        session.handle(
            Robolectric.buildActivity(Activity::class.java).get(),
            "unknown",
            event("purchase_tapped"),
        )

        assertEquals(emptyList<StoreProduct>(), executor.purchased)
    }

    @Test
    fun reportedRuntimeEventUsesSignedRestoreRoute() = runBlocking {
        val executor = RecordingExecutor()
        val session = ExperiencePurchaseSession(
            products = emptyList(),
            routes = listOf(
                ExperiencePurchaseRoute(
                    "paywall",
                    "restore_tapped",
                    ExperiencePurchaseAction.Restore(),
                ),
            ),
            executor = executor,
            programExecutor = RecordingProgramExecutor(),
            scope = kotlinx.coroutines.CoroutineScope(Dispatchers.Unconfined),
            context = CONTEXT,
        )

        session.handle(
            Robolectric.buildActivity(Activity::class.java).get(),
            "paywall",
            event("restore_tapped", "screen_id" to "paywall"),
        )

        assertEquals(1, executor.restoreCount)
    }

    @Test
    fun authenticatedPurchaseOutcomesExecuteOnlyTheirSignedContinuation() = runBlocking {
        val catalog = AuthenticatedExperiencePurchaseCatalog.parse(
            release(
                products = buildJsonArray {
                    add(product("pro", "subscription", "play.pro", basePlanId = "annual"))
                },
                placements = buildJsonArray { add(placement("primary", "pro")) },
                routes = purchaseOutcomeRoutes(),
            ),
        )
        val product = storeProduct("primary")
        val activity = Robolectric.buildActivity(Activity::class.java).get()
        val cases = listOf(
            PurchaseResult.Purchased to "purchase_completed",
            PurchaseResult.Cancelled to "purchase_cancelled",
            PurchaseResult.Failed(IllegalStateException("failed")) to "purchase_failed",
            PurchaseResult.Pending to null,
        )

        cases.forEach { (result, expectedEvent) ->
            val programExecutor = RecordingProgramExecutor()
            val session = ExperiencePurchaseSession(
                products = listOf(product),
                routes = catalog.routes,
                executor = RecordingExecutor(purchaseResult = result),
                programExecutor = programExecutor,
                scope = kotlinx.coroutines.CoroutineScope(Dispatchers.Unconfined),
                context = CONTEXT,
            )

            session.handle(activity, "paywall", event("purchase_tapped"))

            assertEquals(listOfNotNull(expectedEvent), programExecutor.eventNames)
        }
    }

    @Test
    fun authenticatedRestoreOutcomesExecuteOnlyTheirSignedContinuation() = runBlocking {
        val catalog = AuthenticatedExperiencePurchaseCatalog.parse(
            release(
                products = buildJsonArray {},
                placements = buildJsonArray {},
                routes = restoreOutcomeRoutes(),
            ),
        )
        val activity = Robolectric.buildActivity(Activity::class.java).get()
        val cases = listOf(
            RestoreResult.Restored to "restore_completed",
            RestoreResult.NoPurchases to "restore_empty",
            RestoreResult.Failed(IllegalStateException("failed")) to "restore_failed",
        )

        cases.forEach { (result, expectedEvent) ->
            val programExecutor = RecordingProgramExecutor()
            val session = ExperiencePurchaseSession(
                products = emptyList(),
                routes = catalog.routes,
                executor = RecordingExecutor(restoreResult = result),
                programExecutor = programExecutor,
                scope = kotlinx.coroutines.CoroutineScope(Dispatchers.Unconfined),
                context = CONTEXT,
            )

            session.handle(activity, "paywall", event("restore_tapped"))

            assertEquals(listOf(expectedEvent), programExecutor.eventNames)
        }
    }

    @Test
    fun authenticatedExitProgramsPreserveSignedReasonAndDefaultLikeTheJourneyContract() {
        val catalog = AuthenticatedExperiencePurchaseCatalog.parse(
            release(
                products = buildJsonArray {
                    add(product("pro", "subscription", "play.pro", basePlanId = "annual"))
                },
                placements = buildJsonArray { add(placement("primary", "pro")) },
                routes = buildJsonArray {
                    add(route("purchase_tapped", buildJsonObject {
                        put("type", "purchase")
                        put("placementId", "primary")
                        put("onCompleted", exitProgram("expired"))
                        put("onFailed", exitProgram(null))
                        put("onCancelled", exitProgram("future_reason"))
                    }))
                },
            ),
        )

        val action = catalog.routes.single().action as ExperiencePurchaseAction.Purchase
        assertEquals(
            ExperienceOutcomeAction.Exit(JourneyExitReason.EXPIRED),
            action.onCompleted.actions.single(),
        )
        assertEquals(
            ExperienceOutcomeAction.Exit(JourneyExitReason.COMPLETED),
            action.onFailed.actions.single(),
        )
        assertEquals(
            ExperienceOutcomeAction.Exit(JourneyExitReason.COMPLETED),
            action.onCancelled.actions.single(),
        )
    }

    @Test
    fun unsupportedAuthenticatedOutcomeActionFailsDuringPreparation() {
        val error = assertThrows(ExperiencePurchaseException::class.java) {
            AuthenticatedExperiencePurchaseCatalog.parse(
                release(
                    products = buildJsonArray {
                        add(product("pro", "subscription", "play.pro", basePlanId = "annual"))
                    },
                    placements = buildJsonArray { add(placement("primary", "pro")) },
                    routes = buildJsonArray {
                        add(route("purchase_tapped", buildJsonObject {
                            put("type", "purchase")
                            put("placementId", "primary")
                            put("onCompleted", buildJsonArray {
                                add(buildJsonObject {
                                    put("type", "navigate")
                                    put("screenId", "success")
                                })
                            })
                        }))
                    },
                ),
            )
        }

        assertEquals(
            "Commerce outcome 'onCompleted' contains an action Android cannot execute",
            error.message,
        )
    }

    @Test
    fun authenticatedOutcomeCannotSilentlyDropActionsAfterDismissal() {
        val error = assertThrows(ExperiencePurchaseException::class.java) {
            AuthenticatedExperiencePurchaseCatalog.parse(
                release(
                    products = buildJsonArray {
                        add(product("pro", "subscription", "play.pro", basePlanId = "annual"))
                    },
                    placements = buildJsonArray { add(placement("primary", "pro")) },
                    routes = buildJsonArray {
                        add(route("purchase_tapped", buildJsonObject {
                            put("type", "purchase")
                            put("placementId", "primary")
                            put("onCompleted", buildJsonArray {
                                add(buildJsonObject { put("type", "dismiss") })
                                add(buildJsonObject {
                                    put("type", "send_event")
                                    put("eventName", "silently_dropped")
                                })
                            })
                        }))
                    },
                ),
            )
        }

        assertEquals(
            "Commerce outcome 'onCompleted' cannot continue after a terminal action",
            error.message,
        )
    }

    @Test
    fun retiredPresentationCannotStartCommerce() = runBlocking {
        val executor = RecordingExecutor()
        val session = ExperiencePurchaseSession(
            products = listOf(storeProduct("primary")),
            routes = listOf(
                ExperiencePurchaseRoute(
                    "paywall",
                    "purchase_tapped",
                    ExperiencePurchaseAction.Purchase(ExperiencePlacementValue.Literal("primary")),
                ),
            ),
            executor = executor,
            programExecutor = RecordingProgramExecutor(),
            scope = kotlinx.coroutines.CoroutineScope(Dispatchers.Unconfined),
            context = CONTEXT,
        )
        session.retire()

        session.handle(
            Robolectric.buildActivity(Activity::class.java).get(),
            "paywall",
            event("purchase_tapped"),
        )

        assertEquals(emptyList<StoreProduct>(), executor.purchased)
    }

    private class RecordingExecutor(
        private val purchaseResult: PurchaseResult = PurchaseResult.Purchased,
        private val restoreResult: RestoreResult = RestoreResult.Restored,
    ) : ExperiencePurchaseExecutor {
        val purchased = mutableListOf<StoreProduct>()
        var restoreCount = 0

        override suspend fun purchase(
            activity: Activity,
            product: StoreProduct,
            expectedOwnerDistinctId: String?,
        ): PurchaseResult {
            purchased += product
            return purchaseResult
        }

        override suspend fun restore(expectedOwnerDistinctId: String?): RestoreResult {
            restoreCount += 1
            return restoreResult
        }
    }

    private class RecordingProgramExecutor : ExperienceOutcomeProgramExecutor {
        val eventNames = mutableListOf<String>()

        override fun validate(
            program: ExperienceOutcomeProgram,
            context: ExperiencePurchaseContext,
        ) = Unit

        override suspend fun execute(
            activity: Activity,
            program: ExperienceOutcomeProgram,
            context: ExperiencePurchaseContext,
        ) {
            eventNames += program.actions.mapNotNull {
                (it as? ExperienceOutcomeAction.SendEvent)?.eventName
            }
        }
    }

    private fun release(
        products: JsonArray,
        placements: JsonArray,
        routes: JsonArray = buildJsonArray {},
    ): AuthenticatedRelease {
        val identity = ExperienceReleaseIdentity(
            appId = "app",
            environment = "development",
            experienceId = "experience-1",
            experienceVersionId = "version-1",
            buildId = "build-1",
            versionNumber = 1,
            publishedAt = "2026-08-31T00:00:00Z",
            publishedAtSeq = 1,
        )
        val descriptor = buildJsonObject {
            put("products", products)
            put("placements", placements)
            put("journey", buildJsonObject { put("routes", routes) })
        }
        return AuthenticatedRelease("key", "sha", identity, ByteArray(0), descriptor, 1)
    }

    private fun product(
        id: String,
        type: String,
        storeProductId: String,
        platform: String = "google_play",
        basePlanId: String? = null,
        purchaseOptionId: String? = null,
    ): JsonObject = buildJsonObject {
        put("id", id)
        put("type", type)
        put("store", buildJsonObject {
            put("platform", platform)
            put("productId", storeProductId)
            if (platform == "google_play") {
                put("productType", type)
                put("basePlanId", basePlanId?.let(::JsonPrimitive) ?: JsonNull)
                put("purchaseOptionId", purchaseOptionId?.let(::JsonPrimitive) ?: JsonNull)
            }
        })
        put("entitlements", buildJsonArray {
            add(entitlement(id, null))
            add(entitlement("metered", "fixed"))
            add(entitlement("unlimited", "unlimited"))
        })
    }

    private fun entitlement(id: String, allowanceType: String?): JsonObject = buildJsonObject {
        put("id", id)
        put("featureId", JsonNull)
        put("allowanceType", allowanceType?.let(::JsonPrimitive) ?: JsonNull)
    }

    private fun placement(
        id: String,
        productId: String,
        offerId: String? = null,
    ): JsonObject = buildJsonObject {
        put("id", id)
        put("productId", productId)
        if (offerId != null) {
            put("googlePlay", buildJsonObject { put("offerId", offerId) })
        }
    }

    private fun purchaseRoutes(): JsonArray = buildJsonArray {
        add(route("purchase_tapped", buildJsonObject {
            put("type", "purchase")
            put("placementId", "primary")
        }))
    }

    private fun purchaseOutcomeRoutes(): JsonArray = buildJsonArray {
        add(route("purchase_tapped", buildJsonObject {
            put("type", "purchase")
            put("placementId", "primary")
            put("onCompleted", eventProgram("purchase_completed"))
            put("onFailed", eventProgram("purchase_failed"))
            put("onCancelled", eventProgram("purchase_cancelled"))
        }))
    }

    private fun restoreOutcomeRoutes(): JsonArray = buildJsonArray {
        add(route("restore_tapped", buildJsonObject {
            put("type", "restore")
            put("onRestored", eventProgram("restore_completed"))
            put("onNoPurchases", eventProgram("restore_empty"))
            put("onFailed", eventProgram("restore_failed"))
        }))
    }

    private fun route(eventName: String, action: JsonObject): JsonObject = buildJsonObject {
        put("eventName", eventName)
        put("host", buildJsonObject {
            put("kind", "screen")
            put("screenId", "paywall")
        })
        put("program", buildJsonArray { add(action) })
    }

    private fun eventProgram(eventName: String): JsonArray = buildJsonArray {
        add(buildJsonObject {
            put("type", "send_event")
            put("eventName", eventName)
        })
    }

    private fun exitProgram(reason: String?): JsonArray = buildJsonArray {
        add(buildJsonObject {
            put("type", "exit")
            reason?.let { put("reason", it) }
        })
    }

    private fun subscriptionOffer(offerId: String?, token: String) = PlaySubscriptionOffer(
        basePlanId = "annual",
        offerId = offerId,
        offerToken = token,
        offerTags = emptyList(),
        pricingPhases = listOf(
            PlayPricingPhase(
                priceAmountMicros = 9_990_000,
                billingPeriod = "P1Y",
                billingCycleCount = 0,
                recurrenceMode = PlayRecurrenceMode.INFINITE,
            ),
        ),
    )

    private fun event(name: String, vararg properties: Pair<String, String>) = NuxieRuntimeEvent(
        localIndex = 0,
        coreType = 0,
        name = name,
        url = "",
        target = "",
        delay = 0f,
        properties = properties.map { (key, value) ->
            NuxieRuntimeEventProperty(
                key,
                NuxieRuntimeEventPropertyValue.Bytes(value.encodeToByteArray()),
            )
        },
    )

    private fun storeProduct(placementId: String) = StoreProduct(
        productId = "product-$placementId",
        storeProductId = "play-$placementId",
        basePlanId = null,
        offerId = null,
        placementId = placementId,
        rawProduct = null,
        offerToken = null,
        isOfferPersonalized = false,
        productType = BillingClient.ProductType.INAPP,
    )

    companion object {
        private val CONTEXT = ExperiencePurchaseContext(null, "customer-1")
    }
}
