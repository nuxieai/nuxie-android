# SDK confidence lanes

This source change implements [UNIV-4312](https://universe.basis.dev/issue/UNIV-4312).
It is dormant by default. `scripts/ci/pipeline.py` emits the retained complete
legacy graph in `.buildkite/lanes.json` unless the operator selects runner policy
version `2`. Platform splitting additionally requires `NUXIE_SDK_SPLIT_LANES=1`
and the final topology. Invalid versions, unknown topology and every v2 fallback
SDK graph fail before an executable native graph is uploaded.

The source uploader has mutually exclusive legacy, final-v2 and fallback-v2
composers. Final Linux jobs request `linux-native` or `linux-portable` and
`linux-14g-v2`; Apple jobs request `macos-apple` and `mac-64g-v2`. Every v2 job
uses its physical host concurrency group with eager limit one. The fallback
composer requests `temporary-light` / `linux-light-6g-v2` and uploads no native
graph. The live Buildkite **initial bootstrap** must independently select the
matching profile, capability and physical group before installed v2 hooks are
activated; source rendering cannot repair its own initial routing.

The installed root-owned policy supplies limits and verifies the live admitted
job owner for every Bazel invocation. Linux installation paths are
`/usr/local/lib/nuxie-buildkite/hooks/runner-policy.py` and
`/etc/nuxie/buildkite-runner-policy.json`; macOS uses
`/Library/NuxieBuildkite/hooks/runner-policy.py` and
`/Library/NuxieBuildkite/runner-policy.json`. The checkout bridge verifies the
whole root-controlled ancestor chain before calling the installed helper. It
requests resources without authorizing them. V2 derives distinct Bazel output bases from each owning or generated workspace
inside this checkout under the installed output-user-root. Explicit output-base
overrides remain local legacy controls and fail under v2. There is no nested whole-job
`run` wrapper: each Bazel frontend invokes the installed `run` boundary itself.

The final 14 GiB Linux profile supplies Bazel jobs 2, CPU 4, memory 7168 MiB,
one local test and bootstrap jobs 2. CI setup applies the installed Node heap
budget through `NODE_OPTIONS` (4096 MiB Linux, 8192 MiB Mac). Its installed
Gradle budget is one worker.
V2 jobs disable Gradle daemons and project parallelism, set the daemon heap to
2048 MiB and metaspace to 512 MiB, and run Kotlin compilation in process.
The worker ceiling is explicit for consumer commands and inherited by child
producer/fixture commands through `GRADLE_OPTS`. These controls are based on
[Gradle environment semantics](https://docs.gradle.org/current/userguide/build_environment.html)
and [Kotlin compiler execution strategy](https://kotlinlang.org/docs/compiler-execution-strategy.html).
They do not impose a cgroup memory ceiling; VM allocation, serialized physical
jobs and actual resource admission remain required.

`toolchain.py` validates provisioned version/path prerequisites without installing
machine tools and retains public observations. Version strings are prerequisites,
not approval of exact compiler, linker, sysroot or SDK bytes. Native actions
remain unqualified until their complete producer and consumer frontend closure,
actual toolchains, installed policy and pipeline identity have passed the owning
checks on admitted hosts. Native pins are preserved by this change. Any pinned
producer that does not honor the v2 frontend/bootstrap controls is an activation
blocker; do not silently upgrade the pin or disable enforcement.

Remote caching stays off by default, including all composer jobs. SDK writers
are always off. Read mode requires the separate operator selector
`NUXIE_CI_BAZEL_REMOTE_CACHE_MODE=read`, policy 2, explicit comma-separated
`NUXIE_CI_BAZEL_CACHE_PROFILES`, `NUXIE_BAZEL_REMOTE_CACHE_ELIGIBLE=1`, a
`sha256:<64 lowercase hex>` domain, endpoint, absolute credential path and
protected public `CA_FILE` / `TLS_PIN_FILE` inputs. The canonical helper verifies
leaf trust, endpoint, TLS pin and reader credentials before emitting flags or
credentials. Selecting split lanes does not qualify cache actions. Keep profiles
unlisted until the exact executing frontend closure and native domain are approved;
local synthetic shell fixtures do not provide that approval. The copied public
TLS helpers derive from the root consolidation source, not an operator secret
store. No transport secrets or toolchain approvals are embedded here.

Every split job performs its own required preparation and consumer checks.
There is no cross-job native transfer. A successful job appends a readiness
record under `output/pipeline/readiness/receipt.json`, binding the exact clean
checkout commit, Buildkite build/job identities, platform, configuration, host
architecture, native-pin file digests and each original producer receipt plus
its verified complete artifact inventory. `prerequisiteJobs` is empty because
these jobs are independent. This observation does not replace a native producer
receipt or authorize packaging from another job. A future packager handoff must
bind exact producing job/source/native pin/configuration/architecture/inventory
receipts before consumption.

Declared artifacts retain these records, tool observations, original native
products, test reports and unique per-invocation Bazel BEP/profile evidence.
The executable post-artifact hook calls the separately trusted installed cleanup
helper after automatic upload, preserving source and uncertain/active output.
It does not promise removal of every nested consumer cache; normal checkout
cleaning and approved host maintenance retain that responsibility.

## Owning check scope

The split graph has one Linux Android job. It retains SDK JVM/Bazel tests, SDK
build, example and instrumentation APK compilation, original Maven preparation,
Gradle SDK unit/API/lint checks, the example APK, release-artifact verification,
the real Maven/Gradle consumer and cross-SDK fixture provenance. Instrumentation
APK compilation is not an emulator execution claim. Java 21, Android API 36,
build-tools 36.0.0, NDK 29.0.14206865 and CMake 3.22.1 must be provisioned.
`runtime/artifact.json` and `runtime/android-toolchain.properties` remain the
canonical runtime/toolchain pins.

## Qualification before activation or a ready PR

Run the lightweight source/selector/receipt oracles without native tools:

```sh
python3 -B -m unittest discover -s scripts/ci -p 'test_*.py'
python3 -B -m unittest discover -s scripts/tests -p 'test_*.py'
```

Then run the canonical full owning readiness command on a positively admitted
host, and qualify the new Linux/Apple selector seams with actual native products.
Do not replace the full command with the sum of synthetic fixtures. Record exact
commands and outcomes in the PR, including toolchain provisioning and any blocked
producer qualification. Initial live routing, installed policy activation and
remote-read/native-domain approval are separate reviewed rollout steps.

```sh
./gradlew :nuxie-android:test :nuxie-android:apiCheck :nuxie-android:lint :example-app:assembleDebug
# Also execute the rendered Android SDK/APK/Maven/release/fixture lane.
```
