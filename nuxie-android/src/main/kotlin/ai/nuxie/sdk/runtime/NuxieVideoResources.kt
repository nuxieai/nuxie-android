package ai.nuxie.sdk.runtime

/** Host-measured limits; platform-managed decoding does not imply hardware acceleration. */
internal data class NuxieVideoDecoderBudget(
    val maxPlayers: Int,
    val managedPlayers: Int,
    val hardwarePlayers: Int,
    val managedPixelsPerSecond: Long,
    val softwarePixelsPerSecond: Long,
) {
    init {
        require(maxPlayers >= 0 && managedPlayers >= 0 && hardwarePlayers >= 0)
        require(managedPixelsPerSecond >= 0 && softwarePixelsPerSecond >= 0)
    }
    fun nativeValues() = longArrayOf(maxPlayers.toLong(), managedPlayers.toLong(), hardwarePlayers.toLong(),
        managedPixelsPerSecond, softwarePixelsPerSecond)
}

internal data class NuxieVideoDecoderRequest(
    val id: Long,
    val pixelsPerSecond: Long,
    val priority: Long,
    val visible: Boolean,
    val hardwareSupported: Boolean = false,
    val softwareSupported: Boolean = false,
    val managedSupported: Boolean = true,
) {
    init { require(id >= 0 && pixelsPerSecond >= 0 && priority in 0..0xffff_ffffL) }
    fun nativeValues() = longArrayOf(id, pixelsPerSecond, priority,
        ((if (visible) 1 else 0) or (if (hardwareSupported) 2 else 0) or
            (if (softwareSupported) 4 else 0) or (if (managedSupported) 8 else 0)).toLong())
}

internal enum class NuxieVideoAllocation { Hardware, Software, Poster, PlatformManaged }
