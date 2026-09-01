package ai.nuxie.sdk.billing

import ai.nuxie.sdk.NuxieEnvironment
import android.util.Log
import java.io.File
import java.math.BigDecimal
import java.security.MessageDigest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull

internal enum class StoredPurchaseState { PENDING, PURCHASED }

internal fun purchaseEvidenceDirectory(
    filesDirectory: File,
    apiKey: String,
    environment: NuxieEnvironment,
): File {
    // Keep the pre-rename directory stable so existing purchase evidence remains discoverable.
    return File(File(filesDirectory, "nuxie-commerce"), purchaseAuthorityScope(apiKey, environment))
}

internal fun purchaseAuthorityScope(apiKey: String, environment: NuxieEnvironment): String =
    MessageDigest.getInstance("SHA-256")
        .digest("$apiKey\u0000${environment.name}".encodeToByteArray())
        .joinToString("") { "%02x".format(it) }

internal data class StoredPurchaseContext(
    val placementId: String? = null,
    val experienceId: String? = null,
    val experienceVersion: String? = null,
    val price: BigDecimal? = null,
    val displayPrice: String? = null,
)

internal data class StoredFeatureAllowance(
    val featureId: String,
    val type: String,
    val unlimited: Boolean,
    val allowance: Double? = null,
)

/** Durable signed-catalog mapping plus the customer account used for checkout. */
internal data class StoredPurchaseBinding(
    val obfuscatedAccountId: String,
    val distinctId: String,
    val storeProductId: String,
    val nuxieProductId: String,
    val basePlanId: String? = null,
    val purchaseOptionId: String? = null,
    val offerId: String? = null,
    val productType: String,
    val consumable: Boolean,
    val context: StoredPurchaseContext? = null,
    val featureAllowances: List<StoredFeatureAllowance> = emptyList(),
    val licensingPublicKey: String? = null,
    val nuxieManaged: Boolean,
)

internal data class StoredProductMapping(
    val storeProductId: String,
    val nuxieProductId: String,
    val basePlanId: String? = null,
    val purchaseOptionId: String? = null,
    val offerId: String? = null,
    val productType: String,
    val consumable: Boolean,
    val context: StoredPurchaseContext? = null,
    val featureAllowances: List<StoredFeatureAllowance> = emptyList(),
    val licensingPublicKey: String? = null,
)

internal data class StoredProductIdentity(
    val storeProductId: String,
    val nuxieProductId: String,
    val basePlanId: String?,
    val purchaseOptionId: String?,
    val offerId: String?,
    val productType: String?,
)

internal val StoredPurchaseBinding.productIdentity: StoredProductIdentity
    get() = StoredProductIdentity(
        storeProductId,
        nuxieProductId,
        basePlanId,
        purchaseOptionId,
        offerId,
        productType,
    )

internal val StoredProductMapping.productIdentity: StoredProductIdentity
    get() = StoredProductIdentity(
        storeProductId,
        nuxieProductId,
        basePlanId,
        purchaseOptionId,
        offerId,
        productType,
    )

internal fun PurchaseEvidence.matchesProductIdentity(identity: StoredProductIdentity): Boolean =
    identity.storeProductId in storeProductIds &&
        identity.nuxieProductId == nuxieProductId &&
        identity.basePlanId == basePlanId &&
        identity.offerId == offerId &&
        (purchaseOptionId == null || identity.purchaseOptionId == purchaseOptionId) &&
        (productType == null || identity.productType == productType)

/**
 * Match retained legacy evidence without guessing between newly distinct Play
 * purchase options. Callers must still require a single matching candidate.
 */
internal fun StoredProductIdentity.matchesKnownIdentity(known: StoredProductIdentity): Boolean =
    storeProductId == known.storeProductId &&
        nuxieProductId == known.nuxieProductId &&
        basePlanId == known.basePlanId &&
        offerId == known.offerId &&
        (known.purchaseOptionId == null || purchaseOptionId == known.purchaseOptionId) &&
        (known.productType == null || productType == known.productType)

private data class StoredPurchaseBindingKey(
    val obfuscatedAccountId: String,
    val productIdentity: StoredProductIdentity,
)

/** Minimal Play evidence retained until /purchase and managed completion succeed. */
internal data class PurchaseEvidence(
    val purchaseToken: String,
    val packageName: String,
    val storeProductIds: List<String>,
    val nuxieProductId: String? = null,
    val basePlanId: String? = null,
    val purchaseOptionId: String? = null,
    val offerId: String? = null,
    val productType: String? = null,
    val purchaseState: StoredPurchaseState,
    val obfuscatedAccountId: String? = null,
    val syncAttributionDistinctId: String,
    val ownerDistinctId: String? = null,
    val context: StoredPurchaseContext? = null,
    /** Purchase-token-scoped allowance snapshot; null is unresolved and empty carries no Feature allowances. */
    val pinnedFeatureAllowances: List<StoredFeatureAllowance>? = null,
    val acknowledged: Boolean,
    val consumed: Boolean = false,
    val synced: Boolean = false,
    val permanentlyRejected: Boolean = false,
    val syncAttempts: Int = 0,
    val completionAttempts: Int = 0,
    val firstSeenMillis: Long,
    val consumable: Boolean = false,
    val catalogResolved: Boolean = false,
    val completionEmitted: Boolean = false,
    val syncedEventEmitted: Boolean = false,
    val syncedCustomerId: String? = null,
    val acceptedResponseBody: JsonObject? = null,
    val nuxieManaged: Boolean = false,
    val signatureVerificationRequired: Boolean = false,
    val signatureVerified: Boolean = false,
    val authorityScope: String? = null,
    val revoked: Boolean = false,
    val backendSyncedAtMillis: Long? = null,
)

internal interface PurchaseEvidenceStore {
    fun load(): Map<String, PurchaseEvidence>
    fun upsert(evidence: PurchaseEvidence): Boolean
    fun loadBindings(): List<StoredPurchaseBinding> = emptyList()
    fun upsertBinding(binding: StoredPurchaseBinding): Boolean = true
    fun loadProductMappings(): List<StoredProductMapping> = emptyList()
    /** Successful installation invokes the listener before a later mapping installation may begin. */
    fun upsertProductMapping(mapping: StoredProductMapping): Boolean = true
    fun setProductMappingsChangedListener(listener: (() -> Unit)?) = Unit
}

/** Scope-private atomic JSON store. Purchase tokens are keys, never logs or preferences. */
internal class FilePurchaseEvidenceStore(
    directory: File,
) : PurchaseEvidenceStore {
    private val file = File(directory, "purchase-evidence.json")
    private val bindingsFile = File(directory, "purchase-bindings.json")
    private val catalogFile = File(directory, "purchase-catalog.json")
    private val lock = Any()
    private val productMappingInstallationLock = Any()
    private val json = Json { ignoreUnknownKeys = true }
    @Volatile private var productMappingsChangedListener: (() -> Unit)? = null

    init {
        directory.mkdirs()
    }

    override fun load(): Map<String, PurchaseEvidence> = synchronized(lock) { loadUnlocked() }

    override fun upsert(evidence: PurchaseEvidence): Boolean = synchronized(lock) {
        val entries = loadUnlocked().toMutableMap()
        entries[evidence.purchaseToken] = evidence
        saveUnlocked(entries)
    }

    override fun loadBindings(): List<StoredPurchaseBinding> = synchronized(lock) {
        runCatching {
            if (!bindingsFile.exists()) emptyList() else {
                json.parseToJsonElement(bindingsFile.readText()).jsonArray.mapNotNull {
                    decodeBinding(it as? JsonObject ?: return@mapNotNull null)
                }
            }
        }.getOrDefault(emptyList())
    }

    override fun upsertBinding(binding: StoredPurchaseBinding): Boolean = synchronized(lock) {
        val entries = loadBindingsUnlocked().associateByTo(linkedMapOf()) {
            StoredPurchaseBindingKey(it.obfuscatedAccountId, it.productIdentity)
        }
        entries[StoredPurchaseBindingKey(binding.obfuscatedAccountId, binding.productIdentity)] = binding
        runCatching {
            val temporary = File(bindingsFile.parentFile, "${bindingsFile.name}.tmp")
            val encoded = JsonArray(entries.values.map(::encodeBinding))
            temporary.writeText(json.encodeToString(JsonArray.serializer(), encoded))
            if (!temporary.renameTo(bindingsFile)) error("Could not publish purchase bindings")
            true
        }.onFailure { Log.w("NuxieBilling", "Could not persist purchase binding.", it) }
            .getOrDefault(false)
    }

    override fun loadProductMappings(): List<StoredProductMapping> = synchronized(lock) {
        loadMappingsUnlocked()
    }

    override fun upsertProductMapping(mapping: StoredProductMapping): Boolean =
        synchronized(productMappingInstallationLock) {
            val persisted = synchronized(lock) {
                val entries = loadMappingsUnlocked().associateByTo(linkedMapOf()) { it.productIdentity }
                entries[mapping.productIdentity] = mapping
                saveArray(catalogFile, entries.values.map(::encodeMapping), "catalog mapping")
            }
            if (persisted) productMappingsChangedListener?.invoke()
            persisted
        }

    override fun setProductMappingsChangedListener(listener: (() -> Unit)?) {
        productMappingsChangedListener = listener
    }

    private fun loadBindingsUnlocked(): List<StoredPurchaseBinding> = runCatching {
        if (!bindingsFile.exists()) emptyList() else {
            json.parseToJsonElement(bindingsFile.readText()).jsonArray.mapNotNull {
                decodeBinding(it as? JsonObject ?: return@mapNotNull null)
            }
        }
    }.getOrDefault(emptyList())

    private fun loadMappingsUnlocked(): List<StoredProductMapping> = runCatching {
        if (!catalogFile.exists()) emptyList() else {
            json.parseToJsonElement(catalogFile.readText()).jsonArray.mapNotNull {
                decodeMapping(it as? JsonObject ?: return@mapNotNull null)
            }
        }
    }.getOrDefault(emptyList())

    private fun saveArray(target: File, values: List<JsonObject>, description: String): Boolean = runCatching {
        val temporary = File(target.parentFile, "${target.name}.tmp")
        temporary.writeText(json.encodeToString(JsonArray.serializer(), JsonArray(values)))
        if (!temporary.renameTo(target)) error("Could not publish purchase $description")
        true
    }.onFailure { Log.w("NuxieBilling", "Could not persist purchase $description.", it) }
        .getOrDefault(false)

    private fun loadUnlocked(): Map<String, PurchaseEvidence> = runCatching {
        if (!file.exists()) emptyMap() else json.parseToJsonElement(file.readText()).jsonObject
            .mapNotNull { (token, raw) -> decodeEvidence(raw.jsonObject)?.let { token to it } }
            .toMap()
    }.getOrDefault(emptyMap())

    private fun saveUnlocked(entries: Map<String, PurchaseEvidence>): Boolean = runCatching {
        val temporary = File(file.parentFile, "${file.name}.tmp")
        val encoded = JsonObject(entries.mapValues { encodeEvidence(it.value) })
        temporary.writeText(json.encodeToString(JsonObject.serializer(), encoded))
        if (!temporary.renameTo(file)) error("Could not publish purchase evidence")
        true
    }.onFailure { Log.w("NuxieBilling", "Could not persist purchase evidence.", it) }
        .getOrDefault(false)

    private fun encodeEvidence(evidence: PurchaseEvidence): JsonObject = JsonObject(
        buildMap {
            put("purchaseToken", JsonPrimitive(evidence.purchaseToken))
            put("packageName", JsonPrimitive(evidence.packageName))
            put("storeProductIds", JsonArray(evidence.storeProductIds.map(::JsonPrimitive)))
            evidence.nuxieProductId?.let { put("nuxieProductId", JsonPrimitive(it)) }
            evidence.basePlanId?.let { put("basePlanId", JsonPrimitive(it)) }
            evidence.purchaseOptionId?.let { put("purchaseOptionId", JsonPrimitive(it)) }
            evidence.offerId?.let { put("offerId", JsonPrimitive(it)) }
            evidence.productType?.let { put("productType", JsonPrimitive(it)) }
            put("purchaseState", JsonPrimitive(evidence.purchaseState.name))
            evidence.obfuscatedAccountId?.let { put("obfuscatedAccountId", JsonPrimitive(it)) }
            put("syncAttributionDistinctId", JsonPrimitive(evidence.syncAttributionDistinctId))
            evidence.ownerDistinctId?.let { put("ownerDistinctId", JsonPrimitive(it)) }
            evidence.context?.let { put("context", encodeContext(it)) }
            evidence.pinnedFeatureAllowances?.let { allowances ->
                put("pinnedFeatureAllowances", JsonArray(allowances.map(::encodeAllowance)))
            }
            put("acknowledged", JsonPrimitive(evidence.acknowledged))
            put("consumed", JsonPrimitive(evidence.consumed))
            put("synced", JsonPrimitive(evidence.synced))
            put("permanentlyRejected", JsonPrimitive(evidence.permanentlyRejected))
            put("syncAttempts", JsonPrimitive(evidence.syncAttempts))
            put("completionAttempts", JsonPrimitive(evidence.completionAttempts))
            put("firstSeenMillis", JsonPrimitive(evidence.firstSeenMillis))
            put("consumable", JsonPrimitive(evidence.consumable))
            put("catalogResolved", JsonPrimitive(evidence.catalogResolved))
            put("completionEmitted", JsonPrimitive(evidence.completionEmitted))
            put("syncedEventEmitted", JsonPrimitive(evidence.syncedEventEmitted))
            evidence.syncedCustomerId?.let { put("syncedCustomerId", JsonPrimitive(it)) }
            evidence.acceptedResponseBody?.let { put("acceptedResponseBody", it) }
            put("nuxieManaged", JsonPrimitive(evidence.nuxieManaged))
            put("signatureVerificationRequired", JsonPrimitive(evidence.signatureVerificationRequired))
            put("signatureVerified", JsonPrimitive(evidence.signatureVerified))
            evidence.authorityScope?.let { put("authorityScope", JsonPrimitive(it)) }
            put("revoked", JsonPrimitive(evidence.revoked))
            evidence.backendSyncedAtMillis?.let { put("backendSyncedAtMillis", JsonPrimitive(it)) }
        },
    )

    private fun decodeEvidence(raw: JsonObject): PurchaseEvidence? = runCatching {
        PurchaseEvidence(
            purchaseToken = raw.string("purchaseToken") ?: return null,
            packageName = raw.string("packageName") ?: return null,
            storeProductIds = raw["storeProductIds"]?.jsonArray.orEmpty()
                .mapNotNull { (it as? JsonPrimitive)?.contentOrNull },
            nuxieProductId = raw.string("nuxieProductId"),
            basePlanId = raw.string("basePlanId"),
            purchaseOptionId = raw.string("purchaseOptionId"),
            offerId = raw.string("offerId"),
            productType = raw.string("productType"),
            purchaseState = StoredPurchaseState.valueOf(raw.string("purchaseState") ?: return null),
            obfuscatedAccountId = raw.string("obfuscatedAccountId"),
            syncAttributionDistinctId = raw.string("syncAttributionDistinctId") ?: return null,
            ownerDistinctId = raw.string("ownerDistinctId"),
            context = (raw["context"] as? JsonObject)?.let(::decodeContext),
            pinnedFeatureAllowances = (raw["pinnedFeatureAllowances"] as? JsonArray)
                ?.mapNotNull(::decodeAllowance),
            acknowledged = raw.boolean("acknowledged"),
            consumed = raw.boolean("consumed"),
            synced = raw.boolean("synced"),
            permanentlyRejected = raw.boolean("permanentlyRejected"),
            syncAttempts = (raw["syncAttempts"] as? JsonPrimitive)?.intOrNull ?: 0,
            completionAttempts = (raw["completionAttempts"] as? JsonPrimitive)?.intOrNull ?: 0,
            firstSeenMillis = (raw["firstSeenMillis"] as? JsonPrimitive)?.longOrNull ?: return null,
            consumable = raw.boolean("consumable"),
            catalogResolved = raw.boolean("catalogResolved"),
            completionEmitted = raw.boolean("completionEmitted"),
            syncedEventEmitted = raw.boolean("syncedEventEmitted"),
            syncedCustomerId = raw.string("syncedCustomerId"),
            acceptedResponseBody = raw["acceptedResponseBody"] as? JsonObject,
            nuxieManaged = raw.boolean("nuxieManaged"),
            signatureVerificationRequired = raw.boolean("signatureVerificationRequired"),
            signatureVerified = raw.boolean("signatureVerified"),
            authorityScope = raw.string("authorityScope"),
            revoked = raw.boolean("revoked"),
            backendSyncedAtMillis = (raw["backendSyncedAtMillis"] as? JsonPrimitive)?.longOrNull,
        )
    }.getOrNull()

    private fun encodeBinding(binding: StoredPurchaseBinding): JsonObject = JsonObject(
        buildMap {
            put("obfuscatedAccountId", JsonPrimitive(binding.obfuscatedAccountId))
            put("distinctId", JsonPrimitive(binding.distinctId))
            put("storeProductId", JsonPrimitive(binding.storeProductId))
            put("nuxieProductId", JsonPrimitive(binding.nuxieProductId))
            binding.basePlanId?.let { put("basePlanId", JsonPrimitive(it)) }
            binding.purchaseOptionId?.let { put("purchaseOptionId", JsonPrimitive(it)) }
            binding.offerId?.let { put("offerId", JsonPrimitive(it)) }
            put("productType", JsonPrimitive(binding.productType))
            put("consumable", JsonPrimitive(binding.consumable))
            binding.context?.let { put("context", encodeContext(it)) }
            put("featureAllowances", JsonArray(binding.featureAllowances.map(::encodeAllowance)))
            binding.licensingPublicKey?.let { put("licensingPublicKey", JsonPrimitive(it)) }
            put("nuxieManaged", JsonPrimitive(binding.nuxieManaged))
        },
    )

    private fun decodeBinding(raw: JsonObject): StoredPurchaseBinding? = runCatching {
        StoredPurchaseBinding(
            obfuscatedAccountId = raw.string("obfuscatedAccountId") ?: return null,
            distinctId = raw.string("distinctId") ?: return null,
            storeProductId = raw.string("storeProductId") ?: return null,
            nuxieProductId = raw.string("nuxieProductId") ?: return null,
            basePlanId = raw.string("basePlanId"),
            purchaseOptionId = raw.string("purchaseOptionId"),
            offerId = raw.string("offerId"),
            productType = raw.string("productType") ?: return null,
            consumable = raw.boolean("consumable"),
            context = (raw["context"] as? JsonObject)?.let(::decodeContext),
            featureAllowances = (raw["featureAllowances"] as? JsonArray)
                .orEmpty().mapNotNull(::decodeAllowance),
            licensingPublicKey = raw.string("licensingPublicKey"),
            nuxieManaged = raw.boolean("nuxieManaged"),
        )
    }.getOrNull()

    private fun encodeMapping(mapping: StoredProductMapping): JsonObject = encodeBinding(
        StoredPurchaseBinding(
            obfuscatedAccountId = "",
            distinctId = "",
            storeProductId = mapping.storeProductId,
            nuxieProductId = mapping.nuxieProductId,
            basePlanId = mapping.basePlanId,
            purchaseOptionId = mapping.purchaseOptionId,
            offerId = mapping.offerId,
            productType = mapping.productType,
            consumable = mapping.consumable,
            context = mapping.context,
            featureAllowances = mapping.featureAllowances,
            licensingPublicKey = mapping.licensingPublicKey,
            nuxieManaged = false,
        ),
    )

    private fun decodeMapping(raw: JsonObject): StoredProductMapping? = runCatching {
        StoredProductMapping(
            storeProductId = raw.string("storeProductId") ?: return null,
            nuxieProductId = raw.string("nuxieProductId") ?: return null,
            basePlanId = raw.string("basePlanId"),
            purchaseOptionId = raw.string("purchaseOptionId"),
            offerId = raw.string("offerId"),
            productType = raw.string("productType") ?: return null,
            consumable = raw.boolean("consumable"),
            context = (raw["context"] as? JsonObject)?.let(::decodeContext),
            featureAllowances = (raw["featureAllowances"] as? JsonArray)
                .orEmpty().mapNotNull(::decodeAllowance),
            licensingPublicKey = raw.string("licensingPublicKey"),
        )
    }.getOrNull()

    private fun JsonObject.string(key: String): String? =
        (this[key] as? JsonPrimitive)?.contentOrNull

    private fun encodeAllowance(allowance: StoredFeatureAllowance): JsonObject = JsonObject(buildMap {
        put("featureId", JsonPrimitive(allowance.featureId))
        put("type", JsonPrimitive(allowance.type))
        put("unlimited", JsonPrimitive(allowance.unlimited))
        allowance.allowance?.let { put("allowance", JsonPrimitive(it)) }
    })

    private fun decodeAllowance(raw: kotlinx.serialization.json.JsonElement): StoredFeatureAllowance? {
        val allowance = raw as? JsonObject ?: return null
        return StoredFeatureAllowance(
            featureId = allowance.string("featureId") ?: return null,
            type = allowance.string("type") ?: return null,
            unlimited = allowance.boolean("unlimited"),
            allowance = (allowance["allowance"] as? JsonPrimitive)?.doubleOrNull,
        )
    }

    private fun encodeContext(context: StoredPurchaseContext): JsonObject = JsonObject(buildMap {
        context.placementId?.let { put("placementId", JsonPrimitive(it)) }
        context.experienceId?.let { put("experienceId", JsonPrimitive(it)) }
        context.experienceVersion?.let { put("experienceVersion", JsonPrimitive(it)) }
        context.price?.let { put("price", JsonPrimitive(it.toPlainString())) }
        context.displayPrice?.let { put("displayPrice", JsonPrimitive(it)) }
    })

    private fun decodeContext(raw: JsonObject) = StoredPurchaseContext(
        raw.string("placementId"),
        raw.string("experienceId"),
        raw.string("experienceVersion"),
        raw.string("price")?.toBigDecimalOrNull(),
        raw.string("displayPrice"),
    )

    private fun JsonObject.boolean(key: String): Boolean =
        (this[key] as? JsonPrimitive)?.booleanOrNull ?: false
}

internal class InMemoryPurchaseEvidenceStore : PurchaseEvidenceStore {
    private val entries = linkedMapOf<String, PurchaseEvidence>()
    private val bindings = linkedMapOf<StoredPurchaseBindingKey, StoredPurchaseBinding>()
    private val mappings = linkedMapOf<StoredProductIdentity, StoredProductMapping>()
    private val productMappingInstallationLock = Any()
    @Volatile private var productMappingsChangedListener: (() -> Unit)? = null
    override fun load(): Map<String, PurchaseEvidence> = synchronized(entries) { entries.toMap() }
    override fun upsert(evidence: PurchaseEvidence): Boolean = synchronized(entries) {
        entries[evidence.purchaseToken] = evidence
        true
    }
    override fun loadBindings(): List<StoredPurchaseBinding> = synchronized(bindings) {
        bindings.values.toList()
    }
    override fun upsertBinding(binding: StoredPurchaseBinding): Boolean = synchronized(bindings) {
        bindings[StoredPurchaseBindingKey(binding.obfuscatedAccountId, binding.productIdentity)] = binding
        true
    }
    override fun loadProductMappings(): List<StoredProductMapping> = synchronized(mappings) {
        mappings.values.toList()
    }
    override fun upsertProductMapping(mapping: StoredProductMapping): Boolean =
        synchronized(productMappingInstallationLock) {
            synchronized(mappings) { mappings[mapping.productIdentity] = mapping }
            productMappingsChangedListener?.invoke()
            true
        }
    override fun setProductMappingsChangedListener(listener: (() -> Unit)?) {
        productMappingsChangedListener = listener
    }
}
