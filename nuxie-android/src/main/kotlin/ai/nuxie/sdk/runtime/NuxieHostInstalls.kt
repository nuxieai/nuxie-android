package ai.nuxie.sdk.runtime

internal data class NuxieValueMarker(
    val model: String,
    val value: String,
    val marker: String,
) {
    internal fun toNative() = NativeValueMarker(
        model = model.encodeToByteArray(),
        value = value.encodeToByteArray(),
        marker = marker.encodeToByteArray(),
    )
}

internal class NativeValueMarker(
    val model: ByteArray,
    val value: ByteArray,
    val marker: ByteArray,
)

internal data class NuxieValueRule(
    val model: String,
    val property: String,
    val kind: Int,
    val mode: Int,
    val numberBound: Double,
    val text: String,
    val values: List<String>,
    val pickedProperty: String,
    val boundFlags: Int,
    val minimum: Long,
    val maximum: Long,
    val code: String,
    val message: String,
) {
    internal fun toNative() = NativeValueRule(
        model = model.encodeToByteArray(),
        property = property.encodeToByteArray(),
        kind = kind,
        mode = mode,
        numberBound = numberBound,
        text = text.encodeToByteArray(),
        values = values.map { it.encodeToByteArray() }.toTypedArray(),
        pickedProperty = pickedProperty.encodeToByteArray(),
        boundFlags = boundFlags,
        minimum = minimum,
        maximum = maximum,
        code = code.encodeToByteArray(),
        message = message.encodeToByteArray(),
    )
}

internal class NativeValueRule(
    val model: ByteArray,
    val property: ByteArray,
    val kind: Int,
    val mode: Int,
    val numberBound: Double,
    val text: ByteArray,
    val values: Array<ByteArray>,
    val pickedProperty: ByteArray,
    val boundFlags: Int,
    val minimum: Long,
    val maximum: Long,
    val code: ByteArray,
    val message: ByteArray,
)

internal data class NuxieRuleGroupMember(
    val property: String,
    val errorsPath: String,
    val itemModel: String,
    val codeProperty: String,
    val messageProperty: String,
) {
    internal fun toNative() = NativeRuleGroupMember(
        property = property.encodeToByteArray(),
        errorsPath = errorsPath.encodeToByteArray(),
        itemModel = itemModel.encodeToByteArray(),
        codeProperty = codeProperty.encodeToByteArray(),
        messageProperty = messageProperty.encodeToByteArray(),
    )
}

internal class NativeRuleGroupMember(
    val property: ByteArray,
    val errorsPath: ByteArray,
    val itemModel: ByteArray,
    val codeProperty: ByteArray,
    val messageProperty: ByteArray,
)

internal data class NuxieRuleGroup(
    val model: String,
    val valid: String,
    val members: List<NuxieRuleGroupMember>,
) {
    internal fun toNative() = NativeRuleGroup(
        model = model.encodeToByteArray(),
        valid = valid.encodeToByteArray(),
        members = members.map { it.toNative() }.toTypedArray(),
    )
}

internal class NativeRuleGroup(
    val model: ByteArray,
    val valid: ByteArray,
    val members: Array<NativeRuleGroupMember>,
)

internal object NuxieValueRuleKind {
    const val NUMBER_MINIMUM = 1
    const val NUMBER_MAXIMUM = 2
    const val TEXT_MINIMUM = 3
    const val TEXT_MAXIMUM = 4
    const val ALLOWED_VALUES = 5
    const val ITEM_COUNT = 6
    const val PICKED_COUNT = 7
    const val LENGTH = 8
    const val PATTERN = 9
    const val REQUIRED = 10
    const val URL = 11
    const val DATE = 12
}

internal object NuxieValueRuleMode {
    const val MARK = 0
    const val REFUSE = 1
}

internal object NuxieValueRuleBounds {
    const val MINIMUM = 1
    const val MAXIMUM = 2
}

internal data class NativeRuleInstallResult(val status: Int, val code: String?, val message: String?)

/** Installed before creating any instance or player in the imported file. */
internal data class NuxieValuePolicy(val rules: List<NuxieValueRule>, val groups: List<NuxieRuleGroup>) {
    fun markers(catalog: NuxieViewModelCatalog): List<NuxieValueMarker> = buildList {
        for (schema in catalog.schemas) {
            val properties = catalog.properties.filter { it.schemaIndex == schema.index }
            for (value in properties.filter { it.kind in setOf(NuxieViewModelPropertyKind.NUMBER,
                NuxieViewModelPropertyKind.BOOLEAN, NuxieViewModelPropertyKind.COLOR, NuxieViewModelPropertyKind.ENUM) }) {
                val markerName = if (value.name.startsWith("state:")) "state:isset:${value.name.removePrefix("state:")}"
                    else "isset:${value.name}"
                val marker = properties.firstOrNull { it.name == markerName } ?: continue
                check(marker.kind == NuxieViewModelPropertyKind.BOOLEAN) { "Value marker must be boolean" }
                add(NuxieValueMarker(schema.name, value.name, markerName))
            }
        }
    }
}
