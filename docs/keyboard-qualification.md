# Docked keyboard qualification

`TextInputKeyboardDeviceTest.keyboardAvoidanceMovesTheLiveSurfaceAndEditorTogetherWithoutDrift`
requires a docked IME whose inset covers the editor's original position. It checks
that the live renderer and native editor move together, repeated geometry updates
do not accumulate translation, a rotated field remains visible and unclipped,
and hiding the IME resets the translation.

On API 34+, the test snapshots `stylus_handwriting_enabled`, disables handwriting
for this case using the instrumentation shell permission, and restores and reads
back the exact prior value in `finally`. It drops the shell permission after each
write. Older supported API levels use their normal IME setup. A floating keyboard
or an IME that does not cover the fixture's original position fails the explicit
occlusion precondition; the test does not silently pass or qualify that mode.

The test waits for geometric occlusion, not merely IME visibility. On the API 36
SwiftShader emulator, Gboard's stylus tutorial/handwriting UI reported IME visible
with a zero-height inset. The old test therefore passed its editor-above-keyboard
wait before immediately failing its required-shift assertion. Failure-time
geometry showed a focused editor at y=2160, height=144, unchanged root height=2400,
and an IME bottom inset of zero. The captured screen showed the stylus tutorial;
WindowManager reported the IME frame as `[0,2400][1080,2400]`. Disabling handwriting
made the same occlusion, alignment, rotation and reset assertions pass on the
same warm emulator without a production SDK change.

Android defines this test setting and its enabled default in
[Settings.java](https://android.googlesource.com/platform/frameworks/base/+/refs/tags/android-16.0.0_r2/core/java/android/provider/Settings.java).
This diagnosis explains the observed zero-inset reproduction; it does not attribute
every historical software-graphics failure to Gboard. Handwriting, floating IMEs,
physical keyboards/devices and other window modes require their own qualification.
