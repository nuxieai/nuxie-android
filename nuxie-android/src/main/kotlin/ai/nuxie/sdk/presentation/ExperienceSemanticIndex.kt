package ai.nuxie.sdk.presentation

import ai.nuxie.sdk.runtime.NativeSemanticRole
import ai.nuxie.sdk.runtime.NativeSemanticState
import ai.nuxie.sdk.runtime.NativeSemanticNode
import ai.nuxie.sdk.runtime.NuxieSemanticTree

/** UI-owned identity map. IDs are never recycled for a different native node or occurrence. */
internal class ExperienceSemanticIndex {
    data class Entry(val virtualId: Int, val node: NativeSemanticNode, val parent: Int?)

    private var nextId = 1L
    private val identities = mutableMapOf<Long, Int>()
    var entries: Map<Int, Entry> = emptyMap()
        private set
    private var childrenByParent: Map<Int?, List<Entry>> = emptyMap()
    var readingOrder: List<Long> = emptyList()
        private set
    var tree: NuxieSemanticTree? = null
        private set

    fun clearOccurrence() {
        identities.clear()
        entries = emptyMap()
        childrenByParent = emptyMap()
        tree = null
        readingOrder = emptyList()
    }

    fun update(incoming: NuxieSemanticTree, nativeFieldIds: Set<Long> = emptySet()) {
        require(incoming.nodes.size <= 16_384) { "Semantic tree exceeds native node limit" }
        val byId = incoming.nodes.associateBy { it.id }
        require(byId.size == incoming.nodes.size) { "Duplicate semantic node identity" }
        require(incoming.nodes.all { it.id in 0..0xffff_ffffL }) { "Invalid semantic node identity" }
        require(incoming.nodes.filter { it.role == NativeSemanticRole.TEXT_FIELD }.all { it.id in nativeFieldIds }) {
            "Semantic text fields require an associated native editable control"
        }
        data class Placement(val hidden: Boolean, val represented: Boolean, val parent: Long?)
        val placements = mutableMapOf<Long, Placement>()
        // Resolve ancestors iteratively, once per node: deep authored groups must not
        // overflow the UI stack or turn every frame into a quadratic traversal.
        for (node in incoming.nodes) {
            val path = mutableListOf<NativeSemanticNode>()
            val visiting = mutableSetOf<Long>()
            var current: NativeSemanticNode? = node
            while (current != null && current.id !in placements) {
                require(visiting.add(current.id)) { "Cyclic semantic hierarchy" }
                path += current
                current = if (current.parentId == -1) null else {
                    val id = current.parentId.toLong() and 0xffff_ffffL
                    requireNotNull(byId[id]) { "Missing semantic ancestor" }
                }
            }
            for (item in path.asReversed()) {
                val parentId = if (item.parentId == -1) null else item.parentId.toLong() and 0xffff_ffffL
                val parent = parentId?.let { placements.getValue(it) }
                val hidden = item.stateFlags and NativeSemanticState.HIDDEN != 0 || parent?.hidden == true
                placements[item.id] = Placement(hidden, !hidden && item.id !in nativeFieldIds,
                    if (parent?.represented == true) parentId else parent?.parent)
            }
        }
        val visible = byId.filterKeys { !placements.getValue(it).hidden }
        val pendingIdentities = identities.filterKeys { it in byId }.toMutableMap()
        var pendingNext = nextId
        for (id in visible.keys) if (id !in pendingIdentities) {
            check(pendingNext <= Int.MAX_VALUE) { "Accessibility virtual identity space exhausted" }
            pendingIdentities[id] = pendingNext++.toInt()
        }
        val updated = visible.values.filter { placements.getValue(it.id).represented }.associate { node ->
            val id = pendingIdentities.getValue(node.id)
            val parent = placements.getValue(node.id).parent?.let { pendingIdentities.getValue(it) }
            id to Entry(id, node, parent)
        }
        val orderedChildren = visible.values.groupBy { it.parentId }.mapValues { (_, siblings) ->
            siblings.sortedWith(compareBy({ it.siblingIndex }, { pendingIdentities.getValue(it.id) }))
        }
        val order = mutableListOf<Long>()
        val pending = ArrayDeque<NativeSemanticNode>()
        orderedChildren[-1].orEmpty().asReversed().forEach(pending::addLast)
        while (pending.isNotEmpty()) {
            val node = pending.removeLast()
            order += node.id
            if (node.id != 0xffff_ffffL) {
                orderedChildren[node.id.toInt()].orEmpty().asReversed().forEach(pending::addLast)
            }
        }
        identities.clear()
        identities.putAll(pendingIdentities)
        nextId = pendingNext
        entries = updated
        childrenByParent = updated.values.groupBy { it.parent }.mapValues { (_, siblings) ->
            siblings.sortedWith(compareBy({ it.node.siblingIndex }, { it.virtualId }))
        }
        readingOrder = order
        tree = incoming
    }

    fun children(parent: Int?): List<Entry> = childrenByParent[parent].orEmpty()
}
