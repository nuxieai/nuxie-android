# Runtime

`artifact.json` pins the published shared engine archive and its SHA-256.
Gradle verifies and extracts that archive into the gitignored `prebuilt/`
directory, subject to `size-budget.json`.
