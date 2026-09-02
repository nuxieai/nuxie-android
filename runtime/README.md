# Runtime

`artifact.json` pins the published shared engine archive and its SHA-256.
Gradle verifies and extracts that archive into the gitignored `prebuilt/`
directory, records and revalidates every installed file digest, and publishes
complete trees with sibling-directory atomic renames. Interrupted installs are
recovered at the start of the next fetch or local stage. The extracted set is
subject to `size-budget.json`.

`android-toolchain.properties` is the shared Android release-toolchain pin.
Gradle, CI, and `scripts/stage-runtime.sh` use its exact NDK revision; local
staging rejects a different NDK instead of selecting whichever revision happens
to sort last on the machine.

`./gradlew verifyAndroidReleaseArtifacts` builds the release AAR, example APK,
and example AAB. It requires the complete two-ABI native library inventory,
checks every packaged ELF `PT_LOAD` segment for the 16 KiB contract, uses Build
Tools 36 `zipalign -P 16` for the APK, and requires `PAGE_ALIGNMENT_16K` in the
configuration emitted by the pinned bundletool for the actual AAB.

`runtime:boundary` scans every Kotlin and Java source set (including generated
sources) plus native sources under `src/main/cpp`. It rejects bridge imports,
simple or fully qualified bridge references, bridge typealiases, and `nux_*`
calls outside the JNI shim after removing comments. `:nuxie-android:lint`
depends on it because the boundary is a static source rule and lint is part of
the documented PR gate. This pragmatic source scanner intentionally does not
detect reflective access or inspect compiled bytecode.
