package ai.nuxie.sdk.presentation

import ai.nuxie.sdk.runtime.NativeViewModelWrite
import ai.nuxie.sdk.runtime.NuxieViewModelCatalog
import ai.nuxie.sdk.runtime.NuxieViewModelMutationKind
import ai.nuxie.sdk.runtime.NuxieViewModelPropertyKind

/** Resolves only environment slots and authored globals declared by this artifact. */
internal class ExperienceEnvironmentBindings(private val catalog: NuxieViewModelCatalog) {
    data class ThemeSelection(val schemaIndex: Int, val authoredInstanceIndex: Int?)

    private val colorScheme = catalog.schemas.firstOrNull { it.name == "env" && it.isGlobal }?.let { env ->
        catalog.properties.firstOrNull { it.schemaIndex == env.index && it.name == "colorScheme" }
    }

    fun colorSchemeMutation(scheme: ExperienceColorScheme): NativeViewModelWrite? {
        val property = colorScheme?.takeIf { it.kind == NuxieViewModelPropertyKind.ENUM } ?: return null
        val index = property.enumLabels.indexOf(scheme.label).takeIf { it >= 0 } ?: return null
        return NativeViewModelWrite(NuxieViewModelMutationKind.SET_ENUM, "colorScheme", integerValue = index.toLong())
    }

    /** Viewport values describe this page's logical surface, as in iOS setLayoutSizeLocked. */
    fun viewportMutations(width: Float, height: Float): List<NativeViewModelWrite> {
        val env = catalog.schemas.firstOrNull { it.name == "env" && it.isGlobal } ?: return emptyList()
        return listOf("width" to width, "height" to height).mapNotNull { (path, value) ->
            catalog.properties.firstOrNull { it.schemaIndex == env.index && it.name == path &&
                it.kind == NuxieViewModelPropertyKind.NUMBER }?.let {
                NativeViewModelWrite(NuxieViewModelMutationKind.SET_NUMBER, path, numberValue = value)
            }
        }
    }

    /** A null authored index asks the runtime for the schema default, as on iOS. */
    fun themeSelection(scheme: ExperienceColorScheme): ThemeSelection? {
        val schema = catalog.schemas.firstOrNull { it.name == THEME_GLOBAL_NAME && it.isGlobal } ?: return null
        val instance = catalog.authoredInstances.firstOrNull { it.schemaIndex == schema.index && it.name == scheme.label }
        return ThemeSelection(schema.index, instance?.index)
    }

    companion object {
        const val THEME_GLOBAL_NAME = "Theme"
    }
}
