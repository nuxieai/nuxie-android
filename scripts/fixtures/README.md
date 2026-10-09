# Native semantic text fixture

Run `bash scripts/fixtures/generate-semantic-text.sh [runtime-checkout]` from the Android SDK checkout. The script compiles the runtime's dependency-free `nuxie-schema` library and the fixture generator with the same local `rustc`, then writes `nuxie-android/src/androidTest/assets/semantic_text.riv`. No runtime archive is built or published.

The fixture uses canonical schema keys. It contains one Text owner with TextField SemanticData, the Unicode label `Prénom 👋`, two TextValueRuns including `field/名前`, and a non-rendering CustomPropertyString named `editable/名前`. It requires no font and tests semantic ownership/JNI encoding, not visible glyph rendering. Its private text payload is fixture data.

Generated against runtime `b0775349f8a6706cd94943e4e932055fbab44e07`. SHA-256: `fe28bef78da518159aaed81de8007feff6d56a783024d87b1c1dc6c8c6d3f7e1`.

`NativeSemanticsDeviceTest.populatedNodesAndUnicodeTextOwnershipCrossJni` imports and presents the fixture through the Android Vulkan renderer, reads the populated snapshot through JNI, checks its role/label/parent, and resolves the exact Unicode run name to its semantic owner. It also reads and edits the non-rendering value, checks stale-capture rejection, and verifies Unicode and empty values without changing semantic text. Until the runtime artifact pin includes the field-string API, stage a build of the above runtime revision and run with `NUXIE_RUNTIME_USE_LOCAL=1`.

## F5 Journey test signatures

Run `node scripts/fixtures/sign-f5-journey-tests.mjs` from the SDK root to re-sign the two test-only F5 Journey routes. The script reads the pinned F5 release and the existing rendered-input test identity, retaining the published render, response tables and native policy. It writes only public key, descriptor hashes and signatures to `nuxie-android/src/androidTest/assets/f5-journey-signatures.json`; the generated private key is never saved. These signatures avoid AndroidKeyStore's large-message signing refusal. The device test requires an exact descriptor hash match and still uses the production signature verifier. This does not republish or modify the shared fixture bytes.
