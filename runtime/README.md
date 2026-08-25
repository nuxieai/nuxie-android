# Runtime

`artifact.json` pins the published shared engine archive and its SHA-256.
Gradle verifies and extracts that archive into the gitignored `prebuilt/`
directory, records and revalidates every installed file digest, and publishes
complete trees with sibling-directory atomic renames. Interrupted installs are
recovered at the start of the next fetch or local stage. The extracted set is
subject to `size-budget.json`.

`runtime:boundary` scans every Kotlin and Java source set (including generated
sources) plus native sources under `src/main/cpp`. It rejects bridge imports,
simple or fully qualified bridge references, bridge typealiases, and `nux_*`
calls outside the JNI shim after removing comments. `:nuxie-android:lint`
depends on it because the boundary is a static source rule and lint is part of
the documented PR gate. This pragmatic source scanner intentionally does not
detect reflective access or inspect compiled bytecode.
