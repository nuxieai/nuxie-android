package ai.nuxie.sdk.presentation

import ai.nuxie.sdk.runtime.NuxieViewModelCatalog
import ai.nuxie.sdk.runtime.NuxieViewModelMutationKind
import ai.nuxie.sdk.runtime.NuxieViewModelPropertyKind
import android.content.res.Configuration
import org.junit.Assert.*
import org.junit.Test

class ExperienceEnvironmentBindingsTest {
    @Test fun `night mode is dark only for NIGHT_YES regardless of device type`() {
        for (type in listOf(Configuration.UI_MODE_TYPE_NORMAL, Configuration.UI_MODE_TYPE_CAR)) {
            assertEquals(ExperienceColorScheme.DARK,
                ExperienceColorScheme.fromUiMode(type or Configuration.UI_MODE_NIGHT_YES))
            for (mode in listOf(Configuration.UI_MODE_NIGHT_NO, Configuration.UI_MODE_NIGHT_UNDEFINED)) {
                assertEquals(ExperienceColorScheme.LIGHT, ExperienceColorScheme.fromUiMode(type or mode))
            }
        }
    }

    @Test fun `color scheme writes its label index in the artifact's enum order`() {
        for (labels in listOf(listOf("light", "dark"), listOf("dark", "light"))) {
            val bindings = ExperienceEnvironmentBindings(catalog(labels = labels))
            for (scheme in ExperienceColorScheme.entries) {
                val write = checkNotNull(bindings.colorSchemeMutation(scheme))
                assertEquals(NuxieViewModelMutationKind.SET_ENUM, write.kind)
                assertEquals("colorScheme", write.path)
                assertEquals(labels.indexOf(scheme.label).toLong(), write.integerValue)
            }
        }
    }

    @Test fun `color scheme skips absent slots wrong types and missing labels`() {
        for (catalog in listOf(
            catalog().copy(properties = emptyList()),
            catalog(kind = NuxieViewModelPropertyKind.STRING),
            catalog(labels = listOf("day", "night")),
            catalog(envGlobal = false),
            catalog().copy(schemas = emptyList()),
        )) {
            val bindings = ExperienceEnvironmentBindings(catalog)
            for (scheme in ExperienceColorScheme.entries) assertNull(bindings.colorSchemeMutation(scheme))
        }
    }

    @Test fun `Theme selects only its own named authored instances`() {
        val bindings = ExperienceEnvironmentBindings(catalog().copy(authoredInstances = listOf(
            NuxieViewModelCatalog.AuthoredInstance(0, 9, "dark"),
            NuxieViewModelCatalog.AuthoredInstance(1, 0, "light"),
            NuxieViewModelCatalog.AuthoredInstance(1, 1, "dark"),
        )))
        assertEquals(ExperienceEnvironmentBindings.ThemeSelection(1, 0), bindings.themeSelection(ExperienceColorScheme.LIGHT))
        assertEquals(ExperienceEnvironmentBindings.ThemeSelection(1, 1), bindings.themeSelection(ExperienceColorScheme.DARK))
    }

    @Test fun `a missing named instance falls back to the schema default even with no authored instances`() {
        for (instances in listOf(emptyList(), listOf(
            NuxieViewModelCatalog.AuthoredInstance(1, 0, "light"),
            NuxieViewModelCatalog.AuthoredInstance(1, 1, null),
        ))) {
            val bindings = ExperienceEnvironmentBindings(catalog().copy(authoredInstances = instances))
            assertEquals(ExperienceEnvironmentBindings.ThemeSelection(1, null), bindings.themeSelection(ExperienceColorScheme.DARK))
        }
    }

    @Test fun `Theme is absent unless the file declares a global with that exact name`() {
        for (catalog in listOf(catalog(themeName = "Brand"), catalog(themeGlobal = false),
            catalog().copy(schemas = emptyList()))) {
            val bindings = ExperienceEnvironmentBindings(catalog)
            for (scheme in ExperienceColorScheme.entries) assertNull(bindings.themeSelection(scheme))
        }
    }

    private fun catalog(
        labels: List<String> = listOf("light", "dark"),
        kind: NuxieViewModelPropertyKind = NuxieViewModelPropertyKind.ENUM,
        envGlobal: Boolean = true,
        themeName: String = "Theme",
        themeGlobal: Boolean = true,
    ) = NuxieViewModelCatalog(
        listOf(
            NuxieViewModelCatalog.Schema(0, "env", 0..0, IntRange.EMPTY, null, envGlobal),
            NuxieViewModelCatalog.Schema(1, themeName, IntRange.EMPTY, IntRange.EMPTY, null, themeGlobal),
        ),
        listOf(NuxieViewModelCatalog.Property(0, 0, "colorScheme", kind, null, labels)),
        emptyList(),
    )
}
