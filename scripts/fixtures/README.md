# Native semantic text fixture

Run `bash scripts/fixtures/generate-semantic-text.sh [runtime-checkout]` from the Android SDK checkout. The script compiles the runtime's dependency-free `nuxie-schema` library and the fixture generator with the same local `rustc`, then writes `nuxie-android/src/androidTest/assets/semantic_text.riv`. No runtime archive is built or published.

The fixture uses canonical schema keys. It contains one Text owner with TextField SemanticData, the Unicode label `Prénom 👋`, and two TextValueRuns including `field/名前`. It requires no font and tests semantic ownership/JNI encoding, not visible glyph rendering. Its private text payload is fixture data.

Generated against runtime `037e9ac3a9d949e9ff3cb0e971b9628eb2a14db5`. SHA-256: `4e5cbeb29d08c54311b631d6fae83b3d132246670a3e574e24980b9570505f5f`.

`NativeSemanticsDeviceTest.populatedNodesAndUnicodeTextOwnershipCrossJni` imports and presents the fixture through the Android Vulkan renderer, reads the populated snapshot through JNI, checks its role/label/parent, resolves the exact Unicode run name to its semantic owner, and checks a missing run returns NotFound. The ordinary Gradle runtime fetch consumes the pinned public v0.3.11 artifact; no local runtime override is needed.
