package ai.nuxie.sdk.runtime

/** Product-neutral named input changes accepted by one atomic player step. */
internal sealed interface NuxiePlayerInput {
    val name: String

    data class Boolean(override val name: String, val value: kotlin.Boolean) : NuxiePlayerInput

    data class Number(override val name: String, val value: Double) : NuxiePlayerInput

    data class Trigger(override val name: String) : NuxiePlayerInput
}

/** Exact pointer phases accepted by one atomic ProductHost player step. */
internal enum class NuxiePlayerPointerKind(val nativeValue: Int) {
    DOWN(0),
    MOVE(1),
    UP(2),
    EXIT(3),
}

/** One pointer sample already projected into authored artboard coordinates. */
internal data class NuxiePlayerPointerEvent(
    val kind: NuxiePlayerPointerKind,
    val x: Float,
    val y: Float,
    val pointerId: Int,
    val timestampSeconds: Float,
)

/** Compatibility name used by the typed renderer publication path. */
internal typealias NuxiePlayerPointer = NuxiePlayerPointerEvent

/** Fixed construction shape for `NuxPlayerInputChange`. */
internal data class NativePlayerInput(
    val kind: Int,
    val name: String,
    val boolValue: Boolean,
    val numberValue: Float,
)

/** Fixed construction shape for `NuxPlayerPointerEvent`. */
internal data class NativePlayerPointer(
    val kind: Int,
    val x: Float,
    val y: Float,
    val pointerId: Int,
    val timestampSeconds: Float,
)

internal fun encodePlayerInputs(inputs: List<NuxiePlayerInput>): List<NativePlayerInput> =
    inputs.map { input ->
        require(input.name.isNotEmpty()) { "Player input name must not be empty" }
        when (input) {
            is NuxiePlayerInput.Boolean -> NativePlayerInput(
                kind = NUX_PLAYER_INPUT_KIND_BOOLEAN,
                name = input.name,
                boolValue = input.value,
                numberValue = 0f,
            )

            is NuxiePlayerInput.Number -> {
                require(input.value.isFinite()) { "Player number input must be finite" }
                val nativeValue = input.value.toFloat()
                require(nativeValue.isFinite()) {
                    "Player number input is outside the native Float range"
                }
                NativePlayerInput(
                    kind = NUX_PLAYER_INPUT_KIND_NUMBER,
                    name = input.name,
                    boolValue = false,
                    numberValue = nativeValue,
                )
            }

            is NuxiePlayerInput.Trigger -> NativePlayerInput(
                kind = NUX_PLAYER_INPUT_KIND_TRIGGER,
                name = input.name,
                boolValue = false,
                numberValue = 0f,
            )
        }
    }

internal fun encodePlayerPointers(
    pointers: List<NuxiePlayerPointerEvent>,
): List<NativePlayerPointer> = pointers.map { pointer ->
    require(pointer.x.isFinite() && pointer.y.isFinite()) {
        "Player pointer coordinates must be finite"
    }
    require(pointer.timestampSeconds.isFinite() && pointer.timestampSeconds >= 0f) {
        "Player pointer timestamp must be finite and nonnegative"
    }
    NativePlayerPointer(
        kind = pointer.kind.nativeValue,
        x = pointer.x,
        y = pointer.y,
        pointerId = pointer.pointerId,
        timestampSeconds = pointer.timestampSeconds,
    )
}

internal sealed interface NuxieRuntimeEventPropertyValue {
    data class Number(val value: Float) : NuxieRuntimeEventPropertyValue

    data class Bool(val value: Boolean) : NuxieRuntimeEventPropertyValue

    class Bytes(val value: ByteArray) : NuxieRuntimeEventPropertyValue

    data class Color(val argb: Int) : NuxieRuntimeEventPropertyValue

    data class Enum(val ordinal: ULong) : NuxieRuntimeEventPropertyValue

    data object Trigger : NuxieRuntimeEventPropertyValue
}

internal data class NuxieRuntimeEventProperty(
    val name: String,
    val value: NuxieRuntimeEventPropertyValue,
)

internal data class NuxieRuntimeEvent(
    val localIndex: Int,
    val coreType: Int,
    val name: String,
    val url: String,
    val target: String,
    val delay: Float,
    val properties: List<NuxieRuntimeEventProperty>,
)

internal enum class NuxiePlayerPointerHit(val nativeValue: Int) {
    NONE(0),
    HIT(1),
    HIT_OPAQUE(2),
    ;

    companion object {
        fun fromNativeValue(value: Int): NuxiePlayerPointerHit? =
            entries.firstOrNull { it.nativeValue == value }
    }
}

internal sealed interface NuxieHostValue {
    data object Null : NuxieHostValue

    data class Bool(val value: Boolean) : NuxieHostValue

    data class Number(val value: Double) : NuxieHostValue

    data class String(val value: kotlin.String) : NuxieHostValue

    data class List(val values: kotlin.collections.List<NuxieHostValue>) : NuxieHostValue

    data class Object(val fields: kotlin.collections.List<Field>) : NuxieHostValue {
        data class Field(val key: kotlin.String, val value: NuxieHostValue)
    }
}

internal data class NuxieHostCommand(
    val name: String,
    val value: NuxieHostValue,
)

internal sealed interface NuxieViewModelValue {
    data object Unsupported : NuxieViewModelValue

    class Bytes(val value: ByteArray) : NuxieViewModelValue

    data class Number(val value: Float) : NuxieViewModelValue

    data class Bool(val value: Boolean) : NuxieViewModelValue

    data class Integer(val value: ULong) : NuxieViewModelValue

    data class ReferencedInstance(val id: ULong) : NuxieViewModelValue

    data class List(val values: kotlin.collections.List<ULong>) : NuxieViewModelValue
}

internal enum class NuxieViewModelChangeOrigin(val nativeValue: Int) {
    CALLER(0),
    RUNTIME(1),
    ;

    companion object {
        fun fromNativeValue(value: Int): NuxieViewModelChangeOrigin? =
            entries.firstOrNull { it.nativeValue == value }
    }
}

internal data class NuxieViewModelChange(
    val origin: NuxieViewModelChangeOrigin,
    val correlationId: ULong,
    val ownerInstanceId: ULong,
    val propertyIndex: Int,
    val value: NuxieViewModelValue,
)

internal data class NuxiePlayerStepOutcome(
    val keepGoing: Boolean,
    val pointerHits: List<NuxiePlayerPointerHit>,
    val events: List<NuxieRuntimeEvent>,
    val hostCommands: List<NuxieHostCommand>,
    val viewModelChanges: List<NuxieViewModelChange>,
)

/** JNI construction shapes copied from one owned `NuxPlayerStepResult`. */
internal data class NativePlayerStepOutcome(
    val keepGoing: Boolean,
    val pointerHits: IntArray,
    val events: Array<NativeRuntimeEvent>,
    val hostCommands: Array<NativeHostCommand>,
    val viewModelChanges: Array<NativeViewModelChange>,
)

internal data class NativeHostCommand(
    val name: String,
    val value: NativeHostValue,
)

internal data class NativeHostValue(
    val kind: Int,
    val boolValue: Boolean,
    val numberValue: Double,
    val stringValue: String,
    val listValue: Array<NativeHostValue>,
    val objectValue: Array<NativeHostField>,
)

internal data class NativeHostField(
    val key: String,
    val value: NativeHostValue,
)

internal data class NativeRuntimeEvent(
    val localIndex: Long,
    val coreType: Int,
    val name: String,
    val url: String,
    val target: String,
    val delay: Float,
    val properties: Array<NativeRuntimeEventProperty>,
)

internal data class NativeRuntimeEventProperty(
    val name: String,
    val kind: Int,
    val numberValue: Float,
    val boolValue: Boolean,
    val bytesValue: ByteArray,
    val colorValue: Int,
    val integerValue: Long,
)

internal data class NativeViewModelChange(
    val origin: Int,
    val correlationId: Long,
    val ownerInstanceId: Long,
    val propertyIndex: Long,
    val kind: Int,
    val bytesValue: ByteArray,
    val numberValue: Float,
    val integerValue: Long,
    val boolValue: Boolean,
    val referencedInstanceId: Long,
    val listItems: LongArray,
)

internal fun NativePlayerStepOutcome.toPlayerStepOutcome(): NuxiePlayerStepOutcome =
    NuxiePlayerStepOutcome(
        keepGoing = keepGoing,
        pointerHits = pointerHits.map { value ->
            checkNotNull(NuxiePlayerPointerHit.fromNativeValue(value)) {
                "Unknown native pointer result $value"
            }
        },
        events = events.map { event ->
            NuxieRuntimeEvent(
                localIndex = event.localIndex.checkedNativeIndex("event local index"),
                coreType = event.coreType,
                name = event.name,
                url = event.url,
                target = event.target,
                delay = event.delay,
                properties = event.properties.map { property ->
                    NuxieRuntimeEventProperty(
                        name = property.name,
                        value = property.toEventPropertyValue(),
                    )
                },
            )
        },
        hostCommands = hostCommands.map { command ->
            NuxieHostCommand(
                name = command.name,
                value = command.value.toHostValue(),
            )
        },
        viewModelChanges = viewModelChanges.map { change ->
            NuxieViewModelChange(
                origin = checkNotNull(NuxieViewModelChangeOrigin.fromNativeValue(change.origin)) {
                    "Unknown native view-model change origin ${change.origin}"
                },
                correlationId = change.correlationId.toULong(),
                ownerInstanceId = change.ownerInstanceId.toULong(),
                propertyIndex = change.propertyIndex.checkedNativeIndex(
                    "view-model property index",
                ),
                value = change.toViewModelValue(),
            )
        },
    )

private fun NativeHostValue.toHostValue(): NuxieHostValue = when (kind) {
    0 -> NuxieHostValue.Null
    1 -> NuxieHostValue.Bool(boolValue)
    2 -> {
        check(numberValue.isFinite()) { "Native host number must be finite" }
        NuxieHostValue.Number(numberValue)
    }
    3 -> NuxieHostValue.String(stringValue)
    4 -> NuxieHostValue.List(listValue.map(NativeHostValue::toHostValue))
    5 -> {
        val keys = objectValue.map(NativeHostField::key)
        check(keys.size == keys.toSet().size) { "Native host object contains duplicate keys" }
        NuxieHostValue.Object(
            objectValue.map { field ->
                NuxieHostValue.Object.Field(field.key, field.value.toHostValue())
            },
        )
    }
    else -> error("Unknown native host value kind $kind")
}

private fun NativeRuntimeEventProperty.toEventPropertyValue(): NuxieRuntimeEventPropertyValue =
    when (kind) {
        0 -> NuxieRuntimeEventPropertyValue.Number(numberValue)
        1 -> NuxieRuntimeEventPropertyValue.Bool(boolValue)
        2 -> NuxieRuntimeEventPropertyValue.Bytes(bytesValue.copyOf())
        3 -> NuxieRuntimeEventPropertyValue.Color(colorValue)
        4 -> NuxieRuntimeEventPropertyValue.Enum(integerValue.toULong())
        5 -> NuxieRuntimeEventPropertyValue.Trigger
        else -> error("Unknown native runtime event property kind $kind")
    }

private fun NativeViewModelChange.toViewModelValue(): NuxieViewModelValue = when (kind) {
    0 -> NuxieViewModelValue.Unsupported
    1, 12 -> NuxieViewModelValue.Bytes(bytesValue.copyOf())
    2 -> NuxieViewModelValue.Number(numberValue)
    3 -> NuxieViewModelValue.Bool(boolValue)
    9 -> NuxieViewModelValue.ReferencedInstance(referencedInstanceId.toULong())
    8 -> NuxieViewModelValue.List(listItems.map(Long::toULong))
    4, 5, 6, 7, 10, 11, 13 -> NuxieViewModelValue.Integer(integerValue.toULong())
    else -> error("Unknown native view-model value kind $kind")
}

private fun Long.checkedNativeIndex(label: String): Int {
    check(this in 0..Int.MAX_VALUE.toLong()) { "$label is out of Kotlin range: $this" }
    return toInt()
}

private const val NUX_PLAYER_INPUT_KIND_BOOLEAN = 0
private const val NUX_PLAYER_INPUT_KIND_NUMBER = 1
private const val NUX_PLAYER_INPUT_KIND_TRIGGER = 2
