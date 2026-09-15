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
published candidate. Local staging is not evidence that an artifact is available
on Maven Central. Signing, Central upload, tagged release orchestration and a
public-repository consumer run remain required before announcing availability.

Publication uses the Android release software component so dependency scopes
are preserved. Billing and coroutines are compile dependencies because public
APIs expose their types; SQLite and serialization remain runtime dependencies.
See [Android library publication](https://developer.android.com/build/publish-library/upload-library)
and [Maven Central requirements](https://central.sonatype.org/publish/requirements/).
