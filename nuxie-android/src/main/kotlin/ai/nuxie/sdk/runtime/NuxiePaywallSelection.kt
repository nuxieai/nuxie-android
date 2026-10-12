package ai.nuxie.sdk.runtime

/** SDK selection projection; only identities frozen at signed binding may be selected. */
internal class NuxiePaywallSelection(val products: List<Product>) {
    data class Product(val nativeId: Long, val handle: Long, val productId: String?)

    fun apply(root: Long, native: NuxieTypedRuntimeNative, snapshot: NativeViewModelSnapshot, change: NuxieViewModelChange): Boolean {
        val list = snapshot.atPath("paywall/products")?.takeIf { it.kind == NuxieViewModelPropertyKind.LIST.nativeValue }?.listItemIds?.toList()
        val id = (change.value as? NuxieViewModelValue.Bytes)?.value?.decodeToString()
        val validList = list != null && list.distinct().size == list.size &&
            list.all { occurrence -> products.any { it.nativeId == occurrence } }
        val matches = if (validList && id != null) products.filter {
            it.nativeId in checkNotNull(list) && it.productId == id
        } else emptyList()
        val selected = matches.singleOrNull()
        fun write(handle: Long, value: NativeViewModelWrite) {
            check(native.mutateViewModel(handle, value) == 0) { "Paywall selection write failed: ${value.path}" }
        }
        snapshot.atPath("paywall/selectedIndex")?.takeIf { it.kind == NuxieViewModelPropertyKind.NUMBER.nativeValue }?.let {
            val index = selected?.let { product -> checkNotNull(list).indexOf(product.nativeId) } ?: -1
            if (it.numberValue != index.toFloat()) write(root, NativeViewModelWrite(
                NuxieViewModelMutationKind.SET_NUMBER, "paywall/selectedIndex", numberValue = index.toFloat()))
        }
        if (selected != null && snapshot.atPath("paywall/selectedProduct")?.referencedInstanceId != selected.nativeId) {
            write(root, NativeViewModelWrite(NuxieViewModelMutationKind.SET_VIEW_MODEL,
                "paywall/selectedProduct", relatedViewModel = selected.handle))
        }
        for (product in products) {
            val flag = snapshot.values.singleOrNull { it.ownerInstanceId == product.nativeId && it.name == "isSelected" }
            val isSelected = product == selected
            if (flag?.kind == NuxieViewModelPropertyKind.BOOLEAN.nativeValue && flag.boolValue != isSelected) write(product.handle,
                NativeViewModelWrite(NuxieViewModelMutationKind.SET_BOOLEAN, "isSelected", boolValue = isSelected))
        }
        return selected != null
    }

    fun selectionWrite(snapshot: NativeViewModelSnapshot, changes: List<NuxieViewModelChange>): NuxieViewModelChange? {
        val field = snapshot.atPath("paywall/selectedProductId") ?: return null
        return changes.lastOrNull { it.origin == NuxieViewModelChangeOrigin.RUNTIME &&
            it.ownerInstanceId.toLong() == field.ownerInstanceId && it.propertyIndex.toLong() == field.propertyIndex }
    }

    companion object {
        fun create(projection: NuxieViewModelListProjection, children: List<Long>, native: NuxieTypedRuntimeNative,
                   catalog: NuxieViewModelCatalog, rootSchema: Int): NuxiePaywallSelection? {
            if (projection.listPath != "paywall/products" || projection.selectedItemPath != "paywall/selectedProduct" ||
                projection.itemSchemaName != "PaywallProduct" || runCatching {
                    catalog.propertyAtPath(rootSchema, "paywall/selectedProductId").kind
                }.getOrNull() != NuxieViewModelPropertyKind.STRING) return null
            return NuxiePaywallSelection(projection.items.zip(children).map { (item, handle) ->
                val identity = native.snapshotViewModel(handle)
                check(identity.status == 0) { "Cannot identify signed paywall product" }
                Product(checkNotNull(identity.value).rootInstanceId, handle,
                    (item.values["productId"] as? NuxieViewModelScalarValue.StringValue)?.value)
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
