package ai.nuxie.sdk.runtime

internal fun setVideoValue(artboard: NuxieRuntimeArtboard, player: NuxieRuntimePlayer,
    path: String, value: NuxieViewModelScalarValue) {
    check(artboard.setDefaultViewModelValue(path, value))
    player.step(0.0)
}
