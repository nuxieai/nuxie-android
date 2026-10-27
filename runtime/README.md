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

## Executor lifecycle qualification

The lane delegates termination callback ordering to the runtime. Observers
registered during teardown run on its worker after already registered observers;
callbacks registered after completion run inline. Kotlin retains only handle
ownership holds, so concurrent waits cannot lose the handle while cleanup is
still pending.

Run the focused executor contracts with a prepared host runtime. From a
`nuxie-runtime` checkout, build the portable library (GPU and scripting features
are unnecessary):

```sh
python3 tools/bazel/runtime.py build -p nux-capi
```

Then, from this Android SDK checkout:

```sh
NUXIE_HOST_CAPI_LIB=/absolute/path/to/nuxie-runtime/target/debug/libnux_capi.dylib \
  ./gradlew :nuxie-android:executorContractTest
```

Use `libnux_capi.so` on Linux. This target links the production JNI executor and
attachment helpers, loads the real command queue in an isolated JVM with
`-Xcheck:jni`, and runs the shared lifecycle contracts without a GPU, render
fixtures, scripting initialization, or a device. It is separate from ordinary
unit tests so an ambient host library cannot change their runtime-availability
behavior. The same contracts also run in the host rendering and Android device
suites. With pool execution, the target selects a host matching the prepared
library's platform and stages only that library, ignoring rendering-driver
settings.
