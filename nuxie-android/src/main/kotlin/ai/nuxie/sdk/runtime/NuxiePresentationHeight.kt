package ai.nuxie.sdk.runtime

import kotlinx.serialization.json.*

/** One height-mode instance per player, confined to that player's native lane. */
internal class NuxiePresentationHeight {
    private data class Contract(val globalName: String, val propertyName: String, val fixed: Double, val hug: Double)
    private companion object {
        val contract: Contract by lazy {
            val schema = checkNotNull(NuxiePresentationHeight::class.java.getResourceAsStream("/ai/nuxie/sdk/runtime/presentation-schema.json")) {
                "Missing presentation schema resource"
            }.use { Json.parseToJsonElement(it.bufferedReader().readText()).jsonObject }
            val property = schema.getValue("property").jsonObject
            check(schema.getValue("version").jsonPrimitive.int == 1 && property.getValue("type").jsonPrimitive.content == "number") {
                "Unsupported presentation schema"
            }
            val values = property.getValue("values").jsonObject
            Contract(schema.getValue("globalName").jsonPrimitive.content, property.getValue("name").jsonPrimitive.content,
                values.getValue("fixed").jsonPrimitive.double, values.getValue("hug").jsonPrimitive.double)
        }
    }
    private var resolved = false
    private var instance: NuxieRuntimeViewModelState? = null
    var measuresContent = false
        private set

    fun apply(file: NuxieRuntimeFile, player: NuxieRuntimePlayer, fitContent: Boolean, width: Float, height: Float): Boolean {
        require(width.isFinite() && width > 0 && height.isFinite() && height > 0)
        if (!resolved) {
            val catalog = file.viewModelCatalog()
            val schema = catalog.schemas.singleOrNull { it.isGlobal && it.name == contract.globalName }
            if (schema != null && catalog.properties.any {
                it.schemaIndex == schema.index && it.name == contract.propertyName && it.kind == NuxieViewModelPropertyKind.NUMBER
            }) {
                val created = file.newViewModel(schema.index)
                try { player.bindGlobalViewModel(contract.globalName, created) }
                catch (error: Throwable) { created.close(); throw error }
                instance = created
            }
            resolved = true
        }
        player.setLayoutSize(width, height)
        instance?.setValue(contract.propertyName, NuxieViewModelScalarValue.NumberValue(if (fitContent) contract.hug else contract.fixed))
        measuresContent = fitContent && instance != null
        return instance != null
    }

    fun close() {
        instance?.close()
        instance = null
        measuresContent = false
    }
}
