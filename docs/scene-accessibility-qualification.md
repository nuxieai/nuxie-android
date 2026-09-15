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
| Occurrence/input suspension and stale queued work | `ExperienceSurfaceHostPointerTest` |
| Native editing, semantic visibility, accessible replacement and composition | `ExperienceTextInputTest` |
| Populated Unicode JNI capture and exact TextValueRun ownership | `NativeSemanticsDeviceTest` |
| Native/virtual traversal links and injected container keyboard/cursor movement | `SemanticTraversalDeviceTest` |

Run full unit, API compatibility, lint and example assembly checks against the public runtime:

```sh
./gradlew :nuxie-android:test :nuxie-android:apiCheck :nuxie-android:lint :example-app:assembleDebug
```

Run the four semantic device cases on an API 36 emulator against the same public runtime:

```sh
ANDROID_SERIAL=emulator-5556 ./gradlew :nuxie-android:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=ai.nuxie.sdk.presentation.SemanticTraversalDeviceTest,ai.nuxie.sdk.runtime.NativeSemanticsDeviceTest
```

These device probes use imported or synthetic semantic fixtures. They establish JNI and framework behavior, not full published-screen or screen-reader behavior.

## Remaining acceptance work

The shared accessibility spec in the parent repository remains authoritative. Full published-scene control activation and ordered effects, directional entry from outside the scene, rotation/RTL/keyboard avoidance, transition focus handoff and rollback, loading/retry and identity withdrawal must be qualified. TalkBack, switch access, physical input and the UIKit/VoiceOver adapter remain required. Capability admission and publisher rollout must wait for cross-platform qualification; merging internal adapter infrastructure does not waive those requirements.
