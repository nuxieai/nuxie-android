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

The opt-in probe runs the installed TalkBack service alongside UiAutomation and checks that forward swipes visit all eight signed authored identities in order, including both repeated labels and the single secure native editor. Seven backward swipes must then return through those exact identities in reverse order; every swipe must change focus. The combined traversal passed twice on API 36 with TalkBack 16.0.0.738667889. After traversal, hardware swipes return focus to Seats and real upward/downward TalkBack gestures must produce exactly one durable increment/decrement emission in order. The edit/Done assertions still use direct accessibility/native editor calls; assistive editing and spoken-output correctness remain unqualified. Signed button activation is covered below.

Use a dedicated rooted 64-bit API 34+ emulator with TalkBack installed and its primary virtio touchscreen (0..32767 axes). Inspect `adb -s emulator-5556 shell su 0 cat /sys/class/input/event1/device/name`; the selected event must report `virtio_input_multi_touch_1`. The event number may differ across emulators.

```sh
python3 scripts/test-talkback.py --serial emulator-5556 --input-device /dev/input/event1 --repeat 2
```

The primary-user driver temporarily enables TalkBack and grants its notification permission when needed to keep the service setup dialog out of test windows. It restores/read-checks all three accessibility settings plus the original notification permission and flags in a `finally` block, including failed test runs. UiAutomation uses `FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES`. Framework-injected keys/swipes did not advance TalkBack in the Android Settings control probe; timed evdev input did. The test therefore writes a hardware swipe to the explicitly validated emulator device, captures writer errors, and observes focus from the real service. Natural portrait orientation and the selected primary touchscreen are validated before input. This helper is not a physical-device input driver.

[UiAutomation shell descriptors](https://developer.android.com/reference/android/app/UiAutomation#executeShellCommandRwe(java.lang.String)) and [TalkBack navigation gestures](https://support.google.com/accessibility/android/answer/6006598) describe the underlying APIs and interaction. Physical TalkBack exploration, speech, activation, editing and transition/focus recovery remain separate acceptance work.


The hardware driver waits for Android `TYPE_TOUCH_INTERACTION_END` after each swipe. Before the first adjustment it reads, without changing, TalkBack 16's `pref_current_selector_setting_key` until the service selects `ADJUSTABLE_WIDGET` (five-second bound). Accessibility focus can be visible before this service-owned mode is ready. This explicit precondition qualifies settled slider gestures, not rapid gestures during mode changes. The combined traversal and adjustment probe passed twice on API 36 / TalkBack 16.0.0.738667889. Earlier unsynchronized probes recorded one missed increment and one no-op forward swipe; neither assertion was weakened or retried. The service preference is test-only, version-specific qualification infrastructure and is not an SDK dependency.


## Mounted-scene first publication boundary

The mounted screen withholds its whole accessibility subtree until native fields and virtual controls have been published together. The renderer reports completion through an internal listener callback; it does not change its parent's policy. The mounted screen reveals its own container once, leaving later owner policy changes untouched. Ordinary screens without `scene-semantics-v1` do not wait for this callback.

`PublishedTextInputDeviceTest.semanticSceneEntersAccessibilityOnlyAfterCompletePublication` holds rendering before mount and places a visible native probe child in the real mounted container. The Android accessibility client must not see that child before publication. After removing the probe and starting rendering, it must see the authored heading and exactly one native editor. This container-boundary regression failed on pre-change source and passes on the candidate. A companion ordinary-screen case proves there is no gate for screens without semantic support; the two-case device run passed. A first attempted oracle without the visible probe passed unchanged code because real editors initially lack geometry; that attempt was insufficient and was strengthened. Logs: `/tmp/nuxie-publication-regression-red-probe.log`, `/tmp/nuxie-publication-regression-green.log`, `/tmp/nuxie-publication-regression-pair.log`.

The refined owner-callback candidate passed ten entry-only repetitions through actual TalkBack 16 on the API 36 software-GPU emulator (`/tmp/nuxie-publication-owned-talkback-entry.log`). Earlier differential evidence for the gate: ten candidate passes, unchanged main failed on repetition three, then ten return-to-candidate passes (`/tmp/nuxie-talkback-atomic-entry-only.log`, `/tmp/nuxie-talkback-ungated-entry-only.log`, `/tmp/nuxie-talkback-gated-return-entry-only.log`). Driver settings and permissions were restored and read-verified. This supports initial-entry behavior, not stable full traversal/action qualification: a paired run still had a later no-op swipe, and slider mode latency remains unresolved. Default semantic capability admission stays disabled. Native readiness passed, and the fix was delivered in [Android PR122](https://github.com/nuxieai/nuxie-android/pull/122), adopted by [parent PR6516](https://github.com/nuxieai/nuxie-dev/pull/6516).

The driver exposes the entry-only diagnostic harness through `--scenario entry`; the publication fix and its two device regressions are already delivered. The isolated branch repeated both regressions successfully (`/tmp/nuxie-publication-isolated-device.log`).


## Actual TalkBack signed button activation and diagnostic controls

The hardware writer supports double taps as well as swipes. `--scenario activation` and `--scenario activation-error` select the existing signed semantic success/error cases and keep TalkBack running alongside UiAutomation. Each case verifies the focused authored button and requires the hardware double-tap coordinates to fall outside its bounds. Success must commit the exact ordered durable response/event batch before navigation, complete navigation, and avoid duplicates. Script failure must leave no partial response or accepted batch and destroy the presentation. The direct accessibility-action path remains available when the opt-in TalkBack argument is absent.

```sh
python3 scripts/test-talkback.py --serial emulator-5562 --input-device /dev/input/event1 --scenario activation --repeat 2
python3 scripts/test-talkback.py --serial emulator-5562 --input-device /dev/input/event1 --scenario activation-error --repeat 2
```

On rechecked Android head `46ff813` (iOS head `de71df6c`), both cases passed twice through TalkBack 16.0.0.738667889 on the dedicated API 36 ARM64 software-GPU emulator. Five complete `roles` repetitions also passed forward/backward exact-identity traversal and actual up/down slider gestures with durable ordered effects. Evidence: `/tmp/nuxie-talkback-post-publication-{roles,activation,activation-error}.log`. No production source changed for these tests. All completed drivers restored and verified the original accessibility settings and notification permission/flags.

The driver also exposes bounded controls: `entry` checks initial heading focus; `entry-after-editor` repeats presentation after actual traversal to the secure editor; `native-slider` and `virtual-slider` isolate reader entry/exit/re-entry and adjustment behavior without the published renderer. Both slider controls passed twice on the same environment (`/tmp/nuxie-talkback-post-publication-{native-slider,virtual-slider}.log`). Controls are not substitutes for signed-scene evidence. Entry probes ignore transient loading/window targets but still require the first authored focused target to be the heading; every subsequent swipe must move to the exact next authored identity.

These new passes do not erase earlier intermittent no-op swipes or software-graphics adjustable-mode failures. Speech, assistive text entry, rapid adjustments, physical devices, VoiceOver, and the remaining cross-platform acceptance matrix are still open. Default semantic capability admission remains disabled. Native readiness passes for these test-harness extensions; delivery is tracked in the SDK PR and [UNIV-3172](https://universe.basis.dev/issue/UNIV-3172).


The paired-entry probe remains red on current `46ff813`: normal logging failed on presentation two at a forward swipe (`/tmp/nuxie-talkback-post-publication-paired-entry.log`). A service-debug repetition failed Annual plan → Seats; TalkBack recognized the right-swipe but later reported no root node. Window-close timing shows that report followed test teardown, so it is not evidence of the original cause (`/tmp/nuxie-talkback-post-publication-paired-{debug,service}.log`).

A failure-only five-second observation window, retaining the original failed verdict and the same two-second focus deadline, distinguished delayed focus from a lost gesture. On presentation two, Continue → Annual plan missed the deadline at uptime 477812 ms; the expected Annual plan focus was present at 483102 ms. TalkBack logged the original gesture at 475383 ms and a successful focus action at approximately 478124 ms. No additional gesture was sent. This proves late completion for that case, not the cause of its latency or every earlier failure. The temporary observation window/logging was removed and the failure message now names the expected, previous and current controls. Original timing assertions remain unchanged. Evidence: `/tmp/nuxie-talkback-post-publication-late-focus.log` and `/tmp/nuxie-talkback-post-publication-late-focus-service.log`.

Repeated-presentation latency remains a qualification blocker despite the five standalone role passes and four signed activation passes above. Service debug settings, accessibility settings, notification permission and flags were restored/read-verified. Next diagnosis should correlate delayed accessibility handling with main-thread/render-compositor stalls without treating a teardown-caused root error as the original defect. The runtime's `keepGoing` result is explicitly not a host render/scheduling signal and must not be used as an idle-rendering shortcut.


## Rebooted graphics controls for delayed traversal

On unchanged Android `46ff813` production source with diagnostic harness `68b86ab`, three paired-entry repetitions passed on the task AVD using host GPU (`/tmp/nuxie-paired-host-control.log`). After a confirmed shutdown and reboot of that same AVD using SwiftShader, three paired repetitions passed, followed by ten more without reboot (`/tmp/nuxie-paired-software-control.log`, `/tmp/nuxie-paired-software-warm.log`). A failure-only main-thread sampling probe was armed for the software runs; it never fired, so those runs supply no new blocked-thread evidence. The probe was removed afterward.

Keeping that software emulator running, two signed script-error activations passed, followed by three more paired-entry repetitions (`/tmp/nuxie-paired-error-primer.log`, `/tmp/nuxie-paired-after-error.log`). Thus this sequence has sixteen passing software paired repetitions after reboot, not a reproduction of the earlier late-focus failure. The checks preserve the original two-second focus deadline and exact authored transitions. These results do not isolate graphics mode as the cause, do not prove a cleanup leak, and do not resolve the previously observed latency. Error teardown alone did not reproduce it in this control. The subsequent mixed-workload replay is recorded below.

All driver settings/permission restorations passed; independent secure-settings readback was services=null, accessibility_enabled=0, touch_exploration_enabled=0. No production code or timing assertion changed. The task-owned software emulator remains running for a subsequent mixed-workload replay; user emulators 5556/5558/5560 remain untouched. SDK heads were rechecked: Android `46ff813`, iOS `de71df6c`.


## Warm mixed-workload replay

The same SwiftShader emulator remained running for a full replay, with remote heads rechecked at Android `46ff813` and iOS `de71df6c`. In order, `roles --repeat 5`, `activation --repeat 2`, `activation-error --repeat 2`, `native-slider --repeat 2`, `virtual-slider --repeat 2`, and `entry-after-editor --repeat 3` all passed: sixteen tests, including six presentations in the final paired-entry tests. Logs are `/tmp/nuxie-mixed-warm-{roles,activation,activation-error,native-slider,virtual-slider,entry-after-editor}.log`.

A temporary failure-only main-thread sampling probe never fired and was removed. All original focus deadlines and exact-identity assertions were retained. Each driver restored/read-verified the original settings and notification permission/flags; independent final secure-settings readback was null/0/0. This demonstrates that the previous mixed workload alone is insufficient to reproduce the latency on this warmed AVD. It supplies no new failure stack and does not explain or resolve the earlier late completion. Further diagnosis needs timing evidence captured during a recurrence, rather than attributing the failure to error teardown or workload order. Production source remains unchanged and default semantic capability admission remains disabled.


After review, the slider controls validate the input device before launching their Activity, preventing setup failures from leaving a diagnostic window open. Both review findings are closed. The final candidate, with the temporary stack probe removed, passed one repetition each of native-slider, virtual-slider, activation, activation-error and paired entry (`/tmp/nuxie-talkback-final-{native-slider,virtual-slider,activation,activation-error,entry-after-editor}.log`); every driver restored/read-verified its settings. Native readiness passes diff check and all SDK unit/API/lint/example checks, with no required commands skipped. These final checks qualify the test-harness delivery, not the unresolved latency or full accessibility support.

## TalkBack native-editor focus across Home and return

`--scenario home-return` runs `PublishedTextInputDeviceTest.signedAuthoredRolesRestoreTalkBackEditorAfterHome` against the signed role fixture. Actual hardware traversal must reach Password, Android Home must stop the original Activity and remove its accessibility window, and return must resume that same Activity. TalkBack must restore the exact secure native editor. A forward swipe then reaches the exact next authored node in the newly published tree, and a backward swipe returns to that editor. No authored emission or response may be produced by this lifecycle sequence.

Three consecutive API 36 ARM64 SwiftShader / TalkBack 16.0.0.738667889 runs passed on Android `e5d91cc` production code (`/tmp/nuxie-talkback-home-return-current-tree.log`). Each driver restored and verified settings and notification permission/flags. The initial harness needed an assertion import and two oracle corrections: find the authored control across its label fields, then compare against its newly published node rather than a retired pre-background platform object. Both adapters withdraw virtual objects while inactive; the native editor retains its identity. Earlier assertion failures are harness evidence, not product regressions. The final test preserves the existing two-second swipe deadline and bounds focus restoration at five seconds.

This qualifies one native-editor focus path through real Home/return. It does not qualify virtual focus restoration, permission overlays, split-screen, process death, IME editing, speech or every lifecycle transition, and does not resolve the separately observed intermittent traversal latency. Capability admission remains disabled.

The existing heading-entry, paired-entry and complete roles probes each passed once after the endpoint enum refactor (`/tmp/nuxie-talkback-home-regression-{entry,entry-after-editor,roles}.log`). Native readiness passed diff check and all SDK unit/API/lint/example commands. Standards and Spec reviews found no actionable issue. No production code changed.

## Virtual-control Home/return diagnosis

Remote heads were rechecked at Android `a7130806b4212b18284711a7720b6fa677baeb57` and iOS `0a84b32475a48db097b4e8b32e05b44736801ed0`. The diagnostic `virtual-home-return` scenario reaches Seats through actual TalkBack, then repeats five Home/return cycles in the same presentation. Each requires original Activity stop/window withdrawal, same Activity resume, focus on the current published Seats node, forward traversal to the native editor and reverse traversal to Seats. No authored effects/responses may be generated. Native-editor mode still requires its original exact platform identity; virtual mode resolves the newly published authored node because inactive virtual objects are retired.

This scenario remains intermittently red and is not full qualification. Two runs failed on the initial heading-to-Continue swipe before any Home action (`/tmp/nuxie-talkback-virtual-home-device.log`, `/tmp/nuxie-talkback-virtual-home-probe.log`). A failure-only three-sample main-thread probe, after the original two-second deadline and before teardown, captured Android vsync handling followed by two `HardwareRenderer.nSyncAndDrawFrame` waits (`/tmp/nuxie-talkback-virtual-home-stacks.log`). This establishes rendering-path activity during that failure, not its root cause. The earlier log also shows skipped frames in both the test app and system UI.

After three passing single-cycle runs, a ten-repetition batch failed on repetition five after Home/return: Seats was republished but actual accessibility focus remained null at the original five-second deadline (`/tmp/nuxie-talkback-virtual-home-native-warm.log`). This is a distinct observed restoration failure, not proof it shares the initial-swipe cause. The next ten single-cycle runs passed (`/tmp/nuxie-talkback-virtual-home-restore-probe.log`), and three five-cycle runs then passed (`/tmp/nuxie-talkback-virtual-home-cycles.log`). A failure-only native-thread capture was armed for those latter controls but never triggered; no native backtrace was obtained. All temporary stack probes and post-failure waits were removed. Original test deadlines and failed verdicts were retained throughout.

Further diagnosis must distinguish renderer/compositor delay, emulator-wide scheduling and SDK/window focus timing using evidence from a recurrence. Passing repeats do not resolve the observed restoration timeout. Default semantic capability admission remains disabled; production source is unchanged.

With all temporary probes removed, the native-editor Home/return case passed once and the virtual-control case passed all five cycles once (`/tmp/nuxie-talkback-virtual-home-final-{home-return,virtual-home-return}.log`). Settings and notification permission/flags were restored/read-verified. These are final harness controls, not resolution of the documented intermittent failures.

## Retain cleared virtual focus until withdrawal

Tracing the real Home/return sequence found that TalkBack clears Seats before Activity stop withdraws the tree. At withdrawal, the prior adapter saved no target; passing runs recovered through the service's own behavior. Fifteen traced Home/return cycles showed this ordering (`/tmp/nuxie-talkback-focus-window-events.log`). These traces do not establish that every earlier timeout had this cause.

The provider now retains the last virtual focus owner, including its containing focus root and revision. Withdrawal may use that history only while the same root/revision still owns focus. A native or other scene focus event invalidates it; occurrence retirement clears it. Ordinary publication never uses this history to undo a service clear. This repairs Android event ordering within the existing owned-focus restoration contract; shared authoring and action semantics are unchanged.

`ExperienceAccessibilityProviderTest.service clearing virtual focus before withdrawal preserves the owned restoration target` failed before the fix and passes afterward. It also verifies that a normal frame does not restore cleared focus. Companion regressions prove that a native shell owner wins and retirement cancels history. All 32 provider tests passed (`/tmp/nuxie-focus-history-{red,green}.log`). Three actual TalkBack tests, each with five Home/return cycles, passed on API36 ARM64 SwiftShader (`/tmp/nuxie-focus-history-device.log`). All temporary traces were removed; settings/permission flags were restored and verified. This fixes the lost SDK restoration target; unrelated frame-rendering latency, full lifecycle qualification and default capability admission remain unresolved.


## TalkBack acquisition recovery

Run `scripts/test-talkback.py --serial <dedicated-emulator> --input-device /dev/input/event1 --scenario recovery-retry` or `--scenario recovery-close` on the documented API 34+ rooted emulator setup. Both use actual enabled TalkBack, hardware swipes to reach the native control, and a hardware double-tap at a point verified outside that control’s pointer bounds. They preserve and restore accessibility settings and TalkBack notification permission/flags using the same driver as the authored-control probes.

`PublishedTextInputDeviceTest.talkBackRetriesFailedAcquisitionInTheSameActivity` first fails signed-release artifact acquisition, reaches Retry with TalkBack, and verifies exactly two acquisition attempts, one Activity launch, the same container, and one successful reveal. `talkBackClosesSlowAcquisitionWithoutReveal` reaches Close while acquisition is held, waits for Activity close admission, and proves presentation stays pending until cancellation-resistant acquisition drains; the acquired lease closes once and no Experience-shown event is emitted.

Both probes pass on API 36 ARM64 SwiftShader with TalkBack 16.0.0.738667889 using public runtime 0.3.12. This is initial acquisition recovery, not speech-output validation, native-renderer failure recovery, outgoing-navigation recovery, all shell modes, or physical-device qualification. Existing direct-control tests continue to cover those separate lifecycle/drain contracts where documented.

## TalkBack outgoing navigation recovery

`--scenario navigation-retry` and `--scenario navigation-close` exercise recovery while the published source screen stays visible. Both first encounter destination acquisition failure, retry through TalkBack into a native import failure, then either Retry to successful navigation or Close the presentation. Each activation uses hardware traversal and a double-tap verified outside the focused button's bounds. The shared activation helper also serves initial acquisition recovery.

`PublishedTextInputDeviceTest.talkBackRetriesOutgoingAcquisitionAndNativeFailure` proves three acquisition attempts, one Activity launch, no source dismissal checkpoint during either failure, one checkpoint on successful navigation, and retirement of failed destination ownership. `talkBackClosesOutgoingNativeFailure` proves Close cancels the pending destination, retires its lease and emits one source dismissal checkpoint. Both pass on API 36 ARM64 SwiftShader with TalkBack 16.0.0.738667889 and public runtime 0.3.12. The driver restores and verifies accessibility settings and notification permission/flags.

The source remains usable by the existing navigation-recovery contract; these tests do not impose modal accessibility isolation. They qualify reaching and activating recovery controls, not speech, concurrent source editing with TalkBack, every shell mode, native initial-load recovery, or physical devices.
