package ai.nuxie.sdk.presentation

import ai.nuxie.sdk.runtime.NativeSemanticNode
import ai.nuxie.sdk.runtime.NuxieSemanticTree
import org.junit.Assert.*
import org.junit.Test

class ExperienceSemanticIndexTest {
    @Test fun `unsigned native ids never collide with host id and remain stable across updates`() {
        val index = ExperienceSemanticIndex()
        index.update(tree(node(0xffff_ffffL)))
        val first = index.entries.keys.single()
        assertTrue(first > 0)
        index.update(tree(node(0xffff_ffffL).copy(label = "Updated")))
        assertEquals(first, index.entries.keys.single())
        index.clearOccurrence()
        index.update(tree(node(0xffff_ffffL)))
        assertNotEquals(first, index.entries.keys.single())
        assertNull(index.entries[first])
    }

    @Test fun `native fields are associated once and hidden ancestors suppress descendants`() {
        val index = ExperienceSemanticIndex()
        val field = node(1).copy(role = 6)
        assertThrows(IllegalArgumentException::class.java) { index.update(tree(field)) }
        index.update(tree(field, node(2), node(3).copy(stateFlags = 256), node(4).copy(parentId = 3)), setOf(1))
        assertEquals(listOf(2L), index.entries.values.map { it.node.id })
    }

    @Test fun `invalid hierarchy cannot replace the committed index`() {
        val index = ExperienceSemanticIndex()
        index.update(tree(node(10)))
        val original = index.entries
        assertThrows(IllegalArgumentException::class.java) { index.update(tree(node(1).copy(parentId = 2))) }
        assertEquals(original, index.entries)
        assertThrows(IllegalArgumentException::class.java) {
            index.update(tree(node(1).copy(parentId = 2), node(2).copy(parentId = 1)))
        }
        assertEquals(original, index.entries)
    }

    @Test fun `children use authored sibling order and retain grouping`() {
        val index = ExperienceSemanticIndex()
        index.update(tree(node(3).copy(parentId = 1, siblingIndex = 1), node(1), node(2).copy(parentId = 1)))
        val root = index.children(null).single()
        assertEquals(1L, root.node.id)
        assertEquals(listOf(2L, 3L), index.children(root.virtualId).map { it.node.id })
    }

    @Test fun `maximum depth is resolved without recursive stack growth`() {
        val index = ExperienceSemanticIndex()
        val nodes = (1..16_384).map { id -> node(id.toLong()).copy(parentId = if (id == 1) -1 else id - 1, stateFlags = if (id == 1) 64 else 0) }
        index.update(NuxieSemanticTree(1, 1, nodes.reversed()))
        assertEquals(16_384, index.entries.size)
        assertTrue(index.entries.values.all { it.node.stateFlags and 64 != 0 })
        assertEquals(1L, index.children(null).single().node.id)
    }

    @Test fun `reading order includes native fields once and suppresses hidden subtrees`() {
        val index = ExperienceSemanticIndex()
        index.update(tree(
            node(5).copy(parentId = 1, siblingIndex = 2),
            node(3).copy(parentId = 1, siblingIndex = 1, role = 6),
            node(1).copy(role = 10),
            node(2).copy(parentId = 1, siblingIndex = 0),
            node(8).copy(siblingIndex = 1, stateFlags = 256),
            node(9).copy(parentId = 8, role = 6),
        ), setOf(3, 9))
        assertEquals(listOf(1L, 2L, 3L, 5L), index.readingOrder)
        assertFalse(index.entries.values.any { it.node.id == 3L || it.node.id == 9L })
        index.clearOccurrence()
        assertTrue(index.readingOrder.isEmpty())
    }

    private fun tree(vararg nodes: NativeSemanticNode) = NuxieSemanticTree(1, 1, nodes.toList())
    private fun node(id: Long) = NativeSemanticNode(id, -1, 0, 1, 0, 0, 0, 1,
        0f, 0f, 10f, 10f, "Continue", "", "")
}
