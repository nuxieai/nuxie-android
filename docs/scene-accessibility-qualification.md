# Scene accessibility qualification

The internal Android adapter projects the runtime's presented semantic tree through an `AccessibilityNodeProvider`, with native EditText controls represented by their real Views. This is implementation work for [UNIV-3172](https://universe.basis.dev/issue/UNIV-3172). The SDK capability registry does not yet admit `scene-semantics-v1`; this document does not declare accessible published Experiences or parity complete.

The adapter consumes the public Android runtime v0.3.12 pinned in `runtime/artifact.json`. Snapshots and exact authored action dispatch remain owned by the runtime lane. The shared tree projects ancestor-disabled state for both virtual controls and real editors. Presented geometry, occurrence retirement and input suspension fence UI publication and actions. The UI action result reports queue admission; native validation can still reject an action later if its capture becomes stale.

## Executing evidence

| Behavior | Executing tests |
| --- | --- |
| Stable virtual identities, hierarchy validation and native-field exclusion | `ExperienceSemanticIndexTest` |
| Role/state/secure-value projection, focus, traversal and keyboard navigation | `ExperienceAccessibilityProviderTest` |
| Visible geometry and host-coordinate conversion | `ExperienceSemanticGeometryTest` |
| Capture ownership, malformed-copy cleanup and native actions | `NuxieSemanticSnapshotTest`, `NuxieExperiencePlayerTest` |
| Occurrence/input suspension, stale queued work, main-thread recovery and environment updates during pending presentation | `ExperienceSurfaceHostPointerTest` |
| Native editing, semantic visibility, accessible replacement and composition | `ExperienceTextInputTest` |
| Populated Unicode JNI capture and exact TextValueRun ownership | `NativeSemanticsDeviceTest` |
| Native/virtual traversal links and injected container keyboard/cursor movement | `SemanticTraversalDeviceTest` |
| Signed published authored roles, secure native editor and ordered durable adjustable/response emissions | `PublishedTextInputDeviceTest.signedAuthoredRolesExposeSecureEditorAndDurableNativeActions` (two API 36 passes) |

Run full unit, API compatibility, lint and example assembly checks against the public runtime:

```sh
./gradlew :nuxie-android:test :nuxie-android:apiCheck :nuxie-android:lint :example-app:assembleDebug
```

Run the four semantic device cases on an API 36 emulator against the same public runtime:

```sh
ANDROID_SERIAL=emulator-5556 ./gradlew :nuxie-android:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=ai.nuxie.sdk.presentation.SemanticTraversalDeviceTest,ai.nuxie.sdk.runtime.NativeSemanticsDeviceTest
```

These device probes use imported or synthetic semantic fixtures. They establish JNI and framework behavior, not full published-screen or screen-reader behavior.

The signed authored-role case uses the production-published shared fixture, candidate capability admission and the public runtime. It checks labels, selected versus checked state, disabled actions, repeated identities and one secure editor. Accessibility focus/set-text followed by the native IME Done action must save the response after authored increment and decrement emissions, without duplicate batches. It does not run TalkBack.

## Remaining acceptance work

The shared accessibility spec in the parent repository remains authoritative. Full published-scene control activation and ordered effects, directional entry from outside the scene, rotation/RTL/keyboard avoidance, transition focus handoff and rollback, loading/retry and identity withdrawal must be qualified. TalkBack, switch access, physical input and the UIKit/VoiceOver adapter remain required. Capability admission and publisher rollout must wait for cross-platform qualification; merging internal adapter infrastructure does not waive those requirements.


## Actual TalkBack emulator traversal

The opt-in probe runs the installed TalkBack service alongside UiAutomation and checks that forward swipes visit all eight signed authored identities in order, including both repeated labels and the single secure native editor. Seven backward swipes must then return through those exact identities in reverse order; every swipe must change focus. The combined traversal passed twice on API 36 with TalkBack 16.0.0.738667889. After traversal, hardware swipes return focus to Seats and real upward/downward TalkBack gestures must produce exactly one durable increment/decrement emission in order. The edit/Done assertions still use direct accessibility/native editor calls; button activation, assistive editing and spoken-output correctness remain unqualified.

Use a dedicated rooted 64-bit API 34+ emulator with TalkBack installed and its primary virtio touchscreen (0..32767 axes). Inspect `adb -s emulator-5556 shell su 0 cat /sys/class/input/event1/device/name`; the selected event must report `virtio_input_multi_touch_1`. The event number may differ across emulators.

```sh
python3 scripts/test-talkback.py --serial emulator-5556 --input-device /dev/input/event1 --repeat 2
```

The driver temporarily enables TalkBack and restores/read-checks all three accessibility settings in a `finally` block, including failed test runs. UiAutomation uses `FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES`. Framework-injected keys/swipes did not advance TalkBack in the Android Settings control probe; timed evdev input did. The test therefore writes a hardware swipe to the explicitly validated emulator device, captures writer errors, and observes focus from the real service. This helper is not a physical-device input driver.

[UiAutomation shell descriptors](https://developer.android.com/reference/android/app/UiAutomation#executeShellCommandRwe(java.lang.String)) and [TalkBack navigation gestures](https://support.google.com/accessibility/android/answer/6006598) describe the underlying APIs and interaction. Physical TalkBack exploration, speech, activation, editing and transition/focus recovery remain separate acceptance work.


The hardware driver waits for Android `TYPE_TOUCH_INTERACTION_END` after each swipe. Before the first adjustment it reads, without changing, TalkBack 16's `pref_current_selector_setting_key` until the service selects `ADJUSTABLE_WIDGET` (five-second bound). Accessibility focus can be visible before this service-owned mode is ready. This explicit precondition qualifies settled slider gestures, not rapid gestures during mode changes. The combined traversal and adjustment probe passed twice on API 36 / TalkBack 16.0.0.738667889. Earlier unsynchronized probes recorded one missed increment and one no-op forward swipe; neither assertion was weakened or retried. The service preference is test-only, version-specific qualification infrastructure and is not an SDK dependency.
