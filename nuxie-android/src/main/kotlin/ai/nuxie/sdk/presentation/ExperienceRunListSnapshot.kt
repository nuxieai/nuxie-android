package ai.nuxie.sdk.presentation

import ai.nuxie.sdk.runtime.NativeViewModelSnapshot
import ai.nuxie.sdk.runtime.NativeViewModelWrite
import ai.nuxie.sdk.runtime.NuxieRuntimeFile
import ai.nuxie.sdk.runtime.NuxieRuntimeViewModelState
import ai.nuxie.sdk.runtime.NuxieViewModelMutationKind
import ai.nuxie.sdk.runtime.NuxieViewModelPropertyKind
import kotlinx.serialization.json.*

/** References use checkpoint-local ordinals, never process-local native identities. */
internal data class ExperienceRunListSnapshot(val nodes: List<Node>) {
    data class OriginStep(val path: String, val index: Int)
    data class Items(val path: String, val items: List<Int>)
    data class Reference(val path: String, val target: Int)
    data class Node(val schema: Int, val origin: List<OriginStep>?,
        val fields: JsonArray, val lists: List<Items>, val references: List<Reference>)

    fun encode(): JsonObject = buildJsonObject {
        put("nodes", JsonArray(nodes.map { node -> buildJsonObject {
            put("schema", node.schema)
            put("origin", node.origin?.let { origin -> JsonArray(origin.map { step -> buildJsonObject {
                put("path", step.path); put("index", step.index)
            } }) } ?: JsonNull)
            put("fields", node.fields)
            put("lists", JsonArray(node.lists.map { list -> buildJsonObject {
                put("path", list.path); put("items", JsonArray(list.items.map(::JsonPrimitive)))
            } }))
            put("references", JsonArray(node.references.map { edge -> buildJsonObject {
                put("path", edge.path); put("target", edge.target)
            } }))
        } }))
    }

    val journeyLists: Map<String, JsonElement> get() {
        fun assign(objectValues: MutableMap<String, JsonElement>, parts: List<String>, value: JsonElement) {
            val key = parts.firstOrNull() ?: return
            if (parts.size == 1) { objectValues[key] = value; return }
            val child = (objectValues[key] as? JsonObject)?.toMutableMap() ?: mutableMapOf()
            assign(child, parts.drop(1), value)
            objectValues[key] = JsonObject(child)
        }
        fun objectValue(index: Int, ancestors: Set<Int>): JsonObject? {
            if (index !in nodes.indices || index in ancestors) return null
            val node = nodes[index]
            val result = mutableMapOf<String, JsonElement>()
            for ((path, value) in ExperienceRunSnapshot(node.fields).journeyValues) {
                assign(result, path.split('/'), value)
            }
            for (list in node.lists) {
                assign(result, list.path.split('/'), JsonArray(list.items.mapNotNull {
                    objectValue(it, ancestors + index)
                }))
            }
            return JsonObject(result)
        }
        return nodes.firstOrNull()?.lists.orEmpty().associate { list ->
            list.path to JsonArray(list.items.mapNotNull { objectValue(it, setOf(0)) })
        }
    }

    /** Runs before publishing the new owner or binding any screen. */
    fun restore(file: NuxieRuntimeFile, root: NuxieRuntimeViewModelState) {
        val initial = root.nativeSnapshot()
        require(nodes.isNotEmpty() && nodes[0].schema.toLong() ==
            initial.instances.single { it.id == initial.rootInstanceId }.schemaIndex) { "Invalid run list root" }
        require(nodes.all { node -> node.schema >= 0 &&
            node.references.all { it.target in nodes.indices } &&
            node.lists.all { list -> list.items.all { it in nodes.indices } }
        }) { "Invalid run list edges" }
        val references = mutableListOf(root)
        val owned = mutableListOf<NuxieRuntimeViewModelState>()
        var failure: Throwable? = null
        try {
            // Acquire authored rows before any list or reference changes its original address.
            for (node in nodes.drop(1)) {
                val reference = node.origin?.let { origin ->
                    require(origin.isNotEmpty()) { "Invalid row origin" }
                    var owner = root
                    for (step in origin) {
                        val ids = listEntries(owner.nativeSnapshot()).firstOrNull { it.first == step.path }?.second
                        require(ids != null && step.index in ids.indices) { "Invalid row origin" }
                        owner = owner.acquireListItem(step.path, step.index, ids[step.index]).also(owned::add)
                    }
                    owner
                } ?: file.newSchemaViewModel(node.schema).also(owned::add)
                val snapshot = reference.nativeSnapshot()
                require(snapshot.instances.single { it.id == snapshot.rootInstanceId }.schemaIndex == node.schema.toLong()) {
                    "Invalid row schema"
                }
                references += reference
            }
            val identities = references.map { it.nativeSnapshot().rootInstanceId }
            require(identities.toSet().size == identities.size) { "Distinct rows must retain distinct identities" }
            for ((index, node) in nodes.withIndex()) {
                for (edge in node.references) references[index].restoreReference(edge.path, references[edge.target])
            }
            for ((index, node) in nodes.withIndex()) {
                val reference = references[index]
                reference.restoreWrites(ExperienceRunSnapshot(node.fields).writes())
                for (list in node.lists) {
                    val current = checkNotNull(listEntries(reference.nativeSnapshot())
                        .firstOrNull { it.first == list.path }?.second) { "Invalid list path" }.toMutableList()
                    for ((position, child) in list.items.withIndex()) {
                        val identity = identities[child]
                        if (current.getOrNull(position) == identity) continue
                        val from = (position until current.size).firstOrNull { current[it] == identity }
                        if (from != null) {
                            reference.restoreWrites(listOf(NativeViewModelWrite(NuxieViewModelMutationKind.LIST_MOVE,
                                list.path, index = from.toLong(), secondIndex = position.toLong())))
                            current.add(position, current.removeAt(from))
                        } else {
                            reference.insertListItem(list.path, position, references[child])
                            current.add(position, identity)
                        }
                    }
                    while (current.size > list.items.size) {
                        reference.restoreWrites(listOf(NativeViewModelWrite(NuxieViewModelMutationKind.LIST_REMOVE,
                            list.path, index = current.lastIndex.toLong())))
                        current.removeAt(current.lastIndex)
                    }
                }
            }
        } catch (error: Throwable) {
            failure = error
            throw error
        } finally {
            // Lists and reference properties retain their own ownership after these handles close.
            var cleanupFailure: Throwable? = null
            for (item in owned.asReversed()) {
                try { item.close() } catch (error: Throwable) {
                    val primary = failure ?: cleanupFailure
                    if (primary != null) primary.addSuppressed(error) else cleanupFailure = error
                }
            }
            cleanupFailure?.let { throw it }
        }
    }

    companion object {
        fun decode(value: JsonElement): ExperienceRunListSnapshot = ExperienceRunListSnapshot(
            value.jsonObject.getValue("nodes").jsonArray.map { item ->
                val node = item.jsonObject
                Node(node.getValue("schema").jsonPrimitive.int,
                    node.getValue("origin").takeUnless { it == JsonNull }?.jsonArray?.map { step ->
                        OriginStep(step.jsonObject.getValue("path").jsonPrimitive.content,
                            step.jsonObject.getValue("index").jsonPrimitive.int)
                    }, node.getValue("fields").jsonArray,
                    node.getValue("lists").jsonArray.map { list -> Items(
                        list.jsonObject.getValue("path").jsonPrimitive.content,
                        list.jsonObject.getValue("items").jsonArray.map { it.jsonPrimitive.int }) },
                    node.getValue("references").jsonArray.map { edge -> Reference(
                        edge.jsonObject.getValue("path").jsonPrimitive.content,
                        edge.jsonObject.getValue("target").jsonPrimitive.int) })
            })

        fun authoredOrigins(snapshot: NativeViewModelSnapshot): Map<Long, List<OriginStep>> {
            val origins = mutableMapOf(snapshot.rootInstanceId to emptyList<OriginStep>())
            fun visit(id: Long, origin: List<OriginStep>) {
                for ((path, children) in listEntries(snapshot, id)) {
                    for ((index, child) in children.withIndex()) {
                        if (child in origins) continue
                        val address = origin + OriginStep(path, index)
                        origins[child] = address
                        visit(child, address)
                    }
                }
            }
            visit(snapshot.rootInstanceId, emptyList())
            return origins
        }

        fun capture(snapshot: NativeViewModelSnapshot, origins: Map<Long, List<OriginStep>>,
            authoredIds: Set<Long>): ExperienceRunListSnapshot? {
            if (listEntries(snapshot).isEmpty()) return null
            val instances = snapshot.instances.associateBy { it.id }
            val values = snapshot.values.groupBy { it.ownerInstanceId }
            val indices = mutableMapOf<Long, Int>()
            val nodes = mutableListOf<Node?>()
            val identities = mutableListOf<Long>()
            fun visit(id: Long): Int {
                indices[id]?.let { return it }
                val index = nodes.size
                indices[id] = index
                nodes += null
                identities += id
                val lists = listEntries(snapshot, id).map { (path, children) -> Items(path, children.map { visit(it) }) }
                fun retainedRows(owner: Long, ancestors: Set<Long>) {
                    if (owner in ancestors) return
                    for (entry in values[owner].orEmpty()) {
                        if (entry.kind != NuxieViewModelPropertyKind.VIEW_MODEL.nativeValue || entry.referencedInstanceId == 0L) continue
                        val child = entry.referencedInstanceId
                        if (child in origins || child !in authoredIds) visit(child)
                        else retainedRows(child, ancestors + owner)
                    }
                }
                retainedRows(id, emptySet())
                val schema = checkNotNull(instances[id]).schemaIndex
                require(schema in 0..Int.MAX_VALUE.toLong()) { "Invalid row schema" }
                nodes[index] = Node(schema.toInt(), origins[id], ExperienceRunSnapshot.captureFields(snapshot, id),
                    lists, emptyList())
                return index
            }
            visit(snapshot.rootInstanceId)
            return ExperienceRunListSnapshot(nodes.mapIndexed { index, item ->
                val node = checkNotNull(item)
                val references = mutableListOf<Reference>()
                fun edges(owner: Long, prefix: String, ancestors: Set<Long>) {
                    if (owner in ancestors) return
                    for (entry in values[owner].orEmpty()) {
                        if (entry.kind != NuxieViewModelPropertyKind.VIEW_MODEL.nativeValue || entry.referencedInstanceId == 0L) continue
                        val path = prefix + entry.name
                        val target = indices[entry.referencedInstanceId]
                        if (target != null) references += Reference(path, target)
                        else edges(entry.referencedInstanceId, "$path/", ancestors + owner)
                    }
                }
                edges(identities[index], "", emptySet())
                node.copy(references = references)
            })
        }

        private fun listEntries(snapshot: NativeViewModelSnapshot, root: Long = snapshot.rootInstanceId): List<Pair<String, List<Long>>> {
            val values = snapshot.values.groupBy { it.ownerInstanceId }
            val result = mutableListOf<Pair<String, List<Long>>>()
            fun visit(id: Long, prefix: String, ancestors: Set<Long>) {
                if (id in ancestors) return
                for (entry in values[id].orEmpty()) {
                    val path = prefix + entry.name
                    when (NuxieViewModelPropertyKind.fromNativeValue(entry.kind)) {
                        NuxieViewModelPropertyKind.VIEW_MODEL -> visit(entry.referencedInstanceId, "$path/", ancestors + id)
                        NuxieViewModelPropertyKind.LIST -> result += path to entry.listItemIds.toList()
                        else -> Unit
                    }
                }
            }
            visit(root, "", emptySet())
            return result
        }
    }
}
