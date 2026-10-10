# Android SDK builds

Bazel compiles the SDK's Kotlin/Java sources, both JNI adapters, Android resources,
lint library, JVM tests, example APK, and instrumentation APK. It packages the
release AAR with the unchanged runtime publication in `runtime/artifact.json`.
The runtime remains an imported artifact; SDK builds do not compile runtime Rust.

Use Python 3.11+, JDK 21, and the Android SDK with platform 36, build-tools
36.0.0, and NDK 29.0.14206865. Set `JAVA_HOME` and `ANDROID_HOME`. The launcher
uses an installed Bazelisk or downloads Bazelisk 1.27.0 with its release SHA-256;
`.bazelversion` pins Bazel 9.3.0. Kotlin compilation remains 2.0.21 with Java 17
bytecode and `-Xjvm-default=all`. Maven resolver lockfiles retain the owning
Gradle dependency versions, including its resolved Kotlin stdlib 2.1.20.

```sh
scripts/bazel/sdk.sh doctor
scripts/bazel/sdk.sh build
scripts/bazel/sdk.sh test
scripts/bazel/sdk.sh example
scripts/bazel/sdk.sh instrumentation
scripts/bazel/sdk.sh prepare --output build/maven-repository
```

`test` runs the ordinary SDK JVM tests and both lint rule suites. The live host
render smoke suite remains separate. Test-only harnesses and fixture inputs are
declared runfiles and are absent from the AAR and sources JAR.

`prepare` creates the AAR, sources, Javadoc, POM, Gradle module metadata and
checksum sidecars in a conventional Maven repository. `sdk-artifacts.json`
records their paths, sizes, hashes, source revision and selected runtime identity.
It replaces only the requested Maven version and preserves unchanged artifacts'
modification times. Downstream SDKs use this stable interface:

```sh
scripts/bazel/sdk.sh prepare --output /absolute/maven/repository \
  --maven-version 0.2.0-ANDROID_SOURCE_REVISION
```

The 40-character revision suffix must equal the Android checkout's HEAD, and
source-addressed or signed preparation requires a clean committed SDK checkout.
Ignored generated/build outputs are exempt. Consumers of exact source pins must
reject `sourceDirty: true`; ordinary local development preparation records it.
`--sign` uses the existing release signing environment and requires the public
pinned runtime. The version defaults to the canonical root Gradle build version.
Publication scopes retain billing and coroutines as API dependencies.

Preparation also runs the existing binary compatibility validator directly on
the Bazel AAR's `classes.jar`, comparing it against the reviewed public API dump.
That validator task has no SDK source compilation dependency. The prepared AAR
must pass the two-ABI ELF alignment and portable JNI dependency-name checks.

For local runtime development, first build its Android distribution and run
`scripts/stage-runtime.sh /absolute/runtime/checkout`. Then use
`NUXIE_RUNTIME_USE_LOCAL=1 scripts/bazel/sdk.sh build`. The import verifies both
ABI libraries and the generated header against the staged source/NDK provenance.

Gradle remains the independent public API, Android lint and AGP release-packaging
oracle. `scripts/bazel/sdk.sh check` runs Bazel tests/builds followed by the
repository's Gradle checks and 16 KiB release verification. `api-check` and `lint`
run those individual checks. `providers` retains the RevenueCat/Superwall example
build/test and provider-selection drivers; their dependencies stay outside the
SDK artifact. `host-render` runs the explicit live native smoke driver.

```sh
scripts/bazel/sdk.sh instrumentation-run --serial "$ANDROID_SERIAL" \
  --class ai.nuxie.sdk.presentation.NativeSemanticsDeviceTest
```

The instrumentation APK includes the original fixture assets, test Activities,
runner, SDK and both native ABIs. It uses the existing standalone
`ai.nuxie.sdk.test` application and can be installed/run on the device matrix.

## Worktree caches

Bazel shares action products, dependency downloads, and fetched repositories
with `nuxie-runtime` and the other Nuxie SDKs under `~/.cache/nuxie/bazel`.
Each checkout retains its own output base, `bazel-*` links, and prepared SDK
outputs. Matching compile actions reuse the shared cache across worktrees.

Set an absolute `NUXIE_BAZEL_CACHE_DIR` when using `scripts/bazel/sdk.sh` to
relocate only the reusable caches. `NUXIE_ANDROID_BAZEL_OUTPUT_BASE`, when
explicitly set, must be unique to the checkout. `NUXIE_BAZEL_OUTPUT_USER_ROOT`
can be shared: Bazel derives separate output bases underneath it for each
checkout. Run `python3 -B -m unittest discover -s scripts/bazel -p 'test_cache.py'`
for the cache override checks.

`NUXIE_BAZEL_JOBS` defaults to 2. `NUXIE_BAZEL_BIN` selects an existing launcher.
Commands require at least 5 GiB of free disk before starting.

After intentionally changing Maven inputs, repin the owning hub:

```sh
REPIN=1 bazelisk run @sdk_maven//:pin
```

The other hubs are `test_maven`, `lint_maven`, and `docs_maven`. Their classpaths
are isolated from the published SDK. Commit updated resolver lockfiles alongside
the canonical dependency change and compare against owning Gradle resolution.
