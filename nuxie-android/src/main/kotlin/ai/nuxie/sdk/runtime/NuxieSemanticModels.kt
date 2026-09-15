package ai.nuxie.sdk.runtime

/** Values from the pinned nux_capi.generated.h semantic ABI. */
internal object NativeSemanticRole {
    const val BUTTON = 1
    const val LINK = 2
    const val CHECKBOX = 3
    const val SWITCH_CONTROL = 4
    const val SLIDER = 5
    const val TEXT_FIELD = 6
    const val TEXT = 7
    const val IMAGE = 8
    const val LIST = 10
    const val RADIO_GROUP = 16
    const val RADIO_BUTTON = 17
}

internal object NativeSemanticState {
    const val SELECTED = 1 shl 1
    const val CHECKED = 1 shl 2
    const val TOGGLED = 1 shl 4
    const val DISABLED = 1 shl 6
    const val HIDDEN = 1 shl 8
    const val OBSCURED = 1 shl 12
}

/** Copied C ABI node. IDs retain all unsigned 32 bits; these are not Android virtual IDs. */
internal data class NativeSemanticNode(
    val id: Long,
    val parentId: Int,
    val siblingIndex: Int,
    val role: Int,
    val stateFlags: Int,
    val traitFlags: Int,
    val headingLevel: Int,
    val actions: Int,
    val minX: Float,
    val minY: Float,
    val maxX: Float,
    val maxY: Float,
    val label: String,
    val value: String,
    val hint: String,
)

internal interface NuxieSemanticNative {
    fun enableSemantics(player: Long): Int = error("enableSemantics is not implemented")
    fun captureSemantics(player: Long): NativeCallResult<Long> = error("captureSemantics is not implemented")
    fun semanticInfo(snapshot: Long): NativeCallResult<LongArray> = error("semanticInfo is not implemented")
    fun semanticNode(snapshot: Long, index: Int): NativeCallResult<NativeSemanticNode> = error("semanticNode is not implemented")
    fun semanticNodeForTextRun(player: Long, snapshot: Long, name: String): NativeCallResult<Long> =
        error("semanticNodeForTextRun is not implemented")
    fun freeSemantics(snapshot: Long): Int = error("freeSemantics is not implemented")
    fun validateSemantics(player: Long, snapshot: Long): Int = error("validateSemantics is not implemented")
    fun queueSemanticAction(player: Long, snapshot: Long, nodeId: Long, action: Int): Int = error("queueSemanticAction is not implemented")
}

/** UI-safe data only. The native capture that authorizes actions stays on the runtime lane. */
internal class NuxieSemanticTree(
    val renderRevision: Long,
    val treeVersion: Long,
    nodes: List<NativeSemanticNode>,
) {
    // Native captures retain each node's own flags. Native editors bypass semantic
    // action admission, so every consumer needs the same ancestor-disabled state.
    val nodes: List<NativeSemanticNode> = inheritDisabledState(nodes)

    private fun inheritDisabledState(nodes: List<NativeSemanticNode>): List<NativeSemanticNode> {
        require(nodes.size <= 16_384) { "Semantic tree exceeds native node limit" }
        val byId = nodes.associateBy { it.id }
        require(byId.size == nodes.size) { "Duplicate semantic node identity" }
        val disabled = mutableMapOf<Long, Boolean>()
        for (node in nodes) {
            val path = mutableListOf<NativeSemanticNode>()
            val visiting = mutableSetOf<Long>()
            var current: NativeSemanticNode? = node
            while (current != null && current.id !in disabled) {
                require(visiting.add(current.id)) { "Cyclic semantic hierarchy" }
                path += current
                current = if (current.parentId == -1) null else
                    requireNotNull(byId[current.parentId.toLong() and 0xffff_ffffL]) { "Missing semantic ancestor" }
            }
            var inherited = current?.let { disabled.getValue(it.id) } ?: false
            for (item in path.asReversed()) {
                inherited = inherited || item.stateFlags and NativeSemanticState.DISABLED != 0
                disabled[item.id] = inherited
            }
        }
        return nodes.map { node ->
            if (disabled.getValue(node.id)) node.copy(stateFlags = node.stateFlags or NativeSemanticState.DISABLED)
            else node
        }
    }
}

/** Lane-confined ownership of a presented capture, including atomic copy failure cleanup. */
internal class NuxieSemanticSnapshot private constructor(
    private var handle: Long?,
    private val native: NuxieSemanticNative,
    val tree: NuxieSemanticTree,
) {
    fun validate(player: Long): Int = native.validateSemantics(player, requireHandle())

    fun queueAction(player: Long, nodeId: Long, action: Int): Int =
        native.queueSemanticAction(player, requireHandle(), nodeId, action)

    /** Missing means no visible semantic field for this authored root run in this capture. */
    fun nodeForTextRun(player: Long, name: String): NativeSemanticNode? {
        val result = native.semanticNodeForTextRun(player, requireHandle(), name)
        if (result.status == 3) return null
        if (result.status != 0) throw NuxieRuntimeCallException("associate semantic text field", result.status)
        val id = checkNotNull(result.value) { "Native semantic association returned no identity" }
        return checkNotNull(tree.nodes.singleOrNull { it.id == id && it.role == NativeSemanticRole.TEXT_FIELD }) {
            "Native semantic association returned an absent or non-field node"
        }
    }

    fun close() {
        val owned = handle ?: return
        handle = null
        val status = native.freeSemantics(owned)
        if (status != 0) throw NuxieRuntimeCallException("free semantic snapshot", status)
    }

    private fun requireHandle() = checkNotNull(handle) { "Semantic snapshot is closed" }

    companion object {
        fun capture(player: Long, native: NuxieSemanticNative): NuxieSemanticSnapshot {
            val handle = native.captureSemantics(player).required("capture semantics")
            try {
                val info = native.semanticInfo(handle).required("read semantic info")
                check(info.size == 3 && info[2] in 0..16_384) { "Invalid semantic snapshot dimensions" }
                val nodes = List(info[2].toInt()) { index ->
                    native.semanticNode(handle, index).required("read semantic node")
                }
                return NuxieSemanticSnapshot(handle, native, NuxieSemanticTree(info[0], info[1], nodes))
            } catch (error: Throwable) {
                try {
                    val status = native.freeSemantics(handle)
                    if (status != 0) throw NuxieRuntimeCallException("free semantic snapshot", status)
                } catch (cleanup: Throwable) { error.addSuppressed(cleanup) }
                throw error
            }
        }

        private fun <T> NativeCallResult<T>.required(operation: String): T {
            if (status != 0) throw NuxieRuntimeCallException(operation, status)
            return checkNotNull(value) { "Native runtime $operation returned no value" }
        }
    }
}
