# SDK publication

The single SDK coordinate is `ai.nuxie:nuxie-android`. The release publication
contains its AAR, Kotlin/Java API documentation, sources, POM and Gradle module
metadata. The AAR includes the native runtime for arm64-v8a and x86_64 and the
Test Store lint rule. Purchase-provider adapters remain example code.

## Local qualification

From this repository, stage the publication and build an independent consumer:

```sh
./gradlew :nuxie-android:publishReleasePublicationToStagingRepository
python3 scripts/test-maven-consumer.py --version 0.1.0
```

Use the version in the root Gradle build. Staged files live under
`build/maven-repository`. The consumer declares only the Maven coordinate; it
does not reference the SDK project or manually supply the SDK's dependencies.
It compiles the public Feature and Play product APIs, builds an R8-minified
release APK, checks both ABI inventories, and proves that published lint accepts
the debug Test Store gate and rejects unconditional enablement. Generated app
sources and logs remain under `build/maven-consumer`.

The `--repository` option accepts another repository URL for qualification of a
published candidate. The SDK coordinate resolves exclusively from that repository;
the test proves an empty selected repository fails even with a populated fallback.
Local staging is not evidence that an artifact is available
on Maven Central. An actual Central deployment and a public-repository consumer
run remain required before announcing availability.

## Signed bundle

Provide the armored release private key through `NUXIE_SIGNING_KEY` and its
password through `NUXIE_SIGNING_PASSWORD` using the release secret environment.
These are signing credentials, separate from the Central user token. With that
environment present, Gradle signs the publication in memory:

```sh
./gradlew --no-daemon :nuxie-android:publishReleasePublicationToStagingRepository
python3 scripts/bundle-maven-release.py --version 0.1.0 \
  --public-key /absolute/path/to/release-public-key.asc \
  --fingerprint FULL_RELEASE_KEY_FINGERPRINT \
  --output build/distributions/nuxie-android-0.1.0-central.zip
```

Bundle verification requires GnuPG on PATH. Supply the independently known full
release-key fingerprint. The builder verifies each artifact against that key,
checks publication identity and required metadata/content, and creates the Maven
repository layout with signatures and MD5, SHA-1, SHA-256 and SHA-512 checksums.
It does not upload or publish anything.

`python3 scripts/test-maven-signing.py` qualifies Gradle signing with a temporary
test key and rejects a wrong fingerprint, altered bytes and missing signatures.
It replaces staging signatures with test signatures; regenerate the publication
with the release key before preparing a real release bundle. The temporary
private key is removed when the test exits. No production credential is needed
for this test.

## Tagged release and Central deployment

After the SDK change passes native readiness and merges, create and push the
`vVERSION` tag at that reviewed commit and check out the tag. The build version
must match. With the release signing environment present, run:

```sh
python3 scripts/publish-maven-release.py prepare --version 0.1.0 \
  --public-key /absolute/path/to/release-public-key.asc \
  --fingerprint FULL_RELEASE_KEY_FINGERPRINT \
  --output-directory build/releases/0.1.0
```

Preparation checks the remote tag and main ancestry, rejects a dirty checkout or
local runtime override, runs native tests/API/lint/example/release assembly,
stages the signed publication, runs the independent consumer and verifies the
bundle. It records the source commit and bundle digest in `release.json`. Use a
new output directory for a new preparation attempt; retain failed logs.

Provide the Central user-token username and password as
`NUXIE_CENTRAL_USERNAME` and `NUXIE_CENTRAL_PASSWORD`, then run:

```sh
python3 scripts/publish-maven-release.py upload build/releases/0.1.0/release.json
python3 scripts/publish-maven-release.py status build/releases/0.1.0/deployment.json
python3 scripts/publish-maven-release.py publish build/releases/0.1.0/deployment.json
python3 scripts/publish-maven-release.py status build/releases/0.1.0/deployment.json
python3 scripts/publish-maven-release.py qualify-public build/releases/0.1.0/deployment.json
```

Upload requests Central validation with `USER_MANAGED`. Publish proceeds only
from `VALIDATED`; repeat status while validation/publication is in progress.
Public qualification requires `PUBLISHED`, compares every public artifact with
the prepared bundle, and builds the independent consumer from Maven Central.
Only then does the receipt record public consumer qualification.

The upload receipt is created before the request and prevents duplicate upload
attempts. If the connection fails without returning an ID, locate the receipt's
deployment name in the Central Portal, then recover with:

```sh
python3 scripts/publish-maven-release.py recover-upload \
  build/releases/0.1.0/deployment.json --deployment-id DEPLOYMENT_UUID
```

Recovery checks that Central's deployment identity matches the saved attempt.
If no matching deployment can be found, resolve the uncertain request through
Central before starting a new upload. Failed validation remains recorded in the
receipt's status response. HTTP calls do not retry automatically or forward
credentials through redirects.

`python3 -m unittest scripts/tests/test_publish_maven_release.py` exercises the
API contract and failure handling with simulated responses. It does not prove
namespace ownership, live Central validation or public release availability.

Publication uses the Android release software component so dependency scopes
are preserved. Billing and coroutines are compile dependencies because public
APIs expose their types; SQLite and serialization remain runtime dependencies.
See [Android library publication](https://developer.android.com/build/publish-library/upload-library)
and [Maven Central requirements](https://central.sonatype.org/publish/requirements/).
