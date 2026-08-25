package ai.nuxie.sdk.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NuxieViewModelCatalogTest {
    @Test
    fun `maps the complete JNI catalog construction shape`() {
        val native = NativeViewModelCatalog(
            schemas = arrayOf(
                NativeViewModelSchema(
                    index = 0,
                    name = "Checkout",
                    firstProperty = 0,
                    propertyCount = 2,
                    firstAuthoredInstance = 0,
                    authoredInstanceCount = 1,
                    defaultAuthoredInstance = 0,
                    isGlobal = true,
                ),
                NativeViewModelSchema(
                    index = 1,
                    name = "Address",
                    firstProperty = 2,
                    propertyCount = 1,
                    firstAuthoredInstance = 1,
                    authoredInstanceCount = 0,
                    defaultAuthoredInstance = -1,
                    isGlobal = false,
                ),
            ),
            properties = arrayOf(
                NativeViewModelProperty(0, 0, "status", 5, -1, arrayOf("idle", "busy")),
                NativeViewModelProperty(0, 1, "address", 9, 1, emptyArray()),
                NativeViewModelProperty(1, 0, "city", 1, -1, emptyArray()),
            ),
            authoredInstances = arrayOf(
                NativeViewModelAuthoredInstance(0, 0, "Default checkout"),
            ),
        )

        val catalog = native.toViewModelCatalog()

        assertEquals(2, catalog.schemas.size)
        assertEquals(0 until 2, catalog.schemas[0].propertyRange)
        assertEquals(0 until 1, catalog.schemas[0].authoredInstanceRange)
        assertEquals(0, catalog.schemas[0].defaultAuthoredInstance)
        assertTrue(catalog.schemas[0].isGlobal)
        assertNull(catalog.schemas[1].defaultAuthoredInstance)
        assertFalse(catalog.schemas[1].isGlobal)
        assertEquals(NuxieViewModelPropertyKind.ENUM, catalog.properties[0].kind)
        assertEquals(listOf("idle", "busy"), catalog.properties[0].enumLabels)
        assertEquals(1, catalog.properties[1].referencedSchemaIndex)
        assertEquals("Default checkout", catalog.authoredInstances.single().name)
    }
}
