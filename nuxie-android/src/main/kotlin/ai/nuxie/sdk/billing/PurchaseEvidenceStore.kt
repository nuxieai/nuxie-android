package ai.nuxie.sdk.billing

import ai.nuxie.sdk.NuxieEnvironment
import ai.nuxie.sdk.identity.CustomerKind
import ai.nuxie.sdk.logging.NuxieLog as Log
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.math.BigDecimal
import java.security.MessageDigest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
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
    testStore: Boolean = false,
): File {
    // Keep the pre-rename directory stable so existing purchase evidence remains discoverable.
    return File(File(filesDirectory, "nuxie-commerce"), purchaseAuthorityScope(apiKey, environment, testStore))
}

internal fun purchaseAuthorityScope(
    apiKey: String,
    environment: NuxieEnvironment,
    testStore: Boolean = false,
): String =
    MessageDigest.getInstance("SHA-256")
        // Preserve the existing native scope exactly; only Test Store adds a domain.
        .digest(("$apiKey\u0000${environment.name}" + if (testStore) "\u0000test-store" else "").encodeToByteArray())
        .joinToString("") { "%02x".format(it) }

internal data class StoredPurchaseContext(
    val placementId: String? = null,
    val experienceId: String? = null,
    val experienceVersion: String? = null,
    val price: BigDecimal? = null,
    val displayPrice: String? = null,
    val journeyId: String? = null,
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
    val outcomeEventId: String? = null,
    /**
     * The checkout this binding was written for has not had its outcome yet.
     * Only such a binding correlates a Play delivery with a checkout (its
     * customer, outcome event and completion); once the checkout ends, or its
     * completion is delivered, the binding keeps only catalog facts.
     */
    val awaitingCheckout: Boolean = true,
    /**
     * The purchases Play already owned when the checkout began. None of them
     * is the checkout's own purchase, so none is ever matched to this binding.
     */
    val priorTokens: Set<String> = emptySet(),
    /**
     * The checkout this binding was written for, an id made when it began. A
     * binding is retired only by name: by its own checkout, or by the completion
     * of the purchase that matched it. One without an id correlates no delivery.
     */
    val checkoutId: String? = null,
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

/**
 * One owed `/purchase` send: the outbox entry for a purchase token. Its reason,
 * requester and customer kind are recorded when the send is admitted and are
 * never recomputed, so every retry, including after a restart, sends exactly
 * what was admitted. Every admission gets a fresh [admissionId]; each send
 * carries the id it sent, and an answer changes the entry only while the id
 * still matches.
 */
internal data class PurchaseSend(
    val reason: PurchaseSyncReason,
    val distinctId: String,
    val customerKind: CustomerKind,
    /** Every stored send has one; [UNRECORDED_ADMISSION_ID] marks a send built without an admission. */
    val admissionId: String = UNRECORDED_ADMISSION_ID,
) {
    companion object {
        const val UNRECORDED_ADMISSION_ID = "unrecorded"

        /** A new admission: this send with a fresh admission id. */
        fun admit(reason: PurchaseSyncReason, distinctId: String, customerKind: CustomerKind) =
            PurchaseSend(reason, distinctId, customerKind, java.util.UUID.randomUUID().toString())
    }

    /** The same reason, requester and kind as a new admission. */
    fun readmitted(): PurchaseSend = admit(reason, distinctId, customerKind)
}

/** The Play facts a send of an entry carries, besides its admission's reason, requester and kind. */
internal data class SendPayload(
    val productId: String?,
    val basePlanId: String?,
    val purchaseOptionId: String?,
    val offerId: String?,
    val productType: String?,
    val obfuscatedAccountId: String?,
)

/**
 * One Play purchase's durable outbox entry, keyed by its purchase token: the
 * only purchase state the SDK keeps. Once the server answers it, it stays as
 * the purchase's record with its own flags (answered, acknowledged or consumed
 * with Play, checkout completion delivered), so a later delivery of the same
 * purchase reads them instead of sending, finishing or delivering again. It
 * stores no signed purchase: what is sent is the token and its Play facts.
 */
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
    /**
     * The customer whose checkout made this purchase: checkout correlation for
     * events only. Null when no checkout's context came with it (a purchase a
     * scan, a Restore or an already-owned checkout found), and then no
     * `$purchase_completed` is ever emitted for it.
     */
    val buyerDistinctId: String? = null,
    /** The customer the server last attached this purchase to. Only the server's 200 sets it. */
    val ownerDistinctId: String? = null,
    /** The server last refused this purchase: it is never sent again on its own, only by a Restore. */
    val refused: Boolean = false,
    /** The outbox entry: the send still owed for this purchase, or null when none is. */
    val pendingSend: PurchaseSend? = null,
    val context: StoredPurchaseContext? = null,
    /** Purchase-token-scoped allowance snapshot; null is unresolved and empty carries no Feature allowances. */
    val pinnedFeatureAllowances: List<StoredFeatureAllowance>? = null,
    val acknowledged: Boolean,
    val consumed: Boolean = false,
    val syncAttempts: Int = 0,
    /**
     * Epoch millis when the owed entry is due again after its last failed send;
     * null when it has not failed since it was admitted.
     */
    val retryDueAtMillis: Long? = null,
    val completionAttempts: Int = 0,
    val firstSeenMillis: Long,
    val consumable: Boolean = false,
    val catalogResolved: Boolean = false,
    val completionEmitted: Boolean = false,
    /**
     * Whether the purchase's latest accepted admission was reported as
     * `$purchase_synced` for the customer it was accepted for. Telemetry only:
     * each accepted admission is reported once.
     */
    val syncedEventEmitted: Boolean = false,
    val syncedCustomerId: String? = null,
    val nuxieManaged: Boolean = false,
    /** Play's signature on this purchase verified against the catalog's licensing key. */
    val signatureVerified: Boolean = false,
    val authorityScope: String? = null,
    val backendSyncedAtMillis: Long? = null,
    val checkoutCompletionEventId: String? = null,
    /** The checkout whose binding (or flight) this purchase matched; its completion retires that binding. */
    val checkoutId: String? = null,
) {
    /** The server accepted this purchase for its owner. */
    val synced: Boolean get() = ownerDistinctId != null

    /** What a send of this entry carries; a write that changes it is a new admission. */
    val sendPayload: SendPayload
        get() = SendPayload(
            storeProductIds.firstOrNull(),
            basePlanId,
            purchaseOptionId,
            offerId,
            productType,
            obfuscatedAccountId,
        )

    /** Accepted and still to be acknowledged or consumed with Play by Nuxie. */
    val needsManagedCompletion: Boolean
        get() = nuxieManaged && catalogResolved && if (consumable) !consumed else !acknowledged

    /**
     * Only the purchase's record: the server answered it (it is never sent
     * again), it is done with Play, and its checkout completion is delivered or
     * not owed. These flags, not any state kept beside the entry, decide what a
     * later delivery of the purchase still does.
     */
    val isSettled: Boolean
        get() = pendingSend == null && (synced || refused) &&
            !(synced && needsManagedCompletion) &&
            (completionEmitted || buyerDistinctId == null || refused)

    /**
     * Another customer's settled record is only the record of their purchase,
     * so it gives way to [requester]'s checkout: the entry starts afresh.
     * Any other entry of another customer stays theirs.
     */
    fun givesWayTo(requester: String?): Boolean =
        requester != null && isSettled && (ownerDistinctId ?: buyerDistinctId) != requester
}

internal interface PurchaseEvidenceStore {
    /** False when the store cannot be read (it fails closed): it cannot save either. */
    suspend fun isReadable(): Boolean = true
    /**
     * The same reads, telling a store that cannot be read apart from an empty
     * one: null when it cannot be read. The outbox reads only through these, so
     * a failed read is never taken for an empty store.
     */
    suspend fun loadOrNull(): Map<String, PurchaseEvidence>? = load()
    suspend fun loadBindingsOrNull(): List<StoredPurchaseBinding>? = loadBindings()
    suspend fun loadProductMappingsOrNull(): List<StoredProductMapping>? = loadProductMappings()
    /**
     * At launch, before any outbox work. A file that was read but does not
     * decode is corrupt: it is moved aside to a quarantine name, never read
     * again, and an empty file takes its place. False only when a file cannot
     * be read right now (an IO error, or storage that is still locked before
     * the first unlock): that is not corruption, nothing is moved, and the
     * outbox is off for the session; the next launch tries again.
     */
    suspend fun openAtLaunch(): Boolean = isReadable()
    suspend fun load(): Map<String, PurchaseEvidence>
    suspend fun upsert(evidence: PurchaseEvidence): Boolean
    suspend fun loadBindings(): List<StoredPurchaseBinding> = emptyList()
    suspend fun upsertBinding(binding: StoredPurchaseBinding): Boolean = true
    /**
     * End the binding of checkout [checkoutId], if one still awaits it. The
     * check and the write are one step, so it never ends another checkout's
     * binding. True when nothing was left to end or it was ended.
     */
    suspend fun retireCheckoutBinding(checkoutId: String): Boolean =
        loadBindings()
            .filter { it.awaitingCheckout && it.checkoutId == checkoutId }
            .all { upsertBinding(it.copy(awaitingCheckout = false)) }
    suspend fun loadProductMappings(): List<StoredProductMapping> = emptyList()
    /**
     * An installation that changes the catalog (a new product or a changed
     * mapping) invokes the listener, once, before a later mapping installation
     * may begin. Installing the same mapping again changes nothing and does not.
     * A checkout installing its own product's mapping passes [catalogChange]
     * false: that is no catalog change, and the listener is not invoked.
     */
    suspend fun upsertProductMapping(mapping: StoredProductMapping, catalogChange: Boolean = true): Boolean = true
    fun setProductMappingsChangedListener(listener: (suspend () -> Unit)?) = Unit
}

/** Scope-private atomic JSON store. Purchase tokens are keys, never logs or preferences. */
internal class FilePurchaseEvidenceStore(
    private val directory: File,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    /** Names the quarantined files. */
    private val nowMillis: () -> Long = System::currentTimeMillis,
) : PurchaseEvidenceStore {
    private val file = File(directory, "purchase-evidence.json")
    private val bindingsFile = File(directory, "purchase-bindings.json")
    private val catalogFile = File(directory, "purchase-catalog.json")
    private val lock = Any()
    private val productMappingInstallationLock = Mutex()
    private val json = Json { ignoreUnknownKeys = true }
    @Volatile private var productMappingsChangedListener: (suspend () -> Unit)? = null

    /** Each of the store's files must read: entries, checkout bindings and the catalog. */
    override suspend fun isReadable(): Boolean = onIo {
        synchronized(lock) {
            readUnlocked() != null && loadBindingsUnlocked() != null && loadMappingsUnlocked() != null
        }
    }

    override suspend fun openAtLaunch(): Boolean = onIo {
        synchronized(lock) {
            // Every file is opened, so each corrupt one is moved aside at this launch.
            listOf(
                openAtLaunchUnlocked(StoreFile.EVIDENCE, readEvidenceFileUnlocked()),
                openAtLaunchUnlocked(StoreFile.BINDINGS, readRowsFileUnlocked(bindingsFile, ::decodeBinding)),
                openAtLaunchUnlocked(StoreFile.CATALOG, readRowsFileUnlocked(catalogFile, ::decodeMapping)),
            ).all { it }
        }
    }

    /** The store's three files, and the names their quarantined copies take. */
    private enum class StoreFile(val fileName: String, val quarantinePrefix: String) {
        EVIDENCE("purchase-evidence.json", "purchase-evidence.unreadable-"),
        BINDINGS("purchase-bindings.json", "purchase-bindings.unreadable-"),
        CATALOG("purchase-catalog.json", "purchase-catalog.unreadable-"),
    }

    /** One read of a store file. */
    private sealed interface FileRead<out T> {
        data class Read<T>(val value: T) : FileRead<T>
        /** The bytes could not be read now: an IO error, not corruption. */
        data class Unavailable(val cause: Throwable) : FileRead<Nothing>
        /** The bytes were read but do not decode; [row] is null when the file does not parse. */
        data class Corrupt(val row: Int?, val field: String?, val cause: Throwable? = null) : FileRead<Nothing>
    }

    private fun openAtLaunchUnlocked(kind: StoreFile, read: FileRead<*>): Boolean = when (read) {
        is FileRead.Read -> true
        is FileRead.Unavailable -> false.also { logUnavailable(kind, read.cause) }
        is FileRead.Corrupt -> quarantineUnlocked(kind, read)
    }

    /**
     * Move a corrupt file aside, next to where it was, so an empty one takes its
     * place. Only the newest quarantined copy of each file is kept, so repeated
     * corruption cannot fill the disk. A file that cannot be moved stays where
     * it is, closed, and the outbox is off for the session.
     */
    private fun quarantineUnlocked(kind: StoreFile, corrupt: FileRead.Corrupt): Boolean {
        val source = File(directory, kind.fileName)
        val target = File(directory, "${kind.quarantinePrefix}${nowMillis()}.json")
        if (!runCatching { source.renameTo(target) }.getOrDefault(false)) {
            logUnavailable(kind, IllegalStateException("Could not move the unreadable file aside"))
            return false
        }
        directory.listFiles()
            ?.filter { it != target && it.name.startsWith(kind.quarantinePrefix) && it.name.endsWith(".json") }
            ?.forEach { runCatching { it.delete() } }
        logQuarantined(kind, corrupt, target)
        return true
    }

    private fun logUnavailable(kind: StoreFile, cause: Throwable) = when (kind) {
        StoreFile.EVIDENCE -> Log.w("NuxieBilling", "Could not read purchase evidence.", cause)
        StoreFile.BINDINGS -> Log.w("NuxieBilling", "Could not read purchase bindings.", cause)
        StoreFile.CATALOG -> Log.w("NuxieBilling", "Could not read purchase catalog.", cause)
    }

    /** One line per quarantine: where it failed (row -1 when the file does not parse) and where it went. */
    private fun logQuarantined(kind: StoreFile, corrupt: FileRead.Corrupt, target: File) {
        val row = Log.status("row", corrupt.row ?: -1)
        val field = Log.sensitive("field", corrupt.field)
        val path = Log.sensitive("path", target.path)
        when (kind) {
            StoreFile.EVIDENCE -> Log.w("NuxieBilling", "Quarantined an unreadable purchase evidence file.", null, row, field, path)
            StoreFile.BINDINGS -> Log.w("NuxieBilling", "Quarantined an unreadable purchase bindings file.", null, row, field, path)
            StoreFile.CATALOG -> Log.w("NuxieBilling", "Quarantined an unreadable purchase catalog file.", null, row, field, path)
        }
    }

    /** The file's text, null when there is no file, or why it cannot be read now. */
    private fun readTextUnlocked(target: File): FileRead<String?> {
        if (!target.exists()) return FileRead.Read(null)
        return try {
            FileRead.Read(target.readText())
        } catch (failure: Exception) {
            FileRead.Unavailable(failure)
        }
    }

    /** An unreadable store reads as empty: nothing unlocks early and nothing is sent. */
    override suspend fun load(): Map<String, PurchaseEvidence> = onIo { synchronized(lock) { readUnlocked().orEmpty() } }

    override suspend fun loadOrNull(): Map<String, PurchaseEvidence>? = onIo { synchronized(lock) { readUnlocked() } }

    override suspend fun loadBindingsOrNull(): List<StoredPurchaseBinding>? = onIo {
        synchronized(lock) { loadBindingsUnlocked() }
    }

    override suspend fun loadProductMappingsOrNull(): List<StoredProductMapping>? = onIo {
        synchronized(lock) { loadMappingsUnlocked() }
    }

    /** An unreadable store is never written: its file stays exactly as it is. */
    override suspend fun upsert(evidence: PurchaseEvidence): Boolean = onIo {
        synchronized(lock) {
            val entries = readUnlocked()?.toMutableMap() ?: return@synchronized false
            entries[evidence.purchaseToken] = evidence
            saveUnlocked(entries)
        }
    }

    /** Unreadable bindings read as empty and are never written over. */
    override suspend fun loadBindings(): List<StoredPurchaseBinding> = onIo {
        synchronized(lock) { loadBindingsUnlocked().orEmpty() }
    }

    override suspend fun upsertBinding(binding: StoredPurchaseBinding): Boolean = onIo {
        synchronized(lock) {
            val entries = (loadBindingsUnlocked() ?: return@synchronized false).associateByTo(linkedMapOf()) {
                StoredPurchaseBindingKey(it.obfuscatedAccountId, it.productIdentity)
            }
            entries[StoredPurchaseBindingKey(binding.obfuscatedAccountId, binding.productIdentity)] = binding
            saveBindingsUnlocked(entries.values)
        }
    }

    override suspend fun retireCheckoutBinding(checkoutId: String): Boolean = onIo {
        synchronized(lock) {
            val bindings = loadBindingsUnlocked() ?: return@synchronized false
            if (bindings.none { it.awaitingCheckout && it.checkoutId == checkoutId }) return@synchronized true
            saveBindingsUnlocked(
                bindings.map { if (it.awaitingCheckout && it.checkoutId == checkoutId) it.copy(awaitingCheckout = false) else it },
            )
        }
    }

    private fun saveBindingsUnlocked(bindings: Collection<StoredPurchaseBinding>): Boolean = runCatching {
        directory.mkdirs()
        val temporary = File(bindingsFile.parentFile, "${bindingsFile.name}.tmp")
        val encoded = JsonArray(bindings.map(::encodeBinding))
        temporary.writeText(json.encodeToString(JsonArray.serializer(), encoded))
        if (!temporary.renameTo(bindingsFile)) error("Could not publish purchase bindings")
        true
    }.onFailure { Log.w("NuxieBilling", "Could not persist purchase binding.", it) }
        .getOrDefault(false)

    /** An unreadable catalog reads as empty and is never written over. */
    override suspend fun loadProductMappings(): List<StoredProductMapping> = onIo {
        synchronized(lock) { loadMappingsUnlocked().orEmpty() }
    }

    override suspend fun upsertProductMapping(mapping: StoredProductMapping, catalogChange: Boolean): Boolean = onIo {
        productMappingInstallationLock.withLock {
            val upsert = synchronized(lock) {
                val entries = (loadMappingsUnlocked() ?: return@synchronized MappingUpsert.FAILED)
                    .associateByTo(linkedMapOf()) { it.productIdentity }
                // The same mapping again changes nothing: no write and no catalog change.
                if (entries[mapping.productIdentity] == mapping) return@synchronized MappingUpsert.UNCHANGED
                entries[mapping.productIdentity] = mapping
                if (saveArray(catalogFile, entries.values.map(::encodeMapping), "catalog mapping")) {
                    MappingUpsert.CHANGED
                } else {
                    MappingUpsert.FAILED
                }
            }
            if (upsert == MappingUpsert.CHANGED && catalogChange) {
                withContext(NonCancellable) { productMappingsChangedListener?.invoke() }
            }
            upsert != MappingUpsert.FAILED
        }
    }

    override fun setProductMappingsChangedListener(listener: (suspend () -> Unit)?) {
        productMappingsChangedListener = listener
    }

    private suspend fun <T> onIo(block: suspend () -> T): T = withContext(ioDispatcher) { block() }

    /**
     * The checkout bindings, or null when they cannot be read: a file that does
     * not parse, or a row with a field that does not decode to its type. Either
     * fails closed, as the entries do: nothing is written over the file.
     */
    private fun loadBindingsUnlocked(): List<StoredPurchaseBinding>? =
        when (val read = readRowsFileUnlocked(bindingsFile, ::decodeBinding)) {
            is FileRead.Read -> read.value
            is FileRead.Unavailable -> null.also { logUnavailable(StoreFile.BINDINGS, read.cause) }
            is FileRead.Corrupt -> null.also {
                if (read.row == null) {
                    Log.w("NuxieBilling", "Could not read purchase bindings.", read.cause)
                } else {
                    Log.w(
                        "NuxieBilling",
                        "Invalid purchase bindings row; the store stays closed until it reads.",
                        null,
                        Log.status("row", read.row),
                        Log.sensitive("field", read.field),
                    )
                }
            }
        }

    /** The catalog mappings, or null when they cannot be read, as for the bindings. */
    private fun loadMappingsUnlocked(): List<StoredProductMapping>? =
        when (val read = readRowsFileUnlocked(catalogFile, ::decodeMapping)) {
            is FileRead.Read -> read.value
            is FileRead.Unavailable -> null.also { logUnavailable(StoreFile.CATALOG, read.cause) }
            is FileRead.Corrupt -> null.also {
                if (read.row == null) {
                    Log.w("NuxieBilling", "Could not read purchase catalog.", read.cause)
                } else {
                    Log.w(
                        "NuxieBilling",
                        "Invalid purchase catalog row; the store stays closed until it reads.",
                        null,
                        Log.status("row", read.row),
                        Log.sensitive("field", read.field),
                    )
                }
            }
        }

    private fun <T> readRowsFileUnlocked(target: File, decode: (JsonObject) -> T): FileRead<List<T>> {
        val text = when (val read = readTextUnlocked(target)) {
            is FileRead.Read -> read.value ?: return FileRead.Read(emptyList())
            is FileRead.Unavailable -> return read
            is FileRead.Corrupt -> return read
        }
        val rows = try {
            json.parseToJsonElement(text).jsonArray
        } catch (failure: Exception) {
            return FileRead.Corrupt(row = null, field = null, cause = failure)
        }
        return FileRead.Read(
            rows.mapIndexed { index, raw ->
                try {
                    decode(raw as? JsonObject ?: throw MalformedField("row"))
                } catch (malformed: MalformedField) {
                    return FileRead.Corrupt(index, malformed.field)
                }
            },
        )
    }

    private fun saveArray(target: File, values: List<JsonObject>, description: String): Boolean = runCatching {
        directory.mkdirs()
        val temporary = File(target.parentFile, "${target.name}.tmp")
        temporary.writeText(json.encodeToString(JsonArray.serializer(), JsonArray(values)))
        if (!temporary.renameTo(target)) error("Could not publish purchase $description")
        true
    }.onFailure { Log.w("NuxieBilling", "Could not persist purchase evidence", it, Log.sensitive("description", description)) }
        .getOrDefault(false)

    /**
     * The stored entries, or null when the store is unreadable: a file that
     * does not parse, or a row in the current format with a field that is
     * present but does not decode to its type (an absent field stays absent).
     * Either fails closed, and nothing is written until it reads again. A row
     * in a previous SDK's format is recognised by what it lacks (no `refused`,
     * or an owed send with no admission id) and only that row is dropped; the
     * next write leaves it out. Play redelivers what it still owns, which then
     * goes through the outbox normally. Rows are named by their position,
     * never their purchase token.
     */
    private fun readUnlocked(): Map<String, PurchaseEvidence>? = when (val read = readEvidenceFileUnlocked()) {
        is FileRead.Read -> read.value
        is FileRead.Unavailable -> null.also { logUnavailable(StoreFile.EVIDENCE, read.cause) }
        is FileRead.Corrupt -> null.also {
            if (read.row == null) {
                Log.w("NuxieBilling", "Could not read purchase evidence.", read.cause)
            } else {
                Log.w(
                    "NuxieBilling",
                    "Could not read a purchase evidence row; the store stays closed until it reads.",
                    null,
                    Log.status("row", read.row),
                    Log.sensitive("field", read.field),
                )
            }
        }
    }

    private fun readEvidenceFileUnlocked(): FileRead<Map<String, PurchaseEvidence>> {
        val text = when (val read = readTextUnlocked(file)) {
            is FileRead.Read -> read.value ?: return FileRead.Read(emptyMap())
            is FileRead.Unavailable -> return read
            is FileRead.Corrupt -> return read
        }
        val rows = try {
            json.parseToJsonElement(text).jsonObject
        } catch (failure: Exception) {
            return FileRead.Corrupt(row = null, field = null, cause = failure)
        }
        val entries = linkedMapOf<String, PurchaseEvidence>()
        rows.entries.forEachIndexed { index, (token, raw) ->
            val row = raw as? JsonObject
            if (row != null && isPreviousFormat(row)) {
                Log.w("NuxieBilling", "Invalid purchase evidence row was dropped: it is in a previous format.", null, Log.status("row", index))
                return@forEachIndexed
            }
            entries[token] = try {
                decodeEvidence(row ?: throw MalformedField("row"))
            } catch (malformed: MalformedField) {
                return FileRead.Corrupt(index, malformed.field)
            }
        }
        return FileRead.Read(entries)
    }

    /** A row written before the outbox (no `refused`), or before admission ids (an owed send without one). */
    private fun isPreviousFormat(row: JsonObject): Boolean =
        "refused" !in row || (row["pendingSend"] as? JsonObject)?.let { "admissionId" !in it } == true

    private fun saveUnlocked(entries: Map<String, PurchaseEvidence>): Boolean = runCatching {
        directory.mkdirs()
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
            evidence.buyerDistinctId?.let { put("buyerDistinctId", JsonPrimitive(it)) }
            evidence.ownerDistinctId?.let { put("ownerDistinctId", JsonPrimitive(it)) }
            put("refused", JsonPrimitive(evidence.refused))
            evidence.pendingSend?.let { send ->
                put("pendingSend", JsonObject(mapOf(
                    "reason" to JsonPrimitive(send.reason.wireValue),
                    "distinctId" to JsonPrimitive(send.distinctId),
                    "customerKind" to JsonPrimitive(send.customerKind.wireValue),
                    "admissionId" to JsonPrimitive(send.admissionId),
                )))
            }
            evidence.context?.let { put("context", encodeContext(it)) }
            evidence.pinnedFeatureAllowances?.let { allowances ->
                put("pinnedFeatureAllowances", JsonArray(allowances.map(::encodeAllowance)))
            }
            put("acknowledged", JsonPrimitive(evidence.acknowledged))
            put("consumed", JsonPrimitive(evidence.consumed))
            put("syncAttempts", JsonPrimitive(evidence.syncAttempts))
            evidence.retryDueAtMillis?.let { put("retryDueAtMillis", JsonPrimitive(it)) }
            put("completionAttempts", JsonPrimitive(evidence.completionAttempts))
            put("firstSeenMillis", JsonPrimitive(evidence.firstSeenMillis))
            put("consumable", JsonPrimitive(evidence.consumable))
            put("catalogResolved", JsonPrimitive(evidence.catalogResolved))
            put("completionEmitted", JsonPrimitive(evidence.completionEmitted))
            put("syncedEventEmitted", JsonPrimitive(evidence.syncedEventEmitted))
            evidence.syncedCustomerId?.let { put("syncedCustomerId", JsonPrimitive(it)) }
            put("nuxieManaged", JsonPrimitive(evidence.nuxieManaged))
            put("signatureVerified", JsonPrimitive(evidence.signatureVerified))
            evidence.authorityScope?.let { put("authorityScope", JsonPrimitive(it)) }
            evidence.backendSyncedAtMillis?.let { put("backendSyncedAtMillis", JsonPrimitive(it)) }
            evidence.checkoutId?.let { put("checkoutId", JsonPrimitive(it)) }
            evidence.checkoutCompletionEventId?.let {
                put("checkoutCompletionEventId", JsonPrimitive(it))
            }
        },
    )

    /**
     * A row in the current format. A field that is present but does not decode
     * to its type throws [MalformedField], which makes the store unreadable; an
     * absent field (or JSON null) stays absent.
     */
    private fun decodeEvidence(raw: JsonObject): PurchaseEvidence = PurchaseEvidence(
        purchaseToken = raw.requiredString("purchaseToken"),
        packageName = raw.requiredString("packageName"),
        storeProductIds = raw.optionalStrings("storeProductIds").orEmpty(),
        nuxieProductId = raw.optionalString("nuxieProductId"),
        basePlanId = raw.optionalString("basePlanId"),
        purchaseOptionId = raw.optionalString("purchaseOptionId"),
        offerId = raw.optionalString("offerId"),
        productType = raw.optionalString("productType"),
        purchaseState = raw.requiredString("purchaseState").let { state ->
            StoredPurchaseState.entries.firstOrNull { it.name == state } ?: throw MalformedField("purchaseState")
        },
        obfuscatedAccountId = raw.optionalString("obfuscatedAccountId"),
        buyerDistinctId = raw.optionalString("buyerDistinctId"),
        ownerDistinctId = raw.optionalString("ownerDistinctId"),
        refused = raw.optionalBoolean("refused") ?: throw MalformedField("refused"),
        pendingSend = raw.optionalObject("pendingSend")?.let(::decodeSend),
        context = raw.optionalObject("context")?.let(::decodeContext),
        pinnedFeatureAllowances = raw.optionalArray("pinnedFeatureAllowances")
            ?.map { decodeAllowance(it, "pinnedFeatureAllowances") },
        acknowledged = raw.optionalBoolean("acknowledged") ?: false,
        consumed = raw.optionalBoolean("consumed") ?: false,
        syncAttempts = raw.optionalInt("syncAttempts") ?: 0,
        retryDueAtMillis = raw.optionalLong("retryDueAtMillis"),
        completionAttempts = raw.optionalInt("completionAttempts") ?: 0,
        firstSeenMillis = raw.optionalLong("firstSeenMillis") ?: throw MalformedField("firstSeenMillis"),
        consumable = raw.optionalBoolean("consumable") ?: false,
        catalogResolved = raw.optionalBoolean("catalogResolved") ?: false,
        completionEmitted = raw.optionalBoolean("completionEmitted") ?: false,
        syncedEventEmitted = raw.optionalBoolean("syncedEventEmitted") ?: false,
        syncedCustomerId = raw.optionalString("syncedCustomerId"),
        nuxieManaged = raw.optionalBoolean("nuxieManaged") ?: false,
        signatureVerified = raw.optionalBoolean("signatureVerified") ?: false,
        authorityScope = raw.optionalString("authorityScope"),
        backendSyncedAtMillis = raw.optionalLong("backendSyncedAtMillis"),
        checkoutCompletionEventId = raw.optionalString("checkoutCompletionEventId"),
        checkoutId = raw.optionalString("checkoutId"),
    )

    /** An owed send records its reason, requester, kind and admission; one without any of them is not read. */
    private fun decodeSend(raw: JsonObject): PurchaseSend {
        val reason = raw.requiredString("reason", "pendingSend.reason").let { wire ->
            PurchaseSyncReason.entries.firstOrNull { it.wireValue == wire } ?: throw MalformedField("pendingSend.reason")
        }
        val kind = raw.requiredString("customerKind", "pendingSend.customerKind").let { wire ->
            CustomerKind.entries.firstOrNull { it.wireValue == wire } ?: throw MalformedField("pendingSend.customerKind")
        }
        return PurchaseSend(
            reason = reason,
            distinctId = raw.requiredString("distinctId", "pendingSend.distinctId"),
            customerKind = kind,
            admissionId = raw.requiredString("admissionId", "pendingSend.admissionId"),
        )
    }

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
            binding.outcomeEventId?.let { put("outcomeEventId", JsonPrimitive(it)) }
            put("awaitingCheckout", JsonPrimitive(binding.awaitingCheckout))
            put("priorTokens", JsonArray(binding.priorTokens.map(::JsonPrimitive)))
            binding.checkoutId?.let { put("checkoutId", JsonPrimitive(it)) }
        },
    )

    private fun decodeBinding(raw: JsonObject): StoredPurchaseBinding = StoredPurchaseBinding(
        obfuscatedAccountId = raw.requiredString("obfuscatedAccountId"),
        distinctId = raw.requiredString("distinctId"),
        storeProductId = raw.requiredString("storeProductId"),
        nuxieProductId = raw.requiredString("nuxieProductId"),
        basePlanId = raw.optionalString("basePlanId"),
        purchaseOptionId = raw.optionalString("purchaseOptionId"),
        offerId = raw.optionalString("offerId"),
        productType = raw.requiredString("productType"),
        consumable = raw.optionalBoolean("consumable") ?: false,
        context = raw.optionalObject("context")?.let(::decodeContext),
        featureAllowances = raw.optionalArray("featureAllowances").orEmpty()
            .map { decodeAllowance(it, "featureAllowances") },
        licensingPublicKey = raw.optionalString("licensingPublicKey"),
        nuxieManaged = raw.optionalBoolean("nuxieManaged") ?: false,
        outcomeEventId = raw.optionalString("outcomeEventId"),
        // A binding stored without it awaits no checkout.
        awaitingCheckout = raw.optionalBoolean("awaitingCheckout") ?: false,
        priorTokens = raw.optionalStrings("priorTokens").orEmpty().toSet(),
        checkoutId = raw.optionalString("checkoutId"),
    )

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

    private fun decodeMapping(raw: JsonObject): StoredProductMapping = StoredProductMapping(
        storeProductId = raw.requiredString("storeProductId"),
        nuxieProductId = raw.requiredString("nuxieProductId"),
        basePlanId = raw.optionalString("basePlanId"),
        purchaseOptionId = raw.optionalString("purchaseOptionId"),
        offerId = raw.optionalString("offerId"),
        productType = raw.requiredString("productType"),
        consumable = raw.optionalBoolean("consumable") ?: false,
        context = raw.optionalObject("context")?.let(::decodeContext),
        featureAllowances = raw.optionalArray("featureAllowances").orEmpty()
            .map { decodeAllowance(it, "featureAllowances") },
        licensingPublicKey = raw.optionalString("licensingPublicKey"),
    )

    /** A field present but not of its type: the row, and so the store, is unreadable. */
    private class MalformedField(val field: String) : RuntimeException("Malformed purchase store field")

    /** A present value; JSON null reads as absent, as the encoder never writes it. */
    private fun JsonObject.present(key: String): JsonElement? = this[key]?.takeUnless { it is JsonNull }

    private fun JsonObject.optionalString(key: String, field: String = key): String? = present(key)?.let {
        (it as? JsonPrimitive)?.takeIf { primitive -> primitive.isString }?.content ?: throw MalformedField(field)
    }

    private fun JsonObject.requiredString(key: String, field: String = key): String =
        optionalString(key, field) ?: throw MalformedField(field)

    private fun JsonObject.optionalStrings(key: String): List<String>? = optionalArray(key)?.map {
        (it as? JsonPrimitive)?.takeIf { primitive -> primitive.isString }?.content ?: throw MalformedField(key)
    }

    private fun JsonObject.optionalNumber(key: String, field: String): JsonPrimitive? = present(key)?.let {
        (it as? JsonPrimitive)?.takeUnless { primitive -> primitive.isString } ?: throw MalformedField(field)
    }

    private fun JsonObject.optionalLong(key: String, field: String = key): Long? =
        optionalNumber(key, field)?.let { it.longOrNull ?: throw MalformedField(field) }

    private fun JsonObject.optionalInt(key: String, field: String = key): Int? =
        optionalNumber(key, field)?.let { it.intOrNull ?: throw MalformedField(field) }

    private fun JsonObject.optionalDouble(key: String, field: String = key): Double? =
        optionalNumber(key, field)?.let { it.doubleOrNull ?: throw MalformedField(field) }

    private fun JsonObject.optionalBoolean(key: String, field: String = key): Boolean? =
        optionalNumber(key, field)?.let { it.booleanOrNull ?: throw MalformedField(field) }

    private fun JsonObject.optionalObject(key: String): JsonObject? =
        present(key)?.let { it as? JsonObject ?: throw MalformedField(key) }

    private fun JsonObject.optionalArray(key: String): JsonArray? =
        present(key)?.let { it as? JsonArray ?: throw MalformedField(key) }

    private fun encodeAllowance(allowance: StoredFeatureAllowance): JsonObject = JsonObject(buildMap {
        put("featureId", JsonPrimitive(allowance.featureId))
        put("type", JsonPrimitive(allowance.type))
        put("unlimited", JsonPrimitive(allowance.unlimited))
        allowance.allowance?.let { put("allowance", JsonPrimitive(it)) }
    })

    private fun decodeAllowance(raw: JsonElement, field: String): StoredFeatureAllowance {
        val allowance = raw as? JsonObject ?: throw MalformedField(field)
        return StoredFeatureAllowance(
            featureId = allowance.requiredString("featureId", "$field.featureId"),
            type = allowance.requiredString("type", "$field.type"),
            unlimited = allowance.optionalBoolean("unlimited", "$field.unlimited") ?: false,
            allowance = allowance.optionalDouble("allowance", "$field.allowance"),
        )
    }

    private fun encodeContext(context: StoredPurchaseContext): JsonObject = JsonObject(buildMap {
        context.placementId?.let { put("placementId", JsonPrimitive(it)) }
        context.experienceId?.let { put("experienceId", JsonPrimitive(it)) }
        context.experienceVersion?.let { put("experienceVersion", JsonPrimitive(it)) }
        context.journeyId?.let { put("journeyId", JsonPrimitive(it)) }
        context.price?.let { put("price", JsonPrimitive(it.toPlainString())) }
        context.displayPrice?.let { put("displayPrice", JsonPrimitive(it)) }
    })

    private fun decodeContext(raw: JsonObject) = StoredPurchaseContext(
        placementId = raw.optionalString("placementId", "context.placementId"),
        experienceId = raw.optionalString("experienceId", "context.experienceId"),
        experienceVersion = raw.optionalString("experienceVersion", "context.experienceVersion"),
        price = raw.optionalString("price", "context.price")
            ?.let { it.toBigDecimalOrNull() ?: throw MalformedField("context.price") },
        displayPrice = raw.optionalString("displayPrice", "context.displayPrice"),
        journeyId = raw.optionalString("journeyId", "context.journeyId"),
    )
}

/** What writing a catalog mapping did: the catalog changes only when a mapping does. */
private enum class MappingUpsert { CHANGED, UNCHANGED, FAILED }

internal class InMemoryPurchaseEvidenceStore : PurchaseEvidenceStore {
    private val entries = linkedMapOf<String, PurchaseEvidence>()
    private val bindings = linkedMapOf<StoredPurchaseBindingKey, StoredPurchaseBinding>()
    private val mappings = linkedMapOf<StoredProductIdentity, StoredProductMapping>()
    private val productMappingInstallationLock = Mutex()
    @Volatile private var productMappingsChangedListener: (suspend () -> Unit)? = null
    override suspend fun load(): Map<String, PurchaseEvidence> = synchronized(entries) { entries.toMap() }
    override suspend fun upsert(evidence: PurchaseEvidence): Boolean = synchronized(entries) {
        entries[evidence.purchaseToken] = evidence
        true
    }
    override suspend fun loadBindings(): List<StoredPurchaseBinding> = synchronized(bindings) {
        bindings.values.toList()
    }
    override suspend fun upsertBinding(binding: StoredPurchaseBinding): Boolean = synchronized(bindings) {
        bindings[StoredPurchaseBindingKey(binding.obfuscatedAccountId, binding.productIdentity)] = binding
        true
    }
    override suspend fun retireCheckoutBinding(checkoutId: String): Boolean = synchronized(bindings) {
        for (entry in bindings.entries) {
            val binding = entry.value
            if (binding.awaitingCheckout && binding.checkoutId == checkoutId) entry.setValue(binding.copy(awaitingCheckout = false))
        }
        true
    }
    override suspend fun loadProductMappings(): List<StoredProductMapping> = synchronized(mappings) {
        mappings.values.toList()
    }
    override suspend fun upsertProductMapping(mapping: StoredProductMapping, catalogChange: Boolean): Boolean =
        productMappingInstallationLock.withLock {
            // The same mapping again changes nothing: no catalog change.
            val changed = synchronized(mappings) {
                (mappings.put(mapping.productIdentity, mapping) != mapping)
            }
            if (changed && catalogChange) withContext(NonCancellable) { productMappingsChangedListener?.invoke() }
            true
        }
    override fun setProductMappingsChangedListener(listener: (suspend () -> Unit)?) {
        productMappingsChangedListener = listener
    }
}
