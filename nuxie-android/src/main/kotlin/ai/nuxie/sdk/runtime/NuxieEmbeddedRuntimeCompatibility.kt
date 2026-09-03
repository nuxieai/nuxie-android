package ai.nuxie.sdk.runtime

/**
 * The compatibility declaration shared by the embedded runtime and the
 * publisher backend that produces its Rive/Luau inputs.
 *
 * This is intentionally distinct from the native runtime's build source
 * revision, which is provenance rather than a release-format contract.
 */
internal object NuxieEmbeddedRuntimeCompatibility {
    const val SOURCE_REVISION = "753fcb19fc1d6219cabbd95a7694ca1d13ae2bd8"
    const val LUAU_REVISION = "rive_0_36"
    val LUAU_BYTECODE_VERSIONS = setOf(3, 6)
    const val SCENE_FORMAT_MAJOR = 7
    const val SCENE_FORMAT_MINOR = 3
    val CAPABILITIES = setOf("rive", "text-input")
}
