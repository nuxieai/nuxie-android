package ai.nuxie.sdk.runtime

/** Authored graph data only: native handles and renderer-owned values never cross lanes. */
internal class NuxieViewModelArchive private constructor(
    private val rootId: Long,
    private val instances: List<Instance>,
    private val aliases: Map<String, Long>,
) {
    private data class Instance(val id: Long, val schemaIndex: Int, val schemaName: String?, val values: List<NativeViewModelSnapshotValue>)

    fun restore(
        native: NuxieTypedRuntimeNative,
        file: NuxieRuntimeFile,
        artboard: NuxieRuntimeArtboard,
    ): NuxieRuntimeViewModelState {
        val catalog = value(native.viewModelCatalog(file.requireHandle()), "read retained graph catalog").toViewModelCatalog()
        val handles = linkedMapOf<Long, Long>()
        fun write(handle: Long, mutation: NativeViewModelWrite) {
            val status = native.mutateViewModel(handle, mutation)
            check(status == STATUS_OK) { "Restore '${mutation.path}' failed: $status" }
        }
        try {
            // Allocate before linking, preserving shared children and cycles without replaying events.
            for (instance in instances) {
                val schema = catalog.schemas.singleOrNull { it.index == instance.schemaIndex }
                require(schema != null && instance.schemaName != null && schema.name == instance.schemaName) {
                    "Retained view-model schema does not match this release"
                }
                val handle = if (instance.id == rootId) value(native.newDefaultViewModel(artboard.requireHandle()), "create retained root")
                    else value(native.newViewModel(file.requireHandle(), instance.schemaIndex, null), "create retained child")
                handles[instance.id] = handle
                require(value(native.viewModelRootSchemaIndex(handle), "check retained schema") == instance.schemaIndex.toLong())
            }
            for (instance in instances) {
                val handle = handles.getValue(instance.id)
                for (stored in instance.values) {
                    val property = catalog.properties.singleOrNull {
                        it.schemaIndex == instance.schemaIndex && it.index.toLong() == stored.propertyIndex && it.name == stored.name
                    }
                    require(property != null && property.kind.nativeValue == stored.kind) { "Retained property does not match this release" }
                    val kind = when (property.kind) {
                        NuxieViewModelPropertyKind.STRING -> NuxieViewModelMutationKind.SET_STRING
                        NuxieViewModelPropertyKind.NUMBER -> NuxieViewModelMutationKind.SET_NUMBER
                        NuxieViewModelPropertyKind.BOOLEAN -> NuxieViewModelMutationKind.SET_BOOLEAN
                        NuxieViewModelPropertyKind.COLOR -> NuxieViewModelMutationKind.SET_COLOR
                        NuxieViewModelPropertyKind.ENUM -> NuxieViewModelMutationKind.SET_ENUM
                        NuxieViewModelPropertyKind.LIST_INDEX -> NuxieViewModelMutationKind.SET_LIST_INDEX
                        NuxieViewModelPropertyKind.VIEW_MODEL -> {
                            if (stored.referencedInstanceId == 0L) {
                                val fresh = value(native.snapshotViewModel(handle), "check empty reference").values.single { it.name == stored.name }
                                require(fresh.referencedInstanceId == 0L) { "Cannot restore an empty reference over an authored child" }
                                continue
                            }
                            write(handle, NativeViewModelWrite(NuxieViewModelMutationKind.SET_VIEW_MODEL, stored.name,
                                relatedViewModel = handles.getValue(stored.referencedInstanceId)))
                            continue
                        }
                        NuxieViewModelPropertyKind.LIST -> {
                            write(handle, NativeViewModelWrite(NuxieViewModelMutationKind.LIST_CLEAR, stored.name))
                            stored.listItemIds.forEachIndexed { index, id ->
                                write(handle, NativeViewModelWrite(NuxieViewModelMutationKind.LIST_INSERT, stored.name,
                                    relatedViewModel = handles.getValue(id), index = index.toLong()))
                            }
                            continue
                        }
                        // Triggers are events, not retained values. Resource properties remain file-owned.
                        else -> continue
                    }
                    write(handle, NativeViewModelWrite(kind, stored.name, stored.bytesValue,
                        stored.numberValue, stored.integerValue, stored.boolValue))
                }
            }
            val root = handles.getValue(rootId)
            val status = native.bindViewModel(artboard.requireHandle(), root)
            check(status == STATUS_OK) { "Bind retained view model failed: $status" }
            val newIds = handles.mapValues { (_, handle) -> value(native.snapshotViewModel(handle), "identify restored graph").rootInstanceId }
            return NuxieRuntimeViewModelState(root, handles.filterKeys { it != rootId }.values.toList(), native, catalog,
                instances.single { it.id == rootId }.schemaIndex, aliases.mapValues { (_, old) -> newIds.getValue(old) })
        } catch (error: Throwable) {
            handles.values.toList().asReversed().forEach { handle ->
                runCatching { check(native.freeViewModel(handle) == STATUS_OK) }.exceptionOrNull()?.let(error::addSuppressed)
            }
            throw error
        }
    }

    companion object {
        private const val STATUS_OK = 0
        private val reservedRoot = setOf("env", "safeArea", "screen", "nuxieTextInputs", "fontScale")

        fun capture(snapshot: NativeViewModelSnapshot, schemaNames: Map<Long, String>, aliases: Map<String, Long>): NuxieViewModelArchive {
            val byOwner = snapshot.values.groupBy { it.ownerInstanceId }
            fun children(value: NativeViewModelSnapshotValue): List<Long> = when (value.kind) {
                NuxieViewModelPropertyKind.VIEW_MODEL.nativeValue -> listOfNotNull(value.referencedInstanceId.takeUnless { it == 0L })
                NuxieViewModelPropertyKind.LIST.nativeValue -> value.listItemIds.toList()
                else -> emptyList()
            }
            fun reserved(value: NativeViewModelSnapshotValue) =
                (value.ownerInstanceId == snapshot.rootInstanceId && value.name in reservedRoot) ||
                    (value.name == "nuxieLayoutPaint" && value.kind == NuxieViewModelPropertyKind.VIEW_MODEL.nativeValue)
            val excluded = mutableSetOf<Long>()
            val pending = ArrayDeque(snapshot.values.filter(::reserved).flatMap(::children))
            while (pending.isNotEmpty()) {
                val id = pending.removeFirst()
                if (excluded.add(id)) byOwner[id].orEmpty().flatMap(::children).forEach(pending::addLast)
            }
            val authoredValues = snapshot.values.filter { value ->
                !reserved(value) && value.ownerInstanceId !in excluded && children(value).none { it in excluded }
            }.groupBy { it.ownerInstanceId }
            val reachable = linkedSetOf(snapshot.rootInstanceId)
            pending.add(snapshot.rootInstanceId)
            while (pending.isNotEmpty()) {
                authoredValues[pending.removeFirst()].orEmpty().flatMap(::children).forEach { if (reachable.add(it)) pending.add(it) }
            }
            val instances = snapshot.instances.filter { it.id in reachable }.map {
                require(it.schemaIndex in 0..Int.MAX_VALUE.toLong())
                Instance(it.id, it.schemaIndex.toInt(), schemaNames[it.schemaIndex], authoredValues[it.id].orEmpty().map { value ->
                    value.copy(bytesValue = value.bytesValue.copyOf(), listItemIds = value.listItemIds.copyOf())
                })
            }
            require(instances.map { it.id }.toSet() == reachable) { "Retained graph references an absent instance" }
            return NuxieViewModelArchive(snapshot.rootInstanceId, instances, aliases.filterValues { it in reachable })
        }

        private fun <T> value(result: NativeCallResult<T>, operation: String): T {
            check(result.status == STATUS_OK) { "$operation failed: ${result.status}" }
            return checkNotNull(result.value) { "$operation returned no value" }
        }
    }
}
