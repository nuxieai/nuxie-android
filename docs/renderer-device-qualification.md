# Renderer device qualification

Use the repository device lane graphics configuration for renderer overlap qualification on this macOS setup. The main repository `scripts/ci/android-emulator.mjs:476-494` selects host graphics with Vulkan and GLDirectMem:

```sh
emulator -avd <dedicated-avd> -no-snapshot -gpu host -feature Vulkan,GLDirectMem
```

Keep the test deadlines and workloads unchanged. Record the emulator image, graphics mode, SDK commit, runtime commit, APK checksum and each method result. A graphics-mode comparison requires stopping only the owned emulator and restarting that same AVD; preserve other devices.

At SDK a3618c3c with runtime 79ea4691971958501c64f7ff55076790ec8c9c4d on API36 ARM64 (BE2A.250530.026.F3/13894323), `activeRenderingCanOverlapRendererCreation` and `activeRenderingCanOverlapRendererCreationAndResize` each failed all three runs with `-gpu swiftshader_indirect`. Timing probes showed continued creation, rendering and destruction through the 45-second deadline, with individual CPU readbacks taking several seconds. The assertion therefore cannot by itself establish a resource leak. Both unchanged methods passed all three runs with `-gpu host`, including the expected 51 renderer sets. The first host run took 42.747 seconds; subsequent runs were faster. That is a thin margin against 45 seconds, tracked under [UNIV-3971](https://universe.basis.dev/issue/UNIV-3971); a miss remains a qualification failure and must retain its timings and stacks. This qualifies this setup, not every emulator or physical device.

Track environment and driver limits in [UNIV-3971](https://universe.basis.dev/issue/UNIV-3971) and [UNIV-3173](https://universe.basis.dev/issue/UNIV-3173). A native stack in a driver fence wait must be paired with operation timings before labeling it a deadlock. Do not hide a driver problem behind a global SDK or runtime mutex, longer test deadlines, fewer iterations, or skipped assertions. Physical-device qualification remains separate.

Signed-transition qualification has the same graphics dependency. With the exact same test APK at SDK 7174aaaa, `signedCustomTransitionsComposeAndRestoreSourceOnAbort` passed three host-graphics runs. The software-graphics control failed twice at the watchdog assertion (871 ms and 955 ms); its other run failed earlier at texture capture. A diagnostic handshake timed out after 702 ms against its 700 ms budget with neither endpoint complete; the outgoing completion event arrived about 2.1 seconds after the request. This is late delivery, not merely time before the stopwatch started. Keep the watchdog and completion assertions unchanged, record this limit under [UNIV-3974](https://universe.basis.dev/issue/UNIV-3974), and do not infer a runtime source regression or physical-device behavior from the emulator comparison.

A follow-up phase probe narrows that observation: the outgoing phase applied at watchdog start and the incoming phase 23 ms later. Native completion events emerged at 699 ms and 700 ms, but the outgoing event reached its waiter at 820 ms, after rendering and publication. iOS awaits phase application and a zero-delta step before its watchdog; Round 1 Android queued phase application while preserving an in-flight frame's model revision. The probe does not establish equivalence between those sequences, but phase-write delay alone does not explain the measured late delivery. Round 2 corrects the sequencing difference by awaiting phase writes and their zero-delta step before starting the unchanged watchdog. This fixes budget consumption by pending frames; it does not establish that every driver delivery delay is resolved.

The round 1 69-method sample used `-gpu host` alone, without the lane feature flags. Those results are a sample from four device classes, not the full device suite. Software comparison failures and intermittent host-graphics destruction/recreation failures remain open.

## Qualification limits after round 2

[UNIV-3977](https://universe.basis.dev/issue/UNIV-3977) and [UNIV-3974](https://universe.basis.dev/issue/UNIV-3974) are not qualified on the lane flags: their transition methods fail before the transition starts, at the provisional-screen pixel assertion. A passing custom-phase unit test does not qualify those device paths. The source/flags control preserves the assertion and saves both bitmaps when pixels change.

The phase-write correction applies to custom transitions. The single-screen exit handshake still starts its watchdog before its fire-and-forget exit write lands, unlike iOS. This remains outside the claimed correction.

The custom-phase frame wait has no elapsed-time bound. Native frame completion, hidden submission retirement or settlement, host failure or release, and caller cancellation settle or cancel it. A permanently pending visible frame can wait indefinitely, matching the iOS reference. No timeout is added by this carry.
