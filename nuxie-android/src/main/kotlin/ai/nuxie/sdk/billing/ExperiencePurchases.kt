package ai.nuxie.sdk.billing

import ai.nuxie.sdk.JourneyExitReason
import ai.nuxie.sdk.experiences.AuthenticatedRelease
import ai.nuxie.sdk.events.JsonValueConverter
import ai.nuxie.sdk.features.FeatureType
import ai.nuxie.sdk.features.FeatureAllowance
import ai.nuxie.sdk.journey.JourneyService
import ai.nuxie.sdk.runtime.NuxieRuntimeEvent
import ai.nuxie.sdk.runtime.NuxieViewModelSnapshot
import android.app.Activity
import com.android.billingclient.api.BillingClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.util.concurrent.atomic.AtomicBoolean

internal class ExperiencePurchaseException(message: String) : IllegalStateException(message)

/**
 * The authenticated Google Play catalog and commerce routes retained for one
 * presented Experience. The parser deliberately accepts [AuthenticatedRelease]
 * rather than descriptor JSON so no store identifier can reach Billing before
 * release authentication and admission have completed.
 */
internal data class AuthenticatedExperiencePurchaseCatalog(
    val requests: List<CatalogProductRequest>,
    val routes: List<ExperiencePurchaseRoute>,
) {
    companion object {
        fun parse(release: AuthenticatedRelease): AuthenticatedExperiencePurchaseCatalog {
            val products = parseProducts(release)
            val placements = parsePlacements(release, products)
            val requests = placements.values.mapNotNull { placement ->
                val product = products[placement.productId] ?: invalid(
                    "Placement '${placement.id}' references an unknown Product",
                )
                if (product.platform != GOOGLE_PLAY) return@mapNotNull null
                CatalogProductRequest(
                    productId = product.id,
                    storeProductId = product.storeProductId,
                    productType = product.billingType,
                    basePlanId = product.basePlanId,
                    purchaseOptionId = product.purchaseOptionId,
                    offerSelection = placement.offerSelection,
                    placementId = placement.id,
                    consumable = product.type == CONSUMABLE,
                    featureAllowances = product.featureAllowances,
                    experienceId = release.identity.experienceId,
                    experienceVersion = release.identity.experienceVersionId,
                )
            }
            val parsedRoutes = parseRoutes(release, placements.keys)
            val duplicateRoute = parsedRoutes.groupBy { it.screenId to it.eventName }
                .entries.firstOrNull { it.value.size > 1 }?.key
            if (duplicateRoute != null) {
                invalid(
                    "Experience has duplicate commerce routes for screen '${duplicateRoute.first}' " +
                        "and event '${duplicateRoute.second}'",
                )
            }
            return AuthenticatedExperiencePurchaseCatalog(
                requests = requests,
                routes = parsedRoutes,
            )
        }

        private fun parseProducts(release: AuthenticatedRelease): Map<String, Product> {
            val rawProducts = release.descriptor["products"] as? JsonArray
                ?: invalid("Authenticated Experience has no Product catalog")
            val products = LinkedHashMap<String, Product>()
            rawProducts.forEachIndexed { index, element ->
                val raw = element as? JsonObject ?: invalid("Product[$index] is not an object")
                val id = raw.requiredString("id", "Product[$index]")
                val type = raw.requiredString("type", "Product '$id'")
                if (type !in PRODUCT_TYPES) invalid("Product '$id' has unsupported type '$type'")
                val store = raw["store"] as? JsonObject
                    ?: invalid("Product '$id' has no store identity")
                val platform = store.requiredString("platform", "Product '$id' store")
                val parsed = when (platform) {
                    GOOGLE_PLAY -> parseGoogleProduct(id, type, store, raw)
                    APPLE_APP_STORE -> Product(
                        id = id,
                        type = type,
                        platform = platform,
                        storeProductId = store.requiredString("productId", "Product '$id' store"),
                        billingType = "",
                        basePlanId = null,
                        purchaseOptionId = null,
                        featureAllowances = emptyList(),
                    )
                    else -> invalid("Product '$id' has unsupported store platform '$platform'")
                }
                if (products.put(id, parsed) != null) invalid("Duplicate Product '$id'")
            }
            return products
        }

        private fun parseGoogleProduct(
            id: String,
            type: String,
            store: JsonObject,
            product: JsonObject,
        ): Product {
            val storeType = store.requiredString("productType", "Product '$id' store")
            if (storeType != type) invalid("Product '$id' type does not match its Play identity")
            val basePlanId = store.nullableString("basePlanId", "Product '$id' store")
            val purchaseOptionId = store.nullableString("purchaseOptionId", "Product '$id' store")
            if (type == SUBSCRIPTION) {
                if (basePlanId.isNullOrBlank() || purchaseOptionId != null) {
                    invalid("Play subscription Product '$id' requires one exact base plan")
                }
            } else if (basePlanId != null) {
                invalid("Play one-time Product '$id' cannot use a base plan")
            }
            return Product(
                id = id,
                type = type,
                platform = GOOGLE_PLAY,
                storeProductId = store.requiredString("productId", "Product '$id' store"),
                billingType = if (type == SUBSCRIPTION) {
                    BillingClient.ProductType.SUBS
                } else {
                    BillingClient.ProductType.INAPP
                },
                basePlanId = basePlanId,
                purchaseOptionId = purchaseOptionId,
                featureAllowances = parseGrants(id, product),
            )
        }

        private fun parseGrants(productId: String, product: JsonObject): List<FeatureAllowance> {
            val entitlements = product["entitlements"] as? JsonArray
                ?: invalid("Product '$productId' has no Entitlement list")
            return entitlements.mapIndexed { index, element ->
                val raw = element as? JsonObject
                    ?: invalid("Product '$productId' Entitlement[$index] is not an object")
                val id = raw.requiredString("id", "Product '$productId' Entitlement[$index]")
                val featureId = raw.nullableString("featureId", "Entitlement '$id'") ?: id
                when (val allowanceType = raw.nullableString("allowanceType", "Entitlement '$id'")) {
                    null -> FeatureAllowance(featureId, FeatureType.BOOLEAN)
                    "fixed" -> FeatureAllowance(featureId, FeatureType.METERED)
                    "unlimited" -> FeatureAllowance(
                        featureId,
                        FeatureType.METERED,
                        unlimited = true,
                    )
                    else -> invalid("Entitlement '$id' has unsupported allowance '$allowanceType'")
                }
            }
        }

        private fun parsePlacements(
            release: AuthenticatedRelease,
            products: Map<String, Product>,
        ): Map<String, Placement> {
            val rawPlacements = release.descriptor["placements"] as? JsonArray
                ?: invalid("Authenticated Experience has no Placement catalog")
            val placements = LinkedHashMap<String, Placement>()
            rawPlacements.forEachIndexed { index, element ->
                val raw = element as? JsonObject ?: invalid("Placement[$index] is not an object")
                val id = raw.requiredString("id", "Placement[$index]")
                val productId = raw.requiredString("productId", "Placement '$id'")
                val product = products[productId]
                    ?: invalid("Placement '$id' references unknown Product '$productId'")
                val googlePlay = raw["googlePlay"]
                val offerSelection = when (googlePlay) {
                    null -> OfferSelection.None
                    is JsonObject -> {
                        if (product.platform != GOOGLE_PLAY) {
                            invalid("Placement '$id' has a Google Play offer for a non-Google Product")
                        }
                        OfferSelection.Exact(
                            googlePlay.requiredString(
                                "offerId",
                                "Placement '$id' Google Play selection",
                            ),
                        )
                    }
                    else -> invalid("Placement '$id' has invalid Google Play selection")
                }
                if (placements.put(id, Placement(id, productId, offerSelection)) != null) {
                    invalid("Duplicate Placement '$id'")
                }
            }
            return placements
        }

        private fun parseRoutes(
            release: AuthenticatedRelease,
            placementIds: Set<String>,
        ): List<ExperiencePurchaseRoute> {
            val journey = release.descriptor["journey"] as? JsonObject
                ?: invalid("Authenticated Experience has no Journey")
            val routes = journey["routes"] as? JsonArray
                ?: invalid("Authenticated Experience Journey has no routes")
            return routes.mapNotNull { element ->
                val raw = element as? JsonObject ?: invalid("Journey route is not an object")
                val host = raw["host"] as? JsonObject ?: invalid("Journey route has no host")
                if (host.requiredString("kind", "Journey route host") != "screen") {
                    return@mapNotNull null
                }
                val screenId = host.requiredString("screenId", "Journey route host")
                val eventName = raw.requiredString("eventName", "Journey route")
                val program = raw["program"] as? JsonArray ?: invalid("Journey route has no program")
                val actions = program.mapIndexed { index, action ->
                    action as? JsonObject ?: invalid("Journey route action[$index] is not an object")
                }
                val purchaseActions = actions.filter {
                    (it["type"] as? JsonPrimitive)?.content in setOf("purchase", "restore")
                }
                if (purchaseActions.isEmpty()) return@mapNotNull null
                if (actions.size != 1 || purchaseActions.size != 1) {
                    invalid(
                        "Commerce route '$screenId/$eventName' must contain exactly one direct action",
                    )
                }
                val action = purchaseActions.single()
                val parsedAction = when (action.requiredString("type", "Journey route action")) {
                    "purchase" -> ExperiencePurchaseAction.Purchase(
                        placement = parsePlacementValue(action["placementId"], placementIds),
                        onCompleted = parseProgram(action["onCompleted"], "onCompleted"),
                        onFailed = parseProgram(action["onFailed"], "onFailed"),
                        onCancelled = parseProgram(action["onCancelled"], "onCancelled"),
                    )
                    "restore" -> ExperiencePurchaseAction.Restore(
                        onRestored = parseProgram(action["onRestored"], "onRestored"),
                        onNoPurchases = parseProgram(action["onNoPurchases"], "onNoPurchases"),
                        onFailed = parseProgram(action["onFailed"], "onFailed"),
                    )
                    else -> return@mapNotNull null
                }
                ExperiencePurchaseRoute(screenId, eventName, parsedAction)
            }
        }

        private fun parsePlacementValue(
            value: JsonElement?,
            placementIds: Set<String>,
        ): ExperiencePlacementValue {
            val literal = when (value) {
                is JsonPrimitive -> value.takeIf { it.isString }?.content
                is JsonObject -> (value["literal"] as? JsonPrimitive)
                    ?.takeIf { it.isString }?.content
                else -> null
            }
            if (literal != null) {
                if (literal !in placementIds) invalid("Purchase references unknown Placement '$literal'")
                return ExperiencePlacementValue.Literal(literal)
            }
            val reference = (value as? JsonObject)?.get("ref") as? JsonObject
                ?: invalid("Purchase Placement must be a literal or path reference")
            if (reference.requiredString("kind", "Purchase Placement reference") != "path") {
                invalid("Purchase Placement reference must use kind 'path'")
            }
            if (reference["viewModelName"] != null || reference["isRelative"] != null) {
                invalid("Purchase Placement path must be absolute")
            }
            val path = reference.requiredString("path", "Purchase Placement reference")
            val terminal = path.split('.', '/').lastOrNull()
            if (terminal != "placementId") {
                invalid("Purchase Placement path must resolve a placementId")
            }
            return ExperiencePlacementValue.Path(path)
        }

        private fun parseProgram(value: JsonElement?, outlet: String): ExperienceOutcomeProgram {
            if (value == null || value === JsonNull) return ExperienceOutcomeProgram.EMPTY
            val actions = value as? JsonArray
                ?: invalid("Commerce outcome '$outlet' is not a program")
            val parsedActions = actions.mapIndexed { index, element ->
                val action = element as? JsonObject
                    ?: invalid("Commerce outcome '$outlet' action[$index] is not an object")
                when (action.requiredString("type", "Commerce outcome '$outlet' action[$index]")) {
                    "send_event" -> {
                        val payload = when (val rawPayload = action["payload"]) {
                            null, JsonNull -> JsonObject(emptyMap())
                            is JsonObject -> rawPayload
                            else -> invalid(
                                "Commerce outcome '$outlet' send_event payload is not an object",
                            )
                        }
                        if (payload.containsValueReference()) {
                            invalid(
                                "Commerce outcome '$outlet' has a dynamic send_event payload, " +
                                    "which Android does not support yet",
                            )
                        }
                        ExperienceOutcomeAction.SendEvent(
                            action.requiredString(
                                "eventName",
                                "Commerce outcome '$outlet' send_event",
                            ),
                            payload,
                        )
                    }
                    "milestone" -> ExperienceOutcomeAction.Milestone(
                        action.requiredString(
                            "milestoneId",
                            "Commerce outcome '$outlet' milestone",
                        ),
                    )
                    "dismiss" -> ExperienceOutcomeAction.Dismiss
                    "exit" -> ExperienceOutcomeAction.Exit(
                        parseExitReason(
                            action.nullableString(
                                "reason",
                                "Commerce outcome '$outlet' exit",
                            ),
                        ),
                    )
                    else -> invalid(
                        "Commerce outcome '$outlet' contains an action Android cannot execute",
                    )
                }
            }
            val terminalActionIndex = parsedActions.indexOfFirst {
                it === ExperienceOutcomeAction.Dismiss || it is ExperienceOutcomeAction.Exit
            }
            if (terminalActionIndex >= 0 && terminalActionIndex != parsedActions.lastIndex) {
                invalid("Commerce outcome '$outlet' cannot continue after a terminal action")
            }
            return ExperienceOutcomeProgram(parsedActions)
        }

        /** Matches the iOS Journey action contract: absent and unknown reasons complete. */
        private fun parseExitReason(reason: String?): JourneyExitReason = when (reason) {
            "dismissed" -> JourneyExitReason.DISMISSED
            "goal_met" -> JourneyExitReason.GOAL_MET
            "trigger_unmatched" -> JourneyExitReason.TRIGGER_UNMATCHED
            "expired" -> JourneyExitReason.EXPIRED
            "error" -> JourneyExitReason.ERROR
            "cancelled" -> JourneyExitReason.CANCELLED
            "superseded" -> JourneyExitReason.SUPERSEDED
            else -> JourneyExitReason.COMPLETED
        }

        private fun JsonElement.containsValueReference(): Boolean = when (this) {
            is JsonObject -> "ref" in this || values.any { it.containsValueReference() }
            is JsonArray -> any { it.containsValueReference() }
            else -> false
        }

        private data class Product(
            val id: String,
            val type: String,
            val platform: String,
            val storeProductId: String,
            val billingType: String,
            val basePlanId: String?,
            val purchaseOptionId: String?,
            val featureAllowances: List<FeatureAllowance>,
        )

        private data class Placement(
            val id: String,
            val productId: String,
            val offerSelection: OfferSelection,
        )
    }
}

internal data class ExperiencePurchaseRoute(
    val screenId: String,
    val eventName: String,
    val action: ExperiencePurchaseAction,
)

internal sealed interface ExperiencePurchaseAction {
    data class Purchase(
        val placement: ExperiencePlacementValue,
        val onCompleted: ExperienceOutcomeProgram = ExperienceOutcomeProgram.EMPTY,
        val onFailed: ExperienceOutcomeProgram = ExperienceOutcomeProgram.EMPTY,
        val onCancelled: ExperienceOutcomeProgram = ExperienceOutcomeProgram.EMPTY,
    ) : ExperiencePurchaseAction

    data class Restore(
        val onRestored: ExperienceOutcomeProgram = ExperienceOutcomeProgram.EMPTY,
        val onNoPurchases: ExperienceOutcomeProgram = ExperienceOutcomeProgram.EMPTY,
        val onFailed: ExperienceOutcomeProgram = ExperienceOutcomeProgram.EMPTY,
    ) : ExperiencePurchaseAction
}

internal data class ExperienceOutcomeProgram(
    val actions: List<ExperienceOutcomeAction>,
) {
    companion object {
        val EMPTY = ExperienceOutcomeProgram(emptyList())
    }
}

internal sealed interface ExperienceOutcomeAction {
    data class SendEvent(val eventName: String, val payload: JsonObject) : ExperienceOutcomeAction
    data class Milestone(val milestoneId: String) : ExperienceOutcomeAction
    data object Dismiss : ExperienceOutcomeAction
    data class Exit(val reason: JourneyExitReason) : ExperienceOutcomeAction
}

internal data class ExperiencePurchaseContext(
    val journeyId: String?,
    val ownerDistinctId: String,
)

/** Host operations the authenticated outcome-program interpreter can perform. */
internal interface ExperiencePurchaseProgramHost {
    fun dismissFromAuthenticatedProgram()

    fun exitFromAuthenticatedProgram(reason: JourneyExitReason)
}

internal interface ExperienceOutcomeProgramExecutor {
    fun validate(program: ExperienceOutcomeProgram, context: ExperiencePurchaseContext)

    suspend fun execute(
        activity: Activity,
        program: ExperienceOutcomeProgram,
        context: ExperiencePurchaseContext,
    )
}

internal class AuthenticatedExperienceOutcomeProgramExecutor(
    private val journeys: JourneyService,
    private val emit: (String, Map<String, Any?>, String?) -> Unit,
) : ExperienceOutcomeProgramExecutor {
    override fun validate(program: ExperienceOutcomeProgram, context: ExperiencePurchaseContext) {
        if (context.journeyId == null && program.actions.any { it is ExperienceOutcomeAction.Milestone }) {
            invalid("Standalone Experience commerce outcomes cannot record Journey milestones")
        }
        if (context.journeyId == null && program.actions.any { it is ExperienceOutcomeAction.Exit }) {
            invalid("Standalone Experience commerce outcomes cannot exit a Journey")
        }
    }

    override suspend fun execute(
        activity: Activity,
        program: ExperienceOutcomeProgram,
        context: ExperiencePurchaseContext,
    ) {
        for (action in program.actions) {
            when (action) {
                is ExperienceOutcomeAction.SendEvent -> emit(
                    action.eventName,
                    JsonValueConverter.toNativeMap(action.payload),
                    context.ownerDistinctId,
                )
                is ExperienceOutcomeAction.Milestone -> journeys.milestone(
                    context.ownerDistinctId,
                    checkNotNull(context.journeyId),
                    action.milestoneId,
                )
                ExperienceOutcomeAction.Dismiss -> {
                    withContext(Dispatchers.Main.immediate) {
                        (activity as? ExperiencePurchaseProgramHost)
                            ?.dismissFromAuthenticatedProgram()
                            ?: throw ExperiencePurchaseException(
                                "Experience host cannot execute an authenticated dismissal",
                            )
                    }
                    return
                }
                is ExperienceOutcomeAction.Exit -> {
                    withContext(Dispatchers.Main.immediate) {
                        (activity as? ExperiencePurchaseProgramHost)
                            ?.exitFromAuthenticatedProgram(action.reason)
                            ?: throw ExperiencePurchaseException(
                                "Experience host cannot execute an authenticated Journey exit",
                            )
                    }
                    return
                }
            }
        }
    }
}

internal sealed interface ExperiencePlacementValue {
    data class Literal(val placementId: String) : ExperiencePlacementValue
    data class Path(val path: String) : ExperiencePlacementValue
}

internal interface ExperiencePurchaseExecutor {
    suspend fun purchase(
        activity: Activity,
        product: StoreProduct,
        expectedOwnerDistinctId: String?,
    ): PurchaseResult

    suspend fun restore(expectedOwnerDistinctId: String?): RestoreResult
}

internal class PurchaseServiceExperiencePurchaseExecutor(
    private val purchases: PurchaseService,
) : ExperiencePurchaseExecutor {
    override suspend fun purchase(
        activity: Activity,
        product: StoreProduct,
        expectedOwnerDistinctId: String?,
    ): PurchaseResult =
        withContext(Dispatchers.Main.immediate) {
            purchases.purchase(
                activity,
                product,
                replacement = null,
                expectedOwnerDistinctId = expectedOwnerDistinctId,
            )
        }

    override suspend fun restore(expectedOwnerDistinctId: String?): RestoreResult =
        purchases.restorePurchases(expectedOwnerDistinctId)
}

internal fun interface ExperiencePurchasePreparer {
    suspend fun prepare(
        release: AuthenticatedRelease,
        journeyId: String?,
        ownerDistinctId: String?,
    ): ExperiencePurchaseSession?

    companion object {
        val NONE = ExperiencePurchasePreparer { _, _, _ -> null }
    }
}

internal class AuthenticatedExperiencePurchasePreparer(
    private val resolver: ProductResolver,
    private val executor: ExperiencePurchaseExecutor,
    private val programExecutor: ExperienceOutcomeProgramExecutor,
    private val scope: CoroutineScope,
    private val distinctId: () -> String,
) : ExperiencePurchasePreparer {
    override suspend fun prepare(
        release: AuthenticatedRelease,
        journeyId: String?,
        ownerDistinctId: String?,
    ): ExperiencePurchaseSession? {
        val catalog = AuthenticatedExperiencePurchaseCatalog.parse(release)
        if (catalog.requests.isEmpty() && catalog.routes.isEmpty()) return null
        val context = ExperiencePurchaseContext(
            journeyId = journeyId,
            ownerDistinctId = ownerDistinctId ?: distinctId(),
        )
        catalog.routes.forEach { route ->
            route.action.outcomePrograms.forEach { programExecutor.validate(it, context) }
        }
        val products = resolver.resolve(catalog.requests)
        return ExperiencePurchaseSession(
            products,
            catalog.routes,
            executor,
            programExecutor,
            scope,
            context,
        )
    }
}

/** One presentation's signed route table and freshly resolved Play products. */
internal class ExperiencePurchaseSession(
    products: List<StoreProduct>,
    private val routes: List<ExperiencePurchaseRoute>,
    private val executor: ExperiencePurchaseExecutor,
    private val programExecutor: ExperienceOutcomeProgramExecutor,
    private val scope: CoroutineScope,
    private val context: ExperiencePurchaseContext,
) {
    private val products = products.toList()
    private val productsByPlacement = this.products.associateBy { it.placementId }
    private val retired = AtomicBoolean(false)
    private val actionLock = Any()
    private val actionsInFlight = mutableSetOf<Pair<String, String>>()

    internal fun resolvedProducts(): List<StoreProduct> = products

    fun handle(
        activity: Activity,
        screenId: String,
        event: NuxieRuntimeEvent,
        viewModelSnapshot: NuxieViewModelSnapshot? = null,
    ) {
        if (retired.get() || activity.isFinishing || activity.isDestroyed) return
        val route = routes.singleOrNull {
            it.screenId == screenId && it.eventName == event.name
        } ?: return
        val routeKey = route.screenId to route.eventName
        if (!synchronized(actionLock) { actionsInFlight.add(routeKey) }) return
        when (val action = route.action) {
            is ExperiencePurchaseAction.Purchase -> {
                val placementId = when (val placement = action.placement) {
                    is ExperiencePlacementValue.Literal -> placement.placementId
                    is ExperiencePlacementValue.Path ->
                        viewModelSnapshot?.resolveString(placement.path)
                }
                val product = productsByPlacement[placementId]
                if (product == null) {
                    synchronized(actionLock) { actionsInFlight.remove(routeKey) }
                    return
                }
                scope.launch {
                    try {
                        val outcome = executor.purchase(
                            activity,
                            product,
                            context.ownerDistinctId,
                        )
                        val program = when (outcome) {
                            PurchaseResult.Purchased -> action.onCompleted
                            PurchaseResult.Cancelled -> action.onCancelled
                            PurchaseResult.Pending -> ExperienceOutcomeProgram.EMPTY
                            is PurchaseResult.Failed -> action.onFailed
                        }
                        if (!retired.get()) programExecutor.execute(activity, program, context)
                    } finally {
                        synchronized(actionLock) { actionsInFlight.remove(routeKey) }
                    }
                }
            }
            is ExperiencePurchaseAction.Restore -> scope.launch {
                try {
                    val outcome = executor.restore(context.ownerDistinctId)
                    val program = when (outcome) {
                        RestoreResult.Restored -> action.onRestored
                        RestoreResult.NoPurchases -> action.onNoPurchases
                        is RestoreResult.Failed -> action.onFailed
                    }
                    if (!retired.get()) programExecutor.execute(activity, program, context)
                } finally {
                    synchronized(actionLock) { actionsInFlight.remove(routeKey) }
                }
            }
        }
    }

    fun retire() {
        retired.set(true)
    }
}

private val ExperiencePurchaseAction.outcomePrograms: List<ExperienceOutcomeProgram>
    get() = when (this) {
        is ExperiencePurchaseAction.Purchase -> listOf(onCompleted, onFailed, onCancelled)
        is ExperiencePurchaseAction.Restore -> listOf(onRestored, onNoPurchases, onFailed)
    }

private fun JsonObject.requiredString(key: String, owner: String): String =
    (this[key] as? JsonPrimitive)
        ?.takeIf { it.isString }
        ?.content
        ?.takeIf(String::isNotBlank)
        ?: invalid("$owner has no '$key'")

private fun JsonObject.nullableString(key: String, owner: String): String? {
    val value = this[key] ?: return null
    if (value === JsonNull) return null
    return (value as? JsonPrimitive)
        ?.takeIf { it.isString }
        ?.content
        ?.takeIf(String::isNotBlank)
        ?: invalid("$owner has invalid '$key'")
}

private fun invalid(message: String): Nothing = throw ExperiencePurchaseException(message)

private const val GOOGLE_PLAY = "google_play"
private const val APPLE_APP_STORE = "apple_app_store"
private const val SUBSCRIPTION = "subscription"
private const val CONSUMABLE = "consumable"
private val PRODUCT_TYPES = setOf(SUBSCRIPTION, CONSUMABLE, "nonConsumable")
