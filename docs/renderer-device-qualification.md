# Renderer device qualification

Use a dedicated Android emulator with host graphics for renderer overlap qualification on this macOS setup:

```sh
emulator -avd <dedicated-avd> -no-snapshot -gpu host
```

Keep the test deadlines and workloads unchanged. Record the emulator image, graphics mode, SDK commit, runtime commit, APK checksum and each method result. A graphics-mode comparison requires stopping only the owned emulator and restarting that same AVD; preserve other devices.

At SDK a3618c3c with runtime 79ea4691971958501c64f7ff55076790ec8c9c4d on API36 ARM64 (BE2A.250530.026.F3/13894323), `activeRenderingCanOverlapRendererCreation` and `activeRenderingCanOverlapRendererCreationAndResize` each failed all three runs with `-gpu swiftshader_indirect`. Timing probes showed continued creation, rendering and destruction through the 45-second deadline, with individual CPU readbacks taking several seconds. The assertion therefore cannot by itself establish a resource leak. Both unchanged methods passed all three runs with `-gpu host`, including the expected 51 renderer sets. The first host run took 42.747 seconds; subsequent runs were faster. This qualifies this setup, not every emulator or physical device.

Track environment and driver limits in [UNIV-3971](https://universe.basis.dev/issue/UNIV-3971) and [UNIV-3173](https://universe.basis.dev/issue/UNIV-3173). A native stack in a driver fence wait must be paired with operation timings before labeling it a deadlock. Do not hide a driver problem behind a global SDK or runtime mutex, longer test deadlines, fewer iterations, or skipped assertions. Physical-device qualification remains separate.
