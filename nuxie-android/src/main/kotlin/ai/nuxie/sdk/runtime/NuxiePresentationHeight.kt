package ai.nuxie.sdk.runtime

/** One height-mode instance per player, confined to that player's native lane. */
internal class NuxiePresentationHeight {
    // Temporary until the coordinator names the shared presentation-schema.json commit.
    private companion object {
        const val GLOBAL_NAME = "\$nuxie.presentation"
        const val PROPERTY_NAME = "heightMode"
        const val FIXED = 0.0
        const val HUG = 2.0
    }
    private var resolved = false
    private var instance: NuxieRuntimeViewModelState? = null
    var measuresContent = false
        private set

    fun apply(file: NuxieRuntimeFile, player: NuxieRuntimePlayer, fitContent: Boolean, width: Float, height: Float): Boolean {
        require(width.isFinite() && width > 0 && height.isFinite() && height > 0)
        if (!resolved) {
            val catalog = file.viewModelCatalog()
            val schema = catalog.schemas.singleOrNull { it.isGlobal && it.name == GLOBAL_NAME }
            if (schema != null && catalog.properties.any {
                it.schemaIndex == schema.index && it.name == PROPERTY_NAME && it.kind == NuxieViewModelPropertyKind.NUMBER
            }) {
                val created = checkNotNull(file.newAuthoredViewModel(GLOBAL_NAME, schema.defaultAuthoredInstance ?: -1))
                try { player.setGlobalViewModel(GLOBAL_NAME, created) }
                catch (error: Throwable) { created.close(); throw error }
                instance = created
            }
            resolved = true
        }
        player.setLayoutSize(width, height)
        instance?.setValue(PROPERTY_NAME, NuxieViewModelScalarValue.NumberValue(if (fitContent) HUG else FIXED))
        measuresContent = fitContent && instance != null
        return instance != null
    }

    fun close() {
        instance?.close()
        instance = null
        measuresContent = false
    }
}
