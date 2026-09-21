# Native input fixture provenance

These are byte-for-byte copies of the iOS runtime qualification fixtures in
`Tests/NuxieUnitTests/Fixtures`, last changed in nuxie-ios commit
`5b0efdab3f7203bfad16f8f884d36d95a86d20c8`. Their independent geometry and
occurrence expectations come from `NuxieNativeRuntimeTests.swift`.
They are not part of the separately synchronized `fixtures/` export.

| File | SHA-256 |
| --- | --- |
| native_input_layout.riv | 9eb8e39302bf0cc5ce3f05e8256e3d107e6f357c400a3ea171479a90dd97a7cf |
| native_input_occurrences.riv | d94235991c6f20d61e08173788236139d96c7ab6be914736f3efd2a844a9446b |

Update from the iOS source fixtures, not by modifying the Android copies.

## Mounted converter fixture

`native_input_mounted.riv` is generated through Nuxie's Scene authoring API by
`tools/nuxie-editor/crates/nuxie-authoring/src/native_input_device_fixture.rs`
in the parent repository. It contains a laid-out native TextInput with semantic
identity and a two-way StringTrim binding to `State.answer`. Initial source text
is `  initial  `. Forward presentation trims it; reverse editing preserves the
entered text, following the native converter contract.

Generate with `NUXIE_NATIVE_INPUT_FIXTURE_PATH=<absolute output path> cargo test
-p nuxie-authoring --lib generate_mounted_input_fixture -- --ignored` from
`tools/nuxie-editor`. The external font is an unchanged copy of
`crates/editor/assets/nuxie-editor-ui-400.ttf`.

| File | SHA-256 |
| --- | --- |
| native_input_mounted.riv | 44c4d6025a10ca91f0a45b7e70d629bbb525a6207752206a709ca235f6d2a624 |
| native_input_font.ttf | 2898476918b21c3f9b5ba22e86853c6d63b544f92da277a92533011a28c93af5 |

This mounted test does not qualify signed release admission or custom converter
failure/recovery. Those require the full publication path.

## Published plain and secure custom converters

`native-converter/` and `native-converter-secure/` contain unchanged publication
outputs from the parent repository's
`packages/view-compiler/src/compiler-backends/rive-ios-production-artifact.test.ts`,
test `publishes a numeric duration response with a custom editable formatter`.
Export using `NUXIE_CONVERTER_DEVICE_ARTIFACT_DIR=<new absolute directory>`;
the secure case exports to that directory suffixed with `-secure`.

The fixture converts numeric seconds to minutes:seconds and reverse-converts
valid edits. Invalid edits preserve the previous numeric source, leaving the
draft available for authored validation. Secure and plain descriptors both use
binding capture. The instrumentation test admits the signed release through
the production verifier, including schema, locator identity, replay policy, and
the embedded runtime's compatibility requirements. It also verifies every staged
artifact hash and size before mounting.

The `native-converter-validated/` and `native-converter-secure-validated/`
variants add the authored editing-ended action. The publisher's real release
assembler packages and signs its compiled action artifact. It shares the
converter's parser and emits `$response_set` and `duration_ready` only when the
draft parses. Device tests use actual UI edit/blur delivery and runtime commands,
checking malformed, empty, invalid-seconds, corrected, and equivalent-value edits.
The validated cases also route the actual native outcomes through
`JourneyRuntimeEmissionCoordinator` and `JourneyService`. After each edit they
reload the disk journal and check the typed numeric response and submission
count. Invalid drafts preserve the previous answer without submitting;
corrected and equivalent-value drafts submit. Admission uses the production
catalog and installed runtime compatibility. The test supplies a local profile
and mount adapter; it does not exercise network delivery or the public app API.
