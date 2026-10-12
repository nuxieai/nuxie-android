package ai.nuxie.sdk.runtime

import ai.nuxie.sdk.logging.NuxieLog as Log

/** SDK selection projection; only identities frozen at signed binding may be selected. */
internal class NuxiePaywallSelection(val products: List<Product>) {
    data class Identity(val productId: String?, val placementId: String?)
    data class Product(val nativeId: Long, val handle: Long, val productId: String?, val placementId: String?)
    data class SelectionWrite(val change: NuxieViewModelChange, val byPlacement: Boolean)
    fun identities(): Map<Long, Identity> = products.associate { it.nativeId to Identity(it.productId, it.placementId) }

    fun apply(root: Long, native: NuxieTypedRuntimeNative, snapshot: NativeViewModelSnapshot, selection: SelectionWrite): Boolean? {
        val list = snapshot.atPath("paywall/products")?.takeIf { it.kind == NuxieViewModelPropertyKind.LIST.nativeValue }?.listItemIds?.toList()
        val id = (selection.change.value as? NuxieViewModelValue.Bytes)?.value?.decodeToString()
        val validList = list != null && list.distinct().size == list.size &&
            list.all { occurrence -> products.any { it.nativeId == occurrence } }
        val matches = if (validList && id != null) products.filter {
            it.nativeId in checkNotNull(list) && (if (selection.byPlacement) it.placementId else it.productId) == id
        } else emptyList()
        val selected = matches.singleOrNull()
        val writes = mutableListOf<Pair<Long, NativeViewModelWrite>>()
        fun write(handle: Long, value: NativeViewModelWrite) { writes += handle to value }
        snapshot.atPath("paywall/selectedIndex")?.takeIf { it.kind == NuxieViewModelPropertyKind.NUMBER.nativeValue }?.let {
            val index = selected?.let { product -> checkNotNull(list).indexOf(product.nativeId) } ?: -1
            if (it.numberValue != index.toFloat()) write(root, NativeViewModelWrite(
                NuxieViewModelMutationKind.SET_NUMBER, "paywall/selectedIndex", numberValue = index.toFloat()))
        }
        val otherPath = if (selection.byPlacement) "paywall/selectedProductId" else "paywall/selectedPlacementId"
        snapshot.atPath(otherPath)?.takeIf { it.kind == NuxieViewModelPropertyKind.STRING.nativeValue }?.let {
            val otherID = (if (selection.byPlacement) selected?.productId else selected?.placementId) ?: ""
            if (it.bytesValue.decodeToString() != otherID) write(root, NativeViewModelWrite(
                NuxieViewModelMutationKind.SET_STRING, otherPath, bytesValue = otherID.encodeToByteArray()))
        }
        for (product in products) {
            val flag = snapshot.values.singleOrNull { it.ownerInstanceId == product.nativeId && it.name == "isSelected" }
            val isSelected = product == selected
            if (flag?.kind == NuxieViewModelPropertyKind.BOOLEAN.nativeValue && flag.boolValue != isSelected) write(product.handle,
                NativeViewModelWrite(NuxieViewModelMutationKind.SET_BOOLEAN, "isSelected", boolValue = isSelected))
        }
        if (selected != null && snapshot.atPath("paywall/selectedProduct")?.referencedInstanceId != selected.nativeId) {
            write(root, NativeViewModelWrite(NuxieViewModelMutationKind.SET_VIEW_MODEL,
                "paywall/selectedProduct", relatedViewModel = selected.handle))
        }
        // The purchase link is last. If feedback is refused, hide the purchase
        // target rather than charge the old plan; restore display values best-effort.
        val applied = mutableListOf<Pair<Long, NativeViewModelWrite>>()
        try {
            for ((handle, mutation) in writes) {
                val owner = if (handle == root) snapshot.atPath(mutation.path)?.ownerInstanceId
                    else products.single { it.handle == handle }.nativeId
                val previous = snapshot.values.singleOrNull {
                    it.ownerInstanceId == owner && it.name == mutation.path.substringAfterLast('/')
                }
                check(native.mutateViewModel(handle, mutation) == 0) { "Paywall selection write rejected: ${mutation.path}" }
                if (previous != null && mutation.kind != NuxieViewModelMutationKind.SET_VIEW_MODEL) {
                    applied += handle to mutation.copy(bytesValue = previous.bytesValue, numberValue = previous.numberValue, boolValue = previous.boolValue)
                }
            }
        } catch (error: Throwable) {
            Log.w("NuxiePaywallSelection", "Native paywall selection was rejected; clearing the purchase target", error)
            for ((handle, previous) in applied.asReversed()) {
                runCatching { check(native.mutateViewModel(handle, previous) == 0) }
                    .exceptionOrNull()?.let { Log.w("NuxiePaywallSelection", "Could not restore paywall display state", it) }
            }
            return false
        }
        return selected != null
    }

    fun selectionWrite(snapshot: NativeViewModelSnapshot, changes: List<NuxieViewModelChange>): SelectionWrite? {
        val fields = listOf("paywall/selectedProductId", "paywall/selectedPlacementId").mapIndexedNotNull { index, path ->
            snapshot.atPath(path)?.let { it to (index == 1) }
        }
        for (change in changes.asReversed()) {
            if (change.origin != NuxieViewModelChangeOrigin.RUNTIME) continue
            val field = fields.singleOrNull { (field, _) ->
                change.ownerInstanceId.toLong() == field.ownerInstanceId && change.propertyIndex.toLong() == field.propertyIndex
            } ?: continue
            return SelectionWrite(change, field.second)
        }
        return null
    }

    companion object {
        fun create(projection: NuxieViewModelListProjection, children: List<Long>, native: NuxieTypedRuntimeNative,
                   catalog: NuxieViewModelCatalog, rootSchema: Int): NuxiePaywallSelection? {
            if (projection.listPath != "paywall/products" || projection.selectedItemPath != "paywall/selectedProduct" ||
                projection.itemSchemaName != "PaywallProduct" ||
                listOf("paywall/selectedProductId", "paywall/selectedPlacementId").none { path ->
                    runCatching { catalog.propertyAtPath(rootSchema, path).kind }.getOrNull() == NuxieViewModelPropertyKind.STRING
                }) return null
            return NuxiePaywallSelection(projection.items.zip(children).map { (item, handle) ->
                val identity = native.snapshotViewModel(handle)
                check(identity.status == 0) { "Cannot identify signed paywall product" }
                Product(checkNotNull(identity.value).rootInstanceId, handle,
                    (item.values["productId"] as? NuxieViewModelScalarValue.StringValue)?.value,
                    (item.values["placementId"] as? NuxieViewModelScalarValue.StringValue)?.value)
            })
        }
    }
}

private fun NativeViewModelSnapshot.atPath(path: String): NativeViewModelSnapshotValue? {
    var owner = rootInstanceId
    val segments = path.split('/')
    for (segment in segments.dropLast(1)) {
        val link = values.singleOrNull { it.ownerInstanceId == owner && it.name == segment && it.kind == NuxieViewModelPropertyKind.VIEW_MODEL.nativeValue }
            ?: return null
        owner = link.referencedInstanceId
    }
    return values.singleOrNull { it.ownerInstanceId == owner && it.name == segments.last() }
}
